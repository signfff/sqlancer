package sqlancer.sqlite3.oracle;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.SQLGlobalState;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;

/**
 * Tracks SQL workload diversity hints for EGRAPH oracle checks. This is not SQLite source-code coverage; real code
 * coverage still has to be collected from an instrumented SQLite build.
 */
public class EGraphSqlCoverage {

    public enum SqlFeature {
        LOGICAL_AND,
        LOGICAL_OR,
        LOGICAL_NOT,
        CMP_EQUALS,
        CMP_NOT_EQUALS,
        CMP_LESS,
        CMP_GREATER,
        CMP_LESS_EQ,
        CMP_GREATER_EQ,
        ARITH_PLUS,
        ARITH_MINUS,
        ARITH_MULTIPLY,
        ARITH_DIVIDE,
        ARITH_NEGATE,
        IS_NULL,
        IS_NOT_NULL,
        BETWEEN,
        COLUMN_REF,
    }

    public enum ExecutionFeature {
        TABLE_SCAN,
        INDEX_SEARCH,
        COVERING_INDEX,
        AUTOMATIC_INDEX,
        TEMP_BTREE,
        ORDER_BY,
        GROUP_BY,
        DISTINCT,
        SUBQUERY,
        CORRELATED_SUBQUERY,
        COMPOUND_QUERY,
        CO_ROUTINE,
        MATERIALIZE,
        MULTI_INDEX_OR,
    }

    static final Map<SqlFeature, AtomicInteger> hits = new EnumMap<>(SqlFeature.class);
    static final Map<ExecutionFeature, AtomicInteger> executionHits = new EnumMap<>(ExecutionFeature.class);
    static final AtomicInteger totalChecks = new AtomicInteger(0);
    static final AtomicInteger variantChecks = new AtomicInteger(0);
    static final AtomicInteger executedQueries = new AtomicInteger(0);
    static final AtomicInteger explainedQueries = new AtomicInteger(0);
    static final AtomicInteger explainFailures = new AtomicInteger(0);
    static final AtomicInteger originalResultChecks = new AtomicInteger(0);
    // Attempts thrown away because the generated base query matched no row. These
    // never reach recordCheck(), so without their own counters the data generator
    // looks perfect no matter how often it fails to hit the predicate.
    // How often wrapping a base query that was just probed non-empty produces no
    // rows. When that happens the oracle falls back to the unwrapped query, so the
    // coverage shape is silently lost for that check.
    static final Map<String, AtomicInteger> originalWrapperApplied = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> originalWrapperEmptied = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> originalWrapperErrored = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> probeErrorMessages = new ConcurrentHashMap<>();
    static final AtomicInteger satisfiabilityRejects = new AtomicInteger(0);
    static final AtomicInteger satisfiabilityAccepts = new AtomicInteger(0);
    static final AtomicInteger baseProbeTotal = new AtomicInteger(0);
    static final AtomicInteger baseProbeEmpty = new AtomicInteger(0);

    // An equivalent rewrite should not change whether the query can run at all. When the variant
    // raises an error the original did not, the check is currently abandoned as noise. Counted here
    // for triage rather than reported: a rewritten tree can be deeper than the original, so
    // "expression tree is too large" / "no query solution" are legitimate, not defects.
    static final Map<String, AtomicInteger> variantOnlyErrors = new ConcurrentHashMap<>();
    static final AtomicInteger variantOnlyErrorTotal = new AtomicInteger(0);

    /**
     * Two spellings that returned the same rows in a different order. SQL only fixes an order where an ORDER BY says
     * so, and not even then when the sort key has ties, so this is not a finding - but it is worth seeing, since a
     * genuine ordering defect would look exactly like this and needs an oracle that knows the key is a total order.
     */
    static final AtomicInteger orderOnlyDifferences = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<String> orderOnlySamples = new ConcurrentLinkedQueue<>();

    /**
     * Two spellings whose rows agree once the integer and float spellings of a number are folded together. This IS
     * reported as a finding - both sides project the same expressions, so a rewrite should not move a value between
     * storage classes - but it is counted apart, because the encoder used to fold the two silently and a run needs to
     * say whether that was hiding anything.
     */
    static final AtomicInteger numericSpellingDifferences = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<String> numericSpellingSamples = new ConcurrentLinkedQueue<>();

    /**
     * A spelling-only difference that the wrapper explains: the unwrapped pair of queries returns the
     * same rows, so each side merely kept a different representative of rows the engine considers
     * equal (a bare GROUP BY column or DISTINCT keeps whichever of them it saw first, which the
     * documentation calls an arbitrary choice). NOT a finding, counted here rather than above.
     */
    static final AtomicInteger representativeChoiceDifferences = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<String> representativeChoiceSamples = new ConcurrentLinkedQueue<>();

    /**
     * Comparisons weakened to the row count alone, because the query truncates its rows without saying which ones. A
     * large share here means the oracle is mostly counting rows rather than checking them.
     */
    static final AtomicInteger cardinalityOnlyComparisons = new AtomicInteger(0);

    /**
     * Comparisons abandoned because the statement is built on top of whichever rows an unordered LIMIT kept, which makes
     * the row count as arbitrary as the rows. A large number here is a corpus written before the base queries carried a
     * total order over their LIMIT, not a defect.
     */
    static final AtomicInteger incomparableTruncations = new AtomicInteger(0);

    // Checks that ran with an empty original result on purpose (see EMPTY_BASE_CHECK_PERCENT).
    static final AtomicInteger emptyBaseChecks = new AtomicInteger(0);

    // Which kind of table the base query targeted, and how many of those survived the non-empty
    // probe. Both halves are needed: a kind that is picked often but never survives is a different
    // problem from one that is never picked.
    static final Map<String, AtomicInteger> targetTableKinds = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> targetTableKindUsable = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> LAST_TARGET_KIND = new ThreadLocal<>();
    /**
     * Label for a check that never recorded a target table kind, which is every check replayed from
     * a corpus: those take the createCorpusGeneratedQuery path and never choose a target table.
     * Without it the plan histogram attributed them to whichever arm the last generated check
     * happened to use, and since corpora supply roughly 70% of a long run's checks, both arms were
     * mostly made of queries that never belonged to either.
     */
    private static final ThreadLocal<String> LAST_QUERY_SOURCE = new ThreadLocal<>();

