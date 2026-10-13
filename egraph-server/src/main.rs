mod sql_rewrite;

use axum::{extract::Json, http::StatusCode, response::IntoResponse, routing::post, Router};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sql_rewrite::generate_equivalent_where_clauses;
use sqlparser::ast::{
    visit_expressions, BinaryOperator, Expr as SqlExpr, Query, Select, SelectItem, SetExpr, Statement,
    UnaryOperator,
};
use sqlparser::dialect::GenericDialect;
use sqlparser::parser::Parser;
use std::net::SocketAddr;
use std::ops::ControlFlow;
use std::sync::LazyLock;

#[derive(Deserialize)]
struct GenerateRequest {
    query_base64: String,
    #[serde(default = "default_max_variants")]
    max_variants: usize,
}

fn default_max_variants() -> usize {
    3
}

#[derive(Serialize)]
struct GenerateResponse {
    variants: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

/// Preprocess SQLite-specific syntax that sqlparser's GenericDialect doesn't handle.
///
/// Uses a placeholder trick to avoid regex look-ahead:
/// 1. Convert postfix `ISNULL` / `NOTNULL` to `IS NULL` / `IS NOT NULL`
/// 2. Lower an uppercase hex prefix (`0X1F` to `0x1F`)
/// 3. Remove `INDEXED BY ident` and `NOT INDEXED`
///
/// Quoted text - string literals and quoted identifiers - is masked first so none of these text
/// rewrites reach it: on 2026-10-07 the hex rule turned the literal '...0X3O...' into '...0x3O...'.
fn preprocess_sqlite_sql(input: &str) -> String {
    let (masked, quoted) = mask_quoted(input);
    let s = preprocess_unquoted_sql(&masked);
    unmask_quoted(&s, &quoted)
}

/// Replaces every '...', "..." and `...` (with doubled-quote escapes) by \x01<index>\x02.
fn mask_quoted(input: &str) -> (String, Vec<String>) {
    let mut out = String::with_capacity(input.len());
    let mut quoted = Vec::new();
    let mut chars = input.char_indices().peekable();
    while let Some((start, c)) = chars.next() {
        if c != '\'' && c != '"' && c != '`' {
            out.push(c);
            continue;
        }
        let mut end = input.len();
        while let Some((i, d)) = chars.next() {
            if d == c {
                if matches!(chars.peek(), Some((_, e)) if *e == c) {
                    chars.next();
                } else {
                    end = i + 1;
                    break;
                }
            }
        }
        out.push_str(&format!("\x01{}\x02", quoted.len()));
        quoted.push(input[start..end].to_string());
    }
    (out, quoted)
}

fn unmask_quoted(input: &str, quoted: &[String]) -> String {
    if quoted.is_empty() {
        return input.to_string();
    }
    static RE_PLACEHOLDER: LazyLock<Regex> = LazyLock::new(|| Regex::new("\x01([0-9]+)\x02").unwrap());
    RE_PLACEHOLDER
        .replace_all(input, |c: &regex::Captures| {
            c[1].parse::<usize>().ok().and_then(|i| quoted.get(i)).cloned().unwrap_or_else(|| c[0].to_string())
        })
        .to_string()
}

fn preprocess_unquoted_sql(input: &str) -> String {
    // An operand ends in a word, a closing bracket, or a masked quoted string (\x02).
    static RE_ISNULL: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)(\w|\x02|\))\s+ISNULL\b"#).unwrap());
    static RE_NOTNULL: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)(\w|\x02|\))\s+NOTNULL\b"#).unwrap());
    static RE_INDEXED: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\s+INDEXED\s+BY\s+(?:\x01[0-9]+\x02|\w+)"#).unwrap());
    let s = RE_ISNULL.replace_all(input, "$1 IS NULL").to_string();
    let s = RE_NOTNULL.replace_all(&s, "$1 IS NOT NULL").to_string();

    // SQLite's `x IS y` and `x IS NOT y` compare any two expressions; sqlparser only knows IS
    // [NOT] NULL/TRUE/FALSE/DISTINCT FROM and refused the rest, so each such check got no variants.
    // IS [NOT] DISTINCT FROM is the same comparison in both, and SQLite has accepted that spelling
    // since 3.39. (The regex crate has no lookahead, so the next token is captured and judged.)
    static RE_IS: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\bIS(\s+NOT)?\s+(\w+|[^\w\s])"#).unwrap());
    let s = RE_IS
        .replace_all(&s, |c: &regex::Captures| {
            let next = c[2].to_ascii_uppercase();
            let negated = c.get(1).is_some();
            let keeps = matches!(next.as_str(), "NULL" | "TRUE" | "FALSE" | "DISTINCT")
                || (!negated && next == "NOT");
            if keeps {
                c[0].to_string()
            } else if negated {
                format!("IS DISTINCT FROM {}", &c[2])
            } else {
                format!("IS NOT DISTINCT FROM {}", &c[2])
            }
        })
        .to_string();

    // The workload prints binary comparisons as ((l)OP(r)) with no spaces around OP, so `x IS y`
    // and `x IS NOT y` reach us as `x IS(y)` and `x IS NOT(y)`. RE_IS needs whitespace after IS
    // and reads the bare NOT of the second shape as its next token, so both slipped through and
    // sqlparser refused a '(' right after IS. SQLite reads IS and IS NOT DISTINCT FROM as the same
    // comparison either way.
    static RE_IS_PAREN: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\bIS(\s+NOT)?\("#).unwrap());
    let s = RE_IS_PAREN
        .replace_all(&s, |c: &regex::Captures| {
            if c.get(1).is_some() {
                "IS DISTINCT FROM (".to_string()
            } else {
                "IS NOT DISTINCT FROM (".to_string()
            }
        })
        .to_string();

    // SQLite allows an empty list, `x IN ()`, and sqlparser does not: it was the commonest refusal
    // of the 2026-10-07 long run (2265 logged). An empty subquery is the same set - x IN () is 0
    // and x NOT IN () is 1 for every x, NULL included, measured both ways.
    // Chained COLLATE, `x COLLATE a COLLATE b`, is a chain of suffix operators in SQLite and the
    // rightmost name decides; sqlparser parses only one. Fold the chain to the rightmost.
    static RE_COLLATE_CHAIN: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\bCOLLATE\s+\w+(?:\s+COLLATE\s+\w+)+"#).unwrap());
    let s = RE_COLLATE_CHAIN
        .replace_all(&s, |c: &regex::Captures| {
            static RE_LAST: LazyLock<Regex> =
                LazyLock::new(|| Regex::new(r#"(?i)\bCOLLATE\s+(\w+)\s*$"#).unwrap());
            let name = RE_LAST.captures(&c[0]).unwrap()[1].to_string();
            format!("COLLATE {}", name)
        })
        .to_string();
    // GLOB is SQLite's; sqlparser has no such operator and refused every query with one. It does
    // have SIMILAR TO, which SQLite has not and which sits at the same precedence as LIKE, so GLOB
    // travels under that name and restore_glob gives it back on the way out.
    static RE_GLOB: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"(?i)\bGLOB\b"#).unwrap());
    let s = RE_GLOB.replace_all(&s, "SIMILAR TO").to_string();

    // SQLite rewrites `x IN ()` / `x NOT IN ()` at parse time into the boolean constants
    // FALSE / TRUE (parse.y, "expr1 IN ()"), and `x IS <boolean>` is then a truth-value test,
    // not a value comparison: `'8' IS (5 IN ())` compiles to IsTrue('8') while `'8' IS 0` does
    // not, and only a TRUEFALSE on the RIGHT of IS folds that way (`FALSE IS x` stays a value
    // comparison). Spelling the empty list as an empty subquery kept the value but lost the
    // constant, so IS/IS NOT over it diverged from SQLite for text operands. Replacing the whole
    // `operand IN ()` with FALSE reproduces SQLite's own rewrite (the operand is dead either way:
    // SQLite folds `FALSE AND x` to FALSE), and the IS fold then sees the same constant SQLite
    // builds. Where the operand cannot be delimited safely the text is left alone for the
    // sqlparser refusal path.
    let s = rewrite_empty_in(&s);

    static RE_0X: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"\b0X([0-9a-fA-F]+)"#).unwrap());
    let s = RE_0X.replace_all(&s, "0x$1").to_string();

    let s = RE_INDEXED.replace_all(&s, "").to_string();

    // Step 6: remove NOT INDEXED (SQLite hint to skip index usage)
    static RE_NOT_INDEXED: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\s+NOT\s+INDEXED\b"#).unwrap());
    RE_NOT_INDEXED.replace_all(&s, "").to_string()
}

fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// Byte offset of the next keyword occurrence in `input`, matched case-insensitively with word
/// boundaries on both sides, or None. The returned offset and `offset + kw.len()` are char
/// boundaries (both bytes are ASCII), and `offset + 1..` scanning is byte-based but only ever
/// matches ASCII, so it never splits a UTF-8 character.
fn find_keyword(input: &str, kw: &str) -> Option<usize> {
    let bytes = input.as_bytes();
    let k = kw.as_bytes();
    if k.is_empty() {
        return None;
    }
    let mut i = 0;
    while i + k.len() <= bytes.len() {
        let matches = (0..k.len()).all(|j| bytes[i + j].eq_ignore_ascii_case(&k[j]));
        if matches && (i == 0 || !is_word_byte(bytes[i - 1]))
            && (i + k.len() == bytes.len() || !is_word_byte(bytes[i + k.len()]))
        {
            return Some(i);
        }
        i += 1;
    }
    None
}

/// Skip ASCII whitespace forward from `start`; the position of the first non-whitespace byte, or
/// None when the rest of the input is whitespace.
fn skip_ws(input: &str, mut start: usize) -> Option<usize> {
    let bytes = input.as_bytes();
    while start < bytes.len() && matches!(bytes[start], b' ' | b'\t' | b'\r' | b'\n') {
        start += 1;
    }
    (start < bytes.len()).then_some(start)
}

/// Walk backwards over identifier bytes and dots, from byte position `end` down to the start of
/// the token. Handles `t1.c0` and decimal numbers like `5.5`.
fn walk_back_ident(input: &str, mut end: usize) -> usize {
    let bytes = input.as_bytes();
    while end > 0 && (is_word_byte(bytes[end - 1]) || bytes[end - 1] == b'.') {
        end -= 1;
    }
    end
}

/// The operand atom directly left of the byte position `end` (which points at the start of the
/// `IN` keyword, after any whitespace has been skipped): a parenthesised group (with an optional
/// function-name prefix), a bracket identifier, a masked quoted string (`\x01N\x02`, with an
/// optional `x` blob prefix), or a bare identifier/number. Returns the half-open span of the atom.
/// The input is the quote-masked SQL, so no quote handling is needed here.
fn operand_atom_backwards(input: &str, end: usize) -> Option<(usize, usize)> {
    let bytes = input.as_bytes();
    let mut e = end;
    while e > 0 && matches!(bytes[e - 1], b' ' | b'\t' | b'\r' | b'\n') {
        e -= 1;
    }
    if e == 0 {
        return None;
    }
    let c = bytes[e - 1];
    let s = match c {
        b')' => {
            let mut depth = 0usize;
            let mut j = e;
            loop {
                if j == 0 {
                    return None; // unbalanced
                }
                match bytes[j - 1] {
                    b')' => depth += 1,
                    b'(' => {
                        depth -= 1;
                        if depth == 0 {
                            break;
                        }
                    }
                    _ => {}
                }
                j -= 1;
            }
            let mut s = j - 1;
            // A function call: `count(...)` - the name sits directly before the `(`. NOT is not
            // a function name: `NOT (5) IN ()` is NOT ((5) IN ()), and swallowing the NOT would
            // replace the whole thing with FALSE where SQLite has NOT FALSE.
            if s > 0 && is_word_byte(bytes[s - 1]) {
                let w = walk_back_ident(input, s);
                if !input[w..s].eq_ignore_ascii_case("NOT") {
                    s = w;
                }
            }
            s
        }
        b']' => {
            let mut j = e - 1;
            loop {
                if j == 0 {
                    return None; // unbalanced
                }
                if bytes[j - 1] == b'[' {
                    break;
                }
                j -= 1;
            }
            j - 1
        }
        b'\x02' => {
            // Masked quoted string placeholder: \x01<digits>\x02.
            let mut j = e - 1;
            while j > 0 && bytes[j - 1].is_ascii_digit() {
                j -= 1;
            }
            if j == 0 || bytes[j - 1] != b'\x01' {
                return None;
            }
            let mut s = j - 1;
            // A blob literal: the `x` of x'...' sits directly before the placeholder. NOT is
            // excluded for the same reason as in the group branch above.
            if s > 0 && is_word_byte(bytes[s - 1]) {
                let w = walk_back_ident(input, s);
                if !input[w..s].eq_ignore_ascii_case("NOT") {
                    s = w;
                }
            }
            s
        }
        _ if is_word_byte(c) || c == b'.' => walk_back_ident(input, e),
        _ => return None,
    };
    Some((s, e))
}

/// Whether the token left of the captured operand atom can start a new expression at a looser
/// precedence, so that the atom really is the whole left operand of the empty IN. `x IS NOT NULL
/// IN ()` folds to FALSE as one unit (IS sits at the same precedence as IN and is left-associative),
/// so a word from the IS/IN/LIKE/BETWEEN family, or anything unrecognised, is refused and the text
/// is left alone for the sqlparser refusal path.
fn operand_left_neighbour_is_safe(input: &str, atom_start: usize) -> bool {
    let bytes = input.as_bytes();
    let mut i = atom_start;
    while i > 0 && matches!(bytes[i - 1], b' ' | b'\t' | b'\r' | b'\n') {
        i -= 1;
    }
    if i == 0 {
        return true; // start of the input
    }
    let b = bytes[i - 1];
    if is_word_byte(b) {
        let w = walk_back_ident(input, i);
        let word = &input[w..i];
        if word.eq_ignore_ascii_case("NOT") {
            // NOT is the loose negation (`NOT x IN ()` is NOT (x IN ())) unless it is the NOT
            // of an IS NOT at the same precedence.
            let mut j = w;
            while j > 0 && matches!(bytes[j - 1], b' ' | b'\t' | b'\r' | b'\n') {
                j -= 1;
            }
            let w2 = walk_back_ident(input, j);
            return w2 == j || !input[w2..j].eq_ignore_ascii_case("IS");
        }
        return matches!(
            word.to_ascii_uppercase().as_str(),
            "AND"
                | "OR"
                | "WHERE"
                | "ON"
                | "WHEN"
                | "THEN"
                | "ELSE"
                | "CASE"
                | "HAVING"
                | "RETURNING"
                | "SET"
                | "BY"
                | "AS"
                | "SELECT"
                | "ALL"
                | "DISTINCT"
                | "LIMIT"
                | "OFFSET"
        );
    }
    matches!(b, b'(' | b',' | b'+' | b'-' | b'*' | b'/' | b'%' | b'&' | b'|' | b'~')
}

/// Rewrites `x IN ()` / `x NOT IN ()` to FALSE / TRUE, replicating what SQLite's parser itself
/// does with an empty list (parse.y: "expr1 IN ()" becomes a TK_TRUEFALSE constant, and `x IS
/// <boolean>` is then a truth-value test: `'8' IS (5 IN ())` compiles to IsTrue('8') while
/// `'8' IS 0` does an Eq, and only a constant on the RIGHT of IS folds that way). Spelling the
/// empty list as an empty subquery kept the value but lost the constant, which diverged for text
/// operands. The operand is replaced along with the IN because SQLite discards it too (a
/// function-carrying operand survives as `FALSE AND x`, which the planner folds to FALSE). The
/// rewrite only goes through when the operand is a single atom whose left neighbour cannot extend
/// it at IS/IN precedence; otherwise the text is left untouched for the sqlparser refusal path.
fn rewrite_empty_in(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut pos = 0usize; // scan cursor
    let mut copied = 0usize; // everything before this is already in `out`
    loop {
        let Some(rel) = find_keyword(&input[pos..], "IN") else {
            break;
        };
        let kstart = pos + rel;
        let kend = kstart + 2;
        let Some(after) = skip_ws(input, kend) else {
            break;
        };
        if input.as_bytes()[after] != b'(' {
            pos = kend;
            continue;
        }
        let Some(inner) = skip_ws(input, after + 1) else {
            pos = after + 1;
            continue;
        };
        if input.as_bytes()[inner] != b')' {
            pos = inner;
            continue;
        }
        let pend = inner + 1; // one past "IN ()"
        // The atom left of IN: either the operand itself, or the NOT of `x NOT IN ()`.
        let Some((s, e)) = operand_atom_backwards(input, kstart) else {
            pos = kend;
            continue;
        };
        let atom_is_not = input[s..e].eq_ignore_ascii_case("NOT");
        let (s, safe) = if atom_is_not {
            // NOT IN (): the real operand sits left of the NOT.
            match operand_atom_backwards(input, s) {
                Some((s2, _)) => (s2, operand_left_neighbour_is_safe(input, s2)),
                None => {
                    pos = kend;
                    continue;
                }
            }
        } else {
            (s, operand_left_neighbour_is_safe(input, s))
        };
        if !safe {
            pos = kend;
            continue;
        }
        out.push_str(&input[copied..s]);
        out.push_str(if atom_is_not { "TRUE" } else { "FALSE" });
        copied = pend;
        pos = pend;
    }
    out.push_str(&input[copied..]);
    out
}

async fn generate_variants(Json(req): Json<GenerateRequest>) -> impl IntoResponse {
    let dialect = GenericDialect {};
    let query_bytes = match base64::decode(&req.query_base64) {
        Ok(bytes) => bytes,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "Invalid base64 query"),
    };
    let raw = match String::from_utf8(query_bytes) {
        Ok(s) => s,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "Query is not valid UTF-8"),
    };
    let query_str = preprocess_sqlite_sql(&raw);
    let variants = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match Parser::parse_sql(&dialect, &query_str) {
            Ok(statements) if statements.len() == 1 && !prints_faithfully(&statements[0]) => {
                Err("the query does not survive printing: sqlparser would change a literal".to_string())
            }
            Ok(statements) if statements.len() == 1 && !groups_like_sqlite(&statements[0]) => {
                Err("sqlparser groups an unbracketed operator differently from SQLite".to_string())
            }
            Ok(statements) if statements.len() == 1 => match &statements[0] {
                Statement::Query(query) => {
                    generate_query_variants(query, req.max_variants, &query_str)
                }
                _ => Ok(Vec::new()),
            },
            Ok(_) => Err("expected exactly one SQL statement".to_string()),
            Err(e) => Err(format!("failed to parse SQL: {}", e)),
        }
    })) {
        Ok(Ok(v)) => {
            log_request(&raw, &format!("ok {}", v.len()));
            v
        }
        Ok(Err(e)) => {
            log_request(&raw, &format!("refused {}", e.split(':').next().unwrap_or("")));
            return error_response(StatusCode::BAD_REQUEST, &e);
        }
        Err(_) => {
            log_request(&raw, "panic");
            eprintln!("[ERROR] e-graph computation panicked");
            return error_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                "e-graph computation panicked",
            );
        }
    };

    (
        StatusCode::OK,
        Json(GenerateResponse {
            variants,
            error: None,
        }),
    )
        .into_response()
}

