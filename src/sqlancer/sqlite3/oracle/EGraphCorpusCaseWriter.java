package sqlancer.sqlite3.oracle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.SQLGlobalState;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;

public final class EGraphCorpusCaseWriter {

    public static final String CASE_BEGIN = "-- EGRAPH_CORPUS_CASE_BEGIN";
    public static final String SETUP_BEGIN = "-- EGRAPH_CORPUS_SETUP_BEGIN";
    public static final String SETUP_DELTA = "-- EGRAPH_CORPUS_SETUP_DELTA";
    public static final String BASE_QUERY = "-- EGRAPH_BASE_QUERY";
    public static final String REPLAY_QUERY = "-- EGRAPH_REPLAY_QUERY";
    public static final String CASE_END = "-- EGRAPH_CORPUS_CASE_END";

    private static final AtomicInteger CASE_COUNT = new AtomicInteger();
    private static final AtomicInteger EMPTY_CASE_COUNT = new AtomicInteger();
    private static final AtomicLong ATTEMPT_COUNT = new AtomicLong();
    private static final int MAX_CASES = Integer.getInteger("sqlite3.egraph.corpus.maxCases", 5000);
    private static final int MAX_EMPTY_CASES = Integer.getInteger("sqlite3.egraph.corpus.maxEmptyCases", 1000);
    private static final int MAX_ROWS_PER_TABLE = Integer.getInteger("sqlite3.egraph.corpus.maxRowsPerTable", 200);
    // Capture every N-th case so the recorded window spans the whole long run
    // instead of just its first minutes. Default 1 keeps the old behavior.
    private static final int SAMPLE_INTERVAL = Integer.getInteger("sqlite3.egraph.corpus.sampleInterval", 1);
    // Snapshot delta encoding. A captured case used to re-serialize the whole
    // database - measured at ~560 KB per case, of which ~95% of the tables were
    // byte-identical to the previous case. Writing only the tables that actually
    // changed keeps replay-all.sql (and therefore the replay itself) roughly an
    // order of magnitude smaller. Every KEYFRAME_INTERVAL cases a full snapshot
    // is written so the replay can be chunked and restarted from there.
    private static final boolean SNAPSHOT_DELTA = !"false"
            .equalsIgnoreCase(System.getProperty("sqlite3.egraph.corpus.snapshotDelta", "true"));
    private static final int KEYFRAME_INTERVAL = Integer.getInteger("sqlite3.egraph.corpus.keyframeInterval", 50);
    // Skip the expensive snapshot query round trip when SQLite reports the same
    // (database, schema_version, total_changes) triple as the previous case.
    private static final boolean SNAPSHOT_CACHE = !"false"
            .equalsIgnoreCase(System.getProperty("sqlite3.egraph.corpus.snapshotCache", "true"));
    /**
     * Room for one table's rows in a single INSERT. Taken from the limit the reader will actually apply, less a tenth
     * for the statement's own text, rather than from a number copied next to a comment saying what the reader's limit
     * was at the time.
     */
    private static final int MAX_INSERT_STATEMENT_CHARS = Integer.getInteger(
            "sqlite3.egraph.corpus.maxInsertStatementChars",
            SQLite3EGraphInputCorpus.maxCaseSetupStatementChars() * 9 / 10);
    /**
     * Write only the objects the case's query needs, rather than the whole database. The reader accepts a case whose
     * setup is at most 80 statements; measured on the accumulated corpus, the median snapshot was 3205 and only 4.2%
     * of them could be loaded, with 88% of the statements in the rest belonging to probe tables the query never names.
     * False restores the whole-database snapshot.
     */
    private static final boolean SNAPSHOT_ONLY_REFERENCED = !"false"
            .equalsIgnoreCase(System.getProperty("sqlite3.egraph.corpus.snapshotOnlyReferenced", "true"));
    private static String cachedSnapshotKey;
    private static List<String> cachedSnapshotSetup = List.of();
    private static SnapshotStructure lastWrittenSnapshot;
    private static int casesSinceKeyframe;
    /**
     * What the reader will have accumulated for the next case, as a statement count and a character count.
     *
     * <p>
     * The reader reconstructs a delta case by appending the delta to the previous case's setup, and a delta rebuilds a
     * table with its own DROP first - so the reconstruction is correct but grows with every delta, while the reader
     * refuses a case whose setup is over 80 statements or 12000 characters. Measured on a corpus this writer had just
     * produced: 142 of 2645 cases came back refused, all of them far from a keyframe. Tracking the same two numbers here
     * and writing a full snapshot before they are crossed is what keeps every written case loadable.
     * </p>
     */
    private static int reconstructedStatements;
    private static int reconstructedChars;
    private static final AtomicInteger DELTA_CASE_COUNT = new AtomicInteger();
    private static final java.util.regex.Pattern CREATE_TABLE_NAME = java.util.regex.Pattern.compile(
            "^CREATE\\s+(?:TEMP\\s+|TEMPORARY\\s+)?(?:VIRTUAL\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(\"(?:[^\"]|\"\")*\"|`[^`]*`|\\[[^\\]]*\\]|[A-Za-z_][A-Za-z0-9_$]*)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern INSERT_TABLE_NAME = java.util.regex.Pattern.compile(
            "^INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO\\s+"
                    + "(\"(?:[^\"]|\"\")*\"|`[^`]*`|\\[[^\\]]*\\]|[A-Za-z_][A-Za-z0-9_$]*)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern OBJECT_NAME = java.util.regex.Pattern.compile(
            "^CREATE\\s+(?:TEMP\\s+|TEMPORARY\\s+)?(UNIQUE\\s+INDEX|INDEX|VIEW|TRIGGER)\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(\"(?:[^\"]|\"\")*\"|`[^`]*`|\\[[^\\]]*\\]|[A-Za-z_][A-Za-z0-9_$]*)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private EGraphCorpusCaseWriter() {
    }

