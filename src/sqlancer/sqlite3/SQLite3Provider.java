package sqlancer.sqlite3;

import java.io.File;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.sqlite3.gen.SQLite3AnalyzeGenerator;
import sqlancer.sqlite3.gen.SQLite3CreateVirtualRtreeTabelGenerator;
import sqlancer.sqlite3.gen.SQLite3ExplainGenerator;
import sqlancer.sqlite3.gen.SQLite3PragmaGenerator;
import sqlancer.sqlite3.gen.SQLite3ReindexGenerator;
import sqlancer.sqlite3.gen.SQLite3TransactionGenerator;
import sqlancer.sqlite3.gen.SQLite3VacuumGenerator;
import sqlancer.sqlite3.gen.SQLite3VirtualFTSTableCommandGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3AlterTable;
import sqlancer.sqlite3.gen.ddl.SQLite3CreateTriggerGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3CreateVirtualFTSTableGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3DropIndexGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3DropTableGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3IndexGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3TableGenerator;
import sqlancer.sqlite3.gen.ddl.SQLite3ViewGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3DeleteGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3InsertGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3StatTableGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3UpdateGenerator;
import sqlancer.sqlite3.oracle.SQLite3EGraphInputCorpus;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;

@AutoService(DatabaseProvider.class)
public class SQLite3Provider extends SQLProviderAdapter<SQLite3GlobalState, SQLite3Options> {

    public static boolean allowFloatingPointFp = true;
    public static boolean mustKnowResult;

    // PRAGMAS to achieve good performance
    private static final List<String> DEFAULT_PRAGMAS = Arrays.asList("PRAGMA cache_size = 50000;",
            "PRAGMA temp_store=MEMORY;", "PRAGMA synchronous=off;");

    public SQLite3Provider() {
        super(SQLite3GlobalState.class, SQLite3Options.class);
    }

    public enum Action implements AbstractAction<SQLite3GlobalState> {
        PRAGMA(SQLite3PragmaGenerator::insertPragma), // 0
        CREATE_INDEX(SQLite3IndexGenerator::insertIndex), // 1
        CREATE_VIEW(SQLite3ViewGenerator::generate), // 2
        CREATE_TRIGGER(SQLite3CreateTriggerGenerator::create), // 3
        CREATE_TABLE(SQLite3TableGenerator::createRandomTableStatement), // 4
        CREATE_VIRTUALTABLE(SQLite3CreateVirtualFTSTableGenerator::createRandomTableStatement), // 5
        CREATE_RTREETABLE(SQLite3CreateVirtualRtreeTabelGenerator::createRandomTableStatement), // 6
        INSERT(SQLite3InsertGenerator::insertRow), // 7
        DELETE(SQLite3DeleteGenerator::deleteContent), // 8
        ALTER(SQLite3AlterTable::alterTable), // 9
        UPDATE(SQLite3UpdateGenerator::updateRow), // 10
        DROP_INDEX(SQLite3DropIndexGenerator::dropIndex), // 11
        DROP_TABLE(SQLite3DropTableGenerator::dropTable), // 12
        DROP_VIEW(SQLite3ViewGenerator::dropView), // 13
        VACUUM(SQLite3VacuumGenerator::executeVacuum), // 14
        REINDEX(SQLite3ReindexGenerator::executeReindex), // 15
        ANALYZE(SQLite3AnalyzeGenerator::generateAnalyze), // 16
        EXPLAIN(SQLite3ExplainGenerator::explain), // 17
        CHECK_RTREE_TABLE((g) -> {
            SQLite3Table table = g.getSchema().getRandomTableOrBailout(t -> t.getName().startsWith("r"));
            String format = String.format("SELECT rtreecheck('%s');", table.getName());
            return new SQLQueryAdapter(format, ExpectedErrors.from("The database file is locked"));
        }), // 18
        VIRTUAL_TABLE_ACTION(SQLite3VirtualFTSTableCommandGenerator::create), // 19
        MANIPULATE_STAT_TABLE(SQLite3StatTableGenerator::getQuery), // 20
        TRANSACTION_START(SQLite3TransactionGenerator::generateBeginTransaction) {
            @Override
            public boolean canBeRetried() {
                return false;
            }

        }, // 21
        ROLLBACK_TRANSACTION(SQLite3TransactionGenerator::generateRollbackTransaction) {
            @Override
            public boolean canBeRetried() {
                return false;
            }
        }, // 22
        COMMIT(SQLite3TransactionGenerator::generateCommit) {
            @Override
            public boolean canBeRetried() {
                return false;
            }
        }; // 23