/// With EGRAPH_REQUEST_LOG set, one line per request: outcome, a tab, the query on one line. For
/// measuring what the live workload sends (`--profile` reads it back); off by default.
fn log_request(query: &str, outcome: &str) {
    static LOG: LazyLock<Option<std::sync::Mutex<std::fs::File>>> = LazyLock::new(|| {
        std::env::var("EGRAPH_REQUEST_LOG").ok().and_then(|path| {
            std::fs::OpenOptions::new().create(true).append(true).open(path).ok().map(std::sync::Mutex::new)
        })
    });
    if let Some(file) = LOG.as_ref() {
        use std::io::Write;
        let one_line = query.replace('\n', " ").replace('\r', " ").replace('\t', " ");
        if let Ok(mut f) = file.lock() {
            let _ = writeln!(f, "{}\t{}", outcome, one_line);
        }
    }
}

/// Whether printing the statement and parsing it again gives the same statement. Every variant goes
/// out through sqlparser's printer, and that printer leaves a quote unescaped when the string holds
/// two quotes in a row or a backslash before one: the value `Q''\` is printed as 'Q''\', which reads
/// back as `Q'\` (measured on sqlparser 0.62). The variant then asks about a different string, and
/// the 2026-10-03 v2 trunk run reported that sixteen times as a single-side empty mismatch. Refusing
/// such a query costs a handful of checks; anything else the printer cannot reproduce is caught too.
fn prints_faithfully(statement: &Statement) -> bool {
    match Parser::parse_sql(&GenericDialect {}, &statement.to_string()) {
        Ok(reparsed) => reparsed.len() == 1 && reparsed[0] == *statement,
        Err(_) => false,
    }
}

