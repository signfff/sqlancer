"""Checks e-graph rewrite rules for soundness against a real SQLite.

A rule `lhs => rhs` is sound only if the two sides are interchangeable everywhere a query can put
them. Both sides are evaluated by SQLite itself - not by the rewrite server's evaluator, which is
known to differ from it - for every binding of the rule's variables to:

  * a literal from LITERALS, spelled into the SQL (a literal has no affinity and no collation), or
  * a column of table v, whose declared type gives an affinity and whose COLLATE a collation, read
    over every row of v (each row holds one of LITERALS, converted by the column's affinity), or
  * a column with an explicit COLLATE NOCASE.

A binding fails when quote(lhs) IS NOT quote(rhs) - quote() tells 1 from 1.0 from '1' - or, for a
rule whose sides produce a value rather than a truth value, when a comparison against the result
answers differently: a value can carry an explicit collation to the comparison around it.

Rules named in OPERAND_SWAPPING are run under the guard the server applies to them: they are
withheld whenever both operands mention a column, or both carry an explicit collation.

Usage:
  python rulegate.py --from-source ../../../egraph-server/src/sql_rewrite.rs   # rules in the code
  python rulegate.py rules.txt                                                  # name ; lhs => rhs
Writes one line per rule to stdout and, with --json FILE, the counterexamples.
"""
import argparse
import itertools
import random
import json
import re
import sqlite3
import sys
from multiprocessing import Pool

LITERALS = ["NULL", "0", "1", "-1", "2", "9223372036854775807", "-9223372036854775808", "0.5", "1.0",
            "-0.0", "1e308", "'abc'", "'ABC'", "''", "'1'", "' 1'", "'1.0'", "'a '", "x''", "x'31'",
            # REAL boundaries: '1e999' overflows to +Inf in a REAL/NUMERIC column and in CAST(... AS
            # REAL), and lower(+Inf) is the text 'inf', whose truth value is 0 while +Inf's is 1 -
            # istrue-lower failed on exactly this (trunk-optmask11). 'inf'/'nan' as text cast to 0.0,
            # '0x10' casts to 0.0 while its numeric prefix is 0, and '12.5abc' casts by prefix.
            "'1e999'", "'-1e999'", "'inf'", "'nan'", "'0x10'", "' 0x10'", "'99999999999999999999'",
            "'2.5e-999'", "'12.5abc'", "x'3130'",
            # Boolean constants, 2026-10-13: the server now reproduces SQLite's `x IN ()` -> FALSE
            # parse-time rewrite, so rules meet literal TRUE/FALSE (also on the right of IS via
            # is-sym from `FALSE IS x`). SQLite folds a boolean constant on the RIGHT of IS into the
            # IS TRUE family; the server's printer moves it to the LEFT (to_sql models that below),
            # where SQLite value-compares it like any other number.
            "TRUE", "FALSE"]
COLUMNS = ["c_int", "c_real", "c_text", "c_blob", "c_num", "c_any", "c_nocase", "c_rtrim"]
EXPLICIT = "COLL"  # (vN.c_text COLLATE NOCASE)
SCHEMA = ("CREATE TABLE v(c_int INTEGER, c_real REAL, c_text TEXT, c_blob BLOB, c_num NUMERIC, c_any, "
          "c_nocase TEXT COLLATE NOCASE, c_rtrim TEXT COLLATE RTRIM)")

SAMPLE = 60000
OPERAND_SWAPPING = {"eq-sym", "noteq-sym", "gt-to-lt", "lt-to-gt", "gteq-to-lteq", "lteq-to-gteq", "is-sym", "isnot-sym"}

BINARY = {"and": "AND", "or": "OR", "=": "=", "<>": "<>", "<": "<", ">": ">", "<=": "<=", ">=": ">=",
          "+": "+", "-": "-", "*": "*", "/": "/", "rem": "%", "concat": "||", "bitand": "&", "bitor": "|"}
PREFIX = {"not": "NOT", "neg": "-", "bitnot": "~"}
POSTFIX = {"isnull": "IS NULL", "isnotnull": "IS NOT NULL", "istrue": "IS TRUE", "isfalse": "IS FALSE",
           "isnottrue": "IS NOT TRUE", "isnotfalse": "IS NOT FALSE"}
