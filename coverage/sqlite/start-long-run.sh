#!/usr/bin/env bash
# Long EGRAPH capture on Linux: the counterpart of start-long-codecov-capture.ps1.
#
# Same corpus inputs and the same -D properties, with five differences:
#   * SQLite's trunk is the system under test by default (build-trunk-jdbc.sh). --sut bundled tests
#     the driver's own library instead, with the trunk shell as referee and the known-bug signatures
#     on. Against trunk both are off: the referee is built from the same source, so "agrees on trunk"
#     can only mean its replay differed from the run, and the signatures name bugs trunk has fixed.
#     Either verdict would only ever drop a finding (2026-10-07: a TEMP table missing from the
#     replay, and every wrapper's EXISTS (... LIMIT 1) read as an unstable LIMIT).
#   * A database is replaced after 100000 checks, SQLancer's own default, not after 100000000.
#     SQLancer keeps every statement a database ran to reproduce a finding, and an EGRAPH check
#     costs about 36 KB of them (base query, up to 16 variants, a corpus case's setup): one database
#     run for 24 hours on 2026-10-04 filled a 32g heap and halved the check rate. 100000 checks is
#     about 3.6 GB and three hours.
#   * The heap ceiling is 32g, not 3g, for that 3.6 GB with room to spare.
#   * The run directory is the working directory, so logs/, databases/ and the superjournals land in
#     it and several runs can go side by side on one machine.
#   * The rewrite server is started if nothing answers on :3000, and --detach leaves the run going
#     after the ssh session ends.
# The coverage replay (finish-long-codecov.ps1) is not ported: its instrumented build is 3.53.4.
#
# Usage: start-long-run.sh [--timeout SEC] [--tag TAG] [--heap 32g] [--max-corpus-queries N]
#                          [--sut trunk|bundled] [--referee CMD|""] [--no-auto-research-corpus]
#                          [--detach] [-Dname=value ...]
# Optimizer masks (EGraphOptimizerMask) are off unless asked for, e.g. -Degraph.optMask=combined; their
# findings are attributed in <run>/optmask-findings.log.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

timeout=-1 tag="" heap=32g max_corpus_queries=30000 sut=trunk no_ar_corpus=0 detach=0
trunk_build=$here/trunk-jdbc/build
referee=$trunk_build/sqlite3_trunk referee_given=0
extra=()
while [ $# -gt 0 ]; do
    case $1 in
        --timeout) timeout=$2; shift 2 ;;
        --tag) tag=$2; shift 2 ;;
        --heap) heap=$2; shift 2 ;;
        --max-corpus-queries) max_corpus_queries=$2; shift 2 ;;
        --sut) sut=$2; shift 2 ;;
        --referee) referee=$2; referee_given=1; shift 2 ;;
        --no-auto-research-corpus) no_ar_corpus=1; shift ;;
        --detach) detach=1; shift ;;
        -D*) extra+=("$1"); shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -f "$HOME/opt/env.sh" ] && source "$HOME/opt/env.sh"

stamp=$(date +%Y%m%d-%H%M%S)
run_dir=$here/manual-long-codecov-$stamp${tag:+-$tag}

if [ $detach = 1 ]; then
    # Re-run this script without --detach in its own session, so a dropped ssh connection or a
    # closed terminal does not take the run with it.
    mkdir -p "$run_dir"
    rerun=(--timeout "$timeout" --heap "$heap" --max-corpus-queries "$max_corpus_queries" --sut "$sut"
           ${tag:+--tag "$tag"} "${extra[@]}")
    [ $referee_given = 1 ] && rerun+=(--referee "$referee")
    [ $no_ar_corpus = 1 ] && rerun+=(--no-auto-research-corpus)
    RUN_DIR="$run_dir" setsid nohup "$0" "${rerun[@]}" > "$run_dir/launcher.log" 2>&1 < /dev/null &
    echo "Detached (pid $!). Run dir: $run_dir"
    echo "Follow it with: tail -f $run_dir/launcher.log"
    exit 0
fi
run_dir=${RUN_DIR:-$run_dir}
mkdir -p "$run_dir"
echo "$run_dir" > "$here/last-long-codecov-run.txt"

jar=$root/target/sqlancer-2.0.0.jar
[ -f "$jar" ] || { echo "Missing $jar. Build SQLancer first." >&2; exit 1; }

egraph_input=$here/sqlite-official-select-only-variant-ready-corpus.sql
[ -f "$egraph_input" ] || { echo "Missing variant-ready EGRAPH input corpus: $egraph_input" >&2; exit 1; }
case_count=$(grep -c -- '-- EGRAPH_CORPUS_CASE_BEGIN' "$egraph_input" || true)
[ "$case_count" -gt 0 ] || { echo "Variant-ready EGRAPH input corpus has no cases: $egraph_input" >&2; exit 1; }