/// SQLite's binding strength of an operator node, higher binds tighter (lang_expr.html "Operators"):
/// OR, AND, NOT, the equality family (= <> IS IN LIKE GLOB MATCH REGEXP BETWEEN), the ordering
/// comparisons, bit operators, + -, * / %, ||, then COLLATE and the prefix operators. None for
/// anything that is an atom or brackets its own operands (function calls, CASE, CAST, subqueries).
fn sqlite_binding(expr: &SqlExpr) -> Option<u8> {
    use BinaryOperator as B;
    Some(match expr {
        SqlExpr::BinaryOp { op, .. } => match op {
            B::Or => 1,
            B::And => 2,
            B::Eq | B::NotEq | B::Match | B::Regexp => 4,
            B::Lt | B::LtEq | B::Gt | B::GtEq => 5,
            B::BitwiseAnd | B::BitwiseOr | B::PGBitwiseShiftLeft | B::PGBitwiseShiftRight => 6,
            B::Plus | B::Minus => 7,
            B::Multiply | B::Divide | B::Modulo => 8,
            B::StringConcat => 9,
            _ => return None,
        },
        SqlExpr::UnaryOp { op: UnaryOperator::Not, .. } => 3,
        // In parse.y a prefix -, + and ~ take BITNOT's precedence, above COLLATE: SQLite reads
        // `-9223372036854775808 COLLATE NOCASE` as (-9223372036854775808) COLLATE NOCASE, the
        // integer minimum, and sqlparser as -(9223372036854775808 COLLATE NOCASE), a REAL. Found by
        // e2e.py with collated literals as leaves (2026-10-08).
        SqlExpr::Collate { .. } => 10,
        SqlExpr::UnaryOp { .. } => 11,
        SqlExpr::IsNull(_)
        | SqlExpr::IsNotNull(_)
        | SqlExpr::IsTrue(_)
        | SqlExpr::IsNotTrue(_)
        | SqlExpr::IsFalse(_)
        | SqlExpr::IsNotFalse(_)
        | SqlExpr::IsDistinctFrom(..)
        | SqlExpr::IsNotDistinctFrom(..)
        | SqlExpr::InList { .. }
        | SqlExpr::InSubquery { .. }
        | SqlExpr::Between { .. }
        | SqlExpr::Like { .. }
        | SqlExpr::ILike { .. }
        | SqlExpr::SimilarTo { .. } => 4,
        _ => return None,
    })
}