    // How many DISTINCT execution plans the original plus its variants produced within one check.
    // This is the direct measure of whether a rewrite is worth anything: if every variant of a
    // predicate compiles to the same plan, the two sides run the same code and the comparison
    // cannot fail no matter how many variants there are. Bucketed by target table kind because the
    // whole point of allowing R-Tree targets is the claim that its plans do NOT collapse the way a
    // plain table's do.
    private static final ThreadLocal<java.util.Set<String>> PLAN_GROUP = new ThreadLocal<>();
    static final Map<String, AtomicInteger> planDistinctHistogram = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> baseProbeEmptyFeatures = new ConcurrentHashMap<>();
    static final AtomicInteger originalEmptyResults = new AtomicInteger(0);
    static final AtomicInteger originalNonEmptyResults = new AtomicInteger(0);
    static final AtomicInteger comparedVariantPairs = new AtomicInteger(0);
    static final AtomicInteger originalEmptyResultPairs = new AtomicInteger(0);
    static final AtomicInteger variantEmptyResultPairs = new AtomicInteger(0);
    static final AtomicInteger bothEmptyResultPairs = new AtomicInteger(0);
    static final AtomicInteger bothNonEmptyResultPairs = new AtomicInteger(0);
    static final Map<String, AtomicInteger> wrapperShapeHits = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> querySourceHits = new ConcurrentHashMap<>();
    static final Map<String, SourceStats> sourceStats = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> corpusFilterSkips = new ConcurrentHashMap<>();
    private static final int MAX_EMPTY_QUERY_SAMPLES = Integer.getInteger("egraph.coverage.emptySamples", 20);
    private static final int REPORT_FLUSH_SECONDS = Integer.getInteger("egraph.coverage.flushSeconds", 30);
    private static final ConcurrentLinkedQueue<String> emptyOriginalSamples = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> bothEmptyPairSamples = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> emptyOriginalPairSamples = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> emptyVariantPairSamples = new ConcurrentLinkedQueue<>();
    private static final ScheduledExecutorService REPORT_WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "egraph-workload-hints-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final String REPORT_PATH = System.getProperty("egraph.coverage.file",
            String.format("egraph-workload-hints-%d-%d.txt", ProcessHandle.current().pid(),
                    System.currentTimeMillis()));
    private static final String TRACE_PATH = System.getProperty("egraph.trace.file", "");

    static {
        for (SqlFeature f : SqlFeature.values()) {
            hits.put(f, new AtomicInteger(0));
        }
        for (ExecutionFeature f : ExecutionFeature.values()) {
            executionHits.put(f, new AtomicInteger(0));
        }
        if (REPORT_FLUSH_SECONDS > 0) {
            REPORT_WRITER.scheduleAtFixedRate(EGraphSqlCoverage::writeReportSafely, REPORT_FLUSH_SECONDS,
                    REPORT_FLUSH_SECONDS, TimeUnit.SECONDS);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            writeReportSafely();
            REPORT_WRITER.shutdownNow();
        }));
    }

    /**
     * Records the outcome of the non-empty probe on a freshly generated base query. When the probe fails, the WHERE
     * clause features are tallied separately so the report shows which predicate kinds the data generator cannot
     * satisfy.
     */
    /**
     * Records whether the wrapper kept the rows of a base query that the non-empty probe had already accepted. A
     * wrapper that empties the result costs the check its coverage shape, because the caller then replays the
     * unwrapped query instead.
     */
    public static void recordOriginalWrapper(String shape, String outcome) {
        recordOriginalWrapper(shape, outcome, null);
    }

    public static void recordOriginalWrapper(String shape, String outcome, String sql) {
        String key = shape == null ? "UNKNOWN" : shape;
        originalWrapperApplied.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String samplePath = System.getProperty("egraph.wrapperEmptied.log");
        if (samplePath != null && !samplePath.isBlank() && sql != null && !"ROWS".equals(outcome)) {
            synchronized (EGraphSqlCoverage.class) {
                try (FileWriter writer = new FileWriter(samplePath, true)) {
                    writer.write("-- " + key + " -> " + outcome + System.lineSeparator());
                    writer.write(sql);
                    writer.write(System.lineSeparator());
                } catch (IOException ignored) {
                }
            }
        }
        if ("EMPTY".equals(outcome)) {
            originalWrapperEmptied.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        } else if ("ERROR".equals(outcome) || "EXPECTED_ERROR".equals(outcome)) {
            originalWrapperErrored.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if ("EXPECTED_ERROR".equals(outcome)) {
                probeErrorMessages.computeIfAbsent("(expected error swallowed by SQLQueryAdapter)",
                        k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /** Groups probe failures by a normalized message so the report names the actual cause. */
    public static void recordProbeError(String message, String sql) {
        String key = message == null ? "unknown" : message.replaceAll("[0-9]+", "N").trim();
        if (key.length() > 90) {
            key = key.substring(0, 90);
        }
        probeErrorMessages.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String samplePath = System.getProperty("egraph.wrapperError.log");
        if (samplePath != null && !samplePath.isBlank() && sql != null) {
            synchronized (EGraphSqlCoverage.class) {
                try (FileWriter writer = new FileWriter(samplePath, true)) {
                    writer.write("-- " + key + System.lineSeparator());
                    writer.write(sql);
                    writer.write(System.lineSeparator());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Two spellings that returned the same rows in a different order. Not a finding; see the field. */
    public static void recordOrderOnlyDifference(String originalSql, String variantSql) {
        orderOnlyDifferences.incrementAndGet();
        if (orderOnlySamples.size() < MAX_EMPTY_QUERY_SAMPLES) {
            orderOnlySamples.add(compactSql(originalSql) + "  ||  " + compactSql(variantSql));
        }
    }

    /** A comparison abandoned because nothing about the result is determined. See the field. */
    public static void recordIncomparableTruncation() {
        incomparableTruncations.incrementAndGet();
    }

    /** A comparison that could only look at the row count. See the field. */
    public static void recordCardinalityOnlyComparison() {
        cardinalityOnlyComparisons.incrementAndGet();
    }

    /** Two spellings whose rows differ only in how a number is spelled. Reported as a finding, counted here. */
    public static void recordNumericSpellingDifference(String originalSql, String variantSql) {
        numericSpellingDifferences.incrementAndGet();
        if (numericSpellingSamples.size() < MAX_EMPTY_QUERY_SAMPLES) {
            numericSpellingSamples.add(compactSql(originalSql) + "  ||  " + compactSql(variantSql));
        }
    }

    /**
     * A spelling-only difference the wrapper explains: the unwrapped queries agree, so the wrapper
     * kept a different representative of equal rows. Not a finding; see the field.
     */
    public static void recordRepresentativeChoiceDifference(String originalSql, String variantSql) {
        representativeChoiceDifferences.incrementAndGet();
        if (representativeChoiceSamples.size() < MAX_EMPTY_QUERY_SAMPLES) {
            representativeChoiceSamples.add(compactSql(originalSql) + "  ||  " + compactSql(variantSql));
        }
    }

    /**
     * Records an error raised by a variant whose original ran fine. Grouped by normalized message so
     * the report names the cause; the pair is appended to egraph.variantOnlyError.log when that
     * property is set, so the cases can be replayed and triaged by hand.
     */
    public static void recordVariantOnlyError(String message, String originalSql, String variantSql) {
        // 200, not 90: a swallowed error's key carries two prefixes before its reason, and at 90 the
        // reason ("malformed JSON", "no such column") was cut off.
        String key = message == null ? "unknown" : message.replaceAll("[0-9]+", "N").replaceAll("\\s+", " ").trim();
        if (key.length() > 200) {
            key = key.substring(0, 200);
        }
        variantOnlyErrorTotal.incrementAndGet();
        variantOnlyErrors.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String samplePath = System.getProperty("egraph.variantOnlyError.log");
        if (samplePath != null && !samplePath.isBlank()) {
            synchronized (EGraphSqlCoverage.class) {
                try (FileWriter writer = new FileWriter(samplePath, true)) {
                    writer.write("-- EGRAPH_VARIANT_ONLY_ERROR: " + key + System.lineSeparator());
                    writer.write("-- original: " + originalSql + System.lineSeparator());
                    writer.write("-- variant : " + variantSql + System.lineSeparator());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Starts collecting execution plans for one check (original + its variants). */
    public static void beginPlanGroup() {
        PLAN_GROUP.set(new java.util.LinkedHashSet<>());
    }

    /** Files the distinct-plan count for the check that just finished. */
    public static void endPlanGroup() {
        java.util.Set<String> plans = PLAN_GROUP.get();
        PLAN_GROUP.remove();
        if (plans == null || plans.isEmpty()) {
            return;
        }
        String kind = LAST_TARGET_KIND.get();
        // Cleared per check, so the next one cannot inherit this label.
        LAST_TARGET_KIND.remove();
        if (kind == null) {
            kind = LAST_QUERY_SOURCE.get();
        }
        String bucket = (kind == null ? "UNKNOWN" : kind) + " / " + bucketOf(plans.size());
        planDistinctHistogram.computeIfAbsent(bucket, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String bucketOf(int distinct) {
        if (distinct <= 1) {
            return "1 plan (rewrite changed nothing)";
        }
        if (distinct == 2) {
            return "2 plans";
        }
        if (distinct <= 4) {
            return "3-4 plans";
        }
        if (distinct <= 8) {
            return "5-8 plans";
        }
        return "9+ plans";
    }

    public static void recordTargetTableKind(String kind) {
        LAST_TARGET_KIND.set(kind);
        targetTableKinds.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    public static void recordEmptyBaseCheck() {
        emptyBaseChecks.incrementAndGet();
    }

    public static void recordSatisfiability(boolean accepted) {
        if (accepted) {
            satisfiabilityAccepts.incrementAndGet();
        } else {
            satisfiabilityRejects.incrementAndGet();
        }
    }

    public static void recordBaseProbe(String sql, boolean nonEmpty) {
        baseProbeTotal.incrementAndGet();
        if (nonEmpty) {
            String kind = LAST_TARGET_KIND.get();
            if (kind != null) {
                targetTableKindUsable.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            }
            return;
        }
        baseProbeEmpty.incrementAndGet();
        String samplePath = System.getProperty("egraph.emptyBase.log");
        if (samplePath != null && !samplePath.isBlank()) {
            // Diagnostic sample of the queries the data generator could not satisfy.
            synchronized (EGraphSqlCoverage.class) {
                try (FileWriter writer = new FileWriter(samplePath, true)) {
                    writer.write(sql);
                    writer.write(System.lineSeparator());
                } catch (IOException ignored) {
                }
            }
        }
        for (String feature : whereFeatures(sql)) {
            baseProbeEmptyFeatures.computeIfAbsent(feature, key -> new AtomicInteger()).incrementAndGet();
        }
    }

    public static void recordCheck() {
        totalChecks.incrementAndGet();
    }

    public static void trace(String message) {
        if (TRACE_PATH == null || TRACE_PATH.isBlank()) {
            return;
        }
        String line = String.format("%d [%s] %s", System.currentTimeMillis(), Thread.currentThread().getName(),
                message == null ? "" : message);
        synchronized (EGraphSqlCoverage.class) {
            try (PrintWriter out = new PrintWriter(new FileWriter(TRACE_PATH, true))) {
                out.println(line);
            } catch (IOException ignored) {
            }
        }
    }

    public static void recordVariantCheck() {
        variantChecks.incrementAndGet();
    }

    public static void recordWrapperShape(String shapeName) {
        if (shapeName == null || shapeName.isBlank()) {
            return;
        }
        wrapperShapeHits.computeIfAbsent(shapeName, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static void recordQuerySource(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return;
        }
        LAST_QUERY_SOURCE.set(sourceName);
        querySourceHits.computeIfAbsent(sourceName, ignored -> new AtomicInteger()).incrementAndGet();
        statsFor(sourceName);
    }

    public static void recordCorpusFilterSkip(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        corpusFilterSkips.computeIfAbsent(reason, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Whether every original and variant is preceded by an EXPLAIN QUERY PLAN, for the execution-feature and
     * multi-plan statistics. Off by default: it is up to 17 extra statements per check and nothing in the comparison
     * reads it. -Degraph.explain=true for A/B work that measures plans.
     */
    private static final boolean EXPLAIN = Boolean.getBoolean("egraph.explain");

    public static void recordExecution(String sql, SQLGlobalState<?, ?> state) {
        executedQueries.incrementAndGet();
        if (!EXPLAIN) {
            return;
        }
        if (sql == null || sql.isBlank() || state == null) {
            explainFailures.incrementAndGet();
            return;
        }

        SQLancerResultSet rs = null;
        try {
            SQLQueryAdapter explain = new SQLQueryAdapter("EXPLAIN QUERY PLAN " + stripTrailingSemicolon(sql), false);
            rs = explain.executeAndGet(state, false);
            if (rs == null) {
                explainFailures.incrementAndGet();
                return;
            }
            explainedQueries.incrementAndGet();
            EnumSet<ExecutionFeature> queryFeatures = EnumSet.noneOf(ExecutionFeature.class);
            java.util.Set<String> planGroup = PLAN_GROUP.get();
            StringBuilder planText = planGroup == null ? null : new StringBuilder();
            while (rs.next()) {
                String detail = rs.getString(4);
                analyzeExecutionPlanDetail(detail, queryFeatures);
                if (planText != null) {
                    // EXPLAIN QUERY PLAN already renders constants as '?', so the detail column is
                    // literal-free and can be compared across variants as-is.
                    planText.append(detail).append('|');
                }
            }
            if (planGroup != null && planText != null) {
                planGroup.add(planText.toString());
            }
            for (ExecutionFeature feature : queryFeatures) {
                executionHits.get(feature).incrementAndGet();
            }
        } catch (Exception e) {
            explainFailures.incrementAndGet();
        } finally {
            if (rs != null) {
                try {
                    if (!rs.isClosed()) {
                        rs.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static void recordOriginalResult(String sql, int rowCount) {
        recordOriginalResult(null, sql, rowCount);
    }

    public static void recordOriginalResult(String sourceName, String sql, int rowCount) {
        originalResultChecks.incrementAndGet();
        SourceStats stats = statsFor(sourceName);
        if (stats != null) {
            stats.originalChecks.incrementAndGet();
        }
        if (rowCount == 0) {
            originalEmptyResults.incrementAndGet();
            if (stats != null) {
                stats.originalEmpty.incrementAndGet();
            }
            addSample(emptyOriginalSamples, sql);
        } else {
            originalNonEmptyResults.incrementAndGet();
            if (stats != null) {
                stats.originalNonEmpty.incrementAndGet();
            }
        }
    }

    public static void recordResultPair(String originalSql, int originalRows, String variantSql, int variantRows) {
        recordResultPair(null, originalSql, originalRows, variantSql, variantRows);
    }

    public static void recordResultPair(String sourceName, String originalSql, int originalRows, String variantSql,
            int variantRows) {
        comparedVariantPairs.incrementAndGet();
        SourceStats stats = statsFor(sourceName);
        if (stats != null) {
            stats.comparedPairs.incrementAndGet();
        }
        boolean originalEmpty = originalRows == 0;
        boolean variantEmpty = variantRows == 0;

        if (originalEmpty) {
            originalEmptyResultPairs.incrementAndGet();
            if (stats != null) {
                stats.originalEmptyPairs.incrementAndGet();
            }
        }
        if (variantEmpty) {
            variantEmptyResultPairs.incrementAndGet();
            if (stats != null) {
                stats.variantEmptyPairs.incrementAndGet();
            }
        }
        if (originalEmpty && variantEmpty) {
            bothEmptyResultPairs.incrementAndGet();
            if (stats != null) {
                stats.bothEmptyPairs.incrementAndGet();
            }
            addSample(bothEmptyPairSamples, "original: " + originalSql + " | variant: " + variantSql);
        } else if (!originalEmpty && !variantEmpty) {
            bothNonEmptyResultPairs.incrementAndGet();
            if (stats != null) {
                stats.bothNonEmptyPairs.incrementAndGet();
            }
        } else if (originalEmpty) {
            addSample(emptyOriginalPairSamples,
                    "original rows=0: " + originalSql + " | variant rows=" + variantRows + ": " + variantSql);
        } else if (variantEmpty) {
            addSample(emptyVariantPairSamples,
                    "original rows=" + originalRows + ": " + originalSql + " | variant: " + variantSql);
        }
    }

    public static void recordVariantCheck(String sourceName) {
        recordVariantCheck();
        SourceStats stats = statsFor(sourceName);
        if (stats != null) {
            stats.variantChecks.incrementAndGet();
        }
    }

    /**
     * The WHERE constructs that matter when diagnosing why the data generator failed to produce a matching row. Kept
     * separate from {@link SqlFeature} on purpose: this list names the predicate kinds the generator either inverts or
     * cannot invert, so the report points straight at the gap. Plain substring matching on the lowercased clause is
     * enough here and avoids a wall of regex escapes.
     */
    private static final String[][] DATA_GEN_FEATURES = {
            { "IN_LIST", " in (", " in(" },
            { "LIKE", " like " },
            { "GLOB", " glob " },
            { "REGEXP_MATCH", " regexp ", " match " },
            { "CAST", "cast(" },
            { "COLLATE", " collate " },
            { "CASE", "case " },
            { "TYPEOF", "typeof(" },
            { "LENGTH", "length(" },
            { "ABS", "abs(" },
            { "SUBSTR", "substr(" },
            { "UPPER_LOWER", "upper(", "lower(" },
            { "TRIM", "trim(" },
            { "COALESCE_IFNULL", "coalesce(", "ifnull(", "nullif(" },
            { "HEX_QUOTE", "hex(", "quote(" },
            { "ROUND_MATH", "round(", "ceil(", "floor(", "pow(", "log(", "sqrt(", "mod(" },
            { "REPLACE_INSTR", "replace(", "instr(" },
            { "BITWISE", "&", "|", "<<", ">>" },
            { "CONCAT", "||" },
            { "BLOB_LITERAL", "x'" },
            { "SUBQUERY", "select " },
            { "NULL_LITERAL", "null" },
    };

    private static java.util.List<String> whereFeatures(String sql) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return result;
        }
        String whereSql = extractWhere(sql);
        if (whereSql.isEmpty()) {
            return result;
        }
        String needle = " " + whereSql.toLowerCase(java.util.Locale.ROOT) + " ";
        for (String[] feature : DATA_GEN_FEATURES) {
            for (int i = 1; i < feature.length; i++) {
                if (needle.contains(feature[i])) {
                    result.add(feature[0]);
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            result.add("PLAIN_CMP_ONLY");
        }
        return result;
    }

    public static void analyzeWhere(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }

        String whereSql = extractWhere(sql);
        if (whereSql.isEmpty()) {
            return;
        }

        boolean hasCol = whereSql.matches(".*\\b\\w+\\.c\\d+\\b.*") || whereSql.matches(".*\\bc\\d+\\b.*");
        if (hasCol) {
            hits.get(SqlFeature.COLUMN_REF).incrementAndGet();
        }

        checkPattern(whereSql, "(?i)\\bAND\\b", SqlFeature.LOGICAL_AND);
        checkPattern(whereSql, "(?i)\\bOR\\b", SqlFeature.LOGICAL_OR);
        checkPattern(whereSql, "(?i)\\bNOT\\b", SqlFeature.LOGICAL_NOT);
        checkPattern(whereSql, "(?i)\\bIS NULL\\b|\\bISNULL\\b", SqlFeature.IS_NULL);
        checkPattern(whereSql, "(?i)\\bIS NOT NULL\\b|\\bNOTNULL\\b|\\bNOT NULL\\b", SqlFeature.IS_NOT_NULL);
        checkPattern(whereSql, "(?i)\\bBETWEEN\\b", SqlFeature.BETWEEN);

        if (whereSql.contains(">=")) {
            hits.get(SqlFeature.CMP_GREATER_EQ).incrementAndGet();
        } else if (whereSql.contains(">")) {
            hits.get(SqlFeature.CMP_GREATER).incrementAndGet();
        }
        if (whereSql.contains("<=")) {
            hits.get(SqlFeature.CMP_LESS_EQ).incrementAndGet();
        } else if (whereSql.contains("<")) {
            hits.get(SqlFeature.CMP_LESS).incrementAndGet();
        }
        if (whereSql.contains("<>")) {
            hits.get(SqlFeature.CMP_NOT_EQUALS).incrementAndGet();
        } else if (whereSql.contains("=")) {
            hits.get(SqlFeature.CMP_EQUALS).incrementAndGet();
        }

        if (whereSql.matches(".*[\\w\\)]\\s*\\+\\s*[\\w\\(].*")) {
            hits.get(SqlFeature.ARITH_PLUS).incrementAndGet();
        }
        if (whereSql.matches(".*[\\w\\)]\\s*-\\s*[\\w\\(].*")) {
            hits.get(SqlFeature.ARITH_MINUS).incrementAndGet();
        }
        if (whereSql.matches(".*[\\w\\)]\\s*\\*\\s*[\\w\\(].*")) {
            hits.get(SqlFeature.ARITH_MULTIPLY).incrementAndGet();
        }
        if (whereSql.matches(".*[\\w\\)]\\s*/\\s*[\\w\\(].*")) {
            hits.get(SqlFeature.ARITH_DIVIDE).incrementAndGet();
        }
        if (whereSql.matches(".*\\(?\\s*\\-\\s*\\w+.*")) {
            hits.get(SqlFeature.ARITH_NEGATE).incrementAndGet();
        }
    }

    private static void checkPattern(String sql, String regex, SqlFeature f) {
        if (Pattern.compile(regex).matcher(sql).find()) {
            hits.get(f).incrementAndGet();
        }
    }

    static void analyzeExecutionPlanDetail(String detail) {
        EnumSet<ExecutionFeature> queryFeatures = EnumSet.noneOf(ExecutionFeature.class);
        analyzeExecutionPlanDetail(detail, queryFeatures);
        for (ExecutionFeature feature : queryFeatures) {
            executionHits.get(feature).incrementAndGet();
        }
    }

    static void analyzeExecutionPlanDetail(String detail, EnumSet<ExecutionFeature> queryFeatures) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        String normalized = detail.toUpperCase();
        if (normalized.contains("SCAN ")) {
            queryFeatures.add(ExecutionFeature.TABLE_SCAN);
        }
        if (normalized.contains("SEARCH ")) {
            queryFeatures.add(ExecutionFeature.INDEX_SEARCH);
        }
        if (normalized.contains("COVERING INDEX")) {
            queryFeatures.add(ExecutionFeature.COVERING_INDEX);
        }
        if (normalized.contains("AUTOMATIC")) {
            queryFeatures.add(ExecutionFeature.AUTOMATIC_INDEX);
        }
        if (normalized.contains("USE TEMP B-TREE")) {
            queryFeatures.add(ExecutionFeature.TEMP_BTREE);
        }
        if (normalized.contains("ORDER BY")) {
            queryFeatures.add(ExecutionFeature.ORDER_BY);
        }
        if (normalized.contains("GROUP BY")) {
            queryFeatures.add(ExecutionFeature.GROUP_BY);
        }
        if (normalized.contains("DISTINCT")) {
            queryFeatures.add(ExecutionFeature.DISTINCT);
        }
        if (normalized.contains("SUBQUERY")) {
            queryFeatures.add(ExecutionFeature.SUBQUERY);
        }
        if (normalized.contains("CORRELATED")) {
            queryFeatures.add(ExecutionFeature.CORRELATED_SUBQUERY);
        }
        if (normalized.contains("COMPOUND QUERY")) {
            queryFeatures.add(ExecutionFeature.COMPOUND_QUERY);
        }
        if (normalized.contains("CO-ROUTINE")) {
            queryFeatures.add(ExecutionFeature.CO_ROUTINE);
        }
        if (normalized.contains("MATERIALIZE")) {
            queryFeatures.add(ExecutionFeature.MATERIALIZE);
        }
        if (normalized.contains("MULTI-INDEX OR")) {
            queryFeatures.add(ExecutionFeature.MULTI_INDEX_OR);
        }
    }

    private static String extractWhere(String sql) {
        Pattern p = Pattern.compile("(?i)\\bWHERE\\b\\s+(.+?)(?:\\s+ORDER\\b|\\s+GROUP\\b|\\s+LIMIT\\b|\\s*$)");
        Matcher m = p.matcher(sql);
        if (m.find()) {
            return m.group(1).trim();
        }
        return sql;
    }

    private static String stripTrailingSemicolon(String sql) {
        String result = sql.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static void addSample(ConcurrentLinkedQueue<String> samples, String sql) {
        if (MAX_EMPTY_QUERY_SAMPLES <= 0 || sql == null || sql.isBlank()
                || samples.size() >= MAX_EMPTY_QUERY_SAMPLES) {
            return;
        }
        samples.add(compactSql(sql));
    }

    private static String compactSql(String sql) {
        String result = sql.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        if (result.length() > maxLength) {
            return result.substring(0, maxLength) + "...";
        }
        return result;
    }

    private static double percentage(int part, int whole) {
        return whole > 0 ? 100.0 * part / whole : 0.0;
    }

    private static SourceStats statsFor(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }
        return sourceStats.computeIfAbsent(sourceName, ignored -> new SourceStats());
    }

    private static void writeReportSafely() {
        try {
            writeReport();
        } catch (RuntimeException e) {
            System.err.println("[WORKLOAD-HINTS] Failed to write report: " + e.getMessage());
        }
    }

    static synchronized void writeReport() {
        int total = totalChecks.get();
        int withVar = variantChecks.get();
        int featureCount = SqlFeature.values().length;
        long covered = hits.values().stream().filter(v -> v.get() > 0).count();
        int executionFeatureCount = ExecutionFeature.values().length;
        long executionCovered = executionHits.values().stream().filter(v -> v.get() > 0).count();
        Map<String, AtomicInteger> wrapperSnapshot = new TreeMap<>(wrapperShapeHits);
        Map<String, AtomicInteger> sourceSnapshot = new TreeMap<>(querySourceHits);
        Map<String, SourceStats> sourceStatsSnapshot = new TreeMap<>(sourceStats);
        Map<String, AtomicInteger> corpusFilterSnapshot = new TreeMap<>(corpusFilterSkips);

        Path reportPath = Path.of(REPORT_PATH);
        Path parent = reportPath.toAbsolutePath().getParent();
        Path tmpPath = null;
        boolean reportMoved = false;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmpPath = Files.createTempFile(parent != null ? parent : Path.of("."),
                    reportPath.getFileName().toString(), ".tmp");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8))) {
            w.println("============================================================");
            w.println("  EGRAPH WORKLOAD DIVERSITY HINTS");
            w.println("============================================================");
            w.println("  NOTE: This report is NOT SQLite source-code coverage.");
            w.println("  Use it only to check whether the generated workload reaches");
            w.println("  diverse SQL constructs and SQLite execution-plan shapes.");
            w.println("  Final coverage evaluation must use gcov/lcov or llvm-cov");
            w.println("  on an instrumented SQLite build.");
            w.println();
            w.printf("  Total checks: %d | With variants: %d (%.1f%%)%n",
                    total, withVar, total > 0 ? 100.0 * withVar / total : 0);
            w.printf("  Executed queries: %d | Explained: %d | Explain failures: %d%n",
                    executedQueries.get(), explainedQueries.get(), explainFailures.get());
            w.println();

            w.println("  Query sources");
            if (sourceSnapshot.isEmpty()) {
                w.println("  - no query sources recorded");
            } else {
                for (Map.Entry<String, AtomicInteger> entry : sourceSnapshot.entrySet()) {
                    w.printf("  # %-24s %6d%n", entry.getKey(), entry.getValue().get());
                }
            }
            w.println();

            if (!sourceStatsSnapshot.isEmpty()) {
                w.println("  Query source effectiveness");
                w.println("  Source                         checks  variants   var%  orig-empty  pairs  both-empty  orig-only-empty  var-only-empty");
                for (Map.Entry<String, SourceStats> entry : sourceStatsSnapshot.entrySet()) {
                    String source = entry.getKey();
                    SourceStats stats = entry.getValue();
                    int checksForSource = sourceSnapshot.getOrDefault(source, new AtomicInteger()).get();
                    int variantsForSource = stats.variantChecks.get();
                    int originalChecksForSource = stats.originalChecks.get();
                    int pairsForSource = stats.comparedPairs.get();
                    int sourceOriginalOnlyEmpty = Math.max(0,
                            stats.originalEmptyPairs.get() - stats.bothEmptyPairs.get());
                    int sourceVariantOnlyEmpty = Math.max(0,
                            stats.variantEmptyPairs.get() - stats.bothEmptyPairs.get());
                    w.printf("  %-28s %6d  %8d  %5.1f%%  %10d  %5d  %10d  %15d  %14d%n",
                            source, checksForSource, variantsForSource,
                            percentage(variantsForSource, checksForSource),
                            stats.originalEmpty.get(), pairsForSource, stats.bothEmptyPairs.get(),
                            sourceOriginalOnlyEmpty, sourceVariantOnlyEmpty);
                    if (originalChecksForSource > checksForSource) {
                        // This should not normally happen, but keeps the report useful if future call sites record
                        // source results before source hits.
                        w.printf("  %-28s original-result-checks=%d%n", "", originalChecksForSource);
                    }
                }
                w.println();
            }

            List<String> knownBugLines = EGraphKnownBugs.report();
            if (!knownBugLines.isEmpty()) {
                w.println("  Mismatches attributed to an already reported bug (not findings)");
                knownBugLines.forEach(w::println);
                w.println("  Turn the recognition off with -Degraph.knownBugs=false to see them as findings again.");
                w.println();
            }

            List<String> refereeLines = EGraphTrunkReferee.report();
            if (!refereeLines.isEmpty()) {
                w.println("  Mismatches put to the trunk build (-Degraph.referee.command)");
                refereeLines.forEach(w::println);
                w.println();
            }

            List<String> integrityLines = EGraphIntegrityCheck.report();
            if (!integrityLines.isEmpty()) {
                w.println("  Integrity checks that did not answer ok (count, first line with numbers blanked)");
                integrityLines.forEach(w::println);
                w.println();
            }

            if (EGraphOptimizerMask.mode() != EGraphOptimizerMask.Mode.OFF) {
                w.println("  Optimizer masks (-Degraph.optMask)");
                w.println("    " + EGraphOptimizerMask.summary());
                w.println();
            }

            if (!corpusFilterSnapshot.isEmpty()) {
                w.println("  Corpus input filter skips");
                for (Map.Entry<String, AtomicInteger> entry : corpusFilterSnapshot.entrySet()) {
                    w.printf("  # %-34s %6d%n", entry.getKey(), entry.getValue().get());
                }
                w.println();
            }

            int originalChecks = originalResultChecks.get();
            int originalEmpty = originalEmptyResults.get();
            int originalNonEmpty = originalNonEmptyResults.get();
            int pairCount = comparedVariantPairs.get();
            int originalEmptyPairs = originalEmptyResultPairs.get();
            int variantEmptyPairs = variantEmptyResultPairs.get();
            int bothEmptyPairs = bothEmptyResultPairs.get();
            int bothNonEmptyPairs = bothNonEmptyResultPairs.get();
            int originalOnlyEmptyPairs = Math.max(0, originalEmptyPairs - bothEmptyPairs);
            int variantOnlyEmptyPairs = Math.max(0, variantEmptyPairs - bothEmptyPairs);

            int probeTotal = baseProbeTotal.get();
            int probeEmpty = baseProbeEmpty.get();
            w.printf("  Satisfiability pre-filter: accepted %d, rejected %d (%.1f%% of generated clauses)%n",
                    satisfiabilityAccepts.get(), satisfiabilityRejects.get(),
                    percentage(satisfiabilityRejects.get(),
                            satisfiabilityAccepts.get() + satisfiabilityRejects.get()));
            int wrapApplied = originalWrapperApplied.values().stream().mapToInt(AtomicInteger::get).sum();
            int wrapEmptied = originalWrapperEmptied.values().stream().mapToInt(AtomicInteger::get).sum();
            int wrapErrored = originalWrapperErrored.values().stream().mapToInt(AtomicInteger::get).sum();
            w.printf("  Wrapping an already non-empty base query: filtered to empty %d (%.1f%%), failed to run %d (%.1f%%), of %d wrapped%n",
                    wrapEmptied, percentage(wrapEmptied, wrapApplied), wrapErrored,
                    percentage(wrapErrored, wrapApplied), wrapApplied);
            w.println("  Both outcomes make the check fall back to the unwrapped query, losing the shape.");
            if (wrapEmptied + wrapErrored > 0) {
                w.println("  Per shape: emptied / errored / applied, worst first");
                originalWrapperApplied.entrySet().stream()
                        .map(entry -> {
                            int applied = entry.getValue().get();
                            AtomicInteger emptiedCounter = originalWrapperEmptied.get(entry.getKey());
                            AtomicInteger erroredCounter = originalWrapperErrored.get(entry.getKey());
                            int emptied = emptiedCounter == null ? 0 : emptiedCounter.get();
                            int errored = erroredCounter == null ? 0 : erroredCounter.get();
                            return new Object[] { entry.getKey(), applied, emptied, errored,
                                    percentage(emptied + errored, applied) };
                        })
                        .filter(row -> (int) row[2] + (int) row[3] > 0)
                        .sorted((left, right) -> Double.compare((double) right[4], (double) left[4]))
                        .limit(30)
                        .forEach(row -> w.printf("  # %-34s %4d / %4d / %5d  (%.1f%% lost)%n", row[0], row[2],
                                row[3], row[1], row[4]));
            }
            if (!probeErrorMessages.isEmpty()) {
                w.println("  Probe failure messages (normalized), most frequent first");
                probeErrorMessages.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue().get(), left.getValue().get()))
                        .limit(12)
                        .forEach(entry -> w.printf("  # %6d  %s%n", entry.getValue().get(), entry.getKey()));
            }
            int variantOnlyErrs = variantOnlyErrorTotal.get();
            if (variantOnlyErrs > 0) {
                w.println();
                w.println("  Variant raised an error the original did not (equivalence-breaking candidates)");
                w.printf("  Total: %d  -- triage these by hand; set -Degraph.variantOnlyError.log=<path> to capture pairs%n",
                        variantOnlyErrs);
                variantOnlyErrors.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue().get(), left.getValue().get()))
                        .limit(12)
                        .forEach(entry -> w.printf("  # %6d  %s%n", entry.getValue().get(), entry.getKey()));
            }
            w.println();
            w.println("  Base-query data generation (non-empty probe before the check)");
            w.printf("  Generated base queries: %d | Matched no row and were discarded: %d (%.1f%%) | Usable: %d (%.1f%%)%n",
                    probeTotal, probeEmpty, percentage(probeEmpty, probeTotal), probeTotal - probeEmpty,
                    percentage(probeTotal - probeEmpty, probeTotal));
            if (!planDistinctHistogram.isEmpty()) {
                w.println();
                w.println("  Distinct execution plans per check (original + variants)");
                w.println("  A check that produced only one plan ran the same bytecode on both sides:");
                w.println("  the comparison could not have failed, however many variants it had.");
                planDistinctHistogram.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> w.printf("  # %-52s %7d%n", e.getKey(), e.getValue().get()));
            }
            if (!targetTableKinds.isEmpty()) {
                w.println("  Base-query target table kind: generated / survived the non-empty probe");
                targetTableKinds.entrySet().stream()
                        .sorted((l, r) -> Integer.compare(r.getValue().get(), l.getValue().get()))
                        .forEach(e -> {
                            AtomicInteger usable = targetTableKindUsable.get(e.getKey());
                            w.printf("  # %-18s %7d / %7d%n", e.getKey(), e.getValue().get(),
                                    usable == null ? 0 : usable.get());
                        });
            }
            int emptyBaseRan = emptyBaseChecks.get();
            if (emptyBaseRan > 0) {
                w.printf("  Of the discarded, deliberately checked anyway: %d (sampled via -Degraph.emptyBaseCheckPercent)%n",
                        emptyBaseRan);
                w.println("  These are the only checks that can ever trigger the single-side-empty judgment.");
            }
            if (probeEmpty > 0 && !baseProbeEmptyFeatures.isEmpty()) {
                w.println("  WHERE features of the discarded queries (which predicates the generator cannot satisfy)");
                baseProbeEmptyFeatures.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue().get(), left.getValue().get()))
                        .limit(24)
                        .forEach(entry -> w.printf("  # %-22s %6d  (%.1f%% of discarded)%n", entry.getKey(),
                                entry.getValue().get(), percentage(entry.getValue().get(), probeEmpty)));
            }
            w.println();
            if (incomparableTruncations.get() > 0) {
                w.printf("  Comparisons abandoned (statement built on arbitrarily truncated rows): %d%n",
                        incomparableTruncations.get());
                w.println();
            }

            if (cardinalityOnlyComparisons.get() > 0) {
                w.printf("  Comparisons limited to the row count (query truncates arbitrarily): %d%n",
                        cardinalityOnlyComparisons.get());
                w.println();
            }

            if (numericSpellingDifferences.get() > 0) {
                w.printf("  Rows differing only in how a number is spelled: %d%n",
                        numericSpellingDifferences.get());
                for (String sample : numericSpellingSamples) {
                    w.println("  # " + sample);
                }
                w.println();
            }

            if (representativeChoiceDifferences.get() > 0) {
                w.printf("  Spelling-only differences the wrapper explains (unwrapped queries agree; not findings): %d%n",
                        representativeChoiceDifferences.get());
                for (String sample : representativeChoiceSamples) {
                    w.println("  # " + sample);
                }
                w.println();
            }

            if (orderOnlyDifferences.get() > 0) {
                w.printf("  Same rows in a different order (not findings): %d%n", orderOnlyDifferences.get());
                for (String sample : orderOnlySamples) {
                    w.println("  # " + sample);
                }
                w.println();
            }

            w.println("  Result-set emptiness");
            w.printf("  Original queries checked: %d | Empty: %d (%.1f%%) | Non-empty: %d (%.1f%%)%n",
                    originalChecks, originalEmpty, percentage(originalEmpty, originalChecks),
                    originalNonEmpty, percentage(originalNonEmpty, originalChecks));
            w.printf("  Compared variant pairs: %d | Both empty: %d (%.1f%%) | Both non-empty: %d (%.1f%%)%n",
                    pairCount, bothEmptyPairs, percentage(bothEmptyPairs, pairCount),
                    bothNonEmptyPairs, percentage(bothNonEmptyPairs, pairCount));
            w.printf("  Single-side empty mismatches (bug candidates): original-only empty %d (%.1f%%) | variant-only empty %d (%.1f%%)%n",
                    originalOnlyEmptyPairs, percentage(originalOnlyEmptyPairs, pairCount),
                    variantOnlyEmptyPairs, percentage(variantOnlyEmptyPairs, pairCount));
            w.printf("  Empty-side totals including both-empty: original empty side %d (%.1f%%) | variant empty side %d (%.1f%%)%n",
                    originalEmptyPairs, percentage(originalEmptyPairs, pairCount),
                    variantEmptyPairs, percentage(variantEmptyPairs, pairCount));
            if (!emptyOriginalSamples.isEmpty()) {
                w.println("  Empty original query samples");
                for (String sample : emptyOriginalSamples) {
                    w.printf("  - %s%n", sample);
                }
            }
            if (!bothEmptyPairSamples.isEmpty()) {
                w.println("  Both-empty original/variant pair samples");
                for (String sample : bothEmptyPairSamples) {
                    w.printf("  - %s%n", sample);
                }
            }
            if (!emptyOriginalPairSamples.isEmpty()) {
                w.println("  Original-only empty mismatch samples");
                for (String sample : emptyOriginalPairSamples) {
                    w.printf("  - %s%n", sample);
                }
            }
            if (!emptyVariantPairSamples.isEmpty()) {
                w.println("  Variant-only empty mismatch samples");
                for (String sample : emptyVariantPairSamples) {
                    w.printf("  - %s%n", sample);
                }
            }
            w.println();

            w.println("  Input SQL features");
            for (SqlFeature f : SqlFeature.values()) {
                int h = hits.get(f).get();
                String bar = h > 0 ? "#" : "-";
                double pct = total > 0 ? 100.0 * h / total : 0;
                w.printf("  %s %-18s %6d  (%5.1f%%)%n", bar, f.name(), h, pct);
            }

            w.println();
            w.printf("  Input SQL feature hints: %d/%d (%.1f%%)%n",
                    covered, featureCount, 100.0 * covered / featureCount);
            w.println();
            w.println("  SQLite execution-plan features (per explained query)");
            for (ExecutionFeature f : ExecutionFeature.values()) {
                int h = executionHits.get(f).get();
                String bar = h > 0 ? "#" : "-";
                double pct = explainedQueries.get() > 0 ? 100.0 * h / explainedQueries.get() : 0;
                w.printf("  %s %-20s %6d  (%5.1f%%)%n", bar, f.name(), h, pct);
            }

            w.println();
            w.printf("  Execution-plan feature hints: %d/%d (%.1f%%)%n",
                    executionCovered, executionFeatureCount, 100.0 * executionCovered / executionFeatureCount);
            w.println();
            w.println("  SQLite EGRAPH base/wrapper shapes");
            if (wrapperSnapshot.isEmpty()) {
                w.println("  - no base/wrapper shapes recorded");
            } else {
                for (Map.Entry<String, AtomicInteger> entry : wrapperSnapshot.entrySet()) {
                    w.printf("  # %-24s %6d%n", entry.getKey(), entry.getValue().get());
                }
            }
            w.println("============================================================");
            }
            try {
                Files.move(tmpPath, reportPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, reportPath, StandardCopyOption.REPLACE_EXISTING);
            }
            reportMoved = true;
            System.err.println("[WORKLOAD-HINTS] Report written to " + REPORT_PATH);
        } catch (IOException e) {
            System.err.println("[WORKLOAD-HINTS] Failed to write report: " + e.getMessage());
        } finally {
            if (!reportMoved && tmpPath != null) {
                try {
                    Files.deleteIfExists(tmpPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class SourceStats {
        private final AtomicInteger variantChecks = new AtomicInteger();
        private final AtomicInteger originalChecks = new AtomicInteger();
        private final AtomicInteger originalEmpty = new AtomicInteger();
        private final AtomicInteger originalNonEmpty = new AtomicInteger();
        private final AtomicInteger comparedPairs = new AtomicInteger();
        private final AtomicInteger originalEmptyPairs = new AtomicInteger();
        private final AtomicInteger variantEmptyPairs = new AtomicInteger();
        private final AtomicInteger bothEmptyPairs = new AtomicInteger();
        private final AtomicInteger bothNonEmptyPairs = new AtomicInteger();
    }
}