        private final SQLQueryProvider<SQLite3GlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<SQLite3GlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(SQLite3GlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    private enum TableType {
        NORMAL, FTS, RTREE
    }

    private static final boolean EGRAPH_RTREE_TARGETS = !"false"
            .equalsIgnoreCase(System.getProperty("egraph.rtreeTargets", "true"));

    /**
     * Ordinary tables EGRAPH creates, on top of the R-Tree when egraph.rtreeTargets is on.
     *
     * <p>
     * 3 by default, to go with egraph.joinPercent: the reports this exists for need three tables
     * joined in a nest, and a single extra table measurably bought nothing when tried on its own.
     * 1 restores the historical single-table database.
     * </p>
     */
    private static final int EGRAPH_TABLES = Math.max(1,
            Integer.parseInt(System.getProperty("egraph.tables", "3")));

    /**
     * Multiplier for the per-action budgets, which are totals across tables rather than per-table
     * figures. Without it an extra table just makes every table likelier to end up empty.
     */
    private static int egraphTableScale(SQLite3GlobalState globalState) {
        if (globalState.getDbmsSpecificOptions().oracles != SQLite3OracleFactory.EGRAPH) {
            return 1;
        }
        return Math.max(1, EGRAPH_TABLES);
    }

    private static final Action[] EGRAPH_ACTIONS = {
            Action.PRAGMA,
            Action.CREATE_INDEX,
            Action.INSERT,
            Action.UPDATE,
            Action.ANALYZE
    };

    private static int mapActions(SQLite3GlobalState globalState, Action a) {
        int nrPerformed = 0;
        Randomly r = globalState.getRandomly();
        switch (a) {
        case CREATE_VIEW:
            nrPerformed = r.getInteger(0, 2);
            break;
        case DELETE:
        case DROP_VIEW:
        case DROP_INDEX:
            nrPerformed = r.getInteger(0, 0);
            break;
        case ALTER:
            nrPerformed = r.getInteger(0, 0);
            break;
        case EXPLAIN:
        case CREATE_TRIGGER:
        case DROP_TABLE:
            nrPerformed = r.getInteger(0, 0);
            break;
        case VACUUM:
        case CHECK_RTREE_TABLE:
            nrPerformed = r.getInteger(0, 3);
            break;
        case INSERT:
            nrPerformed = r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            break;
        case MANIPULATE_STAT_TABLE:
            nrPerformed = r.getInteger(0, 5);
            break;
        case CREATE_INDEX:
            nrPerformed = r.getInteger(0, 5);
            break;
        case VIRTUAL_TABLE_ACTION:
        case UPDATE:
            nrPerformed = r.getInteger(0, 30);
            break;
        case PRAGMA:
            nrPerformed = r.getInteger(0, 20);
            break;
        case CREATE_TABLE:
        case CREATE_VIRTUALTABLE:
        case CREATE_RTREETABLE:
            nrPerformed = 0;
            break;
        case TRANSACTION_START:
        case REINDEX:
        case ANALYZE:
        case ROLLBACK_TRANSACTION:
        case COMMIT:
        default:
            nrPerformed = r.getInteger(1, 10);
            break;
        }
        return nrPerformed;
    }

    private static int mapEGraphActions(SQLite3GlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case PRAGMA:
            return r.getInteger(0, 2);
        case CREATE_INDEX:
            return r.getInteger(0, 2) * egraphTableScale(globalState);
        case INSERT:
            return r.getInteger(1, Math.max(2, Math.min(6, globalState.getOptions().getMaxNumberInserts())))
                    * egraphTableScale(globalState);
        case UPDATE:
            return r.getInteger(0, 2);
        case ANALYZE:
            return r.getInteger(0, 1);
        default:
            return 0;
        }
    }

