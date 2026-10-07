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

    static RE_0X: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"\b0X([0-9a-fA-F]+)"#).unwrap());
    let s = RE_0X.replace_all(&s, "0x$1").to_string();

    let s = RE_INDEXED.replace_all(&s, "").to_string();

    // Step 6: remove NOT INDEXED (SQLite hint to skip index usage)
    static RE_NOT_INDEXED: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\s+NOT\s+INDEXED\b"#).unwrap());
    RE_NOT_INDEXED.replace_all(&s, "").to_string()
}

async fn generate_variants(Json(req): Json<GenerateRequest>) -> impl IntoResponse {
    let dialect = GenericDialect {};
    let query_bytes = match base64::decode(&req.query_base64) {
        Ok(bytes) => bytes,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "Invalid base64 query"),
    };
    let query_str = match String::from_utf8(query_bytes) {
        Ok(s) => preprocess_sqlite_sql(&s),
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "Query is not valid UTF-8"),
    };
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
        Ok(Ok(v)) => v,
        Ok(Err(e)) => return error_response(StatusCode::BAD_REQUEST, &e),
        Err(_) => {
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
        SqlExpr::UnaryOp { .. } | SqlExpr::Collate { .. } => 10,
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
        | SqlExpr::ILike { .. } => 4,
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
            | (Some(p), SqlExpr::ILike { expr, pattern, .. }) => {
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
    fn refuses_operators_sqlparser_groups_differently() {
        let refused = [
            "SELECT * FROM t0, t1 WHERE t0.c0 = t1.c1 < t0.c2",
            "SELECT * FROM t0 WHERE c0 | c1 & c2",
            "SELECT * FROM t0 WHERE c0 * c1 || c2 = 'x'",
            "SELECT * FROM t0 WHERE c0 <> c1 >= 2",
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
        Parser::parse_sql(&GenericDialect {}, &preprocess_sqlite_sql(sql)).unwrap().remove(0)
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
            let s = restore_integer_hex(&variant_query.to_string(), source_sql);
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
        "{} base queries saturated and counted; {} did not saturate within the limits and were skipped",
        usable, unsaturated
    );
    for (i, (name, _)) in candidates.iter().enumerate() {
        println!("{:<34} matched {:>5}  new equalities {:>5}", name, matched[i], added[i]);
    }
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
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
