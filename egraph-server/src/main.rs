mod sql_rewrite;

use axum::{extract::Json, http::StatusCode, response::IntoResponse, routing::post, Router};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sql_rewrite::generate_equivalent_where_clauses;
use sqlparser::ast::{Query, Select, SelectItem, SetExpr, Statement};
use sqlparser::dialect::GenericDialect;
use sqlparser::parser::Parser;
use std::net::SocketAddr;
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
/// 1. Protect `ISNULL(` / `NOTNULL(` function calls ?placeholder
/// 2. Convert postfix `ISNULL` / `NOTNULL` ?`IS NULL` / `IS NOT NULL`
/// 3. Restore function calls from placeholder
/// 4. Remove `INDEXED BY ident`
fn preprocess_sqlite_sql(input: &str) -> String {
    static RE_ISNULL: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(\w+|"[^"]*"|\))\s+ISNULL\b"#).unwrap());
    static RE_NOTNULL: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(\w+|"[^"]*"|\))\s+NOTNULL\b"#).unwrap());
    static RE_INDEXED: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)\s+INDEXED\s+BY\s+(?:"[^"]*"|`[^`]*`|\w+)"#).unwrap());

    // Step 1: protect function calls ISNULL(...) / NOTNULL(...) from postfix conversion
    let s = input.replace("ISNULL(", "\x00ISNULL\x00(");
    let s = s.replace("NOTNULL(", "\x00NOTNULL\x00(");

    // Step 2: convert postfix forms
    let s = RE_ISNULL.replace_all(&s, "$1 IS NULL").to_string();
    let s = RE_NOTNULL.replace_all(&s, "$1 IS NOT NULL").to_string();

    // Step 3: restore function calls
    let s = s.replace("\x00ISNULL\x00(", "ISNULL(");
    let s = s.replace("\x00NOTNULL\x00(", "NOTNULL(");

    // Step 4: fix uppercase hex prefixes (0X??0x?
    static RE_0X: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"0X([0-9a-fA-F]+)"#).unwrap());
    let s = RE_0X.replace_all(&s, "0x$1").to_string();

    // Step 5: remove INDEXED BY xxx
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

#[cfg(test)]
mod tests {
    use super::{generate_query_variants, preprocess_sqlite_sql, prints_faithfully};
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

#[tokio::main]
async fn main() {
    let app = Router::new().route("/generate-variants", post(generate_variants));
    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("Server running on http://{}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}
