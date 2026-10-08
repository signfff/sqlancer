"""End-to-end check of the rewrite server: random predicates in, variants out, both run in SQLite.

rulegate.py proves a rule sound as an equation; this proves the whole path - parsing the query,
turning it into the e-graph language, the rules, extraction, printing back to SQL - keeps every
answer. A predicate over table t0 (columns of every affinity and collation, rows of edge values) is
sent to the server; the original and each variant are run as SELECT rowid ... WHERE <pred> and their
row sets compared. Any difference is a defect in the server, not in SQLite.

Usage: python e2e.py [--n 500] [--seed 1] [--url http://127.0.0.1:3000] [--features is,in,...]
"""
import argparse
import base64
import json
import random
import sqlite3
import sys
import time
import urllib.request
from collections import Counter

VALUES = ["NULL", "0", "1", "-1", "2", "9223372036854775807", "-9223372036854775808", "0.5", "1.0", "-0.0",
          "1e308", "'abc'", "'ABC'", "''", "'1'", "' 1'", "'1.0'", "'a '", "x''", "x'31'"]
COLUMNS = ["c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7"]
SCHEMA = ("CREATE TABLE t0(c0 INTEGER, c1 REAL, c2 TEXT, c3 BLOB, c4 NUMERIC, c5, c6 TEXT COLLATE NOCASE, "
          "c7 TEXT COLLATE RTRIM)")


class Gen:
    def __init__(self, rnd, features):
        self.r = rnd
        self.features = features

    def leaf(self):
        if self.r.random() < 0.6:
            return "t0." + self.r.choice(COLUMNS)
        # Now and then a literal with an explicit COLLATE: constants and columns differ there.
        if self.r.random() < 0.1:
            return "(%s COLLATE %s)" % (self.r.choice(VALUES), self.r.choice(["NOCASE", "RTRIM", "BINARY"]))
        return self.r.choice(VALUES)

    def value(self, d):
        if d <= 0 or self.r.random() < 0.4:
            return self.leaf()
        choices = ["arith", "concat", "bit", "neg"]
        if "coalesce" in self.features:
            choices += ["coalesce", "ifnull", "nullif", "iif"]
        if "case" in self.features:
            choices += ["case"]
        if "cast" in self.features:
            choices += ["cast"]
        if "func" in self.features:
            choices += ["func", "func"]
        k = self.r.choice(choices)
        a, b = self.value(d - 1), self.value(d - 1)
        if k == "arith":
            return "(%s %s %s)" % (a, self.r.choice(["+", "-", "*", "/", "%"]), b)
        if k == "concat":
            return "(%s || %s)" % (a, b)
        if k == "bit":
            return "(%s %s %s)" % (a, self.r.choice(["&", "|"]), b)
        if k == "neg":
            return "(- %s)" % a
        if k in ("coalesce", "ifnull", "nullif"):
            return "%s(%s, %s)" % (k, a, b)
        if k == "iif":
            return "iif(%s, %s, %s)" % (self.pred(d - 1), a, b)
        if k == "case":
            form = self.r.randint(0, 3)
            if form == 0:
                return "(CASE WHEN %s THEN %s END)" % (self.pred(d - 1), a)
            if form == 1:
                return "(CASE WHEN %s THEN %s WHEN %s THEN %s ELSE %s END)" % (
                    self.pred(d - 1), a, self.pred(d - 1), b, self.leaf())
            if form == 2:
                return "(CASE %s WHEN %s THEN %s ELSE %s END)" % (self.leaf(), self.leaf(), a, b)
            return "(CASE WHEN %s THEN %s ELSE %s END)" % (self.pred(d - 1), a, b)
        if k == "func":
            f1 = ["abs", "length", "lower", "upper", "typeof", "trim", "ltrim", "rtrim", "hex", "quote",
                  "unicode", "likely", "unlikely", "round", "sign", "octet_length"]
            f2 = ["instr", "substr", "max", "min", "round", "trim", "ifnull", "glob", "like", "likelihood"]
            if self.r.random() < 0.6:
                return "%s(%s)" % (self.r.choice(f1), a)
            f = self.r.choice(f2)
            if f == "likelihood":
                return "likelihood(%s, 0.5)" % a
            return "%s(%s, %s)" % (f, a, b)
        if k == "cast":
            return "CAST(%s AS %s)" % (a, self.r.choice(["INTEGER", "REAL", "TEXT", "NUMERIC", "BLOB"]))
        raise AssertionError(k)

    def pred(self, d):
        choices = ["cmp", "cmp", "cmp", "between", "isnull", "istrue"]
        if d > 0:
            choices += ["and", "or", "not", "and", "or"]
        if "is" in self.features:
            choices += ["is", "is"]
        if "in" in self.features:
            choices += ["in", "in"]
        if "like" in self.features:
            choices += ["like"]
        k = self.r.choice(choices)
        v = lambda: self.value(max(0, d - 1))
        if k == "cmp":
            return "(%s %s %s)" % (v(), self.r.choice(["=", "<>", "<", ">", "<=", ">="]), v())
        if k == "between":
            return "(%s %sBETWEEN %s AND %s)" % (v(), self.r.choice(["", "NOT "]), v(), v())
        if k == "isnull":
            return "(%s %s)" % (v(), self.r.choice(["IS NULL", "IS NOT NULL", "ISNULL", "NOTNULL"]))
        if k == "istrue":
            return "(%s %s)" % (v(), self.r.choice(["IS TRUE", "IS FALSE", "IS NOT TRUE", "IS NOT FALSE"]))
        if k in ("and", "or"):
            return "(%s %s %s)" % (self.pred(d - 1), k.upper(), self.pred(d - 1))
        if k == "not":
            return "(NOT %s)" % self.pred(d - 1)
        if k == "is":
            return "(%s %s %s)" % (v(), self.r.choice(["IS", "IS NOT"]), v())
        if k == "in":
            items = ", ".join(v() for _ in range(self.r.randint(0, 3)))
            return "(%s %sIN (%s))" % (v(), self.r.choice(["", "NOT "]), items)
        if k == "like":
            return "(%s %s %s)" % (v(), self.r.choice(["LIKE", "GLOB", "NOT LIKE"]), v())
        raise AssertionError(k)


