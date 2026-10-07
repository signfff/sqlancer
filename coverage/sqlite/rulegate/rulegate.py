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
import json
import re
import sqlite3
import sys
from multiprocessing import Pool

LITERALS = ["NULL", "0", "1", "-1", "2", "9223372036854775807", "-9223372036854775808", "0.5", "1.0",
            "-0.0", "1e308", "'abc'", "'ABC'", "''", "'1'", "' 1'", "'1.0'", "'a '", "x''", "x'31'"]
COLUMNS = ["c_int", "c_real", "c_text", "c_blob", "c_num", "c_any", "c_nocase", "c_rtrim"]
EXPLICIT = "COLL"  # (vN.c_text COLLATE NOCASE)
SCHEMA = ("CREATE TABLE v(c_int INTEGER, c_real REAL, c_text TEXT, c_blob BLOB, c_num NUMERIC, c_any, "
          "c_nocase TEXT COLLATE NOCASE, c_rtrim TEXT COLLATE RTRIM)")

OPERAND_SWAPPING = {"eq-sym", "noteq-sym", "gt-to-lt", "lt-to-gt", "gteq-to-lteq", "lteq-to-gteq"}

BINARY = {"and": "AND", "or": "OR", "=": "=", "<>": "<>", "<": "<", ">": ">", "<=": "<=", ">=": ">=",
          "+": "+", "-": "-", "*": "*", "/": "/", "rem": "%", "concat": "||", "bitand": "&", "bitor": "|"}
PREFIX = {"not": "NOT", "neg": "-", "bitnot": "~"}
POSTFIX = {"isnull": "IS NULL", "isnotnull": "IS NOT NULL", "istrue": "IS TRUE", "isfalse": "IS FALSE",
           "isnottrue": "IS NOT TRUE", "isnotfalse": "IS NOT FALSE"}
EXTENSION = {"is": "({} IS {})", "isnot": "({} IS NOT {})", "uplus": "(+{})",
             "in": "({} IN {})", "notin": "({} NOT IN {})", "list2": "({}, {})",
             "iif": "iif({}, {}, {})", "case": "(CASE WHEN {} THEN {} ELSE {} END)",
             "coalesce": "coalesce({}, {})", "ifnull": "ifnull({}, {})", "nullif": "nullif({}, {})",
             "cast_int": "CAST({} AS INTEGER)", "cast_real": "CAST({} AS REAL)",
             "cast_text": "CAST({} AS TEXT)", "cast_numeric": "CAST({} AS NUMERIC)"}
# Operators whose result is a value that can carry a collation onward, not a truth value.
VALUE_OPS = {"+", "-", "*", "/", "rem", "concat", "bitand", "bitor", "neg", "bitnot", "uplus", "iif", "case",
             "coalesce", "ifnull", "nullif", "cast_int", "cast_real", "cast_text", "cast_numeric"}

# Context around a value-producing side: (template, operand) with {e} for the side.
COMPARISON_PARTNERS = ["'ABC'", "'abc '", "1", "w.c_text", "w.c_nocase"]
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
        return EXTENSION[op].format(*args)
    raise ValueError("unknown operator " + op)


def produces_value(tree):
    return not isinstance(tree, str) and tree[0] in VALUE_OPS


def options():
    return [("lit", lit) for lit in LITERALS] + [("col", c) for c in COLUMNS] + [("col", EXPLICIT)]


def check_rule(rule):
    name, lhs_text, rhs_text = rule
    lhs, rhs = parse(lhs_text), parse(rhs_text)
    vs = variables(lhs)
    for v in variables(rhs):
        if v not in vs:
            return {"name": name, "status": "ERROR", "detail": "rhs variable %s not bound by lhs" % v}
    contexts = ["{e}"]
    if produces_value(lhs) or produces_value(rhs):
        contexts += [t.format(e="{e}", w=w) for t in COMPARISON_TEMPLATES for w in COMPARISON_PARTNERS]
    db = sqlite3.connect(":memory:")
    db.execute(SCHEMA)
    for lit in LITERALS:
        db.execute("INSERT INTO v VALUES (%s)" % ", ".join([lit] * len(COLUMNS)))
    db.execute("CREATE TABLE w AS SELECT * FROM v")
    checked = 0
    for choice in itertools.product(options(), repeat=len(vs)):
        cols = [c for c in choice if c[0] == "col"]
        if name in OPERAND_SWAPPING and len(vs) >= 2:
            a, b = choice[0], choice[1]
            if a[0] == "col" and b[0] == "col":
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
        for ctx in contexts:
            uses_w = "w." in ctx
            if uses_w and len(frm) >= 2:
                continue  # keeps the join small; a partner column is tried with up to one column operand
            le, re_ = ctx.format(e=l_sql), ctx.format(e=r_sql)
            sources = frm + (["w"] if uses_w else [])
            sql = "SELECT %s, quote(%s), quote(%s)%s %s WHERE quote(%s) IS NOT quote(%s) LIMIT 1" % (
                " || ' ' || ".join(shown) if shown else "''", le, re_,
                ", quote(w.c_text)" if uses_w else ", NULL",
                ("FROM " + ", ".join(sources)) if sources else "", le, re_)
            checked += 1
            try:
                row = db.execute(sql).fetchone()
            except sqlite3.Error as e:
                return {"name": name, "status": "FAIL", "context": ctx, "binding": str(choice),
                        "detail": "error: %s" % e, "lhs": lhs_text, "rhs": rhs_text}
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
