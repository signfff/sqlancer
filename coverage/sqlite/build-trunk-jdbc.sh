#!/usr/bin/env bash
# Builds the sqlite-jdbc native library and a matching shell from the latest SQLite trunk (Linux).
#
# The Linux counterpart of build-trunk-jdbc.ps1: same sources, same SQLITE_* defines, same three
# edits to sqlite3.c, with sqlite-jdbc's Linux-x86_64 compile and link flags. Point the JVM at it:
#
#     -Dorg.sqlite.lib.path=$OUT_DIR/build -Dorg.sqlite.lib.name=libsqlitejdbc.so
#
# Usage: build-trunk-jdbc.sh [--no-fetch]
#   OUT_DIR   (default: coverage/sqlite/trunk-jdbc next to this script)
#   JDBC_TAG  (default: 3.53.4.0, the sqlite-jdbc version in pom.xml)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=${OUT_DIR:-$here/trunk-jdbc}
JDBC_TAG=${JDBC_TAG:-3.53.4.0}
JDBC_JAR=${JDBC_JAR:-$HOME/.m2/repository/org/xerial/sqlite-jdbc/$JDBC_TAG/sqlite-jdbc-$JDBC_TAG.jar}
no_fetch=0
[ "${1:-}" = "--no-fetch" ] && no_fetch=1
: "${JAVA_HOME:?JAVA_HOME must point at a JDK}"
[ -f "$JDBC_JAR" ] || { echo "$JDBC_JAR is missing: run mvn package first" >&2; exit 1; }

build=$OUT_DIR/build
repo=$OUT_DIR/repo
mkdir -p "$build"

# 1. Latest trunk, shallow. GitHub drops a connection now and then: retry before giving up.
if [ ! -d "$repo/.git" ]; then
    git clone -q --depth 1 https://github.com/sqlite/sqlite.git "$repo"
elif [ $no_fetch = 1 ]; then
    echo "Skipping the fetch: building $(git -C "$repo" log -1 --format='%h %ci')"
else
    ok=0
    for attempt in 1 2 3 4; do
        if git -C "$repo" fetch -q --depth 1 origin master; then ok=1; break; fi
        sleep 5
    done
    [ $ok = 1 ] || { echo "fetching trunk from GitHub failed" >&2; exit 1; }
    git -C "$repo" reset -q --hard FETCH_HEAD
fi

# Amalgamation with the UPDATE/DELETE LIMIT grammar, which the public amalgamation lacks, and shell.c.
(
    cd "$repo"
    ./configure --update-limit > cfg-jdbc.log 2>&1
    rm -f sqlite3.c shell.c parse.c parse.h
    make -j8 sqlite3.c shell.c > amalg-jdbc.log 2>&1
    grep -q 'lemon .*-DSQLITE_ENABLE_UPDATE_DELETE_LIMIT' amalg-jdbc.log
    cp sqlite3.c sqlite3.h sqlite3ext.h shell.c "$OUT_DIR/"
    git log -1 --format='trunk %H %ci'
)

# 2. sqlite-jdbc's native sources for the driver version in use.
raw=https://raw.githubusercontent.com/xerial/sqlite-jdbc/$JDBC_TAG
for f in src/main/java/org/sqlite/core/NativeDB.c src/main/java/org/sqlite/core/NativeDB.java \
         src/main/ext/extension-functions.c; do
    dest=$OUT_DIR/$(basename "$f")
    [ -f "$dest" ] || curl -fsSL --retry 4 -o "$dest" "$raw/$f"
done

# 3. The same edits sqlite-jdbc's Makefile makes (its own perl one-liners).
perl -p -e 's/^opendb_out:/  if(!db->mallocFailed && rc==SQLITE_OK){ rc = RegisterExtensionFunctions(db); }\nopendb_out:/;' \
     "$OUT_DIR/sqlite3.c" \
  | perl -p -e 's/^(static const char \* const sqlite3azCompileOpt.+)$/\1\n\n\/* This has been automatically added by sqlite-jdbc *\/\n  "JDBC_EXTENSIONS",/;' \
  > "$build/sqlite3.c"
cat "$OUT_DIR/extension-functions.c" >> "$build/sqlite3.c"
grep -q 'rc = RegisterExtensionFunctions(db);' "$build/sqlite3.c"
grep -q '"JDBC_EXTENSIONS",' "$build/sqlite3.c"
perl -p -e 's/sqlite3_api;/sqlite3_api = 0;/g' "$OUT_DIR/sqlite3ext.h" > "$build/sqlite3ext.h"
cp "$OUT_DIR/sqlite3.h" "$build/"

# 4. JNI header from the driver's own classes.
"$JAVA_HOME/bin/javac" -h "$build" -cp "$JDBC_JAR" -d "$build/classes" "$OUT_DIR/NativeDB.java"
mv -f "$build/org_sqlite_core_NativeDB.h" "$build/NativeDB.h"

# 5. Compile with sqlite-jdbc's Linux-x86_64 flags.
sqlite_defines=(
    -DSQLITE_ENABLE_LOAD_EXTENSION=1 -DSQLITE_HAVE_ISNAN -DHAVE_USLEEP=1
    -DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS
    -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_PERCENTILE -DSQLITE_ENABLE_STAT4
    -DSQLITE_ENABLE_DBSTAT_VTAB -DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_THREADSAFE=1
    -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DEFAULT_FILE_PERMISSIONS=0666
    -DSQLITE_MAX_VARIABLE_NUMBER=250000 -DSQLITE_MAX_MMAP_SIZE=1099511627776
    -DSQLITE_MAX_LENGTH=2147483647 -DSQLITE_MAX_COLUMN=32767 -DSQLITE_MAX_SQL_LENGTH=1073741824
    -DSQLITE_MAX_FUNCTION_ARG=127 -DSQLITE_MAX_ATTACHED=125 -DSQLITE_MAX_PAGE_COUNT=4294967294
    -DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS -DSQLITE_ENABLE_UPDATE_DELETE_LIMIT)
ccflags=(-I"$build" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -Os -fPIC -m64 -fvisibility=hidden)
gcc "${ccflags[@]}" "${sqlite_defines[@]}" -DSQLITE_CORE -w -c "$build/sqlite3.c" -o "$build/sqlite3.o"
gcc "${ccflags[@]}" -c "$OUT_DIR/NativeDB.c" -o "$build/NativeDB.o"
gcc "${ccflags[@]}" -o "$build/libsqlitejdbc.so" "$build/NativeDB.o" "$build/sqlite3.o" \
    -shared -static-libgcc -pthread -lm

# The referee replays a mismatch outside the JVM; built from the same source it answers whether the
# mismatch reproduces on the very same SQLite.
gcc -O2 -w "${sqlite_defines[@]}" -I"$build" -o "$build/sqlite3_trunk" \
    "$OUT_DIR/shell.c" "$build/sqlite3.c" -lpthread -lm -ldl

echo "select sqlite_version(), sqlite_source_id();" | "$build/sqlite3_trunk"
echo "Built $build/libsqlitejdbc.so and $build/sqlite3_trunk"
