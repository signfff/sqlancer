package sqlancer.common.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Reproducer;
import sqlancer.SQLGlobalState;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.common.schema.AbstractTable;

public class EGraphMetamorphicOracle<G extends SQLGlobalState<?, ?>> implements TestOracle<G> {

    public interface QueryGenerator<G extends SQLGlobalState<?, ?>> {
        GeneratedQuery generate(G globalState) throws Exception;
    }

    public static final class GeneratedQuery {
        private final String originalQuery;
        private final String rewriteQuery;
        private final Function<String, String> variantWrapper;
        private final String source;
        private final sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind truncation;

        public GeneratedQuery(String originalQuery, String rewriteQuery, Function<String, String> variantWrapper,
                String source) {
            this(originalQuery, rewriteQuery, variantWrapper, source,
                    sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.NONE);
        }

        /**
         * @param truncation
         *            how a LIMIT with no total order over it limits what can be compared. TRAILING leaves the row count
         *            comparable; NESTED leaves nothing comparable, because the rest of the statement is built on top of
         *            whichever rows the LIMIT happened to keep.
         */
        public GeneratedQuery(String originalQuery, String rewriteQuery, Function<String, String> variantWrapper,
                String source, sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind truncation) {
            this.originalQuery = originalQuery;
            this.rewriteQuery = rewriteQuery;
            this.variantWrapper = variantWrapper;
            this.source = source;
            this.truncation = truncation;
        }

        sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind truncation() {
            return truncation;
        }

        String getOriginalQuery() {
            return originalQuery;
        }

        String getRewriteQuery() {
            return rewriteQuery;
        }

        String wrapVariant(String variantQuery) {
            return variantWrapper.apply(variantQuery);
        }

        String getSource() {
            return source;
        }
    }

    private static final boolean MONITOR_ACTIVE = Boolean.getBoolean("egraph.monitor");
    private static final Path SINGLE_SIDE_EMPTY_LOG = Path.of(System.getProperty("egraph.singleSideEmptyLog",
            System.getProperty("egraph.variantOnlyEmptyLog", "egraph-single-side-empty-reproducers.sql")));
    private static int checkCounter;

    /**
     * How many checks in a row found the rewrite server unavailable, and when to stop waiting for it. A capture with no
     * server tests nothing, so the run is better ended than left reporting progress it is not making.
     */
    private static final java.util.concurrent.atomic.AtomicLong VARIANTS_UNAVAILABLE_IN_A_ROW = new java.util.concurrent.atomic.AtomicLong();
    private static final long VARIANTS_UNAVAILABLE_LIMIT = Long.getLong("egraph.variantServerFailAfter", 500);
    private static final long VARIANTS_UNAVAILABLE_WARN_EVERY = 50;

    private final G state;
    private final QueryGenerator<G> queryGenerator;
    private final ExpectedErrors errors;
    private final EGraphVariantGenerator variantGenerator;

    private Reproducer<G> reproducer;
    private String lastQueryString;
    /** Optimizer flags for the variants of a check; see EGraphOptimizerMask. One per oracle, i.e. per connection. */
    private final sqlancer.sqlite3.oracle.EGraphOptimizerMask optMask = new sqlancer.sqlite3.oracle.EGraphOptimizerMask();
    /** The mask the current check's variants run under, 0 for none; written into its reproducers. */
    private int currentMask;

    public EGraphMetamorphicOracle(G state, QueryGenerator<G> queryGenerator, ExpectedErrors errors,
            EGraphVariantGenerator variantGenerator) {
        if (state == null || queryGenerator == null || errors == null || variantGenerator == null) {
            throw new IllegalArgumentException("Null variables used to initialize EGraphMetamorphicOracle.");
        }
        this.state = state;
        this.queryGenerator = queryGenerator;
        this.errors = errors;
        this.variantGenerator = variantGenerator;
    }

    @Override
    public void check() throws Exception {
        long checkStartTime = System.currentTimeMillis();
        int checkNum = ++checkCounter;
        boolean show = MONITOR_ACTIVE && checkNum == 1;
        boolean skipped = false;

        try {
            reproducer = null;
            lastQueryString = null;

            if (show) {
                printSeparator('-', 64);
                System.err.printf("  EGRAPH ORACLE CHECK #%d%n", checkNum);
                printSeparator('-', 64);
                printPhase(1, 5, "DATABASE STATE");
                dumpDatabaseState();
            }

            long genStart = System.currentTimeMillis();
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " generate-query start");
            GeneratedQuery generatedQuery = queryGenerator.generate(state);
            long genTime = System.currentTimeMillis() - genStart;
            if (generatedQuery == null) {
                skipped = true;
                throw new IgnoreMeException();
            }
            String originalQuery = generatedQuery.getOriginalQuery();
            String rewriteQuery = generatedQuery.getRewriteQuery();
            String querySource = generatedQuery.getSource();
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " generate-query done source="
                    + querySource + " ms=" + genTime + " rewrite=" + formatQuery(rewriteQuery));
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordQuerySource(querySource);
            if (originalQuery == null || originalQuery.isBlank()) {
                skipped = true;
                throw new IgnoreMeException();
            }
            if (rewriteQuery == null || rewriteQuery.isBlank()) {
                rewriteQuery = originalQuery;
            }
            lastQueryString = originalQuery;