    /**
     * @param rowsAreDetermined
     *            whether the case's query says which rows it returns. A LIMIT with a total order over it does; a bare
     *            LIMIT does not, and a replayed case with a bare LIMIT can only have its row count compared. The reader
     *            cannot tell the two apart from the text, so the answer is written into the case.
     */
    public static void recordCase(SQLGlobalState<?, ?> state, String baseQuery, int rowCount,
            List<String> replayQueries, boolean rowsAreDetermined) {
        // Off unless a replay file is named: the checks below run SQL and are not free, and this is
        // also called on a mismatch's way out, where nothing should run that could replace it.
        String replayFileProperty = System.getProperty("egraph.replay.file");
        if (replayFileProperty == null || replayFileProperty.isBlank()) {
            return;
        }
        if (!(state instanceof SQLite3GlobalState) || baseQuery == null
                || !SQLite3EGraphInputCorpus.isSafeEGraphQueryInput(baseQuery)) {
            return;
        }
        String replayFilePath = System.getProperty("egraph.replay.file");
        if (replayFilePath == null || replayFilePath.isBlank() || !reserveCaseSlot(rowCount)) {
            return;
        }
        SQLite3GlobalState sqliteState = (SQLite3GlobalState) state;
        List<String> setupStatements = obtainSnapshotSetup(sqliteState, baseQuery);
        List<String> additionalReplayQueries = normalizeReplayQueries(baseQuery, replayQueries);
        if (setupStatements.isEmpty()) {
            return;
        }
        File replayFile = new File(replayFilePath);
        File parent = replayFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        synchronized (EGraphCorpusCaseWriter.class) {
            SnapshotStructure snapshot = SNAPSHOT_DELTA ? SnapshotStructure.parse(setupStatements) : null;
            List<String> deltaStatements = null;
            if (snapshot != null && lastWrittenSnapshot != null && casesSinceKeyframe < KEYFRAME_INTERVAL) {
                deltaStatements = snapshot.deltaFrom(lastWrittenSnapshot);
                if (deltaStatements != null && !readerWouldStillLoad(deltaStatements)) {
                    deltaStatements = null;
                }
            }
            // A case the reader would refuse is worse than no case: it is read and discarded on every
            // run for the rest of the corpus's life. The reader is asked directly, so the two cannot
            // drift apart again.
            String rejectReason = SQLite3EGraphInputCorpus.corpusSetupRejectReason(setupStatements);
            if (rejectReason != null) {
                EGraphSqlCoverage.recordCorpusFilterSkip("write-" + rejectReason);
                return;
            }
            boolean writeDelta = deltaStatements != null;
            try (FileWriter writer = new FileWriter(replayFile, true)) {
                writer.write(CASE_BEGIN + " rows=" + rowCount + " setup=" + (writeDelta ? "delta" : "full")
                        + (rowsAreDetermined ? " rows_determined=1" : "") + System.lineSeparator());
                if (writeDelta) {
                    // Only the tables that differ from the previous case are
                    // rebuilt; everything else is still standing in the replay
                    // database from the preceding case.
                    writer.write(SETUP_DELTA + System.lineSeparator());
                    for (String statement : deltaStatements) {
                        writer.write(ensureSemicolon(statement));
                        writer.write(System.lineSeparator());
                    }
                    DELTA_CASE_COUNT.incrementAndGet();
                } else {
                    writer.write(SETUP_BEGIN + System.lineSeparator());
                    for (String statement : setupStatements) {
                        writer.write(ensureSemicolon(statement));
                        writer.write(System.lineSeparator());
                    }
                }
                writer.write(BASE_QUERY + System.lineSeparator());
                writer.write(ensureSemicolon(baseQuery));
                writer.write(System.lineSeparator());
                for (String query : additionalReplayQueries) {
                    writer.write(REPLAY_QUERY + System.lineSeparator());
                    writer.write(ensureSemicolon(query));
                    writer.write(System.lineSeparator());
                }
                writer.write(CASE_END + System.lineSeparator());
                writer.flush();
                lastWrittenSnapshot = snapshot;
                casesSinceKeyframe = writeDelta ? casesSinceKeyframe + 1 : 1;
                if (writeDelta) {
                    reconstructedStatements += deltaStatements.size();
                    reconstructedChars += totalChars(deltaStatements);
                } else {
                    reconstructedStatements = setupStatements.size();
                    reconstructedChars = totalChars(setupStatements);
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static int getDeltaCaseCount() {
        return DELTA_CASE_COUNT.get();
    }

    public static int getCaseCount() {
        return CASE_COUNT.get();
    }

    /**
     * Returns the setup statements describing the current database, reusing the previous result when SQLite reports
     * that neither the schema nor any row has changed since then. A snapshot costs a full scan per table, so skipping
     * it for unchanged databases is what makes dense case capture affordable.
     */
    private static List<String> obtainSnapshotSetup(SQLite3GlobalState state, String baseQuery) {
        // What the case actually needs. A context refresh leaves dozens of probe tables standing,
        // and writing all of them made the median snapshot 3205 statements against a reader that
        // accepts 80: of the 8761 snapshots in the accumulated corpus, 4.2% could be loaded at all,
        // and 88% of the statements in the rest belonged to probe tables the query never names.
        Set<String> referenced = SNAPSHOT_ONLY_REFERENCED ? referencedObjects(state, baseQuery) : null;
        if (!SNAPSHOT_CACHE) {
            return createSnapshotSetup(state, referenced);
        }
        synchronized (EGraphCorpusCaseWriter.class) {
            String key = snapshotKey(state);
            if (key != null && referenced != null) {
                key = key + "|" + new java.util.TreeSet<>(referenced);
            }
            if (key != null && key.equals(cachedSnapshotKey) && !cachedSnapshotSetup.isEmpty()) {
                return cachedSnapshotSetup;
            }
            List<String> setup = createSnapshotSetup(state, referenced);
            if (key != null && !setup.isEmpty()) {
                cachedSnapshotKey = key;
                cachedSnapshotSetup = setup;
            } else {
                cachedSnapshotKey = null;
                cachedSnapshotSetup = List.of();
            }
            return setup;
        }
    }

    private static String snapshotKey(SQLite3GlobalState state) {
        String schemaVersion = readScalar(state, "PRAGMA schema_version");
        if (schemaVersion == null) {
            return null;
        }
        String totalChanges = readScalar(state, "SELECT total_changes()");
        if (totalChanges == null) {
            return null;
        }
        return state.getDatabaseName() + "|" + schemaVersion + "|" + totalChanges;
    }

    private static String readScalar(SQLite3GlobalState state, String sql) {
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null || !rs.next()) {
                return null;
            }
            return rs.getString(1);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean reserveCaseSlot(int rowCount) {
        // Interval sampling: with SAMPLE_INTERVAL > 1 only every N-th attempt is
        // captured. The per-attempt counter is NOT reset between databases, so
        // accepted cases are spread evenly across the entire run.
        long attempt = ATTEMPT_COUNT.getAndIncrement();
        if (SAMPLE_INTERVAL > 1 && attempt % SAMPLE_INTERVAL != 0) {
            return false;
        }
        if (CASE_COUNT.incrementAndGet() > MAX_CASES) {
            return false;
        }
        if (rowCount == 0 && EMPTY_CASE_COUNT.incrementAndGet() > MAX_EMPTY_CASES) {
            return false;
        }
        return true;
    }

    /**
     * @param referenced the objects the case needs, or null to write the whole database
     */
    private static List<String> createSnapshotSetup(SQLite3GlobalState state, Set<String> referenced) {
        List<String> setup = new ArrayList<>();
        try {
            state.updateSchema();
            setup.addAll(createCleanupStatements(state, referenced));
            setup.addAll(readSchemaSql(state, "table", referenced));
            setup.addAll(createFallbackTableStatements(state, referenced));
            // 索引放在数据之前：源库里索引先存在、插入时逐行拦截违反唯一索引的行，
            // 而旧顺序（数据在前）让所有行先落地，再建唯一索引就必然失败
            // ——长跑里 15 次 "UNIQUE constraint failed" 全是这么来的。
            // 顺序对齐后重放会像源库一样逐行拦截，状态也更忠实。
            setup.addAll(readSchemaSql(state, "index", referenced));
            setup.addAll(createInsertStatements(state, referenced));
            setup.addAll(readSchemaSql(state, "view", referenced));
            setup.addAll(readSchemaSql(state, "trigger", referenced));
            setup.add("ANALYZE");
        } catch (Exception ignored) {
            return List.of();
        }
        return setup;
    }

    /**
     * A parsed snapshot: the statements that rebuild each table, plus the index, view and trigger statements that sit
     * on top of them. Only used to compute deltas; a case that cannot be parsed simply falls back to a full snapshot.
     */
    /** Whether the reader would still take a case reconstructed by appending this delta to what came before. */
    private static boolean readerWouldStillLoad(List<String> deltaStatements) {
        return reconstructedStatements + deltaStatements.size() <= SQLite3EGraphInputCorpus.maxCaseSetupStatements()
                && reconstructedChars + totalChars(deltaStatements) <= SQLite3EGraphInputCorpus.maxCaseSetupChars();
    }

    private static int totalChars(List<String> statements) {
        int total = 0;
        for (String statement : statements) {
            total += statement.length();
        }
        return total;
    }

    private static final class SnapshotStructure {

        /** 一张表的重建语句，CREATE 与 INSERT 分开保存，好让 delta 把索引插在两者之间。 */
        private static final class TableStatements {
            private final List<String> creates = new ArrayList<>();
            private final List<String> inserts = new ArrayList<>();

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof TableStatements)) {
                    return false;
                }
                TableStatements that = (TableStatements) other;
                return creates.equals(that.creates) && inserts.equals(that.inserts);
            }

            @Override
            public int hashCode() {
                return creates.hashCode() * 31 + inserts.hashCode();
            }
        }

        private final java.util.LinkedHashMap<String, TableStatements> tables;
        /** 索引单独存：delta 要和完整快照一样把它们建在数据之前，插入时才会逐行拦截。 */
        private final List<String> indexes;
        /** 视图和触发器留在数据之后：建在 INSERT 之前会让重放触发源库当时还没有的触发器。 */
        private final List<String> postDataObjects;
        private final boolean analyze;

        private SnapshotStructure(java.util.LinkedHashMap<String, TableStatements> tables, List<String> indexes,
                List<String> postDataObjects, boolean analyze) {
            this.tables = tables;
            this.indexes = indexes;
            this.postDataObjects = postDataObjects;
            this.analyze = analyze;
        }

        private List<String> allObjects() {
            List<String> all = new ArrayList<>(indexes);
            all.addAll(postDataObjects);
            return all;
        }

        static SnapshotStructure parse(List<String> setup) {
            java.util.LinkedHashMap<String, TableStatements> tables = new java.util.LinkedHashMap<>();
            List<String> indexes = new ArrayList<>();
            List<String> postDataObjects = new ArrayList<>();
            boolean analyze = false;
            String currentTable = null;
            for (String rawStatement : setup) {
                String statement = rawStatement.strip();
                String upper = statement.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("DROP ")) {
                    continue; // deltas derive their own drops from the table set
                }
                if (upper.equals("ANALYZE") || upper.equals("ANALYZE;")) {
                    analyze = true;
                    continue;
                }
                if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
                    indexes.add(statement);
                    currentTable = null;
                    continue;
                }
                if (upper.startsWith("CREATE VIEW") || upper.startsWith("CREATE TRIGGER")
                        || upper.startsWith("CREATE TEMP") || upper.startsWith("CREATE TEMPORARY")) {
                    postDataObjects.add(statement);
                    currentTable = null;
                    continue;
                }
                if (upper.startsWith("CREATE ")) {
                    String name = parseCreateTableName(statement);
                    if (name == null) {
                        return null;
                    }
                    currentTable = normalizeIdentifierKey(name);
                    tables.computeIfAbsent(currentTable, key -> new TableStatements()).creates.add(statement);
                    continue;
                }
                if (upper.startsWith("INSERT ")) {
                    String name = parseInsertTableName(statement);
                    if (name == null) {
                        return null;
                    }
                    String key = normalizeIdentifierKey(name);
                    if (!tables.containsKey(key)) {
                        return null; // data without a matching create: not safe to encode as a delta
                    }
                    tables.get(key).inserts.add(statement);
                    currentTable = key;
                    continue;
                }
                return null; // unknown statement kind
            }
            return new SnapshotStructure(tables, indexes, postDataObjects, analyze);
        }

