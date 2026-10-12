#!/bin/sh
# Long EGRAPH capture inside the docker image (the tmux zwq_sql + /bdata environment):
#   * SQLite trunk is the system under test: the JNI library built by
#     coverage/sqlite/build-trunk-jdbc.sh into coverage/sqlite/trunk-jdbc/build.
#   * Against trunk the referee and the known-bug signatures are off (both would only ever
#     drop a finding), and the combined optimizer mask is on with attribution logging.
#   * One database per 100000 checks (~3.6 GB of kept statements, about three hours), 32g heap.
#   * The image entrypoint passes every argument to SQLancer, so the JVM flags travel in
#     JAVA_TOOL_OPTIONS; -w points SQLancer's working directory at the run directory so
#     logs/, databases/ and the superjournals land there.
#   * The run is capped at 86400 seconds; relaunch for more.
set -eu

cd /bdata/lxy/zwq/SQL || exit 1
tag=${1:-$(date +%Y%m%d-%H%M%S)}
run_dir=/work/run-$tag
mkdir -p "run-$tag"

[ -f coverage/sqlite/trunk-jdbc/build/libsqlitejdbc.so ] || {
    echo "Missing coverage/sqlite/trunk-jdbc/build/libsqlitejdbc.so: run build-trunk-jdbc.sh" >&2
    exit 1
}

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --network bridge \
  --add-host=host.docker.internal:host-gateway \
  --mount type=bind,src="$(pwd)",dst=/work \
  -w "$run_dir" \
  -e "JAVA_TOOL_OPTIONS=-Xmx32g -Dorg.sqlite.lib.path=/work/coverage/sqlite/trunk-jdbc/build -Dorg.sqlite.lib.name=libsqlitejdbc.so -Degraph.knownBugs=false -Degraph.optMask=combined -Dsqlancer.statementTimeoutSeconds=15 -Degraph.coverage.file=$run_dir/workload-hints.txt -Degraph.singleSideEmptyLog=$run_dir/single-side-empty-reproducers.sql -Degraph.variantOnlyError.log=$run_dir/variant-only-errors.log -Degraph.optMask.log=$run_dir/optmask-findings.log -Degraph.repro.dir=$run_dir/repro -Degraph.integrity.log=$run_dir/integrity-findings.log -Dsqlite3.egraph.baseSkeletons=true -Dsqlite3.egraph.autoResearchGuidedShapes=true" \
  sqlancer-egraph \
  --num-threads=1 \
  --num-tries=100000 \
  --max-generated-databases=-1 \
  --num-queries=100000 \
  --num-statement-kind-retries=3 \
  --timeout-seconds=86400 \
  --print-progress-information=true \
  --print-progress-summary=true \
  --log-each-select=true \
  --egraph-log-each-select=false \
  --egraph-url=http://127.0.0.1:3000 \
  --egraph-max-variants=16 \
  --egraph-timeout-millis=3000 \
  sqlite3 --oracle=EGRAPH
