<#
.SYNOPSIS
    Builds the sqlite-jdbc native library and a matching shell from the latest SQLite trunk.

.DESCRIPTION
    SQLancer reaches SQLite through sqlite-jdbc, which loads a native library. Pointing it at a
    library built from trunk makes trunk the system under test, without touching the Java side:

        -Dorg.sqlite.lib.path=D:\sqlancer\coverage\sqlite\trunk-jdbc\build
        -Dorg.sqlite.lib.name=sqlitejdbc.dll

    The build follows sqlite-jdbc's own Makefile for the version in pom.xml (3.53.4.0): the same
    SQLITE_* defines, the same three edits to sqlite3.c (register extension-functions.c on open,
    add JDBC_EXTENSIONS to compile_options, append the extension source) and the same link flags.
    UPDATE/DELETE LIMIT is generated into the parser here, which the public amalgamation cannot
    do, so this build matches the shipped driver more closely than build-instrumented-sqlite.ps1.

    The amalgamation is generated in WSL (SQLite's configure needs a POSIX shell). WSL is shut
    down afterwards: left running it keeps ~1 GB resident and long runs then die of memory.

    Measured 2026-10-02 against the bundled 3.53.4 library, seed 13, 150 s: same check count,
    peak working set 2.39 GB vs 2.49 GB. compile_options differ only in the compiler version and
    trunk's new MAX_SCHEMA default.
#>
param(
    [string] $OutDir = "D:\sqlancer\coverage\sqlite\trunk-jdbc",
    [string] $JdbcTag = "3.53.4.0",
    [string] $Gcc = "D:\Dev-Cpp\TDM-GCC-64\bin\gcc.exe",
    [string] $JdbcJar = "$env:USERPROFILE\.m2\repository\org\xerial\sqlite-jdbc\3.53.4.0\sqlite-jdbc-3.53.4.0.jar",
    # WSL directory holding a clone of https://github.com/sqlite/sqlite.git (made once with --depth 1).
    [string] $WslSqliteDir = "~/sqlite-trunk/src",
    # Build what the local clone already holds, for when GitHub cannot be reached.
    [switch] $NoFetch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$build = Join-Path $OutDir "build"
New-Item -ItemType Directory -Force -Path $build | Out-Null

# 1. Latest trunk. Fetched by Windows git, because WSL in NAT mode cannot use a localhost proxy and
#    its own fetches from GitHub kept breaking off mid-transfer; WSL then pulls from this local copy.
$repo = Join-Path $OutDir "repo"
if (-not (Test-Path (Join-Path $repo ".git"))) {
    git clone -q --depth 1 https://github.com/sqlite/sqlite.git $repo
} elseif ($NoFetch) {
    Write-Host "Skipping the fetch: building $(git -C $repo log -1 --format='%h %ci')"
} else {
    # The proxy drops a GitHub connection now and then (502, TLS reset): retry before giving up.
    foreach ($attempt in 1..4) {
        git -C $repo fetch -q --depth 1 origin master
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 5
    }
    if ($LASTEXITCODE -eq 0) { git -C $repo reset -q --hard FETCH_HEAD }
}
if ($LASTEXITCODE -ne 0) { throw "fetching trunk from GitHub failed" }

# Amalgamation with the UPDATE/DELETE LIMIT grammar, and shell.c, generated in WSL.
$wslOut = (wsl -e wslpath -a ($OutDir -replace '\\', '/')).Trim()
$wslRepo = (wsl -e wslpath -a ($repo -replace '\\', '/')).Trim()
$script = @"
set -e
cd $WslSqliteDir
git fetch -q --depth 1 '$wslRepo' HEAD
git reset -q --hard FETCH_HEAD
./configure --update-limit > cfg-jdbc.log 2>&1
rm -f sqlite3.c shell.c parse.c parse.h
make sqlite3.c shell.c > amalg-jdbc.log 2>&1
grep -q 'lemon .*-DSQLITE_ENABLE_UPDATE_DELETE_LIMIT' amalg-jdbc.log
cp sqlite3.c sqlite3.h sqlite3ext.h shell.c '$wslOut/'
git log -1 --format='trunk %H %ci'
"@
try {
    wsl -e bash -lc $script
    if ($LASTEXITCODE -ne 0) { throw "WSL step failed with exit code $LASTEXITCODE" }
} finally {
    wsl --shutdown
}

# 2. sqlite-jdbc's native sources for the driver version in use.
$raw = "https://raw.githubusercontent.com/xerial/sqlite-jdbc/$JdbcTag"
foreach ($f in @("src/main/java/org/sqlite/core/NativeDB.c", "src/main/java/org/sqlite/core/NativeDB.java",
                 "src/main/ext/extension-functions.c")) {
    $dest = Join-Path $OutDir (Split-Path $f -Leaf)
    if (-not (Test-Path $dest)) { Invoke-WebRequest -UseBasicParsing -Uri "$raw/$f" -OutFile $dest }
}

# 3. The same edits sqlite-jdbc's Makefile makes, written without a BOM and with LF endings.
$utf8 = New-Object System.Text.UTF8Encoding $false
$src = [System.IO.File]::ReadAllText((Join-Path $OutDir "sqlite3.c"), $utf8)
$src = [regex]::Replace($src, '(?m)^opendb_out:',
    "  if(!db->mallocFailed && rc==SQLITE_OK){ rc = RegisterExtensionFunctions(db); }`nopendb_out:")
$src = [regex]::Replace($src, '(?m)^(static const char \* const sqlite3azCompileOpt.+)$',
    "`$1`n`n/* This has been automatically added by sqlite-jdbc */`n  `"JDBC_EXTENSIONS`",")
$src += [System.IO.File]::ReadAllText((Join-Path $OutDir "extension-functions.c"), $utf8)
[System.IO.File]::WriteAllText((Join-Path $build "sqlite3.c"), $src, $utf8)
$ext = [System.IO.File]::ReadAllText((Join-Path $OutDir "sqlite3ext.h"), $utf8).Replace("sqlite3_api;", "sqlite3_api = 0;")
[System.IO.File]::WriteAllText((Join-Path $build "sqlite3ext.h"), $ext, $utf8)
Copy-Item -Force (Join-Path $OutDir "sqlite3.h") $build

# 4. JNI header from the driver's own classes.
& javac -h $build -cp $JdbcJar -d (Join-Path $build "classes") (Join-Path $OutDir "NativeDB.java")
if ($LASTEXITCODE -ne 0) { throw "javac -h failed" }
Move-Item -Force (Join-Path $build "org_sqlite_core_NativeDB.h") (Join-Path $build "NativeDB.h")

# 5. Compile. _WIN32_WINNT only exposes Vista+ APIs (GetTickCount64, SRW locks) that TDM-GCC's
#    headers hide by default; it does not change SQLite's behaviour.
$sqliteDefines = @(
    "-DSQLITE_ENABLE_LOAD_EXTENSION=1", "-DSQLITE_HAVE_ISNAN", "-DHAVE_USLEEP=1",
    "-DSQLITE_ENABLE_COLUMN_METADATA", "-DSQLITE_ENABLE_FTS3", "-DSQLITE_ENABLE_FTS3_PARENTHESIS",
    "-DSQLITE_ENABLE_FTS5", "-DSQLITE_ENABLE_RTREE", "-DSQLITE_ENABLE_PERCENTILE", "-DSQLITE_ENABLE_STAT4",
    "-DSQLITE_ENABLE_DBSTAT_VTAB", "-DSQLITE_ENABLE_MATH_FUNCTIONS", "-DSQLITE_THREADSAFE=1",
    "-DSQLITE_DEFAULT_MEMSTATUS=0", "-DSQLITE_DEFAULT_FILE_PERMISSIONS=0666",
    "-DSQLITE_MAX_VARIABLE_NUMBER=250000", "-DSQLITE_MAX_MMAP_SIZE=1099511627776",
    "-DSQLITE_MAX_LENGTH=2147483647", "-DSQLITE_MAX_COLUMN=32767", "-DSQLITE_MAX_SQL_LENGTH=1073741824",
    "-DSQLITE_MAX_FUNCTION_ARG=127", "-DSQLITE_MAX_ATTACHED=125", "-DSQLITE_MAX_PAGE_COUNT=4294967294",
    "-DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS", "-DSQLITE_ENABLE_UPDATE_DELETE_LIMIT")
$jni = @("-D_JNI_IMPLEMENTATION_", "-D_WIN32_WINNT=0x0A00", "-Os", "-I$build",
         "-I$env:JAVA_HOME\include", "-I$env:JAVA_HOME\include\win32")
& $Gcc @jni @sqliteDefines "-DSQLITE_CORE" -w -c (Join-Path $build "sqlite3.c") -o (Join-Path $build "sqlite3.o")
if ($LASTEXITCODE -ne 0) { throw "compiling sqlite3.c failed" }
& $Gcc @jni -c (Join-Path $OutDir "NativeDB.c") -o (Join-Path $build "NativeDB.o")
if ($LASTEXITCODE -ne 0) { throw "compiling NativeDB.c failed" }
& $Gcc -o (Join-Path $build "sqlitejdbc.dll") (Join-Path $build "NativeDB.o") (Join-Path $build "sqlite3.o") "-Wl,--kill-at" -shared -static-libgcc
if ($LASTEXITCODE -ne 0) { throw "linking sqlitejdbc.dll failed" }

# The referee replays a mismatch outside the JVM; built from the same source it answers whether the
# mismatch reproduces on the very same SQLite.
& $Gcc -O2 "-D_WIN32_WINNT=0x0A00" -w @sqliteDefines "-I$build" -o (Join-Path $build "sqlite3_trunk.exe") (Join-Path $OutDir "shell.c") (Join-Path $build "sqlite3.c")
if ($LASTEXITCODE -ne 0) { throw "compiling the shell failed" }

"select sqlite_version(), sqlite_source_id();" | & (Join-Path $build "sqlite3_trunk.exe")
Write-Host "Built $build\sqlitejdbc.dll and $build\sqlite3_trunk.exe"
