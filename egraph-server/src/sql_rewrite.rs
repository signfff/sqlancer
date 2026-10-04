use egg::{rewrite, EGraph, FromOp, Id, Language, RecExpr, Rewrite, Runner};
use rusqlite::Connection;
use sqlparser::ast::{
    BinaryOperator, Expr as SqlExpr, GroupByExpr, Ident, OrderByKind, Query, SelectItem, SetExpr,
    UnaryOperator,
};
use sqlparser::tokenizer::Span;
use std::collections::{HashMap, HashSet};
use std::fmt;
use std::sync::LazyLock;

/// 变体校验日志默认静默：egraph-server 常以 `cargo run --release` 前台运行，
/// 长跑时每个变体一行会把那个终端刷满。设 EGRAPH_LOG_VALIDATE=1 打开。
static LOG_VALIDATE: LazyLock<bool> = LazyLock::new(|| {
    std::env::var("EGRAPH_LOG_VALIDATE")
        .map(|v| !matches!(v.trim(), "" | "0" | "false" | "FALSE" | "False"))
        .unwrap_or(false)
});

macro_rules! validate_log {
    ($($arg:tt)*) => {
        if *LOG_VALIDATE {
            eprintln!($($arg)*);
        }
    };
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum SqlLang {
    And([Id; 2]),
    Or([Id; 2]),
    Not([Id; 1]),
    Eq([Id; 2]),
    NotEq([Id; 2]),
    Lt([Id; 2]),
    Gt([Id; 2]),
    LtEq([Id; 2]),
    GtEq([Id; 2]),
    Add([Id; 2]),
    Sub([Id; 2]),
    Mul([Id; 2]),
    Div([Id; 2]),
    Neg([Id; 1]),
    BitNot([Id; 1]),
    Between([Id; 3]),
    IsNull([Id; 1]),
    IsNotNull([Id; 1]),
    IsFalse([Id; 1]),
    IsTrue([Id; 1]),
    IsNotFalse([Id; 1]),
    IsNotTrue([Id; 1]),
    Concat([Id; 2]),    // || string concatenation
    BitAnd([Id; 2]),    // & bitwise AND
    BitOr([Id; 2]),     // | bitwise OR
    Remainder([Id; 2]), // % modulo
    Symbol(u64),
}

impl Language for SqlLang {
    fn matches(&self, other: &Self) -> bool {
        std::mem::discriminant(self) == std::mem::discriminant(other)
    }

    fn children(&self) -> &[Id] {
        match self {
            SqlLang::And(c) => c,
            SqlLang::Or(c) => c,
            SqlLang::Not(c) => c,
            SqlLang::Eq(c) => c,
            SqlLang::NotEq(c) => c,
            SqlLang::Lt(c) => c,
            SqlLang::Gt(c) => c,
            SqlLang::LtEq(c) => c,
            SqlLang::GtEq(c) => c,
            SqlLang::Add(c) => c,
            SqlLang::Sub(c) => c,
            SqlLang::Mul(c) => c,
            SqlLang::Div(c) => c,
            SqlLang::Neg(c) => c,
            SqlLang::BitNot(c) => c,
            SqlLang::Between(c) => c,
            SqlLang::IsNull(c) => c,
            SqlLang::IsNotNull(c) => c,
            SqlLang::IsFalse(c) => c,
            SqlLang::IsTrue(c) => c,
            SqlLang::IsNotFalse(c) => c,
            SqlLang::IsNotTrue(c) => c,
            SqlLang::Concat(c) => c,
            SqlLang::BitAnd(c) => c,
            SqlLang::BitOr(c) => c,
            SqlLang::Remainder(c) => c,
            SqlLang::Symbol(_) => &[],
        }
    }

    fn children_mut(&mut self) -> &mut [Id] {
        match self {
            SqlLang::And(c) => c,
            SqlLang::Or(c) => c,
            SqlLang::Not(c) => c,
            SqlLang::Eq(c) => c,
            SqlLang::NotEq(c) => c,
            SqlLang::Lt(c) => c,
            SqlLang::Gt(c) => c,
            SqlLang::LtEq(c) => c,
            SqlLang::GtEq(c) => c,
            SqlLang::Add(c) => c,
            SqlLang::Sub(c) => c,
            SqlLang::Mul(c) => c,
            SqlLang::Div(c) => c,
            SqlLang::Neg(c) => c,
            SqlLang::BitNot(c) => c,
            SqlLang::Between(c) => c,
            SqlLang::IsNull(c) => c,
            SqlLang::IsNotNull(c) => c,
            SqlLang::IsFalse(c) => c,
            SqlLang::IsTrue(c) => c,
            SqlLang::IsNotFalse(c) => c,
            SqlLang::IsNotTrue(c) => c,
            SqlLang::Concat(c) => c,
            SqlLang::BitAnd(c) => c,
            SqlLang::BitOr(c) => c,
            SqlLang::Remainder(c) => c,
            SqlLang::Symbol(_) => &mut [],
        }
    }
}

impl FromOp for SqlLang {
    type Error = String;

    fn from_op(op: &str, children: Vec<Id>) -> Result<Self, Self::Error> {
        match op {
            "and" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("and: expected 2 children"))?;
                Ok(SqlLang::And(arr))
            }
            "or" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("or: expected 2 children"))?;
                Ok(SqlLang::Or(arr))
            }
            "not" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("not: expected 1 child"))?;
                Ok(SqlLang::Not(arr))
            }
            "=" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("=: expected 2 children"))?;
                Ok(SqlLang::Eq(arr))
            }
            "<>" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("<>: expected 2 children"))?;
                Ok(SqlLang::NotEq(arr))
            }
            "<" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("<: expected 2 children"))?;
                Ok(SqlLang::Lt(arr))
            }
            ">" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!(">: expected 2 children"))?;
                Ok(SqlLang::Gt(arr))
            }
            "<=" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("<=: expected 2 children"))?;
                Ok(SqlLang::LtEq(arr))
            }
            ">=" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!(">=: expected 2 children"))?;
                Ok(SqlLang::GtEq(arr))
            }
            "+" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("+: expected 2 children"))?;
                Ok(SqlLang::Add(arr))
            }
            "-" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("-: expected 2 children"))?;
                Ok(SqlLang::Sub(arr))
            }
            "*" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("*: expected 2 children"))?;
                Ok(SqlLang::Mul(arr))
            }
            "/" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("/: expected 2 children"))?;
                Ok(SqlLang::Div(arr))
            }
            "neg" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("neg: expected 1 child"))?;
                Ok(SqlLang::Neg(arr))
            }
            "bitnot" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("bitnot: expected 1 child"))?;
                Ok(SqlLang::BitNot(arr))
            }
            "between" => {
                let arr: [Id; 3] = children
                    .try_into()
                    .map_err(|_| format!("between: expected 3 children"))?;
                Ok(SqlLang::Between(arr))
            }
            "isnull" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("isnull: expected 1 child"))?;
                Ok(SqlLang::IsNull(arr))
            }
            "isnotnull" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("isnotnull: expected 1 child"))?;
                Ok(SqlLang::IsNotNull(arr))
            }
            "isfalse" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("isfalse: expected 1 child"))?;
                Ok(SqlLang::IsFalse(arr))
            }
            "istrue" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("istrue: expected 1 child"))?;
                Ok(SqlLang::IsTrue(arr))
            }
            "isnotfalse" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("isnotfalse: expected 1 child"))?;
                Ok(SqlLang::IsNotFalse(arr))
            }
            "isnottrue" => {
                let arr: [Id; 1] = children
                    .try_into()
                    .map_err(|_| format!("isnottrue: expected 1 child"))?;
                Ok(SqlLang::IsNotTrue(arr))
            }
            "concat" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("concat: expected 2 children"))?;
                Ok(SqlLang::Concat(arr))
            }
            "bitand" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("bitand: expected 2 children"))?;
                Ok(SqlLang::BitAnd(arr))
            }
            "bitor" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("bitor: expected 2 children"))?;
                Ok(SqlLang::BitOr(arr))
            }
            "rem" => {
                let arr: [Id; 2] = children
                    .try_into()
                    .map_err(|_| format!("rem: expected 2 children"))?;
                Ok(SqlLang::Remainder(arr))
            }
            _ => {
                // Try parsing as a u64 Symbol value
                op.parse::<u64>()
                    .map(SqlLang::Symbol)
                    .map_err(|_| format!("unknown operator: '{}'", op))
            }
        }
    }
}

impl fmt::Display for SqlLang {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SqlLang::And(_) => write!(f, "and"),
            SqlLang::Or(_) => write!(f, "or"),
            SqlLang::Not(_) => write!(f, "not"),
            SqlLang::Eq(_) => write!(f, "="),
            SqlLang::NotEq(_) => write!(f, "<>"),
            SqlLang::Lt(_) => write!(f, "<"),
            SqlLang::Gt(_) => write!(f, ">"),
            SqlLang::LtEq(_) => write!(f, "<="),
            SqlLang::GtEq(_) => write!(f, ">="),
            SqlLang::Add(_) => write!(f, "+"),
            SqlLang::Sub(_) => write!(f, "-"),
            SqlLang::Mul(_) => write!(f, "*"),
            SqlLang::Div(_) => write!(f, "/"),
            SqlLang::Neg(_) => write!(f, "neg"),
            SqlLang::BitNot(_) => write!(f, "bitnot"),
            SqlLang::Between(_) => write!(f, "between"),
            SqlLang::IsNull(_) => write!(f, "isnull"),
            SqlLang::IsNotNull(_) => write!(f, "isnotnull"),
            SqlLang::IsFalse(_) => write!(f, "isfalse"),
            SqlLang::IsTrue(_) => write!(f, "istrue"),
            SqlLang::IsNotFalse(_) => write!(f, "isnotfalse"),
            SqlLang::IsNotTrue(_) => write!(f, "isnottrue"),
            SqlLang::Concat(_) => write!(f, "concat"),
            SqlLang::BitAnd(_) => write!(f, "bitand"),
            SqlLang::BitOr(_) => write!(f, "bitor"),
            SqlLang::Remainder(_) => write!(f, "rem"),
            SqlLang::Symbol(k) => write!(f, "{}", k),
        }
    }
}

pub type SymbolTable = HashMap<u64, SqlExpr>;

//  sqlparser Expr ?RecExpr<SqlLang>

pub fn sql_expr_to_recexpr(expr: &SqlExpr, source_sql: &str) -> (RecExpr<SqlLang>, SymbolTable) {
    let mut rec = RecExpr::default();
    let mut symbols = SymbolTable::new();
    let mut counter = 0u64;
    // Dedup map: identical sub-expressions (same column ref, same literal)
    // share a single Symbol node, so rules like tight-eq (x>=y AND x<=y ?x=y)
    // can bind the same variable across positions.
    let mut dedup: HashMap<String, Id> = HashMap::new();
    sql_expr_to_recexpr_impl(
        expr,
        &mut rec,
        &mut symbols,
        &mut counter,
        &mut dedup,
        source_sql,
    );
    (rec, symbols)
}

fn make_symbol(
    expr: &SqlExpr,
    rec: &mut RecExpr<SqlLang>,
    symbols: &mut SymbolTable,
    counter: &mut u64,
    dedup: &mut HashMap<String, Id>,
    source_sql: &str,
) -> Id {
    let symbol_expr = fix_hex_format(expr.clone(), source_sql);
    let key_str = format!("{}", symbol_expr);
    if let Some(&existing_id) = dedup.get(&key_str) {
        return existing_id;
    }
    let key = *counter;
    *counter += 1;
    symbols.insert(key, symbol_expr);
    let id = rec.add(SqlLang::Symbol(key));
    dedup.insert(key_str, id);
    id
}

fn sql_expr_to_recexpr_impl(
    expr: &SqlExpr,
    rec: &mut RecExpr<SqlLang>,
    symbols: &mut SymbolTable,
    counter: &mut u64,
    dedup: &mut HashMap<String, Id>,
    source_sql: &str,
) -> Id {
    match expr {
        SqlExpr::BinaryOp { left, op, right } => match op {
            BinaryOperator::And => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::And([l, r]))
            }
            BinaryOperator::Or => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Or([l, r]))
            }
            BinaryOperator::Eq => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Eq([l, r]))
            }
            BinaryOperator::NotEq => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::NotEq([l, r]))
            }
            BinaryOperator::Lt => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Lt([l, r]))
            }
            BinaryOperator::Gt => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Gt([l, r]))
            }
            BinaryOperator::LtEq => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::LtEq([l, r]))
            }
            BinaryOperator::GtEq => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::GtEq([l, r]))
            }
            BinaryOperator::Plus => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Add([l, r]))
            }
            BinaryOperator::Minus => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Sub([l, r]))
            }
            BinaryOperator::Multiply => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Mul([l, r]))
            }
            BinaryOperator::Divide => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Div([l, r]))
            }
            BinaryOperator::StringConcat => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Concat([l, r]))
            }
            BinaryOperator::BitwiseAnd => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::BitAnd([l, r]))
            }
            BinaryOperator::BitwiseOr => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::BitOr([l, r]))
            }
            BinaryOperator::Modulo => {
                let l = sql_expr_to_recexpr_impl(left, rec, symbols, counter, dedup, source_sql);
                let r = sql_expr_to_recexpr_impl(right, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Remainder([l, r]))
            }
            _ => make_symbol(expr, rec, symbols, counter, dedup, source_sql),
        },

        SqlExpr::UnaryOp { op, expr: inner } => match op {
            UnaryOperator::Not => {
                let child =
                    sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Not([child]))
            }
            UnaryOperator::Minus => {
                let child =
                    sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::Neg([child]))
            }
            UnaryOperator::Plus => {
                // Unary plus must stay an opaque Symbol, but not for the reason first written here
                // ("it forces numeric type coercion, (+ '123') = 123 but '123' is TEXT"). That part
                // is wrong: +x returns its operand unchanged, value and typeof() both - checked
                // over 14 literals including '123', '0123' and '  42  '.
                //
                // The real reason is affinity. A column reference carries its declared affinity
                // into a comparison; +column is an expression and carries none, so the other side
                // stops being converted. On a table t(i INTEGER) holding 123:
                //     i = '123'    -> 1        (+i) = '123'  -> 0
                //     i < '9'      -> 0        (+i) < '9'    -> 1
                // Measured 2026-09-19 after enabling a uplus node and rules "?x -> (uplus ?x)" on
                // comparison operands: the random arm's multi-plan rate rose 6.3% -> 9.1%, and it
                // bought four false positives in three runs. Reverted.
                //
                // The lesson generalises: a rewrite being value-preserving is not enough for an
                // e-graph, because an e-class is substitutable in every context - and the other
                // side of a comparison is a context, just like NOT is.
                make_symbol(expr, rec, symbols, counter, dedup, source_sql)
            }
            UnaryOperator::BitwiseNot => {
                let child =
                    sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
                rec.add(SqlLang::BitNot([child]))
            }
            _ => make_symbol(expr, rec, symbols, counter, dedup, source_sql),
        },

        SqlExpr::Between {
            expr: inner,
            negated: false,
            low,
            high,
        } => {
            let e = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            let lo = sql_expr_to_recexpr_impl(low, rec, symbols, counter, dedup, source_sql);
            let hi = sql_expr_to_recexpr_impl(high, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::Between([e, lo, hi]))
        }

        SqlExpr::Between {
            expr: inner,
            negated: true,
            low,
            high,
        } => {
            // NOT BETWEEN = NOT (x BETWEEN lo AND hi)
            let e = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            let lo = sql_expr_to_recexpr_impl(low, rec, symbols, counter, dedup, source_sql);
            let hi = sql_expr_to_recexpr_impl(high, rec, symbols, counter, dedup, source_sql);
            let between = rec.add(SqlLang::Between([e, lo, hi]));
            rec.add(SqlLang::Not([between]))
        }

        SqlExpr::IsNull(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsNull([child]))
        }

        SqlExpr::IsNotNull(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsNotNull([child]))
        }

        SqlExpr::IsFalse(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsFalse([child]))
        }
        SqlExpr::IsTrue(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsTrue([child]))
        }
        SqlExpr::IsNotFalse(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsNotFalse([child]))
        }
        SqlExpr::IsNotTrue(inner) => {
            let child = sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql);
            rec.add(SqlLang::IsNotTrue([child]))
        }

        SqlExpr::Nested(inner) => {
            // Parentheses are transparent
            sql_expr_to_recexpr_impl(inner, rec, symbols, counter, dedup, source_sql)
        }

        // Everything else becomes an opaque Symbol
        _ => make_symbol(expr, rec, symbols, counter, dedup, source_sql),
    }
}

/// Does this predicate compare row values?
///
/// SQLite gives row values two different NULL rules. `(a,b) = (c,d)` is FALSE as soon as one pair is
/// definitely unequal, so `(NULL, 0.25) <> ('45','46')` is TRUE. But `(a,b) >= (c,d)` short-circuits
/// positionally and is NULL as soon as the first pair is, so the same comparison written as a
/// BETWEEN is NULL and the row disappears. Rules that are sound for scalars - `eq-to-tight` turns
/// `x = y` into `x >= y AND x <= y` - are therefore unsound here, and a long run did report that
/// pair as a result mismatch.
///
/// The converter has no row-value node: a tuple folds into an opaque Symbol, so nothing downstream
/// can tell it apart from a column. Refusing the whole query is what keeps every rule honest
/// without having to certify each one against row-value semantics.
///
/// Only the shapes the converter takes apart are walked. A tuple anywhere else already sits inside a
/// subtree that becomes one opaque Symbol, and no rule decomposes those.
pub fn contains_row_value(expr: &SqlExpr) -> bool {
    match expr {
        SqlExpr::Tuple(_) => true,
        SqlExpr::Nested(inner) => contains_row_value(inner),
        SqlExpr::BinaryOp { left, right, .. } => {
            contains_row_value(left) || contains_row_value(right)
        }
        SqlExpr::UnaryOp { expr: inner, .. } => contains_row_value(inner),
        SqlExpr::Between {
            expr: inner,
            low,
            high,
            ..
        } => contains_row_value(inner) || contains_row_value(low) || contains_row_value(high),
        SqlExpr::IsNull(inner)
        | SqlExpr::IsNotNull(inner)
        | SqlExpr::IsTrue(inner)
        | SqlExpr::IsFalse(inner)
        | SqlExpr::IsNotTrue(inner)
        | SqlExpr::IsNotFalse(inner)
        | SqlExpr::IsUnknown(inner)
        | SqlExpr::IsNotUnknown(inner) => contains_row_value(inner),
        // An IN is not walked, on purpose. The converter keeps `(a, b) IN (...)` whole as one opaque
        // atom whose value is a plain truth value, so no comparison rule ever sees the tuple: what
        // reaches the atom is the identities and the boolean rules around it, and those hold for
        // any three-valued predicate whatever its operands. IN arms were once added here on that
        // worry alone, with no mismatch behind them, and they switched off the one shape - the
        // row-value IN with min() of 6c97dbe469 - that the random path had found a real bug with.
        _ => false,
    }
}