/// Whether sqlparser grouped every unbracketed operator the way SQLite does.
///
/// They disagree on three pairs: = and < share a level in sqlparser but not in SQLite, & binds
/// tighter than | there but not here, and || sits with * there but above it here. `t0.c0 = t1.c1 <
/// t0.c2` is c0 = (c1 < c2) to SQLite and (c0 = c1) < c2 to sqlparser, whose variants then print
/// that reading with brackets: on 2026-10-07 all 16 variants of it returned 6 rows against 3 on
/// trunk. A single-table predicate is caught by the SQLite validator; a two-table one falls back to
/// the evaluator, which reads the same wrong tree. So such a query is refused instead.
///
/// The test: an operand that is itself an operator, written without brackets, must bind at least as
/// tightly as its parent on the left (SQLite's binary operators group left to right) and strictly
/// more tightly on the right, and an operand of a prefix or postfix operator at least as tightly.
fn groups_like_sqlite(statement: &Statement) -> bool {
    fn operand_ok(parent: u8, child: &SqlExpr, right: bool) -> bool {
        match sqlite_binding(child) {
            Some(c) if right => c > parent,
            Some(c) => c >= parent,
            None => true,
        }
    }
    visit_expressions(statement, |expr| {
        let ok = match (sqlite_binding(expr), expr) {
            (Some(p), SqlExpr::BinaryOp { left, right, .. }) => {
                operand_ok(p, left, false) && operand_ok(p, right, true)
            }
            (Some(p), SqlExpr::IsDistinctFrom(left, right))
            | (Some(p), SqlExpr::IsNotDistinctFrom(left, right)) => {
                operand_ok(p, left, false) && operand_ok(p, right, true)
            }
            (Some(p), SqlExpr::Like { expr, pattern, .. })
            | (Some(p), SqlExpr::ILike { expr, pattern, .. })
            | (Some(p), SqlExpr::SimilarTo { expr, pattern, .. }) => {
                operand_ok(p, expr, false) && operand_ok(p, pattern, true)
            }
            (Some(p), SqlExpr::Between { expr, low, high, .. }) => {
                operand_ok(p, expr, false) && operand_ok(p, low, true) && operand_ok(p, high, true)
            }
            (Some(p), SqlExpr::UnaryOp { expr, .. })
            | (Some(p), SqlExpr::Collate { expr, .. })
            | (Some(p), SqlExpr::IsNull(expr))
            | (Some(p), SqlExpr::IsNotNull(expr))
            | (Some(p), SqlExpr::IsTrue(expr))
            | (Some(p), SqlExpr::IsNotTrue(expr))
            | (Some(p), SqlExpr::IsFalse(expr))
            | (Some(p), SqlExpr::IsNotFalse(expr))
            | (Some(p), SqlExpr::InList { expr, .. })
            | (Some(p), SqlExpr::InSubquery { expr, .. }) => operand_ok(p, expr, false),
            _ => true,
        };
        if ok {
            ControlFlow::Continue(())
        } else {
            ControlFlow::Break(())
        }
    })
    .is_continue()
}

#[cfg(test)]
mod tests {
    use super::{generate_query_variants, groups_like_sqlite, preprocess_sqlite_sql, prints_faithfully};
    use sqlparser::ast::Statement;
    use sqlparser::dialect::GenericDialect;
    use sqlparser::parser::Parser;

    fn generate_variants_for_sql(sql: &str) -> Vec<String> {
        let dialect = GenericDialect {};
        let query_str = preprocess_sqlite_sql(sql);
        let statements = Parser::parse_sql(&dialect, &query_str).unwrap();
        match &statements[0] {
            Statement::Query(query) => generate_query_variants(query, 3, &query_str).unwrap(),
            _ => panic!("expected query"),
        }
    }

    #[test]
    fn keeps_sqlite_blob_hex_literals_as_blobs() {
        let variants = generate_variants_for_sql("SELECT c0 FROM t0 WHERE c0 <= x'7979'");
        assert!(!variants.is_empty());
        assert!(variants.iter().all(|v| !v.contains("0x7979")));
        assert!(variants.iter().any(|v| v.contains("X'7979'")));
    }

    #[test]
    fn keeps_sqlite_integer_hex_literals_as_integers() {
        let variants = generate_variants_for_sql("SELECT c0 FROM t0 WHERE c0 <= 0x7979");
        assert!(!variants.is_empty());
        assert!(variants.iter().all(|v| !v.contains("X'7979'")));
        assert!(variants.iter().any(|v| v.contains("0x7979")));
    }