    @Override
    public void generateDatabase(SQLite3GlobalState globalState) throws Exception {
        Randomly r = new Randomly(SQLite3SpecialStringGenerator::generate);
        globalState.setRandomly(r);
        if (globalState.getDbmsSpecificOptions().generateDatabase) {
            boolean isEGraph = globalState.getDbmsSpecificOptions().oracles == SQLite3OracleFactory.EGRAPH;

            addSensiblePragmaDefaults(globalState);
            if (isEGraph && SQLite3EGraphInputCorpus.isConfigured(globalState.getDbmsSpecificOptions())) {
                return;
            }
            // That reasoning held while EGRAPH wrapped one table in a singleton AbstractTables.
            // Since egraph.joinPercent the base query can span several tables, and the bugs that
            // needs are real: a nested RIGHT JOIN report on sqlite.org/bugs returns a row that
            // should not exist, and it stops reproducing if any one of its three tables, the
            // nesting, or the RIGHT is removed. Verified in isolation that our own IS NULL rules
            // rewrite that query's WHERE into a form where the bug does not fire, so the two sides
            // disagree and the oracle would report it - the only thing missing is generating a
            // query of that shape.
            //
            // The other half of the old comment still applies: INSERT and CREATE_INDEX budgets are
            // totals across tables, so mapEGraphActions scales them by the table count.
            int nrTablesToCreate = isEGraph ? EGRAPH_TABLES : 1;
            // With egraph.rtreeTargets on, EGRAPH also gets one R-Tree table so the oracle has
            // something whose predicates survive SQLite's front-end normalisation. See the comment
            // on the target-table filter in SQLite3OracleFactory for why R-Tree specifically.
            boolean egraphRtreeTable = isEGraph && EGRAPH_RTREE_TARGETS
                    && globalState.getDbmsSpecificOptions().testRtree;
            if (egraphRtreeTable) {
                nrTablesToCreate++;
            }
            if (!isEGraph) {
                if (Randomly.getBoolean()) {
                    nrTablesToCreate++;
                }
                while (Randomly.getBooleanWithSmallProbability()) {
                    nrTablesToCreate++;
                }
            }
            int i = 0;
            String rtreeTableName = null;

            do {
                SQLQueryAdapter tableQuery;
                if (isEGraph) {
                    // Table 0 is always a normal one: the wrapper shapes, the corpus setup and the
                    // snapshot writer all assume a regular table named t0 exists. The extra table,
                    // when asked for, is an R-Tree - never FTS, whose index is only reachable
                    // through MATCH and which the rewrite rules therefore cannot drive.
                    if (egraphRtreeTable && i == 1) {
                        tableQuery = SQLite3CreateVirtualRtreeTabelGenerator.createTableStatement("rt" + i,
                                globalState);
                        rtreeTableName = "rt" + i;
                    } else {
                        tableQuery = SQLite3TableGenerator.createTableStatement(
                                DBMSCommon.createTableName(i), globalState);
                    }
                } else {
                    tableQuery = getTableQuery(globalState, i);
                }
                i++;
                globalState.executeStatement(tableQuery);
            } while (globalState.getSchema().getDatabaseTables().size() < nrTablesToCreate);
            assert globalState.getSchema().getTables().getTables().size() == nrTablesToCreate;
            if (rtreeTableName != null) {
                seedRtreeTable(globalState, rtreeTableName);
            }
            checkTablesForGeneratedColumnLoops(globalState);
            if (globalState.getDbmsSpecificOptions().testDBStats && Randomly.getBooleanWithSmallProbability()) {
                SQLQueryAdapter tableQuery = new SQLQueryAdapter(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS stat USING dbstat(main)");
                globalState.executeStatement(tableQuery);
            }
            Action[] actions = isEGraph ? EGRAPH_ACTIONS : Action.values();
            StatementExecutor.ActionMapper<SQLite3GlobalState, Action> actionMapper = isEGraph
                    ? SQLite3Provider::mapEGraphActions
                    : SQLite3Provider::mapActions;
            StatementExecutor<SQLite3GlobalState, Action> se = new StatementExecutor<>(globalState, actions,
                    actionMapper, (q) -> {
                        if (q.couldAffectSchema() && globalState.getSchema().getDatabaseTables().isEmpty()) {
                            throw new IgnoreMeException();
                        }
                    });
            se.executeStatements();


            SQLQueryAdapter query = SQLite3TransactionGenerator.generateCommit(globalState);
            globalState.executeStatement(query);

            // also do an abort for DEFERRABLE INITIALLY DEFERRED
            query = SQLite3TransactionGenerator.generateRollbackTransaction(globalState);
            globalState.executeStatement(query);
            if (isEGraph) {
                replayEGraphInputCorpus(globalState);
            }
        }
    }

    /**
     * Gives the EGRAPH R-Tree table a deterministic starting population.
     *
     * SQLancer's generic INSERT cannot fill an R-Tree usefully: it picks a random subset of columns,
     * so the coordinate pairs are usually incomplete, and the values it invents violate the module's
     * own x1&lt;=x2 constraint often enough that most rows are silently dropped by OR IGNORE. The
     * table then sits at a couple of rows, every random predicate probes empty, and the check is
     * discarded before the query ever runs - which is exactly why the first two runs after enabling
     * R-Tree targets reported zero queries against it.
     *
     * The rows are spread over overlapping and nested bounding boxes on purpose: that is what makes
     * the R-Tree actually branch, so a rewritten predicate can reach a different node-traversal path
     * rather than just scanning a single leaf.
     */
    private static void seedRtreeTable(SQLite3GlobalState globalState, String tableName) {
        try {
            List<SQLite3Column> columns = globalState.getSchema().getDatabaseTables().stream()
                    .filter(t -> t.getName().equals(tableName)).findFirst()
                    .map(SQLite3Table::getColumns).orElse(null);
            if (columns == null || columns.size() < 3) {
                return;
            }
            // Column 0 is the R-Tree id; the rest come in (min, max) pairs.
            int nrPairs = (columns.size() - 1) / 2;
            if (nrPairs < 1) {
                return;
            }
            StringBuilder names = new StringBuilder(columns.get(0).getName());
            for (int c = 1; c <= nrPairs * 2; c++) {
                names.append(", ").append(columns.get(c).getName());
            }
            StringBuilder values = new StringBuilder();
            for (int row = 0; row < 64; row++) {
                if (row > 0) {
                    values.append(", ");
                }
                values.append('(').append(row);
                for (int pair = 0; pair < nrPairs; pair++) {
                    int lo = (row * 7 + pair * 13) % 40 - 20;
                    int hi = lo + (row % 5) + 1;
                    values.append(", ").append(lo).append(", ").append(hi);
                }
                values.append(')');
            }
            globalState.executeStatement(new SQLQueryAdapter(
                    "INSERT OR IGNORE INTO " + tableName + "(" + names + ") VALUES " + values, true));
            globalState.updateSchema();
        } catch (Exception ignored) {
            // A failed seed only means the table stays empty and the oracle skips it.
        }
    }

    public static void ensureEGraphRandomDatabase(SQLite3GlobalState globalState) throws Exception {
        Set<String> existingNames = new HashSet<>();
        int ordinaryTables = 0;
        try {
            globalState.updateSchema();
            for (SQLite3Table table : globalState.getSchema().getDatabaseTables()) {
                existingNames.add(table.getName().toLowerCase(Locale.ROOT));
                if (!table.isView() && !table.isVirtual() && !table.getColumns().isEmpty()) {
                    ordinaryTables++;
                }
            }
        } catch (AssertionError ignored) {
        }
        // A corpus case brings its own t0, and this used to return the moment any ordinary table
        // existed. That is why a join never appeared in a corpus-driven run: the join path needs a
        // second ordinary table, and nothing ever created one. The missing tables are topped up
        // instead. With egraph.tables at its default of 1, one ordinary table is still enough and
        // this returns exactly where it used to.
        boolean wantRtree = EGRAPH_RTREE_TARGETS && globalState.getDbmsSpecificOptions().testRtree
                && existingNames.stream().noneMatch(name -> name.startsWith("rt"));
        if (ordinaryTables >= EGRAPH_TABLES && !wantRtree) {
            return;
        }

        String rtreeTableName = null;
        int nextName = 0;
        for (int created = ordinaryTables; created < EGRAPH_TABLES; created++) {
            String name;
            do {
                name = DBMSCommon.createTableName(nextName++);
            } while (existingNames.contains(name.toLowerCase(Locale.ROOT)));
            existingNames.add(name.toLowerCase(Locale.ROOT));
            globalState.executeStatement(SQLite3TableGenerator.createTableStatement(name, globalState));
        }
        if (wantRtree) {
            int rtreeIndex = 1;
            while (existingNames.contains(("rt" + rtreeIndex).toLowerCase(Locale.ROOT))) {
                rtreeIndex++;
            }
            rtreeTableName = "rt" + rtreeIndex;
            globalState.executeStatement(
                    SQLite3CreateVirtualRtreeTabelGenerator.createTableStatement(rtreeTableName, globalState));
        }
        globalState.updateSchema();
        if (rtreeTableName != null) {
            seedRtreeTable(globalState, rtreeTableName);
        }
        checkTablesForGeneratedColumnLoops(globalState);

        StatementExecutor<SQLite3GlobalState, Action> se = new StatementExecutor<>(globalState, EGRAPH_ACTIONS,
                SQLite3Provider::mapEGraphActions, (q) -> {
                    if (q.couldAffectSchema() && globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();

        SQLQueryAdapter query = SQLite3TransactionGenerator.generateCommit(globalState);
        globalState.executeStatement(query);
        query = SQLite3TransactionGenerator.generateRollbackTransaction(globalState);
        globalState.executeStatement(query);
        globalState.updateSchema();
    }

    private boolean replayEGraphInputCorpus(SQLite3GlobalState globalState) throws Exception {
        if (!SQLite3EGraphInputCorpus.isConfigured(globalState.getDbmsSpecificOptions())) {
            return false;
        }
        addSensiblePragmaDefaults(globalState);
        boolean executedSetupStatement = false;
        boolean schemaChanged = false;
        for (String statement : SQLite3EGraphInputCorpus.readSetupStatements(globalState.getDbmsSpecificOptions())) {
            try {
                SQLQueryAdapter query = new SQLQueryAdapter(statement, corpusStatementCouldAffectSchema(statement));
                if (query.execute(globalState, false)) {
                    executedSetupStatement = true;
                    logExecutedCorpusStatement(globalState, query);
                    schemaChanged |= query.couldAffectSchema();
                }
            } catch (AssertionError ignored) {
            } catch (RuntimeException ignored) {
            } catch (Exception ignored) {
            }
        }
        try {
            if (schemaChanged) {
                globalState.updateSchema();
            }
        } catch (AssertionError ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        return executedSetupStatement && !globalState.getSchema().getDatabaseTables().isEmpty();
    }

    private static boolean corpusStatementCouldAffectSchema(String statement) {
        String normalized = statement.stripLeading().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("(?s)^(CREATE|DROP|ALTER)\\b.*")
                || normalized.matches("(?s).*\\b(CREATE|DROP|ALTER)\\s+(TABLE|INDEX|VIEW|TRIGGER|VIRTUAL)\\b.*");
    }

    private static void logExecutedCorpusStatement(SQLite3GlobalState globalState, SQLQueryAdapter query) {
        if (globalState.getState() != null) {
            globalState.getState().logStatement(query);
        }
        if (globalState.getOptions() != null && globalState.getOptions().logEachSelect()
                && globalState.getLogger() != null) {
            globalState.getLogger().writeCurrent(query.getQueryString());
        }
    }

    private static void checkTablesForGeneratedColumnLoops(SQLite3GlobalState globalState) throws Exception {
        for (SQLite3Table table : globalState.getSchema().getDatabaseTables()) {
            SQLQueryAdapter q = new SQLQueryAdapter("SELECT * FROM " + table.getName(),
                    ExpectedErrors.from("needs an odd number of arguments", " requires an even number of arguments",
                            "generated column loop", "integer overflow", "malformed JSON",
                            "JSON cannot hold BLOB values", "JSON path error", "labels must be TEXT",
                            "table does not support scanning"));
            if (!q.execute(globalState)) {
                throw new IgnoreMeException();
            }
        }
    }

    private SQLQueryAdapter getTableQuery(SQLite3GlobalState globalState, int i) throws AssertionError {
        SQLQueryAdapter tableQuery;
        List<TableType> options = new ArrayList<>(Arrays.asList(TableType.values()));
        if (!globalState.getDbmsSpecificOptions().testFts) {
            options.remove(TableType.FTS);
        }
        if (!globalState.getDbmsSpecificOptions().testRtree) {
            options.remove(TableType.RTREE);
        }
        switch (Randomly.fromList(options)) {
        case NORMAL:
            String tableName = DBMSCommon.createTableName(i);
            tableQuery = SQLite3TableGenerator.createTableStatement(tableName, globalState);
            break;
        case FTS:
            String ftsTableName = "v" + DBMSCommon.createTableName(i);
            tableQuery = SQLite3CreateVirtualFTSTableGenerator.createTableStatement(ftsTableName,
                    globalState.getRandomly());
            break;
        case RTREE:
            String rTreeTableName = "rt" + i;
            tableQuery = SQLite3CreateVirtualRtreeTabelGenerator.createTableStatement(rTreeTableName, globalState);
            break;
        default:
            throw new AssertionError();
        }
        return tableQuery;
    }

    private void addSensiblePragmaDefaults(SQLite3GlobalState globalState) throws Exception {
        List<String> pragmasToExecute = new ArrayList<>();
        if (!Randomly.getBooleanWithSmallProbability()) {
            pragmasToExecute.addAll(DEFAULT_PRAGMAS);
        }
        // PRAGMA case_sensitive_like is no longer set. It re-registers LIKE without SQLITE_DETERMINISTIC, so an
        // index whose expression or WHERE uses LIKE turns into SQLITE_CORRUPT ("malformed database schema")
        // the next time the schema is parsed - a long run stopped on exactly that. Upstream calls it
        // documented behaviour of a deprecated pragma (forum d3abdc1a4d, WONT FIX), so every report it
        // produces is one of ours. SQLite3PragmaGenerator already leaves it out for the same reason.
        if (Randomly.getBoolean() && globalState.getDbmsSpecificOptions().oracles != SQLite3OracleFactory.PQS) {
            // the encoding has an influence how binary strings are cast
            pragmasToExecute.add(String.format("PRAGMA encoding = '%s';",
                    Randomly.fromOptions("UTF-8", "UTF-16", "UTF-16le", "UTF-16be")));
        }
        for (String s : pragmasToExecute) {
            globalState.executeStatement(new SQLQueryAdapter(s));
        }
    }

    @Override
    public SQLConnection createDatabase(SQLite3GlobalState globalState) throws SQLException {
        File dir = new File("." + File.separator + "databases");
        if (!dir.exists()) {
            dir.mkdir();
        }
        File dataBase = new File(dir, globalState.getDatabaseName() + ".db");
        if (dataBase.exists() && ((SQLite3GlobalState) globalState).getDbmsSpecificOptions().deleteIfExists) {
            dataBase.delete();
        }
        String url = "jdbc:sqlite:" + dataBase.getAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver is not available on the classpath", e);
        }
        return new SQLConnection(DriverManager.getConnection(url));
    }

    @Override
    public String getDBMSName() {
        return "sqlite3";
    }

    @Override
    public String getQueryPlan(String selectStr, SQLite3GlobalState globalState) throws Exception {
        String queryPlan = "";
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(selectStr);
            try {
                globalState.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // Set up the expected errors for NoREC oracle.
        ExpectedErrors errors = new ExpectedErrors();
        SQLite3Errors.addExpectedExpressionErrors(errors);
        SQLite3Errors.addMatchQueryErrors(errors);
        SQLite3Errors.addQueryErrors(errors);
        SQLite3Errors.addInsertUpdateErrors(errors);

        SQLQueryAdapter q = new SQLQueryAdapter(SQLite3ExplainGenerator.explain(selectStr), errors);
        try (SQLancerResultSet rs = q.executeAndGet(globalState)) {
            if (rs != null) {
                while (rs.next()) {
                    queryPlan += rs.getString(4) + ";";
                }
            }
        } catch (SQLException | AssertionError e) {
            queryPlan = "";
        }
        return queryPlan;
    }

    @Override
    protected double[] initializeWeightedAverageReward() {
        return new double[Action.values().length];
    }

    @Override
    protected void executeMutator(int index, SQLite3GlobalState globalState) throws Exception {
        SQLQueryAdapter queryMutateTable = Action.values()[index].getQuery(globalState);
        globalState.executeStatement(queryMutateTable);

    }

    @Override
    protected boolean addRowsToAllTables(SQLite3GlobalState globalState) throws Exception {
        List<SQLite3Table> tablesNoRow = globalState.getSchema().getDatabaseTables().stream()
                .filter(t -> t.getNrRows(globalState) == 0).collect(Collectors.toList());
        for (SQLite3Table table : tablesNoRow) {
            SQLQueryAdapter queryAddRows = SQLite3InsertGenerator.insertRow(globalState, table);
            globalState.executeStatement(queryAddRows);
        }

        return true;
    }
}