            // The original always runs under the default optimizations; the mask, if any, is set for
            // the variants only. In DQP mode the original is compared with itself under the mask, and
            // a check that gets no mask has nothing to compare.
            boolean dqp = sqlancer.sqlite3.oracle.EGraphOptimizerMask
                    .mode() == sqlancer.sqlite3.oracle.EGraphOptimizerMask.Mode.DQP;
            optMask.reset(state);
            currentMask = optMask.choose(state, originalQuery);
            if (dqp && currentMask == 0) {
                skipped = true;
                throw new IgnoreMeException();
            }
            if (currentMask != 0) {
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " optmask "
                        + sqlancer.sqlite3.oracle.EGraphOptimizerMask.describe(currentMask)
                        + (dqp ? " dqp" : " combined"));
            }

            sqlancer.sqlite3.oracle.EGraphSqlCoverage.beginPlanGroup();
            long origExecStart = System.currentTimeMillis();
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " original-exec start source="
                    + querySource + " sql=" + formatQuery(originalQuery));
            List<String> originalResult = getResultRows(originalQuery, errors, state);
            long origExecTime = System.currentTimeMillis() - origExecStart;
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " original-exec done source="
                    + querySource + " rows=" + originalResult.size() + " ms=" + origExecTime);
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordOriginalResult(querySource, originalQuery,
                    originalResult.size());

            if (show) {
                printPhase(2, 5, "ORIGINAL QUERY GENERATION");
                System.err.printf("  Generation time: %d ms%n", genTime);
                System.err.printf("  Query:%n    %s%n", formatQuery(originalQuery));
            }

            if (show) {
                printPhase(3, 5, "ORIGINAL QUERY EXECUTION");
                printRows(originalResult, origExecTime);
            }

            long variantStart = System.currentTimeMillis();
            List<String> variants;
            // Whether the original is compared with itself under the mask: always in DQP mode, and in
            // combined mode when the rewrite server has nothing for this query - a masked check still has
            // an optimizer path to test then (f9d7a71210 in the benchmark is caught no other way).
            boolean selfCompare = dqp;
            if (dqp) {
                variants = java.util.Collections.singletonList(originalQuery);
            } else {
                try {
                    sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " egraph-request start source="
                            + querySource + " rewrite=" + formatQuery(rewriteQuery));
                    variants = variantGenerator.generateVariants(rewriteQuery);
                    VARIANTS_UNAVAILABLE_IN_A_ROW.set(0);
                } catch (Exception e) {
                    if (isVariantGenerationUnavailable(e)) {
                        // Without the rewrite server a check has nothing to compare against, and this
                        // path used to skip quietly. A 24 hour capture lost 21 of its hours that way:
                        // the server was killed, every check after it was skipped, and the run kept
                        // reporting progress. Past a run of failures there is nothing to wait for.
                        long inARow = VARIANTS_UNAVAILABLE_IN_A_ROW.incrementAndGet();
                        if (inARow == 1 || inARow % VARIANTS_UNAVAILABLE_WARN_EVERY == 0) {
                            System.err.printf(
                                    "[EGRAPH] the rewrite server has refused %d checks in a row (%s). "
                                            + "Nothing is being tested while it is down.%n",
                                    inARow, e);
                        }
                        if (VARIANTS_UNAVAILABLE_LIMIT > 0 && inARow >= VARIANTS_UNAVAILABLE_LIMIT) {
                            System.err.printf(
                                    "[EGRAPH] giving up after %d checks without the rewrite server. "
                                            + "Raise -Degraph.variantServerFailAfter to wait longer, or 0 to wait forever.%n",
                                    inARow);
                            System.exit(3);
                        }
                        if (currentMask == 0) {
                            skipped = true;
                            throw new IgnoreMeException();
                        }
                        variants = java.util.Collections.singletonList(originalQuery);
                        selfCompare = true;
                    } else {
                        throw new AssertionError("EGRAPH variant generation failed: " + e, e);
                    }
                }
            }
            if ((variants == null || variants.isEmpty()) && currentMask != 0) {
                variants = java.util.Collections.singletonList(originalQuery);
                selfCompare = true;
            }
            if (selfCompare && !dqp) {
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " optmask self-compare fallback");
            }
            long variantTime = System.currentTimeMillis() - variantStart;
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " egraph-request done source="
                    + querySource + " variants=" + (variants == null ? 0 : variants.size()) + " ms=" + variantTime);

            if (show) {
                printPhase(4, 5, "EGRAPH VARIANT GENERATION");
                System.err.printf("  Generated: %d variants in %d ms%n",
                        variants == null ? 0 : variants.size(), variantTime);
            }

            if (variants == null || variants.isEmpty()) {
                skipped = true;
                throw new IgnoreMeException();
            }
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordVariantCheck(querySource);

            if (show) {
                printPhase(5, 5, "VARIANT EXECUTION AND COMPARISON");
                System.err.printf("  Reference: original query returned %d rows%n", originalResult.size());
            }

            boolean recordExample = sqlancer.sqlite3.oracle.EGraphExampleLogger.isEnabled();
            List<String> exampleVariantQueries = recordExample ? new ArrayList<>() : null;
            List<List<String>> exampleVariantResults = recordExample ? new ArrayList<>() : null;
            int testedCount = 0;
            int passedCount = 0;
            boolean corpusCaseRecorded = false;
            List<String> replayQueries = new ArrayList<>();
            replayQueries.add(originalQuery);
            optMask.apply(state, currentMask);
            for (int i = 0; i < variants.size(); i++) {
                String variantQuery = selfCompare ? originalQuery : generatedQuery.wrapVariant(variants.get(i));
                if (variantQuery == null || variantQuery.isBlank()
                        || (!selfCompare && variantQuery.equals(originalQuery))) {
                    continue;
                }

                testedCount++;
                long varExecStart = System.currentTimeMillis();
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " variant-exec start source="
                        + querySource + " variant#" + (i + 1) + " sql=" + formatQuery(variantQuery));
                List<String> variantResult = getResultRows(variantQuery, errors, state, originalQuery);
                long varExecTime = System.currentTimeMillis() - varExecStart;
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.trace("check#" + checkNum + " variant-exec done source="
                        + querySource + " variant#" + (i + 1) + " rows=" + variantResult.size() + " ms="
                        + varExecTime);
                replayQueries.add(variantQuery);
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordResultPair(querySource, originalQuery,
                        originalResult.size(), variantQuery, variantResult.size());
                if (recordExample) {
                    exampleVariantQueries.add(variantQuery);
                    exampleVariantResults.add(variantResult);
                }
                try {
                    if (hasSingleSideEmptyMismatch(originalResult, variantResult)) {
                        logSingleSideEmptyMismatch(state, querySource, rewriteQuery, originalQuery, variantQuery,
                                originalResult, variantResult, currentMask);
                        throw new AssertionError(String.format(
                                "EGRAPH single-side empty result mismatch! Original rows: %d, variant rows: %d.%nFirst query: \"%s\"%nSecond query: \"%s\"",
                                originalResult.size(), variantResult.size(), originalQuery, variantQuery));
                    }
                    if (generatedQuery
                            .truncation() == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.NESTED) {
                        // The statement is built on top of whichever rows an unordered LIMIT kept, so
                        // the count is as arbitrary as the rows and there is nothing here to compare.
                        // A two-hour run reported 4 rows against 5 for two spellings that match the
                        // same 13 rows once the LIMIT is removed, on both 3.53.4 and trunk.
                        sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordIncomparableTruncation();
                    } else if (generatedQuery
                            .truncation() == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.TRAILING) {
                        // The statement ends on the LIMIT, so it returns min(limit, matching rows) and
                        // two spellings that match the same rows agree on that number whichever ones
                        // they reached first. The rows themselves are still the plan's choice.
                        sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordCardinalityOnlyComparison();
                        if (originalResult.size() != variantResult.size()) {
                            logMismatch(originalResult, variantResult, originalQuery, variantQuery, state);
                            throw new AssertionError(String.format(
                                    "The size of the result sets mismatch (%d and %d)!%nFirst query: \"%s\"%nSecond query: \"%s\"",
                                    originalResult.size(), variantResult.size(), originalQuery, variantQuery));
                        }
                    } else {
                        assumeRowsAreEqual(originalResult, variantResult, originalQuery, variantQuery, state);
                    }
                    passedCount++;
                    if (show) {
                        System.err.printf("  [V%d] %3d rows  %3d ms  MATCH%n",
                                i + 1, variantResult.size(), varExecTime);
                    }
                } catch (AssertionError e) {
                    if (currentMask != 0) {
                        attributeMaskedFinding(generatedQuery.truncation(), originalQuery, variantQuery,
                                originalResult, variantResult);
                    }
                    // Ask the trunk build first when one is configured. It answers from behaviour -
                    // do these two queries agree on an engine carrying every fix since the release -
                    // where the signatures below only recognise the shape of a report someone
                    // already wrote down. A masked variant is replayed under the same mask.
                    sqlancer.sqlite3.oracle.EGraphTrunkReferee.Verdict verdict = sqlancer.sqlite3.oracle.EGraphTrunkReferee
                            .isConfigured()
                                    ? sqlancer.sqlite3.oracle.EGraphTrunkReferee.judge(currentDatabaseFile(state),
                                            originalQuery, variantQuery, currentMask)
                                    : null;
                    if (verdict == sqlancer.sqlite3.oracle.EGraphTrunkReferee.Verdict.FIXED_UPSTREAM
                            || verdict == sqlancer.sqlite3.oracle.EGraphTrunkReferee.Verdict.UNSTABLE_QUERY) {
                        String reason = verdict.name().toLowerCase(java.util.Locale.ROOT);
                        sqlancer.sqlite3.oracle.EGraphKnownBugs.record(reason, rewriteQuery, originalQuery,
                                variantQuery, originalResult.size(), variantResult.size());
                        if (show) {
                            System.err.printf("  [V%d] %3d rows  %3d ms  %s%n",
                                    i + 1, variantResult.size(), varExecTime, reason);
                        }
                        continue;
                    }
                    // A defect that is live in the shipped engine answers every check that reaches
                    // it, hundreds of times in a single run, and buries whatever else the run finds.
                    // Recognised ones are counted and logged rather than reported as a finding.
                    //
                    // Only when the referee had no answer. The signatures are regexes over the query
                    // text, so they also match a query that merely looks like an old report - and
                    // LIVE_UPSTREAM means the trunk build just disagreed on this very database, which
                    // is the strongest evidence a finding can have. Letting a regex overrule that
                    // turned confirmed live defects into a line in the known-bug log.
                    if (verdict != sqlancer.sqlite3.oracle.EGraphTrunkReferee.Verdict.LIVE_UPSTREAM) {
                        String knownBug = sqlancer.sqlite3.oracle.EGraphKnownBugs.recognise(rewriteQuery);
                        if (knownBug != null) {
                            sqlancer.sqlite3.oracle.EGraphKnownBugs.record(knownBug, rewriteQuery, originalQuery,
                                    variantQuery, originalResult.size(), variantResult.size());
                            if (show) {
                                System.err.printf("  [V%d] %3d rows  %3d ms  KNOWN BUG (%s)%n",
                                        i + 1, variantResult.size(), varExecTime, knownBug);
                            }
                            continue;
                        }
                    }
                    if (show) {
                        System.err.printf("  [V%d] %3d rows  %3d ms  MISMATCH%n",
                                i + 1, variantResult.size(), varExecTime);
                        printMismatch(originalResult, variantResult);
                    }
                    reproducer = new EGraphReproducer<>(originalQuery, variantQuery, errors);
                    state.getState().getLocalState()
                            .log(String.format("Original query: %s%nVariant query: %s", originalQuery, variantQuery));
                    if (!corpusCaseRecorded) {
                        sqlancer.sqlite3.oracle.EGraphCorpusCaseWriter.recordCase(state, rewriteQuery,
                                originalResult.size(), replayQueries,
                                generatedQuery
                                        .truncation() == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.NONE);
                        corpusCaseRecorded = true;
                    }
                    throw e;
                }
            }
            if (!corpusCaseRecorded && testedCount > 0) {
                sqlancer.sqlite3.oracle.EGraphCorpusCaseWriter.recordCase(state, rewriteQuery, originalResult.size(),
                        replayQueries,
                                generatedQuery
                                        .truncation() == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.NONE);
                corpusCaseRecorded = true;
            }
            if (recordExample && !exampleVariantQueries.isEmpty()) {
                sqlancer.sqlite3.oracle.EGraphExampleLogger.record(state, rewriteQuery, originalQuery, originalResult,
                        exampleVariantQueries, exampleVariantResults, querySource);
            }

            if (show) {
                long totalTime = System.currentTimeMillis() - checkStartTime;
                System.err.printf("  SUMMARY: %d variants tested, %d passed, %d failed%n",
                        testedCount, passedCount, testedCount - passedCount);
                System.err.printf("  Total time: %d ms%n", totalTime);
            }
        } catch (IgnoreMeException e) {
            skipped = true;
            throw e;
        } finally {
            // Whatever ended the check, the next statement - context setup of the next check included -
            // must run under the default optimizations again.
            if (currentMask != 0) {
                optMask.reset(state);
                currentMask = 0;
            }
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.endPlanGroup();
            if (show && !skipped) {
                System.err.println("[MONITOR] Complete check shown.");
            }
        }
    }

    /**
     * Says which side a finding under an optimizer mask needs, by re-running it with each factor alone on the same
     * database: the original under the mask (no rewriting) and the variant under the default optimizations (no mask).
     * Leaves the mask applied, as the variant loop expects.
     */
    private void attributeMaskedFinding(sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind truncation,
            String originalQuery, String variantQuery, List<String> originalResult, List<String> variantResult) {
        int mask = currentMask;
        List<String> originalMasked = null;
        List<String> variantDefault = null;
        try {
            originalMasked = getResultRows(originalQuery, errors, state);
        } catch (Exception | AssertionError ignored) {
            // Left unknown.
        }
        try {
            optMask.apply(state, 0);
            variantDefault = getResultRows(variantQuery, errors, state);
        } catch (Exception | AssertionError ignored) {
            // Left unknown.
        } finally {
            try {
                optMask.apply(state, mask);
            } catch (java.sql.SQLException e) {
                throw new AssertionError("could not restore the optimizer mask", e);
            }
        }
        sqlancer.sqlite3.oracle.EGraphOptimizerMask.Attribution attribution;
        if (originalMasked != null && !sameOutcome(truncation, originalResult, originalMasked)) {
            attribution = sqlancer.sqlite3.oracle.EGraphOptimizerMask.Attribution.OPTIMIZER;
        } else if (variantDefault != null && !sameOutcome(truncation, originalResult, variantDefault)) {
            attribution = sqlancer.sqlite3.oracle.EGraphOptimizerMask.Attribution.EGRAPH;
        } else if (originalMasked != null && variantDefault != null) {
            attribution = sqlancer.sqlite3.oracle.EGraphOptimizerMask.Attribution.COMBINED;
        } else {
            attribution = sqlancer.sqlite3.oracle.EGraphOptimizerMask.Attribution.UNKNOWN;
        }
        sqlancer.sqlite3.oracle.EGraphOptimizerMask.recordFinding(attribution, mask, originalQuery, variantQuery,
                originalResult.size(), originalMasked == null ? -1 : originalMasked.size(),
                variantDefault == null ? -1 : variantDefault.size(), variantResult.size());
        state.getState().getLocalState()
                .log(String.format("-- variant optimizer mask: SELECT sqlancer_optimizations(%s); -- %s; attribution %s",
                        Integer.toUnsignedString(mask), sqlancer.sqlite3.oracle.EGraphOptimizerMask.describe(mask),
                        attribution));
    }

    /** The comparison the variant loop makes, as a yes/no: whether two results of the same check agree. */
    static boolean sameOutcome(sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind truncation, List<String> a,
            List<String> b) {
        if (hasSingleSideEmptyMismatch(a, b)) {
            return false;
        }
        if (truncation == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.NESTED) {
            return true;
        }
        if (truncation == sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind.TRAILING) {
            return a.size() == b.size();
        }
        return a.size() == b.size() && resultRowsMatch(a, b);
    }

    private void dumpDatabaseState() {
        try {
            List<? extends AbstractTable<?, ?, ?>> tables = state.getSchema().getDatabaseTables();
            if (tables.isEmpty()) {
                System.err.println("  NO TABLES in database.");
                return;
            }

            int emptyCount = 0;
            for (AbstractTable<?, ?, ?> table : tables) {
                long rowCount = -1;
                try {
                    SQLQueryAdapter q = new SQLQueryAdapter("SELECT COUNT(*) FROM \"" + table.getName() + "\"");
                    try (SQLancerResultSet rs = q.executeAndGet(state)) {
                        if (rs != null && rs.next()) {
                            rowCount = rs.getLong(1);
                        }
                    }
                } catch (Exception ignored) {
                }
                if (rowCount == 0) {
                    emptyCount++;
                }
                System.err.printf("    - %s%s  [%s]%n", table.getName(), table.isView() ? " [VIEW]" : "",
                        rowCount < 0 ? "unknown rows" : rowCount + " rows");
            }
            if (emptyCount > 0) {
                System.err.println("  WARNING: Some tables are empty.");
            }
        } catch (Exception e) {
            System.err.printf("  Could not read schema: %s%n", e.getMessage());
        }
    }

    private static void printRows(List<String> rows, long executionTimeMillis) {
        System.err.printf("  Result rows: %d  (execution: %d ms)%n", rows.size(), executionTimeMillis);
        int limit = Math.min(rows.size(), 10);
        for (int i = 0; i < limit; i++) {
            System.err.printf("    row[%d] = %s%n", i, rows.get(i));
        }
        if (rows.size() > limit) {
            System.err.printf("    ... (%d more rows)%n", rows.size() - limit);
        }
    }

    private static void printMismatch(List<String> originalResult, List<String> variantResult) {
        int maxShow = Math.min(10, Math.max(originalResult.size(), variantResult.size()));
        for (int r = 0; r < maxShow; r++) {
            String orig = r < originalResult.size() ? originalResult.get(r) : "<missing>";
            String vari = r < variantResult.size() ? variantResult.get(r) : "<missing>";
            String mark = orig.equals(vari) ? " " : "!";
            System.err.printf("    row[%d] %s orig: %s%n", r, mark, orig);
            System.err.printf("           %s var:  %s%n", mark, vari);
        }
    }

    private static void printPhase(int num, int total, String title) {
        System.err.printf("  [%d/%d] %s%n", num, total, title);
        printSeparator('-', 64);
    }

    private static void printSeparator(char ch, int len) {
        for (int i = 0; i < len; i++) {
            System.err.print(ch);
        }
        System.err.println();
    }

    private static String formatQuery(String query) {
        if (query == null) {
            return "<null>";
        }
        if (query.length() > 200) {
            return query.substring(0, 197) + "...";
        }
        return query;
    }

    private static List<String> getResultRows(String queryString, ExpectedErrors errors, SQLGlobalState<?, ?> state)
            throws SQLException {
        return getResultRows(queryString, errors, state, null);
    }

    /**
     * A non-null originalQueryForTriage marks this execution as a variant whose original already ran
     * successfully. An expected error on that side means the rewrite changed whether the query can run
     * at all - information the original execution could not give us, and which was previously dropped
     * as noise. It is recorded rather than reported: a rewritten tree can be deeper or shaped so that
     * no index applies, making "expression tree is too large" / "no query solution" legitimate.
     */
    private static List<String> getResultRows(String queryString, ExpectedErrors errors, SQLGlobalState<?, ?> state,
            String originalQueryForTriage) throws SQLException {
        sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordExecution(queryString, state);
        SQLQueryAdapter q = new SQLQueryAdapter(queryString, errors, true, state.getOptions().canonicalizeSqlString());
        List<String> rows = new ArrayList<>();
        SQLancerResultSet result = null;
        try {
            result = q.executeAndGet(state);
            if (result == null) {
                if (originalQueryForTriage != null) {
                    String swallowed = q.getSwallowedError();
                    sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordVariantOnlyError(
                            "(expected error swallowed by SQLQueryAdapter) " + (swallowed == null ? "?" : swallowed),
                            originalQueryForTriage, queryString);
                }
                throw new IgnoreMeException();
            }
            if (state.getOptions().logEachSelect() && state.getOptions().egraphLogEachSelect()) {
                state.getLogger().writeCurrent(queryString);
            }
            int columnCount = result.getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(encodeResultValue(result.getString(i)));
                }
                rows.add(row.toString());
            }
        } catch (Exception e) {
            if (e instanceof IgnoreMeException) {
                throw e;
            }
            if (e.getMessage() != null && errors.errorIsExpected(e.getMessage())) {
                if (originalQueryForTriage != null) {
                    sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordVariantOnlyError(e.getMessage(),
                            originalQueryForTriage, queryString);
                }
                throw new IgnoreMeException();
            }
            throw new AssertionError(queryString, e);
        } finally {
            if (result != null && !result.isClosed()) {
                result.close();
            }
        }
        return rows;
    }

    private static void logSingleSideEmptyMismatch(SQLGlobalState<?, ?> state, String querySource, String rewriteQuery,
            String originalQuery, String variantQuery, List<String> originalRows, List<String> variantRows,
            int optimizerMask) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- EGRAPH_SINGLE_SIDE_EMPTY_BEGIN\n");
        sb.append("-- source: ").append(querySource == null ? "UNKNOWN" : querySource).append('\n');
        if (optimizerMask != 0) {
            // The variant ran after SELECT sqlancer_optimizations(mask); the original under the default.
            sb.append("-- variant_optimizer_mask: ")
                    .append(sqlancer.sqlite3.oracle.EGraphOptimizerMask.describe(optimizerMask)).append('\n');
        }
        sb.append("-- original_rows: ").append(originalRows.size()).append('\n');
        sb.append("-- variant_rows: ").append(variantRows.size()).append('\n');
        sb.append("-- base_query_sent_to_egraph:\n");
        appendSql(sb, rewriteQuery);
        sb.append("-- wrapped_original_query:\n");
        appendSql(sb, originalQuery);
        sb.append("-- wrapped_variant_query:\n");
        appendSql(sb, variantQuery);
        sb.append("-- original_row_sample:\n");
        int originalLimit = Math.min(10, originalRows.size());
        for (int i = 0; i < originalLimit; i++) {
            sb.append("-- row[").append(i).append("] = ").append(originalRows.get(i)).append('\n');
        }
        if (originalRows.size() > originalLimit) {
            sb.append("-- ... ").append(originalRows.size() - originalLimit).append(" more rows\n");
        }
        sb.append("-- variant_row_sample:\n");
        int variantLimit = Math.min(10, variantRows.size());
        for (int i = 0; i < variantLimit; i++) {
            sb.append("-- row[").append(i).append("] = ").append(variantRows.get(i)).append('\n');
        }
        if (variantRows.size() > variantLimit) {
            sb.append("-- ... ").append(variantRows.size() - variantLimit).append(" more rows\n");
        }
        sb.append("-- EGRAPH_SINGLE_SIDE_EMPTY_END\n\n");
        state.getState().getLocalState().log(sb.toString());
        try {
            Files.writeString(SINGLE_SIDE_EMPTY_LOG, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /**
     * The file SQLite3Provider.createDatabase opened for this run, so the referee can ask its questions of the same
     * data. Null when the state does not name a database, which leaves the referee with no verdict.
     */
    private static java.io.File currentDatabaseFile(SQLGlobalState<?, ?> state) {
        if (state == null || state.getDatabaseName() == null) {
            return null;
        }
        return new java.io.File("." + java.io.File.separator + "databases",
                state.getDatabaseName() + ".db");
    }

    private static void appendSql(StringBuilder sb, String sql) {
        sb.append(sql == null ? "" : sql.strip());
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != ';') {
            sb.append(';');
        }
        sb.append('\n');
    }

    private static boolean isVariantGenerationUnavailable(Throwable t) {
        while (t != null) {
            String className = t.getClass().getName();
            String message = t.getMessage();
            if ("java.net.ConnectException".equals(className) || "java.net.SocketTimeoutException".equals(className)) {
                return true;
            }
            if (message != null && (message.contains("Connection refused") || message.contains("timed out")
                    || message.contains("HTTP request failed"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Encodes one result value so two result sets can be compared as text.
     *
     * <p>
     * The trailing ".0" used to be stripped here, which made the integer 1 and the float 1.0 the same value. Both sides
     * of a check project the same expressions - only the rows selected differ - so a rewrite has no business changing a
     * value's storage class, and when one does that is worth seeing rather than hiding. Such a difference is counted
     * separately (see {@link #resultRowsMatch(List, List, String, String)}) so a run says how often it happens.
     * </p>
     */
    static String encodeResultValue(String value) {
        if (value == null) {
            return "N";
        }
        String canonicalized = ComparatorHelper.canonicalizeResultValue(value);
        return "S" + canonicalized.length() + ":" + canonicalized;
    }

    /**
     * The same encoded row with integer and float spellings of one number folded together, for deciding whether a
     * mismatch is only about storage class.
     */
    static String foldNumericSpelling(String encodedRow) {
        StringBuilder folded = new StringBuilder(encodedRow.length());
        int at = 0;
        while (at < encodedRow.length()) {
            if (at > 0) {
                if (encodedRow.charAt(at) != '|') {
                    return encodedRow; // not an encoding this method understands; leave it alone
                }
                folded.append('|');
                at++;
            }
            if (encodedRow.startsWith("N", at)) {
                folded.append('N');
                at++;
                continue;
            }
            int colon = encodedRow.indexOf(':', at);
            if (!encodedRow.startsWith("S", at) || colon < 0) {
                return encodedRow;
            }
            int length;
            try {
                length = Integer.parseInt(encodedRow.substring(at + 1, colon));
            } catch (NumberFormatException e) {
                return encodedRow;
            }
            int end = colon + 1 + length;
            if (end > encodedRow.length()) {
                return encodedRow;
            }
            String value = encodedRow.substring(colon + 1, end).replaceAll("[\\.]0+$", "");
            folded.append('S').append(value.length()).append(':').append(value);
            at = end;
        }
        return folded.toString();
    }

    static void assumeRowsAreEqual(List<String> originalRows, List<String> variantRows,
            String originalQueryString, String variantQueryString, SQLGlobalState<?, ?> state) {
        if (originalRows.size() != variantRows.size()) {
            logMismatch(originalRows, variantRows, originalQueryString, variantQueryString, state);
            throw new AssertionError(String.format(
                    "The size of the result sets mismatch (%d and %d)!%nFirst query: \"%s\"%nSecond query: \"%s\"",
                    originalRows.size(), variantRows.size(), originalQueryString, variantQueryString));
        }

        if (!state.getOptions().validateResultSizeOnly()
                && !resultRowsMatch(originalRows, variantRows, originalQueryString, variantQueryString)) {
            logMismatch(originalRows, variantRows, originalQueryString, variantQueryString, state);
            throw new AssertionError(String.format("The content of the result sets mismatch!%nFirst query: \"%s\"%n"
                    + "Second query: \"%s\"", originalQueryString, variantQueryString));
        }
    }

    static boolean hasSingleSideEmptyMismatch(List<String> originalRows, List<String> variantRows) {
        return originalRows.isEmpty() != variantRows.isEmpty();
    }

    static boolean resultRowsMatch(List<String> originalRows, List<String> variantRows) {
        return toMultiset(originalRows).equals(toMultiset(variantRows));
    }

    /**
     * Compares the rows as a multiset, whatever the query says about order.
     *
     * <p>
     * This used to compare them in order whenever the text mentioned ORDER BY anywhere, which was wrong in both
     * directions. An ORDER BY inside a wrapper subquery does not order the query around it, and an ORDER BY on a column
     * with ties does not fix the order of the tied rows - so the comparison reported differences SQL never promised.
     * Measured right after findings stopped being swallowed: of the first 8 that surfaced, 6 were this. In the other
     * direction, two spellings could return the same rows in a different order and that went unrecorded.
     * </p>
     *
     * <p>
     * An order-only difference is now counted and sampled instead. That keeps it visible without spending a run's
     * attention on a difference the standard permits; catching a genuine ordering defect needs an oracle that knows the
     * sort key is a total order, which this one does not.
     * </p>
     */
    static boolean resultRowsMatch(List<String> originalRows, List<String> variantRows,
            String originalQueryString, String variantQueryString) {
        boolean sameMultiset = resultRowsMatch(originalRows, variantRows);
        if (sameMultiset) {
            if (!originalRows.equals(variantRows)) {
                sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordOrderOnlyDifference(originalQueryString,
                        variantQueryString);
            }
            return true;
        }
        // Still reported as a mismatch - it is one - but counted apart, so a run can say whether
        // storage class alone ever separates two spellings, which decides whether the old blanket
        // ".0" stripping was hiding anything.
        if (toMultiset(foldNumericSpelling(originalRows)).equals(toMultiset(foldNumericSpelling(variantRows)))) {
            sqlancer.sqlite3.oracle.EGraphSqlCoverage.recordNumericSpellingDifference(originalQueryString,
                    variantQueryString);
        }
        return false;
    }

    private static List<String> foldNumericSpelling(List<String> rows) {
        List<String> folded = new java.util.ArrayList<>(rows.size());
        for (String row : rows) {
            folded.add(foldNumericSpelling(row));
        }
        return folded;
    }

    static Map<String, Integer> toMultiset(List<String> rows) {
        Map<String, Integer> result = new HashMap<>();
        for (String row : rows) {
            result.merge(row, 1, Integer::sum);
        }
        return result;
    }

    private static void logMismatch(List<String> originalRows, List<String> variantRows,
            String originalQueryString, String variantQueryString, SQLGlobalState<?, ?> state) {
        state.getState().getLocalState().log(String.format("-- Query: \"%s\"; rows: %s%n"
                + "-- Query: \"%s\"; rows: %s", originalQueryString, originalRows, variantQueryString, variantRows));
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

    @Override
    public Reproducer<G> getLastReproducer() {
        return reproducer;
    }

    private static final class EGraphReproducer<G extends SQLGlobalState<?, ?>> implements Reproducer<G> {

        private final String originalQuery;
        private final String variantQuery;
        private final ExpectedErrors errors;

        private EGraphReproducer(String originalQuery, String variantQuery, ExpectedErrors errors) {
            this.originalQuery = originalQuery;
            this.variantQuery = variantQuery;
            this.errors = errors;
        }

        @Override
        public boolean bugStillTriggers(G globalState) {
            try {
                List<String> originalResult = getResultRows(originalQuery, errors, globalState);
                List<String> variantResult = getResultRows(variantQuery, errors, globalState);
                try {
                    assumeRowsAreEqual(originalResult, variantResult, originalQuery, variantQuery, globalState);
                    return false;
                } catch (AssertionError e) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
    }
}