/// Does this predicate compare two sides that could carry different column collations?
///
/// SQLite picks the collation of a comparison from its operands, and when neither carries an
/// explicit COLLATE the left operand's implicit one wins, falling back to the right's. Swapping the
/// sides therefore swaps which column's declared collation decides the answer. Measured on SQLite
/// 3.54 with `a TEXT COLLATE NOCASE` and `b TEXT` holding 'ABC' and 'abc': `a = b` is 1 and `b = a`
/// is 0, and `a < b` is 0 while `b > a` is 1. SQLancer declares a column collation with small
/// probability, so this is reachable, and the six rules that turn a comparison around would have
/// reported it as a result mismatch.
///
/// The implicit collation travels up through parentheses, CAST and a unary sign, and stops at a
/// function call or a concatenation - also measured. Rather than model that, a side counts as
/// collation-bearing when it mentions a column anywhere, which can only withhold a rewrite that
/// would have been sound. Two sides spelled the same way are the same collation whatever it is, so
/// `c0 = c0` stays rewritable, and that is the common case in generated predicates.
pub fn compares_columns_of_unknown_collation(expr: &SqlExpr) -> bool {
    match expr {
        SqlExpr::Nested(inner) => compares_columns_of_unknown_collation(inner),
        SqlExpr::UnaryOp { expr: inner, .. } => compares_columns_of_unknown_collation(inner),
        SqlExpr::BinaryOp { left, op, right } => {
            if is_collation_sensitive_operator(op)
                && ((mentions_a_column(left) && mentions_a_column(right))
                    || (carries_explicit_collation(left) && carries_explicit_collation(right)))
                && left.to_string() != right.to_string()
            {
                return true;
            }
            compares_columns_of_unknown_collation(left)
                || compares_columns_of_unknown_collation(right)
        }
        SqlExpr::Between {
            expr: inner,
            low,
            high,
            ..
        } => {
            if (mentions_a_column(inner) && (mentions_a_column(low) || mentions_a_column(high)))
                || (carries_explicit_collation(inner)
                    && (carries_explicit_collation(low) || carries_explicit_collation(high)))
            {
                let inner_text = inner.to_string();
                if inner_text != low.to_string() || inner_text != high.to_string() {
                    return true;
                }
            }
            compares_columns_of_unknown_collation(inner)
                || compares_columns_of_unknown_collation(low)
                || compares_columns_of_unknown_collation(high)
        }
        SqlExpr::IsNull(inner)
        | SqlExpr::IsNotNull(inner)
        | SqlExpr::IsTrue(inner)
        | SqlExpr::IsFalse(inner)
        | SqlExpr::IsNotTrue(inner)
        | SqlExpr::IsNotFalse(inner)
        | SqlExpr::IsUnknown(inner)
        | SqlExpr::IsNotUnknown(inner) => compares_columns_of_unknown_collation(inner),
        _ => false,
    }
}

fn is_collation_sensitive_operator(op: &BinaryOperator) -> bool {
    matches!(
        op,
        BinaryOperator::Eq
            | BinaryOperator::NotEq
            | BinaryOperator::Lt
            | BinaryOperator::Gt
            | BinaryOperator::LtEq
            | BinaryOperator::GtEq
    )
}

/// Whether a side carries an explicit COLLATE anywhere. One explicit side decides the collation
/// whichever way round it is written, but when both sides have one the left operand's wins:
/// measured on SQLite 3.54, `('ABC' COLLATE nocase) = ('abc' COLLATE binary)` is 1 and the swapped
/// form is 0. SQLite carries an explicit collation up through operators and function arguments
/// (EP_Collate in sqlite3ExprCollSeq), so a COLLATE anywhere in the side counts; that can only
/// withhold a rewrite that would have been sound. The 2026-10-03 trunk run reported
/// `("c0" COLLATE nocase)='abc' COLLATE binary` as a mismatch before this.
fn carries_explicit_collation(expr: &SqlExpr) -> bool {
    expr.to_string().to_uppercase().contains(" COLLATE ")
}

/// Whether a subexpression mentions a column at all. A side whose collation is explicit is left to
/// carries_explicit_collation.
fn mentions_a_column(expr: &SqlExpr) -> bool {
    match expr {
        SqlExpr::Identifier(_) | SqlExpr::CompoundIdentifier(_) => true,
        SqlExpr::Collate { .. } => false,
        SqlExpr::Value(_) => false,
        SqlExpr::Nested(inner) => mentions_a_column(inner),
        SqlExpr::UnaryOp { expr: inner, .. } => mentions_a_column(inner),
        SqlExpr::Cast { expr: inner, .. } => mentions_a_column(inner),
        SqlExpr::BinaryOp { left, right, .. } => mentions_a_column(left) || mentions_a_column(right),
        SqlExpr::Function(_) => false,
        other => other.to_string().chars().any(|c| c.is_alphabetic()),
    }
}

/// The rules that turn a comparison around. Withheld for a query where doing so could change which
/// column's collation decides the answer - see compares_columns_of_unknown_collation.
const OPERAND_SWAPPING_RULES: &[&str] = &[
    "eq-sym",
    "noteq-sym",
    "gt-to-lt",
    "lt-to-gt",
    "gteq-to-lteq",
    "lteq-to-gteq",
];

//  RecExpr<SqlLang> ?sqlparser Expr

pub fn recexpr_to_sql_expr(expr: &RecExpr<SqlLang>, symbols: &SymbolTable) -> SqlExpr {
    let root = Id::from(expr.as_ref().len() - 1);
    recexpr_to_sql_expr_impl(expr, root, symbols)
}