EXTENSION = {"is": "({} IS {})", "isnot": "({} IS NOT {})", "uplus": "(+{})",
             "in": "({} IN {})", "notin": "({} NOT IN {})", "list2": "({}, {})",
             "iif": "iif({}, {}, {})", "case": "(CASE WHEN {} THEN {} ELSE {} END)",
             "case2": "(CASE WHEN {} THEN {} WHEN {} THEN {} ELSE {} END)",
             "casesimple": "(CASE {} WHEN {} THEN {} ELSE {} END)",
             "coalesce": "coalesce({}, {})", "ifnull": "ifnull({}, {})", "nullif": "nullif({}, {})",
             "cast_int": "CAST({} AS INTEGER)", "cast_real": "CAST({} AS REAL)",
             "cast_text": "CAST({} AS TEXT)", "cast_numeric": "CAST({} AS NUMERIC)",
             "cast_blob": "CAST({} AS BLOB)",
             "in1": "({} IN ({}))", "in2": "({} IN ({}, {}))", "in3": "({} IN ({}, {}, {}))",
             "like": "({} LIKE {})", "glob": "({} GLOB {})",
             "collate_binary": "({} COLLATE BINARY)", "collate_nocase": "({} COLLATE NOCASE)",
             "collate_rtrim": "({} COLLATE RTRIM)"}
# Operators whose result is a value that can carry a collation onward, not a truth value.
VALUE_OPS = {"+", "-", "*", "/", "rem", "concat", "bitand", "bitor", "neg", "bitnot", "uplus", "iif", "case",
             "case2", "casesimple",
             "coalesce", "ifnull", "nullif", "cast_int", "cast_real", "cast_text", "cast_numeric", "cast_blob",
             "collate_binary", "collate_nocase", "collate_rtrim"}

# Context around a value-producing side: (template, operand) with {e} for the side.
# Numeric-looking text and typed columns expose affinity: a column carries its affinity into a
# comparison and an expression over it does not (c_int = '1' is 1, (+c_int) = '1' is 0).
COMPARISON_PARTNERS = ["'ABC'", "'abc '", "1", "'1'", "'1.0'", "x'31'", "w.c_text", "w.c_nocase", "w.c_int",
                       "w.c_real"]
COMPARISON_TEMPLATES = ["(({e}) = {w})", "({w} = ({e}))", "(({e}) < {w})", "({w} < ({e}))"]


def parse(text):
    tokens = re.findall(r"\(|\)|[^\s()]+", text)
    pos = 0

    def walk():
        nonlocal pos
        tok = tokens[pos]
        pos += 1
        if tok != "(":
            return tok
        node = [tokens[pos]]
        pos += 1
        while tokens[pos] != ")":
            node.append(walk())
        pos += 1
        return node

    tree = walk()
    if pos != len(tokens):
        raise ValueError("trailing tokens in " + text)
    return tree


def variables(tree, out=None):
    out = [] if out is None else out
    if isinstance(tree, str):
        if tree.startswith("?") and tree not in out:
            out.append(tree)
    else:
        for child in tree[1:]:
            variables(child, out)
    return out


def _is_boolean_literal(text):
    # SQLite's IS fold sees through parens and COLLATE: `x IS (TRUE COLLATE NOCASE)` folds like
    # `x IS TRUE` (measured on trunk), so the server printer swaps those off the right of IS too.
    t = text.strip()
    while True:
        m = re.match(r"^\((.*)\)$", t)
        if not m:
            break
        t = m.group(1).strip()
    return re.match(r"^(?:TRUE|FALSE)(?: COLLATE \w+)*$", t) is not None


def to_sql(tree, binding):
    if isinstance(tree, str):
        if tree.startswith("?"):
            return binding[tree]
        return tree
    op, args = tree[0], [to_sql(a, binding) for a in tree[1:]]
    if op in BINARY:
        return "(%s %s %s)" % (args[0], BINARY[op], args[1])
    if op in PREFIX:
        return "(%s %s)" % (PREFIX[op], args[0])
    if op in POSTFIX:
        return "(%s %s)" % (args[0], POSTFIX[op])
    if op == "between":
        return "(%s BETWEEN %s AND %s)" % tuple(args)
    # Nodes the server's language does not have yet, so candidate rules that need them can be
    # judged before anyone implements them.
    if op in EXTENSION:
        # SQLite folds `x IS <TRUE/FALSE constant>` into the truth-value test, discarding x; the
        # server's printer moves a boolean literal off the right of IS/IS NOT (where the fold would
        # fire) to the left (where SQLite value-compares it), so the gate spells it the same way.
        if op in ("is", "isnot") and _is_boolean_literal(args[1]) and not _is_boolean_literal(args[0]):
            args[0], args[1] = args[1], args[0]
        return EXTENSION[op].format(*args)
    if op.startswith("fn:"):
        return "%s(%s)" % (op[3:], ", ".join(args))
    raise ValueError("unknown operator " + op)


def variables_under_values(tree, under=False, out=None):
    """Variables of a pattern that sit below a value-producing node (arithmetic, ||, CASE, a call,
    CAST, COLLATE's operand is not counted): where the server sees an explicit COLLATE there, it
    rewrites nothing (explicit_collation_under_a_value), so such bindings never meet the rule."""
    out = set() if out is None else out
    if isinstance(tree, str):
        if tree.startswith("?") and under:
            out.add(tree)
        return out
    op = tree[0]
    value = op in VALUE_OPS and not op.startswith("collate_") or op.startswith("fn:")
    for child in tree[1:]:
        variables_under_values(child, under or value, out)
    return out