        /**
         * Returns the statements that turn the previous snapshot into this one, or null when a full snapshot should be
         * written instead.
         */
        List<String> deltaFrom(SnapshotStructure previous) {
            List<String> delta = new ArrayList<>();
            List<String> pendingInserts = new ArrayList<>();
            boolean rebuiltAnyTable = false;
            for (String table : previous.tables.keySet()) {
                if (!tables.containsKey(table)) {
                    delta.add("DROP TABLE IF EXISTS " + quoteIdentifier(table));
                    rebuiltAnyTable = true;
                }
            }
            for (java.util.Map.Entry<String, TableStatements> entry : tables.entrySet()) {
                if (entry.getValue().equals(previous.tables.get(entry.getKey()))) {
                    continue;
                }
                delta.add("DROP TABLE IF EXISTS " + quoteIdentifier(entry.getKey()));
                delta.addAll(entry.getValue().creates);
                // 数据留到对象层（索引）重建之后再灌，和完整快照的
                // 表 → 索引 → 数据 顺序保持一致。
                pendingInserts.addAll(entry.getValue().inserts);
                rebuiltAnyTable = true;
            }
            boolean objectsChanged = !indexes.equals(previous.indexes)
                    || !postDataObjects.equals(previous.postDataObjects);
            if (rebuiltAnyTable || objectsChanged) {
                // Dropping a table takes its indexes and triggers with it, so the
                // whole object layer is rebuilt whenever anything below it moved.
                List<String> drops = new ArrayList<>();
                for (String statement : previous.allObjects()) {
                    String drop = dropForObject(statement);
                    if (drop == null) {
                        return null;
                    }
                    drops.add(drop);
                }
                for (String statement : allObjects()) {
                    String drop = dropForObject(statement);
                    if (drop == null) {
                        return null;
                    }
                    if (!drops.contains(drop)) {
                        drops.add(drop);
                    }
                }
                delta.addAll(drops);
                delta.addAll(indexes);
            }
            delta.addAll(pendingInserts);
            if (rebuiltAnyTable || objectsChanged) {
                delta.addAll(postDataObjects);
            }
            if (delta.isEmpty() && !analyze) {
                return delta;
            }
            if (analyze) {
                delta.add("ANALYZE");
            }
            return delta;
        }
    }

    private static String parseCreateTableName(String statement) {
        java.util.regex.Matcher matcher = CREATE_TABLE_NAME
                .matcher(statement);
        return matcher.find() ? unquoteIdentifier(matcher.group(1)) : null;
    }

    private static String parseInsertTableName(String statement) {
        java.util.regex.Matcher matcher = INSERT_TABLE_NAME.matcher(statement);
        return matcher.find() ? unquoteIdentifier(matcher.group(1)) : null;
    }

    private static String dropForObject(String statement) {
        java.util.regex.Matcher matcher = OBJECT_NAME.matcher(statement);
        if (!matcher.find()) {
            return null;
        }
        String kind = matcher.group(1).toUpperCase(java.util.Locale.ROOT);
        if (kind.contains("INDEX")) {
            kind = "INDEX";
        } else if (kind.contains("VIEW")) {
            kind = "VIEW";
        } else if (kind.contains("TRIGGER")) {
            kind = "TRIGGER";
        } else {
            return null;
        }
        return "DROP " + kind + " IF EXISTS " + quoteIdentifier(unquoteIdentifier(matcher.group(2)));
    }

    private static String unquoteIdentifier(String identifier) {
        String trimmed = identifier.strip();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if (first == '"' && last == '"') {
                return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
            }
            if (first == '`' && last == '`' || first == '\'' && last == '\'') {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            if (first == '[' && last == ']') {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static List<String> createCleanupStatements(SQLite3GlobalState state, Set<String> referenced) {
        List<String> result = new ArrayList<>();
        List<String> views = readSchemaObjectNames(state, "view");
        List<String> triggers = readSchemaObjectNames(state, "trigger");
        List<String> tables = readSchemaObjectNames(state, "table");
        tables.sort((left, right) -> Integer.compare(right.length(), left.length()));
        // Only what this snapshot recreates is dropped. Dropping the rest would leave the replay
        // database without the probe tables an earlier case in the same file still needs.
        for (String trigger : triggers) {
            if (isKept(referenced, trigger)) {
                result.add("DROP TRIGGER IF EXISTS " + quoteIdentifier(trigger));
            }
        }
        for (String view : views) {
            if (isKept(referenced, view)) {
                result.add("DROP VIEW IF EXISTS " + quoteIdentifier(view));
            }
        }
        for (String table : tables) {
            if (isKept(referenced, table)) {
                result.add("DROP TABLE IF EXISTS " + quoteIdentifier(table));
            }
        }
        Set<String> seen = new HashSet<>(tables);
        for (SQLite3Table table : state.getSchema().getDatabaseTables()) {
            if (table.isView() || table.isVirtual() || table.getColumns().isEmpty()
                    || isInternalEGraphObject(table.getName()) || !seen.add(table.getName())
                    || !isKept(referenced, table.getName())) {
                continue;
            }
            result.add("DROP TABLE IF EXISTS " + quoteIdentifier(table.getName()));
        }
        return result;
    }

    private static List<String> createFallbackTableStatements(SQLite3GlobalState state, Set<String> referenced) {
        List<String> result = new ArrayList<>();
        for (SQLite3Table table : state.getSchema().getDatabaseTables()) {
            if (table.isView() || table.isVirtual() || table.getColumns().isEmpty()
                    || isInternalEGraphObject(table.getName()) || !isKept(referenced, table.getName())) {
                continue;
            }
            List<SQLite3Column> columns = table.getColumns().stream().filter(c -> !c.isGenerated())
                    .collect(java.util.stream.Collectors.toList());
            if (columns.isEmpty()) {
                continue;
            }
            String columnDefs = columns.stream().map(EGraphCorpusCaseWriter::fallbackColumnDefinition)
                    .collect(java.util.stream.Collectors.joining(", "));
            result.add("CREATE TABLE IF NOT EXISTS " + quoteIdentifier(table.getName()) + "(" + columnDefs + ")");
        }
        return result;
    }

    private static String fallbackColumnDefinition(SQLite3Column column) {
        String type = column.getType() == null ? "" : column.getType().toString();
        if (type.isBlank()) {
            return quoteIdentifier(column.getName());
        }
        return quoteIdentifier(column.getName()) + " " + type;
    }

    /**
     * The names the base query mentions, plus whatever those depend on: an index or trigger on a kept table, a table a
     * kept view reads. Matched on the text of the query and of each object's own SQL, which is coarse but errs towards
     * keeping - a name that only looks like a table costs one extra table in the snapshot, while a missing one would
     * make the case unreplayable.
     */
    private static Set<String> referencedObjects(SQLite3GlobalState state, String baseQuery) {
        Set<String> kept = new HashSet<>();
        if (baseQuery == null) {
            return kept;
        }
        List<String[]> objects = new ArrayList<>();
        try (SQLancerResultSet rs = new SQLQueryAdapter(
                "SELECT name, COALESCE(tbl_name, name), COALESCE(sql, '') FROM sqlite_master "
                        + "WHERE name NOT LIKE 'sqlite_%'").executeAndGet(state)) {
            if (rs == null) {
                return kept;
            }
            while (rs.next()) {
                objects.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
            }
        } catch (Exception ignored) {
            return kept;
        }
        for (String[] object : objects) {
            if (mentions(baseQuery, object[0])) {
                kept.add(normalizeIdentifierKey(object[0]));
                kept.add(normalizeIdentifierKey(object[1]));
            }
        }
        // Four passes are enough for the shapes this writes: a query names a view, the view names a
        // table, the table carries an index and a trigger, and the trigger names one more table.
        for (int pass = 0; pass < 4; pass++) {
            int before = kept.size();
            for (String[] object : objects) {
                boolean keepThis = kept.contains(normalizeIdentifierKey(object[0]))
                        || kept.contains(normalizeIdentifierKey(object[1]));
                if (!keepThis) {
                    continue;
                }
                kept.add(normalizeIdentifierKey(object[0]));
                kept.add(normalizeIdentifierKey(object[1]));
                for (String[] other : objects) {
                    if (mentions(object[2], other[0])) {
                        kept.add(normalizeIdentifierKey(other[0]));
                        kept.add(normalizeIdentifierKey(other[1]));
                    }
                }
            }
            if (kept.size() == before) {
                break;
            }
        }
        return kept;
    }

    private static boolean mentions(String sql, String name) {
        if (sql == null || name == null || name.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern
                .compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sql).find();
    }

    private static boolean isKept(Set<String> referenced, String name) {
        return referenced == null || referenced.contains(normalizeIdentifierKey(name));
    }

    private static List<String> readSchemaSql(SQLite3GlobalState state, String type, Set<String> referenced) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND type = '" + type
                + "' AND name NOT LIKE 'sqlite_%' UNION ALL SELECT name, sql FROM sqlite_temp_master "
                + "WHERE sql IS NOT NULL AND type = '" + type + "' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        List<String> virtualTableNames = readVirtualTableNames(state);
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null) {
                return result;
            }
            while (rs.next()) {
                String name = rs.getString(1);
                if ("table".equals(type) && isVirtualTableShadowObject(name, virtualTableNames)) {
                    continue;
                }
                if (!isKept(referenced, name)) {
                    continue;
                }
                String schemaSql = rs.getString(2);
                if (schemaSql != null && !schemaSql.isBlank()) {
                    result.add(schemaSql);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static List<String> readSchemaObjectNames(SQLite3GlobalState state, String type) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT name FROM sqlite_master WHERE sql IS NOT NULL AND type = '" + type
                + "' AND name NOT LIKE 'sqlite_%' UNION ALL SELECT name FROM sqlite_temp_master "
                + "WHERE sql IS NOT NULL AND type = '" + type + "' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        List<String> virtualTableNames = readVirtualTableNames(state);
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null) {
                return result;
            }
            while (rs.next()) {
                String name = rs.getString(1);
                if ("table".equals(type) && isVirtualTableShadowObject(name, virtualTableNames)) {
                    continue;
                }
                result.add(name);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static List<String> readVirtualTableNames(SQLite3GlobalState state) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%' "
                + "UNION ALL SELECT name FROM sqlite_temp_master WHERE type = 'table' "
                + "AND sql LIKE 'CREATE VIRTUAL TABLE%'";
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null) {
                return result;
            }
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static boolean isVirtualTableShadowObject(String objectName, List<String> virtualTableNames) {
        if (objectName == null) {
            return false;
        }
        String normalizedObjectName = normalizeIdentifierKey(objectName);
        for (String virtualTableName : virtualTableNames) {
            if (virtualTableName == null) {
                continue;
            }
            String virtualTablePrefix = normalizeIdentifierKey(virtualTableName) + "_";
            if (!normalizedObjectName.startsWith(virtualTablePrefix)) {
                continue;
            }
            String suffix = normalizedObjectName.substring(virtualTablePrefix.length());
            if (isKnownVirtualTableShadowSuffix(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownVirtualTableShadowSuffix(String suffix) {
        switch (suffix) {
            case "content":
            case "segments":
            case "segdir":
            case "docsize":
            case "stat":
            case "data":
            case "idx":
            case "config":
            case "node":
            case "parent":
            case "rowid":
                return true;
            default:
                return false;
        }
    }

    private static List<String> normalizeReplayQueries(String baseQuery, List<String> replayQueries) {
        if (replayQueries == null || replayQueries.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        String normalizedBaseQuery = stripTrailingSemicolon(baseQuery);
        seen.add(normalizedBaseQuery);
        for (String query : replayQueries) {
            if (!isReplaySelectQuery(query)) {
                continue;
            }
            seen.add(stripTrailingSemicolon(query));
        }
        seen.remove(normalizedBaseQuery);
        return new ArrayList<>(seen);
    }

    private static boolean isReplaySelectQuery(String query) {
        if (query == null) {
            return false;
        }
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("SELECT ") || upper.startsWith("SELECT\n") || upper.startsWith("WITH ")
                || upper.startsWith("WITH\n");
    }

    private static String stripTrailingSemicolon(String query) {
        String trimmed = query.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }

    private static List<String> createInsertStatements(SQLite3GlobalState state, Set<String> referenced) {
        List<String> result = new ArrayList<>();
        Set<String> emittedTables = new HashSet<>();
        for (SQLite3Table table : state.getSchema().getDatabaseTables()) {
            if (table.isView() || table.isVirtual() || table.getColumns().isEmpty()
                    || isInternalEGraphObject(table.getName())) {
                continue;
            }
            String tableName = table.getName();
            emittedTables.add(normalizeIdentifierKey(tableName));
            List<SQLite3Column> columns = table.getColumns().stream().filter(c -> !c.isGenerated())
                    .collect(java.util.stream.Collectors.toList());
            if (columns.isEmpty()) {
                continue;
            }
            String columnList = columns.stream().map(c -> quoteIdentifier(c.getName()))
                    .collect(java.util.stream.Collectors.joining(", "));
            String valueList = columns.stream().map(c -> "quote(" + quoteIdentifier(c.getName()) + ")")
                    .collect(java.util.stream.Collectors.joining(" || ', ' || "));
            String sql = "SELECT " + valueList + " FROM " + quoteIdentifier(tableName) + " LIMIT " + MAX_ROWS_PER_TABLE;
            try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
                if (rs == null) {
                    continue;
                }
                // One statement per table rather than per row. The reader counts statements, not
                // rows, and a table at the row cap used to be 128 of the 80 it accepts: measured on
                // a capture, the median snapshot was 1023 statements of which 1020 were these
                // inserts. Several tuples in one VALUES list say exactly the same thing.
                List<String> tuples = new ArrayList<>();
                int tupleChars = 0;
                while (rs.next()) {
                    String tuple = "(" + rs.getString(1) + ")";
                    // Kept under the reader's per-statement limit: a table whose rows do not fit in
                    // one statement is split across a few rather than dropped.
                    if (tupleChars + tuple.length() > MAX_INSERT_STATEMENT_CHARS && !tuples.isEmpty()) {
                        result.add("INSERT INTO " + quoteIdentifier(tableName) + "(" + columnList + ") VALUES "
                                + String.join(", ", tuples));
                        tuples.clear();
                        tupleChars = 0;
                    }
                    tuples.add(tuple);
                    tupleChars += tuple.length() + 2;
                }
                if (!tuples.isEmpty()) {
                    result.add("INSERT INTO " + quoteIdentifier(tableName) + "(" + columnList + ") VALUES "
                            + String.join(", ", tuples));
                }
            } catch (Exception ignored) {
            }
        }
        result.addAll(createCatalogInsertStatements(state, emittedTables, referenced));
        return result;
    }

    private static List<String> createCatalogInsertStatements(SQLite3GlobalState state, Set<String> excludedTables,
            Set<String> referenced) {
        List<String> result = new ArrayList<>();
        List<String> virtualTableNames = readVirtualTableNames(state);
        for (String tableName : readSchemaObjectNames(state, "table")) {
            if (tableName == null || excludedTables.contains(normalizeIdentifierKey(tableName))
                    || isVirtualTableShadowObject(tableName, virtualTableNames)
                    || isReadOnlyVirtualTable(state, tableName) || !isKept(referenced, tableName)) {
                continue;
            }
            List<String> columns = readTableColumnNames(state, tableName);
            if (columns.isEmpty()) {
                continue;
            }
            String columnList = columns.stream().map(EGraphCorpusCaseWriter::quoteIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));
            String valueList = columns.stream().map(c -> "quote(" + quoteIdentifier(c) + ")")
                    .collect(java.util.stream.Collectors.joining(" || ', ' || "));
            String sql = "SELECT " + valueList + " FROM " + quoteIdentifier(tableName) + " LIMIT " + MAX_ROWS_PER_TABLE;
            try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
                if (rs == null) {
                    continue;
                }
                // One statement per table rather than per row. The reader counts statements, not
                // rows, and a table at the row cap used to be 128 of the 80 it accepts: measured on
                // a capture, the median snapshot was 1023 statements of which 1020 were these
                // inserts. Several tuples in one VALUES list say exactly the same thing.
                List<String> tuples = new ArrayList<>();
                int tupleChars = 0;
                while (rs.next()) {
                    String tuple = "(" + rs.getString(1) + ")";
                    // Kept under the reader's per-statement limit: a table whose rows do not fit in
                    // one statement is split across a few rather than dropped.
                    if (tupleChars + tuple.length() > MAX_INSERT_STATEMENT_CHARS && !tuples.isEmpty()) {
                        result.add("INSERT INTO " + quoteIdentifier(tableName) + "(" + columnList + ") VALUES "
                                + String.join(", ", tuples));
                        tuples.clear();
                        tupleChars = 0;
                    }
                    tuples.add(tuple);
                    tupleChars += tuple.length() + 2;
                }
                if (!tuples.isEmpty()) {
                    result.add("INSERT INTO " + quoteIdentifier(tableName) + "(" + columnList + ") VALUES "
                            + String.join(", ", tuples));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static List<String> readTableColumnNames(SQLite3GlobalState state, String tableName) {
        List<String> result = new ArrayList<>();
        String sql = "PRAGMA table_info(" + quoteIdentifier(tableName) + ")";
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null) {
                return result;
            }
            while (rs.next()) {
                String columnName = rs.getString(2);
                if (columnName != null) {
                    result.add(columnName);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static boolean isReadOnlyVirtualTable(SQLite3GlobalState state, String tableName) {
        String sql = "SELECT sql FROM sqlite_master WHERE name = " + quoteLiteral(tableName)
                + " UNION ALL SELECT sql FROM sqlite_temp_master WHERE name = " + quoteLiteral(tableName);
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql).executeAndGet(state)) {
            if (rs == null || !rs.next()) {
                return false;
            }
            String schemaSql = rs.getString(1);
            if (schemaSql == null) {
                return false;
            }
            String normalizedSchemaSql = schemaSql.toLowerCase(java.util.Locale.ROOT);
            // Virtual tables whose rows cannot be read back at all. Trying to
            // serialize them throws while building the snapshot:
            //   - fts4aux / fts3aux / fts5vocab / dbstat are read-only views
            //   - fts3tokenize / fts4tokenize are table-valued functions and reject
            //     a query without an input constraint
            //   - a contentless FTS5 index (content='') stores no text to read
            return normalizedSchemaSql.contains("using dbstat") || normalizedSchemaSql.contains("using fts5vocab")
                    || normalizedSchemaSql.contains("using fts4aux") || normalizedSchemaSql.contains("using fts3aux")
                    || normalizedSchemaSql.contains("using fts3tokenize")
                    || normalizedSchemaSql.contains("using fts4tokenize")
                    || normalizedSchemaSql.contains("content=''")
                    || normalizedSchemaSql.contains("content = ''")
                    || normalizedSchemaSql.contains("contentless_delete");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeIdentifierKey(String identifier) {
        return identifier.toLowerCase(java.util.Locale.ROOT);
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static boolean isInternalEGraphObject(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).startsWith("egraph_");
    }

    private static String ensureSemicolon(String statement) {
        String trimmed = statement.strip();
        if (trimmed.endsWith(";")) {
            return trimmed;
        }
        return trimmed + ";";
    }
}