    #[test]
    fn preprocessing_leaves_string_literals_alone() {
        // From the optmask2 trunk run: '...0X3O...' became '...0x3O...', so every variant missed the row.
        let sql = "SELECT * FROM t2 NOT INDEXED WHERE t2.c0 = 'K0X3O NOT INDEXED x ISNULL INDEXED BY i0 it''s'";
        assert_eq!(
            preprocess_sqlite_sql(sql),
            "SELECT * FROM t2 WHERE t2.c0 = 'K0X3O NOT INDEXED x ISNULL INDEXED BY i0 it''s'"
        );
        assert_eq!(
            preprocess_sqlite_sql("SELECT 0X1F, t0X1, 'a''b' ISNULL, c0 isnull, \"c 0X1\" NOTNULL FROM t INDEXED BY \"i x\""),
            "SELECT 0x1F, t0X1, 'a''b' IS NULL, c0 IS NULL, \"c 0X1\" IS NOT NULL FROM t"
        );
        let variants = generate_variants_for_sql("SELECT * FROM t2 WHERE t2.c0 = 'K0X3O'");
        assert!(!variants.is_empty());
        assert!(variants.iter().all(|v| !v.contains("0x3O")));
    }

    #[test]
    fn accepts_is_with_parenthesized_operand() {
        // The workload prints binary comparisons as ((l)OP(r)) with no spaces around OP, so
        // `x IS y` arrives as `x IS(y)`; sqlparser refused a '(' right after IS.
        let sql = "SELECT c0 FROM t0 WHERE ((c0)IS((c1)))";
        assert!(preprocess_sqlite_sql(sql).contains("IS NOT DISTINCT FROM ("), "{sql}");
        let _ = parse_one(sql);
        let sql = "SELECT c0 FROM t0 WHERE ((c0)IS NOT((c1)))";
        assert!(preprocess_sqlite_sql(sql).contains("IS DISTINCT FROM ("), "{sql}");
        let _ = parse_one(sql);
        // The spaced spellings keep working through RE_IS.
        let _ = parse_one("SELECT c0 FROM t0 WHERE c0 IS (c1)");
        let _ = parse_one("SELECT c0 FROM t0 WHERE c0 IS NOT (c1)");
    }

    #[test]
    fn folds_chained_collate_to_the_rightmost() {
        // SQLite chains COLLATE as suffix operators and the rightmost name decides; sqlparser
        // accepts only one, so the chain is folded in preprocessing.
        let sql = "SELECT * FROM rt1 WHERE 'x' COLLATE BINARY COLLATE NOCASE";
        let p = preprocess_sqlite_sql(sql);
        assert!(!p.contains("COLLATE BINARY COLLATE"), "{p}");
        assert!(p.contains("COLLATE NOCASE"), "{p}");
        let _ = parse_one(sql);
        // Three deep, mixed case, and on a column operand.
        let sql = "SELECT * FROM t0 WHERE ((c0)COLLATE rtrim COLLATE BINARY)";
        assert!(!preprocess_sqlite_sql(sql).contains("COLLATE rtrim COLLATE"), "{sql}");
        let _ = parse_one(sql);
    }

    #[test]
    fn empty_in_becomes_the_boolean_constant_sqlite_builds() {
        // SQLite rewrites x IN () to the constant FALSE at parse time (x NOT IN () to TRUE) and
        // discards the operand; replicating that keeps `x IS (y IN ())` on SQLite's truth-test
        // semantics instead of a value comparison against 0.
        let cases = [
            ("SELECT * FROM t0 WHERE (c0 IN ())", "WHERE (FALSE)"),
            ("SELECT * FROM t0 WHERE x NOT IN ()", "WHERE TRUE"),
            ("SELECT * FROM t0 WHERE NOT (5) IN ()", "WHERE NOT FALSE"),
            ("SELECT * FROM t0 WHERE count(x) IN ()", "WHERE FALSE"),
            ("SELECT * FROM t0 WHERE 'str' IN ()", "WHERE FALSE"),
            ("SELECT * FROM t0 WHERE NOT(5 IN ())", "WHERE NOT(FALSE)"),
            // IN binds tighter than prefix -, so SQLite reads -(2105896977 IN ()) and -FALSE
            // is exactly what it computes; the group parens stay outside the operand.
            ("SELECT * FROM t0 WHERE (-2105896977 IN ())", "WHERE (-FALSE)"),
            ("SELECT * FROM t0 WHERE -2105896977 IN ()", "WHERE -FALSE"),
        ];
        for (sql, want) in cases {
            let p = preprocess_sqlite_sql(sql);
            assert!(p.contains(want), "{sql} -> {p}");
        }
        // The operand cannot be delimited safely, or the list is not empty: left for the parser.
        for sql in [
            "SELECT * FROM t0 WHERE a IS NOT NULL IN ()",
            "SELECT * FROM t0 WHERE a IN b IN ()",
        ] {
            assert!(preprocess_sqlite_sql(sql).contains("IN ()"), "{sql}");
        }
        assert_eq!(
            preprocess_sqlite_sql("SELECT * FROM t0 WHERE c0 IN (1)"),
            "SELECT * FROM t0 WHERE c0 IN (1)"
        );
    }

    #[test]
    fn is_over_an_empty_in_folds_like_sqlite() {
        let sql = "SELECT c0 FROM t0 WHERE '8' IS (c0 IN ())";
        let p = preprocess_sqlite_sql(sql);
        assert!(p.contains("IS NOT DISTINCT FROM (FALSE)"), "{p}");
        let variants = generate_variants_for_sql(sql);
        assert!(!variants.is_empty(), "{}", p);
        assert!(
            variants.iter().all(|v| !v.contains("IN (SELECT 1 WHERE 0)")),
            "{:?}",
            variants
        );
        let sql = "SELECT c0 FROM t0 WHERE c0 IS NOT ((-2105896977 IN ()))";
        let variants = generate_variants_for_sql(sql);
        assert!(!variants.is_empty());
        assert!(
            variants.iter().all(|v| !v.contains("IN (SELECT 1 WHERE 0)")),
            "{:?}",
            variants
        );
    }

    #[test]
    fn refuses_operators_sqlparser_groups_differently() {
        let refused = [
            "SELECT * FROM t0, t1 WHERE t0.c0 = t1.c1 < t0.c2",
            "SELECT * FROM t0 WHERE c0 | c1 & c2",
            "SELECT * FROM t0 WHERE c0 * c1 || c2 = 'x'",
            "SELECT * FROM t0 WHERE c0 <> c1 >= 2",
            "SELECT * FROM t0 WHERE c2 = -9223372036854775808 COLLATE RTRIM",
        ];
        for sql in refused {
            assert!(!groups_like_sqlite(&parse_one(sql)), "{}", sql);
        }
        let accepted = [
            "SELECT * FROM t0, t1 WHERE (t0.c0 = t1.c1) < t0.c2",
            "SELECT * FROM t0 WHERE c0 < c1 = c2",
            "SELECT * FROM t0 WHERE c0 = 1 AND c1 < 2 OR NOT c2 IS NULL",
            "SELECT * FROM t0 WHERE -c0 * c1 + c2 || 'a' COLLATE nocase LIKE 'x%' AND c0 BETWEEN c1 + 1 AND 5",
            "SELECT * FROM t0 WHERE (((t0.c0)=('K0X3O'))) AND c1 IN (1, 2) = 1",
            "SELECT * FROM t0 WHERE c0 & c1 | c2 AND c0 << 2 >= c1",
        ];
        for sql in accepted {
            assert!(groups_like_sqlite(&parse_one(sql)), "{}", sql);
        }
    }