def variants(url, sql, n):
    body = json.dumps({"query_base64": base64.b64encode(sql.encode()).decode(), "max_variants": n}).encode()
    req = urllib.request.Request(url + "/generate-variants", data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read()).get("variants", []), None
    except urllib.error.HTTPError as e:
        return [], json.loads(e.read() or b"{}").get("error", str(e))


def rows(db, sql):
    try:
        return sorted(r[0] for r in db.execute(sql)), None
    except sqlite3.Error as e:
        return None, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=500)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--url", default="http://127.0.0.1:3000")
    ap.add_argument("--features", default="")
    ap.add_argument("--max-variants", type=int, default=16)
    args = ap.parse_args()
    rnd = random.Random(args.seed)
    features = set(f for f in args.features.split(",") if f)
    db = sqlite3.connect(":memory:")
    db.execute(SCHEMA)
    for i in range(60):
        db.execute("INSERT INTO t0 VALUES (%s)" % ", ".join(rnd.choice(VALUES) for _ in COLUMNS))
    gen = Gen(rnd, features)
    stats = Counter()
    mismatches = []
    for _ in range(args.n):
        pred = gen.pred(args.depth)
        sql = "SELECT rowid FROM t0 WHERE %s" % pred
        expected, err = rows(db, sql)
        if err:
            stats["original errors"] += 1
            continue
        t = time.perf_counter()
        vs, refusal = variants(args.url, sql, args.max_variants)
        stats["server ms"] += int((time.perf_counter() - t) * 1000)
        if refusal:
            stats["refused: " + refusal.split(":")[0][:60]] += 1
            continue
        stats["queries with variants" if vs else "queries without variants"] += 1
        for v in vs:
            stats["variants"] += 1
            stats["variant chars"] += len(v)
            got, verr = rows(db, v)
            if verr:
                stats["variant errors"] += 1
                continue
            if got != expected:
                mismatches.append({"original": sql, "variant": v, "expected": expected, "got": got})
    for k, v in sorted(stats.items()):
        print("%6d  %s" % (v, k))
    if stats["variants"]:
        print("%6.1f  variants per query, %.0f chars per variant, %.0f ms per request" % (
            stats["variants"] / max(1, args.n - sum(v for k, v in stats.items() if k.startswith("refused"))),
            stats["variant chars"] / stats["variants"], stats["server ms"] / args.n))
    print("%6d  MISMATCHES" % len(mismatches))
    for m in mismatches[:10]:
        print("--\n%s\n%s\n%s vs %s" % (m["original"], m["variant"], m["expected"], m["got"]))
    sys.exit(1 if mismatches else 0)


if __name__ == "__main__":
    main()
