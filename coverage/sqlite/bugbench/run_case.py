"""Run one bug reproducer through the EGRAPH oracle and report whether it is detected.

Usage: python run_case.py <corpus.sql> <seconds> <workdir>
Prints one JSON line. A differing result surfaces either as a thrown AssertionError
or, when one side comes back empty, as a block in the single-side-empty log.
"""
import json
import os
import re
import subprocess
import sys

# Overridable for machines that keep the long-run template elsewhere: the template file is
# a @-file of JVM flags captured from a long run, and OLD is the directory its output paths
# point at, which every run replaces with its own workdir.
TEMPLATE = os.environ.get("EGRAPH_LONGRUN_TEMPLATE",
        r"D:\sqlancer\coverage\sqlite\manual-long-codecov-20260921-010516\java-args.txt")
OLD = os.path.dirname(TEMPLATE)


def build_args(corpus, seconds, workdir):
    out = []
    for line in open(TEMPLATE, encoding="utf-8-sig").read().splitlines():
        line = line.replace(OLD, workdir)
        if line.startswith("--timeout-seconds="):
            line = "--timeout-seconds=%d" % seconds
        elif line.startswith("--egraph-input-file="):
            line = "--egraph-input-file=" + corpus
        elif line.startswith("-Dsqlite3.egraph.autoResearchResults="):
            continue  # no shape steering: the corpus query is what is under test
        out.append(line)
    out.insert(1, "-Dsqlite3.egraph.corpus.allowJoin=true")
    # Every case here is a known bug, so the known-bug signatures would swallow exactly the hits
    # this bench counts (6c97dbe469 is one of them).
    out.insert(1, "-Degraph.knownBugs=false")
    if os.environ.get("ALLOW_EMPTY_BASE") == "1":
        out.insert(1, "-Dsqlite3.egraph.corpus.allowEmptyBase=true")
    out.insert(1, "-Dsqlite3.egraph.corpusSetupOnly=true")
    path = os.path.join(workdir, "args.txt")
    open(path, "w", encoding="utf-8").write("\n".join(out))
    return path


def main():
    corpus, seconds, workdir = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    os.makedirs(workdir, exist_ok=True)
    args = build_args(corpus, seconds, workdir)
    proc = subprocess.run(["java", "@" + args], capture_output=True, cwd=workdir,
                          timeout=seconds + 180)
    out = proc.stdout.decode("utf-8", "replace") + proc.stderr.decode("utf-8", "replace")
    open(os.path.join(workdir, "out.log"), "w", encoding="utf-8").write(out)

    asserts = re.findall(r"AssertionError[^\n]*", out)
    sse_path = os.path.join(workdir, "single-side-empty-reproducers.sql")
    sse = open(sse_path, encoding="utf-8", errors="replace").read() if os.path.exists(sse_path) else ""
    hits = sse.count("EGRAPH_SINGLE_SIDE_EMPTY_BEGIN")
    trace_path = os.path.join(workdir, "egraph-trace.log")
    trace = open(trace_path, encoding="utf-8", errors="replace").read() if os.path.exists(trace_path) else ""
    if hits:
        first = sse.split("EGRAPH_SINGLE_SIDE_EMPTY_BEGIN")[1].split("EGRAPH_SINGLE_SIDE_EMPTY_END")[0][:600]
    elif asserts:
        first = asserts[0][:400]
    else:
        first = ""
    print(json.dumps({
        "id": os.path.basename(corpus)[:-4],
        "detected": bool(hits or asserts),
        "sseHits": hits,
        "asserts": len(asserts),
        "corpusCandidates": trace.count("corpus-candidate start"),
        "emptyBaseSkips": trace.count("corpus-base-nonempty-probe done rows=false"),
        "first": first,
    }, ensure_ascii=False))


main()