    fn parse_one(sql: &str) -> Statement {
        let pre = preprocess_sqlite_sql(sql);
        Parser::parse_sql(&GenericDialect {}, &pre)
            .unwrap_or_else(|e| panic!("{sql} -> {pre}: {e}"))
            .remove(0)
    }

    #[test]
    fn a_literal_the_printer_would_change_is_refused() {
        // From the v2 trunk run: the value Q''\ came back as 'Q''\', which is Q'\.
        assert!(!prints_faithfully(&parse_one(r"SELECT * FROM t0 WHERE (((t0.c0)=('Q''''\')))")));
        assert!(!prints_faithfully(&parse_one(r"SELECT * FROM t0 WHERE c0 = 'a''''b'")));
        assert!(!prints_faithfully(&parse_one(r"SELECT * FROM t0 WHERE c0 = 'a\''b'")));
    }

    #[test]
    fn ordinary_queries_survive_printing() {
        for sql in [
            "SELECT * FROM t0 WHERE c0 = 'a''b'",
            r"SELECT * FROM t0 WHERE c0 = 'a\b'",
            r"SELECT * FROM t0 WHERE c0 = 'x''\'",
            "SELECT c0 FROM t0 WHERE c0 <= x'7979' ORDER BY c0 DESC LIMIT 10",
            "SELECT * FROM t0 WHERE (t0.c0 COLLATE NOCASE) = 'abc' AND t0.c1 IN (SELECT c1 FROM t1)",
            "SELECT * FROM t0 FULL OUTER JOIN t1 ON t0.c0 = t1.c0 WHERE CAST(t0.c0 AS REAL) BETWEEN -1 AND 2",
            "SELECT * FROM t0 WHERE '甙ⱞ' = t0.c0",
        ] {
            assert!(prints_faithfully(&parse_one(sql)), "{sql}");
        }
    }
}

fn error_response(status: StatusCode, error: &str) -> axum::response::Response {
    (
        status,
        Json(GenerateResponse {
            variants: Vec::new(),
            error: Some(error.to_string()),
        }),
    )
        .into_response()
}

fn generate_query_variants(
    query: &Query,
    max_variants: usize,
    source_sql: &str,
) -> Result<Vec<String>, String> {
    if let SetExpr::Select(select) = &*query.body {
        generate_select_variants(query, select, max_variants, source_sql)
    } else {
        Ok(Vec::new())
    }
}

/// sqlparser parses SQLite's `0xABC` integer literal as Value::HexStringLiteral, and prints it back
/// as `X'ABC'` - a BLOB. The original query is rendered on the Java side and keeps `0x`, so the
/// variant silently compares an integer against a blob and the oracle reports a mismatch that is
/// not a bug. fix_hex_format already repairs this for expressions that enter the e-graph, but the
/// rest of the statement - notably a JOIN's ON clause - goes through `to_string()` untouched.
///
/// Only hex digits that really appear as `0x...` in the source are restored, and only when the
/// source does not also contain the same digits spelled as a blob literal, so a genuine `X'..'`
/// is never rewritten.
/// Undoes the GLOB -> SIMILAR TO spelling of preprocessing, outside quoted text.
fn restore_glob(rendered: &str) -> String {
    static RE_SIMILAR: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"\bSIMILAR TO\b"#).unwrap());
    let (masked, quoted) = mask_quoted(rendered);
    unmask_quoted(&RE_SIMILAR.replace_all(&masked, "GLOB"), &quoted)
}