def produces_value(tree):
    # A bare variable can be a column, which brings its affinity and collation to the comparison.
    return isinstance(tree, str) or tree[0] in VALUE_OPS or tree[0].startswith("fn:")


# Literals carrying an explicit COLLATE: a constant behaves unlike a column in places (SQLite turns
# x IN (constant) into x = +constant, and the constant's COLLATE then decides), and the column with
# an explicit COLLATE alone missed that - in2-dup-to-in1 passed against the IN bug of 2026-10-08.
COLLATED_LITERALS = ["('ABC' COLLATE NOCASE)", "('a ' COLLATE RTRIM)", "(1 COLLATE NOCASE)",
                     # Booleans under COLLATE: SQLite's IS fold looks through the collate (2026-10-13).
                     "(TRUE COLLATE NOCASE)", "(FALSE COLLATE BINARY)"]


def options():
    return [("lit", lit) for lit in LITERALS + COLLATED_LITERALS] + [("col", c) for c in COLUMNS] +         [("col", EXPLICIT)]


def sample_bindings(n):
    rnd = random.Random(n)
    out = []
    while len(out) < SAMPLE:
        choice = tuple(rnd.choice(options()) for _ in range(n))
        if sum(1 for kind, _ in choice if kind == "col") <= 2:
            out.append(choice)
    return out


def check_rule(rule):
    try:
        return check_rule_unguarded(rule)
    except (ValueError, IndexError) as e:
        # One rule the checker cannot read must not take the batch down with it.
        return {"name": rule[0], "status": "ERROR", "detail": "cannot check: %s" % e}