fn recexpr_to_sql_expr_impl(expr: &RecExpr<SqlLang>, id: Id, symbols: &SymbolTable) -> SqlExpr {
    match &expr[id] {
        SqlLang::Symbol(key) => symbols
            .get(key)
            .cloned()
            .unwrap_or_else(|| SqlExpr::Identifier(Ident::new(format!("sym_{}", key)))),

        SqlLang::And([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::And,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Or([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Or,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Not([c]) => SqlExpr::UnaryOp {
            op: UnaryOperator::Not,
            expr: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *c, symbols))),
        },

        SqlLang::Eq([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Eq,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::NotEq([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::NotEq,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Lt([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Lt,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Gt([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Gt,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::LtEq([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::LtEq,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::GtEq([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::GtEq,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Add([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Plus,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Sub([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Minus,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Mul([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Multiply,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Div([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Divide,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Concat([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::StringConcat,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::BitAnd([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::BitwiseAnd,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::BitOr([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::BitwiseOr,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Remainder([l, r]) => SqlExpr::BinaryOp {
            left: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *l, symbols))),
            op: BinaryOperator::Modulo,
            right: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *r, symbols))),
        },

        SqlLang::Neg([c]) => SqlExpr::UnaryOp {
            op: UnaryOperator::Minus,
            expr: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *c, symbols))),
        },

        SqlLang::BitNot([c]) => SqlExpr::UnaryOp {
            op: UnaryOperator::BitwiseNot,
            expr: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *c, symbols))),
        },

        SqlLang::Between([e, lo, hi]) => SqlExpr::Between {
            expr: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *e, symbols))),
            negated: false,
            low: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *lo, symbols))),
            high: Box::new(wrap_compound(recexpr_to_sql_expr_impl(expr, *hi, symbols))),
        },

        SqlLang::IsNull([c]) => SqlExpr::IsNull(Box::new(wrap_compound(recexpr_to_sql_expr_impl(
            expr, *c, symbols,
        )))),

        SqlLang::IsNotNull([c]) => SqlExpr::IsNotNull(Box::new(wrap_compound(
            recexpr_to_sql_expr_impl(expr, *c, symbols),
        ))),

        SqlLang::IsFalse([c]) => SqlExpr::IsFalse(Box::new(wrap_compound(
            recexpr_to_sql_expr_impl(expr, *c, symbols),
        ))),
        SqlLang::IsTrue([c]) => SqlExpr::IsTrue(Box::new(wrap_compound(recexpr_to_sql_expr_impl(
            expr, *c, symbols,
        )))),
        SqlLang::IsNotFalse([c]) => SqlExpr::IsNotFalse(Box::new(wrap_compound(
            recexpr_to_sql_expr_impl(expr, *c, symbols),
        ))),
        SqlLang::IsNotTrue([c]) => SqlExpr::IsNotTrue(Box::new(wrap_compound(
            recexpr_to_sql_expr_impl(expr, *c, symbols),
        ))),
    }
}

/// sqlparser represents both SQLite integer hex (`0x...`) and BLOB hex
/// (`x'...'`) as HexStringLiteral. Convert only tokens whose source span was
/// `0x...`; leave real BLOB literals as `X'...'`.
fn fix_hex_format(expr: SqlExpr, source_sql: &str) -> SqlExpr {
    use sqlparser::ast::{Value, ValueWithSpan};

    fn convert_hex(vws: &ValueWithSpan, source_sql: &str) -> Option<SqlExpr> {
        if let Value::HexStringLiteral(ref hex) = vws.value {
            if hex.is_empty() || !is_sqlite_integer_hex_span(source_sql, vws.span) {
                return None;
            }
            Some(SqlExpr::Value(ValueWithSpan {
                value: Value::Number(format!("0x{}", hex), false),
                span: vws.span,
            }))
        } else {
            None
        }
    }

    match expr {
        SqlExpr::Value(ref vws) => convert_hex(vws, source_sql).unwrap_or(expr),
        SqlExpr::BinaryOp { left, op, right } => SqlExpr::BinaryOp {
            left: Box::new(fix_hex_format(*left, source_sql)),
            op,
            right: Box::new(fix_hex_format(*right, source_sql)),
        },
        SqlExpr::UnaryOp { op, expr: inner } => SqlExpr::UnaryOp {
            op,
            expr: Box::new(fix_hex_format(*inner, source_sql)),
        },
        SqlExpr::Between {
            expr: e,
            negated,
            low,
            high,
        } => SqlExpr::Between {
            expr: Box::new(fix_hex_format(*e, source_sql)),
            negated,
            low: Box::new(fix_hex_format(*low, source_sql)),
            high: Box::new(fix_hex_format(*high, source_sql)),
        },
        SqlExpr::Nested(inner) => SqlExpr::Nested(Box::new(fix_hex_format(*inner, source_sql))),
        SqlExpr::IsNull(inner) => SqlExpr::IsNull(Box::new(fix_hex_format(*inner, source_sql))),
        SqlExpr::IsNotNull(inner) => {
            SqlExpr::IsNotNull(Box::new(fix_hex_format(*inner, source_sql)))
        }
        SqlExpr::IsTrue(inner) => SqlExpr::IsTrue(Box::new(fix_hex_format(*inner, source_sql))),
        SqlExpr::IsFalse(inner) => SqlExpr::IsFalse(Box::new(fix_hex_format(*inner, source_sql))),
        SqlExpr::IsNotTrue(inner) => {
            SqlExpr::IsNotTrue(Box::new(fix_hex_format(*inner, source_sql)))
        }
        SqlExpr::IsNotFalse(inner) => {
            SqlExpr::IsNotFalse(Box::new(fix_hex_format(*inner, source_sql)))
        }
        SqlExpr::IsUnknown(inner) => {
            SqlExpr::IsUnknown(Box::new(fix_hex_format(*inner, source_sql)))
        }
        SqlExpr::IsNotUnknown(inner) => {
            SqlExpr::IsNotUnknown(Box::new(fix_hex_format(*inner, source_sql)))
        }
        other => other,
    }
}

fn is_sqlite_integer_hex_span(source_sql: &str, span: Span) -> bool {
    span_text(source_sql, span)
        .map(|text| {
            let trimmed = text.trim_start();
            trimmed.starts_with("0x") || trimmed.starts_with("0X")
        })
        .unwrap_or(false)
}

fn span_text(source_sql: &str, span: Span) -> Option<&str> {
    if span.start.line == 0 || span.start.column == 0 || span.end.line == 0 || span.end.column == 0
    {
        return None;
    }
    let start = location_to_byte_offset(source_sql, span.start.line, span.start.column)?;
    let end = location_to_byte_offset(source_sql, span.end.line, span.end.column)?;
    source_sql.get(start..end)
}

fn location_to_byte_offset(source_sql: &str, line: u64, column: u64) -> Option<usize> {
    let mut current_line = 1u64;
    let mut current_column = 1u64;
    for (idx, ch) in source_sql.char_indices() {
        if current_line == line && current_column == column {
            return Some(idx);
        }
        if ch == '\n' {
            current_line += 1;
            current_column = 1;
        } else {
            current_column += 1;
        }
    }
    if current_line == line && current_column == column {
        return Some(source_sql.len());
    }
    None
}

/// Parenthesises a subexpression before it is written into a larger one.
///
/// The list used to say which shapes need brackets, and everything unnamed went out bare. That is
/// the wrong way round, because what goes out bare includes every shape this language keeps as an
/// opaque atom - an IN, a LIKE, a COLLATE. Measured: `(c1 COLLATE NOCASE) - (c2 IN (c2))` came back
/// as `c1 COLLATE NOCASE - c2 IN (c2)`, which SQLite reads as `((c1 COLLATE NOCASE) - c2) IN (c2)`,
/// a different question - and the oracle reported the different answer as a defect.
///
/// So the list now says which shapes are already a single primary. An extra pair of brackets around
/// anything else costs nothing; a missing pair changes the meaning.
fn wrap_compound(expr: SqlExpr) -> SqlExpr {
    match &expr {
        SqlExpr::Identifier(_)
        | SqlExpr::CompoundIdentifier(_)
        | SqlExpr::Value(_)
        | SqlExpr::Nested(_)
        | SqlExpr::Function(_)
        | SqlExpr::Cast { .. }
        | SqlExpr::Tuple(_)
        | SqlExpr::Subquery(_)
        | SqlExpr::Wildcard(..)
        | SqlExpr::QualifiedWildcard(..) => expr,
        _ => SqlExpr::Nested(Box::new(expr)),
    }
}

//  Rewrite rules

/// The 12 comparison rules added on 2026-09-08 can be switched off with EGRAPH_EXTRA_RULES=0 so
/// their effect on variant yield and latency can be A/B'd against the previous set in one binary.
fn extra_rules_enabled() -> bool {
    std::env::var("EGRAPH_EXTRA_RULES").map(|v| v != "0").unwrap_or(true)
}

/// Rule names to leave out, comma separated, from EGRAPH_DISABLE_RULES. Used for ablation runs:
/// a rule that compiles to the same bytecode on both sides never makes a check fail, but it may
/// still be the only path by which another rule's pattern becomes reachable, so "is this rule worth
/// keeping" cannot be answered without turning it off and counting what is lost.
fn disabled_rules() -> std::collections::HashSet<String> {
    std::env::var("EGRAPH_DISABLE_RULES")
        .ok()
        .map(|v| {
            v.split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

fn make_rewrite_rules(allow_operand_swap: bool) -> Vec<Rewrite<SqlLang, ()>> {
    let mut rules = make_base_rewrite_rules();
    if extra_rules_enabled() {
        rules.extend(make_extra_comparison_rules());
    }
    if !allow_operand_swap {
        rules.retain(|r| !OPERAND_SWAPPING_RULES.contains(&r.name.as_str()));
    }
    let off = disabled_rules();
    if !off.is_empty() {
        rules.retain(|r| !off.contains(r.name.as_str()));
    }
    rules
}

fn make_extra_comparison_rules() -> Vec<Rewrite<SqlLang, ()>> {
    vec![
        // Reverse of tight-eq / tight-noteq. These EXPAND (one comparison becomes two joined by
        // and/or), which is the point: an equality seek and a two-sided range scan are different
        // execution strategies for the same predicate, so unlike a pure shape change these reach
        // code the original form never compiles to. Soundness is not a coercion question - both
        // sides compare the same two operands under the same affinity rules, and under NULL both
        // sides are NULL. Verified over an adversarial value grid (NULL / '' / 'abc' / '0' /
        // signed zero / int64 boundaries / BLOB) before being enabled.
        rewrite!("eq-to-tight"; "(= ?x ?y)" => "(and (>= ?x ?y) (<= ?x ?y))"),
        rewrite!("noteq-to-lt-or-gt"; "(<> ?x ?y)" => "(or (< ?x ?y) (> ?x ?y))"),
        // Non-strict comparison split into its strict and equal halves, both directions. The OR
        // form is what gives SQLite's OR-optimization something to chew on.
        rewrite!("lteq-to-lt-or-eq"; "(<= ?x ?y)" => "(or (< ?x ?y) (= ?x ?y))"),
        rewrite!("gteq-to-gt-or-eq"; "(>= ?x ?y)" => "(or (> ?x ?y) (= ?x ?y))"),
        rewrite!("lt-or-eq-to-lteq"; "(or (< ?x ?y) (= ?x ?y))" => "(<= ?x ?y)"),
        rewrite!("gt-or-eq-to-gteq"; "(or (> ?x ?y) (= ?x ?y))" => "(>= ?x ?y)"),
        // The not-* rules reversed, making the NOT-over-comparison relation bidirectional. Before
        // this, an expression written as "c0 <> 5" could never reach the "NOT (c0 = 5)" spelling,
        // so half of the reachable forms were unreachable from the generator's output. Same
        // argument as de-morgan-*-rev: the NOT only wraps an already-boolean result, so no operand
        // changes its coercion and NULL stays NULL on both sides.
        rewrite!("eq-to-not-noteq"; "(= ?x ?y)" => "(not (<> ?x ?y))"),
        rewrite!("noteq-to-not-eq"; "(<> ?x ?y)" => "(not (= ?x ?y))"),
        rewrite!("gt-to-not-lteq"; "(> ?x ?y)" => "(not (<= ?x ?y))"),
        rewrite!("lt-to-not-gteq"; "(< ?x ?y)" => "(not (>= ?x ?y))"),
        rewrite!("gteq-to-not-lt"; "(>= ?x ?y)" => "(not (< ?x ?y))"),
        rewrite!("lteq-to-not-gt"; "(<= ?x ?y)" => "(not (> ?x ?y))"),
    ]
}

fn make_base_rewrite_rules() -> Vec<Rewrite<SqlLang, ()>> {
    vec![
        //  Boolean algebra ?commutativity
        rewrite!("and-comm"; "(and ?x ?y)" => "(and ?y ?x)"),
        rewrite!("or-comm"; "(or ?x ?y)" => "(or ?y ?x)"),
        //  Boolean algebra ?associativity (both directions)
        rewrite!("and-assoc-l"; "(and (and ?x ?y) ?z)" => "(and ?x (and ?y ?z))"),
        rewrite!("and-assoc-r"; "(and ?x (and ?y ?z))" => "(and (and ?x ?y) ?z)"),
        rewrite!("or-assoc-l"; "(or (or ?x ?y) ?z)" => "(or ?x (or ?y ?z))"),
        rewrite!("or-assoc-r"; "(or ?x (or ?y ?z))" => "(or (or ?x ?y) ?z)"),
        //  Boolean algebra ?idempotence (disabled: reverse creates spurious nodes)
        // rewrite!("and-idem"; "(and ?x ?x)" => "?x"),
        // rewrite!("or-idem"; "(or ?x ?x)" => "?x"),
        //  Boolean algebra ?absorption (disabled: reverse creates spurious nodes)
        // rewrite!("and-absorb"; "(and ?x (or ?x ?y))" => "?x"),
        // rewrite!("or-absorb"; "(or ?x (and ?x ?y))" => "?x"),
        //  Boolean algebra ?De Morgan
        rewrite!("de-morgan-and"; "(not (and ?x ?y))" => "(or (not ?x) (not ?y))"),
        rewrite!("de-morgan-or"; "(not (or ?x ?y))" => "(and (not ?x) (not ?y))"),
        // De Morgan in reverse.  Safe for the same reason the forward direction is:
        // both sides have the same node count, so this is a pure shape change - it
        // neither compresses (what makes idem/absorb produce spurious nodes) nor
        // expands (what makes distributivity explode).
        rewrite!("de-morgan-and-rev"; "(or (not ?x) (not ?y))" => "(not (and ?x ?y))"),
        rewrite!("de-morgan-or-rev"; "(and (not ?x) (not ?y))" => "(not (or ?x ?y))"),
        //  Boolean algebra - double negation REMOVED
        //
        // not(not(?x)) is ?x as a truth value and is not ?x as a value: SQLite answers
        // NOT (NOT 5) with 1. An e-class union is symmetric, so merging the two put a boolean test
        // and a plain value in one class, and extraction could then substitute either way round -
        // including in an arithmetic position, where (NOT (NOT 5)) + 1 is 2 and 5 + 1 is 6. The note
        // below on isnull-expand already names this as the cause of Bug #12637 and closes one path
        // into it; this rule is the mechanism itself, and it needs no help from isnull-expand. Asked
        // directly with the predicate ((NOT (NOT c0)) + 1) > 0, the server returned a variant mixing
        // both readings of the same operand, which disagrees with the original at c0 = -2.
        //
        // By this project's standing rule - a rewrite has to be strictly equivalent in every context,
        // not only at the top of a WHERE clause - it cannot be kept. Measured cost of its absence,
        // together with the six unsound arithmetic rules: none. See the arithmetic block below.
        // rewrite!("double-neg"; "(not (not ?x))" => "?x"),
        //  Boolean algebra ?factoring (safe: always compresses)
        // Reverse of distributive expansion ?pulls out common factor.
        rewrite!("factor-and"; "(or (and ?x ?y) (and ?x ?z))" => "(and ?x (or ?y ?z))"),
        rewrite!("factor-or"; "(and (or ?x ?y) (or ?x ?z))" => "(or ?x (and ?y ?z))"),
        // RE-ENABLED 2026-09-08. The disabling reason was e-graph explosion, not unsoundness -
        // both hold in Kleene three-valued logic and were re-verified on the adversarial value
        // grid. Explosion is now bounded by EGRAPH_NODE_LIMIT / EGRAPH_TIME_LIMIT_MS (400 / 60ms),
        // so the cost is "other rules fire less", not a hang. Worth it because (x AND y) OR
        // (x AND z) is the entry point to SQLite's OR-optimization: the MULTI_INDEX_OR wrapper
        // shape has been reporting ~100% lost in every workload report, and this is the only rule
        // that can feed it.
        rewrite!("and-dist-or"; "(and ?x (or ?y ?z))" => "(or (and ?x ?y) (and ?x ?z))"),
        rewrite!("or-dist-and"; "(or ?x (and ?y ?z))" => "(and (or ?x ?y) (or ?x ?z))"),
        //  Comparison symmetry
        rewrite!("eq-sym"; "(= ?x ?y)" => "(= ?y ?x)"),
        rewrite!("noteq-sym"; "(<> ?x ?y)" => "(<> ?y ?x)"),
        rewrite!("gt-to-lt"; "(> ?x ?y)" => "(< ?y ?x)"),
        rewrite!("lt-to-gt"; "(< ?x ?y)" => "(> ?y ?x)"),
        rewrite!("gteq-to-lteq"; "(>= ?x ?y)" => "(<= ?y ?x)"),
        rewrite!("lteq-to-gteq"; "(<= ?x ?y)" => "(>= ?y ?x)"),
        //  NOT over comparisons
        rewrite!("not-eq"; "(not (= ?x ?y))" => "(<> ?x ?y)"),
        rewrite!("not-noteq"; "(not (<> ?x ?y))" => "(= ?x ?y)"),
        rewrite!("not-gt"; "(not (> ?x ?y))" => "(<= ?x ?y)"),
        rewrite!("not-lt"; "(not (< ?x ?y))" => "(>= ?x ?y)"),
        rewrite!("not-gteq"; "(not (>= ?x ?y))" => "(< ?x ?y)"),
        rewrite!("not-lteq"; "(not (<= ?x ?y))" => "(> ?x ?y)"),
        // The six rules above, reversed - they make the NOT-over-comparison relation bidirectional.
        // Before this, an expression written as "c0 <> 5" could never reach the "NOT (c0 = 5)"
        // spelling, so half of the reachable forms were unreachable from the generator's output.
        // Same argument as de-morgan-*-rev: the NOT only wraps an already-boolean result, so no
        // operand changes its coercion, and NULL stays NULL on both sides.
        //  NOT over BETWEEN
        rewrite!("not-between"; "(not (between ?x ?lo ?hi))" => "(or (< ?x ?lo) (> ?x ?hi))"),
        //  Comparison chain compression
        rewrite!("tight-eq"; "(and (>= ?x ?y) (<= ?x ?y))" => "(= ?x ?y)"),
        rewrite!("tight-noteq"; "(or (< ?x ?y) (> ?x ?y))" => "(<> ?x ?y)"),
        // Reverse of tight-eq / tight-noteq.  These EXPAND (one comparison becomes two joined by
        // and/or), which is the point: an equality seek and a two-sided range scan are different
        // execution strategies for the same predicate, so unlike a pure shape change these reach
        // code the original form never compiles to.  Soundness is not a coercion question - both
        // sides compare the same two operands with the same affinity rules, and under NULL both
        // sides are NULL.  Verified over the adversarial value grid (NULL / '' / 'abc' / '0' /
        // signed zero / int64 boundaries / BLOB) before being enabled.
        // Non-strict comparison split into its strict and equal halves, both directions.  The OR
        // form is what gives SQLite's OR-optimization something to chew on.
        //  Arithmetic
        //
        // Only the two commutativity rules are left, and they are the only two that survive being
        // measured. The associativity and subtraction rules were justified on the grounds that both
        // sides coerce every operand numerically, so the asymmetry that makes neg-neg unsafe never
        // arises. That is true of coercion and beside the point: SQLite promotes an integer overflow
        // to REAL, and where the overflow happens depends on the bracketing. Enumerated over the
        // twelve edge values this validator already uses, on SQLite 3.54, counting pairs and triples
        // whose two spellings do not give the same value and type:
        //
        //   add-assoc    140 of 1728 triples disagree   (MAX+1)+(-1) is 9.2e18, MAX+(1+(-1)) is MAX
        //   mul-assoc    118 of 1728 triples disagree   (X*4)*0 is 0.0, X*(4*0) is 0
        //   sub-to-add     5 of  144 pairs disagree
        //   sub-antisym    3 of  144 pairs disagree
        //   add-comm       0                            sound
        //   mul-comm       0                            sound
        //
        // The in-memory validator could not catch any of it: it models integers as wrapping i64, so
        // it computes both sides as equal where SQLite produces a REAL on one side only.
        //
        // Nothing is lost by their absence, which is the other half of the measurement: 150 s with
        // the eight rules off against 150 s with them on gave a variant rate of 91.8% against 92.6%
        // and 52219 compared pairs against 45662. Arithmetic is not an indexable position, so
        // rebracketing it was never going to change an access path either.
        rewrite!("add-comm"; "(+ ?x ?y)" => "(+ ?y ?x)"),
        rewrite!("mul-comm"; "(* ?x ?y)" => "(* ?y ?x)"),
        //  Arithmetic double-negation DISABLED ?negation forces numeric coercion
        // neg-neg: --x ?x is UNSAFE in SQLite ?- forces numeric coercion.
        // When x is TEXT, -(-('abc')) = 0 but bare 'abc' ?0.
        // rewrite!("neg-neg"; "(neg (neg ?x))" => "?x"),
        //  IS FALSE / IS TRUE / IS UNKNOWN ?canonical form
        // IS FALSE / IS TRUE ?kept as transparent non-Symbol nodes.  The
        // obvious rewrites (IsFalseot, IsTrued) are correct as standalone
        // identities in WHERE context, but egg's compositional extraction can
        // produce forms like Not(IsFalse(Not(x))) that are NOT equivalent.
        // Even 500-sample validation + forced edge values cannot guarantee
        // catching all mismatches in a graph-based e-graph.
        //
        // IS UNKNOWN / IS NOT UNKNOWN are gone from the language entirely, nodes and rules, because
        // SQLite has no such operator: it parses UNKNOWN as an identifier and every such variant died
        // with "no such column: UNKNOWN". Measured before the rules went: 3101 of 3133 variant-only
        // errors in a 300 s run, and since a variant error abandons the whole check, ~16% of all
        // checks (3133 / 19615) were discarded for it.
        //
        // Worth keeping the general lesson: commenting out one direction of a rule pair does not make
        // the reverse substitution impossible, because applying a rule *unions* the two e-classes and
        // a union is symmetric - the extractor can then emit either node. It only narrows the trigger.
        // A spelling the DBMS under test cannot parse has to leave the language, not just lose a rule.
        //  IS NULL / IS NOT NULL expansion ?DISABLED
        // These rules let egg's compositional extraction merge ISNULL/NOTNULL
        // e-classes with unrelated expressions (e.g. string literals), producing
        // substitutions like "t0.c0 ISNULL ?NOT(NOT('-531915025'))" (Bug #7309)
        // and "t0.c0 NOTNULL ?NOT(NOT(t0.c0))" (Bug #12637 ?wrong when c0=0).
        // NOT(ISNULL) ?ISNOTNULL commutativity is still handled by not-isnull /
        // not-isnotnull rules below, which are safe.
        // rewrite!("isnull-expand"; "(isnull ?x)" => "(not (isnotnull ?x))"),
        // rewrite!("isnotnull-expand"; "(isnotnull ?x)" => "(not (isnull ?x))"),
        rewrite!("not-isnull"; "(not (isnull ?x))" => "(isnotnull ?x)"),
        rewrite!("not-isnotnull"; "(not (isnotnull ?x))" => "(isnull ?x)"),
        //  IS NULL / IS NOT NULL expansion into the IS TRUE / IS FALSE family
        // These do what isnull-expand above was meant to do, without its failure mode. The
        // difference is the shape of the right-hand side: it is built only from isXXX nodes joined
        // by and/or, so the operand ?x never appears as a bare value. isnull-expand put (isnull ?x)
        // into the not(...) family, and double-neg merged not(not(?y)) with ?y, so a boolean test
        // and a plain value ended up in one e-class - which is how "t0.c0 NOTNULL" came out as
        // "NOT(NOT(t0.c0))" (Bug #12637, wrong when c0=0). There is no path from these rules to a
        // bare operand, so that merge cannot happen. double-neg has since been removed as well, so
        // the merge has no source at all.
        //
        // Meaning: a value that is neither TRUE nor FALSE can only be NULL, and vice versa.
        // Verified over the adversarial value grid - 19 literals (NULL / 0 / 1 / -1 / 0.0 /
        // signed zero / '' / '0' / 'abc' / ' 1' / x'' / x'00' / int64 bounds) plus 6 declared
        // affinities x 4 rows - with zero counterexamples.
        //
        // They earn their place by changing the compiled program, not just the text: measured on a
        // 5000-row table with an index on c0 and 1% NULLs, "c0 IS NULL" compiles to
        // SEARCH USING INDEX i0 (c0=?) while the expansion compiles to SCAN, both returning 50 rows.
        // An index seek and a full scan have to agree, and if they do not, that is the bug.
        //
        // In-run A/B on a corpus of six IS NULL / IS NOT NULL predicates over an 800-row table
        // with indexes on two of the three columns, same data both arms, the two rules the only
        // variable: multi-plan rate 0.4% (3 of 684) with them off, 53.4% (356 of 667) with them on.
        // The control arm shows the other terms in those predicates (c1 > 900 and friends, on an
        // unindexed column) contribute almost nothing, so the difference is these rules.
        //
        // Only these two are enabled. The rest of the family (isnottrue -> or(isfalse, isnull) and
        // its three siblings) verified clean too, but they close a cycle with these two, and the
        // node budget is 200 - they go in one at a time, watching e-graph size and variant quality.
        rewrite!("isnull-to-boolpair"; "(isnull ?x)" => "(and (isnottrue ?x) (isnotfalse ?x))"),
        rewrite!("isnotnull-to-boolpair"; "(isnotnull ?x)" => "(or (istrue ?x) (isfalse ?x))"),
        //  BETWEEN decomposition ?DISABLED
        // In SQLite, "x BETWEEN lo AND hi" and "x>=lo AND x<=hi" are NOT equivalent
        // when operands have mixed types (INT/TEXT/BLOB).  The separate comparisons
        // change type coercion order, producing false positives (BUG #1, #2, #4).
        // RE-ENABLED 2026-09-08. The false positives above (BUG #1/#2/#4) did not reproduce on
        // SQLite 3.53.4: 6 declared affinities (INTEGER/TEXT/REAL/BLOB/NUMERIC/none) x 13 column
        // values (NULL / '' / text / numeric text / padded numeric text / 0 / ints / floats / BLOB)
        // x 169 (lo,hi) pairs = 13182 combinations, zero counterexamples. SQLite's own docs state
        // the two forms are equivalent, the only difference being that BETWEEN evaluates x once.
        // That single residual risk needs a non-deterministic ?x, which egraphMode cannot produce
        // (ExpressionType.FUNCTION is removed) and the corpus filter rejects. Kept because it is
        // one of the few rules that actually changes the compiled program - measured: index range
        // scan becomes two OR'd range scans. If BUG #1/#2/#4-shaped false positives return, the
        // original attribution was right and this line goes back to being commented out.
        rewrite!("between-decomp"; "(between ?x ?lo ?hi)" => "(and (>= ?x ?lo) (<= ?x ?hi))"),
        rewrite!("between-compose"; "(and (>= ?x ?lo) (<= ?x ?hi))" => "(between ?x ?lo ?hi)"),
        //  String concatenation ?associative, not commutative
        rewrite!("concat-assoc-l"; "(concat (concat ?x ?y) ?z)" => "(concat ?x (concat ?y ?z))"),
        rewrite!("concat-assoc-r"; "(concat ?x (concat ?y ?z))" => "(concat (concat ?x ?y) ?z)"),
        //  Bitwise AND ?commutativity, associativity (idempotence DISABLED)
        // bitand-idem: x&x ?x is UNSAFE in SQLite ?& forces numeric coercion.
        // When x is TEXT ('abc'), (x & x) = 0 but bare x = 'abc'. See BUG #7441.
        rewrite!("bitand-comm"; "(bitand ?x ?y)" => "(bitand ?y ?x)"),
        rewrite!("bitand-assoc-l"; "(bitand (bitand ?x ?y) ?z)" => "(bitand ?x (bitand ?y ?z))"),
        rewrite!("bitand-assoc-r"; "(bitand ?x (bitand ?y ?z))" => "(bitand (bitand ?x ?y) ?z)"),
        // rewrite!("bitand-idem"; "(bitand ?x ?x)" => "?x"),
        //  Bitwise OR ?commutativity, associativity (idempotence DISABLED)
        // bitor-idem: x|x ?x is UNSAFE in SQLite ?| forces numeric coercion.
        rewrite!("bitor-comm"; "(bitor ?x ?y)" => "(bitor ?y ?x)"),
        rewrite!("bitor-assoc-l"; "(bitor (bitor ?x ?y) ?z)" => "(bitor ?x (bitor ?y ?z))"),
        rewrite!("bitor-assoc-r"; "(bitor ?x (bitor ?y ?z))" => "(bitor (bitor ?x ?y) ?z)"),
        // rewrite!("bitor-idem"; "(bitor ?x ?x)" => "?x"),
        //  Bitwise NOT (double-negation DISABLED ?forces numeric coercion)
        // bitnot-double: ~~x ?x is UNSAFE in SQLite ?~ forces numeric coercion.
        // When x is TEXT, ~(~('abc')) = 0 but bare 'abc' ?0.
        // rewrite!("bitnot-double"; "(bitnot (bitnot ?x))" => "?x"),
        rewrite!("bitnot-demorgan-and"; "(bitnot (bitand ?x ?y))" => "(bitor (bitnot ?x) (bitnot ?y))"),
        rewrite!("bitnot-demorgan-or";  "(bitnot (bitor ?x ?y))" => "(bitand (bitnot ?x) (bitnot ?y))"),
        //  Modulo: no direct rewrite rules (x % 1 ?0 requires constant detection).
        //          Remainder is still useful as a non-opaque node ?it allows
        //          surrounding AND/OR rules to fire instead of treating the
        //          whole expression as a black-box Symbol.
    ]
}

//  E-graph operations

/// Saturation budget. Tunable because the expanding comparison rules (eq-to-tight and friends)
/// form a growth loop with their contracting inverses: every iteration adds nodes, so the runner
/// always runs to one of these limits rather than reaching saturation. Variants per *second* is
/// what determines detection rate, not variants per query, so these defaults are set from the
/// sweep in EGRAPH.md rather than left at egg's generous ones.
fn node_limit() -> usize {
    std::env::var("EGRAPH_NODE_LIMIT").ok().and_then(|v| v.parse().ok()).unwrap_or(200)
}

fn time_limit_ms() -> u64 {
    std::env::var("EGRAPH_TIME_LIMIT_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(40)
}

pub fn perform_rewrites(
    expr: &RecExpr<SqlLang>,
    iter_limit: usize,
    allow_operand_swap: bool,
) -> (EGraph<SqlLang, ()>, Id) {
    let rules = make_rewrite_rules(allow_operand_swap);
    let runner = Runner::default()
        .with_iter_limit(iter_limit)
        .with_node_limit(node_limit())
        .with_time_limit(std::time::Duration::from_millis(time_limit_ms()))
        .with_expr(expr)
        .run(&rules);
    let root = runner.roots[0];
    (runner.egraph, root)
}

pub fn extract_variants(
    egraph: &EGraph<SqlLang, ()>,
    root: Id,
    max_variants: usize,
) -> Vec<RecExpr<SqlLang>> {
    let mut seen: HashSet<String> = HashSet::new();
    let mut result = Vec::new();

    // Pure randomized extraction ?no AstSize bias toward the original form.
    // Each random walk picks a random e-node at every e-class along the
    // recursion path.  AstSize would always prefer the minimum-cost form
    // (usually the original), which caps diversity.  Random walks explore
    // the full e-graph without cost bias.
    let max_attempts = max_variants * 200;
    for _ in 0..max_attempts {
        if result.len() >= max_variants {
            break;
        }
        let Some(expr) = extract_randomized(egraph, root) else {
            continue;
        };
        let key = format!("{}", expr);
        if seen.insert(key) {
            result.push(expr);
        }
    }

    result
}

/// Returns None when the walk ran into an e-class cycle it could not break without inventing a
/// node. Previously such a walk fabricated `Symbol(0)`, which is not a neutral placeholder: the
/// symbol table resolves id 0 to whatever the first symbol happens to be, so a cycle silently
/// turned into a real column reference and the extracted expression no longer meant anything
/// related to the input. Abandoning the walk costs nothing - extract_variants already retries.
fn extract_randomized(egraph: &EGraph<SqlLang, ()>, root: Id) -> Option<RecExpr<SqlLang>> {
    let mut rng = rand::thread_rng();
    let mut rec = RecExpr::default();
    let mut path: HashSet<Id> = HashSet::new();
    extract_randomized_impl(egraph, root, &mut rec, &mut path, &mut rng)?;
    Some(rec)
}

fn extract_randomized_impl(
    egraph: &EGraph<SqlLang, ()>,
    id: Id,
    rec: &mut RecExpr<SqlLang>,
    path: &mut HashSet<Id>,
    rng: &mut impl rand::Rng,
) -> Option<Id> {
    // Cycle detection. A childless node in the cycling e-class is a safe stand-in: sound rules make
    // every member of an e-class equivalent, so the leaf means the same as the cycle it replaces.
    // With no such leaf there is nothing safe to emit, so the walk is abandoned.
    if path.contains(&id) {
        for node in &egraph[id].nodes {
            if node.children().is_empty() {
                // A childless node has nothing to re-point, so it can be copied as it stands.
                return Some(rec.add(node.clone()));
            }
        }
        return None;
    }

    path.insert(id);
    let nodes = &egraph[id].nodes;
    // Uniformly at random, and measured rather than assumed.
    //
    // The obvious improvement is to weight this pick toward spellings that can change an access path
    // - a comparison's e-class holds the comparison itself next to `NOT (the opposite)`, a BETWEEN, an
    // OR of two ranges and an AND of two bounds, and only the first is what SQLite normalises back to.
    // Built that way, class-relative, a 4-to-1 spread, and A/B'd over four 150 s runs alternating:
    //
    //   distinct variants per check    7.51  7.54  weighted      8.42  8.12  uniform
    //   checks reaching 5+ plans      14.4% 15.0%  weighted     16.9% 15.6%  uniform
    //
    // It made things slightly worse, consistently in both rounds. The first explanation offered for
    // that was wrong and is worth recording as such: it was not that concentrating the distribution
    // made more walks render to the same string. Counted directly (see extraction_walk_outcomes),
    // duplicates are 0.0% of attempts, and for a predicate with a rich e-graph all 16 requested
    // variants survive extraction, validation and dedup. So neither dedup nor validation is the
    // limit, and why weighting cost variants is not established.
    //
    // What the same count did find is the one channel that is large: 82% of walks are abandoned,
    // because a cycle was reached with no childless node in the cycling class, and each abandonment
    // throws away the whole variant rather than that subtree. Commutativity and associativity put a
    // cycle in nearly every boolean e-class, so this is structural.
    //
    // Raising the budget does not help either, measured the same way: node 600 / 100 ms against
    // 200 / 40 ms gave 7.16 and 6.93 variants per check against 8.14 and 8.08, with 31% fewer pairs
    // compared in total - a bigger graph means more cycles, so more of the walks abandon.
    //
    // Which leaves the count of variants per check being set by how rewritable the predicate is - an
    // opaque atom gets the three identity shapes, a rich boolean predicate gets all 16 - and that is
    // decided by the language, not by extraction.
    let idx = rng.gen_range(0..nodes.len());
    let node = &nodes[idx];

    let mut child_ids: Vec<Id> = Vec::with_capacity(node.children().len());
    for &child_id in node.children() {
        match extract_randomized_impl(egraph, child_id, rec, path, rng) {
            Some(child) => child_ids.push(child),
            None => {
                path.remove(&id);
                return None;
            }
        }
    }

    path.remove(&id);

    let new_node = make_node(node, &child_ids);
    Some(rec.add(new_node))
}

fn make_node(template: &SqlLang, child_ids: &[Id]) -> SqlLang {
    match template {
        SqlLang::And(_) => SqlLang::And([child_ids[0], child_ids[1]]),
        SqlLang::Or(_) => SqlLang::Or([child_ids[0], child_ids[1]]),
        SqlLang::Not(_) => SqlLang::Not([child_ids[0]]),
        SqlLang::Eq(_) => SqlLang::Eq([child_ids[0], child_ids[1]]),
        SqlLang::NotEq(_) => SqlLang::NotEq([child_ids[0], child_ids[1]]),
        SqlLang::Lt(_) => SqlLang::Lt([child_ids[0], child_ids[1]]),
        SqlLang::Gt(_) => SqlLang::Gt([child_ids[0], child_ids[1]]),
        SqlLang::LtEq(_) => SqlLang::LtEq([child_ids[0], child_ids[1]]),
        SqlLang::GtEq(_) => SqlLang::GtEq([child_ids[0], child_ids[1]]),
        SqlLang::Add(_) => SqlLang::Add([child_ids[0], child_ids[1]]),
        SqlLang::Sub(_) => SqlLang::Sub([child_ids[0], child_ids[1]]),
        SqlLang::Mul(_) => SqlLang::Mul([child_ids[0], child_ids[1]]),
        SqlLang::Div(_) => SqlLang::Div([child_ids[0], child_ids[1]]),
        SqlLang::Neg(_) => SqlLang::Neg([child_ids[0]]),
        SqlLang::BitNot(_) => SqlLang::BitNot([child_ids[0]]),
        SqlLang::Between(_) => SqlLang::Between([child_ids[0], child_ids[1], child_ids[2]]),
        SqlLang::IsNull(_) => SqlLang::IsNull([child_ids[0]]),
        SqlLang::IsNotNull(_) => SqlLang::IsNotNull([child_ids[0]]),
        SqlLang::IsFalse(_) => SqlLang::IsFalse([child_ids[0]]),
        SqlLang::IsTrue(_) => SqlLang::IsTrue([child_ids[0]]),
        SqlLang::IsNotFalse(_) => SqlLang::IsNotFalse([child_ids[0]]),
        SqlLang::IsNotTrue(_) => SqlLang::IsNotTrue([child_ids[0]]),
        SqlLang::Concat(_) => SqlLang::Concat([child_ids[0], child_ids[1]]),
        SqlLang::BitAnd(_) => SqlLang::BitAnd([child_ids[0], child_ids[1]]),
        SqlLang::BitOr(_) => SqlLang::BitOr([child_ids[0], child_ids[1]]),
        SqlLang::Remainder(_) => SqlLang::Remainder([child_ids[0], child_ids[1]]),
        SqlLang::Symbol(k) => SqlLang::Symbol(*k),
    }
}

//  Random Sampling Validation
//
// For each candidate variant, we generate N random assignments to the opaque
// Symbol leaves and evaluate both the original and variant expression trees.
// If they ever disagree, the variant is NOT semantically equivalent and is
// discarded.  This catches all three classes of false positives:
//   1. NULL propagation errors (three-valued logic)
//   2. Type confusion (boolean used in arithmetic position)
//   3. Literal serialization changes (0x??X'?)

const NUM_SAMPLES: usize = 300;
const NULL_PROB: f64 = 0.12;

/// SQLite-compatible value for in-memory evaluation.
///
/// Bool is NOT a first-class type in SQLite ?TRUE is 1, FALSE is 0.
/// Cross-type comparisons follow SQLite's affinity rules:
///   - NULL with anything ?NULL
///   - Blob > any Text > any Int   (Blob sorts after everything)
///   - Int ?Text: if Text looks numeric, compare as numbers; else Text > Int
///   - Bool ?Int/Text: Bool is treated as Int(0/1)
#[derive(Debug, Clone, PartialEq)]
enum SqlValue {
    Null,
    Bool(bool),
    Int(i64),
    Text(String),
    /// BLOB literal - in SQLite, BLOBs sort after all numbers and text. The bytes are kept: two
    /// different blobs are not the same value, and a contentless Blob made x'41' = x'42' true here.
    Blob(Vec<u8>),
}

impl SqlValue {
    /// SQLite-style comparison.
    fn partial_cmp(&self, other: &SqlValue) -> Option<std::cmp::Ordering> {
        use std::cmp::Ordering;
        // NULL propagates
        if self.is_null() || other.is_null() {
            return None;
        }
        // Normalise Bool ?Int for cross-type comparison (SQLite has no bool type)
        let (a, b) = (self.normalise(), other.normalise());
        match (a.as_ref(), b.as_ref()) {
            // Blob > everything except another Blob, and two blobs compare by their bytes
            (SqlValue::Blob(x), SqlValue::Blob(y)) => x.partial_cmp(y),
            (SqlValue::Blob(_), _) => Some(Ordering::Greater),
            (_, SqlValue::Blob(_)) => Some(Ordering::Less),
            // Same-type
            (SqlValue::Int(x), SqlValue::Int(y)) => x.partial_cmp(y),
            (SqlValue::Text(x), SqlValue::Text(y)) => x.partial_cmp(y),
            // Text against a number compares by storage class, not by value: SQLite 3.54 answers
            // '5' > 9 with 1 and '5' = 5 with 0. Numeric coercion happens only when a column's
            // affinity asks for it, and nothing here carries an affinity, so the no-affinity rule
            // is the one to model. A rewrite whose equivalence needs the coercion will not look
            // equivalent here and is rejected, which is the safe direction for a soundness check.
            (SqlValue::Text(_), SqlValue::Int(_)) => Some(Ordering::Greater),
            (SqlValue::Int(_), SqlValue::Text(_)) => Some(Ordering::Less),
            // Bool normalised to Int already ?should not reach here
            (SqlValue::Bool(_), _) | (_, SqlValue::Bool(_)) => unreachable!(),
            // NULL already handled at top of function; Blob* covered above; remaining combos are unreachable
            _ => unreachable!(),
        }
    }

    /// Convert Bool ?Int (SQLite: FALSE=0, TRUE=1).  Other types unchanged.
    fn normalise(&self) -> Cow<'_, SqlValue> {
        match self {
            SqlValue::Bool(false) => Cow::Owned(SqlValue::Int(0)),
            SqlValue::Bool(true) => Cow::Owned(SqlValue::Int(1)),
            other => Cow::Borrowed(other),
        }
    }

    fn is_null(&self) -> bool {
        matches!(self, SqlValue::Null)
    }
}

use std::borrow::Cow;

/// Evaluate a RecExpr under a specific assignment of Symbol ids to SqlValues.
fn eval(
    expr: &RecExpr<SqlLang>,
    root: Id,
    symbols: &SymbolTable,
    assignment: &HashMap<u64, SqlValue>,
) -> SqlValue {
    match &expr[root] {
        SqlLang::Symbol(k) => eval_symbol(*k, symbols, assignment),

        //  Boolean: three-valued logic
        SqlLang::And([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            three_valued_and(lv, rv)
        }
        SqlLang::Or([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            three_valued_or(lv, rv)
        }
        SqlLang::Not([c]) => {
            let cv = eval(expr, *c, symbols, assignment);
            three_valued_not(cv)
        }

        //  Comparisons: NULL if either side is NULL
        // Through partial_cmp rather than SqlValue equality, so that TRUE and 1 - the same value
        // to SQLite, two variants to this enum - compare equal.
        SqlLang::Eq([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o == std::cmp::Ordering::Equal
        }),
        SqlLang::NotEq([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o != std::cmp::Ordering::Equal
        }),
        // partial_cmp answers None only when an operand is NULL, and an order comparison with a
        // NULL operand is NULL, not FALSE. Reading None as FALSE made `NULL < 5` false here while
        // SQLite returns NULL, which is exactly the difference a rewrite under a NOT turns into a
        // wrong answer - so the checker would have waved such a rule through.
        SqlLang::Lt([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o == std::cmp::Ordering::Less
        }),
        SqlLang::Gt([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o == std::cmp::Ordering::Greater
        }),
        SqlLang::LtEq([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o != std::cmp::Ordering::Greater
        }),
        SqlLang::GtEq([l, r]) => order_compare(expr, *l, *r, symbols, assignment, |o| {
            o != std::cmp::Ordering::Less
        }),

        //  Arithmetic: NULL if any operand is NULL
        SqlLang::Add([l, r]) => arith2(expr, *l, *r, symbols, assignment, |a, b| a + b),
        SqlLang::Sub([l, r]) => arith2(expr, *l, *r, symbols, assignment, |a, b| a - b),
        SqlLang::Mul([l, r]) => arith2(expr, *l, *r, symbols, assignment, |a, b| a * b),
        // Division by zero is NULL in SQLite, not a number. Returning i64::MAX made the two sides
        // of a rewrite agree on a value that never occurs.
        SqlLang::Div([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            match (sqlvalue_to_int(&lv), sqlvalue_to_int(&rv)) {
                (Some(a), Some(b)) if b != 0 => SqlValue::Int(a.wrapping_div(b)),
                _ => SqlValue::Null,
            }
        }
        SqlLang::Neg([c]) => {
            let cv = eval(expr, *c, symbols, assignment);
            match sqlvalue_to_int(&cv) {
                Some(i) => SqlValue::Int(-i),
                None => SqlValue::Null,
            }
        }

        //  Bitwise NOT: ~i ?invert all bits
        SqlLang::BitNot([c]) => {
            let cv = eval(expr, *c, symbols, assignment);
            match sqlvalue_to_int(&cv) {
                Some(i) => SqlValue::Int(!i),
                None => SqlValue::Null,
            }
        }

        //  String concatenation: NULL if either operand is NULL
        SqlLang::Concat([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            if lv.is_null() || rv.is_null() {
                SqlValue::Null
            } else {
                SqlValue::Text(format!(
                    "{}{}",
                    sqlvalue_to_text(&lv),
                    sqlvalue_to_text(&rv)
                ))
            }
        }

        //  Bitwise AND/OR: both operands must be integers, NULL propagation
        SqlLang::BitAnd([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            match (sqlvalue_to_int(&lv), sqlvalue_to_int(&rv)) {
                (Some(a), Some(b)) => SqlValue::Int(a & b),
                _ => SqlValue::Null,
            }
        }
        SqlLang::BitOr([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            match (sqlvalue_to_int(&lv), sqlvalue_to_int(&rv)) {
                (Some(a), Some(b)) => SqlValue::Int(a | b),
                _ => SqlValue::Null,
            }
        }

        //  Remainder: both operands must be integers, NULL if RHS is 0. Wrapping, like Div:
        //  i64::MIN % -1 overflows and panics, where SQLite answers 0.
        SqlLang::Remainder([l, r]) => {
            let lv = eval(expr, *l, symbols, assignment);
            let rv = eval(expr, *r, symbols, assignment);
            match (sqlvalue_to_int(&lv), sqlvalue_to_int(&rv)) {
                (Some(a), Some(b)) if b != 0 => SqlValue::Int(a.wrapping_rem(b)),
                _ => SqlValue::Null,
            }
        }

        //  BETWEEN: e >= lo AND e <= hi (with NULL propagation)
        SqlLang::Between([e, lo, hi]) => {
            let ev = eval(expr, *e, symbols, assignment);
            let lov = eval(expr, *lo, symbols, assignment);
            let hiv = eval(expr, *hi, symbols, assignment);
            if ev.is_null() || lov.is_null() || hiv.is_null() {
                SqlValue::Null
            } else {
                let ge = ev.partial_cmp(&lov);
                let le = ev.partial_cmp(&hiv);
                SqlValue::Bool(
                    (ge == Some(std::cmp::Ordering::Greater)
                        || ge == Some(std::cmp::Ordering::Equal))
                        && (le == Some(std::cmp::Ordering::Less)
                            || le == Some(std::cmp::Ordering::Equal)),
                )
            }
        }

        //  IS NULL / IS NOT NULL
        SqlLang::IsNull([c]) => SqlValue::Bool(eval(expr, *c, symbols, assignment).is_null()),
        SqlLang::IsNotNull([c]) => SqlValue::Bool(!eval(expr, *c, symbols, assignment).is_null()),

        //  IS FALSE: true only when value IS the boolean FALSE
        SqlLang::IsFalse([c]) => SqlValue::Bool(matches!(
            truth_value(&eval(expr, *c, symbols, assignment)),
            SqlValue::Bool(false)
        )),
        //  IS TRUE: true only when value IS the boolean TRUE
        SqlLang::IsTrue([c]) => SqlValue::Bool(matches!(
            truth_value(&eval(expr, *c, symbols, assignment)),
            SqlValue::Bool(true)
        )),
        //  IS NOT FALSE: true when value is NOT the boolean FALSE (includes NULL)
        SqlLang::IsNotFalse([c]) => SqlValue::Bool(!matches!(
            truth_value(&eval(expr, *c, symbols, assignment)),
            SqlValue::Bool(false)
        )),
        //  IS NOT TRUE: true when value is NOT the boolean TRUE (includes NULL)
        SqlLang::IsNotTrue([c]) => SqlValue::Bool(!matches!(
            truth_value(&eval(expr, *c, symbols, assignment)),
            SqlValue::Bool(true)
        )),
    }
}

/// Convert any SqlValue to an SQL truth value: TRUE, FALSE, or NULL.
/// In SQLite: 0 and '' are falsy; NULL is NULL; everything else is TRUE.
fn truth_value(v: &SqlValue) -> SqlValue {
    match v {
        SqlValue::Null => SqlValue::Null,
        SqlValue::Bool(b) => SqlValue::Bool(*b),
        SqlValue::Int(0) => SqlValue::Bool(false),
        SqlValue::Int(_) => SqlValue::Bool(true),
        // A condition of text or blob is true when its leading numeric prefix is non-zero, which is
        // not the same as "not the string 0": measured against SQLite 3.54, 'abc' and x'41' are
        // FALSE while '2abc' and x'31' are TRUE. Treating every non-empty string as TRUE made the
        // checker believe conditions that SQLite never enters.
        SqlValue::Text(s) => SqlValue::Bool(numeric_prefix(s) != 0.0),
        SqlValue::Blob(b) => SqlValue::Bool(numeric_prefix(&blob_as_text(b)) != 0.0),
    }
}

/// The leading number of a string, the way sqlite3AtoF reads one: optional whitespace, optional
/// sign, digits with an optional fraction and exponent. Zero when there is no such prefix.
fn numeric_prefix(s: &str) -> f64 {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() && b[i].is_ascii_whitespace() {
        i += 1;
    }
    let start = i;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        i += 1;
    }
    let mut digits = 0;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
        digits += 1;
    }
    if i < b.len() && b[i] == b'.' {
        i += 1;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
            digits += 1;
        }
    }
    if digits == 0 {
        return 0.0;
    }
    let mut end = i;
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        let mut j = i + 1;
        if j < b.len() && (b[j] == b'+' || b[j] == b'-') {
            j += 1;
        }
        let mut exponent_digits = 0;
        while j < b.len() && b[j].is_ascii_digit() {
            j += 1;
            exponent_digits += 1;
        }
        if exponent_digits > 0 {
            end = j;
        }
    }
    s[start..end].parse::<f64>().unwrap_or(0.0)
}

/// A blob read as text, which is how SQLite converts one before taking a number out of it.
fn blob_as_text(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

/// The bytes behind an x'..' literal. An odd or non-hex body is not a blob to SQLite, and giving
/// back no bytes leaves it comparing as an empty blob rather than silently as some other one.
fn decode_hex(hex: &str) -> Vec<u8> {
    let digits: Vec<u8> = hex.bytes().filter(|b| b.is_ascii_hexdigit()).collect();
    if digits.len() != hex.len() || digits.len() % 2 != 0 {
        return Vec::new();
    }
    digits
        .chunks(2)
        .map(|pair| {
            let value = std::str::from_utf8(pair).unwrap_or("0");
            u8::from_str_radix(value, 16).unwrap_or(0)
        })
        .collect()
}

/// One order comparison, with NULL on either side giving NULL.
fn order_compare<F>(
    expr: &RecExpr<SqlLang>,
    l: Id,
    r: Id,
    symbols: &SymbolTable,
    assignment: &HashMap<u64, SqlValue>,
    accept: F,
) -> SqlValue
where
    F: FnOnce(std::cmp::Ordering) -> bool,
{
    let lv = eval(expr, l, symbols, assignment);
    let rv = eval(expr, r, symbols, assignment);
    match lv.partial_cmp(&rv) {
        Some(ordering) => SqlValue::Bool(accept(ordering)),
        None => SqlValue::Null,
    }
}

/// Three-valued AND:  FALSE wins, NULL otherwise, TRUE only if both TRUE.
fn three_valued_and(l: SqlValue, r: SqlValue) -> SqlValue {
    match (truth_value(&l), truth_value(&r)) {
        (SqlValue::Bool(false), _) | (_, SqlValue::Bool(false)) => SqlValue::Bool(false),
        (SqlValue::Null, _) | (_, SqlValue::Null) => SqlValue::Null,
        (SqlValue::Bool(true), SqlValue::Bool(true)) => SqlValue::Bool(true),
        _ => unreachable!(), // truth_value only returns Null/Bool
    }
}

/// Three-valued OR: TRUE wins, NULL otherwise, FALSE only if both FALSE.
fn three_valued_or(l: SqlValue, r: SqlValue) -> SqlValue {
    match (truth_value(&l), truth_value(&r)) {
        (SqlValue::Bool(true), _) | (_, SqlValue::Bool(true)) => SqlValue::Bool(true),
        (SqlValue::Null, _) | (_, SqlValue::Null) => SqlValue::Null,
        (SqlValue::Bool(false), SqlValue::Bool(false)) => SqlValue::Bool(false),
        _ => unreachable!(),
    }
}

/// Three-valued NOT:  NOT NULL = NULL, NOT TRUE = FALSE, NOT FALSE = TRUE.
fn three_valued_not(v: SqlValue) -> SqlValue {
    match truth_value(&v) {
        SqlValue::Null => SqlValue::Null,
        SqlValue::Bool(b) => SqlValue::Bool(!b),
        _ => unreachable!(),
    }
}

/// Binary arithmetic on integers.  Bool converts to Int(0/1).  Returns Null if
/// either operand is Null or non-numeric.
fn arith2<F>(
    expr: &RecExpr<SqlLang>,
    l: Id,
    r: Id,
    symbols: &SymbolTable,
    assignment: &HashMap<u64, SqlValue>,
    f: F,
) -> SqlValue
where
    F: FnOnce(i64, i64) -> i64,
{
    let lv = eval(expr, l, symbols, assignment);
    let rv = eval(expr, r, symbols, assignment);
    let li = sqlvalue_to_int(&lv);
    let ri = sqlvalue_to_int(&rv);
    match (li, ri) {
        (Some(a), Some(b)) => SqlValue::Int(f(a, b)),
        _ => SqlValue::Null,
    }
}

/// Convert a SqlValue to a text representation.  Bool ?"0"/"1".
fn sqlvalue_to_text(v: &SqlValue) -> String {
    match v {
        SqlValue::Int(i) => i.to_string(),
        SqlValue::Bool(true) => "1".to_string(),
        SqlValue::Bool(false) => "0".to_string(),
        SqlValue::Text(t) => t.clone(),
        SqlValue::Null => String::new(),
        SqlValue::Blob(b) => blob_as_text(b),
    }
}

/// Convert a SqlValue to i64 if possible.  Bool ?0/1.  Text ?try parse.
fn sqlvalue_to_int(v: &SqlValue) -> Option<i64> {
    match v {
        SqlValue::Int(i) => Some(*i),
        SqlValue::Bool(true) => Some(1),
        SqlValue::Bool(false) => Some(0),
        // Not a strict parse: SQLite answers '3abc' + 1 with 4 and 'abc' + 1 with 1, taking the
        // leading number and calling a missing one zero. Refusing the conversion turned those into
        // NULL, so a rewrite over text operands was checked against arithmetic that never happens.
        SqlValue::Text(t) => Some(numeric_prefix(t) as i64),
        SqlValue::Blob(b) => Some(numeric_prefix(&blob_as_text(b)) as i64),
        SqlValue::Null => None,
    }
}

/// Resolve a Symbol: prefer literal value from the symbol table, fall back to
/// the random assignment.
fn eval_symbol(key: u64, symbols: &SymbolTable, assignment: &HashMap<u64, SqlValue>) -> SqlValue {
    if let Some(sql_expr) = symbols.get(&key) {
        match sql_expr {
            SqlExpr::Value(v) => {
                return sqlparser_value_to_sqlvalue(v);
            }
            _ => {}
        }
    }
    assignment.get(&key).cloned().unwrap_or(SqlValue::Null)
}

fn sqlparser_value_to_sqlvalue(v: &sqlparser::ast::Value) -> SqlValue {
    match v {
        sqlparser::ast::Value::Number(s, _) => {
            if let Ok(i) = s.parse::<i64>() {
                SqlValue::Int(i)
            } else {
                SqlValue::Text(s.clone())
            }
        }
        sqlparser::ast::Value::SingleQuotedString(s)
        | sqlparser::ast::Value::DoubleQuotedString(s)
        | sqlparser::ast::Value::NationalStringLiteral(s) => SqlValue::Text(s.clone()),
        // Hex literals ?BLOBs in SQLite (sort after numbers and text)
        sqlparser::ast::Value::HexStringLiteral(h) => SqlValue::Blob(decode_hex(h)),
        sqlparser::ast::Value::Null => SqlValue::Null,
        sqlparser::ast::Value::Boolean(b) => SqlValue::Bool(*b),
        _ => SqlValue::Null,
    }
}

/// Edge-case integers that stress boundary conditions.
const EDGE_VALUES: &[i64] = &[
    0,
    1,
    -1,
    i64::MAX,
    i64::MIN,
    2i64.pow(31) - 1,
    -(2i64.pow(31)), // 32-bit boundaries
    255,
    256,
    65535,
    65536, // byte / word boundaries
];

/// Classify what kind of random value a Symbol should receive, based on its
/// entry in the SymbolTable (the original SqlExpr).
///
/// Returns `Some(pool)` for boolean-valued opaque expressions (they should
/// only get Bool/Null assignments).  Returns `None` for column references
/// and regular value expressions (random assignment handles those).
fn classify_symbol(expr: &SqlExpr) -> Option<Vec<SqlValue>> {
    match expr {
        // Column references ?varied random values, handled by random_assignment
        SqlExpr::Identifier(_) | SqlExpr::CompoundIdentifier(_) => None,

        // Literals ?handled by eval_symbol from SymbolTable
        SqlExpr::Value(_) => None,

        // Boolean-valued opaque expressions (IN, EXISTS, subqueries, LIKE-alikes)
        // ?should only receive Bool/Null values.
        // In strict EGRAPH mode these rarely appear, but handle defensively.
        // LIKE, ILIKE, SIMILAR TO and IS [NOT] DISTINCT FROM are their own variants in sqlparser
        // rather than binary operators, so they used to fall through to the value arm below and an
        // opaque LIKE was handed integer assignments.
        SqlExpr::InList { .. }
        | SqlExpr::InSubquery { .. }
        | SqlExpr::InUnnest { .. }
        | SqlExpr::Exists { .. }
        | SqlExpr::Subquery(_)
        | SqlExpr::Like { .. }
        | SqlExpr::ILike { .. }
        | SqlExpr::SimilarTo { .. }
        | SqlExpr::AnyOp { .. }
        | SqlExpr::IsDistinctFrom(..)
        | SqlExpr::IsNotDistinctFrom(..) => Some(vec![
            SqlValue::Bool(false),
            SqlValue::Bool(true),
            SqlValue::Null,
        ]),

        // An opaque BinaryOp is boolean for some operators and a value for others, and the operator
        // says which. Treating them all as values gave an opaque LIKE integer assignments, so the
        // checker reasoned about `c0 LIKE 'a%'` as if it could be 42 - and a rule whose soundness
        // turns on the operand being 0, 1 or NULL was then checked against values it can never take.
        SqlExpr::BinaryOp { op, .. } => {
            if is_boolean_valued_operator(op) {
                Some(vec![
                    SqlValue::Bool(false),
                    SqlValue::Bool(true),
                    SqlValue::Null,
                ])
            } else {
                None
            }
        }

        // Other opaque expressions ?treat as value
        _ => None,
    }
}

/// Whether a binary operator yields a truth value rather than a value.
fn is_boolean_valued_operator(op: &BinaryOperator) -> bool {
    matches!(
        op,
        BinaryOperator::Eq
            | BinaryOperator::NotEq
            | BinaryOperator::Lt
            | BinaryOperator::Gt
            | BinaryOperator::LtEq
            | BinaryOperator::GtEq
            | BinaryOperator::And
            | BinaryOperator::Or
            | BinaryOperator::Xor
            | BinaryOperator::PGRegexMatch
            | BinaryOperator::PGRegexIMatch
            | BinaryOperator::PGRegexNotMatch
            | BinaryOperator::PGRegexNotIMatch
    )
}

/// Build a random assignment for every Symbol that appears in either tree.
/// Uses the SymbolTable to guide value types:
///   - Literals always use the literal value
///   - Boolean opaque expressions get Bool values
///   - Column references get varied random Int / Text / Null
fn random_assignment<R: rand::Rng>(
    symbol_ids: &HashSet<u64>,
    symbols: &SymbolTable,
    rng: &mut R,
) -> HashMap<u64, SqlValue> {
    let mut map = HashMap::new();
    let edge_count = EDGE_VALUES.len() as i64;

    for &sym in symbol_ids {
        // Check whether this symbol has a fixed type pool. It used to be leaked on the grounds of
        // being tiny and process-lived, but this runs once per symbol per validation sample, and a
        // sample is taken hundreds of times for every request a long run makes - so the leak grew
        // with the run rather than with the rule set. A local owns it now.
        let typed_pool: Option<Vec<SqlValue>> = symbols.get(&sym).and_then(|e| classify_symbol(e));

        if let Some(pool) = typed_pool {
            // Pick from the typed pool
            let idx = rng.gen_range(0..pool.len());
            map.insert(sym, pool[idx].clone());
        } else {
            // Check if it's a literal
            if let Some(sql_expr) = symbols.get(&sym) {
                if let SqlExpr::Value(_) = sql_expr {
                    // Literal ?will be handled by eval_symbol, skip assignment
                    continue;
                }
            }
            // Column reference or opaque value expression ?random value
            let roll = rng.gen::<f64>();
            if roll < NULL_PROB {
                map.insert(sym, SqlValue::Null);
            } else if roll < NULL_PROB + 0.15 {
                // 15%: edge case integer
                let idx = rng.gen_range(0..edge_count) as usize;
                map.insert(sym, SqlValue::Int(EDGE_VALUES[idx]));
            } else if roll < NULL_PROB + 0.25 {
                // 10%: short random text
                let len = rng.gen_range(1..=6);
                let s: String = (0..len)
                    .map(|_| rng.gen_range(b'a'..=b'z') as char)
                    .collect();
                map.insert(sym, SqlValue::Text(s));
            } else {
                // 63%: regular random int
                map.insert(sym, SqlValue::Int(rng.gen_range(-100..=100)));
            }
        }
    }
    map
}

/// Collect all Symbol ids used in a RecExpr.
fn collect_symbols(expr: &RecExpr<SqlLang>, root: Id, set: &mut HashSet<u64>) {
    match &expr[root] {
        SqlLang::Symbol(k) => {
            set.insert(*k);
        }
        _ => {
            for child in expr[root].children() {
                collect_symbols(expr, *child, set);
            }
        }
    }
}

/// Validate that `variant` produces the same result as `original` across
/// NUM_SAMPLES random assignments.
fn validate_variant(
    original: &RecExpr<SqlLang>,
    variant: &RecExpr<SqlLang>,
    symbols: &SymbolTable,
) -> bool {
    let orig_root = Id::from(original.as_ref().len() - 1);
    let var_root = Id::from(variant.as_ref().len() - 1);

    let mut symbol_set = HashSet::new();
    collect_symbols(original, orig_root, &mut symbol_set);
    collect_symbols(variant, var_root, &mut symbol_set);

    let mut rng = rand::thread_rng();

    for _ in 0..NUM_SAMPLES {
        let assignment = random_assignment(&symbol_set, symbols, &mut rng);
        let orig_r = eval(original, orig_root, symbols, &assignment);
        let var_r = eval(variant, var_root, symbols, &assignment);
        if orig_r != var_r {
            // eprintln!("[VALIDATE] FAIL: orig={:?} var={:?} at sample {}", orig_r, var_r, sample_num);
            return false;
        }
    }
    true
}

//  SQLite-backed Validation
//
// Instead of relying solely on the in-memory evaluator (which has subtle
// semantic differences from real SQLite), we run both expressions through
// an actual in-memory SQLite database.  This eliminates ALL false positives
// caused by the evaluator / serialization gap ?no more whack-a-mole.
//
// For each variant we:
//   1. Collect column identifiers from both expression ASTs
//   2. Create an in-memory SQLite table with those columns
//   3. Batch-insert N random test rows
//   4. Run: SELECT SUM((orig) IS NOT (var)) FROM table
//      ?0 means equivalent for every row (IS NOT is NULL-safe)
//   5. If sum > 0 the variant is NOT equivalent ?discard
//
// Returns Option<bool>:
//   Some(true)  ?equivalent
//   Some(false) ?NOT equivalent
//   None        ?SQLite setup failed (caller should fall back to in-memory eval)

const SQLITE_SAMPLES: usize = 500;

/// Edge-case values that are always included in every validation table to
/// catch e-graph compositional mismatches (e.g. NOT(IsFalse(NOT(x))) vs
/// NOT(IsFalse(x)) - these differ for all non-null values, but random
/// sampling might miss the critical NULL vs. non-NULL contrast).
///
/// The int64 and 2^53 entries are not decoration. i64::MIN was reachable only through the random
/// path (EDGE_VALUES at 8% per column, 1-in-11 to pick it), so over 500 samples it was missed
/// about 2.6% of the time - and that is exactly how a `sub-to-add` + `add-assoc` variant escaped
/// into a 300s run and produced the one false positive on 2026-09-08:
///
///     original  (c0 - c0) + (c0 >= c0)        = 0 + 1              = 1     (true)
///     variant   ((-c0) + (c0 >= c0)) + c0     = (9.22e18 + 1) + c0 = 0.0   (false)
///     because   -(-9223372036854775808) overflows int64 and becomes REAL, losing precision
///
/// Forcing the boundaries makes that deterministic instead of a 1-in-40 escape, and it guards
/// every future arithmetic rule for free rather than requiring each to be audited by hand.
const FORCED_VALUES: &[&str] = &[
    "NULL",
    "0",
    "1",
    "-1",
    // int64 boundaries: negation / addition / multiplication silently fall back to REAL here
    "-9223372036854775808",
    "9223372036854775807",
    "-9223372036854775807",
    "4611686018427387904", // 2^62 - doubling overflows
    // REAL/INTEGER precision boundary: beyond 2^53 a double can no longer represent every integer
    "9007199254740993",
    "-9007199254740993",
    // signed zero and the REAL/INTEGER divide
    "0.0",
    "-0.0",
    // affinity edges: TEXT that looks numeric, TEXT that does not, and BLOBs. The numeric-looking
    // spellings are the ones a declared affinity converts, and they are also what separates a
    // comparison by storage class from one by value: SQLite answers '5' > 9 with 1 on a column with
    // no affinity and with 0 on one declared INTEGER.
    "''",
    "'0'",
    "'5'",
    "' 1'",
    "'-0'",
    "'0.0'",
    "'1e3'",
    "'3abc'",
    "'abc'",
    "x''",
    "x'00'",
    "x'31'",
];

/// A declared type for a validation column, which is what gives it an affinity.
///
/// Switchable with EGRAPH_COLUMN_AFFINITIES=0 so the cost can be measured against the previous
/// behaviour in one binary, the way the rule set already is.
fn random_column_affinity<R: rand::Rng>(rng: &mut R) -> &'static str {
    if !column_affinities_enabled() {
        return "";
    }
    const AFFINITIES: &[&str] = &["", " INTEGER", " TEXT", " REAL", " NUMERIC", " BLOB"];
    AFFINITIES[rng.gen_range(0..AFFINITIES.len())]
}

fn column_affinities_enabled() -> bool {
    std::env::var("EGRAPH_COLUMN_AFFINITIES")
        .map(|v| v != "0")
        .unwrap_or(true)
}

/// Boundary values drawn independently per column, so a mismatch that needs *different* extreme
/// values in different columns is reachable. FORCED_VALUES fills every column of a row with the
/// same value, which cannot express that.
const BOUNDARY_COMBINATION_ROWS: usize = 24;

/// The values whose *pairing* decides a rewrite, for the two-column cross product below. A subset of
/// FORCED_VALUES: the int64 and 2^53 bounds where arithmetic changes type, signed zero, and one
/// numeric-looking and one non-numeric TEXT for affinity.
const EXTREME_VALUES: &[&str] = &[
    "NULL",
    "0",
    "1",
    "-1",
    "-9223372036854775808",
    "9223372036854775807",
    "4611686018427387904",
    "9007199254740993",
    "-0.0",
    "'5'",
    "'abc'",
    "x'00'",
];

/// Walk a SqlExpr tree recursively and collect every column identifier
/// (both qualified, e.g. `t0.c0`, and unqualified, e.g. `c0`).
fn collect_sql_identifiers(expr: &SqlExpr, ids: &mut HashSet<String>) {
    match expr {
        SqlExpr::Identifier(id) => {
            ids.insert(id.value.clone());
        }
        SqlExpr::CompoundIdentifier(parts) => {
            ids.insert(
                parts
                    .iter()
                    .map(|i| i.value.clone())
                    .collect::<Vec<_>>()
                    .join("."),
            );
        }
        SqlExpr::BinaryOp { left, right, .. } => {
            collect_sql_identifiers(left, ids);
            collect_sql_identifiers(right, ids);
        }
        SqlExpr::UnaryOp { expr: inner, .. } => {
            collect_sql_identifiers(inner, ids);
        }
        SqlExpr::Between {
            expr: inner,
            low,
            high,
            ..
        } => {
            collect_sql_identifiers(inner, ids);
            collect_sql_identifiers(low, ids);
            collect_sql_identifiers(high, ids);
        }
        SqlExpr::IsNull(inner)
        | SqlExpr::IsNotNull(inner)
        | SqlExpr::IsFalse(inner)
        | SqlExpr::IsTrue(inner)
        | SqlExpr::IsNotFalse(inner)
        | SqlExpr::IsNotTrue(inner)
        | SqlExpr::IsUnknown(inner)
        | SqlExpr::IsNotUnknown(inner) => {
            collect_sql_identifiers(inner, ids);
        }
        SqlExpr::Nested(inner) => {
            collect_sql_identifiers(inner, ids);
        }
        SqlExpr::InList {
            expr: inner, list, ..
        } => {
            collect_sql_identifiers(inner, ids);
            for item in list {
                collect_sql_identifiers(item, ids);
            }
        }
        // Function calls: in EGRAPH mode these are excluded from generation,
        // but handle defensively.  We skip recursing into function arguments
        // (the variant api differs across sqlparser versions).  If columns
        // inside function bodies are missed, the SQLite validation returns
        // None and the caller falls back to the in-memory evaluator.
        //
        // Literals, subqueries, wildcards, etc. ?stop recursing
        _ => {}
    }
}

/// Validate that `variant_expr` is semantically equivalent to `original_where`
/// by evaluating both against random data rows inside a real SQLite database.
fn validate_with_sqlite(
    original_where: &SqlExpr,
    variant_expr: &SqlExpr,
    source_sql: &str,
    num_samples: usize,
) -> Option<bool> {
    // 1. Collect column identifiers from both expression trees
    let mut id_set = HashSet::new();
    collect_sql_identifiers(original_where, &mut id_set);
    collect_sql_identifiers(variant_expr, &mut id_set);

    if id_set.is_empty() {
        // No columns ?expressions are constant, trivially equivalent
        return Some(true);
    }

    // 2. Group identifiers ?(table_name, columns)
    let mut table_name: Option<String> = None;
    let mut columns: HashSet<String> = HashSet::new();

    for id in &id_set {
        if let Some(dot_pos) = id.find('.') {
            let tbl = id[..dot_pos].to_string();
            let col = id[dot_pos + 1..].to_string();
            match &table_name {
                Some(existing) if *existing != tbl => {
                    // Multiple distinct table names ?can't model, fall back
                    return None;
                }
                _ => {
                    table_name = Some(tbl);
                }
            }
            columns.insert(col);
        } else {
            columns.insert(id.clone());
        }
    }

    let table_name = table_name.unwrap_or_else(|| "t".to_string());

    // 3. Sort columns for deterministic output (aids debugging)
    let cols_sorted: Vec<&String> = {
        let mut v: Vec<&String> = columns.iter().collect();
        v.sort();
        v
    };

    // 4. Open in-memory SQLite database
    let conn = match Connection::open_in_memory() {
        Ok(c) => c,
        Err(_) => return None,
    };

    // 5. Create test table with the collected columns
    //
    // Each column gets a declared affinity, drawn at random for this request. Without one every
    // column had BLOB affinity, so the validator never saw a comparison where SQLite converts an
    // operand before comparing it - which is most of what makes its comparison rules interesting,
    // and the reason a rule can be equivalent for two integers and not for an integer and a column
    // declared TEXT. One draw per request rather than one fixed choice: a long run then checks every
    // rule under many combinations instead of the same one every time.
    let mut affinity_rng = rand::thread_rng();
    let col_defs = cols_sorted
        .iter()
        .map(|c| format!("\"{}\"{}", c, random_column_affinity(&mut affinity_rng)))
        .collect::<Vec<_>>()
        .join(", ");
    if conn
        .execute(
            &format!("CREATE TABLE \"{}\" ({})", table_name, col_defs),
            [],
        )
        .is_err()
    {
        return None;
    }

    // 6. Batch-insert random test rows + forced edge-case rows
    let mut rng = rand::thread_rng();
    let mut insert_values: Vec<String> = Vec::with_capacity(num_samples + FORCED_VALUES.len());

    for _ in 0..num_samples {
        let vals: Vec<String> = cols_sorted
            .iter()
            .map(|_| random_sql_literal(&mut rng))
            .collect();
        insert_values.push(format!("({})", vals.join(", ")));
    }

    // Force edge-case rows so compositional mismatches (e.g. IsFalse + Not)
    // are always caught regardless of random sampling luck.
    for &forced in FORCED_VALUES {
        let vals = cols_sorted
            .iter()
            .map(|_| forced.to_string())
            .collect::<Vec<_>>();
        insert_values.push(format!("({})", vals.join(", ")));
    }

    // Boundary values drawn independently per column. The loop above puts the same value in every
    // column, which cannot expose a mismatch that needs, say, i64::MIN in one column and a numeric
    // TEXT in another.
    if cols_sorted.len() > 1 {
        use rand::Rng as _;
        // Every pairing of the extremes across the first two columns, rather than drawn at random.
        // A counterexample that needs one specific pair - (MAX, 1) for the associativity of addition,
        // say - had about a one in eight chance of appearing in the random rows below, so the escape
        // those rows were added to close was still mostly an escape. 144 rows make it certain.
        for &first in EXTREME_VALUES {
            for &second in EXTREME_VALUES {
                let vals: Vec<String> = cols_sorted
                    .iter()
                    .enumerate()
                    .map(|(index, _)| match index {
                        0 => first.to_string(),
                        1 => second.to_string(),
                        _ => FORCED_VALUES[rng.gen_range(0..FORCED_VALUES.len())].to_string(),
                    })
                    .collect();
                insert_values.push(format!("({})", vals.join(", ")));
            }
        }
        for _ in 0..BOUNDARY_COMBINATION_ROWS {
            let vals: Vec<String> = cols_sorted
                .iter()
                .map(|_| FORCED_VALUES[rng.gen_range(0..FORCED_VALUES.len())].to_string())
                .collect();
            insert_values.push(format!("({})", vals.join(", ")));
        }
    }

    let col_names = cols_sorted
        .iter()
        .map(|c| format!("\"{}\"", c))
        .collect::<Vec<_>>()
        .join(", ");

    let insert_sql = format!(
        "INSERT INTO \"{}\" ({}) VALUES {}",
        table_name,
        col_names,
        insert_values.join(", ")
    );
    if conn.execute(&insert_sql, []).is_err() {
        return None;
    }

    // 7. Compare all rows in a single query. IS NOT is NULL-safe:
    //    NULL IS NOT NULL is 0, so equal NULL results compare as equal.
    let orig_sql = format!("{}", fix_hex_format(original_where.clone(), source_sql));
    let var_sql = format!("{}", variant_expr);

    let compare_sql = format!(
        "SELECT SUM(({}) IS NOT ({})) FROM \"{}\"",
        orig_sql, var_sql, table_name
    );

    match conn.query_row(&compare_sql, [], |row| row.get::<_, i64>(0)) {
        Ok(0) => Some(true),  // all rows matched ?equivalent
        Ok(_) => Some(false), // at least one row differed ?NOT equivalent
        Err(_) => None,       // SQL error ?can't validate, fall back
    }
}

/// Generate a random SQL literal string for test-data insertion.
fn random_sql_literal(rng: &mut impl rand::Rng) -> String {
    let roll = rng.gen::<f64>();
    if roll < NULL_PROB {
        "NULL".to_string()
    } else if roll < NULL_PROB + 0.08 {
        // Edge-case integers
        let idx = rng.gen_range(0..EDGE_VALUES.len());
        format!("{}", EDGE_VALUES[idx])
    } else if roll < NULL_PROB + 0.18 {
        // Short random text (safe ASCII, no escaping needed)
        let len = rng.gen_range(1..=6);
        let s: String = (0..len)
            .map(|_| rng.gen_range(b'a'..=b'z') as char)
            .collect();
        format!("'{}'", s)
    } else {
        // Regular integer
        format!("{}", rng.gen_range(-100..=100))
    }
}

//  Main entry point

/// Functions whose value can change between two evaluations of the same expression. A predicate
/// containing one of these may not be written down twice, so only the single-evaluation identity
/// is offered for it.
const NONDETERMINISTIC_MARKERS: [&str; 9] = [
    "random(",
    "randomblob(",
    "changes(",
    "total_changes(",
    "last_insert_rowid(",
    "current_timestamp",
    "current_date",
    "current_time",
    "'now'",
];

/// Rewrites that hold for any predicate whatever it contains, used when the e-graph cannot take
/// the predicate apart. They leave the truth value alone under three-valued logic - NULL stays
/// NULL through a double negation and through `p AND p` / `p OR p` - but they change the shape
/// the query planner sees, which is what decides whether an index is used.
/// Whether a predicate may answer differently on a second evaluation.
pub fn is_nondeterministic(expr: &SqlExpr) -> bool {
    let rendered = expr.to_string().to_lowercase();
    NONDETERMINISTIC_MARKERS
        .iter()
        .any(|marker| rendered.contains(marker))
}

fn identity_variants(where_expr: &SqlExpr, source_sql: &str, max_variants: usize) -> Vec<SqlExpr> {
    if max_variants == 0 {
        return Vec::new();
    }
    let predicate = fix_hex_format(where_expr.clone(), source_sql);
    let nested = |e: &SqlExpr| SqlExpr::Nested(Box::new(e.clone()));
    let not = |e: SqlExpr| SqlExpr::UnaryOp {
        op: UnaryOperator::Not,
        expr: Box::new(e),
    };
    let mut variants = vec![not(nested(&not(nested(&predicate))))];

    if !is_nondeterministic(&predicate) {
        for op in [BinaryOperator::And, BinaryOperator::Or] {
            variants.push(SqlExpr::BinaryOp {
                left: Box::new(nested(&predicate)),
                op,
                right: Box::new(nested(&predicate)),
            });
        }
    }

    variants.truncate(max_variants);
    variants
}

/// How deep the subquery-decomposition recursion may go before it stops. Deeper nesting is
/// vanishingly rare in generated queries, and the limit keeps one pathological query from
/// spending the whole budget inside itself.
const MAX_SUBQUERY_DEPTH: usize = 3;

/// The WHERE predicate of a subquery, if it has one. GROUP BY/HAVING and the other subquery
/// clauses are left alone: only the WHERE is rewritten, so only its equivalence matters.
fn subquery_selection(query: &Query) -> Option<&SqlExpr> {
    match query.body.as_ref() {
        SetExpr::Select(sel) => sel.selection.as_ref(),
        _ => None,
    }
}

/// How the enclosing expression consumes a subquery's rows.
#[derive(Clone, Copy, PartialEq)]
enum SubqueryUse {
    /// `x IN (SELECT ...)`: only the set of rows matters.
    Set,
    /// `(SELECT ...)` as a value: SQLite takes the first row, an implicit LIMIT 1.
    Scalar,
}

/// Aggregates whose answer does not depend on the order rows arrive in. min/max are here although
/// two values equal under a column's collation ('A' and 'a' under NOCASE) tie and either may win;
/// the random path already relies on them in IN subqueries and no such tie has been seen to matter.
const ORDER_FREE_AGGREGATES: [&str; 3] = ["count(", "min(", "max("];

/// Aggregates, window functions and nondeterministic calls whose answer changes with the row order
/// or from one evaluation to the next. Sums of REAL values round differently in another order.
const ORDER_SENSITIVE_MARKERS: [&str; 8] = [
    "group_concat(",
    "string_agg(",
    "json_group_array(",
    "json_group_object(",
    "sum(",
    "avg(",
    "total(",
    " over ",
];

/// Whether a subquery answers the same whichever plan runs it. Rewriting its WHERE exists to
/// change the plan, so anything the plan decides becomes a false positive: `(SELECT a FROM t WHERE
/// b > 0)` returned 2 on 3.53.4 and the same subquery with `NOT (NOT (b > 0))` returned 1, because
/// "the first row" is whichever row the chosen plan reaches first. A set consumer does not care
/// about order until a LIMIT picks some rows out of it; a scalar consumer always picks one.
///
/// A picked row is only determined when the query is an aggregate without GROUP BY (it has exactly
/// one row) or when its ORDER BY names every projected expression, so rows tying on the sort key
/// project the same values. The textual ORDER BY match is conservative: `ORDER BY 1` or an alias
/// fails it and the subquery is left alone.
fn subquery_is_plan_independent(query: &Query, usage: SubqueryUse) -> bool {
    let select = match query.body.as_ref() {
        SetExpr::Select(select) => select,
        _ => return false,
    };
    let mut projected = Vec::new();
    for item in &select.projection {
        match item {
            SelectItem::UnnamedExpr(e) | SelectItem::ExprWithAlias { expr: e, .. } => {
                projected.push(e.to_string().to_lowercase())
            }
            _ => return false,
        }
    }
    let unsafe_marker = |text: &str| {
        ORDER_SENSITIVE_MARKERS.iter().any(|m| text.contains(m))
            || NONDETERMINISTIC_MARKERS.iter().any(|m| text.contains(m))
    };
    if projected.iter().any(|p| unsafe_marker(p)) {
        return false;
    }
    let is_aggregate_call = |p: &String| {
        ORDER_FREE_AGGREGATES.iter().any(|a| p.starts_with(a)) && p.ends_with(')')
    };
    let mentions_aggregate = projected
        .iter()
        .any(|p| ORDER_FREE_AGGREGATES.iter().any(|a| p.contains(a)));
    let grouped = !matches!(&select.group_by, GroupByExpr::Expressions(v, _) if v.is_empty())
        || select.having.is_some();
    if grouped {
        // Which row a bare column comes from inside a group is the plan's choice again.
        return false;
    }
    // With an aggregate in the projection, a bare column beside it takes its value from an
    // arbitrary row of the table - the query has one row, but not a determined one.
    let single_row = mentions_aggregate && projected.iter().all(is_aggregate_call);
    if mentions_aggregate && !single_row {
        return false;
    }

    let picks_rows = usage == SubqueryUse::Scalar || query.limit_clause.is_some();
    if !picks_rows || single_row {
        return true;
    }
    let ordered: HashSet<String> = match query.order_by.as_ref().map(|o| &o.kind) {
        Some(OrderByKind::Expressions(list)) => {
            list.iter().map(|o| o.expr.to_string().to_lowercase()).collect()
        }
        _ => return false,
    };
    projected.iter().all(|p| ordered.contains(p))
}

fn with_selection(query: &Query, new_selection: SqlExpr) -> Box<Query> {
    let mut q = query.clone();
    if let SetExpr::Select(sel) = q.body.as_mut() {
        sel.selection = Some(new_selection);
    }
    Box::new(q)
}

/// Variants for predicates built around a subquery: `x IN (SELECT ... WHERE p)`,
/// `EXISTS (SELECT ... WHERE p)` and `(SELECT ...) op x`. The e-graph language has no node for
/// these - the whole predicate falls through to one opaque Symbol - so its rules never see the
/// subquery's own WHERE. Rewriting that inner predicate and sewing each variant back into the
/// subquery reaches shapes the identity fallback cannot, while the outer structure stays
/// untouched: the subquery is not copied or moved, only its WHERE is replaced by an expression
/// that the validator has already shown to be three-valued equivalent to the original.
///
/// `NOT (...)` and plain parentheses around the whole predicate are unwrapped first and re-wrapped
/// afterwards, because both the parser (as `negated`) and the generator (as an explicit NOT) can
/// put them there.
fn try_subquery_variants(
    where_expr: &SqlExpr,
    source_sql: &str,
    max_variants: usize,
    iter_limit: usize,
    depth: usize,
) -> Option<Vec<SqlExpr>> {
    if depth >= MAX_SUBQUERY_DEPTH {
        return None;
    }

    // Unwrap transparent wrappers, remembering NOTs so the variants get them back.
    let mut not_wrapped = false;
    let mut cur = where_expr;
    loop {
        match cur {
            SqlExpr::Nested(inner) => cur = inner.as_ref(),
            SqlExpr::UnaryOp {
                op: UnaryOperator::Not,
                expr: inner,
            } => {
                not_wrapped = !not_wrapped;
                cur = inner.as_ref();
            }
            _ => break,
        }
    }

    let (subquery, rebuild): (&Query, Box<dyn Fn(SqlExpr) -> SqlExpr>) = match cur {
        SqlExpr::InSubquery {
            expr,
            subquery,
            negated,
        } => {
            if !subquery_is_plan_independent(subquery, SubqueryUse::Set) {
                return None;
            }
            let left = (**expr).clone();
            let q = (**subquery).clone();
            let neg = *negated;
            (
                subquery.as_ref(),
                Box::new(move |p: SqlExpr| SqlExpr::InSubquery {
                    expr: Box::new(left.clone()),
                    subquery: with_selection(&q, p),
                    negated: neg,
                }),
            )
        }
        SqlExpr::Exists { subquery, negated } => {
            let q = (**subquery).clone();
            let neg = *negated;
            (
                subquery.as_ref(),
                Box::new(move |p: SqlExpr| SqlExpr::Exists {
                    subquery: with_selection(&q, p),
                    negated: neg,
                }),
            )
        }
        SqlExpr::Subquery(q) => {
            if !subquery_is_plan_independent(q, SubqueryUse::Scalar) {
                return None;
            }
            let owned = (**q).clone();
            (
                q.as_ref(),
                Box::new(move |p: SqlExpr| SqlExpr::Subquery(with_selection(&owned, p))),
            )
        }
        _ => return None,
    };

    let inner_variants = match subquery_selection(subquery) {
        Some(inner_pred) => generate_equivalent_where_clauses_impl(
            inner_pred,
            source_sql,
            max_variants,
            iter_limit,
            depth + 1,
        )
        .ok()?,
        // No WHERE to rewrite, but giving the subquery one that every row satisfies still changes
        // what the planner does with it: measured on 3.53.4, `20 IN (SELECT y FROM t2)` over a
        // DESC index ignores the NULL in t2 (b16fe2bff5) and `... WHERE 1` does not.
        None => vec![SqlExpr::Value(sqlparser::ast::Value::Number("1".to_string(), false).into())],
    };
    if inner_variants.is_empty() {
        return None;
    }

    Some(
        inner_variants
            .into_iter()
            .map(|p| {
                let mut e = rebuild(p);
                if not_wrapped {
                    e = SqlExpr::UnaryOp {
                        op: UnaryOperator::Not,
                        expr: Box::new(e),
                    };
                }
                e
            })
            .collect(),
    )
}

/// Variants of a value expression - a SELECT-list item - obtained by rewriting the WHERE of one
/// subquery inside it. Only inner WHEREs are touched: the identities used on predicates
/// (`NOT (NOT p)`, `p AND p`) turn a value into 0/1 and would change what is projected. Each variant
/// rewrites the first subquery, walking left to right, that yields any.
pub fn value_subquery_variants(
    expr: &SqlExpr,
    source_sql: &str,
    max_variants: usize,
    iter_limit: usize,
) -> Vec<SqlExpr> {
    if max_variants == 0 {
        return Vec::new();
    }
    match expr {
        SqlExpr::InSubquery { .. } | SqlExpr::Exists { .. } | SqlExpr::Subquery(_) => {
            try_subquery_variants(expr, source_sql, max_variants, iter_limit, 0).unwrap_or_default()
        }
        SqlExpr::Nested(inner) => value_subquery_variants(inner, source_sql, max_variants, iter_limit)
            .into_iter()
            .map(|v| SqlExpr::Nested(Box::new(v)))
            .collect(),
        SqlExpr::UnaryOp { op, expr: inner } => {
            value_subquery_variants(inner, source_sql, max_variants, iter_limit)
                .into_iter()
                .map(|v| SqlExpr::UnaryOp {
                    op: op.clone(),
                    expr: Box::new(v),
                })
                .collect()
        }
        SqlExpr::IsNull(inner) => value_subquery_variants(inner, source_sql, max_variants, iter_limit)
            .into_iter()
            .map(|v| SqlExpr::IsNull(Box::new(v)))
            .collect(),
        SqlExpr::IsNotNull(inner) => {
            value_subquery_variants(inner, source_sql, max_variants, iter_limit)
                .into_iter()
                .map(|v| SqlExpr::IsNotNull(Box::new(v)))
                .collect()
        }
        SqlExpr::BinaryOp { left, op, right } => {
            let lefts = value_subquery_variants(left, source_sql, max_variants, iter_limit);
            if !lefts.is_empty() {
                return lefts
                    .into_iter()
                    .map(|v| SqlExpr::BinaryOp {
                        left: Box::new(v),
                        op: op.clone(),
                        right: right.clone(),
                    })
                    .collect();
            }
            value_subquery_variants(right, source_sql, max_variants, iter_limit)
                .into_iter()
                .map(|v| SqlExpr::BinaryOp {
                    left: left.clone(),
                    op: op.clone(),
                    right: Box::new(v),
                })
                .collect()
        }
        SqlExpr::Function(f) => {
            use sqlparser::ast::{FunctionArg, FunctionArgExpr, FunctionArguments};
            let FunctionArguments::List(list) = &f.args else {
                return Vec::new();
            };
            for (i, arg) in list.args.iter().enumerate() {
                let FunctionArg::Unnamed(FunctionArgExpr::Expr(e)) = arg else {
                    continue;
                };
                let variants = value_subquery_variants(e, source_sql, max_variants, iter_limit);
                if variants.is_empty() {
                    continue;
                }
                return variants
                    .into_iter()
                    .map(|v| {
                        let mut g = f.clone();
                        if let FunctionArguments::List(l) = &mut g.args {
                            l.args[i] = FunctionArg::Unnamed(FunctionArgExpr::Expr(v));
                        }
                        SqlExpr::Function(g)
                    })
                    .collect();
            }
            Vec::new()
        }
        _ => Vec::new(),
    }
}

/// The longest prefix of `s` that is at most `max_bytes` long and ends on a character boundary.
/// Slicing at a fixed byte index panics when it lands inside a multi-byte character, which the
/// generator's random strings (CJK, U+FFFF) reach.
fn truncate_at_char_boundary(s: &str, max_bytes: usize) -> &str {
    if s.len() <= max_bytes {
        return s;
    }
    let mut end = max_bytes;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

pub fn generate_equivalent_where_clauses(
    where_expr: &SqlExpr,
    source_sql: &str,
    max_variants: usize,
    iter_limit: usize,
) -> Result<Vec<SqlExpr>, String> {
    generate_equivalent_where_clauses_impl(where_expr, source_sql, max_variants, iter_limit, 0)
}

fn generate_equivalent_where_clauses_impl(
    where_expr: &SqlExpr,
    source_sql: &str,
    max_variants: usize,
    iter_limit: usize,
    depth: usize,
) -> Result<Vec<SqlExpr>, String> {
    // Subquery decomposition must come first: a predicate shaped around IN (SELECT), EXISTS or a
    // scalar subquery is one opaque Symbol to the language, so without this branch its own WHERE
    // is never seen by any rule. Each inner variant has already passed validation on its own, and
    // the subquery's other clauses stay untouched, so the outer predicate keeps its shape and its
    // semantics. Decomposition does not copy the subquery, so a predicate whose outer text
    // contains random() is still safe here; the recursion applies the nondeterminism check to the
    // inner predicate itself.
    // Room left over goes to the whole-predicate identities, which were all a subquery without an
    // inner WHERE used to get; this is a WHERE, so they are as sound here as anywhere.
    if let Some(mut variants) =
        try_subquery_variants(where_expr, source_sql, max_variants, iter_limit, depth)
    {
        if variants.len() < max_variants {
            let room = max_variants - variants.len();
            variants.extend(identity_variants(where_expr, source_sql, room));
        }
        return Ok(variants);
    }

    // The entry point refuses row-value comparisons (see contains_row_value); a subquery's own WHERE
    // reaches this function without passing that gate, so it is applied again here.
    if depth > 0 && contains_row_value(where_expr) {
        return Ok(Vec::new());
    }

    let (recexpr, symbols) = sql_expr_to_recexpr(where_expr, source_sql);

    // A predicate that can answer differently each time it is evaluated may not be written down
    // twice, and half the rule set does exactly that: eq-to-tight turns `x = y` into
    // `x >= y AND x <= y`, and the distribution and factoring rules copy a whole side. The marker
    // check used to guard only the atom path, so a predicate calling random() reached the rules
    // through every other path. identity_variants already knows to offer only the single-evaluation
    // identity for such a predicate.
    if is_nondeterministic(where_expr) {
        return Ok(identity_variants(where_expr, source_sql, max_variants));
    }

    let root = Id::from(recexpr.as_ref().len() - 1);
    if matches!(recexpr[root], SqlLang::Symbol(_)) {
        // The whole WHERE is one atom this language has no node for - a row-value IN, an
        // IN over a subquery, a LIKE. No rule can match it, so the e-graph used to hand
        // back nothing and the check compared a query against itself. The identities below
        // do not need to look inside the atom: they hold for any predicate, in any context.
        return Ok(identity_variants(where_expr, source_sql, max_variants));
    }

    let (egraph, root) = perform_rewrites(
        &recexpr,
        iter_limit,
        !compares_columns_of_unknown_collation(where_expr),
    );

    // Extract more variants than needed ?validation will filter some out.
    let variants = extract_variants(&egraph, root, max_variants * 5);

    let original_str = format!("{}", fix_hex_format(where_expr.clone(), source_sql));
    let mut results: Vec<SqlExpr> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    seen.insert(original_str);

    for v in &variants {
        if results.len() >= max_variants {
            break;
        }

        let var_root = Id::from(v.as_ref().len() - 1);
        if matches!(v[var_root], SqlLang::Symbol(_)) {
            continue;
        }

        let sql_expr = recexpr_to_sql_expr(v, &symbols);

        // Two-tier validation: SQLite-backed (authoritative) ?in-memory (fallback)
        let var_key = format!("{}", sql_expr);
        let var_label = if var_key.len() > 120 {
            format!("{}...", truncate_at_char_boundary(&var_key, 120))
        } else {
            var_key.clone()
        };

        match validate_with_sqlite(where_expr, &sql_expr, source_sql, SQLITE_SAMPLES) {
            Some(false) => {
                validate_log!(
                    "[VALIDATE] SQLite REJECT #{}.{} - discarded: {}",
                    results.len() + 1,
                    variants.len(),
                    var_label
                );
                continue;
            }
            Some(true) => {
                validate_log!(
                    "[VALIDATE] SQLite PASS #{}.{}: {}",
                    results.len() + 1,
                    variants.len(),
                    var_label
                );
            }
            None => {
                validate_log!(
                    "[VALIDATE] SQLite FAILED #{}.{} - fallback to in-memory: {}",
                    results.len() + 1,
                    variants.len(),
                    var_label
                );
                if !validate_variant(&recexpr, v, &symbols) {
                    validate_log!(
                        "[VALIDATE] In-memory REJECT #{}.{} - discarded: {}",
                        results.len() + 1,
                        variants.len(),
                        var_label
                    );
                    continue;
                }
                validate_log!(
                    "[VALIDATE] In-memory PASS #{}.{}: {}",
                    results.len() + 1,
                    variants.len(),
                    var_label
                );
            }
        }

        let key = format!("{}", sql_expr);
        if seen.insert(key) {
            results.push(sql_expr);
        }
    }

    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_predicate(sql: &str) -> SqlExpr {
        use sqlparser::ast::{SetExpr, Statement};
        use sqlparser::dialect::GenericDialect;
        use sqlparser::parser::Parser;
        let statements = Parser::parse_sql(&GenericDialect {}, sql).expect("parses");
        match &statements[0] {
            Statement::Query(query) => match query.body.as_ref() {
                SetExpr::Select(select) => select.selection.clone().expect("has a WHERE"),
                _ => panic!("not a plain SELECT"),
            },
            _ => panic!("not a query"),
        }
    }

    #[test]
    fn identity_variants_cover_an_atom_the_language_cannot_take_apart() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1)";
        let variants = identity_variants(&parse_predicate(sql), sql, 8);
        let rendered: Vec<String> = variants.iter().map(|v| format!("{}", v)).collect();
        assert_eq!(rendered.len(), 3, "{:?}", rendered);
        assert!(rendered[0].starts_with("NOT (NOT ("), "{:?}", rendered);
        assert!(rendered[1].contains(" AND "), "{:?}", rendered);
        assert!(rendered[2].contains(" OR "), "{:?}", rendered);
    }

    #[test]
    fn identity_variants_do_not_repeat_a_nondeterministic_predicate() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1 WHERE random() > 0)";
        let variants = identity_variants(&parse_predicate(sql), sql, 8);
        assert_eq!(variants.len(), 1, "only the single-evaluation identity is safe");
    }

    #[test]
    fn identity_variants_respect_the_requested_maximum() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1)";
        assert_eq!(identity_variants(&parse_predicate(sql), sql, 2).len(), 2);
        assert!(identity_variants(&parse_predicate(sql), sql, 0).is_empty());
    }

    #[test]
    fn an_in_subquery_with_a_rewritable_where_gets_inner_variants() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1 WHERE c1 = 5 AND c1 < 10)";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        assert!(
            !variants.is_empty(),
            "an IN (SELECT) whose WHERE is rewritable gets inner variants"
        );
        for v in &variants {
            let s = v.to_string();
            assert!(s.contains("SELECT"), "variant keeps the subquery: {s}");
            assert!(s.contains("IN ("), "variant keeps the IN shape: {s}");
        }
    }

    #[test]
    fn an_exists_with_a_rewritable_where_gets_inner_variants() {
        let sql = "SELECT * FROM t0 WHERE EXISTS (SELECT 1 FROM t1 WHERE t1.x = c0 AND t1.y > 5)";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        assert!(!variants.is_empty(), "EXISTS with a rewritable WHERE gets inner variants");
        for v in &variants {
            let s = v.to_string();
            assert!(s.contains("EXISTS"), "variant keeps EXISTS: {s}");
            assert!(s.contains("SELECT"), "variant keeps the subquery: {s}");
        }
    }

    #[test]
    fn a_subquery_without_where_gets_an_always_true_one_and_the_identities() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1)";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        let rendered: Vec<String> = variants.iter().map(|v| v.to_string()).collect();
        assert_eq!(rendered.len(), 4, "{:?}", rendered);
        assert_eq!(rendered[0], "c0 IN (SELECT c1 FROM t1 WHERE 1)");
        assert!(rendered[1].starts_with("NOT (NOT ("), "{:?}", rendered);
    }

    /// Whether the subquery gets decomposed: through the SELECT-list entry point when the SQL
    /// starts with a projected subquery, through the WHERE one otherwise.
    fn scalar_subquery_is_decomposed(sql: &str) -> bool {
        if sql.starts_with("SELECT (") {
            return !value_subquery_variants(&parse_projection(sql), sql, 8, 20).is_empty();
        }
        try_subquery_variants(&parse_predicate(sql), sql, 8, 20, 0).is_some()
    }

    #[test]
    fn a_scalar_subquery_whose_first_row_the_plan_picks_is_left_alone() {
        // 3.53.4: this subquery answered 2, and 1 once its WHERE became NOT (NOT (b > 0)).
        assert!(!scalar_subquery_is_decomposed(
            "SELECT (SELECT a FROM t WHERE b > 0) FROM t0"
        ));
        // Ordered, but not by what it projects: rows tying on b may differ in a.
        assert!(!scalar_subquery_is_decomposed(
            "SELECT (SELECT a FROM t WHERE b > 0 ORDER BY b LIMIT 1) FROM t0"
        ));
    }

    #[test]
    fn a_scalar_subquery_with_a_determined_first_row_is_decomposed() {
        assert!(scalar_subquery_is_decomposed(
            "SELECT (SELECT a FROM t WHERE b > 0 ORDER BY b, a LIMIT 1) FROM t0"
        ));
        assert!(scalar_subquery_is_decomposed(
            "SELECT (SELECT count(*) FROM t WHERE b > 0) FROM t0"
        ));
    }

    #[test]
    fn order_sensitive_or_limited_set_subqueries_are_left_alone() {
        for sql in [
            "SELECT * FROM t0 WHERE c0 IN (SELECT a FROM t WHERE b > 0 LIMIT 2)",
            "SELECT * FROM t0 WHERE c0 IN (SELECT group_concat(a) FROM t WHERE b > 0)",
            "SELECT * FROM t0 WHERE c0 IN (SELECT a + count(*) FROM t WHERE b > 0)",
            "SELECT * FROM t0 WHERE c0 IN (SELECT a FROM t WHERE b > 0 GROUP BY b)",
        ] {
            assert!(!scalar_subquery_is_decomposed(sql), "{sql}");
        }
        assert!(scalar_subquery_is_decomposed(
            "SELECT * FROM t0 WHERE c0 IN (SELECT max(a) FROM t WHERE b > 0)"
        ));
    }

    fn parse_projection(sql: &str) -> SqlExpr {
        use sqlparser::ast::{SelectItem, SetExpr, Statement};
        use sqlparser::dialect::GenericDialect;
        use sqlparser::parser::Parser;
        let statements = Parser::parse_sql(&GenericDialect {}, sql).expect("parses");
        let Statement::Query(query) = &statements[0] else { panic!("not a query") };
        let SetExpr::Select(select) = query.body.as_ref() else { panic!("not a SELECT") };
        match &select.projection[0] {
            SelectItem::UnnamedExpr(e) | SelectItem::ExprWithAlias { expr: e, .. } => e.clone(),
            _ => panic!("not an expression"),
        }
    }

    #[test]
    fn a_projected_subquery_gets_its_where_rewritten_and_nothing_else() {
        // 8ebae091a9: on 3.53.4 this answers '1', and ' 1' once the WHERE is NOT (NOT (...)).
        let sql = "SELECT (SELECT s.c1 FROM t3 s WHERE a.k = s.c1 ORDER BY s.c1 LIMIT 1) FROM t0 a";
        let variants = value_subquery_variants(&parse_projection(sql), sql, 8, 20);
        assert!(!variants.is_empty());
        for v in &variants {
            let s = v.to_string();
            assert!(s.starts_with("(SELECT s.c1 FROM t3 s WHERE "), "value keeps its shape: {s}");
            assert!(s.ends_with("ORDER BY s.c1 LIMIT 1)"), "{s}");
        }
    }

    #[test]
    fn a_subquery_inside_a_projected_expression_is_reached() {
        let sql = "SELECT abs((SELECT count(*) FROM t WHERE t.a > 1 AND t.a < 9)) + 1 FROM t0";
        let variants = value_subquery_variants(&parse_projection(sql), sql, 8, 20);
        assert!(!variants.is_empty());
        for v in &variants {
            let s = v.to_string();
            assert!(s.starts_with("abs((SELECT count(*) FROM t WHERE "), "{s}");
            assert!(s.ends_with(")) + 1"), "{s}");
        }
    }

    #[test]
    fn a_row_value_is_refused_where_it_is_an_operand_and_nowhere_else() {
        for sql in [
            "SELECT * FROM t0 WHERE (c0, c1) <> ('45', '46')",
            "SELECT * FROM t0 WHERE NOT (('45', '46') BETWEEN (c0, c1) AND (c0, c1))",
            "SELECT * FROM t0 WHERE c2 > 1 AND (c0, c1) = (1, 2)",
        ] {
            assert!(contains_row_value(&parse_predicate(sql)), "{sql}");
        }
        for sql in [
            "SELECT c1 FROM t0 WHERE (((c1),(c1)) IN (SELECT c.c1, min(c.c1) FROM t0 AS c))",
            "SELECT * FROM t0 WHERE (c0, c1) IN ((1, 2), (3, 4))",
            "SELECT * FROM t0 WHERE c2 > 1 AND (c0, c1) IN (SELECT a, b FROM t1)",
        ] {
            assert!(!contains_row_value(&parse_predicate(sql)), "{sql}");
        }
    }

    #[test]
    fn a_row_value_in_gets_variants_that_leave_the_in_whole() {
        // 6c97dbe469: on 3.53.4 the base returns no row and NOT (NOT (...)) returns -1.
        let sql = "SELECT c1 FROM t0 WHERE (((c1),(c1)) IN (SELECT c.c1, min(c.c1) FROM t0 AS c))";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        assert!(!variants.is_empty());
        for v in &variants {
            let s = v.to_string();
            assert!(
                s.contains("((c1), (c1)) IN (SELECT c.c1, min(c.c1) FROM t0 AS c)"),
                "the IN atom is untouched: {s}"
            );
        }
    }

    #[test]
    fn a_projected_value_never_gets_a_predicate_identity() {
        // NOT (NOT x) would project 0/1 instead of x.
        let sql = "SELECT (SELECT a FROM t ORDER BY a LIMIT 1) FROM t0";
        let variants = value_subquery_variants(&parse_projection(sql), sql, 8, 20);
        let rendered: Vec<String> = variants.iter().map(|v| v.to_string()).collect();
        assert_eq!(rendered, vec!["(SELECT a FROM t WHERE 1 ORDER BY a LIMIT 1)".to_string()]);
    }

    #[test]
    fn not_wrapped_insubquery_keeps_its_not() {
        let sql = "SELECT * FROM t0 WHERE NOT (c0 IN (SELECT c1 FROM t1 WHERE c1 = 5 AND c1 < 10))";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        assert!(!variants.is_empty());
        for v in &variants {
            let s = v.to_string();
            assert!(s.contains("NOT"), "the outer NOT survives the rewrite: {s}");
        }
    }

    #[test]
    fn nested_subqueries_do_not_explode() {
        let sql = "SELECT * FROM t0 WHERE c0 IN (SELECT c1 FROM t1 WHERE c1 IN (SELECT c2 FROM t2 WHERE c2 > 1 AND c2 < 9))";
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 8, 20).expect("generates");
        for v in &variants {
            assert!(v.to_string().contains("SELECT"), "variants stay well-formed");
        }
    }

    /// Evaluates a WHERE text with no free symbols, the way the rule checker does.
    fn eval_predicate(sql: &str) -> SqlValue {
        let expr = parse_predicate(&format!("SELECT 1 WHERE {}", sql));
        let mut rec = RecExpr::default();
        let mut symbols = SymbolTable::new();
        let mut counter = 0u64;
        let mut dedup = HashMap::new();
        let root = sql_expr_to_recexpr_impl(&expr, &mut rec, &mut symbols, &mut counter, &mut dedup, sql);
        eval(&rec, root, &symbols, &HashMap::new())
    }

    #[test]
    fn an_order_comparison_with_null_is_null_not_false() {
        // Measured against SQLite 3.54: `NULL < 5 IS NULL` answers 1.
        assert_eq!(eval_predicate("NULL < 5"), SqlValue::Null);
        assert_eq!(eval_predicate("NULL >= 5"), SqlValue::Null);
        assert_eq!(eval_predicate("5 > NULL"), SqlValue::Null);
        assert_eq!(eval_predicate("NOT (NULL < 5)"), SqlValue::Null);
    }

    #[test]
    fn dividing_by_zero_is_null() {
        assert_eq!(eval_predicate("5 / 0"), SqlValue::Null);
        assert_eq!(eval_predicate("5 % 0"), SqlValue::Null);
        assert_eq!(eval_predicate("6 / 3"), SqlValue::Int(2));
    }

    #[test]
    fn the_remainder_of_the_smallest_integer_does_not_overflow() {
        // Measured against SQLite 3.54: 0|0|-1|-1|1. i64::MIN % -1 used to panic the server.
        assert_eq!(eval_predicate("(-9223372036854775807 - 1) % -1"), SqlValue::Int(0));
        assert_eq!(eval_predicate("(-9223372036854775807 - 1) % 1"), SqlValue::Int(0));
        assert_eq!(eval_predicate("(-9223372036854775807 - 1) % 7"), SqlValue::Int(-1));
        assert_eq!(eval_predicate("-7 % 3"), SqlValue::Int(-1));
        assert_eq!(eval_predicate("7 % -3"), SqlValue::Int(1));
    }

    #[test]
    fn truncation_backs_off_to_a_character_boundary() {
        assert_eq!(truncate_at_char_boundary("abc", 120), "abc");
        assert_eq!(truncate_at_char_boundary("ab甙c", 3), "ab");
        assert_eq!(truncate_at_char_boundary("ab甙c", 5), "ab甙");
        assert_eq!(truncate_at_char_boundary("ab甙c", 2), "ab");
    }

    #[test]
    fn a_variant_longer_than_the_log_label_with_a_wide_character_does_not_panic() {
        // From the 2026-10-03 trunk run: the 120-byte label cut through 'ⱞ'.
        let sql = "SELECT DISTINCT rt1.c1, rt1.c0 FROM rt1 WHERE (((((rt1.c2) IS TRUE))<=(CAST('YLⱞpFtWBq' AS TEXT))))";
        generate_equivalent_where_clauses(&parse_predicate(sql), sql, 16, 20).expect("generates");
    }

    #[test]
    fn text_and_blob_conditions_follow_their_leading_number() {
        // Measured: 'abc' and x'41' are FALSE, '2abc' and x'31' are TRUE, '0e5' is FALSE.
        assert_eq!(truth_value(&SqlValue::Text("abc".into())), SqlValue::Bool(false));
        assert_eq!(truth_value(&SqlValue::Text("2abc".into())), SqlValue::Bool(true));
        assert_eq!(truth_value(&SqlValue::Text("0e5".into())), SqlValue::Bool(false));
        assert_eq!(truth_value(&SqlValue::Text("  3".into())), SqlValue::Bool(true));
        assert_eq!(truth_value(&SqlValue::Blob(vec![0x41])), SqlValue::Bool(false));
        assert_eq!(truth_value(&SqlValue::Blob(vec![0x31])), SqlValue::Bool(true));
        assert_eq!(truth_value(&SqlValue::Blob(vec![0x00])), SqlValue::Bool(false));
    }

    #[test]
    fn two_different_blobs_are_not_the_same_value() {
        assert_eq!(eval_predicate("x'41' = x'42'"), SqlValue::Bool(false));
        assert_eq!(eval_predicate("x'41' < x'42'"), SqlValue::Bool(true));
        assert_eq!(eval_predicate("x'41' = x'41'"), SqlValue::Bool(true));
        // A blob outranks text, which outranks a number.
        assert_eq!(eval_predicate("x'41' > 'zz'"), SqlValue::Bool(true));
        assert_eq!(eval_predicate("'5' > 9"), SqlValue::Bool(true));
        assert_eq!(eval_predicate("'5' = 5"), SqlValue::Bool(false));
    }

    #[test]
    fn true_and_one_are_the_same_value() {
        assert_eq!(eval_predicate("TRUE = 1"), SqlValue::Bool(true));
        assert_eq!(eval_predicate("FALSE = 0"), SqlValue::Bool(true));
    }

    #[test]
    fn a_comparison_of_two_columns_withholds_the_swap_rules() {
        // Measured on SQLite 3.54: with `a TEXT COLLATE NOCASE` and `b TEXT`, `a = b` is 1 and
        // `b = a` is 0, so turning the comparison around is not an equivalence.
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a = b"
        )));
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE t.a < t.b"
        )));
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a > 1 AND (b <= c)"
        )));
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a BETWEEN b AND b"
        )));

        // The same side twice is the same collation whatever it is, and a literal has none.
        assert!(!compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a = a"
        )));
        assert!(!compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a > 3 AND b < 5"
        )));
        assert!(!compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE a IS NULL"
        )));

        let withheld = make_rewrite_rules(false);
        for name in OPERAND_SWAPPING_RULES {
            assert!(
                !withheld.iter().any(|r| r.name.as_str() == *name),
                "{} should be withheld",
                name
            );
        }
        assert!(make_rewrite_rules(true)
            .iter()
            .any(|r| r.name.as_str() == "eq-sym"));
    }

    #[test]
    fn two_explicit_collations_withhold_the_swap_rules() {
        // Measured on SQLite 3.54: `('ABC' COLLATE nocase) = ('abc' COLLATE binary)` is 1 and the
        // swapped form is 0 - the left operand's explicit collation wins. Reported as a mismatch
        // by the 2026-10-03 trunk run: the original found no row, the variant found 'TR'.
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            r#"SELECT * FROM "t0" WHERE ("c0" COLLATE nocase)='abc' COLLATE binary"#
        )));
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 WHERE ('ABC' COLLATE nocase) < ('abc' COLLATE binary)"
        )));
        assert!(compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE (a COLLATE nocase) BETWEEN ('x' COLLATE binary) AND 'z'"
        )));

        // One explicit side decides whichever way round it is written.
        assert!(!compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 FROM t WHERE (a COLLATE nocase) = 'abc'"
        )));
        assert!(!compares_columns_of_unknown_collation(&parse_predicate(
            "SELECT 1 WHERE 'abc' = ('ABC' COLLATE nocase)"
        )));
    }

    #[test]
    fn the_reported_collation_mismatch_gets_no_swapped_variant() {
        let sql = r#"SELECT * FROM "t0" WHERE ("c0" COLLATE nocase)='abc' COLLATE binary"#;
        let variants =
            generate_equivalent_where_clauses(&parse_predicate(sql), sql, 16, 20).expect("generates");
        assert!(!variants.is_empty(), "the rules that keep the order still apply");
        for v in &variants {
            let s = v.to_string();
            let binary_side = s.find("'abc' COLLATE binary").expect("keeps the literal side");
            let nocase_side = s.find("\"c0\" COLLATE nocase").expect("keeps the column side");
            assert!(nocase_side < binary_side, "the NOCASE side moved right: {s}");
        }
    }

    #[test]
    fn an_opaque_atom_keeps_its_brackets() {
        // Measured: this came back as `c1 COLLATE NOCASE - c2 IN (c2)`, which SQLite reads as
        // `((c1 COLLATE NOCASE) - c2) IN (c2)` - a different question, reported as a defect.
        let sql = "SELECT * FROM t0 WHERE (t0.c1 COLLATE NOCASE) - (t0.c2 IN (t0.c2))";
        let expr = parse_predicate(sql);
        let (recexpr, symbols) = sql_expr_to_recexpr(&expr, sql);
        let rendered = recexpr_to_sql_expr(&recexpr, &symbols).to_string();
        assert!(
            rendered.contains("(t0.c2 IN (t0.c2))"),
            "the IN lost its brackets: {}",
            rendered
        );
        assert!(
            rendered.contains("(t0.c1 COLLATE NOCASE)"),
            "the COLLATE lost its brackets: {}",
            rendered
        );
    }

    #[test]
    fn no_rule_merges_a_boolean_test_with_a_plain_value() {
        // not(not(x)) is x as a truth value and 1 as a value, so merging them let extraction put a
        // boolean test where an arithmetic operand belonged: (NOT (NOT c0)) + 1 against c0 + 1.
        let sql = "SELECT * FROM t0 WHERE ((NOT (NOT t0.c0)) + 1) > 0";
        let variants = generate_equivalent_where_clauses(&parse_predicate(sql), sql, 16, 20)
            .expect("generates");
        for variant in &variants {
            let rendered = variant.to_string();
            assert!(
                !rendered.contains("t0.c0 + 1") && !rendered.contains("1 + t0.c0"),
                "a variant dropped the double negation from a value position: {}",
                rendered
            );
        }
    }

    #[test]
    fn arithmetic_is_only_rewritten_where_sqlite_agrees() {
        // Rebracketing moves where an integer overflow happens, and SQLite promotes an overflow to
        // REAL: measured over the validator's own edge values, add-assoc disagrees on 140 triples of
        // 1728 and mul-assoc on 118. Commutativity disagrees on none, so those two stay.
        let names: Vec<String> = make_rewrite_rules(true)
            .iter()
            .map(|r| r.name.to_string())
            .collect();
        for gone in [
            "add-assoc-l",
            "add-assoc-r",
            "mul-assoc-l",
            "mul-assoc-r",
            "sub-to-add",
            "add-neg-to-sub",
            "sub-antisym",
            "double-neg",
        ] {
            assert!(!names.iter().any(|n| n == gone), "{} is back", gone);
        }
        assert!(names.iter().any(|n| n == "add-comm"));
        assert!(names.iter().any(|n| n == "mul-comm"));
    }

    /// Where the 3200 extraction walks per request actually go.
    ///
    /// Both measured attempts to raise variant yield - weighting the pick, raising the node budget -
    /// came back negative, so this counts the outcomes directly instead of guessing which channel is
    /// the bottleneck: walks abandoned on a cycle, walks that rendered a string already seen, and
    /// walks that produced a new variant.
    #[test]
    fn extraction_walk_outcomes() {
        let sql = "SELECT * FROM t0 WHERE (t0.c0 > 3 AND t0.c1 <= 7) OR t0.c2 <> 0";
        let expr = parse_predicate(sql);
        let (recexpr, _symbols) = sql_expr_to_recexpr(&expr, sql);
        let (egraph, root) = perform_rewrites(&recexpr, 20, true);

        let mut seen: HashSet<String> = HashSet::new();
        let mut abandoned = 0usize;
        let mut duplicate = 0usize;
        let mut fresh = 0usize;
        let attempts = 16 * 200;
        for _ in 0..attempts {
            match extract_randomized(&egraph, root) {
                None => abandoned += 1,
                Some(candidate) => {
                    if seen.insert(candidate.to_string()) {
                        fresh += 1;
                    } else {
                        duplicate += 1;
                    }
                }
            }
        }
        println!(
            "e-graph classes={} nodes={} | attempts={} abandoned={} ({:.1}%) duplicate={} ({:.1}%) fresh={}",
            egraph.number_of_classes(),
            egraph.total_size(),
            attempts,
            abandoned,
            100.0 * abandoned as f64 / attempts as f64,
            duplicate,
            100.0 * duplicate as f64 / attempts as f64,
            fresh
        );
        assert!(fresh > 0, "no variant was extracted at all");

        // What the pipeline after extraction keeps. extract_variants stops as soon as it has
        // max_variants * 5 distinct candidates, so the 3200-attempt budget above is never the limit in
        // production; the loss is downstream of it.
        let candidates = extract_variants(&egraph, root, 16 * 5);
        let kept = generate_equivalent_where_clauses(&expr, sql, 16, 20).expect("generates");
        println!(
            "requested=16 candidates_extracted={} survived_validation_and_dedup={}",
            candidates.len(),
            kept.len()
        );
    }

    #[test]
    fn three_valued_and_false_wins_over_null() {
        assert_eq!(
            three_valued_and(SqlValue::Bool(false), SqlValue::Null),
            SqlValue::Bool(false)
        );
        assert_eq!(
            three_valued_and(SqlValue::Null, SqlValue::Bool(false)),
            SqlValue::Bool(false)
        );
    }

    #[test]
    fn three_valued_or_true_wins_over_null() {
        assert_eq!(
            three_valued_or(SqlValue::Bool(true), SqlValue::Null),
            SqlValue::Bool(true)
        );
        assert_eq!(
            three_valued_or(SqlValue::Null, SqlValue::Bool(true)),
            SqlValue::Bool(true)
        );
    }
}