fn restore_integer_hex(rendered: &str, source_sql: &str) -> String {
    if !rendered.contains("X'") {
        return rendered.to_string();
    }
    let lower_src = source_sql.to_ascii_lowercase();
    let mut out = String::with_capacity(rendered.len());
    let bytes = rendered.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if (bytes[i] == b'X' || bytes[i] == b'x')
            && i + 1 < bytes.len()
            && bytes[i + 1] == b'\''
            && (i == 0 || !bytes[i - 1].is_ascii_alphanumeric() && bytes[i - 1] != b'_')
        {
            if let Some(end) = rendered[i + 2..].find('\'') {
                let digits = &rendered[i + 2..i + 2 + end];
                if !digits.is_empty() && digits.chars().all(|c| c.is_ascii_hexdigit()) {
                    let lower = digits.to_ascii_lowercase();
                    let as_int = format!("0x{}", lower);
                    let as_blob = format!("x'{}'", lower);
                    if lower_src.contains(&as_int) && !lower_src.contains(&as_blob) {
                        out.push_str(&as_int);
                        i = i + 2 + end + 1;
                        continue;
                    }
                }
            }
        }
        // `bytes[i] as char` reads one byte as a code point, which is Latin-1, not UTF-8: a byte of
        // 0xE4 came out as 'ä' and re-encoded as two bytes, so any non-ASCII text in the query was
        // corrupted on its way through here. Copy whole characters instead. The scan above only ever
        // matches ASCII bytes, and those never appear inside a multi-byte sequence, so it stays put.
        let ch = rendered[i..].chars().next().expect("index is on a boundary");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn generate_select_variants(
    query: &Query,
    select: &Select,
    max_variants: usize,
    source_sql: &str,
) -> Result<Vec<String>, String> {
    let mut variant_queries: Vec<Query> = Vec::new();

    // A WHERE that compares row values gets no variants at all - see contains_row_value for why
    // every rule that is sound for scalars stops being sound once an operand is a tuple.
    if let Some(selection) = &select.selection {
        if sql_rewrite::contains_row_value(selection) {
            return Ok(Vec::new());
        }
        for expr in generate_equivalent_where_clauses(selection, source_sql, max_variants, 20)? {
            let mut variant_query = query.clone();
            if let SetExpr::Select(variant_select) = &mut *variant_query.body {
                variant_select.selection = Some(expr);
            }
            variant_queries.push(variant_query);
        }
    }

    // Subqueries in the SELECT list: their WHERE is rewritten and nothing else, so each projected
    // value stays the same. This is what reaches a query with no WHERE of its own, and bugs that
    // show up only in the projection (8ebae091a9, 1b94275d20). Leftover room only, so a query with
    // a rewritable WHERE keeps the variants it had.
    for (i, item) in select.projection.iter().enumerate() {
        if variant_queries.len() >= max_variants {
            break;
        }
        let expr = match item {
            SelectItem::UnnamedExpr(e) | SelectItem::ExprWithAlias { expr: e, .. } => e,
            _ => continue,
        };
        if sql_rewrite::contains_row_value(expr) {
            continue;
        }
        let room = max_variants - variant_queries.len();
        for v in sql_rewrite::value_subquery_variants(expr, source_sql, room, 20) {
            let mut variant_query = query.clone();
            if let SetExpr::Select(variant_select) = &mut *variant_query.body {
                variant_select.projection[i] = match item {
                    SelectItem::ExprWithAlias { alias, .. } => SelectItem::ExprWithAlias {
                        expr: v,
                        alias: alias.clone(),
                    },
                    _ => SelectItem::UnnamedExpr(v),
                };
            }
            variant_queries.push(variant_query);
        }
    }

    let original_str = query.to_string();
    Ok(variant_queries
        .into_iter()
        .filter_map(|variant_query| {
            let s = restore_glob(&restore_integer_hex(&variant_query.to_string(), source_sql));
            if s == original_str {
                None
            } else {
                Some(s)
            }
        })
        .collect())
}

/// `egraph-server --novelty rules.txt`: for each `name ; lhs => rhs`, whether the rules already in
/// the server derive it. The lhs goes into an e-graph with its variables as symbols, the current
/// rules (swaps included, so the answer is an upper bound on what they reach) saturate it, and the
/// rule is DERIVABLE when the rhs is then in the lhs's e-class. Only from the lhs: the server grows
/// variants from the query it was given, so a rule whose rhs is reachable only by starting from the
/// rhs (isnull-expand, through not-isnotnull) still adds something. NEW means not within the limits,
/// which is what a candidate rule has to show to be worth adding.
fn novelty(path: &str) {
    use egg::{RecExpr, Runner};
    let rules = sql_rewrite::make_rewrite_rules(true);
    let text = std::fs::read_to_string(path).expect("cannot read rules file");
    static VAR: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\?([A-Za-z_][A-Za-z0-9_]*)").unwrap());
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
        let Some((name, body)) = line.split_once(';') else { continue };
        let Some((lhs, rhs)) = body.split_once("=>") else { continue };
        let mut names: Vec<String> = Vec::new();
        let mut symbolise = |s: &str| {
            VAR.replace_all(s.trim(), |c: &regex::Captures| {
                let i = names.iter().position(|n| n == &c[1]).unwrap_or_else(|| {
                    names.push(c[1].to_string());
                    names.len() - 1
                });
                format!("{}", 900_001 + i)
            })
            .to_string()
        };
        let (l, r) = (symbolise(lhs), symbolise(rhs));
        let parsed: Result<(RecExpr<sql_rewrite::SqlLang>, RecExpr<sql_rewrite::SqlLang>), _> =
            l.parse().and_then(|a| r.parse().map(|b| (a, b)));
        let Ok((l, r)) = parsed else {
            println!("ERROR      {:<34} cannot parse", name.trim());
            continue;
        };
        let runner = Runner::default()
            .with_iter_limit(30)
            .with_node_limit(200_000)
            .with_time_limit(std::time::Duration::from_secs(10))
            .with_expr(&l)
            .run(&rules);
        let same = runner.egraph.lookup_expr(&r).map(|id| runner.egraph.find(id))
            == Some(runner.egraph.find(runner.roots[0]));
        println!(
            "{:<10} {:<34} {} iterations, {} nodes, stopped: {:?}",
            if same { "DERIVABLE" } else { "NEW" },
            name.trim(),
            runner.iterations.len(),
            runner.egraph.total_number_of_nodes(),
            runner.stop_reason
        );
    }
}

/// `egraph-server --usage rules.txt queries.txt`: for each candidate rule, on how many of the base
/// queries (one SELECT per line) it would add something the current rules do not. Each WHERE is
/// saturated by the current rules to a fixed point (under the server's collation guard), then the candidate
/// is applied once to a copy: a query counts when it matched, and counts as NEW EQUALITIES when the
/// application merged e-classes or added terms, i.e. made variants reachable that were not before.
/// Deterministic, unlike the variants themselves, whose extraction is random.
fn usage(rules_path: &str, queries_path: &str) {
    use egg::{Pattern, RecExpr, Rewrite};
    let candidates: Vec<(String, Rewrite<sql_rewrite::SqlLang, ()>)> = std::fs::read_to_string(rules_path)
        .expect("cannot read rules file")
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter_map(|line| {
            let (name, body) = line.split_once(';')?;
            let (lhs, rhs) = body.split_once("=>")?;
            let lhs: Pattern<sql_rewrite::SqlLang> = lhs.trim().parse().ok()?;
            let rhs: Pattern<sql_rewrite::SqlLang> = rhs.trim().parse().ok()?;
            Some((name.trim().to_string(), Rewrite::new(name.trim(), lhs, rhs).ok()?))
        })
        .collect();
    let mut matched = vec![0usize; candidates.len()];
    let mut added = vec![0usize; candidates.len()];
    let mut usable = 0usize;
    let mut unsaturated = 0usize;
    let mut convertible = 0usize;
    let mut fires = vec![0usize; candidates.len()];
    for line in std::fs::read_to_string(queries_path).expect("cannot read queries").lines() {
        let query_str = preprocess_sqlite_sql(line.trim());
        let Ok(statements) = Parser::parse_sql(&GenericDialect {}, &query_str) else { continue };
        let Some(Statement::Query(query)) = statements.first() else { continue };
        let SetExpr::Select(select) = query.body.as_ref() else { continue };
        let Some(selection) = &select.selection else { continue };
        let (rec, _): (RecExpr<sql_rewrite::SqlLang>, _) = sql_rewrite::sql_expr_to_recexpr(selection, &query_str);
        if matches!(rec.as_ref().last(), Some(sql_rewrite::SqlLang::Symbol(_)) | None) {
            continue;
        }
        let allow_swap = !sql_rewrite::compares_columns_of_unknown_collation(selection);
        // Whether the rule fires at all under the server's own budget - the question a long run
        // asks - counted on every query, saturated or not.
        let (production, _) = sql_rewrite::perform_rewrites(&rec, 20, allow_swap);
        for (i, (_, rule)) in candidates.iter().enumerate() {
            if !rule.search(&production).is_empty() {
                fires[i] += 1;
            }
        }
        convertible += 1;
        // Saturated to a fixed point, not to the server's budget: on an e-graph the current rules
        // could still grow, a redundant candidate also "adds" something, which measured 500 of 2771
        // queries for rules --novelty proves derivable.
        let runner = egg::Runner::default()
            .with_iter_limit(60)
            .with_node_limit(30_000)
            .with_time_limit(std::time::Duration::from_secs(1))
            .with_expr(&rec)
            .run(&sql_rewrite::make_rewrite_rules(allow_swap));
        if !matches!(runner.stop_reason, Some(egg::StopReason::Saturated)) {
            unsaturated += 1;
            continue;
        }
        usable += 1;
        let egraph = runner.egraph;
        for (i, (_, rule)) in candidates.iter().enumerate() {
            let matches = rule.search(&egraph);
            if matches.is_empty() {
                continue;
            }
            matched[i] += 1;
            let mut copy = egraph.clone();
            let before = (copy.number_of_classes(), copy.total_number_of_nodes());
            let changed = rule.apply(&mut copy, &matches);
            copy.rebuild();
            if !changed.is_empty() || (copy.number_of_classes(), copy.total_number_of_nodes()) != before {
                added[i] += 1;
            }
        }
    }
    println!(
        "{} queries converted; fires = matches under the server budget on all of them; matched / new          equalities = on the {} that saturated ({} did not)",
        convertible, usable, unsaturated
    );
    for (i, (name, _)) in candidates.iter().enumerate() {
        println!(
            "{:<34} fires {:>5}  matched {:>5}  new equalities {:>5}",
            name, fires[i], matched[i], added[i]
        );
    }
}

