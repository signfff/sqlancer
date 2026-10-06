#!/usr/bin/env bash
# Linux counterpart of run_case.py: runs one benchmark corpus through SQLancer's EGRAPH oracle and
# reports whether it was detected. Built from a long run's java-args.txt the same way run_case.py is:
# corpus-only input, known-bug suppression off, no auto-research steering, no referee (the release
# library is both the system under test and the only engine that has these defects).
#
# Usage: run_case_server.sh <corpus.sql> <seconds> <workdir> <lib-dir> [extra -D ...]
#   lib-dir: a build directory of build-trunk-jdbc.sh, e.g. release-jdbc/build for 3.53.4
set -euo pipefail
corpus=$(readlink -f "$1"); seconds=$2; work=$3; lib=$(readlink -f "$4"); shift 4
template=${TEMPLATE:-$(ls -t "$HOME"/sqlancer/coverage/sqlite/manual-long-codecov-*/java-args.txt | head -1)}
rm -rf "$work" && mkdir -p "$work" && work=$(readlink -f "$work")
{
    echo "-Dsqlite3.egraph.corpusSetupOnly=true"
    echo "-Dsqlite3.egraph.corpus.allowEmptyBase=true"
    echo "-Degraph.knownBugs=false"
    echo "-Dsqlite3.egraph.corpus.allowJoin=true"
    echo "-Degraph.trace.file=$work/trace.log"
    echo "-Degraph.singleSideEmptyLog=$work/sse.sql"
    echo "-Degraph.optMask.log=$work/optmask.log"
    for extra in "$@"; do echo "$extra"; done
    grep -v -E '^-Dorg\.sqlite\.lib\.|^-Degraph\.referee\.|^-Dsqlite3\.egraph\.autoResearchResults=|^-Degraph\.(trace\.file|singleSideEmptyLog|optMask)' "$template" \
        | sed -E "s|^--timeout-seconds=.*|--timeout-seconds=$seconds|; s|^--egraph-input-file=.*|--egraph-input-file=$corpus|"
    echo "-Dorg.sqlite.lib.path=$lib"
    echo "-Dorg.sqlite.lib.name=libsqlitejdbc.so"
} > "$work/args.txt"
# The JVM options must come before -cp/sqlancer.Main: move the two lib lines up front.
awk '/^-Dorg\.sqlite\.lib\./{lib[++n]=$0; next} {rest[++m]=$0} END{for(i=1;i<=n;i++) print lib[i]; for(i=1;i<=m;i++) print rest[i]}' \
    "$work/args.txt" > "$work/args2.txt" && mv "$work/args2.txt" "$work/args.txt"
cd "$work"
timeout $((seconds + 180)) java @args.txt > out.log 2>&1 || true
sse=$(grep -c EGRAPH_SINGLE_SIDE_EMPTY_BEGIN sse.sql 2>/dev/null || true)
asserts=$(grep -c '^java.lang.AssertionError: \(The \|EGRAPH single\)' out.log || true)
attrib=$(grep -oE '^-- EGRAPH_OPTMASK [A-Z]+' optmask.log 2>/dev/null | awk '{print $3}' | sort | uniq -c | tr '\n' ' ' || true)
echo "$(basename "$corpus" .sql) detected=$([ "${sse:-0}" -gt 0 ] || [ "${asserts:-0}" -gt 0 ] && echo yes || echo no) sse=${sse:-0} asserts=${asserts:-0} attribution=[${attrib}]"