inputs=$egraph_input
high_coverage=$here/high-coverage-egraph-corpus.sql
if [ -f "$high_coverage" ]; then inputs+=":$high_coverage"; hc_status="included: $high_coverage"
else hc_status="missing: $high_coverage"; fi
auto_research_corpus=$here/auto-research-filtered-corpus.sql
if [ $no_ar_corpus = 1 ]; then ar_status="disabled with --no-auto-research-corpus"
elif [ -f "$auto_research_corpus" ]; then inputs+=":$auto_research_corpus"; ar_status="included: $auto_research_corpus"
else ar_status="missing: $auto_research_corpus"; fi

java_args=()
[ -n "$heap" ] && java_args+=("-Xmx$heap")
case $sut in
    trunk)
        [ -f "$trunk_build/libsqlitejdbc.so" ] || { echo "Missing $trunk_build/libsqlitejdbc.so: run build-trunk-jdbc.sh" >&2; exit 1; }
        java_args+=("-Dorg.sqlite.lib.path=$trunk_build" "-Dorg.sqlite.lib.name=libsqlitejdbc.so"
                    -Degraph.knownBugs=false)
        [ $referee_given = 1 ] || referee="" ;;
    bundled) ;;
    *) echo "--sut must be trunk or bundled" >&2; exit 2 ;;
esac
java_args+=("${extra[@]}")
java_args+=(
    --enable-native-access=ALL-UNNAMED
    -Dsqlancer.statementTimeoutSeconds=15
    "-Degraph.coverage.file=$run_dir/workload-hints.txt"
    "-Degraph.singleSideEmptyLog=$run_dir/single-side-empty-reproducers.sql"
    "-Dsqlite3.egraph.input.maxQueries=$max_corpus_queries"
    -Dsqlite3.egraph.input.maxCaseSetupStatements=80
    -Dsqlite3.egraph.corpus.maxAttemptsPerCheck=4
    "-Degraph.variantOnlyError.log=$run_dir/variant-only-errors.log"
    -Dsqlite3.egraph.corpus.selectOnlyTemplates=true
    -Dsqlite3.egraph.baseSkeletons=true
    "-Degraph.referee.command=$referee"
    "-Degraph.referee.log=$run_dir/referee.sql"
    "-Degraph.knownBugs.log=$run_dir/known-bugs.sql"
    "-Degraph.optMask.log=$run_dir/optmask-findings.log"
    "-Degraph.repro.dir=$run_dir/repro"
    "-Degraph.integrity.log=$run_dir/integrity-findings.log"
    -cp "$jar"
    sqlancer.Main
    --num-threads=1
    --num-tries=100000
    --max-generated-databases=-1
    --num-queries=100000
    --num-statement-kind-retries=3
    "--timeout-seconds=$timeout"
    --print-progress-information=true
    --print-progress-summary=true
    --log-each-select=true
    --egraph-log-each-select=false
    --egraph-url=http://127.0.0.1:3000
    --egraph-max-variants=16
    --egraph-timeout-millis=3000
    sqlite3
    --oracle=EGRAPH
    "--egraph-input-file=$inputs"
)
printf '%s\n' "${java_args[@]}" > "$run_dir/java-args.txt"

# The rewrite server is shared by every run on the machine; start it only if nothing answers.
if ! curl -s -m 3 -o /dev/null http://127.0.0.1:3000/; then
    setsid nohup "$root/egraph-server/target/release/egraph-server" \
        >> "$here/egraph-server.out.log" 2>> "$here/egraph-server.err.log" < /dev/null &
    for i in $(seq 20); do curl -s -m 1 -o /dev/null http://127.0.0.1:3000/ && break; sleep 0.5; done
    curl -s -m 3 -o /dev/null http://127.0.0.1:3000/ || { echo "egraph-server did not come up on :3000" >&2; exit 1; }
    echo "Started egraph-server (pid $!)"
fi

echo "Run dir: $run_dir"
echo "System under test: $sut${referee:+, referee $referee}"
echo "Official EGRAPH variant-ready cases: $case_count"
echo "High-coverage corpus input: $hc_status"
echo "Auto-research corpus input: $ar_status"
if [ "$timeout" -gt 0 ]; then echo "SQLancer will stop after $timeout seconds ($((timeout / 3600)) h $((timeout % 3600 / 60)) min)."
else echo "SQLancer will run without a time limit."; fi

cd "$run_dir"
set +e
java "${java_args[@]}" > "$run_dir/sqlancer.out.log" 2>&1
exit_code=$?
set -e
[ -f logs/sqlite3/database0-cur.log ] && cp -f logs/sqlite3/database0-cur.log "$run_dir/database0-cur.log"
echo "SQLancer exit code: $exit_code"
echo "Run dir: $run_dir"