def check_rule_unguarded(rule):
    name, lhs_text, rhs_text = rule
    lhs, rhs = parse(lhs_text), parse(rhs_text)
    vs = variables(lhs)
    for v in variables(rhs):
        if v not in vs:
            return {"name": name, "status": "ERROR", "detail": "rhs variable %s not bound by lhs" % v}
    # Every rule, truth-valued ones too: an explicit COLLATE anywhere inside a CASE condition or
    # branch gives the CASE result its collation, even through isnull, NOT, = or LIKE, whose own value
    # is an integer (measured 2026-10-08: CASE WHEN (('x' COLLATE NOCASE) IS NULL) THEN 'abc' ELSE
    # 'abc' END = 'ABC' is 1, without the COLLATE 0). A rule that drops, adds or reorders such a
    # COLLATE changes the comparison around an enclosing CASE.
    contexts = ["{e}", "((CASE WHEN {e} THEN 'abc' ELSE 'abc' END) = 'ABC')",
                "((CASE WHEN 0 THEN {e} ELSE 'abc' END) = 'ABC')"]
    if produces_value(lhs) or produces_value(rhs):
        contexts += [t.format(e="{e}", w=w) for t in COMPARISON_TEMPLATES for w in COMPARISON_PARTNERS]
    guarded_vars = variables_under_values(lhs)
    db = sqlite3.connect(":memory:")
    db.execute(SCHEMA)
    for lit in LITERALS:
        db.execute("INSERT INTO v VALUES (%s)" % ", ".join([lit] * len(COLUMNS)))
    db.execute("CREATE TABLE w AS SELECT * FROM v")
    checked = 0
    # Exhaustive up to three variables (24389 bindings); beyond that a fixed-seed sample of
    # SAMPLE bindings, each still run in every context. Four variables would be 707281, five 20.5M.
    # A sampled binding puts at most two variables on columns: each column variable multiplies the
    # rows a statement joins by 20, and five of them made one statement 3.2 million rows.
    choices = list(itertools.product(options(), repeat=len(vs))) if len(vs) <= 3 else sample_bindings(len(vs))
    for choice in choices:
        cols = [c for c in choice if c[0] == "col"]
        if name in OPERAND_SWAPPING and len(vs) >= 2:
            # The server withholds these when both operands mention a column, or both carry an
            # explicit COLLATE (compares_columns_of_unknown_collation).
            a, b = choice[0], choice[1]
            explicit = lambda c: c == ("col", EXPLICIT) or (c[0] == "lit" and c[1] in COLLATED_LITERALS)
            if (a[0] == "col" and b[0] == "col") or (explicit(a) and explicit(b)):
                continue
        binding, frm, shown = {}, [], []
        for i, (var, (kind, val)) in enumerate(zip(vs, choice)):
            if kind == "lit":
                binding[var] = val
                shown.append("'%s=%s'" % (var, val.replace("'", "''")))
            else:
                alias = "v%d" % i
                frm.append("v AS " + alias)
                expr = "(%s.c_text COLLATE NOCASE)" % alias if val == EXPLICIT else "%s.%s" % (alias, val)
                binding[var] = expr
                shown.append("'%s=%s:' || quote(%s)" % (var, val, expr))
        l_sql, r_sql = to_sql(lhs, binding), to_sql(rhs, binding)
        # All contexts of a binding in one statement; only a binding that fails somewhere is run
        # again context by context to say where.
        # The server rewrites nothing where an explicit COLLATE sits under a value-producing
        # expression (explicit_collation_under_a_value): a binding that puts one below a value node of
        # the lhs never meets the rule, and the enclosing-CASE contexts only meet bindings without one.
        is_explicit = lambda c: c == ("col", EXPLICIT) or (c[0] == "lit" and c[1] in COLLATED_LITERALS)
        if any(is_explicit(c) and v in guarded_vars for v, c in zip(vs, choice)):
            continue
        explicit_binding = any(is_explicit(c) for c in choice)
        usable = [c for c in contexts if not (explicit_binding and "CASE WHEN" in c and "{e}" in c
                                                and c.startswith("((CASE"))]
        groups = [[c for c in usable if "w." not in c]]
        if len(frm) < 2:
            groups.append([c for c in usable if "w." in c])
        for group in groups:
            if not group:
                continue
            uses_w = "w." in group[0]
            sources = frm + (["w"] if uses_w else [])
            source_sql = ("FROM " + ", ".join(sources)) if sources else ""
            any_mismatch = " OR ".join("quote(%s) IS NOT quote(%s)" % (c.format(e=l_sql), c.format(e=r_sql))
                                       for c in group)
            checked += len(group)
            try:
                hit = db.execute("SELECT 1 %s WHERE %s LIMIT 1" % (source_sql, any_mismatch)).fetchone()
            except sqlite3.Error as e:
                return {"name": name, "status": "FAIL", "context": group[0], "binding": str(choice),
                        "detail": "error: %s" % e, "lhs": lhs_text, "rhs": rhs_text}
            if hit is None:
                continue
            for ctx in group:
                le, re_ = ctx.format(e=l_sql), ctx.format(e=r_sql)
                sql = "SELECT %s, quote(%s), quote(%s)%s %s WHERE quote(%s) IS NOT quote(%s) LIMIT 1" % (
                    " || ' ' || ".join(shown) if shown else "''", le, re_,
                    ", quote(w.c_text)" if uses_w else ", NULL", source_sql, le, re_)
                row = db.execute(sql).fetchone()
                if row is not None:
                    return {"name": name, "status": "FAIL", "context": ctx, "binding": row[0],
                            "lhs_value": row[1], "rhs_value": row[2], "partner": row[3],
                            "lhs": lhs_text, "rhs": rhs_text, "lhs_sql": le, "rhs_sql": re_}
    return {"name": name, "status": "PASS", "checked": checked}


RULE_IN_SOURCE = re.compile(r'^(\s*)(//\s*)?rewrite!\("([^"]+)";\s*"([^"]+)"\s*=>\s*"([^"]+)"\)')


def rules_from_source(path, include_commented):
    rules = []
    for line in open(path, encoding="utf-8"):
        m = RULE_IN_SOURCE.match(line)
        if m and (include_commented or not m.group(2)):
            rules.append((m.group(3) + (" (commented out)" if m.group(2) else ""), m.group(4), m.group(5)))
    return rules


def rules_from_file(path):
    rules = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, body = [p.strip() for p in line.split(";", 1)]
        lhs, rhs = [p.strip() for p in body.split("=>")]
        rules.append((name, lhs, rhs))
    return rules


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rules", nargs="?")
    ap.add_argument("--from-source")
    ap.add_argument("--include-commented", action="store_true")
    ap.add_argument("--json")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()
    rules = rules_from_source(args.from_source, args.include_commented) if args.from_source \
        else rules_from_file(args.rules)
    with Pool(args.jobs) as pool:
        results = pool.map(check_rule, rules, chunksize=1)
    for r in results:
        if r["status"] == "PASS":
            print("PASS  %-34s %d checks" % (r["name"], r["checked"]))
        elif r["status"] == "FAIL":
            print("FAIL  %-34s %s | %s -> %s vs %s" % (r["name"], r.get("context"), r.get("binding"),
                                                       r.get("lhs_value"), r.get("rhs_value")))
        else:
            print("%-5s %-34s %s" % (r["status"], r["name"], r.get("detail")))
    print("sqlite %s: %d pass, %d fail" % (sqlite3.sqlite_version,
                                           sum(r["status"] == "PASS" for r in results),
                                           sum(r["status"] != "PASS" for r in results)))
    if args.json:
        json.dump(results, open(args.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
