"""Would toggling SQLite's optimizer flags alone (no rewriting) expose each benchmark bug?

For every corpus case, runs the case on the 3.53.4 shell with the default optimizations, then with
each single optimization bit turned off and with all of them off (.testctrl optimizations <mask>),
and reports the masks under which the output differs from the default. Output rows are compared as
sorted multisets, so a different row order alone does not count. Coverage data of the instrumented
shell goes to a scratch directory through GCOV_PREFIX.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHELL = os.path.join(HERE, "..", "build", "sqlite3_cov.exe")
MASKS = [1 << b for b in range(32)] + [0xFFFFFFFF]


def run(sql, mask, env):
    # .testctrl prints the resulting flag set ("-All", "+QueryFlattener" ...), which differs per mask:
    # send it to a scratch file so only the case's own output is compared.
    sink = os.path.join(env["GCOV_PREFIX"], "testctrl.out").replace("\\", "/")
    toggle = ".output %s\n.testctrl optimizations 0x%x\n.output stdout\n" % (sink, mask)
    script = (toggle if mask else "") + sql
    p = subprocess.run([SHELL], input=script.encode("utf-8"), capture_output=True, env=env, timeout=60)
    out = (p.stdout + p.stderr).decode("utf-8", "replace").replace("\r\n", "\n")
    return sorted(line for line in out.split("\n") if line.strip())


def main():
    detected = {}
    for name in os.listdir(os.path.join(HERE, "runs7")):
        if name.endswith(".json"):
            d = json.load(open(os.path.join(HERE, "runs7", name)))
            detected[d["id"]] = d["detected"]
    live = {c["id"]: c["live_in_release"] for c in json.load(open(os.path.join(HERE, "referee.json")))["cases"]}
    env = dict(os.environ, GCOV_PREFIX=tempfile.mkdtemp(prefix="dqp-gcov-"), GCOV_PREFIX_STRIP="99")
    rows = []
    for cid in sorted(detected):
        path = os.path.join(HERE, "corpus", cid + ".sql")
        if not os.path.exists(path):
            continue
        sql = open(path, encoding="utf-8").read()
        base = run(sql, 0, env)
        hits = [m for m in MASKS if run(sql, m, env) != base]
        rows.append((cid, detected[cid], live.get(cid), hits))
    print("%-11s %-8s %-6s %s" % ("bug", "egraph", "live", "masks that change the result"))
    for cid, det, lv, hits in rows:
        shown = ", ".join("all" if m == 0xFFFFFFFF else "0x%x" % m for m in hits) or "-"
        print("%-11s %-8s %-6s %s" % (cid, "FOUND" if det else "-", lv, shown))
    dq = [r for r in rows if r[3]]
    print("\nDQP-exposed: %d of %d; egraph found %d; both %d; egraph-only %d; DQP-only %d" % (
        len(dq), len(rows), sum(1 for r in rows if r[1]), sum(1 for r in rows if r[1] and r[3]),
        sum(1 for r in rows if r[1] and not r[3]), sum(1 for r in rows if r[3] and not r[1])))


if __name__ == "__main__":
    sys.exit(main())