/// `egraph-server --profile requests.log`: what the workload asks of the e-graph. Reads the lines
/// log_request wrote (or bare queries), and counts outcomes, which guard stopped a predicate, the
/// language nodes the WHERE reaches, and - the point - the kinds of sub-expression that are still
/// opaque atoms, i.e. what the language cannot see into yet.
fn profile(path: &str) {
    use std::collections::BTreeMap;
    let mut outcomes: BTreeMap<String, usize> = BTreeMap::new();
    let mut paths: BTreeMap<&'static str, usize> = BTreeMap::new();
    let mut nodes: BTreeMap<String, usize> = BTreeMap::new();
    let mut atoms: BTreeMap<String, usize> = BTreeMap::new();
    let mut atom_queries: BTreeMap<String, usize> = BTreeMap::new();
    let mut total = 0usize;
    for line in std::fs::read_to_string(path).expect("cannot read").lines() {
        let (outcome, query) = match line.split_once('\t') {
            Some((o, q)) => (o.to_string(), q),
            None => ("(not logged)".to_string(), line),
        };
        if query.trim().is_empty() {
            continue;
        }
        total += 1;
        let key = outcome.split_whitespace().take(if outcome.starts_with("ok") { 1 } else { 6 }).collect::<Vec<_>>().join(" ");
        let key = if outcome.starts_with("ok 0") { "ok, no variants".to_string() } else { key };
        *outcomes.entry(key).or_default() += 1;
        let query_str = preprocess_sqlite_sql(query);
        let Ok(statements) = Parser::parse_sql(&GenericDialect {}, &query_str) else { continue };
        let Some(Statement::Query(q)) = statements.first() else { continue };
        let SetExpr::Select(select) = q.body.as_ref() else {
            *paths.entry("not a plain SELECT (compound or VALUES)").or_default() += 1;
            continue;
        };
        let Some(selection) = &select.selection else {
            *paths.entry("no WHERE").or_default() += 1;
            continue;
        };
        let path = if sql_rewrite::is_nondeterministic(selection) {
            "guard: nondeterministic, identities only"
        } else if sql_rewrite::explicit_collation_under_a_value(selection) {
            "guard: explicit COLLATE under a value, identities only"
        } else if sql_rewrite::contains_row_value(selection) {
            "row value in WHERE"
        } else {
            "rewritten by the e-graph"
        };
        *paths.entry(path).or_default() += 1;
        let (rec, symbols) = sql_rewrite::sql_expr_to_recexpr(selection, &query_str);
        let mut seen = std::collections::BTreeSet::new();
        for node in rec.as_ref() {
            match node {
                sql_rewrite::SqlLang::Symbol(k) => {
                    let kind = symbols.get(k).map(atom_kind).unwrap_or_else(|| "?".to_string());
                    *atoms.entry(kind.clone()).or_default() += 1;
                    if seen.insert(kind.clone()) {
                        *atom_queries.entry(kind).or_default() += 1;
                    }
                }
                other => {
                    let name = other.to_string();
                    let name = if name.starts_with("fn:") { "fn:*".to_string() } else { name };
                    *nodes.entry(name).or_default() += 1;
                }
            }
        }
    }
    let print = |title: &str, map: &BTreeMap<String, usize>, of: usize| {
        println!("\n== {} ==", title);
        let mut v: Vec<_> = map.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1));
        for (k, n) in v {
            println!("{:>7}  {:>5.1}%  {}", n, 100.0 * *n as f64 / of.max(1) as f64, k);
        }
    };
    println!("{} requests", total);
    print("outcome", &outcomes, total);
    let paths: BTreeMap<String, usize> = paths.into_iter().map(|(k, v)| (k.to_string(), v)).collect();
    print("what happens to the WHERE", &paths, total);
    print("atoms still opaque, by queries containing them", &atom_queries, total);
    let atom_total: usize = atoms.values().sum();
    print("atoms still opaque, by occurrences", &atoms, atom_total);
    let node_total: usize = nodes.values().sum();
    print("language nodes reached", &nodes, node_total);
}

/// A readable kind for an opaque atom: leaves are columns and literals; everything else is a shape
/// the language has no node for.
fn atom_kind(e: &sqlparser::ast::Expr) -> String {
    use sqlparser::ast::Expr as E;
    match e {
        E::Identifier(_) | E::CompoundIdentifier(_) => "leaf: column".to_string(),
        E::Value(_) => "leaf: literal".to_string(),
        E::Nested(x) => atom_kind(x),
        E::Function(f) => {
            let n = match &f.args {
                sqlparser::ast::FunctionArguments::List(l) => l.args.len(),
                _ => 0,
            };
            format!("function {}({} args)", f.name.to_string().to_lowercase(), n)
        }
        E::InSubquery { .. } => "IN (SELECT ...)".to_string(),
        E::Exists { .. } => "EXISTS (SELECT ...)".to_string(),
        E::Subquery(_) => "scalar subquery".to_string(),
        E::InList { list, .. } => format!("IN list of {} (or row value)", list.len()),
        E::Tuple(_) => "row value".to_string(),
        E::Collate { collation, .. } => format!("COLLATE {}", collation),
        E::Like { .. } => "LIKE with ESCAPE".to_string(),
        E::SimilarTo { .. } => "GLOB with ESCAPE".to_string(),
        E::Case { .. } => "CASE (other form)".to_string(),
        E::Cast { .. } => "CAST (other form)".to_string(),
        E::UnaryOp { op, .. } => format!("unary {}", op),
        E::BinaryOp { op, .. } => format!("binary operator {}", op),
        other => format!("{:?}", other).split(|c: char| !c.is_alphanumeric()).next().unwrap_or("?").to_string(),
    }
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 3 && args[1] == "--profile" {
        profile(&args[2]);
        return;
    }
    if args.len() == 3 && args[1] == "--novelty" {
        novelty(&args[2]);
        return;
    }
    if args.len() == 4 && args[1] == "--usage" {
        usage(&args[2], &args[3]);
        return;
    }
    let app = Router::new().route("/generate-variants", post(generate_variants));
    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("Server running on http://{}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}
