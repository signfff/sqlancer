package sqlancer.sqlite3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import sqlancer.IgnoreMeException;
import sqlancer.OracleFactory;
import sqlancer.Randomly;
import sqlancer.common.oracle.CompositeTestOracle;
import sqlancer.common.oracle.EGraphMetamorphicOracle;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.RustEGraphVariantGenerator;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.common.schema.AbstractTables;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Constant;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation.BinaryComparisonOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3ColumnName;
import sqlancer.sqlite3.ast.SQLite3RowValueExpression;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation.BinaryOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.Join.JoinType;
import sqlancer.sqlite3.gen.SQLite3Common;
import sqlancer.sqlite3.ast.SQLite3Function;
import sqlancer.sqlite3.ast.SQLite3Function.ComputableFunction;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3OrderingTerm;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3OrderingTerm.Ordering;
import sqlancer.sqlite3.ast.SQLite3Select;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;
import sqlancer.sqlite3.oracle.SQLite3CODDTestOracle;
import sqlancer.sqlite3.oracle.SQLite3Fuzzer;
import sqlancer.sqlite3.oracle.SQLite3PivotedQuerySynthesisOracle;
import sqlancer.sqlite3.oracle.SQLite3EGraphInputCorpus.CorpusQueryInput;
import sqlancer.sqlite3.oracle.EGraphContextReplayWriter;
import sqlancer.sqlite3.oracle.EGraphContextSnapshot;
import sqlancer.sqlite3.oracle.EGraphPredicateFilter;
import sqlancer.sqlite3.oracle.SQLite3EGraphInputCorpus;
import sqlancer.sqlite3.oracle.EGraphSqlCoverage;
import sqlancer.sqlite3.oracle.tlp.SQLite3TLPAggregateOracle;
import sqlancer.sqlite3.oracle.tlp.SQLite3TLPDistinctOracle;
import sqlancer.sqlite3.oracle.tlp.SQLite3TLPGroupByOracle;
import sqlancer.sqlite3.oracle.tlp.SQLite3TLPHavingOracle;

public enum SQLite3OracleFactory implements OracleFactory<SQLite3GlobalState> {
    PQS {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3PivotedQuerySynthesisOracle(globalState);
        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }

    },
    EGRAPH {
        @Override
        public boolean requiresAllTablesToContainRows() {
            // Deliberately false. The flag makes ProviderAdapter.getTestOracle discard the whole
            // database as soon as *any* table has zero rows, throwing away the entire schema
            // generation and data insertion - which is what collapsed throughput to 39% when the
            // oracle stopped filling tables itself. EGRAPH instead skips empty tables when it
            // picks its target, so an unrelated empty table costs nothing.
            return false;
        }

        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            if (globalState.getOptions().getEGraphUrl() == null
                    || globalState.getOptions().getEGraphUrl().isBlank()) {
                throw new IllegalArgumentException("SQLite3 EGRAPH oracle requires --egraph-url");
            }
            SQLite3ExpressionGenerator gen = new SQLite3ExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(SQLite3Errors.getExpectedExpressionErrors())
                    .with(SQLite3Errors.getMatchQueryErrors()).with(SQLite3Errors.getQueryErrors())
                    .with("misuse of aggregate", "misuse of window function",
                            "second argument to nth_value must be a positive integer", "no such table",
                            "no query solution", "unable to use function MATCH in the requested context")
                    .build();
            List<CorpusQueryInput> corpusInputs = SQLite3EGraphInputCorpus
                    .readQueryInputRecords(globalState.getDbmsSpecificOptions());

            EGraphMetamorphicOracle.QueryGenerator<SQLite3GlobalState> queryGenerator = (state) -> {
                if (!corpusInputs.isEmpty() && Randomly.fromOptions(true, true, false)) {
                    try {
                        return createCorpusGeneratedQuery(state, corpusInputs);
                    } catch (IgnoreMeException ignored) {
                        try {
                            resetCorpusSchema(state);
                        } catch (Exception resetError) {
                            throw new IgnoreMeException();
                        }
                        // Fall back to the generated base-query path below.
                    }
                }
                if (CORPUS_SETUP_ONLY) {
                    EGraphSqlCoverage.trace("corpus-setup-only skip reason=random-generation-path-disabled");
                    throw new IgnoreMeException();
                }
                SQLite3Provider.ensureEGraphRandomDatabase(state);
                // Pick a table to query. Regular tables, plus R-Tree virtual tables when
                // egraph.rtreeTargets is on (default). R-Tree is the one virtual-table module whose
                // predicates the existing rule set can already rewrite: its columns are numeric, so
                // a WHERE over them is the same comparison/AND/OR tree the e-graph handles for a
                // plain table - no new language nodes or rules needed.
                //
                // The reason to want it: on a plain table these rewrites are pointless, because
                // SQLite's front end (sqlite3WhereSplit flattening AND, sqlite3ExprCommute
                // normalising `const OP col`) collapses every spelling to the same VDBE program, so
                // both sides of the comparison run identical bytecode and a defect cancels out. On
                // an R-Tree the constraint push-down happens *after* that normalisation and depends
                // on the shape of the term list, so the spellings do not collapse. Measured on one
                // predicate (x1 <= 6.0 AND x2 >= 6.0, 32 variants): six distinct plans, including
                // full-scan-with-post-filter (no constraint consumed), one-constraint, two
                // constraints in either order, and MULTI-INDEX OR with two separate R-Tree scans.
                // Those are six different paths through rtree.c that must all agree.
                //
                // FTS stays excluded: its index is only reachable through MATCH, which egraphMode
                // removes and which SqlLang has no node for, so rewriting an FTS predicate would
                // only ever produce full-table scans.
                // Empty tables are skipped rather than filled: a base query over an empty table
                // matches nothing, which makes the metamorphic comparison vacuous.
                // Skip empty tables rather than filling them: a base query over an empty table
                // matches nothing, which makes the metamorphic comparison vacuous. Selecting
                // around them is far cheaper than DELETE + reinserting a controlled pool, and
                // the data then comes entirely from SQLancer's own INSERT/UPDATE actions.
                List<SQLite3Table> tables = state.getSchema().getDatabaseTables().stream()
                        .filter(t -> !t.isView() && (!t.isVirtual() || isRtreeTable(state, t))
                                && t.getNrRows(state) > 0)
                        .collect(java.util.stream.Collectors.toList());
                if (tables.isEmpty())
                    throw new IgnoreMeException();
                // Pick the kind of table first, then one of that kind. With a single ordinary
                // table this is identical to a uniform draw over the list (one of each kind), but
                // once egraph.tables adds more ordinary tables a uniform draw would quietly cut the
                // R-Tree's share from 1/2 to 1/4 - and the R-Tree pushdown arm is where 90% of all
                // multi-plan checks come from, against 10% for joins and 6% for plain single-table
                // queries. Measured without this: 5340 R-Tree checks fell to 4012 and total
                // effective checks dropped 20%, with the join arm nowhere near making up for it.
                List<SQLite3Table> virtualCandidates = tables.stream().filter(SQLite3Table::isVirtual)
                        .collect(java.util.stream.Collectors.toList());
                List<SQLite3Table> ordinaryCandidates = tables.stream().filter(t -> !t.isVirtual())
                        .collect(java.util.stream.Collectors.toList());
                SQLite3Table chosen = virtualCandidates.isEmpty() || ordinaryCandidates.isEmpty()
                        ? Randomly.fromList(tables)
                        : Randomly.fromList(Randomly.getBoolean() ? virtualCandidates : ordinaryCandidates);
                // Which kind of table the base query targets. Without this the report cannot tell
                // "R-Tree was never picked" from "R-Tree was picked but every check was discarded",
                // and the first two runs after enabling R-Tree targets were unreadable for exactly
                // that reason.
                // A free-form random predicate on an R-Tree reads as a full scan with a post-filter:
                // the module only consumes `coordinateColumn <op> constant` terms, which the
                // generator practically never produces. Build the WHERE from constraint terms
                // instead for most R-Tree checks, and leave the rest on the random path so the
                // shapes the rule set is usually exercised on stay represented.
                int coordinateColumns = rtreeCoordinateColumns(state, chosen);
                boolean rtreePushdown = coordinateColumns >= 2 && RTREE_PUSHDOWN_PERCENT > 0
                        && Randomly.getNotCachedInteger(0, 100) < RTREE_PUSHDOWN_PERCENT;
                // On a plain table the random predicate leaves every rewrite at "same opcodes,
                // different order". An index-usable term instead lets the NOT-wrapping rules turn
                // an index seek into a full scan, which is the only strategy-level difference the
                // existing rule set can reach here. Prepared before the report bucket is chosen
                // because it can fail (no named column, no non-blob row) and then this check has to
                // fall back to the random path.
                // Predicates built around a subquery: a scalar IN, and a row value compared against
                // one. Measured on the 0921 long run, neither shape occurred once among the 52258
                // base queries, while both carry bug reports of their own. Tried first because it
                // builds the whole WHERE, like the join path below.
                boolean subqueryPredicate = !chosen.isVirtual() && SUBQUERY_PREDICATE_PERCENT > 0
                        && Randomly.getNotCachedInteger(0, 100) < SUBQUERY_PREDICATE_PERCENT
                        && chosen.getColumns().size() >= 1;
                IndexedPredicate indexedPredicate = !subqueryPredicate && !chosen.isVirtual()
                        && INDEXED_PREDICATE_PERCENT > 0
                        && Randomly.getNotCachedInteger(0, 100) < INDEXED_PREDICATE_PERCENT
                                ? prepareIndexedPredicate(state, chosen)
                                : null;
                // Tried only when the indexed-constant path did not claim this check, so the two
                // never fight over the WHERE and each keeps its own control arm in the histogram.
                PartialIndexPredicate partialIndexPredicate = !subqueryPredicate && indexedPredicate == null
                        && !chosen.isVirtual() && PARTIAL_INDEX_PERCENT > 0
                        && Randomly.getNotCachedInteger(0, 100) < PARTIAL_INDEX_PERCENT
                                ? preparePartialIndexPredicate(state, chosen)
                                : null;
                // Multi-table base queries. Exclusive with the three paths above, which all build
                // the WHERE around one table's index; here the predicate spans the joined tables
                // instead. Virtual tables stay out - the R-Tree path has its own predicate shape.
                List<SQLite3Table> joinTables = null;
                if (!subqueryPredicate && indexedPredicate == null && partialIndexPredicate == null
                        && !chosen.isVirtual()
                        && JOIN_PERCENT > 0 && Randomly.getNotCachedInteger(0, 100) < JOIN_PERCENT) {
                    // Virtual tables are allowed as the joined side. Excluding them left this path
                    // dead: EGRAPH skips empty tables rather than filling them (see
                    // requiresAllTablesToContainRows), so a generated database typically has just
                    // one non-empty ordinary table plus the seeded R-Tree, and "the other table
                    // must not be virtual" can then never be satisfied. Measured: the roll
                    // succeeded 294 times in 45s and produced a join zero times.
                    List<SQLite3Table> others = tables.stream()
                            .filter(t -> t != chosen && !t.getColumns().isEmpty())
                            .collect(java.util.stream.Collectors.toList());
                    if (!others.isEmpty()) {
                        // Ordinary tables first. Mixing them with the R-Tree in one pool meant the
                        // joined side was the R-Tree 79% of the time, because a generated database
                        // usually holds only one or two non-empty ordinary tables against its one
                        // seeded R-Tree. The reports this path exists for join ordinary tables.
                        List<SQLite3Table> ordinary = others.stream().filter(t -> !t.isVirtual())
                                .collect(java.util.stream.Collectors.toList());
                        List<SQLite3Table> pool = ordinary.isEmpty() ? others : ordinary;
                        List<SQLite3Table> picked = new ArrayList<>();
                        picked.add(chosen);
                        picked.add(Randomly.fromList(pool));
                        // Take a third table whenever one is available. The nested RIGHT JOIN
                        // report needs three, and getBooleanWithRatherLowProbability() left only
                        // 3.9% of joins with more than two tables - measured, 1.0% with three
                        // ordinary ones - so the extra tables were created and then not used.
                        List<SQLite3Table> rest = pool.stream().filter(t -> !picked.contains(t))
                                .collect(java.util.stream.Collectors.toList());
                        if (!rest.isEmpty() && Randomly.getBoolean()) {
                            picked.add(Randomly.fromList(rest));
                        }
                        joinTables = picked;
                    }
                }
                // The paths are reported apart because the plan histogram buckets by exactly this
                // string, and comparing them within one run is the whole point of the split.
                EGraphSqlCoverage.recordTargetTableKind(chosen.isVirtual()
                        ? (rtreePushdown ? "RTREE_VIRTUAL_PUSHDOWN" : "RTREE_VIRTUAL_RANDOM")
                        : (indexedPredicate != null ? "REGULAR_INDEXED_CONST"
                                : partialIndexPredicate != null ? "REGULAR_PARTIAL_INDEX"
                                        : joinTables != null ? "REGULAR_JOIN" : "REGULAR_RANDOM"));
                AbstractTables<SQLite3Table, SQLite3Column> targetTables = new AbstractTables<>(
                        joinTables != null ? joinTables : java.util.Collections.singletonList(chosen));

                SQLite3ExpressionGenerator configuredGen = gen.setEgraphMode().setTablesAndColumns(targetTables);
                SQLite3Select select;
                SQLite3Expression whereCondition;
                // Retry until WHERE references at least one column (egraph requires column
                // refs)
                int attempts = 0;
                do {
                    select = configuredGen.generateSelect();
                    if (joinTables != null) {
                        // Same wiring TLP uses: getRandomJoinClauses removes the tables it joins
                        // from the list, so what remains is the FROM list. Rebuilt on every retry
                        // because the call mutates its argument.
                        // Built here rather than through getRandomJoinClauses, which rolls twice
                        // (Randomly.getBoolean(), then nrJoinClauses in [0, size)) and left 73% of
                        // this path as plain single-table queries - measured 29 joins out of 108.
                        // The type pool leans on the outer joins: RIGHT and FULL are what the recent
                        // reports turn on, and unlike INNER they keep the result non-empty even when
                        // the ON clause is unsatisfiable, so the non-empty probe discards far less.
                        // NATURAL is excluded because it must not carry an ON clause.
                        List<sqlancer.sqlite3.ast.SQLite3Expression.Join> joins = new ArrayList<>();
                        for (int ji = 1; ji < joinTables.size(); ji++) {
                            JoinType joinType = Randomly.fromOptions(JoinType.RIGHT, JoinType.FULL,
                                    JoinType.RIGHT, JoinType.FULL, JoinType.OUTER, JoinType.INNER,
                                    JoinType.CROSS);
                            SQLite3Expression onClause = joinType == JoinType.CROSS ? null
                                    : configuredGen.generateBooleanExpression();
                            joins.add(new sqlancer.sqlite3.ast.SQLite3Expression.Join(joinTables.get(ji),
                                    onClause, joinType));
                        }
                        select.setJoinClauses(joins);
                        select.setFromList(SQLite3Common.getTableRefs(
                                java.util.Collections.singletonList(joinTables.get(0)), state.getSchema()));
                    } else {
                        select.setFromList(configuredGen.getTableRefs());
                    }
                    if (subqueryPredicate) {
                        whereCondition = generateSubqueryPredicateWhere(chosen, configuredGen);
                    } else if (rtreePushdown) {
                        whereCondition = generateRtreePushdownWhere(chosen, coordinateColumns, configuredGen);
                    } else if (indexedPredicate != null) {
                        whereCondition = generateIndexedConstantWhere(indexedPredicate, configuredGen);
                    } else if (partialIndexPredicate != null) {
                        whereCondition = generatePartialIndexWhere(partialIndexPredicate, configuredGen);
                    } else {
                        whereCondition = configuredGen.generateBooleanExpression();
                    }
                    attempts++;
                    // Reject clauses that can never be TRUE (x-x, x<x, comparisons
                    // with NULL, ...) here rather than discovering it with a probe
                    // query after the data setup: an empty original result makes the
                    // metamorphic comparison vacuous.
                    if (EGraphPredicateFilter.isEgraphCompatible(whereCondition)) {
                        EGraphSqlCoverage.recordSatisfiability(
                                EGraphPredicateFilter.isPotentiallySatisfiable(whereCondition));
                    }
                } while ((!EGraphPredicateFilter.isEgraphCompatible(whereCondition)
                        || !EGraphPredicateFilter.isPotentiallySatisfiable(whereCondition)) && attempts < 100);
                if (!EGraphPredicateFilter.isEgraphCompatible(whereCondition)
                        || !EGraphPredicateFilter.isPotentiallySatisfiable(whereCondition)) {
                    throw new IgnoreMeException();
                }
                // Return SELECT for oracle to execute + egraph to rewrite + compare
                EGraphBaseQuery baseQuery = buildEGraphBaseQuery(select, whereCondition, targetTables);
                String rewriteQuery = baseQuery.sql;
                boolean baseHasRows = queryProducesRows(state, rewriteQuery);
                EGraphSqlCoverage.recordBaseProbe(rewriteQuery, baseHasRows);
                if (!baseHasRows) {
                    // An empty original is only vacuous if the variants are empty too. If a variant
                    // returns rows, that is the cleanest signal this oracle has - no row-order and no
                    // value-formatting ambiguity, straight to hasSingleSideEmptyMismatch. Discarding
                    // every empty base query is exactly what keeps that judgment unreachable, so a
                    // tenth of them are kept.
                    //
                    // A tenth, and not all of them: measured at 10 and at 100 over 150 s each, now
                    // that a finding is visible at all. The two arms compared the same number of
                    // variant pairs, 86509 and 86579, but at 100 the share of pairs with both sides
                    // empty went from 2.6% to 16.3% - 14% of the comparison budget spent on pairs
                    // that cannot say anything - and neither arm raised a single finding more than the
                    // other. The tenth costs 2.6% and is the only way to reach an empty-original
                    // case at all, since the wrapped path needs a base query that already has rows.
                    if (EMPTY_BASE_CHECK_PERCENT <= 0
                            || Randomly.getNotCachedInteger(0, 100) >= EMPTY_BASE_CHECK_PERCENT) {
                        throw new IgnoreMeException();
                    }
                    // No wrapper shape here: a shape over an empty base filters to empty anyway and
                    // would only cost a probe. The plain comparison is what carries the signal.
                    EGraphSqlCoverage.recordEmptyBaseCheck();
                    EGraphSqlCoverage.recordCheck();
                    EGraphSqlCoverage.analyzeWhere(rewriteQuery);
                    return new EGraphMetamorphicOracle.GeneratedQuery(rewriteQuery, rewriteQuery, variant -> variant,
                            "RANDOM_GENERATED_1_3_EMPTY_BASE");
                }
                EGraphSqlCoverage.recordWrapperShape(baseQuery.shapeName);
                EGraphCoverageContext originalContext = createEGraphCoverageContext(state, targetTables,
                        chooseEGraphExecutionCoverageShape(EGraphCoverageShape.PLAIN));
                EGraphCoverageContext variantContext = createVariantEGraphCoverageContext(originalContext,
                        targetTables);
                String result = wrapEGraphCoverageShape(rewriteQuery, originalContext);
                ProbeOutcome wrappedOutcome = probeQuery(state, result);
                EGraphSqlCoverage.recordOriginalWrapper(originalContext.shape.name(), wrappedOutcome.name(), result);
                if (wrappedOutcome != ProbeOutcome.ROWS) {
                    // The wrapper filtered away every row of a base query that was
                    // just probed non-empty, so this check would compare two empty
                    // result sets. Drop the shape rather than the check.
                    originalContext = createEGraphCoverageContext(null, targetTables, EGraphCoverageShape.PLAIN);
                    variantContext = originalContext;
                    result = rewriteQuery;
                }
                EGraphSqlCoverage.recordCheck();
                EGraphSqlCoverage.analyzeWhere(rewriteQuery);
                EGraphCoverageContext finalVariantContext = variantContext;
                return new EGraphMetamorphicOracle.GeneratedQuery(result, rewriteQuery,
                        variant -> wrapEGraphCoverageShape(variant, finalVariantContext), "RANDOM_GENERATED_1_3");
            };
            return new EGraphMetamorphicOracle<>(globalState, queryGenerator, errors,
                    new RustEGraphVariantGenerator(globalState.getOptions().getEGraphUrl(),
                            globalState.getOptions().getEGraphMaxVariants(),
                            globalState.getOptions().getEGraphTimeoutMillis()));
        }

    },
    NoREC {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            SQLite3ExpressionGenerator gen = new SQLite3ExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(SQLite3Errors.getExpectedExpressionErrors())
                    .with(SQLite3Errors.getMatchQueryErrors()).with(SQLite3Errors.getQueryErrors())
                    .with("misuse of aggregate", "misuse of window function",
                            "second argument to nth_value must be a positive integer", "no such table",
                            "no query solution", "unable to use function MATCH in the requested context")
                    .build();
            return new NoRECOracle<>(globalState, gen, errors);
        }
    },
    AGGREGATE {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3TLPAggregateOracle(globalState);
        }

    },
    WHERE {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            SQLite3ExpressionGenerator gen = new SQLite3ExpressionGenerator(globalState);
            ExpectedErrors expectedErrors = ExpectedErrors.newErrors().with(SQLite3Errors.getExpectedExpressionErrors())
                    .build();
            return new TLPWhereOracle<>(globalState, gen, expectedErrors);
        }

    },
    DISTINCT {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3TLPDistinctOracle(globalState);
        }
    },
    GROUP_BY {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3TLPGroupByOracle(globalState);
        }
    },
    HAVING {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3TLPHavingOracle(globalState);
        }
    },
    FUZZER {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3Fuzzer(globalState);
        }
    },
    QUERY_PARTITIONING {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws Exception {
            List<TestOracle<SQLite3GlobalState>> oracles = new ArrayList<>();
            oracles.add(WHERE.create(globalState));
            oracles.add(DISTINCT.create(globalState));
            oracles.add(GROUP_BY.create(globalState));
            oracles.add(HAVING.create(globalState));
            oracles.add(AGGREGATE.create(globalState));
            return new CompositeTestOracle<SQLite3GlobalState>(oracles, globalState);
        }
    },
    CODDTest {
        @Override
        public TestOracle<SQLite3GlobalState> create(SQLite3GlobalState globalState) throws SQLException {
            return new SQLite3CODDTestOracle(globalState);
        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }
    };

    private enum EGraphCoverageShape {
        PLAIN,
        DISTINCT,
        GROUP_BY,
        DERIVED_TABLE,
        COMPOUND_UNION_ALL,
        CORRELATED_SUBQUERY,
        MATERIALIZED_CTE,
        NOT_MATERIALIZED_CTE,
        AUTOMATIC_INDEX,
        CO_ROUTINE,
        MULTI_INDEX_OR,
        LIMIT_OFFSET,
        WHERE_CASE_TRUE,
        WHERE_FUNCTION_TRUE,
        WHERE_COLLATE_TRUE,
        SCALAR_SUBQUERY,
        WINDOW_COUNT,
        VALUES_CTE_JOIN,
        RECURSIVE_CTE,
        FTS5_MATCH_CONTEXT,
        RTREE_CONTEXT,
        RTREE_DEEP_CONTEXT,
        DBSTAT_CONTEXT,
        VIEW_TRIGGER_FK_CONTEXT,
        ANALYZE_INDEX_CONTEXT,
        JSON_CONTEXT,
        TX_WAL_VACUUM_CONTEXT,
        AUTO_VACUUM_INTEGRITY_CONTEXT,
        ATTACH_VACUUM_WAL_CONTEXT,
        ALTER_INDEX_ANALYZE_CONTEXT,
        SELECT_WHERE_STRESS_CONTEXT,
        EXPR_STRESS_CONTEXT,
        WINDOW_STRESS_CONTEXT,
        RESOLVE_STRESS_CONTEXT,
        SORTER_STRESS_CONTEXT,
        JOIN_OPTIMIZER_CONTEXT,
        ALTER_FK_STRESS_CONTEXT,
        INTEGRITY_CHECK_CONTEXT,
        SCALAR_AGGREGATE_CONTEXT,
        VIRTUAL_TABLE_UPDATE_CONTEXT,
        FTS5_SECURE_DELETE_CONTEXT,
        FTS5_DEEP_CONTEXT,
        VIRTUAL_TABLE_SAVEPOINT_CONTEXT,
        JSONB_STRESS_CONTEXT,
        XFER_OPTIMIZATION_CONTEXT,
        MULTI_SELECT_ORDER_BY_CONTEXT,
        // AGGREGATE_ORDER_BY_CONTEXT and WINDOW_RANGE_FULLSCAN_CONTEXT aggregate the inner result
        // instead of appending a constant-true EXISTS filter, so what they compute depends on the rows
        // the base query selected - which is what the rewrite changes, and therefore something this
        // oracle can see be wrong. A value built from fixed literals cannot be, whichever side
        // computes it. ROW_VALUE_CONTEXT is in this group by subject, not by that property.

        ROW_VALUE_CONTEXT,
        AGGREGATE_ORDER_BY_CONTEXT,
        WINDOW_RANGE_FULLSCAN_CONTEXT,
        // Function batteries. These are for coverage alone, and it is worth being exact about why:
        // every value they project is computed from fixed literals, so the original and the variant
        // compute the same thing and a differential oracle cannot see it be wrong - SQLite would get
        // it equally wrong on both sides and the two would still agree. What reaches a finding is a
        // projected value that depends on the rows the rewrite selects, or on the plan. The fixed
        // literals are also the point: 'now', random() and last_insert_rowid() would differ between
        // the two executions and fake a bug.
        SCALAR_FUNCTION_BATTERY_CONTEXT,
        DATE_MODIFIER_BATTERY_CONTEXT,
        WINDOW_FUNCTION_BATTERY_CONTEXT,
        FTS4_MATCH_CONTEXT,
        FTS4_AUX_CONTEXT,
        BLOOM_FILTER_CONTEXT,
        MULTI_INDEX_OR_ROWSET_CONTEXT,
        INDEX_FUNCTION_VALUE_CONTEXT,
        SORTER_DEEP_MERGE_CONTEXT,
        FTS5_DEEP_QUERY_CONTEXT,
        FTS5_AUX_DEEP_CONTEXT,
        FTS4_DEEP_SEGMENT_CONTEXT,
        FTS5_VARIANT_CONFIG_CONTEXT,
        PRAGMA_VTAB_CONTEXT,
        FTS4_MERGE_LCS_CONTEXT,
        FTS3_TOKENIZE_TABLE_CONTEXT,
        FTS5_TOMBSTONE_CONTEXT,
        FTS5_TOKENIZER_VARIANT_CONTEXT,
        SQL_SYNTAX_BATTERY_CONTEXT,
        // Built-ins that the SQLancer expression generator never emits, so their
        // implementations had never been entered: unistrFunc and the percentile
        // extension. Same battery rules as above - fixed literals only.
        COLD_FUNCTION_BATTERY_CONTEXT,
        // INSTEAD OF triggers and ALTER TABLE ... DROP CONSTRAINT. Both are DDL,
        // so they can only live in the setup; the wrapper just reads the probe.
        COLD_DDL_TRIGGER_CONTEXT
    }

    private enum EGraphBaseQueryShape {
        PLAIN,
        COLUMN_PROJECTION,
        DISTINCT_COLUMNS,
        ORDER_BY_COLUMN,
        LIMIT_10,
        ORDER_BY_COLUMN_LIMIT_10
    }

    private static final AtomicInteger TX_CONTEXT_COUNT = new AtomicInteger();
    private static final AtomicInteger ATTACH_CONTEXT_COUNT = new AtomicInteger();
    private static final AtomicInteger INTEGRITY_CONTEXT_COUNT = new AtomicInteger();
    private static final AtomicInteger AUTO_VACUUM_INTEGRITY_CONTEXT_COUNT = new AtomicInteger();
    private static final boolean EGRAPH_BASE_SKELETONS = Boolean
            .parseBoolean(System.getProperty("sqlite3.egraph.baseSkeletons", "true"));
    private static final boolean EGRAPH_SELECT_ONLY_TEMPLATES = Boolean
            .parseBoolean(System.getProperty("sqlite3.egraph.corpus.selectOnlyTemplates", "true"));
    private static final boolean REPLAY_CORPUS_CASE_SETUP = Boolean
            .parseBoolean(System.getProperty("sqlite3.egraph.corpus.replayCaseSetup", "true"));
    private static final int MAX_CORPUS_ATTEMPTS_PER_CHECK = Integer
            .getInteger("sqlite3.egraph.corpus.maxAttemptsPerCheck", 2);
    private static final Set<String> BAD_CORPUS_INPUTS = ConcurrentHashMap.newKeySet();
    /**
     * Diagnostic switch: only run corpus cases that bring their own setup, i.e. replay the corpus CREATE/INSERT
     * verbatim. With -Dsqlite3.egraph.corpusSetupOnly=true everything that would instead run against data SQLancer
     * produced on its own is refused - the random-generation path entirely, and corpus cases without setup
     * statements. Used to measure how much of the workload survives on corpus-provided data alone.
     */
    private static final boolean CORPUS_SETUP_ONLY = Boolean.getBoolean("sqlite3.egraph.corpusSetupOnly");

    /**
     * Keep a corpus case whose base query returns no rows instead of dropping it. "Returns nothing where it should
     * return something" is the shape of a large share of the reported correctness bugs, and the oracle reports an
     * empty original against a non-empty variant just as it reports the reverse. Measured on the reproducers of the
     * SQLite bug forum: one more of them is caught, at no throughput cost the measurement could separate from noise.
     * The random path keeps its own sampling knob (egraph.emptyBaseCheckPercent), since an empty base query there
     * means a predicate that matches nothing, whose variants are empty too.
     */
    private static final boolean ALLOW_EMPTY_BASE_QUERY = !"false"
            .equalsIgnoreCase(System.getProperty("sqlite3.egraph.corpus.allowEmptyBase", "true"));

    private static final boolean RTREE_TARGETS = !"false"
            .equalsIgnoreCase(System.getProperty("egraph.rtreeTargets", "true"));

    /**
     * Per table: -1 when it is not backed by the rtree module, otherwise how many coordinate columns
     * it declares - everything between the id column and the first auxiliary ("+name") column. Read
     * from sqlite_master rather than guessed from the name, because SQLancer's "rt0" convention is
     * not something this oracle should depend on. Cached per name so the lookup does not repeat on
     * every check.
     */
    private static final Map<String, Integer> RTREE_TABLE_CACHE = new ConcurrentHashMap<>();

    private static boolean isRtreeTable(SQLite3GlobalState state, SQLite3Table table) {
        return rtreeCoordinateColumns(state, table) >= 0;
    }

    /**
     * Coordinate columns of an R-Tree table, or -1 if the table is not one. Only these columns can
     * carry a constraint the module consumes, so this is what {@link #generateRtreePushdownWhere}
     * builds its terms from.
     */
    private static int rtreeCoordinateColumns(SQLite3GlobalState state, SQLite3Table table) {
        if (!RTREE_TARGETS || table == null || !table.isVirtual()) {
            return -1;
        }
        // Keyed by name AND column count: generated databases reuse table names, so an "rt1" with
        // five columns would otherwise hand its coordinate count to the next database's three-column
        // "rt1" and make generateRtreePushdownWhere index past the end of the column list.
        String cacheKey = table.getName() + "#" + table.getColumns().size();
        Integer cached = RTREE_TABLE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int coordinates = -1;
        try {
            SQLQueryAdapter q = new SQLQueryAdapter(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '" + table.getName() + "'");
            try (SQLancerResultSet rs = q.executeAndGet(state)) {
                if (rs != null && rs.next()) {
                    String sql = rs.getString(1);
                    if (sql != null) {
                        String normalized = sql.toLowerCase(java.util.Locale.ROOT);
                        // rtree or rtree_i32; no other module name contains it
                        if (normalized.contains("rtree")) {
                            coordinates = countRtreeCoordinateColumns(sql, table.getColumns().size());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            coordinates = -1;
        }
        RTREE_TABLE_CACHE.put(cacheKey, coordinates);
        return coordinates;
    }

    /**
     * Counts the coordinate columns in a {@code CREATE VIRTUAL TABLE ... USING rtree(...)}: the
     * module arguments after the id column, up to the first auxiliary one. Getting this wrong is not
     * dangerous - a constraint on an auxiliary column is simply not consumed and stays a post-filter
     * - so the parse is deliberately simple and clamps to what the schema actually has.
     */
    private static int countRtreeCoordinateColumns(String createStatement, int nrSchemaColumns) {
        int open = createStatement.indexOf('(');
        int close = createStatement.lastIndexOf(')');
        if (open < 0 || close < open) {
            return 0;
        }
        String[] args = createStatement.substring(open + 1, close).split(",");
        int coordinates = 0;
        for (int i = 1; i < args.length; i++) {   // argument 0 is the id column
            if (args[i].trim().startsWith("+")) {
                break;
            }
            coordinates++;
        }
        coordinates = Math.max(0, Math.min(coordinates, nrSchemaColumns - 1));
        return coordinates - coordinates % 2;   // coordinates always come in (min, max) pairs
    }

    // Percentage of R-Tree-targeted base queries whose WHERE is assembled from push-down-eligible
    // constraint terms instead of being taken from the free-form expression generator. 0 restores
    // the previous behaviour; the remainder still goes through the random generator, which keeps
    // the predicate shapes the rule set is normally exercised on in the mix.
    private static final int RTREE_PUSHDOWN_PERCENT = Integer
            .parseInt(System.getProperty("egraph.rtreePushdownPercent", "80"));

    /**
     * Percentage of non-virtual base queries whose WHERE is built as {@code indexedColumn <op>
     * constant}, with an index guaranteed on that column.
     *
     * <p>
     * The point is not the comparison itself but what the rewrite rules can then do with it:
     * {@code allowedOp} (sqlite3.c) only index-matches TK_EQ..TK_GE, so a term the rules wrap in
     * NOT - which is exactly what gt-to-not-lteq and its five siblings produce - stops being
     * index-usable and compiles to a full scan while the original compiles to an index seek. That is
     * a strategy-level difference on a plain table, where the rest of the rule set only manages to
     * reorder the same opcodes. Measured by hand: {@code c1 > 1} gives SEARCH USING INDEX, and
     * {@code NOT (c1 <= 1)} gives SCAN, both returning the same 28 rows.
     * </p>
     *
     * <p>
     * 0 restores the previous always-random behaviour. Left at 0 until an A/B says otherwise.
     * </p>
     */
    private static final int INDEXED_PREDICATE_PERCENT = Integer
            .parseInt(System.getProperty("egraph.indexedPredicatePercent", "0"));

    /**
     * Percentage of base queries whose WHERE is built around a partial index's own predicate.
     *
     * <p>
     * SQLite only considers a partial index when the query's WHERE contains a conjunct that its
     * {@code sqlite3ExprImpliesExpr()} can match against the index predicate, and that check is a
     * conservative syntactic one. Measured on this build: {@code c0 = 7} matches, and so do
     * {@code 7 = c0}, {@code t0.c0 = 7} and {@code (c0 = 7)}, but {@code c0 = 7.0}, {@code c0 = 3+4},
     * {@code c0 BETWEEN 7 AND 7}, {@code c0 >= 7 AND c0 <= 7}, {@code NOT (c0 <> 7)} and
     * {@code c0 IN (7)} all fall back to a full scan.
     * </p>
     *
     * <p>
     * SQLite3IndexGenerator already emits partial indexes for half of the indexes it creates, but it
     * builds their predicate from an independent draw of the random expression generator, and the
     * base query's WHERE is yet another independent draw. Two independent samples of that generator
     * essentially never come out token-identical, so those partial indexes are created and then
     * never enter a single query plan. Handing the base query the index's own predicate as a
     * top-level conjunct is what puts them into the plan, and from there a rewrite that spells the
     * conjunct differently loses the index while the original keeps it - the same divergence the
     * indexed-constant path produces, one level deeper.
     * </p>
     *
     * <p>
     * 0 keeps the previous behaviour. Left at 0 until an A/B says otherwise.
     * </p>
     */
    private static final int PARTIAL_INDEX_PERCENT = Integer
            .parseInt(System.getProperty("egraph.partialIndexPercent", "0"));

    /**
     * Percentage of base queries that span more than one table.
     *
     * <p>
     * The base query has always been a single table ({@code singletonList(chosen)}), while PQS,
     * TLP and the random query synthesizer all call {@code getRandomJoinClauses}. Of 31 correctness
     * reports filed on sqlite.org/bugs over the past month, seven turn on RIGHT or FULL JOIN -
     * nested RIGHT JOIN returning an extra row, RIGHT JOIN with a UNIQUE INDEX, RIGHT JOIN filtered
     * by a row-value IN, json_each under a RIGHT JOIN - and this oracle cannot express any of them.
     * RIGHT and FULL JOIN arrived in 3.39, so they are in the window where a feature still has bugs.
     * </p>
     *
     * <p>
     * Measured before writing this: duplicate column names across joined tables are not a problem -
     * SQLite disambiguates them ({@code c0,c1,c0:1,c1:1}) and all seven wrapper shapes accept the
     * result. An INNER JOIN with an unsatisfiable ON does collapse to zero rows and gets dropped by
     * the non-empty probe, but an outer join does not, which happens to make RIGHT JOIN the
     * cheapest of these to reach.
     * </p>
     *
     * <p>
     * 0 keeps the previous single-table behaviour. Left at 0 until an A/B says otherwise.
     * </p>
     */
    private static final int JOIN_PERCENT = Integer.parseInt(System.getProperty("egraph.joinPercent", "0"));

    /** A partial index plus the exact predicate tree it was created with. */
    private static final class PartialIndexPredicate {
        private final SQLite3Expression predicate;

        PartialIndexPredicate(SQLite3Expression predicate) {
            this.predicate = predicate;
        }
    }

    /** A column with an index on it plus a constant taken from that column, so the term matches rows. */
    private static final class IndexedPredicate {
        private final SQLite3Column column;
        private final SQLite3Constant constant;

        IndexedPredicate(SQLite3Column column, SQLite3Constant constant) {
            this.column = column;
            this.constant = constant;
        }
    }

    /**
     * Creates the index and samples the constant, or returns null when this table has nothing
     * usable - no named column, or no row holding an integer, real or text value. Callers fall back
     * to the random predicate path, which is also what keeps the in-run control arm populated.
     */
    private static IndexedPredicate prepareIndexedPredicate(SQLite3GlobalState state, SQLite3Table table) {
        if (state == null || table == null || table.isVirtual() || !hasUsableIdentifier(table.getName())) {
            return null;
        }
        List<SQLite3Column> candidates = table.getColumns().stream()
                .filter(SQLite3OracleFactory::hasUsableIdentifier)
                .collect(java.util.stream.Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        SQLite3Column column = Randomly.fromList(candidates);
        String indexName = quoteIdentifier(
                "egraph_ixp_" + sanitizeIdentifierPart(table.getName()) + "_" + sanitizeIdentifierPart(column.getName()));
        // IF NOT EXISTS, so this is a no-op after the first check that picks this column. A column
        // that cannot be indexed (a generated column in some SQLite builds) fails here and the
        // sampled constant would still produce a full scan on both sides, so give up instead.
        if (!executeContextStatement(state, "CREATE INDEX IF NOT EXISTS " + indexName + " ON "
                + quoteIdentifier(table.getName()) + "(" + quoteIdentifier(column.getName()) + ")", true)) {
            return null;
        }
        SQLite3Constant constant = sampleColumnConstant(state, table, column);
        return constant == null ? null : new IndexedPredicate(column, constant);
    }

    /**
     * Creates a partial index and returns the predicate it was created with, or null when the table
     * has nothing usable. Callers fall back to the random path, which keeps the in-run control arm
     * populated.
     */
    private static PartialIndexPredicate preparePartialIndexPredicate(SQLite3GlobalState state, SQLite3Table table) {
        if (state == null || table == null || table.isVirtual() || !hasUsableIdentifier(table.getName())) {
            return null;
        }
        List<SQLite3Column> candidates = table.getColumns().stream()
                .filter(SQLite3OracleFactory::hasUsableIdentifier)
                .collect(java.util.stream.Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        SQLite3Column predicateColumn = Randomly.fromList(candidates);
        SQLite3Constant constant = sampleColumnConstant(state, table, predicateColumn);
        if (constant == null) {
            return null;
        }
        SQLite3Expression left = new SQLite3ColumnName(predicateColumn, null);
        SQLite3Expression right = constant;
        if (Randomly.getBoolean()) {
            // Wrapping both sides in the same deterministic function keeps the predicate satisfiable
            // by the sampled row while making the index predicate a shape the planner has to match
            // structurally rather than as a bare column comparison - which is what the partial-index
            // bugs on sqlite.org/bugs look like (a json_quote() call inside the index WHERE).
            ComputableFunction function = Randomly.fromOptions(ComputableFunction.ABS, ComputableFunction.LOWER,
                    ComputableFunction.UPPER, ComputableFunction.LIKELY, ComputableFunction.UNLIKELY);
            left = new SQLite3Function(function, left);
            right = new SQLite3Function(function, right);
        }
        SQLite3Expression predicate = new BinaryComparisonOperation(left, right,
                BinaryComparisonOperator.EQUALS);
        String predicateText = renderExpression(predicate);
        if (predicateText == null || predicateText.isBlank()) {
            return null;
        }
        SQLite3Column indexedColumn = Randomly.fromList(candidates);
        // The predicate is part of the index name: two checks that sample different constants must
        // not have IF NOT EXISTS silently reuse the first one's index, or the query would be built
        // around a predicate that no existing index actually carries.
        String indexName = quoteIdentifier("egraph_pix_" + sanitizeIdentifierPart(table.getName()) + "_"
                + sanitizeIdentifierPart(indexedColumn.getName()) + "_"
                + Integer.toHexString(predicateText.hashCode()));
        if (!executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + quoteIdentifier(table.getName()) + "("
                        + quoteIdentifier(indexedColumn.getName()) + ") WHERE " + predicateText,
                true)) {
            return null;
        }
        return new PartialIndexPredicate(predicate);
    }

    /** Renders an expression the same way the base query will, so the index predicate matches it. */
    private static String renderExpression(SQLite3Expression expression) {
        try {
            SQLite3ToStringVisitor visitor = new SQLite3ToStringVisitor();
            visitor.fullyQualifiedNames = false;
            visitor.visit(expression);
            return visitor.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Builds {@code <indexPredicate> [AND <random>]}. The index predicate has to stay a top-level
     * conjunct: measured, SQLite ignores the partial index as soon as the matching term sits under a
     * NOT or inside an OR, so nesting it would silently put this path back on a full scan.
     */
    /**
     * Percentage of base queries whose WHERE is a subquery predicate: {@code c IN (SELECT ...)} or a row value
     * compared against one.
     *
     * <p>
     * Counted over the 52258 base queries of the 0921 long run: not one contained a row value, an IN over a subquery
     * or a JOIN. The generator only ever produced comparisons, boolean connectives and arithmetic over one table's
     * columns. Three of the SQLite bug reports this oracle is measured against live in exactly the shapes that never
     * appeared - a row value IN with an aggregate in the subquery among them.
     * </p>
     *
     * <p>
     * The rewrite server has no node for IN, so it keeps such a predicate as one atom and offers only the identity
     * rewrites. That is the point: those change which plan SQLite picks for the atom without touching its meaning,
     * and the reports are about the plan the atom compiles to.
     * </p>
     *
     * <p>
     * Measured over 300 s per setting, on the random path alone: at 0 the rewrites produced more than one plan for
     * 6.9% of checks, at 30 for 32.9%, at 100 for 88.6%. Throughput rose rather than fell (1230, 3990 and 4665
     * checks), because a predicate whose subquery reads the queried table is satisfiable by construction and so is
     * discarded far less often than a random one. 30 is the default: it triples the share of checks that can fail at
     * all while leaving most of the random path on the other shapes.
     * </p>
     */
    private static final int SUBQUERY_PREDICATE_PERCENT = Integer
            .parseInt(System.getProperty("egraph.subqueryPredicatePercent", "30"));

    /**
     * Builds a WHERE of the form {@code c IN (SELECT c FROM t AS alias)} or
     * {@code (a, b) IN (SELECT a, min(b) FROM t AS alias)}, over the queried table itself so the subquery is
     * satisfiable by construction - every row of the table satisfies the scalar form, and the row-value form is what
     * the reports about row values and unique indexes need.
     */
    private static SQLite3Expression generateSubqueryPredicateWhere(SQLite3Table table,
            SQLite3ExpressionGenerator gen) {
        List<SQLite3Column> columns = table.getColumns();
        SQLite3Column first = Randomly.fromList(columns);
        String alias = "egraph_sub";
        String quotedTable = quoteIdentifier(table.getName());
        SQLite3Expression predicate;
        SQLite3Column second = columns.size() > 1 ? Randomly.fromList(columns) : null;
        if (second != null && !second.getName().equals(first.getName()) && Randomly.getBoolean()) {
            // The row-value form. An aggregate on the right makes the subquery a single row, which
            // is the shape the row-value reports use; without it the subquery is the table itself.
            String right = Randomly.getBoolean()
                    ? alias + "." + quoteIdentifier(second.getName())
                    : Randomly.fromOptions("min", "max") + "(" + alias + "." + quoteIdentifier(second.getName()) + ")";
            String subquery = "SELECT " + alias + "." + quoteIdentifier(first.getName()) + ", " + right + " FROM "
                    + quotedTable + " AS " + alias;
            predicate = new SQLite3Expression.InOperation(
                    new SQLite3RowValueExpression(List.of(new SQLite3ColumnName(first, null),
                            new SQLite3ColumnName(second, null))),
                    new SQLite3Expression.Subquery(subquery));
        } else {
            String projection = Randomly.getBooleanWithRatherLowProbability()
                    ? Randomly.fromOptions("min", "max") + "(" + alias + "." + quoteIdentifier(first.getName()) + ")"
                    : alias + "." + quoteIdentifier(first.getName());
            // The inner WHERE is what the server's subquery decomposition rewrites: without one, the
            // IN (SELECT ...) predicate is one opaque symbol and every check gets only the three
            // identity variants. It must stay satisfiable by construction so the outer IN keeps
            // returning rows - see generateSubqueryInnerWhere. The row-value form above gets no
            // inner WHERE on purpose: the server's row-value gate rejects every variant of it.
            String subquery = "SELECT " + projection + " FROM " + quotedTable + " AS " + alias + " WHERE "
                    + generateSubqueryInnerWhere(table, first, second, alias);
            predicate = new SQLite3Expression.InOperation(new SQLite3ColumnName(first, null),
                    new SQLite3Expression.Subquery(subquery));
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            // A second term the optimiser has to weigh against the subquery when it picks a plan.
            predicate = new Sqlite3BinaryOperation(predicate, gen.generateBooleanExpression(), BinaryOperator.AND);
        }
        return predicate;
    }

    private static String generateSubqueryInnerWhere(SQLite3Table table, SQLite3Column first,
            SQLite3Column second, String alias) {
        String firstCol = alias + "." + quoteIdentifier(first.getName());
        if (second != null && !second.getName().equals(first.getName()) && Randomly.getBoolean()) {
            // A pure OR of equalities over the same left operand: the shape the OR-to-IN optimization
            // rewrites into IN, where the reported collation bugs live. The first term also makes the
            // predicate satisfiable by construction - any row whose column is not NULL satisfies it.
            return firstCol + " = " + firstCol + " OR " + firstCol + " = "
                    + alias + "." + quoteIdentifier(second.getName());
        }
        // A three-valued tautology: the IS NULL arm covers the rows the self-equality misses.
        return firstCol + " = " + firstCol + " OR " + firstCol + " IS NULL";
    }

    private static SQLite3Expression generatePartialIndexWhere(PartialIndexPredicate prepared,
            SQLite3ExpressionGenerator gen) {
        SQLite3Expression predicate = prepared.predicate;
        if (Randomly.getBoolean()) {
            predicate = new Sqlite3BinaryOperation(predicate, gen.generateBooleanExpression(), BinaryOperator.AND);
        }
        return predicate;
    }

    /**
     * Reads one value out of the column so the comparison is satisfiable by construction. Blobs and
     * NULLs are filtered out in SQL rather than here: a blob literal has to round-trip through
     * x'..' to stay a blob, and a NULL comparison is rejected by EGraphPredicateFilter anyway.
     */
    private static SQLite3Constant sampleColumnConstant(SQLite3GlobalState state, SQLite3Table table,
            SQLite3Column column) {
        String columnName = quoteIdentifier(column.getName());
        String sql = "SELECT typeof(" + columnName + "), " + columnName + " FROM "
                + quoteIdentifier(table.getName()) + " WHERE typeof(" + columnName
                + ") IN ('integer', 'real', 'text') ORDER BY random() LIMIT 1";
        try (SQLancerResultSet rs = new SQLQueryAdapter(sql, false).executeAndGet(state, false)) {
            if (rs == null || !rs.next()) {
                return null;
            }
            String type = rs.getString(1);
            String value = rs.getString(2);
            if (type == null || value == null) {
                return null;
            }
            switch (type) {
            case "integer":
                return SQLite3Constant.createIntConstant(Long.parseLong(value));
            case "real":
                return SQLite3Constant.createRealConstant(Double.parseDouble(value));
            default:
                return SQLite3Constant.createTextConstant(value);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds {@code column <op> constant}. Deliberately plain: the NOT wrapping that costs the term
     * its index usability is what the rule set produces on its own, so the base query has to hand it
     * a term that is index-usable to begin with.
     */
    private static SQLite3Expression generateIndexedConstantWhere(IndexedPredicate prepared,
            SQLite3ExpressionGenerator gen) {
        SQLite3Expression columnRef = new SQLite3ColumnName(prepared.column, null);
        BinaryComparisonOperator operator = Randomly.fromOptions(BinaryComparisonOperator.EQUALS,
                BinaryComparisonOperator.NOT_EQUALS, BinaryComparisonOperator.SMALLER,
                BinaryComparisonOperator.SMALLER_EQUALS, BinaryComparisonOperator.GREATER,
                BinaryComparisonOperator.GREATER_EQUALS);
        SQLite3Expression predicate = new BinaryComparisonOperation(columnRef, prepared.constant, operator);
        if (Randomly.getBooleanWithRatherLowProbability()) {
            // Two terms on the same column: the optimiser then has to pick which one drives the
            // index seek and which stays a post-filter, and the rules can move that boundary.
            predicate = new Sqlite3BinaryOperation(predicate,
                    new BinaryComparisonOperation(new SQLite3ColumnName(prepared.column, null), prepared.constant,
                            Randomly.fromOptions(BinaryComparisonOperator.SMALLER_EQUALS,
                                    BinaryComparisonOperator.GREATER_EQUALS,
                                    BinaryComparisonOperator.NOT_EQUALS)),
                    BinaryOperator.AND);
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            // A term the index cannot serve, so "index seek plus post-filter" stays in the mix.
            predicate = new Sqlite3BinaryOperation(predicate, gen.generateBooleanExpression(), BinaryOperator.AND);
        }
        return predicate;
    }

    // Bound for the "constraint that matches everything" terms. Kept below 1e7 so Double.toString
    // does not switch to exponent notation, which the rewrite server's parser has no need to see.
    private static final double RTREE_WIDE_BOUND = 1000000.0;

    /**
     * Builds a WHERE clause over an R-Tree table's coordinate columns that the module can actually
     * consume.
     *
     * <p>
     * The module only takes terms of the form {@code coordinateColumn <op> constant} that sit
     * directly in the top-level AND/OR structure; anything else stays a post-filter over a full
     * scan. The free-form generator practically never emits that shape - it builds arbitrary
     * arithmetic trees and column-vs-column comparisons - which is why R-Tree targets produced a
     * single plan for 94% of their checks while regular tables managed more than one for 17%. With
     * constraint terms the rewrites become visible: the same predicate compiles to a full scan, a
     * one-constraint scan, a two-constraint scan in either order, or a MULTI-INDEX OR over two
     * separate R-Tree scans, and all of them have to return the same rows.
     * </p>
     */
    private static SQLite3Expression generateRtreePushdownWhere(SQLite3Table table, int coordinateColumns,
            SQLite3ExpressionGenerator gen) {
        List<SQLite3Column> columns = table.getColumns();
        // Never trust the count past what the table can actually supply: column 1 + 2 * pair + 1 has
        // to exist, and a wrong count here aborts the whole test thread rather than one check.
        int nrPairs = Math.min(coordinateColumns, columns.size() - 1) / 2;
        int pair = (int) Randomly.getNotCachedInteger(0, nrPairs);
        double pivot = rtreePivot();
        SQLite3Expression predicate;
        switch ((int) Randomly.getNotCachedInteger(0, nrPairs >= 2 ? 6 : 5)) {
            case 0: {
                // Point containment - the canonical R-Tree query, and the shape that produced six
                // distinct plans across its rewrites when this was measured by hand.
                predicate = rtreeAnd(
                        rtreeConstraint(columns, pair, false, pivot, BinaryComparisonOperator.SMALLER_EQUALS),
                        rtreeConstraint(columns, pair, true, pivot, BinaryComparisonOperator.GREATER_EQUALS));
                break;
            }
            case 1: {
                // Interval overlap: the same two constraints, but with a different bound on each.
                double lower = pivot - Randomly.getNotCachedInteger(1, 9);
                double upper = pivot + Randomly.getNotCachedInteger(1, 9);
                predicate = rtreeAnd(
                        rtreeConstraint(columns, pair, false, upper, BinaryComparisonOperator.SMALLER_EQUALS),
                        rtreeConstraint(columns, pair, true, lower, BinaryComparisonOperator.GREATER_EQUALS));
                break;
            }
            case 2: {
                // Strict bounds. RTREE_LT and RTREE_GT are separate opcodes from RTREE_LE/RTREE_GE
                // in the module's constraint program, and the rewrite rules move between them.
                predicate = rtreeAnd(rtreeConstraint(columns, pair, false, pivot, BinaryComparisonOperator.SMALLER),
                        rtreeConstraint(columns, pair, true, pivot, BinaryComparisonOperator.GREATER));
                break;
            }
            case 3: {
                // A single constraint, leaving the other coordinate to the post-filter.
                predicate = Randomly.getBoolean()
                        ? rtreeConstraint(columns, pair, false, pivot, BinaryComparisonOperator.SMALLER_EQUALS)
                        : rtreeConstraint(columns, pair, true, pivot, BinaryComparisonOperator.GREATER_EQUALS);
                break;
            }
            case 4: {
                // Wide open: true for any row, so the check does not depend on where the data
                // happens to sit, while the module still has two constraints to consume.
                predicate = rtreeAnd(
                        rtreeConstraint(columns, pair, false, RTREE_WIDE_BOUND,
                                BinaryComparisonOperator.SMALLER_EQUALS),
                        rtreeConstraint(columns, pair, true, -RTREE_WIDE_BOUND,
                                BinaryComparisonOperator.GREATER_EQUALS));
                break;
            }
            default: {
                // Two pairs at once - four terms, which is where the order the module receives them
                // in starts to matter.
                int other = (pair + 1) % nrPairs;
                predicate = rtreeAnd(
                        rtreeAnd(rtreeConstraint(columns, pair, false, pivot,
                                BinaryComparisonOperator.SMALLER_EQUALS),
                                rtreeConstraint(columns, pair, true, pivot,
                                        BinaryComparisonOperator.GREATER_EQUALS)),
                        rtreeAnd(rtreeConstraint(columns, other, false, RTREE_WIDE_BOUND,
                                BinaryComparisonOperator.SMALLER_EQUALS),
                                rtreeConstraint(columns, other, true, -RTREE_WIDE_BOUND,
                                        BinaryComparisonOperator.GREATER_EQUALS)));
                break;
            }
        }
        // An OR of two constraint lists is what SQLite turns into MULTI-INDEX OR: two independent
        // R-Tree scans feeding a rowset merge, which a single AND chain never reaches.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            double second = rtreePivot();
            predicate = rtreeOr(predicate,
                    rtreeAnd(rtreeConstraint(columns, pair, false, second, BinaryComparisonOperator.SMALLER_EQUALS),
                            rtreeConstraint(columns, pair, true, second, BinaryComparisonOperator.GREATER_EQUALS)));
        }
        // A term the module cannot consume keeps the "constraint plus post-filter" path in the mix,
        // and keeps some of the generator's own shapes in the R-Tree workload.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            predicate = rtreeAnd(predicate, gen.generateBooleanExpression());
        }
        return predicate;
    }

    /**
     * A value to constrain against. {@code SQLite3Provider#seedRtreeTable} fills the EGRAPH R-Tree
     * with intervals inside [-20, 24], so pivots in that band keep containment queries non-empty on
     * the one table the oracle can count on; the occasional far-out value exercises the path where
     * the module consumes a constraint that then matches nothing.
     */
    private static double rtreePivot() {
        if (Randomly.getBooleanWithSmallProbability()) {
            return Randomly.getNotCachedInteger(-1000, 1001);
        }
        double base = Randomly.getNotCachedInteger(-20, 25);
        return Randomly.getBoolean() ? base : base + 0.5;
    }

    /** Column {@code 1 + 2 * pair} is a pair's minimum, the one after it its maximum. */
    private static SQLite3Expression rtreeCoordinate(List<SQLite3Column> columns, int pair, boolean upperBound) {
        return new SQLite3ColumnName(columns.get(1 + 2 * pair + (upperBound ? 1 : 0)), null);
    }

    private static SQLite3Expression rtreeConstraint(List<SQLite3Column> columns, int pair, boolean upperBound,
            double value, BinaryComparisonOperator operator) {
        return new BinaryComparisonOperation(rtreeCoordinate(columns, pair, upperBound), rtreeLiteral(value),
                operator);
    }

    /**
     * Integer and real spellings of the same bound reach the module through different affinity
     * handling, and rtree_i32 stores what rtree keeps as a float, so both are worth emitting.
     */
    private static SQLite3Expression rtreeLiteral(double value) {
        if (value == Math.rint(value) && Randomly.getBoolean()) {
            return SQLite3Constant.createIntConstant((long) value);
        }
        return SQLite3Constant.createRealConstant(value);
    }

    private static SQLite3Expression rtreeAnd(SQLite3Expression left, SQLite3Expression right) {
        return new Sqlite3BinaryOperation(left, right, BinaryOperator.AND);
    }

    private static SQLite3Expression rtreeOr(SQLite3Expression left, SQLite3Expression right) {
        return new Sqlite3BinaryOperation(left, right, BinaryOperator.OR);
    }

    // Percentage of empty-probe base queries that are checked anyway instead of discarded. 0 restores
    // the previous always-discard behaviour. Default is a small sample so the single-side-empty
    // judgment starts producing data without materially moving throughput.
    private static final int EMPTY_BASE_CHECK_PERCENT = Integer
            .getInteger("egraph.emptyBaseCheckPercent", 10);

    private static final boolean AUTO_RESEARCH_GUIDED_SHAPES = Boolean
            .parseBoolean(System.getProperty("sqlite3.egraph.autoResearchGuidedShapes", "true"));
    private static final String AUTO_RESEARCH_RESULTS = System.getProperty("sqlite3.egraph.autoResearchResults",
            "D:\\sqlancer\\coverage\\sqlite\\auto-research-from-long-20260818-130511\\auto-research-results.csv");
    /**
     * Every wrapper shape a check can put around its base query, in one place. There used to be two lists: this one and
     * a corpus one that was the same minus four entries. A shape added to one was absent from the other, silently.
     *
     * <p>
     * The four that differed are the ones whose SQL names the target table's own columns, which a corpus query's schema
     * need not have - so they are named as such below and left out of the corpus pool rather than left out of a copy.
     * </p>
     *
     * <p>
     * A shape listed more than once is picked that much more often; PLAIN is listed three times, as it was before. The
     * corpus pool kept PLAIN twice rather than three times, a difference of about one check in eighty that is not worth a
     * second list.
     * </p>
     */
    private static final List<EGraphCoverageShape> COVERAGE_SHAPE_POOL = List.of(
            EGraphCoverageShape.PLAIN, EGraphCoverageShape.PLAIN, EGraphCoverageShape.PLAIN, EGraphCoverageShape.DISTINCT, EGraphCoverageShape.GROUP_BY,
            EGraphCoverageShape.DERIVED_TABLE, EGraphCoverageShape.COMPOUND_UNION_ALL,
            EGraphCoverageShape.CORRELATED_SUBQUERY, EGraphCoverageShape.MATERIALIZED_CTE,
            EGraphCoverageShape.AUTOMATIC_INDEX, EGraphCoverageShape.CO_ROUTINE, EGraphCoverageShape.MULTI_INDEX_OR,
            EGraphCoverageShape.NOT_MATERIALIZED_CTE, EGraphCoverageShape.LIMIT_OFFSET,
            EGraphCoverageShape.WHERE_CASE_TRUE, EGraphCoverageShape.WHERE_FUNCTION_TRUE,
            EGraphCoverageShape.WHERE_COLLATE_TRUE, EGraphCoverageShape.SCALAR_SUBQUERY,
            EGraphCoverageShape.WINDOW_COUNT, EGraphCoverageShape.VALUES_CTE_JOIN, EGraphCoverageShape.RECURSIVE_CTE,
            EGraphCoverageShape.FTS5_MATCH_CONTEXT, EGraphCoverageShape.FTS5_DEEP_CONTEXT,
            EGraphCoverageShape.RTREE_CONTEXT, EGraphCoverageShape.RTREE_DEEP_CONTEXT,
            EGraphCoverageShape.DBSTAT_CONTEXT, EGraphCoverageShape.VIEW_TRIGGER_FK_CONTEXT,
            EGraphCoverageShape.ANALYZE_INDEX_CONTEXT, EGraphCoverageShape.JSON_CONTEXT,
            EGraphCoverageShape.TX_WAL_VACUUM_CONTEXT, EGraphCoverageShape.AUTO_VACUUM_INTEGRITY_CONTEXT,
            EGraphCoverageShape.ATTACH_VACUUM_WAL_CONTEXT, EGraphCoverageShape.ALTER_INDEX_ANALYZE_CONTEXT,
            EGraphCoverageShape.SELECT_WHERE_STRESS_CONTEXT, EGraphCoverageShape.EXPR_STRESS_CONTEXT,
            EGraphCoverageShape.WINDOW_STRESS_CONTEXT, EGraphCoverageShape.RESOLVE_STRESS_CONTEXT,
            EGraphCoverageShape.SORTER_STRESS_CONTEXT, EGraphCoverageShape.JOIN_OPTIMIZER_CONTEXT,
            EGraphCoverageShape.ALTER_FK_STRESS_CONTEXT, EGraphCoverageShape.INTEGRITY_CHECK_CONTEXT,
            EGraphCoverageShape.SCALAR_AGGREGATE_CONTEXT, EGraphCoverageShape.VIRTUAL_TABLE_UPDATE_CONTEXT,
            EGraphCoverageShape.FTS5_SECURE_DELETE_CONTEXT, EGraphCoverageShape.VIRTUAL_TABLE_SAVEPOINT_CONTEXT,
            EGraphCoverageShape.JSONB_STRESS_CONTEXT, EGraphCoverageShape.XFER_OPTIMIZATION_CONTEXT,
            EGraphCoverageShape.MULTI_SELECT_ORDER_BY_CONTEXT, EGraphCoverageShape.ROW_VALUE_CONTEXT,
            EGraphCoverageShape.AGGREGATE_ORDER_BY_CONTEXT, EGraphCoverageShape.WINDOW_RANGE_FULLSCAN_CONTEXT,
            EGraphCoverageShape.SCALAR_FUNCTION_BATTERY_CONTEXT, EGraphCoverageShape.DATE_MODIFIER_BATTERY_CONTEXT,
            EGraphCoverageShape.WINDOW_FUNCTION_BATTERY_CONTEXT, EGraphCoverageShape.FTS4_MATCH_CONTEXT,
            EGraphCoverageShape.FTS4_AUX_CONTEXT, EGraphCoverageShape.BLOOM_FILTER_CONTEXT,
            EGraphCoverageShape.MULTI_INDEX_OR_ROWSET_CONTEXT, EGraphCoverageShape.INDEX_FUNCTION_VALUE_CONTEXT,
            EGraphCoverageShape.SORTER_DEEP_MERGE_CONTEXT, EGraphCoverageShape.FTS5_DEEP_QUERY_CONTEXT,
            EGraphCoverageShape.FTS5_AUX_DEEP_CONTEXT, EGraphCoverageShape.FTS4_DEEP_SEGMENT_CONTEXT,
            EGraphCoverageShape.FTS5_VARIANT_CONFIG_CONTEXT, EGraphCoverageShape.PRAGMA_VTAB_CONTEXT,
            EGraphCoverageShape.FTS4_MERGE_LCS_CONTEXT, EGraphCoverageShape.FTS3_TOKENIZE_TABLE_CONTEXT,
            EGraphCoverageShape.FTS5_TOMBSTONE_CONTEXT, EGraphCoverageShape.FTS5_TOKENIZER_VARIANT_CONTEXT,
            EGraphCoverageShape.SQL_SYNTAX_BATTERY_CONTEXT, EGraphCoverageShape.COLD_FUNCTION_BATTERY_CONTEXT,
            EGraphCoverageShape.COLD_DDL_TRIGGER_CONTEXT);

    /** Shapes whose SQL refers to the target table's columns, so they only fit a query this run built. */
    private static final java.util.Set<EGraphCoverageShape> SHAPES_NEEDING_TARGET_COLUMNS = java.util.EnumSet.of(
            EGraphCoverageShape.GROUP_BY, EGraphCoverageShape.CORRELATED_SUBQUERY,
            EGraphCoverageShape.AUTOMATIC_INDEX, EGraphCoverageShape.MULTI_INDEX_OR);

    private static final List<EGraphCoverageShape> CORPUS_COVERAGE_SHAPE_POOL = COVERAGE_SHAPE_POOL.stream()
            .filter(shape -> !SHAPES_NEEDING_TARGET_COLUMNS.contains(shape))
            .collect(java.util.stream.Collectors.toUnmodifiableList());


    private static final List<EGraphCoverageShape> AUTO_RESEARCH_SHAPES = loadAutoResearchShapes();

    private static EGraphCoverageShape chooseEGraphCoverageShape() {
        EGraphCoverageShape autoShape = chooseAutoResearchShape();
        if (autoShape != null && Randomly.fromOptions(true, true, false)) {
            return autoShape;
        }
        return Randomly.fromList(COVERAGE_SHAPE_POOL);
    }

    private static EGraphCoverageShape chooseAutoResearchShape() {
        if (!AUTO_RESEARCH_GUIDED_SHAPES || AUTO_RESEARCH_SHAPES.isEmpty()) {
            return null;
        }
        return Randomly.fromList(AUTO_RESEARCH_SHAPES);
    }

    private static List<EGraphCoverageShape> loadAutoResearchShapes() {
        List<EGraphCoverageShape> shapes = new ArrayList<>();
        Path path = Path.of(AUTO_RESEARCH_RESULTS);
        if (!Files.isRegularFile(path)) {
            return shapes;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int rank = 0;
            for (String line : lines) {
                if (line.startsWith("\"Name\"") || line.isBlank()) {
                    continue;
                }
                String name = readFirstCsvColumn(line);
                List<EGraphCoverageShape> mapped = mapAutoResearchCaseToShapes(name);
                if (mapped.isEmpty()) {
                    continue;
                }
                int weight = Math.max(1, 6 - rank);
                for (int i = 0; i < weight; i++) {
                    shapes.addAll(mapped);
                }
                rank++;
                if (rank >= 8) {
                    break;
                }
            }
        } catch (IOException ignored) {
            return new ArrayList<>();
        }
        return shapes;
    }

    private static String readFirstCsvColumn(String line) {
        if (line.startsWith("\"")) {
            StringBuilder result = new StringBuilder();
            for (int i = 1; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    result.append('"');
                    i++;
                } else if (c == '"') {
                    return result.toString();
                } else {
                    result.append(c);
                }
            }
        }
        int comma = line.indexOf(',');
        return comma >= 0 ? line.substring(0, comma).trim() : line.trim();
    }

    private static List<EGraphCoverageShape> mapAutoResearchCaseToShapes(String name) {
        switch (name) {
            case "alter_index_analyze":
                return List.of(EGraphCoverageShape.ALTER_INDEX_ANALYZE_CONTEXT,
                        EGraphCoverageShape.ANALYZE_INDEX_CONTEXT, EGraphCoverageShape.AUTOMATIC_INDEX,
                        EGraphCoverageShape.MULTI_INDEX_OR, EGraphCoverageShape.SQL_SYNTAX_BATTERY_CONTEXT);
            case "json_table_valued":
                return List.of(EGraphCoverageShape.JSON_CONTEXT, EGraphCoverageShape.JSONB_STRESS_CONTEXT,
                        EGraphCoverageShape.EXPR_STRESS_CONTEXT);
            case "jsonb_stress":
                return List.of(EGraphCoverageShape.JSONB_STRESS_CONTEXT, EGraphCoverageShape.JSON_CONTEXT,
                        EGraphCoverageShape.EXPR_STRESS_CONTEXT);
            case "recursive_window_aggregate":
                return List.of(EGraphCoverageShape.RECURSIVE_CTE, EGraphCoverageShape.WINDOW_STRESS_CONTEXT,
                        EGraphCoverageShape.WINDOW_COUNT, EGraphCoverageShape.GROUP_BY);
            case "dbstat_storage":
                return List.of(EGraphCoverageShape.DBSTAT_CONTEXT, EGraphCoverageShape.ANALYZE_INDEX_CONTEXT);
            case "trigger_view_fk":
                return List.of(EGraphCoverageShape.VIEW_TRIGGER_FK_CONTEXT, EGraphCoverageShape.RESOLVE_STRESS_CONTEXT);
            case "returning_upsert":
                // UPSERT and RETURNING are grammar forms, not data shapes.
                return List.of(EGraphCoverageShape.SQL_SYNTAX_BATTERY_CONTEXT,
                        EGraphCoverageShape.ALTER_INDEX_ANALYZE_CONTEXT,
                        EGraphCoverageShape.SELECT_WHERE_STRESS_CONTEXT);
            case "fts5":
                return List.of(EGraphCoverageShape.FTS5_DEEP_CONTEXT, EGraphCoverageShape.FTS5_MATCH_CONTEXT,
                        EGraphCoverageShape.FTS5_TOMBSTONE_CONTEXT,
                        EGraphCoverageShape.FTS5_TOKENIZER_VARIANT_CONTEXT);
            case "fts5_secure_vocab":
                return List.of(EGraphCoverageShape.FTS5_DEEP_CONTEXT, EGraphCoverageShape.FTS5_SECURE_DELETE_CONTEXT,
                        EGraphCoverageShape.FTS5_MATCH_CONTEXT, EGraphCoverageShape.FTS5_TOMBSTONE_CONTEXT);
            case "rtree":
                return List.of(EGraphCoverageShape.RTREE_DEEP_CONTEXT, EGraphCoverageShape.RTREE_CONTEXT);
            case "tx_wal_vacuum":
                return List.of(EGraphCoverageShape.AUTO_VACUUM_INTEGRITY_CONTEXT,
                        EGraphCoverageShape.TX_WAL_VACUUM_CONTEXT);
            case "attach_vacuum_wal":
                return List.of(EGraphCoverageShape.ATTACH_VACUUM_WAL_CONTEXT,
                        EGraphCoverageShape.TX_WAL_VACUUM_CONTEXT);
            case "xfer_optimization":
                return List.of(EGraphCoverageShape.XFER_OPTIMIZATION_CONTEXT);
            case "multi_select_order_by":
                return List.of(EGraphCoverageShape.MULTI_SELECT_ORDER_BY_CONTEXT,
                        EGraphCoverageShape.COMPOUND_UNION_ALL);
            case "join_optimizer":
                return List.of(EGraphCoverageShape.JOIN_OPTIMIZER_CONTEXT,
                        EGraphCoverageShape.SELECT_WHERE_STRESS_CONTEXT, EGraphCoverageShape.MULTI_INDEX_OR);
            case "alter_fk_stress":
                return List.of(EGraphCoverageShape.ALTER_FK_STRESS_CONTEXT,
                        EGraphCoverageShape.VIEW_TRIGGER_FK_CONTEXT, EGraphCoverageShape.ALTER_INDEX_ANALYZE_CONTEXT);
            case "integrity_check_stress":
                return List.of(EGraphCoverageShape.AUTO_VACUUM_INTEGRITY_CONTEXT,
                        EGraphCoverageShape.INTEGRITY_CHECK_CONTEXT,
                        EGraphCoverageShape.TX_WAL_VACUUM_CONTEXT, EGraphCoverageShape.DBSTAT_CONTEXT);
            case "scalar_aggregate_stress":
                return List.of(EGraphCoverageShape.SCALAR_AGGREGATE_CONTEXT, EGraphCoverageShape.EXPR_STRESS_CONTEXT);
            case "virtual_table_update_stress":
                return List.of(EGraphCoverageShape.VIRTUAL_TABLE_SAVEPOINT_CONTEXT,
                        EGraphCoverageShape.VIRTUAL_TABLE_UPDATE_CONTEXT, EGraphCoverageShape.RTREE_DEEP_CONTEXT,
                        EGraphCoverageShape.RTREE_CONTEXT, EGraphCoverageShape.FTS5_DEEP_CONTEXT,
                        EGraphCoverageShape.FTS5_SECURE_DELETE_CONTEXT);
            default:
                return List.of();
        }
    }

    private static EGraphMetamorphicOracle.GeneratedQuery createCorpusGeneratedQuery(SQLite3GlobalState state,
            List<CorpusQueryInput> corpusInputs) throws Exception {
        List<CorpusQueryInput> candidates = new ArrayList<>(corpusInputs);
        int attempts = Math.min(Math.max(1, MAX_CORPUS_ATTEMPTS_PER_CHECK), candidates.size());
        while (!candidates.isEmpty() && attempts > 0) {
            CorpusQueryInput candidate = Randomly.fromList(candidates);
            candidates.remove(candidate);
            String inputKey = getCorpusInputKey(candidate);
            if (BAD_CORPUS_INPUTS.contains(inputKey)) {
                continue;
            }
            try {
                EGraphSqlCoverage.trace("corpus-candidate start source=" + getCorpusSourceName(candidate)
                        + " setupStatements=" + candidate.getSetupStatements().size() + " query="
                        + shortenForTrace(candidate.getQuery()));
                return createSingleCorpusGeneratedQuery(state, candidate);
            } catch (IgnoreMeException e) {
                EGraphSqlCoverage.trace("corpus-candidate rejected source=" + getCorpusSourceName(candidate)
                        + " query=" + shortenForTrace(candidate.getQuery()));
                BAD_CORPUS_INPUTS.add(inputKey);
                try {
                    resetCorpusSchema(state);
                } catch (Exception ignored) {
                }
                attempts--;
            }
        }
        throw new IgnoreMeException();
    }

    private static EGraphMetamorphicOracle.GeneratedQuery createSingleCorpusGeneratedQuery(SQLite3GlobalState state,
            CorpusQueryInput selectedInput) throws Exception {
        boolean replayedCorpusSetup = false;
        if (selectedInput.hasSetupStatements() && REPLAY_CORPUS_CASE_SETUP) {
            EGraphSqlCoverage.trace("corpus-setup start source=" + getCorpusSourceName(selectedInput)
                    + " statements=" + selectedInput.getSetupStatements().size());
            if (!replayCorpusSetupForQuery(state, selectedInput)) {
                EGraphSqlCoverage.trace("corpus-setup failed source=" + getCorpusSourceName(selectedInput));
                throw new IgnoreMeException();
            }
            EGraphSqlCoverage.trace("corpus-setup done source=" + getCorpusSourceName(selectedInput));
            replayedCorpusSetup = true;
        }
        String rewriteQuery = selectedInput.getQuery();
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            throw new IgnoreMeException();
        }
        if (!replayedCorpusSetup && CORPUS_SETUP_ONLY) {
            EGraphSqlCoverage.trace("corpus-setup-only skip source=" + getCorpusSourceName(selectedInput)
                    + " reason=no-setup-statements");
            throw new IgnoreMeException();
        }
        if (!replayedCorpusSetup) {
            SQLite3Provider.ensureEGraphRandomDatabase(state);
        }
        List<SQLite3Table> tables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && !t.isVirtual() && !t.getColumns().isEmpty()
                        && t.getNrRows(state) > 0)
                .collect(java.util.stream.Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        SQLite3Table chosen = Randomly.fromList(tables);
        AbstractTables<SQLite3Table, SQLite3Column> targetTables = new AbstractTables<>(
                java.util.Collections.singletonList(chosen));
        if (!replayedCorpusSetup) {
            if (EGRAPH_SELECT_ONLY_TEMPLATES) {
                rewriteQuery = mapSelectOnlyCorpusQuery(rewriteQuery, targetTables);
            }
        }
        EGraphSqlCoverage.trace("corpus-base-nonempty-probe start source=" + getCorpusSourceName(selectedInput)
                + " query=" + shortenForTrace(rewriteQuery));
        ProbeOutcome baseOutcome = probeQuery(state, rewriteQuery);
        if (baseOutcome != ProbeOutcome.ROWS) {
            // A base query returning nothing was dropped here because its variants are then
            // usually empty too, and an empty pair can never disagree. But a query that returns
            // no rows where it should return some is exactly what a large share of the reported
            // correctness bugs look like, and an empty original against a non-empty variant is
            // what hasSingleSideEmptyMismatch reports. Keeping the case costs one wasted check
            // when the variants really are all empty.
            // Only a query that ran and matched nothing is worth keeping. One that failed to run
            // carries no judgment, and running it again through the oracle turns the failure into
            // an unexpected-error assertion.
            boolean keep = ALLOW_EMPTY_BASE_QUERY && baseOutcome == ProbeOutcome.EMPTY;
            EGraphSqlCoverage.trace("corpus-base-nonempty-probe " + baseOutcome.name().toLowerCase(Locale.ROOT)
                    + " source=" + getCorpusSourceName(selectedInput) + " query=" + shortenForTrace(rewriteQuery)
                    + " kept=" + keep);
            if (!keep) {
                throw new IgnoreMeException();
            }
        }
        EGraphSqlCoverage.trace("corpus-base-nonempty-probe done source=" + getCorpusSourceName(selectedInput));
        EGraphCoverageContext originalContext = createEGraphCoverageContext(state, targetTables,
                chooseEGraphCorpusExecutionCoverageShape());
        String originalQuery = wrapEGraphCoverageShape(rewriteQuery, originalContext);
        EGraphSqlCoverage.trace("corpus-wrapper-nonempty-probe start source=" + getCorpusSourceName(selectedInput)
                + " query=" + shortenForTrace(originalQuery));
        if (!queryProducesRows(state, originalQuery)) {
            originalContext = createEGraphCoverageContext(null, targetTables, EGraphCoverageShape.PLAIN);
            originalQuery = rewriteQuery;
            EGraphSqlCoverage.trace("corpus-wrapper-nonempty-probe fallback-plain source="
                    + getCorpusSourceName(selectedInput));
        } else {
            EGraphSqlCoverage.trace("corpus-wrapper-nonempty-probe done source=" + getCorpusSourceName(selectedInput));
        }
        EGraphSqlCoverage.recordWrapperShape(replayedCorpusSetup ? "CORPUS_INPUT" : "CORPUS_SELECT_TEMPLATE");
        EGraphSqlCoverage.recordCheck();
        EGraphSqlCoverage.analyzeWhere(rewriteQuery);
        EGraphCoverageContext finalOriginalContext = originalContext;
        String querySource = replayedCorpusSetup ? getCorpusSourceName(selectedInput)
                : getNoSetupCorpusSourceName(selectedInput);
        return new EGraphMetamorphicOracle.GeneratedQuery(originalQuery, rewriteQuery,
                variant -> wrapEGraphCoverageShape(variant, finalOriginalContext),
                querySource, selectedInput.rowsDetermined() ? TruncationKind.NONE : truncationKind(originalQuery));
    }

    /**
     * Whether a query keeps only part of its rows without saying which ones.
     *
     * <p>
     * Only for text this run did not build: the generated shapes put a total order over every LIMIT, but a corpus case
     * carries whatever it was written with, and the corpus on disk was written before that was true. A LIMIT of -1 keeps
     * everything, and a LIMIT of 1 closing a subquery right away is the {@code EXISTS (... LIMIT 1)} the wrappers emit,
     * where which row is found does not reach the result. Anything else can truncate.
     * </p>
     */
    static TruncationKind truncationKind(String sql) {
        if (sql == null) {
            return TruncationKind.NONE;
        }
        TruncationKind worst = TruncationKind.NONE;
        java.util.regex.Matcher limits = TRUNCATING_LIMIT.matcher(sql);
        while (limits.find()) {
            long rows = Long.parseLong(limits.group(1));
            if (rows < 0) {
                continue;
            }
            if (rows <= 1 && limits.group(2) != null) {
                continue;
            }
            // A LIMIT that closes a subquery has the rest of the statement built on top of whichever
            // rows it kept, so the count is as arbitrary as the rows. A LIMIT the statement ends on
            // keeps min(limit, matching rows), and two spellings that match the same rows agree on
            // that number whichever ones they visited first.
            if (limits.group(2) != null || !isLastClause(sql, limits.end())) {
                return TruncationKind.NESTED;
            }
            worst = TruncationKind.TRAILING;
        }
        return worst;
    }

    private static boolean isLastClause(String sql, int afterLimit) {
        String rest = sql.substring(afterLimit).trim();
        if (rest.startsWith(";")) {
            rest = rest.substring(1).trim();
        }
        if (rest.isEmpty()) {
            return true;
        }
        // OFFSET is the only thing that may follow and still leave the count determined.
        java.util.regex.Matcher offset = TRAILING_OFFSET.matcher(rest);
        return offset.matches();
    }

    private static final java.util.regex.Pattern TRAILING_OFFSET = java.util.regex.Pattern
            .compile("OFFSET\\s+\\d+\\s*;?", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * How a query's LIMIT affects what can be compared.
     *
     * <p>
     * Found by a two-hour run, which reported a mismatch of 4 rows against 5 for two spellings that match exactly the
     * same 13 rows when the LIMIT is removed - measured on both 3.53.4 and trunk. The LIMIT sat inside a derived table
     * that the wrapper then filtered, so which ten of the thirteen it kept decided the final count, and the row count
     * this class had been falling back on was no more stable than the rows.
     * </p>
     */
    public enum TruncationKind {
        /** Nothing is truncated, or only a LIMIT 1 closing a subquery: compare the rows. */
        NONE,
        /** The statement ends on the LIMIT: the rows are arbitrary but their number is not. */
        TRAILING,
        /** Something is built on top of the truncated rows: nothing about the result is comparable. */
        NESTED
    }

    private static final java.util.regex.Pattern TRUNCATING_LIMIT = java.util.regex.Pattern
            .compile("\\bLIMIT\\s+(-?\\d+)\\s*(\\))?", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static EGraphBaseQuery buildEGraphBaseQuery(SQLite3Select select, SQLite3Expression whereCondition,
            AbstractTables<SQLite3Table, SQLite3Column> targetTables) {
        List<SQLite3Column> columns = targetTables.getColumns().stream()
                .filter(SQLite3OracleFactory::hasUsableIdentifier)
                .collect(java.util.stream.Collectors.toList());
        EGraphBaseQueryShape shape = chooseEGraphBaseQueryShape(columns);
        resetEGraphBaseQuery(select, whereCondition);
        switch (shape) {
            case COLUMN_PROJECTION:
                select.setFetchColumns(columnProjection(columns));
                break;
            case DISTINCT_COLUMNS:
                select.setFromOptions(SQLite3Select.SelectType.DISTINCT);
                select.setFetchColumns(columnProjection(columns));
                break;
            case ORDER_BY_COLUMN:
                select.setOrderByClauses(orderByColumn(columns));
                break;
            case LIMIT_10:
                select.setOrderByClauses(orderByEveryColumn(columns, null));
                select.setLimitClause(SQLite3Constant.createIntConstant(10));
                break;
            case ORDER_BY_COLUMN_LIMIT_10:
                select.setOrderByClauses(orderByEveryColumn(columns, Randomly.fromList(columns)));
                select.setLimitClause(SQLite3Constant.createIntConstant(10));
                break;
            case PLAIN:
                break;
            default:
                throw new AssertionError(shape);
        }
        return new EGraphBaseQuery(select.asString(), "BASE_" + shape.name());
    }

    private static void resetEGraphBaseQuery(SQLite3Select select, SQLite3Expression whereCondition) {
        select.setFromOptions(SQLite3Select.SelectType.ALL);
        select.setFetchColumns(Arrays.asList(SQLite3ColumnName.createDummy("*")));
        select.setWhereClause(whereCondition);
        select.setGroupByClause(List.of());
        select.setHavingClause(null);
        select.setOrderByClauses(List.of());
        select.setLimitClause(null);
        select.setOffsetClause(null);
    }

    /**
     * There used to be a second, narrower set of shapes for the case where an index could make the two sides visit rows
     * in different orders. It is no longer needed from either end: the comparison is a multiset one whatever the query
     * says, so an ORDER BY cannot turn it order-sensitive, and every LIMIT shape now carries a total order, so a plan
     * change cannot alter which rows it keeps. Both guards moved to where the problem actually was.
     */
    private static EGraphBaseQueryShape chooseEGraphBaseQueryShape(List<SQLite3Column> columns) {
        if (!EGRAPH_BASE_SKELETONS || columns.isEmpty()) {
            return EGraphBaseQueryShape.PLAIN;
        }
        return Randomly.fromOptions(EGraphBaseQueryShape.PLAIN, EGraphBaseQueryShape.PLAIN,
                EGraphBaseQueryShape.COLUMN_PROJECTION, EGraphBaseQueryShape.DISTINCT_COLUMNS,
                EGraphBaseQueryShape.ORDER_BY_COLUMN, EGraphBaseQueryShape.LIMIT_10,
                EGraphBaseQueryShape.ORDER_BY_COLUMN_LIMIT_10);
    }

    private static List<SQLite3Expression> columnProjection(List<SQLite3Column> columns) {
        if (columns.isEmpty()) {
            return Arrays.asList(SQLite3ColumnName.createDummy("*"));
        }
        List<SQLite3Column> selected = new ArrayList<>(Randomly.nonEmptySubset(columns));
        if (selected.size() > 3) {
            selected = new ArrayList<>(selected.subList(0, 3));
        }
        return selected.stream().map(c -> new SQLite3ColumnName(c, null))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * An ORDER BY over every projected column, so that a LIMIT has a defined answer.
     *
     * <p>
     * A LIMIT without one returns whichever rows the plan happened to visit first, and the whole point of a rewrite is
     * to change the plan - so the two sides returned different rows and the oracle called it a defect. Measured on a
     * two-minute run right after findings stopped being swallowed: all 39 of them were this, every single one. Ordering
     * on one column is not enough either, because ties are broken by the plan again.
     * </p>
     *
     * <p>
     * Ties can only remain between rows equal in every projected column, and those are the same row as far as the
     * comparison is concerned, so this makes the result a defined multiset. {@code leadingColumn}, when given, is sorted
     * on first: an index on it can then satisfy the sort, which is a plan worth reaching.
     * </p>
     */
    private static List<SQLite3Expression> orderByEveryColumn(List<SQLite3Column> columns,
            SQLite3Column leadingColumn) {
        if (columns.isEmpty()) {
            return List.of();
        }
        Ordering ordering = Randomly.fromOptions(Ordering.ASC, Ordering.DESC);
        List<SQLite3Expression> terms = new ArrayList<>();
        if (leadingColumn != null) {
            terms.add(new SQLite3OrderingTerm(new SQLite3ColumnName(leadingColumn, null), ordering));
        }
        for (SQLite3Column column : columns) {
            if (column != leadingColumn) {
                terms.add(new SQLite3OrderingTerm(new SQLite3ColumnName(column, null), ordering));
            }
        }
        return terms;
    }

    private static List<SQLite3Expression> orderByColumn(List<SQLite3Column> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        Ordering ordering = Randomly.fromOptions(Ordering.ASC, Ordering.DESC);
        SQLite3Column column = Randomly.fromList(columns);
        return Arrays.asList(new SQLite3OrderingTerm(new SQLite3ColumnName(column, null), ordering));
    }

    private static String mapSelectOnlyCorpusQuery(String query,
            AbstractTables<SQLite3Table, SQLite3Column> targetTables) {
        String stripped = stripTrailingSemicolon(query);
        int fromIdx = findTopLevelKeyword(stripped, "FROM", 0);
        int whereIdx = fromIdx < 0 ? -1 : findTopLevelKeyword(stripped, "WHERE", fromIdx + 4);
        if (fromIdx < 0 || whereIdx < 0 || whereIdx <= fromIdx) {
            throw new IgnoreMeException();
        }
        SQLite3Table targetTable = targetTables.getTables().get(0);
        List<SQLite3Column> targetColumns = targetTables.getColumns().stream()
                .filter(SQLite3OracleFactory::hasUsableIdentifier)
                .collect(java.util.stream.Collectors.toList());
        if (targetColumns.isEmpty() || !hasUsableIdentifier(targetTable.getName())) {
            throw new IgnoreMeException();
        }
        SelectOnlyFrom from = parseSelectOnlyFrom(stripped.substring(fromIdx + 4, whereIdx).trim());
        if (from == null) {
            throw new IgnoreMeException();
        }
        SelectOnlyMapper mapper = new SelectOnlyMapper(targetColumns);
        Set<String> sourceQualifiers = new HashSet<>();
        sourceQualifiers.add(normalizeIdentifier(from.tableName));
        if (from.alias != null && !from.alias.isBlank()) {
            sourceQualifiers.add(normalizeIdentifier(from.alias));
        }
        String targetTableName = quoteIdentifier(targetTable.getName());
        String projection = rewriteSelectOnlyIdentifiers(stripped.substring(0, fromIdx), mapper, sourceQualifiers,
                targetTableName);
        String suffix = rewriteSelectOnlyIdentifiers(stripped.substring(whereIdx), mapper, sourceQualifiers,
                targetTableName);
        String mapped = projection + "FROM " + targetTableName + " " + suffix;
        if (mapper.mappedColumnCount() == 0) {
            throw new IgnoreMeException();
        }
        return mapped;
    }

    private static SelectOnlyFrom parseSelectOnlyFrom(String fromClause) {
        if (fromClause == null || fromClause.isBlank() || fromClause.contains(",")
                || fromClause.contains("(") || fromClause.contains(")")) {
            return null;
        }
        String normalized = fromClause.toUpperCase(Locale.ROOT);
        if (normalized.contains(" JOIN ") || normalized.contains(" INDEXED ") || normalized.contains(" NOT INDEXED ")) {
            return null;
        }
        String[] parts = fromClause.trim().split("\\s+");
        if (parts.length == 1) {
            return new SelectOnlyFrom(unquoteIdentifier(parts[0]), null);
        }
        if (parts.length == 2) {
            return new SelectOnlyFrom(unquoteIdentifier(parts[0]), unquoteIdentifier(parts[1]));
        }
        if (parts.length == 3 && "AS".equalsIgnoreCase(parts[1])) {
            return new SelectOnlyFrom(unquoteIdentifier(parts[0]), unquoteIdentifier(parts[2]));
        }
        return null;
    }

    private static int findTopLevelKeyword(String sql, String keyword, int start) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = Math.max(0, start); i <= sql.length() - keyword.length(); i++) {
            char ch = sql.charAt(i);
            if (inSingle) {
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }
            if (inDouble) {
                if (ch == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inDouble = false;
                    }
                }
                continue;
            }
            if (ch == '\'') {
                inSingle = true;
                continue;
            }
            if (ch == '"') {
                inDouble = true;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && sql.regionMatches(true, i, keyword, 0, keyword.length())) {
                boolean beforeOk = i == 0 || !isIdentifierPart(sql.charAt(i - 1));
                int after = i + keyword.length();
                boolean afterOk = after >= sql.length() || !isIdentifierPart(sql.charAt(after));
                if (beforeOk && afterOk) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String rewriteSelectOnlyIdentifiers(String sql, SelectOnlyMapper mapper, Set<String> sourceQualifiers,
            String targetTableName) {
        StringBuilder out = new StringBuilder(sql.length() + 32);
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (inSingle) {
                out.append(ch);
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (inDouble) {
                out.append(ch);
                if (ch == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        out.append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    inDouble = false;
                }
                i++;
                continue;
            }
            if (ch == '\'') {
                inSingle = true;
                out.append(ch);
                i++;
                continue;
            }
            if (ch == '"') {
                inDouble = true;
                out.append(ch);
                i++;
                continue;
            }
            if (!isIdentifierStart(ch)) {
                out.append(ch);
                i++;
                continue;
            }
            int start = i;
            i++;
            while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
                i++;
            }
            String token = sql.substring(start, i);
            if (isBlobLiteralPrefix(sql, token, i)) {
                out.append(token);
                continue;
            }
            int next = skipWhitespace(sql, i);
            String normalized = normalizeIdentifier(token);
            if (next < sql.length() && sql.charAt(next) == '.' && sourceQualifiers.contains(normalized)) {
                out.append(targetTableName);
                continue;
            }
            if (isSqlKeywordOrFunction(token, next < sql.length() ? sql.charAt(next) : '\0')
                    || sourceQualifiers.contains(normalized)) {
                out.append(token);
                continue;
            }
            out.append(mapper.map(token));
        }
        return out.toString();
    }

    private static boolean isBlobLiteralPrefix(String sql, String token, int nextIndex) {
        return token.length() == 1 && ("x".equals(token) || "X".equals(token))
                && nextIndex < sql.length() && sql.charAt(nextIndex) == '\'';
    }

    private static int skipWhitespace(String sql, int index) {
        int i = index;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private static String normalizeIdentifier(String identifier) {
        return unquoteIdentifier(identifier).toUpperCase(Locale.ROOT);
    }

    private static String unquoteIdentifier(String identifier) {
        String result = identifier == null ? "" : identifier.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                return result.substring(1, result.length() - 1);
            }
        }
        return result;
    }

    private static boolean isSqlKeywordOrFunction(String token, char nextNonWhitespace) {
        String upper = token.toUpperCase(Locale.ROOT);
        if (SQL_TEMPLATE_KEYWORDS.contains(upper)) {
            return true;
        }
        return nextNonWhitespace == '(' && SQL_TEMPLATE_FUNCTIONS.contains(upper);
    }

    private static final Set<String> SQL_TEMPLATE_KEYWORDS = Set.of("SELECT", "ALL", "DISTINCT", "FROM", "WHERE",
            "ORDER", "BY", "LIMIT", "OFFSET", "ASC", "DESC", "NULL", "TRUE", "FALSE", "IS", "NOT", "AND", "OR",
            "BETWEEN", "LIKE", "GLOB", "ESCAPE", "IN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "AS",
            "COLLATE", "BINARY", "NOCASE", "RTRIM", "CAST", "ROWID", "OID", "_ROWID_");

    private static final Set<String> SQL_TEMPLATE_FUNCTIONS = Set.of("ABS", "COALESCE", "GLOB", "IFNULL", "INSTR",
            "LENGTH", "LIKE", "LIKELIHOOD", "LIKELY", "LOWER", "LTRIM", "MAX", "MIN", "NULLIF", "PRINTF", "QUOTE",
            "ROUND", "RTRIM", "SOUNDEX", "SUBSTR", "SUM", "COUNT", "TOTAL", "AVG", "TYPEOF", "UNICODE", "UNLIKELY",
            "UPPER", "DATE", "TIME", "DATETIME", "JULIANDAY", "STRFTIME", "JSON", "JSON_ARRAY",
            "JSON_ARRAY_LENGTH", "JSON_EXTRACT", "JSON_INSERT", "JSON_OBJECT", "JSON_PATCH", "JSON_REMOVE",
            "JSON_TYPE", "JSON_VALID", "JSON_QUOTE", "BASE64", "BASE85");

    private static String getCorpusInputKey(CorpusQueryInput input) {
        return input.getSourceName() + "\n" + input.getQuery();
    }

    private static String getCorpusSourceName(CorpusQueryInput input) {
        String sourceName = input.getSourceName();
        if (sourceName == null) {
            return "CORPUS_REPLAY_SETUP";
        }
        String normalized = sourceName.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (normalized.endsWith("high-coverage-egraph-corpus.sql")) {
            return "CORPUS_HIGH_COVERAGE";
        }
        if (normalized.endsWith("sqlite-official-test-corpus.sql")
                || normalized.endsWith("sqlite-official-egraph-filtered-corpus.sql")) {
            return "CORPUS_OFFICIAL";
        }
        if (normalized.endsWith("sqlite-official-select-only-corpus.sql")
                || normalized.endsWith("sqlite-official-select-only-variant-ready-corpus.sql")) {
            return "CORPUS_OFFICIAL_SELECT_TEMPLATE";
        }
        if (normalized.endsWith("auto-research-filtered-corpus.sql")) {
            return "CORPUS_AUTO_RESEARCH";
        }
        return "CORPUS_REPLAY_SETUP";
    }

    private static String getNoSetupCorpusSourceName(CorpusQueryInput input) {
        String sourceName = getCorpusSourceName(input);
        if ("CORPUS_OFFICIAL_SELECT_TEMPLATE".equals(sourceName) || "CORPUS_AUTO_RESEARCH".equals(sourceName)
                || "CORPUS_HIGH_COVERAGE".equals(sourceName)) {
            return sourceName;
        }
        return "CORPUS_RANDOM_SCHEMA";
    }

    /**
     * Outcome of the non-empty probe. A failing statement used to be indistinguishable from an empty result, which made
     * "the wrapper filtered every row" and "the wrapper does not even run" look identical in the statistics.
     */
    private enum ProbeOutcome {
        ROWS, EMPTY, ERROR,
        /**
         * SQLQueryAdapter swallows errors that are on the expected-error list and returns a null result set instead of
         * throwing. Counting that as an empty result made wrappers look like they filtered every row when in fact the
         * statement never ran.
         */
        EXPECTED_ERROR
    }

    private static ProbeOutcome probeQuery(SQLite3GlobalState state, String queryString) {
        if (state == null || queryString == null || queryString.isBlank()) {
            return ProbeOutcome.ERROR;
        }
        EGraphSqlCoverage.trace("queryProducesRows start sql=" + shortenForTrace(queryString));
        String probe = "SELECT 1 FROM (" + stripTrailingSemicolon(queryString) + ") AS egraph_non_empty_probe LIMIT 1";
        try (SQLancerResultSet rs = new SQLQueryAdapter(probe, false).executeAndGet(state, false)) {
            if (rs == null) {
                EGraphSqlCoverage.trace("queryProducesRows expected-error sql=" + shortenForTrace(queryString));
                return ProbeOutcome.EXPECTED_ERROR;
            }
            boolean hasRows = rs.next();
            EGraphSqlCoverage.trace("queryProducesRows done rows=" + hasRows + " sql=" + shortenForTrace(queryString));
            return hasRows ? ProbeOutcome.ROWS : ProbeOutcome.EMPTY;
        } catch (Exception e) {
            EGraphSqlCoverage.trace("queryProducesRows exception sql=" + shortenForTrace(queryString));
            EGraphSqlCoverage.recordProbeError(e.getMessage(), queryString);
            return ProbeOutcome.ERROR;
        }
    }

    private static boolean queryProducesRows(SQLite3GlobalState state, String queryString) {
        return probeQuery(state, queryString) == ProbeOutcome.ROWS;
    }

    private static String shortenForTrace(String sql) {
        if (sql == null) {
            return "<null>";
        }
        String normalized = sql.strip().replaceAll("\\s+", " ");
        if (normalized.length() > 180) {
            return normalized.substring(0, 177) + "...";
        }
        return normalized;
    }

    private static String stripTrailingSemicolon(String queryString) {
        String result = queryString.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean replayCorpusSetupForQuery(SQLite3GlobalState state, CorpusQueryInput input) {
        try {
            resetCorpusSchema(state);
        } catch (Exception ignored) {
        }
        boolean executedSetupStatement = false;
        boolean schemaChanged = false;
        for (String statement : input.getSetupStatements()) {
            try {
                SQLQueryAdapter query = new SQLQueryAdapter(statement, corpusStatementCouldAffectSchema(statement));
                if (query.execute(state, false)) {
                    executedSetupStatement = true;
                    logExecutedContextStatement(state, query);
                    schemaChanged |= query.couldAffectSchema();
                }
            } catch (AssertionError ignored) {
            } catch (RuntimeException ignored) {
            } catch (Exception ignored) {
            }
        }
        try {
            if (schemaChanged) {
                state.updateSchema();
            }
        } catch (AssertionError ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
        return executedSetupStatement && !state.getSchema().getDatabaseTables().isEmpty();
    }

    private static void resetCorpusSchema(SQLite3GlobalState state) throws Exception {
        try {
            state.updateSchema();
        } catch (AssertionError ignored) {
            return;
        }
        List<SQLite3Table> tables = new ArrayList<>(state.getSchema().getDatabaseTables());
        for (SQLite3Table table : tables) {
            if (table.isView()) {
                executeContextStatement(state, "DROP VIEW IF EXISTS " + quoteIdentifier(table.getName()), true);
            }
        }
        for (SQLite3Table table : tables) {
            if (!table.isView()) {
                executeContextStatement(state, "DROP TABLE IF EXISTS " + quoteIdentifier(table.getName()), true);
            }
        }
        state.updateSchema();
    }

    private static boolean corpusStatementCouldAffectSchema(String statement) {
        String normalized = statement.stripLeading().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("(?s)^(CREATE|DROP|ALTER)\\b.*")
                || normalized.matches("(?s).*\\b(CREATE|DROP|ALTER)\\s+(TABLE|INDEX|VIEW|TRIGGER|VIRTUAL)\\b.*");
    }

    private static EGraphCoverageShape chooseEGraphCorpusExecutionCoverageShape() {
        EGraphCoverageShape autoShape = chooseAutoResearchShape();
        if (autoShape != null && Randomly.fromOptions(true, true, false)) {
            return autoShape;
        }
        return Randomly.fromList(CORPUS_COVERAGE_SHAPE_POOL);
    }

    private static EGraphCoverageShape chooseEGraphExecutionCoverageShape(EGraphCoverageShape inputShape) {
        if (inputShape != EGraphCoverageShape.PLAIN && Randomly.fromOptions(true, true, false)) {
            return EGraphCoverageShape.PLAIN;
        }
        return chooseEGraphCoverageShape();
    }

    private static EGraphCoverageContext createEGraphCoverageContext(SQLite3GlobalState state,
            AbstractTables<SQLite3Table, SQLite3Column> targetTables, EGraphCoverageShape requestedShape)
            throws Exception {
        if (state != null) {
            // This is the one place a check's context is built, so it is where the snapshot the
            // referee replays belongs to a new check. Without this it only ever grew: the first
            // couple of thousand statements of the thread's life, then nothing - every later check
            // handed the referee a setup from some earlier database and it could not answer.
            EGraphContextSnapshot.reset();
        }
        EGraphCoverageShape shape = requestedShape;
        SQLite3Table table = targetTables.getTables().get(0);
        List<SQLite3Column> columns = targetTables.getColumns().stream()
                .filter(SQLite3OracleFactory::hasUsableIdentifier)
                .collect(java.util.stream.Collectors.toList());
        if (columns.isEmpty() || !hasUsableIdentifier(table.getName())) {
            return new EGraphCoverageContext(EGraphCoverageShape.PLAIN, quoteIdentifier(table.getName()), null, null,
                    null, null);
        }
        SQLite3Column column = Randomly.fromList(columns);
        SQLite3Column secondColumn = columns.size() > 1 ? columns.get(1) : column;
        if ((shape == EGraphCoverageShape.MULTI_INDEX_OR || shape == EGraphCoverageShape.AUTOMATIC_INDEX)
                && columns.size() < 2) {
            shape = EGraphCoverageShape.PLAIN;
        }
        if (shape == EGraphCoverageShape.MULTI_INDEX_OR) {
            addMultiIndexOrIndexes(state, table, column, secondColumn);
        } else if (state != null && !prepareHighCoverageContext(state, table, columns, column, shape)) {
            shape = EGraphCoverageShape.PLAIN;
        }
        String groupByColumns = columns.stream().map(c -> quoteIdentifier(c.getName()))
                .collect(java.util.stream.Collectors.joining(", "));
        return new EGraphCoverageContext(shape, quoteIdentifier(table.getName()), quoteIdentifier(column.getName()),
                quoteIdentifier(secondColumn.getName()),
                quoteIdentifier(getEGraphContextIndexName(table.getName(), column.getName())),
                groupByColumns);
    }

    private static EGraphCoverageContext createVariantEGraphCoverageContext(EGraphCoverageContext originalContext,
            AbstractTables<SQLite3Table, SQLite3Column> targetTables) throws Exception {
        if (!Randomly.fromOptions(true, false, false)) {
            return originalContext;
        }
        EGraphCoverageShape variantShape = chooseCrossPlanShape(originalContext.shape);
        if (variantShape == originalContext.shape) {
            return originalContext;
        }
        return createEGraphCoverageContext(null, targetTables, variantShape);
    }

    private static EGraphCoverageShape chooseCrossPlanShape(EGraphCoverageShape originalShape) {
        switch (originalShape) {
            case PLAIN:
            case DERIVED_TABLE:
            case MATERIALIZED_CTE:
            case CO_ROUTINE:
            case NOT_MATERIALIZED_CTE:
            case LIMIT_OFFSET:
            case WHERE_CASE_TRUE:
            case WHERE_FUNCTION_TRUE:
            case WHERE_COLLATE_TRUE:
            case VALUES_CTE_JOIN:
            case RECURSIVE_CTE:
                return Randomly.fromOptions(EGraphCoverageShape.PLAIN, EGraphCoverageShape.DERIVED_TABLE,
                        EGraphCoverageShape.MATERIALIZED_CTE, EGraphCoverageShape.CO_ROUTINE,
                        EGraphCoverageShape.NOT_MATERIALIZED_CTE, EGraphCoverageShape.LIMIT_OFFSET,
                        EGraphCoverageShape.WHERE_CASE_TRUE, EGraphCoverageShape.WHERE_FUNCTION_TRUE,
                        EGraphCoverageShape.WHERE_COLLATE_TRUE, EGraphCoverageShape.VALUES_CTE_JOIN,
                        EGraphCoverageShape.RECURSIVE_CTE);
            case DISTINCT:
                return EGraphCoverageShape.GROUP_BY;
            case GROUP_BY:
                return EGraphCoverageShape.DISTINCT;
            case COMPOUND_UNION_ALL:
            case CORRELATED_SUBQUERY:
            case AUTOMATIC_INDEX:
            case MULTI_INDEX_OR:
            case SCALAR_SUBQUERY:
            case WINDOW_COUNT:
            case FTS5_MATCH_CONTEXT:
            case FTS5_DEEP_CONTEXT:
            case RTREE_CONTEXT:
            case RTREE_DEEP_CONTEXT:
            case DBSTAT_CONTEXT:
            case VIEW_TRIGGER_FK_CONTEXT:
            case ANALYZE_INDEX_CONTEXT:
            case JSON_CONTEXT:
            case TX_WAL_VACUUM_CONTEXT:
            case AUTO_VACUUM_INTEGRITY_CONTEXT:
            case ATTACH_VACUUM_WAL_CONTEXT:
            case ALTER_INDEX_ANALYZE_CONTEXT:
            case SELECT_WHERE_STRESS_CONTEXT:
            case EXPR_STRESS_CONTEXT:
            case WINDOW_STRESS_CONTEXT:
            case RESOLVE_STRESS_CONTEXT:
            case SORTER_STRESS_CONTEXT:
            case JOIN_OPTIMIZER_CONTEXT:
            case ALTER_FK_STRESS_CONTEXT:
            case INTEGRITY_CHECK_CONTEXT:
            case SCALAR_AGGREGATE_CONTEXT:
            case VIRTUAL_TABLE_UPDATE_CONTEXT:
            case FTS5_SECURE_DELETE_CONTEXT:
            case VIRTUAL_TABLE_SAVEPOINT_CONTEXT:
            case JSONB_STRESS_CONTEXT:
            case XFER_OPTIMIZATION_CONTEXT:
            case MULTI_SELECT_ORDER_BY_CONTEXT:
            case ROW_VALUE_CONTEXT:
            case AGGREGATE_ORDER_BY_CONTEXT:
            case WINDOW_RANGE_FULLSCAN_CONTEXT:
            case SCALAR_FUNCTION_BATTERY_CONTEXT:
            case DATE_MODIFIER_BATTERY_CONTEXT:
            case WINDOW_FUNCTION_BATTERY_CONTEXT:
            case FTS4_MATCH_CONTEXT:
            case FTS4_AUX_CONTEXT:
            case BLOOM_FILTER_CONTEXT:
            case MULTI_INDEX_OR_ROWSET_CONTEXT:
            case INDEX_FUNCTION_VALUE_CONTEXT:
            case SORTER_DEEP_MERGE_CONTEXT:
            case FTS5_DEEP_QUERY_CONTEXT:
            case FTS5_AUX_DEEP_CONTEXT:
            case FTS4_DEEP_SEGMENT_CONTEXT:
            case FTS5_VARIANT_CONFIG_CONTEXT:
            case PRAGMA_VTAB_CONTEXT:
            case FTS4_MERGE_LCS_CONTEXT:
            case FTS3_TOKENIZE_TABLE_CONTEXT:
            case FTS5_TOMBSTONE_CONTEXT:
            case FTS5_TOKENIZER_VARIANT_CONTEXT:
            case SQL_SYNTAX_BATTERY_CONTEXT:
            case COLD_FUNCTION_BATTERY_CONTEXT:
            case COLD_DDL_TRIGGER_CONTEXT:
                return originalShape;
            default:
                throw new AssertionError(originalShape);
        }
    }

    private static String wrapEGraphCoverageShape(String query, EGraphCoverageContext context) {
        EGraphCoverageShape shape = context.shape;
        EGraphSqlCoverage.recordWrapperShape(shape.name());
        String columnName = context.columnName;
        String secondColumnName = context.secondColumnName;
        String tableName = context.tableName;
        switch (shape) {
            case DERIVED_TABLE:
                return "SELECT * FROM (" + query + ") AS egraph_sub";
            case DISTINCT:
                return "SELECT DISTINCT * FROM (" + query + ") AS egraph_distinct";
            case GROUP_BY:
                return "SELECT * FROM (" + query + ") AS egraph_group GROUP BY " + context.groupByColumns;
            case COMPOUND_UNION_ALL:
                return "SELECT * FROM (" + query + ") AS egraph_left UNION ALL SELECT * FROM (" + query
                        + ") AS egraph_right";
            case CORRELATED_SUBQUERY:
                return "SELECT * FROM (" + query + ") AS egraph_outer WHERE EXISTS (SELECT 1 FROM " + tableName
                        + " AS egraph_inner WHERE egraph_inner." + columnName + " = egraph_outer." + columnName + ")";
            case MATERIALIZED_CTE:
                return "WITH egraph_mat AS MATERIALIZED (" + query + ") SELECT * FROM egraph_mat";
            case NOT_MATERIALIZED_CTE:
                return "WITH egraph_not_mat AS NOT MATERIALIZED (" + query + ") SELECT * FROM egraph_not_mat";
            case AUTOMATIC_INDEX:
                return "SELECT egraph_outer.* FROM (" + query + ") AS egraph_outer JOIN " + tableName
                        + " AS egraph_auto ON egraph_auto." + columnName + " = egraph_outer." + columnName;
            case CO_ROUTINE:
                // LIMIT -1 has to hang off a SELECT of its own. Appended directly to `query` it
                // collides with the LIMIT the base-query generator already emits, producing
                // "LIMIT 10 LIMIT -1" - a syntax error that aborted the whole check. The extra
                // nesting keeps the intent: a LIMIT on a subquery is what stops the flattener and
                // forces SQLite to run it as a co-routine.
                return "SELECT * FROM (SELECT * FROM (" + query
                        + ") AS egraph_co_inner LIMIT -1) AS egraph_co ORDER BY 1";
            case MULTI_INDEX_OR:
                return "SELECT * FROM (" + query + ") AS egraph_outer WHERE egraph_outer." + columnName
                        + " = 0 OR egraph_outer." + secondColumnName + " = 0";
            case LIMIT_OFFSET:
                return "SELECT * FROM (" + query + ") AS egraph_limit LIMIT -1 OFFSET 0";
            case WHERE_CASE_TRUE:
                return "SELECT * FROM (" + query + ") AS egraph_case WHERE CASE WHEN 1 = 1 THEN 1 ELSE 0 END";
            case WHERE_FUNCTION_TRUE:
                return "SELECT * FROM (" + query
                        + ") AS egraph_func WHERE COALESCE(NULL, 1) = 1 AND TYPEOF(1) = 'integer'";
            case WHERE_COLLATE_TRUE:
                return "SELECT * FROM (" + query + ") AS egraph_collate WHERE 'a' COLLATE BINARY = 'a'";
            case SCALAR_SUBQUERY:
                return "SELECT egraph_scalar.*, (SELECT COUNT(*) FROM " + tableName
                        + ") AS egraph_scalar_count FROM (" + query + ") AS egraph_scalar";
            case WINDOW_COUNT:
                return "SELECT egraph_win.*, COUNT(*) FILTER (WHERE 1) OVER "
                        + "(ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS egraph_window_count FROM ("
                        + query + ") AS egraph_win";
            case VALUES_CTE_JOIN:
                return "WITH egraph_const(egraph_one) AS (VALUES(1)) SELECT egraph_values.* FROM (" + query
                        + ") AS egraph_values JOIN egraph_const ON egraph_const.egraph_one = 1";
            case RECURSIVE_CTE:
                return "WITH RECURSIVE egraph_rec(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM egraph_rec WHERE x < 3), "
                        + "egraph_rec_sum(s) AS (SELECT SUM(x) FILTER (WHERE x > 0) FROM egraph_rec) "
                        + "SELECT egraph_recursive.* FROM (" + query
                        + ") AS egraph_recursive JOIN egraph_rec_sum ON egraph_rec_sum.s >= 1";
            case FTS5_MATCH_CONTEXT:
                return "WITH egraph_fts_rank AS (SELECT rowid, bm25(egraph_fts) AS score, "
                        + "highlight(egraph_fts, 0, '[', ']') AS title_hit, "
                        + "snippet(egraph_fts, 1, '<', '>', '...', 8) AS body_hit "
                        + "FROM egraph_fts WHERE egraph_fts MATCH 'sqlite OR compiler OR variants' "
                        + "ORDER BY rank LIMIT 5) SELECT * FROM (" + query
                        + ") AS egraph_fts_q WHERE EXISTS (SELECT 1 FROM egraph_fts_rank "
                        + "WHERE score IS NOT NULL OR title_hit IS NOT NULL OR body_hit IS NOT NULL)";
            case FTS5_DEEP_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_fts_deep_q WHERE EXISTS (SELECT 1 FROM egraph_fts_deep "
                        + "WHERE egraph_fts_deep MATCH 'sqlite OR prefix OR tokenizer' LIMIT 1)";
            case RTREE_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_rtree_q WHERE EXISTS (SELECT 1 FROM egraph_rtree WHERE x1 <= 6.0 AND x2 >= 6.0 "
                        + "AND y1 <= 6.0 AND y2 >= 6.0)";
            case RTREE_DEEP_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_rtree_deep_q WHERE EXISTS (SELECT 1 FROM egraph_rtree_deep "
                        + "WHERE x1 <= 12.0 AND x2 >= 12.0 AND y1 <= 12.0 AND y2 >= 12.0 LIMIT 1)";
            case DBSTAT_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_dbstat_q WHERE EXISTS (SELECT 1 FROM egraph_dbstat "
                        + "WHERE name IS NOT NULL AND pageno >= 0 LIMIT 1)";
            case VIEW_TRIGGER_FK_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_view_q WHERE EXISTS (SELECT 1 FROM egraph_child_view WHERE value >= 0)";
            case ANALYZE_INDEX_CONTEXT:
                return "SELECT * FROM (" + query + ") AS egraph_idx_q WHERE EXISTS (SELECT 1 FROM " + tableName
                        + " INDEXED BY " + context.indexName + " WHERE " + columnName
                        + " IS NOT NULL OR " + columnName + " IS NULL LIMIT 1)";
            case JSON_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_json_q WHERE json_extract(json_object('ok', 1, 'items', json_array(1, 2, 3)), "
                        + "'$.ok') = 1 AND json_type(json_set('{\"a\":1}', '$.b', json_array(2, 3)), '$.b') = 'array' "
                        + "AND EXISTS (SELECT 1 FROM json_tree('{\"items\":[1,2,3]}') WHERE value = 2)";
            case TX_WAL_VACUUM_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_tx_q WHERE EXISTS (SELECT 1 FROM egraph_tx_probe WHERE value >= 0 LIMIT 1)";
            case AUTO_VACUUM_INTEGRITY_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_autovac_q WHERE EXISTS (SELECT 1 FROM egraph_autovac_probe "
                        + "WHERE k >= 0 LIMIT 1)";
            case ATTACH_VACUUM_WAL_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_attach_q WHERE EXISTS (SELECT 1 FROM egraph_attach_probe "
                        + "WHERE value >= 0 LIMIT 1)";
            case ALTER_INDEX_ANALYZE_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_alter_q WHERE EXISTS (SELECT 1 FROM egraph_alter_probe "
                        + "INDEXED BY egraph_alter_value_idx WHERE value >= 0 LIMIT 1)";
            case SELECT_WHERE_STRESS_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_select_where_q WHERE EXISTS (SELECT 1 FROM egraph_plan_probe "
                        + "WHERE v >= 0 LIMIT 1)";
            case EXPR_STRESS_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_expr_q WHERE EXISTS (SELECT 1 FROM egraph_expr_probe "
                        + "WHERE typeof(txt) = 'text' LIMIT 1)";
            case WINDOW_STRESS_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_window_q WHERE EXISTS (SELECT 1 FROM egraph_window_probe "
                        + "WHERE grp >= 0 LIMIT 1)";
            case RESOLVE_STRESS_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_resolve_q WHERE EXISTS (SELECT 1 FROM egraph_resolve_view "
                        + "WHERE alias_value >= 0 LIMIT 1)";
            case SORTER_STRESS_CONTEXT:
                // Only ORDER BY goes through vdbesort.c; GROUP BY uses a temp
                // B-tree and never spills a PMA. The join multiplies the wide
                // probe rows by the inner row count, which carries the sort past
                // the ~1 MB PMA floor into the external merge path.
                //
                // ORDER BY makes the comparison order-sensitive, so the output
                // order has to be fully determined. It is: the sort key
                // (payload, id) is a total order over distinct probe rows, only
                // probe columns are projected, and the rows repeated per inner
                // row are byte-identical, so ties are interchangeable. A sort
                // that loses or duplicates a row still changes the output and is
                // reported.
                //
                // The inline amplifier matters: a base query often returns only a
                // few rows, and one inner row contributes just ~145 KB, so
                // without it the sort stays under the PMA floor and never spills.
                // Amplifying by 8 guarantees ~1.2 MB even for a single-row inner
                // query, and costs nothing in the snapshot (no extra table).
                return "WITH egraph_sorter_amp(k) AS (VALUES(1),(2),(3),(4),(5),(6),(7),(8)) "
                        + "SELECT p.id, p.bucket FROM (" + query
                        + ") AS egraph_sorter_q JOIN egraph_sorter_probe AS p ON p.bucket >= 0 "
                        + "CROSS JOIN egraph_sorter_amp ORDER BY p.payload, p.id";
            case SCALAR_FUNCTION_BATTERY_CONTEXT:
                return "SELECT egraph_fn.*, instr('abcabc', 'ca') AS egraph_fn_instr, "
                        + "instr(x'0102030405', x'0304') AS egraph_fn_instr_blob, "
                        + "unhex('48656C6C6F') AS egraph_fn_unhex, "
                        + "unhex('48-65-6C', '-') AS egraph_fn_unhex_sep, "
                        + "concat('a', 2, NULL, 'b') AS egraph_fn_concat, "
                        + "concat_ws('-', 'a', NULL, 'b', 3) AS egraph_fn_concat_ws, "
                        + "log(100.0) AS egraph_fn_log, log(2, 8.0) AS egraph_fn_logb, "
                        + "log2(64.0) AS egraph_fn_log2, log10(1000.0) AS egraph_fn_log10, "
                        + "format('%!.4f|%+d|%5s|%x', 3.14159, 42, 'ab', 255) AS egraph_fn_format, "
                        + "char(72, 105, 0x4E2D) AS egraph_fn_char, unicode('Z') AS egraph_fn_unicode, "
                        + "ltrim('xxabcxx', 'x') AS egraph_fn_ltrim, rtrim('xxabcxx', 'x') AS egraph_fn_rtrim, "
                        + "trim('xxabcxx', 'x') AS egraph_fn_trim, replace('aaa', 'a', 'bb') AS egraph_fn_replace, "
                        + "substr('abcdef', -3, 2) AS egraph_fn_substr, quote(x'00ff') AS egraph_fn_quote_blob, "
                        + "quote(NULL) AS egraph_fn_quote_null, iif(1 > 0, 'y', 'n') AS egraph_fn_iif, "
                        + "likelihood(1, 0.5) AS egraph_fn_likelihood, likely(1) AS egraph_fn_likely, "
                        + "unlikely(1) AS egraph_fn_unlikely, octet_length('ab') AS egraph_fn_octet_length, "
                        + "sign(-7.5) AS egraph_fn_sign, trunc(-7.9) AS egraph_fn_trunc, "
                        + "ceil(2.1) AS egraph_fn_ceil, floor(-2.1) AS egraph_fn_floor, "
                        + "pow(2, 10) AS egraph_fn_pow, mod(-7, 3) AS egraph_fn_mod, "
                        + "atan2(1, 1) AS egraph_fn_atan2, degrees(3.14159265358979) AS egraph_fn_degrees, "
                        + "radians(180.0) AS egraph_fn_radians, sinh(1.0) AS egraph_fn_sinh, "
                        + "cosh(1.0) AS egraph_fn_cosh, tanh(1.0) AS egraph_fn_tanh, "
                        + "asinh(1.0) AS egraph_fn_asinh, acosh(2.0) AS egraph_fn_acosh, "
                        + "atanh(0.5) AS egraph_fn_atanh, exp(1.0) AS egraph_fn_exp, "
                        + "ln(2.718281828459045) AS egraph_fn_ln, "
                        + "timediff('2026-08-31 12:00:00', '2026-01-01 00:00:00') AS egraph_fn_timediff FROM ("
                        + query + ") AS egraph_fn";
            case DATE_MODIFIER_BATTERY_CONTEXT:
                return "SELECT egraph_dt.*, "
                        + "datetime('2026-08-31 12:34:56', '+1 day', '-2 hours', '+30 minutes', '-15 seconds') "
                        + "AS egraph_dt_offsets, "
                        + "datetime('2026-08-31 12:34:56', '+1 month', '-1 year') AS egraph_dt_ym, "
                        + "datetime('2026-08-31 12:34:56', 'start of month') AS egraph_dt_som, "
                        + "datetime('2026-08-31 12:34:56', 'start of year') AS egraph_dt_soy, "
                        + "datetime('2026-08-31 12:34:56', 'start of day') AS egraph_dt_sod, "
                        + "datetime('2026-08-31 12:34:56', 'weekday 3') AS egraph_dt_weekday, "
                        + "datetime(2461284.0234, 'julianday') AS egraph_dt_julian, "
                        + "datetime(1788000000, 'unixepoch') AS egraph_dt_unix, "
                        + "datetime(1788000000, 'unixepoch', 'localtime') AS egraph_dt_localtime, "
                        + "datetime('2026-08-31 12:34:56', 'utc') AS egraph_dt_utc, "
                        + "datetime('2026-08-31 12:34:56.789', 'subsec') AS egraph_dt_subsec, "
                        + "datetime('2026-08-31 12:34:56', 'ceiling') AS egraph_dt_ceiling, "
                        + "datetime('2026-08-31 12:34:56', 'floor') AS egraph_dt_floor, "
                        + "datetime(1788000000123, 'auto') AS egraph_dt_auto, "
                        + "strftime('%Y|%m|%d|%H|%M|%S|%f|%j|%w|%W|%s|%J|%U|%G|%g|%u|%e|%k|%l|%p|%P|%I|%R|%T|%F', "
                        + "'2026-08-31 12:34:56.789') AS egraph_dt_strftime, "
                        + "julianday('2026-08-31', 'start of month', '+15 days') AS egraph_dt_juliandayf, "
                        + "unixepoch('2026-08-31 12:34:56', 'subsec') AS egraph_dt_unixepochf, "
                        + "timediff('2026-08-31', '2020-02-29') AS egraph_dt_timediff, "
                        + "date('2026-08-31 12:34:56', '+1 day', 'start of month') AS egraph_dt_date, "
                        + "time('2026-08-31 12:34:56', '+90 minutes') AS egraph_dt_time FROM (" + query
                        + ") AS egraph_dt";
            case WINDOW_FUNCTION_BATTERY_CONTEXT:
                // Collapsed to a single row: the OVER clauses need ORDER BY, and
                // that switches the oracle to an order-sensitive comparison which
                // the unordered inner query could not satisfy. The moving ROWS
                // frames are what drive the inverse callbacks
                // (last_valueInvFunc, groupConcatInverse).
                return "SELECT count(*) AS egraph_wf_rows, sum(w.a) AS egraph_wf_nth, "
                        + "sum(w.b) AS egraph_wf_last, sum(w.c) AS egraph_wf_first, "
                        + "sum(w.d) AS egraph_wf_lag, sum(w.e) AS egraph_wf_lead, "
                        + "sum(w.f) AS egraph_wf_ntile, group_concat(w.g) AS egraph_wf_gc, "
                        + "sum(w.h) AS egraph_wf_nth_plain, sum(w.i) AS egraph_wf_rank FROM ("
                        + "SELECT nth_value(p.id, 2) OVER (ORDER BY p.id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) "
                        + "AS a, last_value(p.id) OVER (ORDER BY p.id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) "
                        + "AS b, first_value(p.id) OVER (ORDER BY p.id ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING) "
                        + "AS c, lag(p.id, 2, -1) OVER (ORDER BY p.id) AS d, "
                        + "lead(p.id, 2, -1) OVER (ORDER BY p.id) AS e, ntile(4) OVER (ORDER BY p.id) AS f, "
                        + "group_concat(p.bucket) OVER (ORDER BY p.id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) "
                        + "AS g, nth_value(p.id, 3) OVER (ORDER BY p.id ROWS BETWEEN UNBOUNDED PRECEDING AND "
                        // GROUPS and RANGE frames, percent_rank, cume_dist and a FILTER on a window
                        // aggregate were measured here and taken back out: 59 lines of coverage for
                        // 16 ms per execution on a wrapper that already costs 3.8 ms, and it runs
                        // once for the original plus once per variant.
                        + "CURRENT ROW) AS h, dense_rank() OVER (ORDER BY p.bucket) AS i "
                        + "FROM (" + query + ") AS egraph_wf_q JOIN egraph_sorter_probe AS p ON p.bucket = 0"
                        + ") AS w";
            case FTS5_VARIANT_CONFIG_CONTEXT:
                // FTS5 behaves like a different engine per configuration: a
                // contentless_delete index keeps tombstones, detail=none has no
                // position lists at all (so no phrase queries), and each tokenizer
                // has its own implementation. Those code paths are unreachable from
                // a single default-configured table.
                return "SELECT egraph_fts5v_q.*, "
                        + "(SELECT count(*) FROM egraph_fts5_cd WHERE egraph_fts5_cd MATCH 'common') "
                        + "AS egraph_fts5v_tombstone, "
                        + "(SELECT count(*) FROM egraph_fts5_cd WHERE egraph_fts5_cd MATCH 'alpha7 OR alpha9') "
                        + "AS egraph_fts5v_tombstone_or, "
                        + "(SELECT count(*) FROM egraph_fts5_none WHERE egraph_fts5_none MATCH 'common AND word5') "
                        + "AS egraph_fts5v_detail_none, "
                        + "(SELECT count(*) FROM egraph_fts5_none WHERE egraph_fts5_none MATCH 'wor*') "
                        + "AS egraph_fts5v_none_prefix, "
                        + "(SELECT count(*) FROM egraph_fts5_col WHERE egraph_fts5_col MATCH '{a}: common') "
                        + "AS egraph_fts5v_detail_col, "
                        + "(SELECT count(*) FROM egraph_fts5_ascii WHERE egraph_fts5_ascii MATCH 'common') "
                        + "AS egraph_fts5v_ascii, "
                        + "(SELECT count(*) FROM egraph_fts5_ext WHERE egraph_fts5_ext MATCH 'common') "
                        + "AS egraph_fts5v_external FROM (" + query + ") AS egraph_fts5v_q";
            case PRAGMA_VTAB_CONTEXT:
                // The pragma table-valued functions are a virtual-table module of
                // their own (pragmaVtabConnect/BestIndex/Filter/Next).
                return "SELECT egraph_pragma_q.*, "
                        + "(SELECT count(*) FROM pragma_table_info('egraph_sorter_probe')) AS egraph_pv_tinfo, "
                        + "(SELECT count(*) FROM pragma_table_xinfo('egraph_sorter_probe')) AS egraph_pv_txinfo, "
                        + "(SELECT count(*) FROM pragma_index_list('egraph_sorter_probe')) AS egraph_pv_ilist, "
                        + "(SELECT count(*) FROM pragma_function_list WHERE name LIKE 'j%') AS egraph_pv_flist, "
                        + "(SELECT count(*) FROM pragma_module_list WHERE name LIKE 'fts%') AS egraph_pv_mlist, "
                        + "(SELECT count(*) FROM pragma_table_list) AS egraph_pv_tlist, "
                        + "(SELECT count(*) FROM pragma_database_list) AS egraph_pv_dblist, "
                        + "(SELECT count(*) FROM pragma_pragma_list) AS egraph_pv_plist, "
                        + "(SELECT count(*) FROM pragma_compile_options WHERE compile_options LIKE 'ENABLE%') AS egraph_pv_copts, "
                        + "(SELECT count(*) FROM pragma_index_info((SELECT name FROM sqlite_master "
                        + "WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 1))) AS egraph_pv_iinfo, "
                        + "(SELECT count(*) FROM pragma_index_xinfo((SELECT name FROM sqlite_master "
                        + "WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 1))) AS egraph_pv_ixinfo, "
                        + "(SELECT count(*) FROM pragma_collation_list) AS egraph_pv_clist FROM (" + query
                        + ") AS egraph_pragma_q";
            case FTS4_MERGE_LCS_CONTEXT:
                // matchinfo's 's'/'y' flags are the LCS computation (fts3MatchinfoLcs),
                // and a three-token phrase drives the doclist/poslist merges. The
                // helpers only work as bare projection items of a SELECT whose FROM
                // names the fts table directly, hence the join rather than a subquery.
                // No ORDER BY: the oracle switches to an order-sensitive comparison as
                // soon as one appears in the query text, so the descending-docid scan
                // lives in the context setup instead.
                return "SELECT egraph_f4m_q.*, length(matchinfo(egraph_fts4m, 'pcxsy')) AS egraph_f4m_lcs, "
                        + "offsets(egraph_fts4m) AS egraph_f4m_offsets, "
                        + "egraph_fts4m.docid AS egraph_f4m_docid FROM (" + query
                        + ") AS egraph_f4m_q, egraph_fts4m "
                        + "WHERE egraph_fts4m MATCH '\"lcsanchor alpha beta\"'";
            case FTS3_TOKENIZE_TABLE_CONTEXT:
                // The fts3tokenize virtual table is an ordinary (read-only) table
                // module, so unlike the fts helpers it can be used from a subquery.
                // Token counts and lengths are real computed values.
                return "SELECT egraph_tok_q.*, "
                        + "(SELECT count(*) FROM egraph_fts3tok "
                        + "WHERE input = 'alpha beta gamma delta epsilon') AS egraph_tok_uni, "
                        + "(SELECT count(*) FROM egraph_fts3tokp "
                        + "WHERE input = 'running jumped quickly testing') AS egraph_tok_porter, "
                        + "(SELECT sum(length(token)) FROM egraph_fts3tok "
                        + "WHERE input = 'sqlite coverage probe') AS egraph_tok_len, "
                        + "(SELECT count(*) FROM egraph_fts4c WHERE egraph_fts4c MATCH 'alpha') "
                        + "AS egraph_tok_content FROM (" + query + ") AS egraph_tok_q";
            case FTS5_TOMBSTONE_CONTEXT:
                // A contentless_delete=1 table keeps deleted rowids in tombstone
                // pages, so every match count below has to consult them.
                return "SELECT egraph_f5cd_q.*, "
                        + "(SELECT count(*) FROM egraph_fts5cd WHERE egraph_fts5cd MATCH 'alpha') "
                        + "AS egraph_f5cd_all, "
                        + "(SELECT count(*) FROM egraph_fts5cd "
                        + "WHERE egraph_fts5cd MATCH 'token7 OR token11 OR token13') AS egraph_f5cd_or, "
                        + "(SELECT count(*) FROM egraph_fts5cd WHERE egraph_fts5cd MATCH 'tomb*') "
                        + "AS egraph_f5cd_prefix FROM (" + query + ") AS egraph_f5cd_q";
            case FTS5_TOKENIZER_VARIANT_CONTEXT:
                // Three tokenizers that the default unicode61 configuration never
                // reaches: ascii folding, tokenchars/separators exceptions, and a
                // detail=none index whose segment iterator is a separate code path.
                return "SELECT egraph_f5tv_q.*, "
                        + "(SELECT count(*) FROM egraph_fts5asc WHERE egraph_fts5asc MATCH 'alpha') "
                        + "AS egraph_f5tv_ascii, "
                        + "(SELECT count(*) FROM egraph_fts5uni WHERE egraph_fts5uni MATCH '\"foo-bar\"') "
                        + "AS egraph_f5tv_uni, "
                        + "(SELECT count(*) FROM egraph_fts5uni WHERE egraph_fts5uni MATCH 'baz') "
                        + "AS egraph_f5tv_sep, "
                        + "(SELECT count(*) FROM egraph_fts5none WHERE egraph_fts5none MATCH 'alpha AND gamma') "
                        + "AS egraph_f5tv_none FROM (" + query + ") AS egraph_f5tv_q";
            case SQL_SYNTAX_BATTERY_CONTEXT:
                // Grammar forms that survive as scalar subqueries: a bracketed
                // sub-join in FROM, USING, a three-keyword join, RIGHT/FULL OUTER
                // JOIN, LIMIT with an offset, a generated column, and a subtype
                // read. All collapse to a count, and none contains ORDER BY -
                // the oracle would switch to an order-sensitive comparison.
                return "SELECT egraph_syn_q.*, "
                        + "(SELECT count(*) FROM (SELECT k FROM egraph_syn_par LIMIT 1, 3)) AS egraph_syn_limit, "
                        + "(SELECT count(*) FROM (egraph_syn_par AS p JOIN egraph_syn_chi AS c ON p.k = c.k)) "
                        + "AS egraph_syn_paren, "
                        + "(SELECT count(*) FROM egraph_syn_par JOIN egraph_syn_chi USING (k)) AS egraph_syn_using, "
                        + "(SELECT count(*) FROM egraph_syn_par NATURAL LEFT OUTER JOIN egraph_syn_chi) "
                        + "AS egraph_syn_natural, "
                        + "(SELECT count(*) FROM egraph_syn_par RIGHT OUTER JOIN egraph_syn_chi "
                        + "ON egraph_syn_par.k = egraph_syn_chi.k) AS egraph_syn_right, "
                        + "(SELECT count(*) FROM egraph_syn_par FULL OUTER JOIN egraph_syn_chi "
                        + "ON egraph_syn_par.k = egraph_syn_chi.k) AS egraph_syn_full, "
                        + "(SELECT sum(g) + sum(h) FROM egraph_syn_def) AS egraph_syn_generated, "
                        + "(SELECT sum(subtype(jsonb(v))) FROM egraph_syn_par) AS egraph_syn_subtype, "
                        // Name resolution: a CTE joined to itself through USING, an alias that
                        // shadows the column it is built from, a correlated subquery that reaches
                        // past its own alias, and the three compound operators. Each wraps in
                        // count(*) so the subquery flattener has to decide whether it can collapse
                        // them, which is where most of these lines are. Still no ORDER BY.
                        + "(SELECT count(*) FROM (WITH egraph_syn_w(p, q) AS "
                        + "(SELECT k, v FROM egraph_syn_par) SELECT w1.p FROM egraph_syn_w AS w1 "
                        + "JOIN egraph_syn_w AS w2 USING (p))) AS egraph_syn_cte, "
                        + "(SELECT count(*) FROM (SELECT k AS v, v AS k FROM egraph_syn_par) WHERE v > 0) "
                        + "AS egraph_syn_shadow, "
                        + "(SELECT count(*) FROM (SELECT (SELECT count(*) FROM egraph_syn_chi AS inr "
                        + "WHERE inr.k = outr.k) AS n FROM egraph_syn_par AS outr)) AS egraph_syn_corr, "
                        + "(SELECT count(*) FROM (SELECT k FROM egraph_syn_par UNION SELECT k FROM egraph_syn_chi)) "
                        + "AS egraph_syn_union, "
                        + "(SELECT count(*) FROM (SELECT k FROM egraph_syn_par EXCEPT SELECT k FROM egraph_syn_chi)) "
                        + "AS egraph_syn_except, "
                        + "(SELECT count(*) FROM (SELECT k FROM egraph_syn_par INTERSECT "
                        + "SELECT k FROM egraph_syn_chi)) AS egraph_syn_intersect, "
                        + "(SELECT count(*) FROM egraph_syn_par AS x LEFT JOIN egraph_syn_par AS y "
                        + "USING (k, v)) AS egraph_syn_using2 "
                        + "FROM (" + query + ") AS egraph_syn_q";
            case FTS5_DEEP_QUERY_CONTEXT:
                // The full FTS5 query grammar against an index that has prefix
                // indexes, several segments and tombstones: column filters, prefix,
                // phrase, NEAR, NOT, initial-token match, rank ordering, reverse
                // rowid iteration and the three fts5vocab shapes.
                return "SELECT egraph_fts5d_q.*, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH 'sqlite AND planner') "
                        + "AS egraph_fts5d_and, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH '{title}: sqlite') "
                        + "AS egraph_fts5d_col, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH '{title body}: coverage') "
                        + "AS egraph_fts5d_cols, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH 'term1*') "
                        + "AS egraph_fts5d_prefix, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH '\"query planner\"') "
                        + "AS egraph_fts5d_phrase, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH 'NEAR(sqlite planner, 5)') "
                        + "AS egraph_fts5d_near, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH 'sqlite NOT alpha3') "
                        + "AS egraph_fts5d_not, "
                        + "(SELECT count(*) FROM egraph_fts5d WHERE egraph_fts5d MATCH '^title5') "
                        + "AS egraph_fts5d_initial, "
                        + "(SELECT count(*) FROM (SELECT rowid FROM egraph_fts5d WHERE egraph_fts5d MATCH 'sqlite' "
                        + "ORDER BY rank LIMIT 20)) AS egraph_fts5d_rank, "
                        + "(SELECT count(*) FROM (SELECT rowid FROM egraph_fts5d WHERE egraph_fts5d MATCH 'sqlite' "
                        + "ORDER BY rowid DESC LIMIT 20)) AS egraph_fts5d_reverse, "
                        + "(SELECT count(*) FROM egraph_fts5d_v WHERE term LIKE 'term1%') AS egraph_fts5d_vinst, "
                        + "(SELECT count(*) FROM egraph_fts5d_vr WHERE cnt > 1) AS egraph_fts5d_vrow, "
                        + "(SELECT count(*) FROM egraph_fts5d_vc WHERE col = 'body') AS egraph_fts5d_vcol FROM ("
                        + query + ") AS egraph_fts5d_q";
            case FTS5_AUX_DEEP_CONTEXT:
                // highlight/snippet/bm25 have to be bare projection items of the
                // SELECT that owns the MATCH, so they are computed in a subquery and
                // aggregated here; the aggregate also collapses the ORDER BY rank
                // result to one row, which keeps the comparison order-independent.
                return "SELECT count(*) AS egraph_fts5aux_rows, sum(length(aux.hl)) AS egraph_fts5aux_hl, "
                        + "sum(length(aux.sn)) AS egraph_fts5aux_sn, min(aux.bm) AS egraph_fts5aux_bm FROM ("
                        + "SELECT highlight(egraph_fts5d, 0, '[', ']') AS hl, "
                        + "snippet(egraph_fts5d, 1, '<', '>', '...', 8) AS sn, "
                        + "bm25(egraph_fts5d, 2.0, 1.0) AS bm FROM egraph_fts5d "
                        + "WHERE egraph_fts5d MATCH 'sqlite OR coverage' ORDER BY rank LIMIT 30) AS aux, ("
                        + query + ") AS egraph_fts5aux_q";
            case FTS4_DEEP_SEGMENT_CONTEXT:
                // Queries over an fts4 index built in batches, so it has several
                // segments and multi-level segment b-trees. matchinfo carries the
                // 's' and 'y' flags, which is the LCS computation.
                return "SELECT egraph_fts4d_q.*, "
                        + "(SELECT count(*) FROM egraph_fts4d WHERE egraph_fts4d MATCH 'common sqlite') "
                        + "AS egraph_fts4d_and, "
                        + "(SELECT count(*) FROM egraph_fts4d WHERE egraph_fts4d MATCH '\"common sqlite planner\"') "
                        + "AS egraph_fts4d_phrase, "
                        + "(SELECT count(*) FROM egraph_fts4d WHERE egraph_fts4d MATCH 'common OR w5 OR x7') "
                        + "AS egraph_fts4d_or, "
                        + "(SELECT count(*) FROM egraph_fts4d WHERE egraph_fts4d MATCH 'common NEAR/2 planner') "
                        + "AS egraph_fts4d_near, "
                        + "(SELECT count(*) FROM egraph_fts4d WHERE egraph_fts4d MATCH 'w1*') "
                        + "AS egraph_fts4d_prefix, length(matchinfo(egraph_fts4d, 'pcxnalsy')) "
                        + "AS egraph_fts4d_matchinfo FROM (" + query
                        + ") AS egraph_fts4d_q, egraph_fts4d WHERE egraph_fts4d MATCH 'common planner'";
            case FTS4_MATCH_CONTEXT:
                // FTS3/FTS4 query-expression variety: OR, phrase, NEAR, negation,
                // column filter, prefix and parenthesised groups, across three
                // tokenizers. For coverage: the match counts are projected, but they are computed
                // from the FTS table and not from the rows the rewrite selects, so both sides compute
                // the same number and the comparison cannot tell a wrong one from a right one.
                return "SELECT egraph_fts4_q.*, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'sqlite OR coverage') "
                        + "AS egraph_fts4_or, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH '\"query planner\"') "
                        + "AS egraph_fts4_phrase, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'sqlite NEAR/3 tokenizer') "
                        + "AS egraph_fts4_near, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'segment -alpha') "
                        + "AS egraph_fts4_not, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'title:sqlite') "
                        + "AS egraph_fts4_col, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'tok*') "
                        + "AS egraph_fts4_prefix, "
                        + "(SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH '(alpha OR beta) AND sqlite') "
                        + "AS egraph_fts4_group, "
                        + "(SELECT count(*) FROM egraph_fts4_uni WHERE egraph_fts4_uni MATCH 'planner') "
                        + "AS egraph_fts4_unicode, "
                        + "(SELECT count(*) FROM egraph_fts3 WHERE egraph_fts3 MATCH 'variants') "
                        + "AS egraph_fts3_simple, "
                        + "(SELECT count(*) FROM egraph_fts4_aux WHERE term LIKE 's%') AS egraph_fts4_terms FROM ("
                        + query + ") AS egraph_fts4_q";
            case FTS4_AUX_CONTEXT:
                // offsets/snippet/matchinfo only work as bare projection items of a
                // SELECT whose FROM names the fts table directly - not inside an
                // aggregate, not through an alias, not in a nested subquery.
                return "SELECT egraph_fts4_aux_q.*, offsets(egraph_fts4) AS egraph_fts4_offsets, "
                        + "snippet(egraph_fts4, '[', ']', '...', -1, 6) AS egraph_fts4_snippet, "
                        + "length(matchinfo(egraph_fts4, 'pcx')) AS egraph_fts4_matchinfo, "
                        + "egraph_fts4.docid AS egraph_fts4_docid FROM (" + query
                        + ") AS egraph_fts4_aux_q, egraph_fts4 WHERE egraph_fts4 MATCH 'gamma OR sqlite OR planner'";
            case BLOOM_FILTER_CONTEXT:
                // A star join the planner answers with a Bloom filter on each
                // dimension (sqlite3ConstructBloomFilter).
                return "SELECT egraph_bloom_q.*, (SELECT count(*) FROM egraph_star_fact AS f "
                        + "JOIN egraph_star_dim1 AS a ON a.k = f.d1 JOIN egraph_star_dim2 AS b ON b.k = f.d2 "
                        + "WHERE a.label = 'd1-7' AND b.label = 'd2-9') AS egraph_bloom_hits, "
                        + "(SELECT count(*) FROM egraph_star_fact AS f JOIN egraph_star_dim1 AS a ON a.k = f.d1 "
                        + "WHERE a.label LIKE 'd1-1%') AS egraph_bloom_like FROM (" + query + ") AS egraph_bloom_q";
            case MULTI_INDEX_OR_ROWSET_CONTEXT:
                // Equality predicates on two indexed columns make the planner pick
                // MULTI-INDEX OR, whose rowid de-duplication is the RowSet code.
                return "SELECT egraph_or_q.*, (SELECT count(*) FROM egraph_or_probe "
                        + "WHERE a = 5 OR b = 7 OR a = 9) AS egraph_or_hits, "
                        + "(SELECT count(*) FROM egraph_or_probe WHERE a IN (1, 2, 3) OR b IN (4, 5, 6)) "
                        + "AS egraph_or_in, (SELECT sum(id) FROM egraph_or_probe WHERE a = 3 OR b = 11 OR id = 42) "
                        + "AS egraph_or_sum FROM (" + query + ") AS egraph_or_q";
            case INDEX_FUNCTION_VALUE_CONTEXT:
                // A function on the constant side of an indexed comparison, which
                // is evaluated at the index seek boundary (valueFromFunction).
                return "SELECT egraph_idxfn_q.*, (SELECT count(*) FROM egraph_or_probe WHERE a = abs(-7)) "
                        + "AS egraph_idxfn_abs, (SELECT count(*) FROM egraph_or_probe WHERE a = length('abcd')) "
                        + "AS egraph_idxfn_len, (SELECT count(*) FROM egraph_or_probe "
                        + "WHERE a = max(3, 5) AND b = min(11, 19)) AS egraph_idxfn_minmax, "
                        + "(SELECT count(*) FROM egraph_or_probe WHERE a IN (VALUES(3),(5),(7),(11))) "
                        + "AS egraph_idxfn_values FROM (" + query + ") AS egraph_idxfn_q";
            case SORTER_DEEP_MERGE_CONTEXT:
                // Same construction as SORTER_STRESS_CONTEXT, amplified far enough
                // (~21 MB) that the sorter needs more PMAs than it can merge in one
                // pass and has to build a merge tree (vdbeSorterAddToTree).
                // Measured at 0.2 s for the whole sort, so the extra volume is cheap.
                return "WITH RECURSIVE egraph_deep_amp(k) AS (VALUES(1) UNION ALL "
                        + "SELECT k + 1 FROM egraph_deep_amp WHERE k < 144) "
                        + "SELECT p.id, p.bucket FROM (" + query
                        + ") AS egraph_deep_q JOIN egraph_sorter_probe AS p ON p.bucket >= 0 "
                        + "CROSS JOIN egraph_deep_amp ORDER BY p.payload, p.id";
            case ROW_VALUE_CONTEXT:
                // Row-value comparisons in every supported position: =, IN (VALUES ...), BETWEEN and
                // IN over a subquery. For coverage: the four counts come from a fixed probe table and
                // not from the rows the rewrite selects, so both sides compute the same four numbers
                // and a wrong one is invisible here. The base rows beside them are what gets compared.
                return "SELECT egraph_rv.*, (SELECT count(*) FROM egraph_rowvalue_probe AS p "
                        + "WHERE (p.a, p.b) IN (VALUES (1, 'x'), (2, 'y'), (3, NULL), (4, 'z'))) "
                        + "AS egraph_rv_in, (SELECT count(*) FROM egraph_rowvalue_probe AS p "
                        + "WHERE (p.a, p.b) = (p.c, p.d)) AS egraph_rv_eq, "
                        + "(SELECT count(*) FROM egraph_rowvalue_probe AS p "
                        + "WHERE (p.a, p.b) BETWEEN (1, 'a') AND (9, 'zzz')) AS egraph_rv_between, "
                        + "(SELECT count(*) FROM egraph_rowvalue_probe AS p WHERE (p.a, p.b) IN "
                        + "(SELECT q2.c, q2.d FROM egraph_rowvalue_probe AS q2)) AS egraph_rv_subquery FROM ("
                        + query + ") AS egraph_rv";
            case AGGREGATE_ORDER_BY_CONTEXT:
                // group_concat with an in-aggregate ORDER BY. The wrapper collapses
                // to a single row, so the order-sensitive comparison this ORDER BY
                // triggers cannot produce a false mismatch from inner row order.
                return "SELECT count(*) AS egraph_agg_rows, "
                        + "group_concat(p.payload, ',' ORDER BY p.id DESC) AS egraph_agg_ordered, "
                        + "group_concat(DISTINCT p.bucket) AS egraph_agg_distinct FROM (" + query
                        + ") AS egraph_agg_q JOIN egraph_sorter_probe AS p ON p.bucket = 0";
            case WINDOW_RANGE_FULLSCAN_CONTEXT:
                // A RANGE frame with EXCLUDE drives the window full-scan path.
                // Collapsed to one row for the same reason as above.
                return "SELECT count(*) AS egraph_win_rows, sum(egraph_win_full.w) AS egraph_win_sum, "
                        + "max(egraph_win_full.w) AS egraph_win_max FROM (SELECT sum(p.id) OVER "
                        + "(ORDER BY p.id RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING "
                        + "EXCLUDE CURRENT ROW) AS w FROM (" + query
                        + ") AS egraph_win_q JOIN egraph_sorter_probe AS p ON p.bucket = 0) AS egraph_win_full";
            case JOIN_OPTIMIZER_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_join_q WHERE EXISTS (SELECT 1 FROM egraph_join_left AS l "
                        + "RIGHT JOIN egraph_join_right AS r ON r.k = l.k "
                        + "WHERE r.k >= 0 LIMIT 1)";
            case ALTER_FK_STRESS_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_alter_fk_q WHERE EXISTS (SELECT 1 FROM egraph_fk_child "
                        + "WHERE pid >= 0 LIMIT 1)";
            case INTEGRITY_CHECK_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_integrity_q WHERE EXISTS (SELECT 1 FROM egraph_integrity_probe "
                        + "WHERE k >= 0 LIMIT 1)";
            case SCALAR_AGGREGATE_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_scalar_func_q WHERE EXISTS (SELECT 1 FROM egraph_scalar_probe "
                        + "WHERE id >= 0 LIMIT 1)";
            case VIRTUAL_TABLE_UPDATE_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_vtab_update_q WHERE EXISTS (SELECT 1 FROM egraph_vtab_rt "
                        + "WHERE x1 <= 6.0 AND x2 >= 6.0 LIMIT 1)";
            case VIRTUAL_TABLE_SAVEPOINT_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_vtab_sp_q WHERE EXISTS (SELECT 1 FROM egraph_vtab_sp_fts "
                        + "WHERE egraph_vtab_sp_fts MATCH 'alpha OR beta' LIMIT 1) "
                        + "AND EXISTS (SELECT 1 FROM egraph_vtab_sp_rt "
                        + "WHERE x1 <= 2.0 AND x2 >= 2.0 LIMIT 1)";
            case FTS5_SECURE_DELETE_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_fts_secure_q WHERE EXISTS (SELECT 1 FROM egraph_fts_secure_vocab "
                        + "WHERE term >= 'a' LIMIT 1)";
            case JSONB_STRESS_CONTEXT:
                // Escaped object labels take jsonLabelCompareEscaped, which the plain
                // '$.a.b' paths never reach. For coverage: the projected values come from literals,
                // so the two sides compute the same ones and a wrong one stays invisible to this
                // oracle.
                return "SELECT egraph_jsonb_q.*, "
                        + "json_extract(json_object('a\"b', 7, 'c', 8), '$.\"a\\\"b\"') AS egraph_jsonb_esc, "
                        + "json_extract('{\"x\ty\":5,\"z\":6}', '$.\"x\ty\"') AS egraph_jsonb_esc2, "
                        + "(SELECT count(*) FROM json_tree('{\"a\\\"b\":{\"x\ty\":1}}')) "
                        + "AS egraph_jsonb_tree, "
                        + "json(jsonb_insert(jsonb('{\"a\":1}'), '$.b', 2)) AS egraph_jsonb_ins, "
                        + "json(jsonb_replace(jsonb('{\"a\":1}'), '$.a', 'x')) AS egraph_jsonb_rep, "
                        + "json(jsonb_remove(jsonb('{\"a\":1,\"b\":2}'), '$.b')) AS egraph_jsonb_rem, "
                        + "json(jsonb_set(jsonb('{\"a\":1}'), '$.a', json('[1,2]'))) AS egraph_jsonb_set, "
                        + "json(jsonb_patch(jsonb('{\"a\":1}'), '{\"b\":2}')) AS egraph_jsonb_patch, "
                        + "json(jsonb_array(1, 'two', NULL, 3.5)) AS egraph_jsonb_arr, "
                        + "json(jsonb_object('k', 1, 'j', 2)) AS egraph_jsonb_obj, "
                        + "json_valid(x'ff00ff', 8) AS egraph_jsonb_badblob, "
                        + "json_error_position('{\"a\":1,,}') AS egraph_jsonb_errpos, "
                        + "json_type(jsonb('{\"a\":1}'), '$.a') AS egraph_jsonb_type, "
                        + "json_pretty(jsonb('{\"a\":[1,2],\"b\":{\"c\":3}}')) AS egraph_jsonb_pretty, "
                        + "(SELECT count(*) FROM json_each(jsonb('[1,2,3,4]'))) AS egraph_jsonb_each, "
                        // The text-to-blob and blob-to-text translators are 300 lines between them,
                        // and each JSON type, escape and numeric form is a separate branch in both.
                        // json_valid's flag argument picks which of the two validators runs, and a
                        // blob that is not JSONB at all exercises the rejection path.
                        + "json(jsonb('{\"a\":1,\"b\":[1,2,{\"c\":null}],\"d\":true,\"e\":false,\"f\":1.5e10}')) "
                        + "AS egraph_jsonb_round, "
                        + "json(jsonb('[\"\\t\\n\\r\\b\\f\\/\\\\\",-0.0,1e-300,1e300]')) AS egraph_jsonb_esc3, "
                        + "json(jsonb('{\"n\":{\"d\":{\"dd\":{\"ddd\":[1,[2,[3,[4]]]]}}}}')) AS egraph_jsonb_deep, "
                        + "json_valid(jsonb('{\"a\":1}'), 1) + json_valid(jsonb('{\"a\":1}'), 2) "
                        + "+ json_valid(jsonb('{\"a\":1}'), 4) AS egraph_jsonb_flags, "
                        + "json_valid(x'00', 8) + json_valid(x'0c', 8) + json_valid(x'7c00ff', 8) "
                        + "AS egraph_jsonb_badblobs, "
                        + "json_error_position('[1,2') + json_error_position('{\"a\"}') "
                        + "+ json_error_position('[1,2,3]') AS egraph_jsonb_errpos2, "
                        + "json_array_length(jsonb('[1,2,3,4,5]')) AS egraph_jsonb_len, "
                        + "json(json_remove(jsonb('[1,2,3,4]'), '$[1]', '$[1]')) AS egraph_jsonb_rem2, "
                        + "json_pretty(jsonb('[1,[2,[3,[4,[5]]]]]')) AS egraph_jsonb_pretty2, "
                        // JSON5 is a second parser inside jsonTranslateTextToBlob: unquoted and
                        // single-quoted labels, hex numbers, a leading + or bare ., the three
                        // non-finite literals, trailing commas and both comment forms. None of it
                        // is reachable from the strict-JSON spellings above.
                        + "json('{a:1, b:2}') AS egraph_json5_bare, "
                        + "json('{\"a\":1, /* c */ \"b\":2}') AS egraph_json5_comment, "
                        + "json('[1,2,3,]') AS egraph_json5_trailing, "
                        + "json('{''a'':''single''}') AS egraph_json5_squote, "
                        + "json('{\"a\":0x1f, \"b\":+1.5, \"c\":.5, \"d\":5.}') AS egraph_json5_num, "
                        + "json('{\"a\":Infinity, \"b\":-Infinity, \"c\":NaN}') AS egraph_json5_nonfinite, "
                        + "json('{$dollar:1, _under:2, a1:3}') AS egraph_json5_ident, "
                        + "json(jsonb('{a:1, b:[1,2,], c:0xff}')) AS egraph_json5_blob, "
                        + "json_valid('{a:1}', 2) + json_valid('{a:1}', 1) + json_valid('{a:1}', 6) "
                        + "AS egraph_json5_valid, "
                        + "json_type('{a:Infinity}', '$.a') AS egraph_json5_type "
                        + "FROM (" + query
                        + ") AS egraph_jsonb_q WHERE json_valid(jsonb('{\"a\":[1,2],\"b\":{\"x\":3}}'), 8) = 1 "
                        + "AND json_extract(jsonb('{\"a\":[1,2],\"b\":{\"x\":3}}'), '$.b.x') = 3";
            case XFER_OPTIMIZATION_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_xfer_q WHERE EXISTS (SELECT 1 FROM egraph_xfer_dst WHERE a >= 0 LIMIT 1)";
            case MULTI_SELECT_ORDER_BY_CONTEXT:
                return "SELECT * FROM (" + query
                        + ") AS egraph_multiselect_q WHERE EXISTS (SELECT 1 FROM egraph_multiselect_probe "
                        + "WHERE v >= 0 LIMIT 1)";
            case COLD_FUNCTION_BATTERY_CONTEXT:
                // unistr and the percentile extension are compiled in but nothing in
                // the generator ever calls them. Every aggregate sits in a scalar
                // subquery over a literal VALUES list, never beside egraph_cf.*: a
                // bare column next to an aggregate makes SQLite pick an arbitrary
                // row, and the original and the variant pick different ones as soon
                // as their plans differ - a mismatch with no bug behind it.
                // The escapes are written as backslash sequences that reach SQLite
                // as-is, which is what exercises unistr's decoder; spelling them as
                // Java character escapes instead would put raw non-ASCII bytes into
                // the corpus and the replay script for no extra coverage. hex()
                // keeps the compared values ASCII as well.
                return "SELECT egraph_cf.*, "
                        + "hex(unistr('\\u00e9\\u4e2dabc')) AS egraph_cf_unistr, "
                        + "hex(unistr('\\U0001f600')) AS egraph_cf_unistr_hi, "
                        + "unistr('a\\\\b') AS egraph_cf_unistr_bs, "
                        + "unistr(x'4142') AS egraph_cf_unistr_blob, "
                        + "unistr(NULL) AS egraph_cf_unistr_null, "
                        + "unistr_quote('a''b') AS egraph_cf_uq_text, "
                        + "unistr_quote(x'00ff') AS egraph_cf_uq_blob, "
                        + "unistr_quote(NULL) AS egraph_cf_uq_null, "
                        + "unistr_quote(1.5) AS egraph_cf_uq_real, "
                        + "unistr_quote(7) AS egraph_cf_uq_int, "
                        // char(9, 10, 65) rather than the escape sequences: a real
                        // newline in the query text would split the line the replay
                        // writer emits, and unistr_quote is what has to produce the
                        // escaped form anyway.
                        + "unistr_quote(char(9, 10, 65)) AS egraph_cf_uq_ctrl, "
                        + "json('{\"a\":1}') -> 'a' AS egraph_cf_arrow, "
                        + "json('{\"a\":1}') ->> 'a' AS egraph_cf_arrow2, "
                        + "json_extract('{\"a\":[1,2,3]}', '$.a') AS egraph_cf_jx, "
                        + "json_quote(json('[1,2]')) AS egraph_cf_jq, "
                        + "json_insert('{\"a\":1}', '$.b', json('[2]')) AS egraph_cf_ji, "
                        + "json_array(json('{\"x\":1}'), 2) AS egraph_cf_ja, "
                        + "(SELECT json_group_array(value) FROM json_each('[1,2,3]')) AS egraph_cf_jga, "
                        + "(SELECT json_group_object(key, value) FROM json_each('{\"p\":1,\"q\":2}')) AS egraph_cf_jgo, "
                        // Literal forms the generator never writes: hex, digit separators, values
                        // that overflow an integer into a real, and the string-to-number
                        // conversions those feed. sqlite3DequoteNumber and the CAST paths are what
                        // these reach.
                        + "0x7fffffff + 0X10 + -0x10 AS egraph_cf_hex, "
                        + "1_000_000 + 0x1_0 + 1_0.5_0 AS egraph_cf_sep, "
                        + "9223372036854775807 + -9223372036854775808 AS egraph_cf_intmax, "
                        + "9223372036854775808 + 1e400 + 1e-400 AS egraph_cf_overflow, "
                        + "CAST('0x10' AS INTEGER) + CAST('1_0' AS INTEGER) + CAST(' 12abc' AS INTEGER) "
                        + "AS egraph_cf_castint, "
                        + "CAST('1e5' AS REAL) + CAST('inf' AS REAL) + CAST('-0' AS REAL) AS egraph_cf_castreal, "
                        + "typeof(x'01') || typeof(1.0) || typeof(9223372036854775808) AS egraph_cf_types, "
                        // printf conversions the workload never writes. The thousands separator and
                        // the ordinal form are SQLite's own extensions, and each flag character is
                        // a separate case in sqlite3_str_vappendf's parser.
                        + "printf('%,d|%,d|%,.2f', 1234567, -1234567, 1234567.891) AS egraph_cf_thousands, "
                        + "printf('%!d|%!d|%!d|%!d', 1, 2, 3, 11) AS egraph_cf_ordinal, "
                        + "printf('%-10d|%-10s|%-10.3f', 42, 'ab', 1.5) AS egraph_cf_leftjust, "
                        + "printf('% d|% i|% f', 42, -42, 1.5) AS egraph_cf_spaceflag, "
                        + "printf('%010.4f|%#x|%#o', 1.5, 255, 8) AS egraph_cf_altform, "
                        + "printf('%.0f|%.0e|%.20f', 2.5, 2.5, 1.0 / 3) AS egraph_cf_precision, "
                        + "printf('%5.1s|%.0s|%c%c', 'abcdef', 'abc', 65, 97) AS egraph_cf_strprec, "
                        + "printf('%1000d', 7) AS egraph_cf_widepad, "
                        + "printf('%d %d %d', 1) AS egraph_cf_missingargs, "
                        + "(SELECT median(column1) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_median, "
                        + "(SELECT median(column1) FROM (VALUES(1),(NULL),(3))) AS egraph_cf_median_null, "
                        + "(SELECT percentile(column1, 25) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_p25, "
                        + "(SELECT percentile(column1, 0) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_p0, "
                        + "(SELECT percentile(column1, 100) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_p100, "
                        + "(SELECT percentile_cont(column1, 0.5) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_pc, "
                        + "(SELECT percentile_disc(column1, 0.5) FROM (VALUES(1),(2),(3),(10))) AS egraph_cf_pd, "
                        // The window form goes through percentInverse, which the
                        // plain aggregate form never reaches. The ORDER BY is inside
                        // a scalar subquery over a literal VALUES list, so it cannot
                        // make the compared query order-sensitive.
                        + "(SELECT group_concat(egraph_cf_w) FROM (SELECT percentile_cont(column1, 0.5) OVER "
                        + "(ORDER BY column1 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS egraph_cf_w "
                        + "FROM (VALUES(1),(2),(3),(10)))) AS egraph_cf_pc_window "
                        + "FROM (" + query + ") AS egraph_cf";
            case COLD_DDL_TRIGGER_CONTEXT:
                // The DDL that this shape exists for ran in the setup. What is left
                // for the wrapper is to read the probe back, so a broken INSTEAD OF
                // trigger or a DROP CONSTRAINT that rewrote the table wrong shows up
                // as a wrong value instead of passing silently.
                return "SELECT egraph_cold_q.*, "
                        + "(SELECT count(*) FROM egraph_cold_view) AS egraph_cold_rows, "
                        + "(SELECT sum(v.a) FROM egraph_cold_view AS v WHERE v.a > 0) AS egraph_cold_sum, "
                        + "(SELECT count(*) FROM sqlite_master WHERE type = 'trigger' "
                        + "AND tbl_name = 'egraph_cold_view') AS egraph_cold_triggers "
                        + "FROM (" + query + ") AS egraph_cold_q";
            case PLAIN:
                return query;
            default:
                throw new AssertionError(shape);
        }
    }

    private static void addMultiIndexOrIndexes(SQLite3GlobalState state, SQLite3Table table,
            SQLite3Column leftColumn, SQLite3Column rightColumn) throws Exception {
        if (!hasUsableIdentifier(table.getName()) || !hasUsableIdentifier(leftColumn) || !hasUsableIdentifier(rightColumn)) {
            return;
        }
        if (table.isVirtual()) {
            // CREATE INDEX is not allowed on a virtual table. Reachable since R-Tree tables became
            // eligible base-query targets: the statement raised an unexpected AssertionError and
            // aborted the whole database generation, which is why the R-Tree table was never
            // actually queried in the first run after that change.
            return;
        }
        String tableName = quoteIdentifier(table.getName());
        String leftColumnName = quoteIdentifier(leftColumn.getName());
        String rightColumnName = quoteIdentifier(rightColumn.getName());
        String leftIndexName = quoteIdentifier("egraph_mio_" + sanitizeIdentifierPart(table.getName()) + "_"
                + sanitizeIdentifierPart(leftColumn.getName()));
        String rightIndexName = quoteIdentifier("egraph_mio_" + sanitizeIdentifierPart(table.getName()) + "_"
                + sanitizeIdentifierPart(rightColumn.getName()));
        state.executeStatement(new SQLQueryAdapter("PRAGMA automatic_index=ON", false));
        state.executeStatement(new SQLQueryAdapter(
                "CREATE INDEX IF NOT EXISTS " + leftIndexName + " ON " + tableName + "("
                        + leftColumnName + ")",
                false));
        state.executeStatement(new SQLQueryAdapter(
                "CREATE INDEX IF NOT EXISTS " + rightIndexName + " ON " + tableName + "("
                        + rightColumnName + ")",
                false));
    }

    private static boolean prepareHighCoverageContext(SQLite3GlobalState state, SQLite3Table table,
            List<SQLite3Column> columns, SQLite3Column selectedColumn, EGraphCoverageShape shape) {
        switch (shape) {
            case FTS5_MATCH_CONTEXT:
                return setupFts5Context(state);
            case FTS5_DEEP_CONTEXT:
                return setupFts5DeepContext(state);
            case RTREE_CONTEXT:
                return setupRtreeContext(state);
            case RTREE_DEEP_CONTEXT:
                return setupRtreeDeepContext(state);
            case DBSTAT_CONTEXT:
                return setupDbstatContext(state);
            case VIEW_TRIGGER_FK_CONTEXT:
                return setupViewTriggerFkContext(state);
            case ANALYZE_INDEX_CONTEXT:
                if (columns.isEmpty()) {
                    return false;
                }
                return setupAnalyzeIndexContext(state, table, selectedColumn);
            case JSON_CONTEXT:
                return true;
            case TX_WAL_VACUUM_CONTEXT:
                return setupTxWalVacuumContext(state);
            case AUTO_VACUUM_INTEGRITY_CONTEXT:
                return setupAutoVacuumIntegrityContext(state);
            case ATTACH_VACUUM_WAL_CONTEXT:
                return setupAttachVacuumWalContext(state);
            case ALTER_INDEX_ANALYZE_CONTEXT:
                return setupAlterIndexAnalyzeContext(state);
            case SELECT_WHERE_STRESS_CONTEXT:
                return setupSelectWhereStressContext(state);
            case EXPR_STRESS_CONTEXT:
                return setupExprStressContext(state);
            case WINDOW_STRESS_CONTEXT:
                return setupWindowStressContext(state);
            case RESOLVE_STRESS_CONTEXT:
                return setupResolveStressContext(state);
            case SORTER_STRESS_CONTEXT:
            case AGGREGATE_ORDER_BY_CONTEXT:
            case WINDOW_RANGE_FULLSCAN_CONTEXT:
            case WINDOW_FUNCTION_BATTERY_CONTEXT:
                return setupSorterStressContext(state);
            case ROW_VALUE_CONTEXT:
                return setupRowValueContext(state);
            case FTS4_MATCH_CONTEXT:
            case FTS4_AUX_CONTEXT:
                return setupFts4Context(state);
            case BLOOM_FILTER_CONTEXT:
                return setupStarJoinContext(state);
            case MULTI_INDEX_OR_ROWSET_CONTEXT:
            case INDEX_FUNCTION_VALUE_CONTEXT:
                return setupOrProbeContext(state);
            case SORTER_DEEP_MERGE_CONTEXT:
                return setupSorterStressContext(state);
            case FTS5_DEEP_QUERY_CONTEXT:
            case FTS5_AUX_DEEP_CONTEXT:
                return setupFts5DeepQueryContext(state);
            case FTS4_DEEP_SEGMENT_CONTEXT:
                return setupFts4DeepSegmentContext(state);
            case FTS5_VARIANT_CONFIG_CONTEXT:
                return setupFts5VariantConfigContext(state);
            case PRAGMA_VTAB_CONTEXT:
                return setupSorterStressContext(state);
            case FTS4_MERGE_LCS_CONTEXT:
                return setupFts4MergeLcsContext(state);
            case FTS3_TOKENIZE_TABLE_CONTEXT:
                return setupFts3TokenizeTableContext(state);
            case FTS5_TOMBSTONE_CONTEXT:
                return setupFts5TombstoneContext(state);
            case FTS5_TOKENIZER_VARIANT_CONTEXT:
                return setupFts5TokenizerVariantContext(state);
            case SQL_SYNTAX_BATTERY_CONTEXT:
                return setupSqlSyntaxBatteryContext(state);
            case COLD_FUNCTION_BATTERY_CONTEXT:
                // Pure wrapper: the built-ins need no schema of their own.
                return true;
            case COLD_DDL_TRIGGER_CONTEXT:
                return setupColdDdlTriggerContext(state);
            case JOIN_OPTIMIZER_CONTEXT:
                return setupJoinOptimizerContext(state);
            case ALTER_FK_STRESS_CONTEXT:
                return setupAlterFkStressContext(state);
            case INTEGRITY_CHECK_CONTEXT:
                return setupIntegrityCheckContext(state);
            case SCALAR_AGGREGATE_CONTEXT:
                return setupScalarAggregateContext(state);
            case VIRTUAL_TABLE_UPDATE_CONTEXT:
                return setupVirtualTableUpdateContext(state);
            case FTS5_SECURE_DELETE_CONTEXT:
                return setupFts5SecureDeleteContext(state);
            case VIRTUAL_TABLE_SAVEPOINT_CONTEXT:
                return setupVirtualTableSavepointContext(state);
            case JSONB_STRESS_CONTEXT:
                return setupJsonbStressContext(state);
            case XFER_OPTIMIZATION_CONTEXT:
                return setupXferOptimizationContext(state);
            case MULTI_SELECT_ORDER_BY_CONTEXT:
                return setupMultiSelectOrderByContext(state);
            default:
                return true;
        }
    }

    private static boolean setupFts4MergeLcsContext(SQLite3GlobalState state) {
        // A memory-mapped pager read path (winMapfile / getPageMMap) never opens
        // otherwise; it costs one pragma and applies to the whole connection.
        executeContextStatement(state, "PRAGMA mmap_size = 268435456", false);
        boolean ok = executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts4m", true);
        ok &= executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts4m USING fts4(title, body)", true);
        if (!ok) {
            return false;
        }
        // automerge=0 leaves one segment per batch, so a term's doclist really is
        // spread over several segments instead of being merged into one.
        executeContextStatement(state, "INSERT INTO egraph_fts4m(egraph_fts4m) VALUES('automerge=0')", false);
        for (int batch = 0; batch < 6; batch++) {
            int lo = batch * 32 + 1;
            int hi = lo + 31;
            executeContextStatement(state,
                    "WITH RECURSIVE n(x) AS (VALUES(" + lo + ") UNION ALL SELECT x + 1 FROM n WHERE x < " + hi + ") "
                            + "INSERT INTO egraph_fts4m(docid, title, body) SELECT x, printf('t%d alpha beta', x), "
                            + "printf('common alpha beta gamma w%d %s alpha beta delta zeta rare%d', x, "
                            + "CASE WHEN x % 64 = 1 THEN 'lcsanchor' ELSE 'plainword' END, x) FROM n",
                    false);
        }
        // Run the helper forms here as well: the wrapper only reaches whichever
        // cases the corpus sampler happens to keep, while context setup is replayed
        // on every coverage run.
        executeContextStatement(state, "SELECT docid, matchinfo(egraph_fts4m, 's') FROM egraph_fts4m "
                + "WHERE egraph_fts4m MATCH '\"lcsanchor alpha beta\"'", false);
        executeContextStatement(state, "SELECT docid, matchinfo(egraph_fts4m, 'pcxsal') FROM egraph_fts4m "
                + "WHERE egraph_fts4m MATCH 'alpha OR delta'", false);
        executeContextStatement(state, "SELECT docid, matchinfo(egraph_fts4m, 'y') FROM egraph_fts4m "
                + "WHERE egraph_fts4m MATCH 'alpha NEAR/3 delta'", false);
        executeContextStatement(state, "SELECT docid, offsets(egraph_fts4m) FROM egraph_fts4m "
                + "WHERE egraph_fts4m MATCH '\"alpha beta gamma\"'", false);
        // 'alpha' and 'beta' are adjacent in the body, so NEAR/2 actually matches
        // and the position lists get merged (fts3PoslistMerge). A wider NEAR over
        // tokens that sit further apart matches nothing and merges nothing.
        executeContextStatement(state, "SELECT docid, snippet(egraph_fts4m) FROM egraph_fts4m "
                + "WHERE egraph_fts4m MATCH 'alpha NEAR/2 beta'", false);
        // Descending docid order is the only way into sqlite3Fts3DoclistPrev.
        executeContextStatement(state,
                "SELECT docid FROM egraph_fts4m WHERE egraph_fts4m MATCH 'alpha' ORDER BY docid DESC LIMIT 64",
                false);
        return ok;
    }

    private static boolean setupFts3TokenizeTableContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts3tok USING fts3tokenize('unicode61')", true);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts3tokp USING fts3tokenize('porter')", true);
        // Drop the fts table before the content table it reads from.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts4c", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts_content_src", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_fts_content_src(id INTEGER PRIMARY KEY, ca TEXT, cb TEXT)", true);
        executeContextStatement(state,
                "INSERT INTO egraph_fts_content_src(id, ca, cb) VALUES "
                        + "(1, 'alpha one sqlite', 'body one coverage'), "
                        + "(2, 'beta two planner', 'body two variants'), "
                        + "(3, 'gamma three tokenizer', 'body three segment')",
                false);
        // content= without a column list makes fts3 read the column names off the
        // content table itself (fts3ContentColumns).
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE egraph_fts4c USING fts4(content=\"egraph_fts_content_src\")", true);
        if (ok) {
            executeContextStatement(state, "INSERT INTO egraph_fts4c(egraph_fts4c) VALUES('rebuild')", false);
            executeContextStatement(state, "SELECT token, start, \"end\", position FROM egraph_fts3tok "
                    + "WHERE input = 'Hello world foo-bar baz qux'", false);
            executeContextStatement(state,
                    "SELECT token FROM egraph_fts3tokp WHERE input = 'running jumped quickly testing'", false);
            executeContextStatement(state, "SELECT docid FROM egraph_fts4c WHERE egraph_fts4c MATCH 'alpha'", false);
        }
        return ok;
    }

    private static boolean setupFts5TombstoneContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5cd", true);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE egraph_fts5cd USING fts5(x, content='', contentless_delete=1)", true);
        if (!ok) {
            return false;
        }
        executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 128) "
                        + "INSERT INTO egraph_fts5cd(rowid, x) "
                        + "SELECT x, printf('tomb%d alpha common token%d', x, x) FROM n",
                false);
        // Deletes on a contentless_delete table are recorded as tombstones rather
        // than by rewriting the index, which is what the tombstone pages are for.
        executeContextStatement(state, "DELETE FROM egraph_fts5cd WHERE rowid % 3 = 0", false);
        executeContextStatement(state, "DELETE FROM egraph_fts5cd WHERE rowid % 5 = 0", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5cd(egraph_fts5cd) VALUES('merge')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5cd(egraph_fts5cd) VALUES('optimize')", false);
        executeContextStatement(state, "SELECT rowid FROM egraph_fts5cd WHERE egraph_fts5cd MATCH 'alpha'", false);
        executeContextStatement(state, "SELECT rowid FROM egraph_fts5cd WHERE egraph_fts5cd MATCH 'tomb*'", false);
        return ok;
    }

    private static boolean setupFts5TokenizerVariantContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5asc", true);
        ok &= executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5asc USING fts5(x, tokenize='ascii')",
                true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5uni", true);
        ok &= executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5uni USING fts5(x, "
                + "tokenize = \"unicode61 tokenchars '-_' separators '@'\")", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5none", true);
        ok &= executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5none USING fts5(x, y, detail=none)",
                true);
        // categories= drives sqlite3Fts5UnicodeCatParse, and trigram is a third tokenizer
        // implementation with its own LIKE handling; between them 93 lines the four-hour run never
        // reached. These are created but not required: an older build without them still works.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5cat", true);
        // sqlite3Fts5UnicodeCatParse walks a table of Unicode general-category names and only the
        // names actually written get their branch, so every category is spelled out here.
        boolean cat = executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5cat USING fts5(x, "
                + "tokenize = \"unicode61 categories 'Lu Ll Lt Lm Lo Mn Mc Me Nd Nl No Pc Pd Ps Pe Pi Pf Po "
                + "Sm Sc Sk So Zs Zl Zp Cc Cf Co Cs Cn' remove_diacritics 2\")", false);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5cat2", true);
        if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5cat2 USING fts5(x, "
                + "tokenize = \"unicode61 categories 'L* N* P* S* Z* C* M*'\")", false)) {
            executeContextStatement(state, "INSERT INTO egraph_fts5cat2(x) VALUES('one two three'), ('a.b,c')",
                    false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts5cat2 WHERE egraph_fts5cat2 MATCH 'one'",
                    false);
        }
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5tri", true);
        boolean tri = executeContextStatement(state,
                "CREATE VIRTUAL TABLE egraph_fts5tri USING fts5(x, tokenize='trigram')", false);
        // The porter stemmer is a chain of steps keyed off fixed suffix lists, and only the
        // suffixes actually present in a document reach their branch. These documents name every
        // suffix each step tests for, which is worth 137 lines; the three tokenizers above share
        // none of that code.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5por", true);
        if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_fts5por USING fts5(x, tokenize='porter')",
                false)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts5por(x) VALUES "
                            + "('relational conditional rational'), "
                            + "('valenci hesitanci digitizer'), "
                            + "('conformabli radicalli differentli vileli analogousli'), "
                            + "('vietnamization predication operator feudalism'), "
                            + "('decisiveness hopefulness callousness'), "
                            + "('formaliti sensitiviti sensibiliti'), "
                            + "('triplicate formative formalize electricity electrical'), "
                            + "('hopeful goodness revival allowance inference airliner'), "
                            + "('gaily plastered bowdlerize effective bushes'), "
                            + "('agreed disabled matting mating meeting milling messing'), "
                            + "('running jumped happiest sized troubled tanned falling')",
                    false);
            for (String stem : new String[] {"relate", "condition", "valence", "digitize", "conform", "vietnam",
                "decis", "formal", "electric", "hope", "run", "agree" }) {
                executeContextStatement(state,
                        "SELECT count(*) FROM egraph_fts5por WHERE egraph_fts5por MATCH '" + stem + "'", false);
            }
        }
        if (!ok) {
            return false;
        }
        if (cat) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts5cat(rowid, x) VALUES (1, 'Hello World 123'), "
                            + "(2, 'cafe naive 45'), (3, 'x1y2z3 alpha')",
                    false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts5cat WHERE egraph_fts5cat MATCH 'hello'",
                    false);
        }
        if (tri) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts5tri(rowid, x) VALUES (1, 'abcdef ghijkl'), (2, 'xyz123 abcdef')",
                    false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts5tri WHERE egraph_fts5tri MATCH 'bcd'",
                    false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts5tri WHERE x LIKE '%cde%'", false);
        }
        executeContextStatement(state,
                "INSERT INTO egraph_fts5asc(rowid, x) VALUES (1, 'Alpha BETA gamma'), (2, 'alpha delta EPSILON'), "
                        + "(3, 'Gamma zeta alpha')",
                false);
        executeContextStatement(state,
                "INSERT INTO egraph_fts5uni(rowid, x) VALUES (1, 'foo-bar baz@qux'), (2, 'foo_bar alpha'), "
                        + "(3, 'baz alpha foo-bar')",
                false);
        executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 64) "
                        + "INSERT INTO egraph_fts5none(rowid, x, y) "
                        + "SELECT x, printf('alpha beta w%d', x), printf('gamma w%d', x) FROM n",
                false);
        // A rank= configuration and an ORDER BY rank are what make fts5 resolve a
        // rank function; ORDER BY stays out of the wrapper for the oracle's sake.
        executeContextStatement(state,
                "INSERT INTO egraph_fts5asc(egraph_fts5asc, rank) VALUES('rank', 'bm25(2.0, 1.0)')", false);
        executeContextStatement(state,
                "SELECT rowid, rank FROM egraph_fts5asc WHERE egraph_fts5asc MATCH 'alpha' ORDER BY rank LIMIT 10",
                false);
        executeContextStatement(state, "SELECT rowid FROM egraph_fts5uni WHERE egraph_fts5uni MATCH '\"foo-bar\"'",
                false);
        executeContextStatement(state,
                "SELECT rowid FROM egraph_fts5none WHERE egraph_fts5none MATCH 'alpha AND gamma'", false);
        return ok;
    }

    private static boolean setupSqlSyntaxBatteryContext(SQLite3GlobalState state) {
        // Grammar forms the SQLancer SQLite3 generator never emits, so their
        // yy_reduce actions, VDBE opcodes and sqlite3Pragma branches were dead.
        // DDL/DML can only live here, not in the wrapper.
        boolean ok = executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_chi", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_chi2", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_par", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_strict", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_def", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_wr", true);
        // STRICT drives OP_TypeCheck; the option list after the closing paren is a
        // separate grammar rule again.
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_syn_strict(a INTEGER PRIMARY KEY, b TEXT NOT NULL, c INT, d ANY) STRICT", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_syn_wr(a INT, b TEXT, PRIMARY KEY(a)) WITHOUT ROWID, STRICT", true);
        // Every DEFAULT form, a two-argument type name, and both generated-column
        // spellings in one table.
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_syn_def(a INTEGER PRIMARY KEY, b INT DEFAULT (abs(-5)), c INT DEFAULT +7, "
                        + "d INT DEFAULT -7, e TEXT DEFAULT CURRENT_TIMESTAMP, f NUMERIC(10,2), g AS (b + c), "
                        + "h INT GENERATED ALWAYS AS (b * 2) STORED)",
                true);
        ok &= executeContextStatement(state, "CREATE TABLE egraph_syn_par(k INTEGER PRIMARY KEY, v TEXT)", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_syn_chi(id INTEGER PRIMARY KEY, k INT, "
                        + "FOREIGN KEY(k) REFERENCES egraph_syn_par(k) MATCH SIMPLE "
                        + "ON DELETE NO ACTION ON UPDATE NO ACTION DEFERRABLE INITIALLY IMMEDIATE)",
                true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_syn_chi2(id INTEGER PRIMARY KEY, k INT REFERENCES egraph_syn_par(k) "
                        + "ON INSERT SET NULL)",
                true);
        if (!ok) {
            return false;
        }
        executeContextStatement(state, "INSERT INTO egraph_syn_def DEFAULT VALUES", false);
        // The DEFAULT CURRENT_TIMESTAMP column definition is what the grammar rule
        // needs, but its value must not differ between context refreshes: the corpus
        // delta encoder compares snapshot statements textually and would rewrite a
        // full snapshot every single time.
        executeContextStatement(state, "UPDATE egraph_syn_def SET e = '2020-01-01 00:00:00'", false);
        executeContextStatement(state,
                "INSERT INTO egraph_syn_par(k, v) VALUES (1, 'p1'), (2, 'p2'), (3, 'p3'), (4, 'p4')", false);
        executeContextStatement(state, "INSERT INTO egraph_syn_chi(id, k) VALUES (1, 1), (2, 2), (3, 3)", false);
        executeContextStatement(state,
                "INSERT INTO egraph_syn_strict VALUES (1, 'a', 2, 3), (2, 'b', 3, 4), (3, 'c', 4, 5)", false);
        // Four upsert spellings: bare DO NOTHING, targeted DO UPDATE, a partial
        // index target, and DO UPDATE with its own WHERE.
        executeContextStatement(state, "INSERT INTO egraph_syn_strict VALUES (1, 'd', 9, 9) ON CONFLICT DO NOTHING",
                false);
        executeContextStatement(state,
                "INSERT INTO egraph_syn_strict VALUES (1, 'e', 8, 8) ON CONFLICT(a) DO UPDATE SET c = excluded.c",
                false);
        executeContextStatement(state,
                "INSERT INTO egraph_syn_strict VALUES (2, 'f', 7, 7) ON CONFLICT(a) WHERE a > 0 DO NOTHING", false);
        executeContextStatement(state, "INSERT INTO egraph_syn_strict VALUES (3, 'g', 6, 6) "
                + "ON CONFLICT(a) DO UPDATE SET c = excluded.c WHERE a > 1", false);
        // Row-value SET, aliased UPDATE/DELETE targets, and RETURNING.
        executeContextStatement(state, "UPDATE egraph_syn_strict SET (b, c) = ('z', 9) WHERE a = 1", false);
        executeContextStatement(state, "UPDATE egraph_syn_strict AS s SET c = c + 1 WHERE s.a = 1", false);
        executeContextStatement(state, "UPDATE main.egraph_syn_strict AS s SET c = c + 1 WHERE s.a = 2", false);
        executeContextStatement(state, "INSERT INTO egraph_syn_par(k, v) VALUES (5, 'p5') RETURNING k, v", false);
        executeContextStatement(state, "UPDATE egraph_syn_par SET v = v || '!' WHERE k = 5 RETURNING k", false);
        executeContextStatement(state, "DELETE FROM egraph_syn_strict AS s WHERE s.a = 3 RETURNING a, b", false);
        // Grammar productions that yy_reduce never reached. These are statements rather than
        // wrapper expressions on purpose: several need ORDER BY, and the oracle switches to an
        // order-sensitive row comparison as soon as that string appears in the query it compares.
        //
        // A parenthesised join carrying its own alias and ON clause is the single largest of them
        // at 29 lines; the rest are three-part names, IN over a literal list and over a
        // table-valued function, named windows that reference each other, the four frame EXCLUDE
        // forms, aggregate ORDER BY, chained compound selects, IS DISTINCT FROM, the two-argument
        // LIMIT, and three-keyword joins.
        for (String grammar : new String[] {
            "SELECT count(*) FROM egraph_syn_par JOIN (egraph_syn_chi JOIN egraph_syn_par AS p2 "
                    + "ON egraph_syn_chi.k = p2.k) AS sub ON egraph_syn_par.k = sub.k",
            "SELECT count(*) FROM (egraph_syn_par JOIN egraph_syn_chi ON egraph_syn_par.k = egraph_syn_chi.k) AS j1",
            "SELECT count(*) FROM egraph_syn_par LEFT JOIN (egraph_syn_chi AS c1 LEFT JOIN egraph_syn_chi AS c2 "
                    + "ON c1.k = c2.k) AS n ON egraph_syn_par.k = n.k",
            "SELECT count(*) FROM (egraph_syn_par NATURAL JOIN egraph_syn_chi) AS nj",
            "SELECT count(*) FROM (egraph_syn_par JOIN egraph_syn_chi USING (k)) AS uj",
            "SELECT count(*) FROM egraph_syn_par WHERE main.egraph_syn_par.k > 0",
            "SELECT main.egraph_syn_par.k FROM main.egraph_syn_par WHERE main.egraph_syn_par.k < 5",
            "SELECT count(*) FROM egraph_syn_par WHERE k IN (1, 2, 3, 4, 5)",
            "SELECT count(*) FROM egraph_syn_par WHERE k NOT IN (1, 2, 3)",
            "SELECT count(*) FROM egraph_syn_par WHERE v IN main.pragma_table_info('egraph_syn_par')",
            "SELECT group_concat(v ORDER BY k) FROM egraph_syn_par",
            "SELECT group_concat(v, '|' ORDER BY k DESC) FROM egraph_syn_par",
            "SELECT count(*) FROM (SELECT sum(k) OVER w1 AS s1, sum(k) OVER w2 AS s2 FROM egraph_syn_par "
                    + "WINDOW w1 AS (ORDER BY k), w2 AS (ORDER BY v))",
            "SELECT count(*) FROM (SELECT sum(k) OVER w2 FROM egraph_syn_par "
                    + "WINDOW w1 AS (PARTITION BY k % 3), w2 AS (w1 ORDER BY k))",
            "SELECT count(*) FROM (SELECT sum(k) OVER w3 FROM egraph_syn_par "
                    + "WINDOW w1 AS (ORDER BY k), w3 AS (w1 ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING))",
            "SELECT count(*) FROM (SELECT sum(k) OVER (ORDER BY k ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING "
                    + "EXCLUDE CURRENT ROW) FROM egraph_syn_par)",
            "SELECT count(*) FROM (SELECT sum(k) OVER (ORDER BY k RANGE BETWEEN UNBOUNDED PRECEDING AND "
                    + "CURRENT ROW EXCLUDE GROUP) FROM egraph_syn_par)",
            "SELECT count(*) FROM (SELECT sum(k) OVER (ORDER BY k GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING "
                    + "EXCLUDE TIES) FROM egraph_syn_par)",
            "SELECT count(*) FROM (SELECT sum(k) OVER (ORDER BY k ROWS UNBOUNDED PRECEDING "
                    + "EXCLUDE NO OTHERS) FROM egraph_syn_par)",
            "SELECT count(*) FROM (SELECT k FROM egraph_syn_par UNION SELECT k FROM egraph_syn_chi "
                    + "UNION SELECT 999)",
            "SELECT count(*) FROM (SELECT k FROM egraph_syn_par UNION ALL SELECT k FROM egraph_syn_chi "
                    + "EXCEPT SELECT 1)",
            "SELECT count(*) FROM (SELECT k FROM egraph_syn_par INTERSECT SELECT k FROM egraph_syn_chi "
                    + "UNION SELECT 1000)",
            "SELECT count(*) FROM egraph_syn_par WHERE k IS NOT DISTINCT FROM 5",
            "SELECT count(*) FROM egraph_syn_par WHERE v IS DISTINCT FROM 'p1'",
            "SELECT NULL IS DISTINCT FROM NULL, 1 IS DISTINCT FROM NULL",
            "SELECT count(*) FROM (SELECT k FROM egraph_syn_par LIMIT 2, 5)",
            "SELECT count(*) FROM (SELECT k FROM egraph_syn_par ORDER BY k LIMIT 1, 2)",
            "SELECT count(*) FROM egraph_syn_par NATURAL LEFT OUTER JOIN egraph_syn_chi",
            "SELECT count(*) FROM egraph_syn_par NATURAL RIGHT OUTER JOIN egraph_syn_chi",
            "SELECT count(*) FROM egraph_syn_par NATURAL FULL OUTER JOIN egraph_syn_chi",
            "PRAGMA main.cache_size(-2000)" }) {
            executeContextStatement(state, grammar, false);
        }
        // RAISE only exists inside a trigger body, and a body holding several statements is its own
        // production. Kept on a table of its own so the ABORT trigger cannot block anything above.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_trg", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_trglog", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_trg(a INT, b INT, c INT)", true)
                && executeContextStatement(state, "CREATE TABLE egraph_syn_trglog(msg TEXT)", true)) {
            executeContextStatement(state,
                    "CREATE TRIGGER egraph_syn_t1 AFTER INSERT ON egraph_syn_trg BEGIN "
                            + "INSERT INTO egraph_syn_trglog VALUES('ins'); SELECT 1; "
                            + "UPDATE egraph_syn_trglog SET msg = msg; END",
                    false);
            executeContextStatement(state,
                    "CREATE TRIGGER egraph_syn_t2 BEFORE UPDATE ON egraph_syn_trg FOR EACH ROW "
                            + "WHEN new.a > 1000 BEGIN SELECT RAISE(IGNORE); END",
                    false);
            executeContextStatement(state,
                    "CREATE TRIGGER egraph_syn_t3 BEFORE DELETE ON egraph_syn_trg BEGIN "
                            + "SELECT RAISE(ABORT, 'no deletes'); END",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_syn_trg VALUES(1, 1, 1), (2, 2, 2)", false);
            executeContextStatement(state, "UPDATE egraph_syn_trg SET a = 2000 WHERE c = 2", false);
            executeContextStatement(state, "UPDATE egraph_syn_trg SET b = b WHERE a < 5", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_syn_trglog", false);
        }
        // DEFAULT taking a bare keyword, and both deferrable spellings on a foreign key.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_defer", true);
        executeContextStatement(state,
                "CREATE TABLE egraph_syn_defer(a INT DEFAULT INDEXED, b INT DEFAULT CURRENT_TIMESTAMP, "
                        + "c INT REFERENCES egraph_syn_par(k) DEFERRABLE INITIALLY DEFERRED, "
                        + "d INT REFERENCES egraph_syn_par(k) NOT DEFERRABLE INITIALLY IMMEDIATE)",
                true);
        executeContextStatement(state, "INSERT INTO egraph_syn_defer(c, d) VALUES(1, 1)", false);
        executeContextStatement(state, "UPDATE egraph_syn_defer SET b = '2020-01-01 00:00:00'", false);
        // SAVEPOINT nesting, which is a different opcode from BEGIN, and the three explicit
        // transaction kinds.
        for (String tx : new String[] {
            "BEGIN", "SAVEPOINT egraph_sp1", "INSERT INTO egraph_syn_trg VALUES(3, 3, 3)",
            "SAVEPOINT egraph_sp2", "INSERT INTO egraph_syn_trg VALUES(4, 4, 4)",
            "ROLLBACK TO egraph_sp2", "RELEASE egraph_sp2",
            "ROLLBACK TO SAVEPOINT egraph_sp1", "RELEASE SAVEPOINT egraph_sp1", "COMMIT",
            "BEGIN IMMEDIATE", "INSERT INTO egraph_syn_trg VALUES(5, 5, 5)", "COMMIT",
            "BEGIN EXCLUSIVE", "INSERT INTO egraph_syn_trg VALUES(6, 6, 6)", "ROLLBACK",
            "BEGIN DEFERRED", "SELECT count(*) FROM egraph_syn_trg", "END" }) {
            executeContextStatement(state, tx, false);
        }
        // An unrecognised PRAGMA name is handed to the VFS through SQLITE_FCNTL_PRAGMA, which is a
        // branch no spelled-out pragma reaches. The boolean pragmas below are each set and put back
        // in the same breath; reverse_unordered_selects in particular must not be left on, since it
        // would change the row order the oracle compares. writable_schema is deliberately absent:
        // it lets a statement corrupt sqlite_master, which has no place in a correctness tool. So is
        // case_sensitive_like: setting it either way re-registers LIKE without SQLITE_DETERMINISTIC,
        // after which any index using LIKE makes the schema read back as corrupt.
        for (String pragma : new String[] {
            "PRAGMA egraph_unknown_pragma", "PRAGMA egraph_unknown_pragma = 5",
            "PRAGMA main.egraph_another_unknown = 'x'",
            "PRAGMA short_column_names = OFF", "PRAGMA short_column_names = ON",
            "PRAGMA full_column_names = ON", "PRAGMA full_column_names = OFF",
            "PRAGMA trusted_schema = OFF", "PRAGMA trusted_schema = ON",
            "PRAGMA cell_size_check = ON", "PRAGMA cell_size_check = OFF",
            "PRAGMA reverse_unordered_selects = ON", "PRAGMA reverse_unordered_selects = OFF",
            "PRAGMA automatic_index = OFF", "PRAGMA automatic_index = ON",
            "PRAGMA recursive_triggers = ON", "PRAGMA recursive_triggers = OFF",
            "PRAGMA checkpoint_fullfsync = ON", "PRAGMA checkpoint_fullfsync = OFF",
            "PRAGMA fullfsync = ON", "PRAGMA fullfsync = OFF",
            "PRAGMA analysis_limit = 100", "PRAGMA analysis_limit",
            "PRAGMA busy_timeout = 100", "PRAGMA busy_timeout", "PRAGMA shrink_memory" }) {
            executeContextStatement(state, pragma, false);
        }
        // OP_TypeCheck exists only for a STRICT table, and every declared type in one has its own
        // branch there.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_st", true);
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_st(a INT, b INTEGER, c REAL, d TEXT, e BLOB, f ANY) STRICT", true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_st VALUES(1, 2, 3.5, 'x', x'00', 'anything')",
                    false);
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_st VALUES(-1, 9223372036854775807, 1e300, '', x'', 42)", false);
            executeContextStatement(state, "UPDATE egraph_syn_st SET a = a + 1 WHERE b > 0", false);
            executeContextStatement(state, "SELECT count(*), typeof(f) FROM egraph_syn_st GROUP BY typeof(f)",
                    false);
        }
        // lookupName resolves a USING column of a RIGHT or FULL join through a separate path that
        // has to merge both sides, and the ambiguous-name errors below are branches of their own.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_ja", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_jb", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_ja(k INT, v TEXT, w INT)", true)
                && executeContextStatement(state, "CREATE TABLE egraph_syn_jb(k INT, v TEXT, x INT)", true)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_ja VALUES(1, 'a', 10), (2, 'b', 20), (3, 'c', 30)", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_jb VALUES(2, 'B', 200), (3, 'C', 300), (4, 'D', 400)", false);
            for (String joined : new String[] {
                "SELECT count(k) FROM egraph_syn_ja FULL OUTER JOIN egraph_syn_jb USING (k)",
                "SELECT count(v) FROM egraph_syn_ja FULL OUTER JOIN egraph_syn_jb USING (k, v)",
                "SELECT count(k) FROM egraph_syn_ja RIGHT JOIN egraph_syn_jb USING (k)",
                "SELECT count(*) FROM egraph_syn_ja RIGHT JOIN egraph_syn_jb USING (k, v)",
                "SELECT count(k) FROM egraph_syn_ja NATURAL FULL OUTER JOIN egraph_syn_jb",
                "SELECT count(k) FROM egraph_syn_ja NATURAL RIGHT OUTER JOIN egraph_syn_jb",
                "SELECT count(*) FROM egraph_syn_ja FULL JOIN egraph_syn_jb USING (k) WHERE k IS NOT NULL",
                "SELECT count(k) FROM (egraph_syn_ja FULL JOIN egraph_syn_jb USING (k)) "
                        + "FULL JOIN egraph_syn_ja AS jc USING (k)",
                "SELECT count(rowid) FROM egraph_syn_ja RIGHT JOIN egraph_syn_jb USING (k)",
                "SELECT count(*) FROM egraph_syn_ja AS x FULL JOIN egraph_syn_jb AS y USING (k) "
                        + "LEFT JOIN egraph_syn_ja AS z USING (k)",
                "SELECT k FROM egraph_syn_ja FULL JOIN egraph_syn_jb USING (k) GROUP BY k HAVING count(*) > 0" }) {
                executeContextStatement(state, joined, false);
            }
        }
        // OE_Replace against a NOT NULL column, and the second pass that generated columns force.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_rg", true);
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_rg(a INTEGER PRIMARY KEY, b INT NOT NULL ON CONFLICT REPLACE DEFAULT 7, "
                        + "c TEXT NOT NULL DEFAULT 'dflt', g AS (b * 2), "
                        + "h INT GENERATED ALWAYS AS (b + 1) STORED)",
                true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_rg(a, b, c) VALUES(1, 1, 'x'), (2, 2, 'y')",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_syn_rg(a, b, c) VALUES(3, NULL, 'z')", false);
            executeContextStatement(state, "INSERT OR REPLACE INTO egraph_syn_rg(a, b, c) VALUES(1, NULL, 'w')",
                    false);
            executeContextStatement(state, "UPDATE egraph_syn_rg SET b = NULL WHERE a = 2", false);
            executeContextStatement(state, "UPDATE OR REPLACE egraph_syn_rg SET b = NULL WHERE a = 2", false);
            executeContextStatement(state, "SELECT count(*), sum(g), sum(h) FROM egraph_syn_rg", false);
        }
        // OP_NewRowid falls back to picking a random rowid once the largest one in the table is
        // 2^63-1, and it walks the frame chain when the insert happens inside a trigger.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_re", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_re2", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_re(k INTEGER PRIMARY KEY, v TEXT)", true)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_re(k, v) VALUES(9223372036854775807, 'max')", false);
            for (int i = 0; i < 4; i++) {
                executeContextStatement(state, "INSERT INTO egraph_syn_re(v) VALUES('r" + i + "')", false);
            }
            if (executeContextStatement(state, "CREATE TABLE egraph_syn_re2(k INTEGER PRIMARY KEY, v TEXT)",
                    true)) {
                executeContextStatement(state,
                        "CREATE TRIGGER egraph_syn_re2_t AFTER INSERT ON egraph_syn_re2 BEGIN "
                                + "INSERT INTO egraph_syn_re(v) VALUES('from-trigger'); END",
                        false);
                executeContextStatement(state, "INSERT INTO egraph_syn_re2(v) VALUES('x'), ('y')", false);
            }
            executeContextStatement(state, "SELECT count(*) FROM egraph_syn_re", false);
        }
        // OP_TypeCheck skips a virtual generated column and checks only part of the row in its p3
        // form, neither of which a STRICT table without generated columns reaches.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_sg", true);
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_sg(a INT, b TEXT, c REAL, v INT AS (a * 2) VIRTUAL, "
                        + "s INT GENERATED ALWAYS AS (a + 1) STORED, d ANY, e BLOB) STRICT",
                true)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_sg(a, b, c, d, e) VALUES(1, 'x', 1.5, 'any', x'00'), "
                            + "(2, 'y', 2.5, 7, x'01')",
                    false);
            executeContextStatement(state, "UPDATE egraph_syn_sg SET a = a + 1", false);
            executeContextStatement(state, "UPDATE egraph_syn_sg SET d = NULL WHERE a = 2", false);
            executeContextStatement(state, "SELECT count(*), sum(v), sum(s) FROM egraph_syn_sg", false);
        }
        // OP_Halt carries the constraint's own name when one is given, and RAISE inside a trigger
        // reaches a different branch again.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_hm", true);
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_hm(a INT CHECK(a > 0), b TEXT NOT NULL, c INT UNIQUE, "
                        + "CONSTRAINT egraph_named_check CHECK(a < 1000))",
                true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_hm VALUES(1, 'x', 1)", false);
            executeContextStatement(state, "INSERT OR IGNORE INTO egraph_syn_hm VALUES(-1, 'y', 2)", false);
            executeContextStatement(state, "INSERT OR IGNORE INTO egraph_syn_hm VALUES(1, NULL, 3)", false);
            executeContextStatement(state, "INSERT OR IGNORE INTO egraph_syn_hm VALUES(1, 'z', 1)", false);
            executeContextStatement(state, "INSERT OR IGNORE INTO egraph_syn_hm VALUES(5000, 'w', 4)", false);
        }
        // sqlite3Update takes a different path when the statement assigns to the rowid or to the
        // primary key, and the rowid has four spellings that all have to be recognised.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_ur", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_ur2", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_ur(k INTEGER PRIMARY KEY, v TEXT)", true)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_ur(k, v) VALUES(1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')", false);
            executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_syn_ur_v ON egraph_syn_ur(v)",
                    false);
            executeContextStatement(state, "UPDATE egraph_syn_ur SET k = k + 1000 WHERE k = 1", false);
            executeContextStatement(state, "UPDATE egraph_syn_ur SET rowid = rowid + 5000 WHERE k = 2", false);
            executeContextStatement(state, "UPDATE egraph_syn_ur SET _rowid_ = _rowid_ + 9000 WHERE k = 3",
                    false);
            executeContextStatement(state, "UPDATE egraph_syn_ur SET oid = oid + 11000 WHERE k = 4", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_syn_ur", false);
        }
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_ur2(a TEXT, b INT, PRIMARY KEY(a, b)) WITHOUT ROWID", true)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_syn_ur2(a, b) VALUES('p', 1), ('q', 2), ('r', 3)", false);
            executeContextStatement(state, "UPDATE egraph_syn_ur2 SET a = a || 'x' WHERE b = 1", false);
            executeContextStatement(state, "UPDATE egraph_syn_ur2 SET b = b + 1000 WHERE b = 2", false);
            executeContextStatement(state, "UPDATE egraph_syn_ur2 SET a = a, b = b WHERE b = 3", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_syn_ur2", false);
        }
        // A generated column that reaches itself is reported through COLFLAG_BUSY, and a chain of
        // them makes sqlite3ExprCodeTarget recurse before it can decide.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_gl", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_gl2", true);
        executeContextStatement(state,
                "CREATE TABLE egraph_syn_gl(a INT, b INT AS (c + 1), c INT AS (b + 1))", false);
        if (executeContextStatement(state,
                "CREATE TABLE egraph_syn_gl2(a INT, b INT AS (a + 1), c INT AS (b + 1), d INT AS (c + 1), "
                        + "e INT AS (d + 1), s INT GENERATED ALWAYS AS (a * 2) STORED)",
                true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_gl2(a) VALUES(1), (2), (3)", false);
            executeContextStatement(state, "UPDATE egraph_syn_gl2 SET a = a + 1", false);
            executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_syn_gl2_e ON egraph_syn_gl2(e)",
                    false);
            executeContextStatement(state,
                    "SELECT count(*), sum(b), sum(c), sum(d), sum(e), sum(s) FROM egraph_syn_gl2", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_syn_gl2 WHERE e > 0", false);
        }
        // A second pass over the productions yy_reduce still had cold. The parenthesised join with
        // its own alias is the one that keeps giving: round six wrote five spellings of it and only
        // eight of its twenty-nine lines came back, so this writes eight more.
        for (String grammar : new String[] {
            "SELECT count(*) FROM egraph_syn_par LEFT JOIN (egraph_syn_chi LEFT JOIN egraph_syn_par AS p3 "
                    + "USING (k)) AS s2 USING (k)",
            "SELECT count(*) FROM egraph_syn_par JOIN (egraph_syn_chi NATURAL JOIN egraph_syn_par AS p4) "
                    + "AS s3 USING (k)",
            "SELECT count(*) FROM (egraph_syn_par JOIN egraph_syn_chi USING (k)) AS s4 "
                    + "JOIN (egraph_syn_chi JOIN egraph_syn_par AS p5 USING (k)) AS s5 ON s4.k = s5.k",
            "SELECT count(*) FROM egraph_syn_par RIGHT JOIN (egraph_syn_chi CROSS JOIN egraph_syn_par AS p6) "
                    + "AS s6 ON egraph_syn_par.k = s6.k",
            "SELECT count(*) FROM egraph_syn_par FULL JOIN (egraph_syn_chi JOIN egraph_syn_par AS p7 ON 1) "
                    + "AS s7 ON egraph_syn_par.k = s7.k",
            "SELECT count(*) FROM (egraph_syn_par AS x JOIN egraph_syn_chi AS y ON x.k = y.k) AS s8",
            "SELECT count(*) FROM ((egraph_syn_par JOIN egraph_syn_chi USING (k)) "
                    + "JOIN egraph_syn_par AS p8 USING (k)) AS s9",
            // The empty IN list and the negated forms are separate branches from IN over values.
            "SELECT count(*) FROM egraph_syn_par WHERE k IN ()",
            "SELECT count(*) FROM egraph_syn_par WHERE k NOT IN ()",
            "SELECT count(*) FROM egraph_syn_par WHERE (k) IN (1, 2)",
            "SELECT count(*) FROM egraph_syn_par WHERE k IN (SELECT k FROM egraph_syn_chi)",
            "SELECT count(*) FROM egraph_syn_par WHERE k NOT IN (SELECT k FROM egraph_syn_chi)",
            "SELECT count(*) FROM egraph_syn_par WHERE v IN ('p1', 'p2', 'p3', 'p4', 'p5', 'p6')",
            // CASE with and without an operand, and with no ELSE.
            "SELECT CASE 1 WHEN 1 THEN 'a' END, CASE 1 WHEN 2 THEN 'a' WHEN 1 THEN 'b' ELSE 'c' END, "
                    + "CASE WHEN NULL THEN 1 END",
            // A bound parameter is a production of its own, and nothing in the workload writes one.
            // These fail at bind time, after the parser has already reduced the rule.
            "SELECT ?", "SELECT ?1 + ?2", "SELECT :name, @other, $third",
            "REINDEX", "REINDEX egraph_syn_par" }) {
            executeContextStatement(state, grammar, false);
        }
        // ALTER forms added in recent SQLite versions, and the two table-option error paths.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_alt", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_alt(a INT NOT NULL, b INT, c TEXT)", true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_alt VALUES(1, 1, 'x')", false);
            executeContextStatement(state, "ALTER TABLE egraph_syn_alt ALTER COLUMN a DROP NOT NULL", false);
            executeContextStatement(state, "ALTER TABLE egraph_syn_alt ALTER COLUMN b SET NOT NULL", false);
            executeContextStatement(state, "ALTER TABLE egraph_syn_alt ADD CHECK (b > 0)", false);
            executeContextStatement(state,
                    "ALTER TABLE egraph_syn_alt ADD CONSTRAINT egraph_syn_chk CHECK (b < 1000)", false);
        }
        executeContextStatement(state, "CREATE TABLE egraph_syn_badopt(a INT) SOMEUNKNOWNOPTION", false);
        executeContextStatement(state, "CREATE TABLE egraph_syn_badopt2(a INT PRIMARY KEY) WITHOUT SOMETHING",
                false);
        // Header pragmas write a word of the file header, which no query reads and the corpus
        // snapshot - which reads sqlite_master - does not capture. foreign_key_check needs an
        // actual dangling reference before it reports anything.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_fkc", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_fkp", true);
        if (executeContextStatement(state, "CREATE TABLE egraph_syn_fkp(id INTEGER PRIMARY KEY)", true)
                && executeContextStatement(state,
                        "CREATE TABLE egraph_syn_fkc(id INTEGER PRIMARY KEY, p INT REFERENCES egraph_syn_fkp(id))",
                        true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_fkp VALUES(1)", false);
            executeContextStatement(state, "INSERT INTO egraph_syn_fkc VALUES(1, 1), (2, 999)", false);
            executeContextStatement(state, "PRAGMA foreign_key_check", false);
            executeContextStatement(state, "PRAGMA foreign_key_check(egraph_syn_fkc)", false);
            executeContextStatement(state, "PRAGMA main.foreign_key_check", false);
        }
        for (String pragma : new String[] {
            "PRAGMA user_version = 42", "PRAGMA user_version", "PRAGMA main.user_version = 43",
            "PRAGMA application_id = 7", "PRAGMA application_id", "PRAGMA data_version",
            "PRAGMA schema_version", "PRAGMA freelist_count",
            "PRAGMA main.table_list(egraph_syn_par)", "PRAGMA table_list(egraph_syn_chi)",
            "PRAGMA integrity_check(egraph_syn_par)", "PRAGMA main.integrity_check(1)",
            "PRAGMA quick_check(egraph_syn_par)",
            "PRAGMA optimize(0x02)", "PRAGMA optimize(0x10)", "PRAGMA optimize(0x10002)",
            "PRAGMA main.optimize", "PRAGMA table_info(egraph_syn_par)",
            "PRAGMA main.table_xinfo(egraph_syn_par)", "PRAGMA index_list(egraph_syn_par)",
            "PRAGMA main.index_list(egraph_syn_chi)",
            // Set and read back, then put the default back: both are documented as unable to change
            // what a query returns, but leaving a connection setting altered is a habit worth not
            // having after what temp_store_directory did.
            "PRAGMA cache_spill = 1000", "PRAGMA cache_spill", "PRAGMA cache_spill = ON",
            "PRAGMA mmap_size = 1048576", "PRAGMA mmap_size", "PRAGMA mmap_size = 0" }) {
            executeContextStatement(state, pragma, false);
        }
        // ALTER forms, including renaming a virtual table (OP_VRename).
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_vt", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_syn_vt2", true);
        if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_syn_vt USING fts5(x)", true)) {
            executeContextStatement(state, "INSERT INTO egraph_syn_vt(rowid, x) VALUES (1, 'alpha beta')", false);
            executeContextStatement(state, "ALTER TABLE egraph_syn_vt RENAME TO egraph_syn_vt2", true);
        }
        executeContextStatement(state, "ALTER TABLE egraph_syn_chi2 DROP COLUMN k", true);
        // ORDER BY ... NULLS LAST/FIRST stays here: the oracle turns order-sensitive
        // the moment ORDER BY shows up anywhere in the compared query text.
        executeContextStatement(state,
                "SELECT a FROM egraph_syn_strict ORDER BY c NULLS LAST, a NULLS FIRST", false);
        executeContextStatement(state, "SELECT a FROM egraph_syn_strict ORDER BY c DESC NULLS FIRST", false);
        executeContextStatement(state,
                "SELECT a, sum(c) OVER w AS s FROM egraph_syn_strict WINDOW w AS (ORDER BY a) ORDER BY a LIMIT 5",
                false);
        executeContextStatement(state, "EXPLAIN SELECT 1", false);
        executeContextStatement(state, "EXPLAIN QUERY PLAN SELECT * FROM egraph_syn_par WHERE k = 1", false);
        // PRAGMA statements can only be issued here; a good part of the
        // sqlite3Pragma switch had never been entered.
        for (String pragma : new String[] { "PRAGMA max_page_count = 1000000", "PRAGMA data_version",
                "PRAGMA collation_list", "PRAGMA database_list", "PRAGMA compile_options", "PRAGMA function_list",
                "PRAGMA module_list", "PRAGMA pragma_list", "PRAGMA table_xinfo(egraph_syn_def)",
                "PRAGMA foreign_key_list(egraph_syn_chi)", "PRAGMA cache_spill = 1", "PRAGMA cell_size_check = 1",
                "PRAGMA reverse_unordered_selects = 1", "PRAGMA reverse_unordered_selects = 0",
                "PRAGMA legacy_alter_table = 1", "PRAGMA legacy_alter_table = 0", "PRAGMA analysis_limit = 400",
                "PRAGMA trusted_schema = 1", "PRAGMA defer_foreign_keys = 1", "PRAGMA defer_foreign_keys = 0",
                "PRAGMA shrink_memory", "PRAGMA optimize" }) {
            executeContextStatement(state, pragma, false);
        }
        return ok;
    }

    private static boolean setupColdDdlTriggerContext(SQLite3GlobalState state) {
        // Two forms the generator never emits. INSTEAD OF is its own grammar rule
        // (trigger_time ::= INSTEAD OF), so the CREATE alone lights up the parser
        // and the DML below lights up the execution path. DROP CONSTRAINT is a
        // 125-line function that had never been entered.
        //
        // Dropping and re-creating rather than CREATE IF NOT EXISTS: ALTER TABLE ...
        // DROP CONSTRAINT removes the constraint for good, so a refreshed context
        // has to rebuild the table or there is nothing left to drop the second time.
        // Every statement is fixed text and the net row state is the same after each
        // refresh, so the corpus delta encoder sees no change to re-serialize.
        executeContextStatement(state, "DROP VIEW IF EXISTS egraph_cold_view", true);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_cold_ck", true);
        boolean ok = executeContextStatement(state,
                // Two named CHECK constraints: one gets dropped below, the other
                // stays so the table still carries a constraint at query time.
                // UNIQUE and FOREIGN KEY constraints cannot be dropped at all
                // ("constraint may not be dropped"), so only CHECK reaches the
                // success path.
                "CREATE TABLE egraph_cold_ck(id INTEGER PRIMARY KEY, a INTEGER, "
                        + "CONSTRAINT egraph_cold_c_pos CHECK(a > -100), "
                        + "CONSTRAINT egraph_cold_c_hi CHECK(a < 1000))",
                true);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_cold_ck(id, a) VALUES (1, 10), (2, 20), (3, 30)", false);
        ok &= executeContextStatement(state,
                "CREATE VIEW egraph_cold_view AS SELECT id, a FROM egraph_cold_ck", true);
        ok &= executeContextStatement(state,
                "CREATE TRIGGER egraph_cold_view_ii INSTEAD OF INSERT ON egraph_cold_view "
                        + "BEGIN INSERT INTO egraph_cold_ck(id, a) VALUES (NEW.id, NEW.a); END",
                true);
        ok &= executeContextStatement(state,
                // FOR EACH ROW and a WHEN clause are separate grammar rules again.
                "CREATE TRIGGER egraph_cold_view_iu INSTEAD OF UPDATE OF a ON egraph_cold_view FOR EACH ROW "
                        + "WHEN NEW.a > 0 BEGIN UPDATE egraph_cold_ck SET a = NEW.a WHERE id = OLD.id; END",
                true);
        ok &= executeContextStatement(state,
                "CREATE TRIGGER egraph_cold_view_id INSTEAD OF DELETE ON egraph_cold_view "
                        + "BEGIN DELETE FROM egraph_cold_ck WHERE id = OLD.id; END",
                true);
        if (!ok) {
            return false;
        }
        // Insert, update and delete through the view. Row 4 is added and removed
        // again so the table ends in the same state every refresh; the replay
        // rewrites the INSERT to INSERT OR IGNORE, which an INSTEAD OF trigger
        // accepts unchanged.
        executeContextStatement(state, "INSERT INTO egraph_cold_view(id, a) VALUES (4, 40)", false);
        executeContextStatement(state, "UPDATE egraph_cold_view SET a = 41 WHERE id = 4", false);
        executeContextStatement(state, "DELETE FROM egraph_cold_view WHERE id = 4", false);
        // Last, so the view and the triggers are already in place when the table is
        // rewritten. They survive it: the triggers hang off the view, not the table.
        executeContextStatement(state, "ALTER TABLE egraph_cold_ck DROP CONSTRAINT egraph_cold_c_pos", true);
        return ok;
    }

    private static boolean setupFts5Context(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts USING fts5(title, body, tokenize='porter')", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_fts(rowid, title, body) VALUES "
                        + "(1, 'sqlite query planner', 'coverage driven sql testing for sqlite compiler'), "
                        + "(2, 'compiler pipeline', 'parser resolver optimizer code generator virtual machine'), "
                        + "(3, 'egraph variants', 'equivalent sql variants and metamorphic testing')",
                false);
        if (ok) {
            executeContextStatement(state, "INSERT INTO egraph_fts(egraph_fts, rank) VALUES('rank', 'bm25(10.0, 5.0)')",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_fts(egraph_fts, rank) VALUES('secure-delete', 1)",
                    false);
            executeContextStatement(state,
                    "SELECT rowid, bm25(egraph_fts), highlight(egraph_fts, 0, '[', ']'), "
                            + "snippet(egraph_fts, 1, '<', '>', '...', 8) FROM egraph_fts "
                            + "WHERE egraph_fts MATCH 'sqlite OR compiler OR variants' ORDER BY rank LIMIT 5",
                    false);
            executeContextStatement(state,
                    "SELECT rowid FROM egraph_fts WHERE egraph_fts MATCH '\"sqlite query\" NEAR(testing, 10)' "
                            + "ORDER BY rank LIMIT 5",
                    false);
            executeContextStatement(state, "DELETE FROM egraph_fts WHERE rowid = 2", false);
            executeContextStatement(state, "INSERT INTO egraph_fts(egraph_fts) VALUES('optimize')", false);
            executeContextStatement(state,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_vocab USING fts5vocab(egraph_fts, 'row')", true);
            executeContextStatement(state,
                    "SELECT term, doc, cnt FROM egraph_fts_vocab WHERE term >= 'a' ORDER BY term LIMIT 10", false);
        }
        return ok;
    }

    private static boolean setupFts5DeepContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_deep USING fts5(title, body, "
                        + "tokenize='unicode61 remove_diacritics 2', prefix='2 3 4')",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts_deep", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_fts_deep(rowid, title, body) VALUES "
                        + "(1, 'sqlite prefix tokenizer', 'sqlite query planner prefix tokenizer coverage'), "
                        + "(2, 'optimizer merge path', 'prefix posting lists merge optimize rebuild'), "
                        + "(3, 'unicode tokenizer', 'cafe cafe naive jalapeno token folding'), "
                        + "(4, 'near phrase search', 'alpha beta gamma sqlite testing planner variants'), "
                        + "(5, 'deletion tombstone', 'delete optimize rebuild prefix token data'), "
                        + "(6, 'ranking snippets', 'highlight snippet bm25 rank column size detail')",
                false);
        if (ok) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts_deep(egraph_fts_deep, rank) VALUES('rank', 'bm25(8.0, 3.0)')",
                    false);
            executeContextStatement(state,
                    "SELECT rowid, bm25(egraph_fts_deep), highlight(egraph_fts_deep, 0, '[', ']'), "
                            + "snippet(egraph_fts_deep, 1, '<', '>', '...', 10) "
                            + "FROM egraph_fts_deep WHERE egraph_fts_deep MATCH 'sqlite OR prefix OR tokenizer' "
                            + "ORDER BY rank LIMIT 6",
                    false);
            executeContextStatement(state,
                    "SELECT rowid FROM egraph_fts_deep WHERE egraph_fts_deep MATCH 'sql* OR optim* OR token*' "
                            + "ORDER BY rank LIMIT 6",
                    false);
            executeContextStatement(state,
                    "SELECT rowid FROM egraph_fts_deep WHERE egraph_fts_deep MATCH 'NEAR(sqlite testing, 8)' "
                            + "ORDER BY rank LIMIT 6",
                    false);
            executeContextStatement(state,
                    "DELETE FROM egraph_fts_deep WHERE egraph_fts_deep MATCH 'tombstone OR deletion'", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_fts_deep(rowid, title, body) VALUES "
                            + "(7, 'replacement prefix', 'replacement rows keep prefix tokenizer query nonempty')",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_fts_deep(egraph_fts_deep) VALUES('rebuild')",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_fts_deep(egraph_fts_deep) VALUES('optimize')",
                    false);
            executeContextStatement(state,
                    "INSERT INTO egraph_fts_deep(egraph_fts_deep, rank) VALUES('merge', 8)", false);
            executeContextStatement(state,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_deep_vocab "
                            + "USING fts5vocab(egraph_fts_deep, 'instance')",
                    true);
            executeContextStatement(state,
                    "SELECT term, doc, col, offset FROM egraph_fts_deep_vocab "
                            + "WHERE term >= 'a' ORDER BY term, doc LIMIT 24",
                    false);

            boolean triOk = executeContextStatement(state,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_trigram USING fts5(content, tokenize='trigram')",
                    true);
            if (triOk) {
                executeContextStatement(state, "DELETE FROM egraph_fts_trigram", false);
                executeContextStatement(state,
                        "INSERT INTO egraph_fts_trigram(rowid, content) VALUES "
                                + "(1, 'alphabet soup beta gamma'), (2, 'sqlite tokenizer trigram coverage'), "
                                + "(3, 'planner variant search space')",
                        false);
                executeContextStatement(state,
                        "SELECT rowid FROM egraph_fts_trigram WHERE egraph_fts_trigram MATCH 'eta' "
                                + "ORDER BY rowid LIMIT 8",
                        false);
            }

            boolean tokenDataOk = executeContextStatement(state,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_tokendata "
                            + "USING fts5(body, tokenize='unicode61', tokendata=1)",
                    true);
            if (tokenDataOk) {
                executeContextStatement(state, "DELETE FROM egraph_fts_tokendata", false);
                executeContextStatement(state,
                        "INSERT INTO egraph_fts_tokendata(rowid, body) VALUES "
                                + "(1, 'alpha beta token data'), (2, 'sqlite prefix token data')",
                        false);
                executeContextStatement(state,
                        "SELECT rowid FROM egraph_fts_tokendata WHERE egraph_fts_tokendata MATCH 'token' "
                                + "ORDER BY rank LIMIT 8",
                        false);
            }
        }
        return ok;
    }

    private static boolean setupRtreeContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_rtree USING rtree(id, x1, x2, y1, y2)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_rtree", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_rtree VALUES "
                        + "(1, 0.0, 10.0, 0.0, 10.0), "
                        + "(2, 5.0, 15.0, 5.0, 15.0), "
                        + "(3, -5.0, 1.0, -5.0, 1.0)",
                false);
        if (ok) {
            executeContextStatement(state, "UPDATE egraph_rtree SET x1 = x1 - 1.0, x2 = x2 + 1.0 WHERE id = 1",
                    false);
            executeContextStatement(state,
                    "DELETE FROM egraph_rtree WHERE id IN (SELECT id FROM egraph_rtree WHERE x2 < 2.0)", false);
            executeContextStatement(state, "SELECT rtreecheck('egraph_rtree')", false);
        }
        return ok;
    }

    private static boolean setupRtreeDeepContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_rtree_deep USING rtree(id, x1, x2, y1, y2)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_rtree_deep", false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 384) "
                        + "INSERT INTO egraph_rtree_deep "
                        + "SELECT x, (x % 32) * 1.0, (x % 32) * 1.0 + 4.0, "
                        + "((x / 32) % 32) * 1.0, ((x / 32) % 32) * 1.0 + 4.0 FROM n",
                false);
        if (ok) {
            executeContextStatement(state,
                    "UPDATE egraph_rtree_deep SET x1 = x1 - 0.25, x2 = x2 + 0.25 WHERE id % 17 = 0",
                    false);
            executeContextStatement(state,
                    "DELETE FROM egraph_rtree_deep WHERE id % 29 = 0 OR (x2 < 3.0 AND y2 < 3.0)", false);
            executeContextStatement(state,
                    "INSERT OR REPLACE INTO egraph_rtree_deep VALUES "
                            + "(10001, 11.0, 13.0, 11.0, 13.0), (10002, -50.0, -40.0, -50.0, -40.0)",
                    false);
            executeContextStatement(state, "SAVEPOINT egraph_rtree_deep_sp", false);
            executeContextStatement(state,
                    "INSERT OR REPLACE INTO egraph_rtree_deep VALUES (10003, 12.0, 14.0, 12.0, 14.0)",
                    false);
            executeContextStatement(state,
                    "UPDATE egraph_rtree_deep SET y1 = y1 - 1.0, y2 = y2 + 1.0 WHERE id = 10001", false);
            executeContextStatement(state, "ROLLBACK TO egraph_rtree_deep_sp", false);
            executeContextStatement(state, "RELEASE egraph_rtree_deep_sp", false);
            executeContextStatement(state,
                    "SELECT id FROM egraph_rtree_deep WHERE x1 <= 12.0 AND x2 >= 12.0 "
                            + "AND y1 <= 12.0 AND y2 >= 12.0 ORDER BY id LIMIT 32",
                    false);
            executeContextStatement(state, "SELECT rtreecheck('egraph_rtree_deep')", false);
        }
        return ok;
    }

    private static boolean setupDbstatContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_dbstat USING dbstat(main)",
                true);
        if (ok) {
            executeContextStatement(state,
                    "SELECT name, path, pageno, pagetype, ncell, payload FROM egraph_dbstat "
                            + "WHERE name IS NOT NULL ORDER BY pageno LIMIT 8",
                    false);
            executeContextStatement(state, "PRAGMA page_count", false);
            executeContextStatement(state, "PRAGMA freelist_count", false);
        }
        return ok;
    }

    private static boolean setupViewTriggerFkContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state, "PRAGMA foreign_keys=ON", false);
        try {
            ok &= executeContextStatement(state,
                    "CREATE TABLE IF NOT EXISTS egraph_parent(id INTEGER PRIMARY KEY, name TEXT UNIQUE)", true);
            ok &= executeContextStatement(state,
                    "CREATE TABLE IF NOT EXISTS egraph_child(id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES "
                            + "egraph_parent(id) ON UPDATE CASCADE ON DELETE SET NULL, value INTEGER CHECK(value BETWEEN "
                            + "0 AND 100))",
                    true);
            ok &= executeContextStatement(state,
                    "CREATE TABLE IF NOT EXISTS egraph_audit(action TEXT, old_value INTEGER, new_value INTEGER)", true);
            ok &= executeContextStatement(state,
                    "CREATE VIEW IF NOT EXISTS egraph_child_view AS SELECT c.id, p.name, c.value FROM egraph_child AS c "
                            + "LEFT JOIN egraph_parent AS p ON p.id = c.parent_id",
                    true);
            ok &= executeContextStatement(state,
                    "CREATE TRIGGER IF NOT EXISTS egraph_child_ai AFTER INSERT ON egraph_child BEGIN INSERT INTO "
                            + "egraph_audit VALUES('insert', NULL, NEW.value); END",
                    true);
            ok &= executeContextStatement(state,
                    "CREATE TRIGGER IF NOT EXISTS egraph_child_au AFTER UPDATE OF value ON egraph_child BEGIN INSERT INTO "
                            + "egraph_audit VALUES('update', OLD.value, NEW.value); END",
                    true);
            ok &= executeContextStatement(state,
                    "INSERT OR IGNORE INTO egraph_parent(id, name) VALUES (1, 'p1'), (2, 'p2')", false);
            ok &= executeContextStatement(state,
                    "INSERT OR IGNORE INTO egraph_child(id, parent_id, value) VALUES (10, 1, 20), (11, 2, 30)", false);
            ok &= executeContextStatement(state, "UPDATE egraph_parent SET id = 3 WHERE id = 1", false);
            ok &= executeContextStatement(state, "UPDATE egraph_child SET value = value + 1 WHERE value < 100", false);
            return ok;
        } finally {
            executeContextStatement(state, "PRAGMA foreign_keys=OFF", false);
        }
    }

    private static boolean setupAnalyzeIndexContext(SQLite3GlobalState state, SQLite3Table table,
            SQLite3Column column) {
        if (!hasUsableIdentifier(table.getName()) || !hasUsableIdentifier(column)) {
            return false;
        }
        if (table.isVirtual()) {
            // Same reason as addMultiIndexOrIndexes: no CREATE INDEX on virtual tables.
            return false;
        }
        String tableName = quoteIdentifier(table.getName());
        String columnName = quoteIdentifier(column.getName());
        String indexName = quoteIdentifier(getEGraphContextIndexName(table.getName(), column.getName()));
        boolean ok = executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columnName + ")",
                true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS " + quoteIdentifier(getEGraphContextIndexName(table.getName(), column.getName()) + "_partial")
                        + " ON " + tableName + "(" + columnName + ") WHERE " + columnName + " IS NOT NULL",
                true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS " + quoteIdentifier(getEGraphContextIndexName(table.getName(), column.getName()) + "_expr")
                        + " ON " + tableName + "(((" + columnName + ") + 1))",
                true);
        executeContextStatement(state, "ANALYZE " + tableName, false);
        executeContextStatement(state, "REINDEX " + indexName, false);
        return ok;
    }

    private static boolean setupTxWalVacuumContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_tx_probe(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_tx_probe", false);
        executeContextStatement(state, "PRAGMA journal_mode=WAL", false);
        executeContextStatement(state, "PRAGMA synchronous=NORMAL", false);
        executeContextStatement(state, "PRAGMA wal_autocheckpoint=64", false);

        boolean savepoint = executeContextStatement(state, "SAVEPOINT egraph_tx_probe_sp", false);
        if (savepoint) {
            executeContextStatement(state, "INSERT INTO egraph_tx_probe(id, value) VALUES (1, 10), (2, 20)", false);
            executeContextStatement(state, "UPDATE egraph_tx_probe SET value = value + 1 WHERE id = 1", false);
            executeContextStatement(state, "ROLLBACK TO egraph_tx_probe_sp", false);
            executeContextStatement(state, "RELEASE egraph_tx_probe_sp", false);
        }

        ok &= executeContextStatement(state,
                "INSERT OR REPLACE INTO egraph_tx_probe(id, value) VALUES (1, 10), (2, 20)", false);
        executeContextStatement(state, "PRAGMA wal_checkpoint(PASSIVE)", false);
        executeContextStatement(state, "PRAGMA quick_check", false);
        if (TX_CONTEXT_COUNT.incrementAndGet() % 512 == 0) {
            executeContextStatement(state, "VACUUM", false);
        }
        return ok;
    }

    private static boolean setupAutoVacuumIntegrityContext(SQLite3GlobalState state) {
        int count = AUTO_VACUUM_INTEGRITY_CONTEXT_COUNT.incrementAndGet();
        executeContextStatement(state, "PRAGMA auto_vacuum=INCREMENTAL", false);
        executeContextStatement(state, "PRAGMA page_size=512", false);
        if (count % 32 == 1) {
            executeContextStatement(state, "VACUUM", false);
        }

        boolean ok = true;
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_autovac_probe", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_autovac_probe(id INTEGER PRIMARY KEY, k INTEGER NOT NULL, "
                        + "payload TEXT, extra BLOB)",
                true);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 192) "
                        + "INSERT INTO egraph_autovac_probe(id, k, payload, extra) "
                        // Deterministic 128-char hex instead of hex(randomblob(64)): the payload
                        // has to be byte-identical across context refreshes, otherwise the corpus
                        // delta encoder sees a change every time and re-serialises all 192 rows
                        // into every snapshot. Same reasoning as SORTER_STRESS_CONTEXT.
                        + "SELECT x, x % 41, printf('autovac-%04d-%s', x, printf('%016X%016X%016X%016X%016X%016X%016X%016X', x, x * 7919, x * 104729, x * 1299709, x * 15485863, x * 179424673, x * 32452843, x * 49979687)), "
                        + "zeroblob(1400 + (x % 7) * 200) FROM n",
                false);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_autovac_k_payload ON egraph_autovac_probe(k, payload)",
                true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_autovac_payload_len "
                        + "ON egraph_autovac_probe((length(payload))) WHERE k >= 0",
                true);
        executeContextStatement(state, "DELETE FROM egraph_autovac_probe WHERE id % 3 = 0", false);
        executeContextStatement(state,
                "UPDATE egraph_autovac_probe SET extra = zeroblob(2048) WHERE id % 11 = 0", false);
        executeContextStatement(state, "PRAGMA freelist_count", false);
        executeContextStatement(state, "PRAGMA incremental_vacuum(8)", false);
        executeContextStatement(state, "PRAGMA integrity_check", false);
        executeContextStatement(state, "PRAGMA quick_check", false);
        if (count % 16 == 0) {
            executeContextStatement(state, "VACUUM", false);
        }
        executeContextStatement(state, "PRAGMA page_count", false);
        return ok;
    }

    private static boolean setupAttachVacuumWalContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_attach_probe(id INTEGER PRIMARY KEY, value INTEGER NOT NULL, "
                        + "note TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_attach_probe", false);
        ok &= executeContextStatement(state,
                "INSERT OR REPLACE INTO egraph_attach_probe(id, value, note) VALUES "
                        + "(1, 10, 'main-a'), (2, 20, 'main-b'), (3, 30, 'main-c')",
                false);

        executeContextStatement(state, "DETACH egraph_aux", false);
        executeContextStatement(state, "PRAGMA journal_mode=WAL", false);
        executeContextStatement(state, "PRAGMA synchronous=NORMAL", false);
        boolean attached = executeContextStatement(state, "ATTACH ':memory:' AS egraph_aux", false);
        if (attached) {
            executeContextStatement(state, "CREATE TABLE egraph_aux.t(id INTEGER PRIMARY KEY, v TEXT)", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_aux.t(v) VALUES('aux-a'), ('aux-b'), ('aux-c')", false);
            executeContextStatement(state, "SELECT count(*), max(v) FROM egraph_aux.t WHERE id > 0", false);
            executeContextStatement(state, "DETACH egraph_aux", false);
        }
        // A URI filename is parsed by sqlite3ParseUri, and every query parameter is its own branch
        // there; the memdb VFS behind these is a second body of code that a plain ':memory:' never
        // opens. Measured at 363 lines the four-hour run never reached, the largest single block
        // found anywhere in the uncovered set. The databases are detached again, so nothing here
        // can change what a later query returns.
        for (String uri : new String[] {
            "'file:egraph_uri1?vfs=memdb&cache=shared'",
            "'file:egraph_uri2?vfs=memdb&mode=memory&cache=private'",
            "'file:egraph_uri3?vfs=memdb&nolock=1&psow=0'",
            "'file:egraph_uri4?vfs=memdb&journal_mode=memory&synchronous=off'" }) {
            if (!executeContextStatement(state, "ATTACH DATABASE " + uri + " AS egraph_uri", false)) {
                continue;
            }
            executeContextStatement(state, "CREATE TABLE IF NOT EXISTS egraph_uri.u(id INTEGER PRIMARY KEY, v TEXT)",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_uri.u(v) VALUES('uri-a'), ('uri-b')", false);
            executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_uri.u_v ON u(v)", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_uri.u WHERE v > 'a'", false);
            executeContextStatement(state, "PRAGMA egraph_uri.page_count", false);
            executeContextStatement(state, "PRAGMA egraph_uri.integrity_check", false);
            executeContextStatement(state, "DETACH DATABASE egraph_uri", false);
        }
        // Overflow pages, freelist reuse and the page mover need rows wider than a page, and they
        // are built in an attached database on purpose: the corpus snapshot reads main's
        // sqlite_master only, so a 480 KB probe table here never reaches replay-all.sql. Measured
        // 94 lines, more than the same work in main, because memdb's own paging is on the way.
        if (executeContextStatement(state, "ATTACH DATABASE 'file:egraph_ovf?vfs=memdb' AS egraph_ovf", false)) {
            executeContextStatement(state,
                    "CREATE TABLE IF NOT EXISTS egraph_ovf.big(k INTEGER PRIMARY KEY, v BLOB, w TEXT)", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_ovf.big(k, v, w) SELECT x, zeroblob(12000), hex(zeroblob(4000)) FROM "
                            + "(WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 40) "
                            + "SELECT x FROM n)",
                    false);
            executeContextStatement(state, "UPDATE egraph_ovf.big SET v = zeroblob(24000) WHERE k % 3 = 0", false);
            executeContextStatement(state, "DELETE FROM egraph_ovf.big WHERE k % 2 = 0", false);
            executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_ovf.big_w ON big(w)", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_ovf.big WHERE w > '0'", false);
            executeContextStatement(state, "PRAGMA egraph_ovf.integrity_check", false);
            executeContextStatement(state, "PRAGMA egraph_ovf.freelist_count", false);
            executeContextStatement(state, "VACUUM egraph_ovf", false);
            executeContextStatement(state, "DETACH DATABASE egraph_ovf", false);
        }
        // The setting forms of the storage PRAGMAs, and a UTF-16 database. Both belong on a
        // throwaway attachment: encoding can only be chosen before the first table exists, and
        // nothing here is allowed to change what a query against main returns. The UTF-16 text
        // functions are what reach sqlite3VdbeMemTranslate, which no UTF-8 database enters.
        for (String encoding : new String[] {"UTF-16le", "UTF-16be" }) {
            if (!executeContextStatement(state, "ATTACH DATABASE 'file:egraph_enc?vfs=memdb' AS egraph_enc", false)) {
                continue;
            }
            executeContextStatement(state, "PRAGMA egraph_enc.encoding = '" + encoding + "'", false);
            executeContextStatement(state, "PRAGMA egraph_enc.encoding", false);
            executeContextStatement(state, "CREATE TABLE egraph_enc.u(a TEXT, b TEXT)", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_enc.u(a, b) VALUES('hello', 'world'), ('cafe', 'naive')", false);
            executeContextStatement(state,
                    "SELECT count(*), max(a), length(b), upper(a), lower(b), hex(a), substr(a, 1, 2), "
                            + "instr(a, 'e'), group_concat(a || '-' || b) FROM egraph_enc.u",
                    false);
            executeContextStatement(state,
                    "SELECT count(*) FROM egraph_enc.u WHERE a LIKE 'h%' AND b GLOB 'w*' "
                            + "AND a COLLATE NOCASE <> 'X'",
                    false);
            executeContextStatement(state, "PRAGMA egraph_enc.secure_delete = FAST", false);
            executeContextStatement(state, "PRAGMA egraph_enc.secure_delete", false);
            executeContextStatement(state, "PRAGMA egraph_enc.journal_size_limit = 65536", false);
            executeContextStatement(state, "PRAGMA egraph_enc.default_cache_size = 3000", false);
            executeContextStatement(state, "PRAGMA egraph_enc.locking_mode = EXCLUSIVE", false);
            executeContextStatement(state, "PRAGMA egraph_enc.locking_mode = NORMAL", false);
            executeContextStatement(state, "PRAGMA egraph_enc.optimize(0xfffe)", false);
            executeContextStatement(state, "PRAGMA egraph_enc.table_list", false);
            executeContextStatement(state, "PRAGMA egraph_enc.integrity_check(2)", false);
            // Every text value read out of a UTF-16 database by a UTF-8 connection has to be
            // translated, which is the only way into sqlite3VdbeMemTranslate.
            executeContextStatement(state,
                    "SELECT replace(a, 'l', 'L'), trim(a, 'ho'), ltrim(b), rtrim(b), "
                            + "printf('%s|%s', a, b), json_quote(a), CAST(a AS BLOB), CAST(b AS TEXT) "
                            + "FROM egraph_enc.u",
                    false);
            executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_enc.u_a ON u(a)", false);
            executeContextStatement(state,
                    "SELECT count(*) FROM egraph_enc.u WHERE a > 'a' AND a COLLATE NOCASE <> 'X'", false);
            executeContextStatement(state, "DETACH DATABASE egraph_enc", false);
        }
        // Reading these back is the branch; the values written are the defaults, so the connection
        // is left exactly as it was found.
        executeContextStatement(state, "PRAGMA hard_heap_limit", false);
        executeContextStatement(state, "PRAGMA soft_heap_limit", false);
        // Three things that need a few hundred rows to reach at all: FTS3's deferred-phrase
        // evaluation, which only fires when a phrase token is too common to iterate; the join
        // strategies the planner picks once ANALYZE has statistics; and the percentile window
        // functions, whose inverse step needs a moving frame over real data. All of it goes in an
        // attachment so none of those rows reach the corpus snapshot. Measured 115 lines - the same
        // work in main is worth 149, and the 34-line difference is not worth putting another
        // thousand rows into every captured case.
        if (executeContextStatement(state, "ATTACH DATABASE 'file:egraph_heavy?vfs=memdb' AS egraph_heavy", false)) {
            if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_heavy.fd USING fts4(x)", false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM n WHERE i < 400) "
                                + "INSERT INTO egraph_heavy.fd(x) "
                                + "SELECT 'common common common rare' || (i % 50) || ' filler' FROM n",
                        false);
                executeContextStatement(state, "INSERT INTO egraph_heavy.fd(fd) VALUES('optimize')", false);
                for (String match : new String[] {"\"common rare1\"", "common rare1", "\"common common\" rare2",
                    "common NEAR/2 rare3", "common -rare4", "\"common common common\"",
                    "rare5 OR (common rare6)" }) {
                    executeContextStatement(state,
                            "SELECT count(*) FROM egraph_heavy.fd WHERE fd MATCH '" + match + "'", false);
                }
                executeContextStatement(state,
                        "SELECT matchinfo(fd, 'pcx') FROM egraph_heavy.fd WHERE fd MATCH 'common rare8' LIMIT 2",
                        false);
            }
            if (executeContextStatement(state, "CREATE TABLE egraph_heavy.ws(a INT, b INT, c TEXT, d INT)", false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE s(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM s WHERE i < 800) "
                                + "INSERT INTO egraph_heavy.ws SELECT i % 40, i % 9, 'w' || i, i FROM s",
                        false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.ws_ab ON ws(a, b)", false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.ws_c ON ws(c)", false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.ws_d ON ws(d) WHERE d > 400", false);
                executeContextStatement(state, "ANALYZE egraph_heavy", false);
                for (String planned : new String[] {
                    "SELECT count(*) FROM egraph_heavy.ws WHERE a = 1 OR b = 2 OR c = 'w5'",
                    "SELECT count(*) FROM egraph_heavy.ws WHERE d > 500 AND d < 600",
                    "SELECT count(*) FROM egraph_heavy.ws AS x, egraph_heavy.ws AS y "
                            + "WHERE x.a = y.b AND x.d < 20",
                    "SELECT count(*) FROM egraph_heavy.ws AS x LEFT JOIN egraph_heavy.ws AS y "
                            + "ON x.a = y.a AND y.b > 5",
                    "SELECT count(*) FROM egraph_heavy.ws WHERE a IN (1, 2, 3) "
                            + "AND b IN (SELECT b FROM egraph_heavy.ws WHERE d < 50)",
                    "SELECT count(*) FROM egraph_heavy.ws WHERE c GLOB 'w1*'",
                    "SELECT count(*) FROM egraph_heavy.ws WHERE rowid IN "
                            + "(SELECT rowid FROM egraph_heavy.ws WHERE a = 5)",
                    "SELECT count(*) FROM (SELECT a, max(d) AS m FROM egraph_heavy.ws GROUP BY a) AS m "
                            + "JOIN egraph_heavy.ws ON egraph_heavy.ws.a = m.a",
                    "SELECT count(*) FROM egraph_heavy.ws AS x JOIN egraph_heavy.ws AS y USING (a) "
                            + "JOIN egraph_heavy.ws AS z USING (a) WHERE x.d < 10",
                    "SELECT count(*) FROM (SELECT percentile_cont(d, 0.5) OVER (ORDER BY d "
                            + "ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) FROM egraph_heavy.ws)",
                    "SELECT count(*) FROM (SELECT median(d) OVER (ORDER BY d "
                            + "ROWS BETWEEN 5 PRECEDING AND CURRENT ROW) FROM egraph_heavy.ws)",
                    "SELECT count(*) FROM (SELECT percentile(d, 25) OVER (ORDER BY d "
                            + "ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) FROM egraph_heavy.ws)",
                    "SELECT count(*) FROM (SELECT percentile_disc(d, 0.75) OVER (PARTITION BY a ORDER BY d "
                            + "ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING) FROM egraph_heavy.ws)",
                    "SELECT count(*) FROM (SELECT nth_value(d, 2) OVER (ORDER BY d "
                            + "ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) FROM egraph_heavy.ws)" }) {
                    executeContextStatement(state, planned, false);
                }
            }
            // sqlite3Fts5GetVarint decodes up to nine bytes and only a large rowid gets past the
            // first two, so the rowids here step over every varint width boundary.
            if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_heavy.fv USING fts5(x)", false)) {
                for (String rowid : new String[] {"1", "127", "128", "16383", "16384", "2097151", "2097152",
                    "268435455", "268435456", "34359738367", "34359738368", "4398046511103",
                    "562949953421311", "72057594037927935", "9223372036854775807" }) {
                    executeContextStatement(state,
                            "INSERT INTO egraph_heavy.fv(rowid, x) VALUES(" + rowid + ", 'a b c')", false);
                }
                executeContextStatement(state, "INSERT INTO egraph_heavy.fv(fv) VALUES('optimize')", false);
                executeContextStatement(state, "SELECT count(*) FROM egraph_heavy.fv WHERE fv MATCH 'b AND c'",
                        false);
                executeContextStatement(state, "SELECT count(*) FROM egraph_heavy.fv WHERE fv MATCH '\"a b\"'",
                        false);
                executeContextStatement(state,
                        "CREATE VIRTUAL TABLE egraph_heavy.fvv USING fts5vocab(fv, instance)", false);
                executeContextStatement(state, "SELECT count(*) FROM egraph_heavy.fvv", false);
            }

            // OP_Column reads the record header with a varint whose length grows with the column
            // count, and the branch guarded by aOffset[0] > 98307 needs hundreds of columns before
            // it can be taken. The second row is wide enough to spill onto overflow pages.
            if (executeContextStatement(state, "CREATE TABLE egraph_heavy.wide(c0 INT, c1 TEXT, c2 REAL, c3 BLOB, c4 ANY, c5 INT, c6 TEXT, c7 REAL, c8 BLOB, c9 ANY, c10 INT, c11 TEXT, c12 REAL, c13 BLOB, c14 ANY, c15 INT, c16 TEXT, c17 REAL, c18 BLOB, c19 ANY, c20 INT, c21 TEXT, c22 REAL, c23 BLOB, c24 ANY, c25 INT, c26 TEXT, c27 REAL, c28 BLOB, c29 ANY, c30 INT, c31 TEXT, c32 REAL, c33 BLOB, c34 ANY, c35 INT, c36 TEXT, c37 REAL, c38 BLOB, c39 ANY, c40 INT, c41 TEXT, c42 REAL, c43 BLOB, c44 ANY, c45 INT, c46 TEXT, c47 REAL, c48 BLOB, c49 ANY, c50 INT, c51 TEXT, c52 REAL, c53 BLOB, c54 ANY, c55 INT, c56 TEXT, c57 REAL, c58 BLOB, c59 ANY, c60 INT, c61 TEXT, c62 REAL, c63 BLOB, c64 ANY, c65 INT, c66 TEXT, c67 REAL, c68 BLOB, c69 ANY, c70 INT, c71 TEXT, c72 REAL, c73 BLOB, c74 ANY, c75 INT, c76 TEXT, c77 REAL, c78 BLOB, c79 ANY, c80 INT, c81 TEXT, c82 REAL, c83 BLOB, c84 ANY, c85 INT, c86 TEXT, c87 REAL, c88 BLOB, c89 ANY, c90 INT, c91 TEXT, c92 REAL, c93 BLOB, c94 ANY, c95 INT, c96 TEXT, c97 REAL, c98 BLOB, c99 ANY, c100 INT, c101 TEXT, c102 REAL, c103 BLOB, c104 ANY, c105 INT, c106 TEXT, c107 REAL, c108 BLOB, c109 ANY, c110 INT, c111 TEXT, c112 REAL, c113 BLOB, c114 ANY, c115 INT, c116 TEXT, c117 REAL, c118 BLOB, c119 ANY, c120 INT, c121 TEXT, c122 REAL, c123 BLOB, c124 ANY, c125 INT, c126 TEXT, c127 REAL, c128 BLOB, c129 ANY, c130 INT, c131 TEXT, c132 REAL, c133 BLOB, c134 ANY, c135 INT, c136 TEXT, c137 REAL, c138 BLOB, c139 ANY, c140 INT, c141 TEXT, c142 REAL, c143 BLOB, c144 ANY, c145 INT, c146 TEXT, c147 REAL, c148 BLOB, c149 ANY, c150 INT, c151 TEXT, c152 REAL, c153 BLOB, c154 ANY, c155 INT, c156 TEXT, c157 REAL, c158 BLOB, c159 ANY, c160 INT, c161 TEXT, c162 REAL, c163 BLOB, c164 ANY, c165 INT, c166 TEXT, c167 REAL, c168 BLOB, c169 ANY, c170 INT, c171 TEXT, c172 REAL, c173 BLOB, c174 ANY, c175 INT, c176 TEXT, c177 REAL, c178 BLOB, c179 ANY, c180 INT, c181 TEXT, c182 REAL, c183 BLOB, c184 ANY, c185 INT, c186 TEXT, c187 REAL, c188 BLOB, c189 ANY, c190 INT, c191 TEXT, c192 REAL, c193 BLOB, c194 ANY, c195 INT, c196 TEXT, c197 REAL, c198 BLOB, c199 ANY, c200 INT, c201 TEXT, c202 REAL, c203 BLOB, c204 ANY, c205 INT, c206 TEXT, c207 REAL, c208 BLOB, c209 ANY, c210 INT, c211 TEXT, c212 REAL, c213 BLOB, c214 ANY, c215 INT, c216 TEXT, c217 REAL, c218 BLOB, c219 ANY, c220 INT, c221 TEXT, c222 REAL, c223 BLOB, c224 ANY, c225 INT, c226 TEXT, c227 REAL, c228 BLOB, c229 ANY, c230 INT, c231 TEXT, c232 REAL, c233 BLOB, c234 ANY, c235 INT, c236 TEXT, c237 REAL, c238 BLOB, c239 ANY, c240 INT, c241 TEXT, c242 REAL, c243 BLOB, c244 ANY, c245 INT, c246 TEXT, c247 REAL, c248 BLOB, c249 ANY, c250 INT, c251 TEXT, c252 REAL, c253 BLOB, c254 ANY, c255 INT, c256 TEXT, c257 REAL, c258 BLOB, c259 ANY, c260 INT, c261 TEXT, c262 REAL, c263 BLOB, c264 ANY, c265 INT, c266 TEXT, c267 REAL, c268 BLOB, c269 ANY, c270 INT, c271 TEXT, c272 REAL, c273 BLOB, c274 ANY, c275 INT, c276 TEXT, c277 REAL, c278 BLOB, c279 ANY, c280 INT, c281 TEXT, c282 REAL, c283 BLOB, c284 ANY, c285 INT, c286 TEXT, c287 REAL, c288 BLOB, c289 ANY, c290 INT, c291 TEXT, c292 REAL, c293 BLOB, c294 ANY, c295 INT, c296 TEXT, c297 REAL, c298 BLOB, c299 ANY, c300 INT, c301 TEXT, c302 REAL, c303 BLOB, c304 ANY, c305 INT, c306 TEXT, c307 REAL, c308 BLOB, c309 ANY, c310 INT, c311 TEXT, c312 REAL, c313 BLOB, c314 ANY, c315 INT, c316 TEXT, c317 REAL, c318 BLOB, c319 ANY, c320 INT, c321 TEXT, c322 REAL, c323 BLOB, c324 ANY, c325 INT, c326 TEXT, c327 REAL, c328 BLOB, c329 ANY, c330 INT, c331 TEXT, c332 REAL, c333 BLOB, c334 ANY, c335 INT, c336 TEXT, c337 REAL, c338 BLOB, c339 ANY, c340 INT, c341 TEXT, c342 REAL, c343 BLOB, c344 ANY, c345 INT, c346 TEXT, c347 REAL, c348 BLOB, c349 ANY, c350 INT, c351 TEXT, c352 REAL, c353 BLOB, c354 ANY, c355 INT, c356 TEXT, c357 REAL, c358 BLOB, c359 ANY, c360 INT, c361 TEXT, c362 REAL, c363 BLOB, c364 ANY, c365 INT, c366 TEXT, c367 REAL, c368 BLOB, c369 ANY, c370 INT, c371 TEXT, c372 REAL, c373 BLOB, c374 ANY, c375 INT, c376 TEXT, c377 REAL, c378 BLOB, c379 ANY, c380 INT, c381 TEXT, c382 REAL, c383 BLOB, c384 ANY, c385 INT, c386 TEXT, c387 REAL, c388 BLOB, c389 ANY, c390 INT, c391 TEXT, c392 REAL, c393 BLOB, c394 ANY, c395 INT, c396 TEXT, c397 REAL, c398 BLOB, c399 ANY)", false)) {
                executeContextStatement(state, "INSERT INTO egraph_heavy.wide(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22, c23, c24, c25, c26, c27, c28, c29, c30, c31, c32, c33, c34, c35, c36, c37, c38, c39, c40, c41, c42, c43, c44, c45, c46, c47, c48, c49, c50, c51, c52, c53, c54, c55, c56, c57, c58, c59, c60, c61, c62, c63, c64, c65, c66, c67, c68, c69, c70, c71, c72, c73, c74, c75, c76, c77, c78, c79, c80, c81, c82, c83, c84, c85, c86, c87, c88, c89, c90, c91, c92, c93, c94, c95, c96, c97, c98, c99, c100, c101, c102, c103, c104, c105, c106, c107, c108, c109, c110, c111, c112, c113, c114, c115, c116, c117, c118, c119, c120, c121, c122, c123, c124, c125, c126, c127, c128, c129, c130, c131, c132, c133, c134, c135, c136, c137, c138, c139, c140, c141, c142, c143, c144, c145, c146, c147, c148, c149, c150, c151, c152, c153, c154, c155, c156, c157, c158, c159, c160, c161, c162, c163, c164, c165, c166, c167, c168, c169, c170, c171, c172, c173, c174, c175, c176, c177, c178, c179, c180, c181, c182, c183, c184, c185, c186, c187, c188, c189, c190, c191, c192, c193, c194, c195, c196, c197, c198, c199, c200, c201, c202, c203, c204, c205, c206, c207, c208, c209, c210, c211, c212, c213, c214, c215, c216, c217, c218, c219, c220, c221, c222, c223, c224, c225, c226, c227, c228, c229, c230, c231, c232, c233, c234, c235, c236, c237, c238, c239, c240, c241, c242, c243, c244, c245, c246, c247, c248, c249, c250, c251, c252, c253, c254, c255, c256, c257, c258, c259, c260, c261, c262, c263, c264, c265, c266, c267, c268, c269, c270, c271, c272, c273, c274, c275, c276, c277, c278, c279, c280, c281, c282, c283, c284, c285, c286, c287, c288, c289, c290, c291, c292, c293, c294, c295, c296, c297, c298, c299, c300, c301, c302, c303, c304, c305, c306, c307, c308, c309, c310, c311, c312, c313, c314, c315, c316, c317, c318, c319, c320, c321, c322, c323, c324, c325, c326, c327, c328, c329, c330, c331, c332, c333, c334, c335, c336, c337, c338, c339, c340, c341, c342, c343, c344, c345, c346, c347, c348, c349, c350, c351, c352, c353, c354, c355, c356, c357, c358, c359, c360, c361, c362, c363, c364, c365, c366, c367, c368, c369, c370, c371, c372, c373, c374, c375, c376, c377, c378, c379, c380, c381, c382, c383, c384, c385, c386, c387, c388, c389, c390, c391, c392, c393, c394, c395, c396, c397, c398, c399) VALUES(0, 't1', 2.5, x'03', NULL, 5, 't6', 7.5, x'08', NULL, 10, 't11', 12.5, x'0d', NULL, 15, 't16', 17.5, x'12', NULL, 20, 't21', 22.5, x'17', NULL, 25, 't26', 27.5, x'1c', NULL, 30, 't31', 32.5, x'21', NULL, 35, 't36', 37.5, x'26', NULL, 40, 't41', 42.5, x'2b', NULL, 45, 't46', 47.5, x'30', NULL, 50, 't51', 52.5, x'35', NULL, 55, 't56', 57.5, x'3a', NULL, 60, 't61', 62.5, x'3f', NULL, 65, 't66', 67.5, x'44', NULL, 70, 't71', 72.5, x'49', NULL, 75, 't76', 77.5, x'4e', NULL, 80, 't81', 82.5, x'53', NULL, 85, 't86', 87.5, x'58', NULL, 90, 't91', 92.5, x'5d', NULL, 95, 't96', 97.5, x'62', NULL, 100, 't101', 102.5, x'67', NULL, 105, 't106', 107.5, x'6c', NULL, 110, 't111', 112.5, x'71', NULL, 115, 't116', 117.5, x'76', NULL, 120, 't121', 122.5, x'7b', NULL, 125, 't126', 127.5, x'80', NULL, 130, 't131', 132.5, x'85', NULL, 135, 't136', 137.5, x'8a', NULL, 140, 't141', 142.5, x'8f', NULL, 145, 't146', 147.5, x'94', NULL, 150, 't151', 152.5, x'99', NULL, 155, 't156', 157.5, x'9e', NULL, 160, 't161', 162.5, x'a3', NULL, 165, 't166', 167.5, x'a8', NULL, 170, 't171', 172.5, x'ad', NULL, 175, 't176', 177.5, x'b2', NULL, 180, 't181', 182.5, x'b7', NULL, 185, 't186', 187.5, x'bc', NULL, 190, 't191', 192.5, x'c1', NULL, 195, 't196', 197.5, x'c6', NULL, 200, 't201', 202.5, x'cb', NULL, 205, 't206', 207.5, x'd0', NULL, 210, 't211', 212.5, x'd5', NULL, 215, 't216', 217.5, x'da', NULL, 220, 't221', 222.5, x'df', NULL, 225, 't226', 227.5, x'e4', NULL, 230, 't231', 232.5, x'e9', NULL, 235, 't236', 237.5, x'ee', NULL, 240, 't241', 242.5, x'f3', NULL, 245, 't246', 247.5, x'f8', NULL, 250, 't251', 252.5, x'fd', NULL, 255, 't256', 257.5, x'02', NULL, 260, 't261', 262.5, x'07', NULL, 265, 't266', 267.5, x'0c', NULL, 270, 't271', 272.5, x'11', NULL, 275, 't276', 277.5, x'16', NULL, 280, 't281', 282.5, x'1b', NULL, 285, 't286', 287.5, x'20', NULL, 290, 't291', 292.5, x'25', NULL, 295, 't296', 297.5, x'2a', NULL, 300, 't301', 302.5, x'2f', NULL, 305, 't306', 307.5, x'34', NULL, 310, 't311', 312.5, x'39', NULL, 315, 't316', 317.5, x'3e', NULL, 320, 't321', 322.5, x'43', NULL, 325, 't326', 327.5, x'48', NULL, 330, 't331', 332.5, x'4d', NULL, 335, 't336', 337.5, x'52', NULL, 340, 't341', 342.5, x'57', NULL, 345, 't346', 347.5, x'5c', NULL, 350, 't351', 352.5, x'61', NULL, 355, 't356', 357.5, x'66', NULL, 360, 't361', 362.5, x'6b', NULL, 365, 't366', 367.5, x'70', NULL, 370, 't371', 372.5, x'75', NULL, 375, 't376', 377.5, x'7a', NULL, 380, 't381', 382.5, x'7f', NULL, 385, 't386', 387.5, x'84', NULL, 390, 't391', 392.5, x'89', NULL, 395, 't396', 397.5, x'8e', NULL)", false);
                executeContextStatement(state, "INSERT INTO egraph_heavy.wide(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22, c23, c24, c25, c26, c27, c28, c29, c30, c31, c32, c33, c34, c35, c36, c37, c38, c39, c40, c41, c42, c43, c44, c45, c46, c47, c48, c49, c50, c51, c52, c53, c54, c55, c56, c57, c58, c59, c60, c61, c62, c63, c64, c65, c66, c67, c68, c69, c70, c71, c72, c73, c74, c75, c76, c77, c78, c79, c80, c81, c82, c83, c84, c85, c86, c87, c88, c89, c90, c91, c92, c93, c94, c95, c96, c97, c98, c99, c100, c101, c102, c103, c104, c105, c106, c107, c108, c109, c110, c111, c112, c113, c114, c115, c116, c117, c118, c119, c120, c121, c122, c123, c124, c125, c126, c127, c128, c129, c130, c131, c132, c133, c134, c135, c136, c137, c138, c139, c140, c141, c142, c143, c144, c145, c146, c147, c148, c149, c150, c151, c152, c153, c154, c155, c156, c157, c158, c159, c160, c161, c162, c163, c164, c165, c166, c167, c168, c169, c170, c171, c172, c173, c174, c175, c176, c177, c178, c179, c180, c181, c182, c183, c184, c185, c186, c187, c188, c189, c190, c191, c192, c193, c194, c195, c196, c197, c198, c199, c200, c201, c202, c203, c204, c205, c206, c207, c208, c209, c210, c211, c212, c213, c214, c215, c216, c217, c218, c219, c220, c221, c222, c223, c224, c225, c226, c227, c228, c229, c230, c231, c232, c233, c234, c235, c236, c237, c238, c239, c240, c241, c242, c243, c244, c245, c246, c247, c248, c249, c250, c251, c252, c253, c254, c255, c256, c257, c258, c259, c260, c261, c262, c263, c264, c265, c266, c267, c268, c269, c270, c271, c272, c273, c274, c275, c276, c277, c278, c279, c280, c281, c282, c283, c284, c285, c286, c287, c288, c289, c290, c291, c292, c293, c294, c295, c296, c297, c298, c299, c300, c301, c302, c303, c304, c305, c306, c307, c308, c309, c310, c311, c312, c313, c314, c315, c316, c317, c318, c319, c320, c321, c322, c323, c324, c325, c326, c327, c328, c329, c330, c331, c332, c333, c334, c335, c336, c337, c338, c339, c340, c341, c342, c343, c344, c345, c346, c347, c348, c349, c350, c351, c352, c353, c354, c355, c356, c357, c358, c359, c360, c361, c362, c363, c364, c365, c366, c367, c368, c369, c370, c371, c372, c373, c374, c375, c376, c377, c378, c379, c380, c381, c382, c383, c384, c385, c386, c387, c388, c389, c390, c391, c392, c393, c394, c395, c396, c397, c398, c399) VALUES(zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 2, 3, 4, 5, 6, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 9, 10, 11, 12, 13, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 16, 17, 18, 19, 20, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 23, 24, 25, 26, 27, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 30, 31, 32, 33, 34, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 37, 38, 39, 40, 41, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 44, 45, 46, 47, 48, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 51, 52, 53, 54, 55, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 58, 59, 60, 61, 62, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 65, 66, 67, 68, 69, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 72, 73, 74, 75, 76, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 79, 80, 81, 82, 83, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 86, 87, 88, 89, 90, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 93, 94, 95, 96, 97, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 100, 101, 102, 103, 104, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 107, 108, 109, 110, 111, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 114, 115, 116, 117, 118, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 121, 122, 123, 124, 125, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 128, 129, 130, 131, 132, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 135, 136, 137, 138, 139, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 142, 143, 144, 145, 146, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 149, 150, 151, 152, 153, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 156, 157, 158, 159, 160, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 163, 164, 165, 166, 167, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 170, 171, 172, 173, 174, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 177, 178, 179, 180, 181, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 184, 185, 186, 187, 188, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 191, 192, 193, 194, 195, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 198, 199, 200, 201, 202, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 205, 206, 207, 208, 209, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 212, 213, 214, 215, 216, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 219, 220, 221, 222, 223, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 226, 227, 228, 229, 230, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 233, 234, 235, 236, 237, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 240, 241, 242, 243, 244, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 247, 248, 249, 250, 251, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 254, 255, 256, 257, 258, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 261, 262, 263, 264, 265, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 268, 269, 270, 271, 272, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 275, 276, 277, 278, 279, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 282, 283, 284, 285, 286, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 289, 290, 291, 292, 293, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 296, 297, 298, 299, 300, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 303, 304, 305, 306, 307, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 310, 311, 312, 313, 314, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 317, 318, 319, 320, 321, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 324, 325, 326, 327, 328, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 331, 332, 333, 334, 335, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 338, 339, 340, 341, 342, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 345, 346, 347, 348, 349, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 352, 353, 354, 355, 356, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 359, 360, 361, 362, 363, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 366, 367, 368, 369, 370, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 373, 374, 375, 376, 377, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 380, 381, 382, 383, 384, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 387, 388, 389, 390, 391, zeroblob(400), 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy', 394, 395, 396, 397, 398, zeroblob(400))", false);
                executeContextStatement(state,
                        "SELECT count(c0), count(c199), count(c399), sum(length(c1)) FROM egraph_heavy.wide",
                        false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.wide_i ON wide(c399)", false);
                executeContextStatement(state,
                        "SELECT count(*) FROM egraph_heavy.wide WHERE c399 IS NOT NULL", false);
                executeContextStatement(state,
                        "UPDATE egraph_heavy.wide SET c399 = c399 WHERE c0 IS NOT NULL", false);
            }
            // A leading index column with three distinct values across four thousand rows, plus
            // statistics, is what makes the planner emit OP_SeekScan instead of a plain search.
            if (executeContextStatement(state, "CREATE TABLE egraph_heavy.sk(a INT, b INT, c INT, d TEXT)",
                    false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE s(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM s WHERE i < 4000) "
                                + "INSERT INTO egraph_heavy.sk SELECT i % 3, i % 1500, i, 'p' || i FROM s",
                        false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.sk_ab ON sk(a, b)", false);
                executeContextStatement(state, "CREATE INDEX egraph_heavy.sk_acd ON sk(a, c, d)", false);
                executeContextStatement(state, "ANALYZE egraph_heavy", false);
                for (String skipped : new String[] {
                    "SELECT count(*) FROM egraph_heavy.sk WHERE b = 700",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE b BETWEEN 700 AND 705",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE c = 2000",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE c > 3990",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE d = 'p2000'",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE b = 700 AND c > 0",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE c IN (1, 2000, 3999)",
                    "SELECT count(*) FROM egraph_heavy.sk WHERE b = 700 OR c = 2000" }) {
                    executeContextStatement(state, skipped, false);
                }
            }

            // fts3EvalDeferredPhrase only runs when FTS3 defers a token to a post-filter, and it does
            // that only if the token's doclist spills onto at least (documents matching the rarest
            // phrase) x (pages per document) overflow pages. Three thousand documents give the common
            // token a few overflow pages; the other side has to be a word found in one document, or
            // the threshold is hundreds of pages and nothing is deferred.
            if (executeContextStatement(state, "CREATE VIRTUAL TABLE egraph_heavy.dd USING fts4(x)", false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM n WHERE i < 3000) "
                                + "INSERT INTO egraph_heavy.dd(x) SELECT "
                                + "'ubiquitous ubiquitous ubiquitous ubiquitous token' || (i % 7) "
                                + "|| CASE WHEN i % 1000 = 0 THEN ' singular' || i ELSE '' END FROM n",
                        false);
                executeContextStatement(state, "INSERT INTO egraph_heavy.dd(dd) VALUES('optimize')", false);
                for (String deferred : new String[] {
                    "SELECT count(*) FROM egraph_heavy.dd WHERE dd MATCH 'ubiquitous singular1000'",
                    "SELECT count(*) FROM egraph_heavy.dd WHERE dd MATCH '\"ubiquitous token6\" singular2000'",
                    "SELECT count(*) FROM egraph_heavy.dd WHERE dd MATCH 'ubiquitous NEAR/3 singular3000'",
                    "SELECT count(*) FROM egraph_heavy.dd WHERE dd MATCH '\"ubiquitous ubiquitous\" singular1000'",
                    "SELECT snippet(dd), offsets(dd), matchinfo(dd, 'pcxnal') FROM egraph_heavy.dd "
                            + "WHERE dd MATCH 'ubiquitous singular2000'" }) {
                    executeContextStatement(state, deferred, false);
                }
            }
            // sqlite3WindowCodeStep walks the frame differently for every shape, and RANGE and
            // GROUPS over a partition need enough rows for the boundaries to move at all.
            if (executeContextStatement(state, "CREATE TABLE egraph_heavy.wk(a INT, b INT, c TEXT)", false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM n WHERE i < 300) "
                                + "INSERT INTO egraph_heavy.wk SELECT i % 10, i, 'w' || i FROM n",
                        false);
                for (String frame : new String[] {
                    "PARTITION BY a ORDER BY b RANGE BETWEEN 5 PRECEDING AND 5 FOLLOWING",
                    "PARTITION BY a ORDER BY b RANGE BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING",
                    "ORDER BY b RANGE BETWEEN UNBOUNDED PRECEDING AND 3 PRECEDING",
                    "ORDER BY b RANGE BETWEEN 3 FOLLOWING AND UNBOUNDED FOLLOWING",
                    "ORDER BY b GROUPS BETWEEN 2 PRECEDING AND 2 FOLLOWING",
                    "ORDER BY a GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW",
                    "ORDER BY a GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING",
                    "ORDER BY b ROWS BETWEEN 2 FOLLOWING AND 5 FOLLOWING",
                    "ORDER BY b ROWS BETWEEN 5 PRECEDING AND 2 PRECEDING",
                    "PARTITION BY a ORDER BY b GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING "
                            + "EXCLUDE CURRENT ROW",
                    "ORDER BY b RANGE BETWEEN 2 PRECEDING AND 2 FOLLOWING EXCLUDE TIES",
                    "ORDER BY b GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE GROUP" }) {
                    executeContextStatement(state,
                            "SELECT count(*) FROM (SELECT sum(b) OVER (" + frame + ") FROM egraph_heavy.wk)",
                            false);
                }
                executeContextStatement(state,
                        "SELECT count(*) FROM (SELECT sum(b) FILTER (WHERE a > 3) OVER (ORDER BY b "
                                + "GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM egraph_heavy.wk)",
                        false);
            }
            // A virtual table that says it will handle an IN constraint itself makes the planner
            // emit OP_VInitIn, and a module that reports bOmitOffset skips the OFFSET counter.
            if (executeContextStatement(state,
                    "CREATE VIRTUAL TABLE egraph_heavy.vr USING rtree(id, x0, x1, y0, y1)", false)) {
                executeContextStatement(state,
                        "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM n WHERE i < 300) "
                                + "INSERT INTO egraph_heavy.vr SELECT i, i * 1.0, i * 1.0 + 5, "
                                + "i * 2.0, i * 2.0 + 5 FROM n",
                        false);
                for (String vq : new String[] {
                    "SELECT count(*) FROM egraph_heavy.vr WHERE id IN (1, 2, 3, 4, 5)",
                    "SELECT count(*) FROM egraph_heavy.vr WHERE id IN (1, 50, 100) AND x0 > 0",
                    "SELECT count(*) FROM (SELECT id FROM egraph_heavy.vr WHERE x0 >= 0 LIMIT 10 OFFSET 20)",
                    "SELECT count(*) FROM (SELECT id FROM egraph_heavy.vr WHERE x0 >= 0 AND x1 <= 1000 "
                            + "LIMIT 3 OFFSET 1)" }) {
                    executeContextStatement(state, vq, false);
                }
            }
            executeContextStatement(state, "DETACH DATABASE egraph_heavy", false);
        }
        // vdbeCommit only builds a super-journal when a transaction dirties more than one database
        // held in a real file with a rollback journal; a database in WAL mode or with synchronous=OFF
        // does not count. The main database is often in WAL mode here, so the transaction writes two
        // attached files set to DELETE mode instead. They are small files reused on every refresh.
        if (executeContextStatement(state, "ATTACH DATABASE 'egraph_superjournal.db' AS egraph_sj", false)) {
            if (executeContextStatement(state, "ATTACH DATABASE 'egraph_superjournal2.db' AS egraph_sj2", false)) {
                for (String setup : new String[] {
                    "PRAGMA egraph_sj.journal_mode = DELETE", "PRAGMA egraph_sj2.journal_mode = DELETE",
                    "PRAGMA egraph_sj.synchronous = NORMAL", "PRAGMA egraph_sj2.synchronous = NORMAL",
                    "CREATE TABLE IF NOT EXISTS egraph_sj.x(k INTEGER PRIMARY KEY, v TEXT)",
                    "CREATE TABLE IF NOT EXISTS egraph_sj2.y(k INTEGER PRIMARY KEY, v TEXT)",
                    "DELETE FROM egraph_sj.x", "DELETE FROM egraph_sj2.y" }) {
                    executeContextStatement(state, setup, false);
                }
                for (String tx : new String[] {
                    "BEGIN", "INSERT INTO egraph_sj.x(v) VALUES('a'), ('b')",
                    "INSERT INTO egraph_sj2.y(v) VALUES('a')", "COMMIT",
                    "BEGIN", "UPDATE egraph_sj.x SET v = v || '1'", "UPDATE egraph_sj2.y SET v = v || '1'", "COMMIT",
                    "BEGIN", "INSERT INTO egraph_sj.x(v) VALUES('c')", "DELETE FROM egraph_sj2.y", "ROLLBACK" }) {
                    executeContextStatement(state, tx, false);
                }
                executeContextStatement(state, "DETACH DATABASE egraph_sj2", false);
            }
            executeContextStatement(state, "DETACH DATABASE egraph_sj", false);
        }
        executeContextStatement(state, "PRAGMA wal_checkpoint(FULL)", false);
        executeContextStatement(state, "PRAGMA optimize", false);
        if (ATTACH_CONTEXT_COUNT.incrementAndGet() % 256 == 0) {
            executeContextStatement(state, "VACUUM", false);
        }
        executeContextStatement(state, "PRAGMA journal_mode=DELETE", false);
        return ok;
    }

    private static boolean setupAlterIndexAnalyzeContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_alter_probe", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_alter_probe(id INTEGER PRIMARY KEY, value INTEGER NOT NULL DEFAULT 0, "
                        + "note TEXT UNIQUE)",
                true);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_alter_probe(id, value, note) VALUES (1, 10, 'alpha'), (2, 20, 'beta')", false);
        ok &= executeContextStatement(state,
                "ALTER TABLE egraph_alter_probe ADD COLUMN extra TEXT DEFAULT 'x'", true);
        ok &= executeContextStatement(state,
                "CREATE INDEX egraph_alter_value_idx ON egraph_alter_probe(value) WHERE value >= 0", true);
        executeContextStatement(state,
                "CREATE INDEX egraph_alter_expr_idx ON egraph_alter_probe((value + length(note)))", true);
        executeContextStatement(state,
                "INSERT INTO egraph_alter_probe(id, value, note, extra) VALUES (3, 30, 'alpha', 'z') "
                        + "ON CONFLICT(note) DO UPDATE SET value = excluded.value + 1 RETURNING id, value",
                false);
        executeContextStatement(state,
                "UPDATE egraph_alter_probe SET value = value + 1 WHERE value < 40 RETURNING id, value", false);
        executeContextStatement(state, "REINDEX egraph_alter_value_idx", false);
        executeContextStatement(state, "ANALYZE egraph_alter_probe", false);
        executeContextStatement(state,
                "SELECT id, value, extra FROM egraph_alter_probe INDEXED BY egraph_alter_value_idx "
                        + "WHERE value >= 0 ORDER BY value LIMIT 4",
                false);
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_rename_probe", true);
        executeContextStatement(state,
                "CREATE TABLE egraph_rename_probe(id INTEGER PRIMARY KEY, a INTEGER, b TEXT, "
                        + "c INTEGER GENERATED ALWAYS AS (a + length(b)) VIRTUAL, d TEXT DEFAULT 'x')",
                true);
        executeContextStatement(state,
                "CREATE INDEX egraph_rename_probe_expr ON egraph_rename_probe((a + length(b))) WHERE a IS NOT NULL",
                true);
        executeContextStatement(state,
                "CREATE VIEW egraph_rename_probe_view AS SELECT id, a, b, c, d FROM egraph_rename_probe WHERE a >= 0",
                true);
        executeContextStatement(state,
                "CREATE TRIGGER egraph_rename_probe_ai AFTER INSERT ON egraph_rename_probe BEGIN "
                        + "UPDATE egraph_rename_probe SET d = 'seen' WHERE id = NEW.id; END",
                true);
        executeContextStatement(state,
                "INSERT INTO egraph_rename_probe(id, a, b) VALUES (1, 10, 'alpha'), (2, 20, 'beta')", false);
        executeContextStatement(state, "ALTER TABLE egraph_rename_probe RENAME COLUMN b TO renamed_b", true);
        executeContextStatement(state, "ALTER TABLE egraph_rename_probe DROP COLUMN d", true);
        executeContextStatement(state, "ALTER TABLE egraph_rename_probe RENAME TO egraph_renamed_probe", true);
        executeContextStatement(state,
                "SELECT id, a, renamed_b, c FROM egraph_renamed_probe WHERE a >= 0 ORDER BY id", false);
        return ok;
    }

    private static boolean setupSelectWhereStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_plan_probe(id INTEGER PRIMARY KEY, v INTEGER, w INTEGER, tag TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_plan_probe", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_plan_probe(id, v, w, tag) VALUES "
                        + "(1, 0, 10, 'alpha'), (2, 1, 20, 'beta'), (3, 2, 20, 'gamma'), "
                        + "(4, 3, 30, 'delta'), (5, 4, NULL, 'epsilon'), (6, 5, 40, 'zeta')",
                false);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_plan_v_idx ON egraph_plan_probe(v)", true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_plan_w_tag_idx ON egraph_plan_probe(w, tag)", true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_plan_partial_idx ON egraph_plan_probe(v) WHERE w IS NOT NULL",
                true);
        executeContextStatement(state, "ANALYZE egraph_plan_probe", false);
        executeContextStatement(state,
                "SELECT p1.id, p2.tag FROM egraph_plan_probe AS p1 "
                        + "LEFT JOIN egraph_plan_probe AS p2 ON p2.w = p1.w "
                        + "WHERE (p1.v = 1 OR p1.w = 20 OR p1.tag IN ('alpha', 'beta')) "
                        + "AND p1.id IN (SELECT id FROM egraph_plan_probe WHERE v BETWEEN 0 AND 4) "
                        + "ORDER BY p1.w, p2.tag LIMIT 8",
                false);
        executeContextStatement(state,
                "SELECT p1.id, p2.tag FROM egraph_plan_probe AS p1 "
                        + "RIGHT JOIN egraph_plan_probe AS p2 ON p2.w = p1.w "
                        + "WHERE p2.v IN (SELECT v FROM egraph_plan_probe WHERE w IS NOT NULL) "
                        + "ORDER BY p2.w, p1.id LIMIT 8",
                false);
        executeContextStatement(state,
                "SELECT * FROM (SELECT v, w FROM egraph_plan_probe WHERE v < 4 "
                        + "UNION ALL SELECT v, w FROM egraph_plan_probe WHERE w >= 20) "
                        + "ORDER BY w DESC, v ASC LIMIT 12",
                false);
        executeContextStatement(state,
                "SELECT v, count(*), max(w) FROM egraph_plan_probe "
                        + "WHERE w IS NOT NULL GROUP BY v HAVING count(*) >= 1 ORDER BY max(w) DESC",
                false);
        return ok;
    }

    private static boolean setupExprStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_expr_probe(id INTEGER PRIMARY KEY, n INTEGER, r REAL, txt TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_expr_probe", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_expr_probe(id, n, r, txt) VALUES "
                        + "(1, NULL, NULL, ''), (2, -1, -1.5, '42'), (3, 0, 0.0, 'alpha'), "
                        + "(4, 1, 1.25, 'Beta'), (5, 7, 3.5, 'text'), (6, 42, 9.0, 'suffix')",
                false);
        executeContextStatement(state,
                "SELECT id, typeof(n), typeof(r), typeof(txt), "
                        + "CASE WHEN n IS NULL THEN 'nil' WHEN n BETWEEN -1 AND 1 THEN 'small' ELSE 'large' END, "
                        + "coalesce(nullif(txt, ''), printf('n=%d', n)), abs(n), round(r, 1), lower(txt), "
                        + "substr(txt || '-x', 1, 4), hex(CAST(txt AS blob)) "
                        + "FROM egraph_expr_probe "
                        + "WHERE (n IS NULL OR n IN (-1, 0, 1, 42)) "
                        + "AND ((CAST(txt AS NUMERIC) >= 0) OR txt GLOB '*a*')",
                false);
        executeContextStatement(state,
                "SELECT id FROM egraph_expr_probe "
                        + "WHERE ((n + 1) * (n - 1)) IS NOT NULL "
                        + "OR (txt COLLATE NOCASE LIKE 'b%' ESCAPE '\\') "
                        + "OR (r NOT BETWEEN -2.0 AND 2.0)",
                false);
        executeContextStatement(state,
                "SELECT json_valid(jsonb('{\"a\":[1,2],\"b\":\"x\"}')), "
                        + "json_extract(jsonb('{\"a\":[1,2]}'), '$.a[0]'), "
                        + "json_pretty(jsonb('{\"a\":{\"b\":1}}'))",
                false);
        executeContextStatement(state,
                "SELECT json_valid('{a:1, b:[2,3,],}', 2), "
                        + "json_extract('{\"escaped\":\"line\\nfeed\"}', '$.escaped'), "
                        + "timediff('2024-02-29 12:34:56', '2023-01-01 00:00:00')",
                false);
        return ok;
    }

    private static boolean setupWindowStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_window_probe(id INTEGER PRIMARY KEY, grp INTEGER, v INTEGER)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_window_probe", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_window_probe(id, grp, v) VALUES "
                        + "(1, 0, 10), (2, 0, 20), (3, 0, 20), (4, 1, 5), "
                        + "(5, 1, 15), (6, 1, 25), (7, 2, 30)",
                false);
        executeContextStatement(state,
                "SELECT id, grp, v, "
                        + "row_number() OVER (PARTITION BY grp ORDER BY v, id) AS rn, "
                        + "rank() OVER (PARTITION BY grp ORDER BY v) AS rnk, "
                        + "dense_rank() OVER (PARTITION BY grp ORDER BY v) AS drnk, "
                        + "sum(v) OVER (PARTITION BY grp ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS win_sum, "
                        + "first_value(v) OVER (PARTITION BY grp ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS first_v "
                        + "FROM egraph_window_probe ORDER BY grp, rn",
                false);
        executeContextStatement(state,
                "SELECT grp, avg(v) FILTER (WHERE v >= 10) OVER (PARTITION BY grp) "
                        + "FROM egraph_window_probe ORDER BY grp, id",
                false);
        executeContextStatement(state,
                "SELECT id, v, sum(v) OVER (ORDER BY v RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING), "
                        + "nth_value(v, 2) OVER (PARTITION BY grp ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING "
                        + "AND UNBOUNDED FOLLOWING) FROM egraph_window_probe ORDER BY grp, v",
                false);
        return ok;
    }

    private static boolean setupResolveStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_resolve_base(id INTEGER PRIMARY KEY, value INTEGER, name TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_resolve_base", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_resolve_base(id, value, name) VALUES "
                        + "(1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')",
                false);
        ok &= executeContextStatement(state,
                "CREATE VIEW IF NOT EXISTS egraph_resolve_view AS "
                        + "SELECT id AS alias_id, value AS alias_value, name AS alias_name "
                        + "FROM egraph_resolve_base",
                true);
        executeContextStatement(state,
                "WITH renamed(alias_id, alias_value) AS ("
                        + "SELECT alias_id, alias_value FROM egraph_resolve_view WHERE alias_value >= 10"
                        + ") SELECT r.alias_id, r.alias_value, "
                        + "(SELECT max(v.alias_value) FROM egraph_resolve_view AS v WHERE v.alias_id <= r.alias_id) AS max_seen "
                        + "FROM renamed AS r WHERE r.alias_value IN (SELECT alias_value FROM egraph_resolve_view) "
                        + "ORDER BY r.alias_id",
                false);
        executeContextStatement(state,
                "SELECT output_name, output_value FROM ("
                        + "SELECT alias_name AS output_name, alias_value + 1 AS output_value "
                        + "FROM egraph_resolve_view) AS resolved_aliases "
                        + "WHERE output_value > 0 ORDER BY output_name",
                false);
        return ok;
    }

    private static boolean setupFts5VariantConfigContext(SQLite3GlobalState state) {
        // Building five differently-configured FTS5 indexes is far too expensive to
        // repeat per check - doing so dropped throughput from ~2500 to 24 checks per
        // three minutes. The sentinel table doubles as the existence test: its
        // CREATE only succeeds the first time in a given database.
        if (!executeContextStatement(state, "CREATE TABLE egraph_fts5_variant_built(x)", true)) {
            return true;
        }
        boolean ok = true;
        String documents = "WITH RECURSIVE d(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM d WHERE i < 120) "
                + "SELECT i, printf('t%d common', i), "
                + "printf('body%d word%d common phrase here alpha%d', i, i, i) FROM d";

        // contentless_delete keeps tombstones instead of rewriting segments, so
        // deleting rows is what exercises the tombstone code.
        // Rebuilt every time on purpose: a contentless index has no DELETE-all, and
        // re-inserting the same rowids would append more index entries, so a setup
        // that ran twice in one check made the original and the variant see
        // different match counts - a false mismatch.
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fts5_cd", false);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE egraph_fts5_cd USING fts5(body, content='', contentless_delete=1)", true);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE d(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM d WHERE i < 120) "
                        + "INSERT INTO egraph_fts5_cd(rowid, body) SELECT i, "
                        + "printf('doc%d common alpha%d beta%d token%d', i, i, i, i) FROM d",
                false);
        executeContextStatement(state, "DELETE FROM egraph_fts5_cd WHERE rowid % 3 = 0", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5_cd(egraph_fts5_cd) VALUES('optimize')", false);
        executeContextStatement(state, "DELETE FROM egraph_fts5_cd WHERE rowid % 5 = 0", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5_cd(egraph_fts5_cd, rank) VALUES('merge', 2)", false);

        // detail=none stores no position lists, which is a separate iterator
        // implementation and rejects phrase queries outright.
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5_none USING fts5(a, b, detail=none)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts5_none", false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts5_none(rowid, a, b) " + documents, false);

        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5_col USING fts5(a, b, detail=column)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts5_col", false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts5_col(rowid, a, b) " + documents, false);

        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5_ascii USING fts5(a, b, tokenize=ascii)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts5_ascii", false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts5_ascii(rowid, a, b) " + documents, false);

        // An external content table means the index holds no copy of the text and
        // has to read through to the source table.
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_fts5_src(id INTEGER PRIMARY KEY, a TEXT, b TEXT)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts5_src", false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts5_src(id, a, b) " + documents, false);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5_ext USING fts5(a, b, "
                        + "content='egraph_fts5_src', content_rowid='id')",
                true);
        executeContextStatement(state, "INSERT INTO egraph_fts5_ext(egraph_fts5_ext) VALUES('rebuild')", false);
        return ok;
    }

    private static boolean setupFts5DeepQueryContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5d USING fts5(title, body, "
                        + "tokenize=\"porter unicode61 remove_diacritics 2\", prefix='2 3 4', columnsize=1, "
                        + "detail=full)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts5d", false);
        // Twenty-odd distinct terms per document, so the index gets large doclists
        // and multi-level structures rather than one tiny segment. Deterministic
        // text: the wrapper compares match counts.
        ok &= executeContextStatement(state,
                "WITH RECURSIVE d(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM d WHERE i < 128) "
                        + "INSERT INTO egraph_fts5d(rowid, title, body) SELECT i, "
                        + "printf('title%d sqlite coverage doc%d', i, i), printf('body%d term%d alpha%d beta%d "
                        + "gamma%d delta%d epsilon%d zeta%d eta%d theta%d iota%d kappa%d lambda%d mu%d nu%d xi%d "
                        + "omicron%d pi%d rho%d sigma%d tau%d sqlite query planner tokenizer segment doclist merge "
                        + "prefix', i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i, i) FROM d",
                false);
        // Segment maintenance plus deletes, which is what leaves tombstones behind.
        executeContextStatement(state, "INSERT INTO egraph_fts5d(egraph_fts5d, rank) VALUES('merge', 2)", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5d(egraph_fts5d) VALUES('optimize')", false);
        executeContextStatement(state, "DELETE FROM egraph_fts5d WHERE rowid % 7 = 0", false);
        executeContextStatement(state, "INSERT INTO egraph_fts5d(egraph_fts5d) VALUES('rebuild')", false);
        executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5d_v USING fts5vocab(egraph_fts5d, 'instance')", true);
        executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5d_vr USING fts5vocab(egraph_fts5d, 'row')", true);
        executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts5d_vc USING fts5vocab(egraph_fts5d, 'col')", true);
        return ok;
    }

    private static boolean setupFts4DeepSegmentContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4d USING fts4(title, body, tokenize=simple, "
                        + "matchinfo=fts3)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts4d", false);
        // Four batches rather than one: each batch flushes its own segment, which
        // is what gives the incremental merge something to do. A single bulk load
        // followed by 'rebuild' produces one optimal segment and merges become
        // no-ops.
        for (int batch = 0; batch < 4; batch++) {
            int from = batch * 32 + 1;
            int to = from + 31;
            ok &= executeContextStatement(state,
                    "WITH RECURSIVE d(i) AS (VALUES(" + from + ") UNION ALL SELECT i + 1 FROM d WHERE i < " + to
                            + ") INSERT INTO egraph_fts4d(docid, title, body) SELECT i, printf('t%d sqlite', i), "
                            + "printf('b%d w%d x%d y%d z%d p%d q%d r%d s%d u%d v%d common sqlite planner segment "
                            + "doclist merge deferred phrase', i, i, i, i, i, i, i, i, i, i, i) FROM d",
                    false);
        }
        executeContextStatement(state, "INSERT INTO egraph_fts4d(egraph_fts4d) VALUES('merge=2,2')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts4d(egraph_fts4d) VALUES('merge=4,3')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts4d(egraph_fts4d) VALUES('automerge=2')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts4d(egraph_fts4d) VALUES('integrity-check')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts4d(egraph_fts4d) VALUES('rebuild')", false);
        // The tokenizer itself as a table-valued function.
        executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_tok USING fts3tokenize('simple')", true);
        executeContextStatement(state,
                "SELECT count(*) FROM egraph_fts4_tok WHERE input = 'hello world tokenize test'", false);
        return ok;
    }

    private static boolean setupFts4Context(SQLite3GlobalState state) {
        boolean ok = true;
        // Three tokenizers so the tokenizer implementations are all reachable.
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4 USING fts4(title, body, tokenize=porter)", true);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_uni USING fts4(title, body, "
                        + "tokenize=unicode61 \"remove_diacritics=2\")",
                true);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts3 USING fts3(title, body, tokenize=simple)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts4", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts4_uni", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts3", false);
        // Deterministic documents: the wrapper compares match counts, and random
        // text would also make every context refresh look like a data change to
        // the delta-encoded snapshot.
        String documents = "WITH RECURSIVE d(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM d WHERE i < 60) "
                + "SELECT i, printf('title %d sqlite coverage', i), "
                + "printf('body %d sqlite query planner variants tokenizer prefix merge segment doclist %s', i, "
                + "CASE i % 3 WHEN 0 THEN 'alpha beta gamma' WHEN 1 THEN 'delta epsilon zeta' "
                + "ELSE 'eta theta iota' END) FROM d";
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts4(docid, title, body) " + documents, false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts4_uni(docid, title, body) " + documents, false);
        ok &= executeContextStatement(state, "INSERT INTO egraph_fts3(docid, title, body) " + documents, false);
        // Segment maintenance paths.
        executeContextStatement(state, "INSERT INTO egraph_fts4(egraph_fts4) VALUES('optimize')", false);
        executeContextStatement(state, "INSERT INTO egraph_fts4_uni(egraph_fts4_uni) VALUES('merge=2,2')", false);
        executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_aux USING fts4aux(egraph_fts4)", true);
        // fts3InitVtab parses the option list, and each option is its own branch there: an external
        // content table, notindexed, prefix indexes, languageid, order=DESC with contentless rows,
        // and the fts3tokenize eponymous table. 105 lines the four-hour run never reached.
        executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_fts4_ext(id INTEGER PRIMARY KEY, v TEXT)", true);
        executeContextStatement(state, "DELETE FROM egraph_fts4_ext", false);
        executeContextStatement(state,
                "INSERT INTO egraph_fts4_ext(id, v) VALUES(1, 'hello world'), (2, 'foo bar baz')", false);
        if (executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_opt USING fts4(a, b, matchinfo=fts3, "
                        + "tokenize=simple, notindexed=b, prefix='2,3')",
                false)) {
            executeContextStatement(state, "DELETE FROM egraph_fts4_opt", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_fts4_opt(a, b) VALUES('alpha beta', 'skipme'), ('gamma delta', 'skipme2')",
                    false);
            executeContextStatement(state,
                    "SELECT count(*) FROM egraph_fts4_opt WHERE egraph_fts4_opt MATCH 'alpha'", false);
        }
        if (executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_cnt USING fts4(v, content='egraph_fts4_ext')",
                false)) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts4_cnt(docid, v) SELECT id, v FROM egraph_fts4_ext", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts4_cnt WHERE egraph_fts4_cnt MATCH 'hello'",
                    false);
        }
        if (executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_lid USING fts4(a, tokenize=porter, languageid=lid)",
                false)) {
            executeContextStatement(state, "DELETE FROM egraph_fts4_lid", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_fts4_lid(a, lid) VALUES('running fast', 0), ('jumped high', 1)", false);
            executeContextStatement(state,
                    "SELECT count(*) FROM egraph_fts4_lid WHERE egraph_fts4_lid MATCH 'run' AND lid = 0", false);
        }
        if (executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts4_desc USING fts4(a, order=DESC, content='')",
                false)) {
            executeContextStatement(state, "INSERT INTO egraph_fts4_desc(docid, a) VALUES(1, 'x y z')", false);
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts4_desc WHERE egraph_fts4_desc MATCH 'x'",
                    false);
        }
        if (executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts3_tok USING fts3tokenize('porter')", false)) {
            executeContextStatement(state, "SELECT count(*) FROM egraph_fts3_tok WHERE input = 'running jumped'",
                    false);
        }
        // EXPLAIN is the only way into sqlite3VdbeDisplayP4: the oracle runs EXPLAIN QUERY PLAN on
        // every query, but that renders no P4 operands.
        for (String explained : new String[] {
            "SELECT count(*) FROM egraph_fts4 WHERE egraph_fts4 MATCH 'sqlite'",
            "SELECT docid, title FROM egraph_fts4_uni WHERE body LIKE 'body 1%'",
            "INSERT INTO egraph_fts4_ext(v) VALUES('explained')",
            "UPDATE egraph_fts4_ext SET v = v || '!' WHERE id = 1",
            "DELETE FROM egraph_fts4_ext WHERE id > 100",
            "WITH RECURSIVE r(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM r WHERE x < 5) SELECT sum(x) FROM r" }) {
            executeContextStatement(state, "EXPLAIN " + explained, false);
        }
        return ok;
    }

    private static boolean setupStarJoinContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_star_fact(id INTEGER PRIMARY KEY, d1 INTEGER, d2 INTEGER, "
                        + "v INTEGER)",
                true);
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_star_dim1(k INTEGER PRIMARY KEY, label TEXT)", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_star_dim2(k INTEGER PRIMARY KEY, label TEXT)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_star_fact", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_star_dim1", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_star_dim2", false);
        // 128 rows is enough for the planner to choose Bloom filters here, and it
        // stays within corpus.maxRowsPerTable so the replay keeps the whole table.
        ok &= executeContextStatement(state,
                "WITH RECURSIVE f(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM f WHERE i < 128) "
                        + "INSERT INTO egraph_star_fact(id, d1, d2, v) SELECT i, i % 11, i % 13, i FROM f",
                false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_star_dim1(k, label) SELECT DISTINCT d1, 'd1-' || d1 FROM egraph_star_fact",
                false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_star_dim2(k, label) SELECT DISTINCT d2, 'd2-' || d2 FROM egraph_star_fact",
                false);
        executeContextStatement(state, "ANALYZE", false);
        return ok;
    }

    private static boolean setupOrProbeContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_or_probe(id INTEGER PRIMARY KEY, a INTEGER, b INTEGER, pad TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_or_probe", false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE r(i) AS (VALUES(1) UNION ALL SELECT i + 1 FROM r WHERE i < 128) "
                        + "INSERT INTO egraph_or_probe(id, a, b, pad) SELECT i, i % 17, i % 23, 'pad' || i FROM r",
                false);
        ok &= executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_or_a ON egraph_or_probe(a)", true);
        ok &= executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_or_b ON egraph_or_probe(b)", true);
        executeContextStatement(state, "ANALYZE", false);
        // Statement forms that cannot appear in a judged SELECT wrapper, so they
        // run here for coverage only: CREATE TABLE AS SELECT (createTableStmt),
        // UPDATE ... FROM (updateFromSelect) and a vector assignment
        // (sqlite3ExprListAppendVector).
        executeContextStatement(state, "DROP TABLE IF EXISTS egraph_or_cts", false);
        executeContextStatement(state,
                "CREATE TABLE egraph_or_cts AS SELECT a, b, pad FROM egraph_or_probe LIMIT 20", true);
        executeContextStatement(state,
                "UPDATE egraph_or_cts SET pad = s.pad FROM egraph_or_probe AS s WHERE egraph_or_cts.a = s.a", false);
        executeContextStatement(state, "UPDATE egraph_or_cts SET (a, b) = (SELECT 1, 2) WHERE a = 3", false);
        executeContextStatement(state, "UPDATE egraph_or_cts SET (a, b) = (b, a) WHERE a > 10", false);
        return ok;
    }

    private static boolean setupRowValueContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_rowvalue_probe("
                        + "id INTEGER PRIMARY KEY, a INTEGER, b TEXT, c INTEGER, d TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_rowvalue_probe", false);
        // NULLs on both sides of the comparisons on purpose: row-value NULL
        // handling is where the three-valued logic is easiest to get wrong.
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_rowvalue_probe(id, a, b, c, d) VALUES "
                        + "(1, 1, 'x', 1, 'x'), (2, 2, 'y', 2, 'z'), (3, 3, NULL, 3, NULL), "
                        + "(4, 4, 'z', NULL, 'z'), (5, NULL, 'w', 5, 'w'), (6, 6, 'a', 6, 'a'), "
                        + "(7, 7, 'b', 7, 'B'), (8, 9, 'zzz', 9, 'zzz')",
                false);
        ok &= executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_rowvalue_probe_ab ON egraph_rowvalue_probe(a, b)", true);
        return ok;
    }

    private static boolean setupSorterStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_sorter_probe(id INTEGER PRIMARY KEY, bucket INTEGER, payload TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_sorter_probe", false);
        ok &= executeContextStatement(state,
                // Wide rows, not many rows. The sorter only writes a PMA once it
                // holds more than SQLITE_DEFAULT_PMA_SIZE (250 pages, ~1 MB), and
                // cache_size cannot lower that floor, so bytes are what matter:
                // 120 rows x ~1.2 KB x the joined inner rows clears 1 MB.
                // Row count stays under corpus.maxRowsPerTable (128) so the
                // replay used for coverage keeps the full table, and the payload
                // is deterministic (no randomblob) so re-creating the context
                // produces byte-identical rows - otherwise every context refresh
                // would look like a change and be re-serialized into every
                // delta-encoded snapshot.
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 120) "
                        + "INSERT INTO egraph_sorter_probe(id, bucket, payload) "
                        + "SELECT x, x % 9, printf('payload-%04d-%s', x, hex(zeroblob(600))) FROM n",
                false);
        executeContextStatement(state, "PRAGMA temp_store=FILE", false);
        // A small page cache and background sorter threads push the sort further
        // down the external merge path: measured on this build, threads=4 takes
        // vdbeSorterFlushPMA from 36% to 86% of its lines. SQLite must return the
        // same rows under any cache size or thread count, so neither setting can
        // turn a correct result into a mismatch.
        executeContextStatement(state, "PRAGMA cache_size=-64", false);
        executeContextStatement(state, "PRAGMA threads=4", false);
        executeContextStatement(state,
                "SELECT bucket, count(*), min(payload), max(payload), group_concat(id, ',') "
                        + "FROM egraph_sorter_probe GROUP BY bucket "
                        + "ORDER BY count(*) DESC, max(payload) COLLATE NOCASE DESC LIMIT 12",
                false);
        executeContextStatement(state,
                "SELECT id, bucket, payload FROM egraph_sorter_probe "
                        + "ORDER BY payload COLLATE NOCASE DESC, bucket ASC, id DESC LIMIT 32 OFFSET 3",
                false);
        executeContextStatement(state, "PRAGMA temp_store=MEMORY", false);
        return ok;
    }

    private static boolean setupJoinOptimizerContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_join_left(id INTEGER PRIMARY KEY, k INTEGER, v INTEGER, tag TEXT)",
                true);
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_join_right(id INTEGER PRIMARY KEY, k INTEGER, v INTEGER, flag INTEGER)",
                true);
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_join_dim(k INTEGER PRIMARY KEY, label TEXT)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_join_left", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_join_right", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_join_dim", false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 96) "
                        + "INSERT INTO egraph_join_left(id, k, v, tag) "
                        + "SELECT x, x % 16, x * 3, printf('L%03d', x) FROM n",
                false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 128) "
                        + "INSERT INTO egraph_join_right(id, k, v, flag) "
                        + "SELECT x, x % 16, x * 5, x % 3 FROM n",
                false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x + 1 FROM n WHERE x < 15) "
                        + "INSERT INTO egraph_join_dim(k, label) SELECT x, printf('D%02d', x) FROM n",
                false);
        executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_join_left_k_v ON egraph_join_left(k, v)", true);
        executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_join_right_k_flag ON egraph_join_right(k, flag)",
                true);
        executeContextStatement(state, "CREATE INDEX IF NOT EXISTS egraph_join_left_partial ON egraph_join_left(v) WHERE k >= 0",
                true);
        executeContextStatement(state, "ANALYZE egraph_join_left", false);
        executeContextStatement(state, "ANALYZE egraph_join_right", false);
        executeContextStatement(state, "ANALYZE egraph_join_dim", false);
        ok &= executeContextStatement(state,
                "SELECT r.id, l.tag FROM egraph_join_left AS l RIGHT JOIN egraph_join_right AS r "
                        + "ON r.k = l.k WHERE r.flag IN (SELECT flag FROM egraph_join_right WHERE k BETWEEN 0 AND 8) "
                        + "ORDER BY r.k, l.id LIMIT 24",
                false);
        executeContextStatement(state,
                "SELECT coalesce(l.k, r.k), count(*) FROM egraph_join_left AS l "
                        + "FULL OUTER JOIN egraph_join_right AS r ON r.k = l.k AND r.flag = l.k % 3 "
                        + "GROUP BY coalesce(l.k, r.k) ORDER BY 1 LIMIT 16",
                false);
        executeContextStatement(state,
                "SELECT count(*) FROM egraph_join_left AS l JOIN egraph_join_right AS r ON r.k = l.k "
                        + "JOIN egraph_join_dim AS d ON d.k = r.k "
                        + "WHERE d.k IN (SELECT k FROM egraph_join_dim WHERE k < 12) AND r.v > l.v",
                false);
        return ok;
    }

    private static boolean setupAlterFkStressContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state, "PRAGMA foreign_keys=ON", false);
        ok &= executeContextStatement(state, "PRAGMA defer_foreign_keys=ON", false);
        try {
            executeContextStatement(state, "DROP VIEW IF EXISTS egraph_alter_fk_view", true);
            executeContextStatement(state, "DROP TRIGGER IF EXISTS egraph_alter_fk_ai", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_alter_fk_renamed", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_alter_fk_rename", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fk_audit", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fk_child2", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fk_parent2", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fk_child", true);
            executeContextStatement(state, "DROP TABLE IF EXISTS egraph_fk_parent", true);

            ok &= executeContextStatement(state,
                    "CREATE TABLE egraph_fk_parent(id INTEGER PRIMARY KEY, code TEXT UNIQUE)", true);
            ok &= executeContextStatement(state,
                    "CREATE TABLE egraph_fk_child(id INTEGER PRIMARY KEY, pid INTEGER DEFAULT 0 "
                            + "REFERENCES egraph_fk_parent(id) ON UPDATE CASCADE ON DELETE SET DEFAULT, note TEXT)",
                    true);
            ok &= executeContextStatement(state,
                    "CREATE TABLE egraph_fk_audit(action TEXT, child_id INTEGER, old_pid INTEGER, new_pid INTEGER)",
                    true);
            ok &= executeContextStatement(state,
                    "CREATE TRIGGER egraph_alter_fk_ai AFTER UPDATE OF pid ON egraph_fk_child BEGIN "
                            + "INSERT INTO egraph_fk_audit VALUES('pid-update', NEW.id, OLD.pid, NEW.pid); END",
                    true);
            ok &= executeContextStatement(state,
                    "INSERT INTO egraph_fk_parent(id, code) VALUES (0, 'default'), (1, 'p1'), (2, 'p2')", false);
            ok &= executeContextStatement(state,
                    "INSERT INTO egraph_fk_child(id, pid, note) VALUES (10, 1, 'c1'), (11, 2, 'c2')", false);
            executeContextStatement(state, "UPDATE egraph_fk_parent SET id = 3 WHERE id = 1", false);
            executeContextStatement(state, "DELETE FROM egraph_fk_parent WHERE id = 2", false);
            executeContextStatement(state, "PRAGMA foreign_key_check", false);

            executeContextStatement(state,
                    "CREATE TABLE egraph_fk_parent2(a INTEGER, b INTEGER, PRIMARY KEY(a, b))", true);
            executeContextStatement(state,
                    "CREATE TABLE egraph_fk_child2(id INTEGER PRIMARY KEY, a INTEGER, b INTEGER, "
                            + "FOREIGN KEY(a, b) REFERENCES egraph_fk_parent2(a, b) "
                            + "ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)",
                    true);
            executeContextStatement(state, "BEGIN", false);
            executeContextStatement(state, "INSERT INTO egraph_fk_parent2(a, b) VALUES (1, 1), (2, 2)", false);
            executeContextStatement(state, "INSERT INTO egraph_fk_child2(id, a, b) VALUES (1, 1, 1), (2, 2, 2)", false);
            executeContextStatement(state, "DELETE FROM egraph_fk_parent2 WHERE a = 1", false);
            executeContextStatement(state, "COMMIT", false);

            executeContextStatement(state,
                    "CREATE TABLE egraph_alter_fk_rename(id INTEGER PRIMARY KEY, a INTEGER, b TEXT, "
                            + "drop_me TEXT DEFAULT 'x')",
                    true);
            executeContextStatement(state,
                    "CREATE INDEX egraph_alter_fk_expr ON egraph_alter_fk_rename((a + length(b))) WHERE a IS NOT NULL",
                    true);
            executeContextStatement(state,
                    "CREATE VIEW egraph_alter_fk_view AS SELECT id, a, b FROM egraph_alter_fk_rename WHERE a >= 0",
                    true);
            executeContextStatement(state,
                    "INSERT INTO egraph_alter_fk_rename(id, a, b) VALUES (1, 10, 'alpha'), (2, 20, 'beta')", false);
            executeContextStatement(state, "ALTER TABLE egraph_alter_fk_rename RENAME COLUMN b TO renamed_b", true);
            executeContextStatement(state, "ALTER TABLE egraph_alter_fk_rename DROP COLUMN drop_me", true);
            executeContextStatement(state, "ALTER TABLE egraph_alter_fk_rename RENAME TO egraph_alter_fk_renamed", true);
            executeContextStatement(state,
                    "SELECT id, a, renamed_b FROM egraph_alter_fk_renamed WHERE a >= 0 ORDER BY id", false);
            return ok;
        } finally {
            executeContextStatement(state, "ROLLBACK", false);
            executeContextStatement(state, "PRAGMA defer_foreign_keys=OFF", false);
            executeContextStatement(state, "PRAGMA foreign_keys=OFF", false);
        }
    }

    private static boolean setupIntegrityCheckContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_integrity_probe(id INTEGER PRIMARY KEY, k INTEGER NOT NULL, "
                        + "payload TEXT, extra BLOB)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_integrity_probe", false);
        ok &= executeContextStatement(state,
                "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x + 1 FROM n WHERE x < 128) "
                        + "INSERT INTO egraph_integrity_probe(id, k, payload, extra) "
                        + "SELECT x, x % 37, printf('payload-%04d-%s', x, printf('%016X%016X', x * 2654435761, x * 40503)), "
                        + "zeroblob(32) FROM n",
                false);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_integrity_k_payload ON egraph_integrity_probe(k, payload)", true);
        executeContextStatement(state,
                "CREATE INDEX IF NOT EXISTS egraph_integrity_payload_expr "
                        + "ON egraph_integrity_probe((length(payload))) WHERE k >= 0",
                true);
        executeContextStatement(state, "DELETE FROM egraph_integrity_probe WHERE id % 19 = 0", false);
        executeContextStatement(state,
                "UPDATE egraph_integrity_probe SET payload = payload || '-updated' WHERE id % 23 = 0", false);
        executeContextStatement(state, "PRAGMA cell_size_check=ON", false);
        executeContextStatement(state, "PRAGMA quick_check", false);
        if (INTEGRITY_CONTEXT_COUNT.incrementAndGet() % 64 == 0) {
            executeContextStatement(state, "PRAGMA integrity_check", false);
        }
        if (INTEGRITY_CONTEXT_COUNT.get() % 256 == 0) {
            executeContextStatement(state, "VACUUM", false);
        }
        executeContextStatement(state, "PRAGMA freelist_count", false);
        executeContextStatement(state, "PRAGMA page_count", false);
        executeContextStatement(state, "PRAGMA cell_size_check=OFF", false);
        return ok;
    }

    private static boolean setupScalarAggregateContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_scalar_probe(id INTEGER PRIMARY KEY, g INTEGER, n REAL, txt TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_scalar_probe", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_scalar_probe(id, g, n, txt) VALUES "
                        + "(1, 0, -1.5, 'alpha'), (2, 0, 0.0, 'Beta'), (3, 1, 2.25, 'gamma'), "
                        + "(4, 1, 1000000.5, 'delta'), (5, 2, NULL, ''), "
                        + "(6, 2, 9223372036854775807, 'quote''value')",
                false);
        executeContextStatement(state,
                "SELECT timediff('2024-02-29 12:34:56.789', '1900-01-01 00:00:00.001'), "
                        + "strftime('%Y-%m-%d %H:%M:%f %J %s %W %w', '2024-02-29 12:34:56.789'), "
                        + "printf('%!.*q|%lld|%#x|%.17g|%c', 12, txt, id, id, n, 65) "
                        + "FROM egraph_scalar_probe WHERE id IN (1, 2, 6) ORDER BY id",
                false);
        executeContextStatement(state,
                "SELECT g, group_concat(replace(txt, 'a', 'A'), '|') FILTER (WHERE txt IS NOT NULL), "
                        + "sum(CASE WHEN typeof(n) = 'real' THEN n ELSE 0 END), max(txt COLLATE NOCASE) "
                        + "FROM egraph_scalar_probe GROUP BY g HAVING count(*) >= 1 ORDER BY g",
                false);
        executeContextStatement(state,
                "SELECT replace(format('%08d-%s-%Q', id, txt, txt), '0', '_') "
                        + "FROM egraph_scalar_probe WHERE txt GLOB '*[ae]*' OR txt = '' ORDER BY txt COLLATE NOCASE",
                false);
        return ok;
    }

    private static boolean setupVirtualTableUpdateContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_vtab_rt USING rtree(id, x1, x2, y1, y2)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_vtab_rt", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_vtab_rt VALUES "
                        + "(1, 0.0, 10.0, 0.0, 10.0), (2, 5.0, 15.0, 5.0, 15.0), "
                        + "(3, -5.0, 1.0, -5.0, 1.0), (4, 20.0, 30.0, 20.0, 30.0)",
                false);
        if (ok) {
            executeContextStatement(state,
                    "UPDATE egraph_vtab_rt SET x1 = x1 - 0.5, x2 = x2 + 0.5 WHERE id IN (1, 2)", false);
            executeContextStatement(state,
                    "SELECT id FROM egraph_vtab_rt WHERE x1 <= 6.0 AND x2 >= 6.0 "
                            + "AND y1 <= 6.0 AND y2 >= 6.0 ORDER BY id",
                    false);
            executeContextStatement(state,
                    "DELETE FROM egraph_vtab_rt WHERE id IN (SELECT id FROM egraph_vtab_rt WHERE x2 < 2.0 OR x1 > 25.0)",
                    false);
            executeContextStatement(state, "SELECT rtreecheck('egraph_vtab_rt')", false);
        }
        boolean ftsOk = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_vtab_fts USING fts5(title, body)", true);
        if (ftsOk) {
            executeContextStatement(state, "DELETE FROM egraph_vtab_fts", false);
            executeContextStatement(state,
                    "INSERT INTO egraph_vtab_fts(rowid, title, body) VALUES "
                            + "(1, 'one', 'alpha beta gamma'), (2, 'two', 'delta epsilon zeta')",
                    false);
            executeContextStatement(state,
                    "UPDATE egraph_vtab_fts SET body = body || ' updated' WHERE rowid = 1", false);
            executeContextStatement(state, "DELETE FROM egraph_vtab_fts WHERE egraph_vtab_fts MATCH 'delta'", false);
            executeContextStatement(state,
                    "SELECT rowid, title FROM egraph_vtab_fts WHERE egraph_vtab_fts MATCH 'alpha OR updated'", false);
        }
        return ok;
    }

    private static boolean setupVirtualTableSavepointContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_vtab_sp_fts USING fts5(title, body, prefix='2 3')",
                true);
        ok &= executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_vtab_sp_rt USING rtree(id, x1, x2, y1, y2)", true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_vtab_sp_fts", false);
        ok &= executeContextStatement(state, "DELETE FROM egraph_vtab_sp_rt", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_vtab_sp_fts(rowid, title, body) VALUES "
                        + "(1, 'alpha beta', 'alpha beta sqlite virtual table savepoint'), "
                        + "(2, 'beta gamma', 'rollback release savepoint fts5 prefix')",
                false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_vtab_sp_rt VALUES "
                        + "(1, 0.0, 4.0, 0.0, 4.0), (2, 10.0, 20.0, 10.0, 20.0)",
                false);
        if (ok) {
            executeContextStatement(state, "SAVEPOINT egraph_vtab_sp_outer", false);
            executeContextStatement(state,
                    "INSERT OR REPLACE INTO egraph_vtab_sp_fts(rowid, title, body) VALUES "
                            + "(3, 'outer row', 'outer virtual table transaction row')",
                    false);
            executeContextStatement(state,
                    "INSERT OR REPLACE INTO egraph_vtab_sp_rt VALUES (3, 1.0, 3.0, 1.0, 3.0)", false);
            executeContextStatement(state, "SAVEPOINT egraph_vtab_sp_inner", false);
            executeContextStatement(state,
                    "UPDATE egraph_vtab_sp_fts SET body = body || ' inner update' WHERE rowid = 1", false);
            executeContextStatement(state,
                    "UPDATE egraph_vtab_sp_rt SET x1 = x1 - 0.5, x2 = x2 + 0.5 WHERE id = 1", false);
            executeContextStatement(state,
                    "DELETE FROM egraph_vtab_sp_fts WHERE egraph_vtab_sp_fts MATCH 'gamma'", false);
            executeContextStatement(state, "ROLLBACK TO egraph_vtab_sp_inner", false);
            executeContextStatement(state, "RELEASE egraph_vtab_sp_inner", false);
            executeContextStatement(state, "RELEASE egraph_vtab_sp_outer", false);
            executeContextStatement(state,
                    "SELECT rowid FROM egraph_vtab_sp_fts WHERE egraph_vtab_sp_fts MATCH 'alpha OR beta' "
                            + "ORDER BY rank LIMIT 8",
                    false);
            executeContextStatement(state,
                    "SELECT id FROM egraph_vtab_sp_rt WHERE x1 <= 2.0 AND x2 >= 2.0 ORDER BY id LIMIT 8",
                    false);
            executeContextStatement(state, "SELECT rtreecheck('egraph_vtab_sp_rt')", false);
        }
        executeContextStatement(state, "ROLLBACK", false);
        return ok;
    }

    private static boolean setupFts5SecureDeleteContext(SQLite3GlobalState state) {
        boolean ok = executeContextStatement(state,
                "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_secure USING fts5(title, body, tokenize='porter')",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_fts_secure", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_fts_secure(rowid, title, body) VALUES "
                        + "(1, 'alpha coverage', 'sqlite fts5 secure delete vocabulary prefix'), "
                        + "(2, 'beta coverage', 'token prefix merge delete optimize'), "
                        + "(3, 'gamma coverage', 'query planner variants and egraph testing'), "
                        + "(4, 'delta coverage', 'prefix prefix prefix token data')",
                false);
        if (ok) {
            executeContextStatement(state,
                    "INSERT INTO egraph_fts_secure(egraph_fts_secure, rank) VALUES('secure-delete', 1)", false);
            executeContextStatement(state,
                    "DELETE FROM egraph_fts_secure WHERE rowid IN (SELECT rowid FROM egraph_fts_secure "
                            + "WHERE egraph_fts_secure MATCH 'prefix OR token' LIMIT 2)",
                    false);
            executeContextStatement(state,
                    "INSERT INTO egraph_fts_secure(rowid, title, body) VALUES "
                            + "(5, 'epsilon coverage', 'replacement token data after secure delete')",
                    false);
            executeContextStatement(state,
                    "SELECT rowid, bm25(egraph_fts_secure), highlight(egraph_fts_secure, 0, '[', ']') "
                            + "FROM egraph_fts_secure WHERE egraph_fts_secure MATCH 'coverage OR token' "
                            + "ORDER BY rank LIMIT 8",
                    false);
            executeContextStatement(state, "INSERT INTO egraph_fts_secure(egraph_fts_secure) VALUES('optimize')",
                    false);
            executeContextStatement(state,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS egraph_fts_secure_vocab "
                            + "USING fts5vocab(egraph_fts_secure, 'row')",
                    true);
            executeContextStatement(state,
                    "SELECT term, doc, cnt FROM egraph_fts_secure_vocab WHERE term >= 'a' ORDER BY term LIMIT 16",
                    false);
        }
        return ok;
    }

    private static boolean setupJsonbStressContext(SQLite3GlobalState state) {
        executeContextStatement(state,
                "SELECT json_valid(jsonb('{\"a\":[1,2,3],\"b\":{\"x\":7}}'), 8), "
                        + "json_valid(x'4a534f4e', 8), "
                        + "json_extract(jsonb('{\"a\":[1,2,3]}'), '$.a[1]')",
                false);
        executeContextStatement(state,
                "SELECT json_patch(jsonb('{\"a\":1,\"b\":{\"x\":2}}'), "
                        + "jsonb('{\"b\":{\"y\":3},\"c\":[4,5]}')), "
                        + "json_pretty(jsonb('{\"nested\":{\"array\":[1,{\"x\":2}]}}'))",
                false);
        return true;
    }

    private static boolean setupXferOptimizationContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_xfer_dst", true);
        ok &= executeContextStatement(state, "DROP TABLE IF EXISTS egraph_xfer_src", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_xfer_src(a INTEGER PRIMARY KEY, b TEXT, c REAL)", true);
        ok &= executeContextStatement(state,
                "CREATE TABLE egraph_xfer_dst(a INTEGER PRIMARY KEY, b TEXT, c REAL)", true);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_xfer_src(a, b, c) VALUES "
                        + "(1, 'one', 1.0), (2, 'two', 2.0), (3, 'three', 3.0), (4, 'four', 4.0)",
                false);
        if (ok) {
            executeContextStatement(state, "INSERT INTO egraph_xfer_dst SELECT * FROM egraph_xfer_src", false);
            executeContextStatement(state,
                    "SELECT a, b, c FROM egraph_xfer_dst WHERE a >= 0 ORDER BY a LIMIT 8", false);
        }

        // Every way a document can be malformed is its own bail-out inside
        // jsonTranslateTextToBlob, and a blob that is not valid JSONB is a separate walk through
        // jsonbValidityCheck - including the JSON5 integer forms, which have their own case there.
        // These are probes, not part of any compared query, so they cost nothing per check.
        executeContextStatement(state, "SELECT json_valid('{') + json_valid('}') + json_valid('[') + json_valid(']')", false);
        executeContextStatement(state, "SELECT json_valid('{,}') + json_valid('[,]') + json_valid('{\"a\"}') + json_valid('{\"a\":}')", false);
        executeContextStatement(state, "SELECT json_valid('{:1}') + json_valid('{\"a\":1,}x') + json_valid('{\"a\" 1}') + json_valid('[1 2]')", false);
        executeContextStatement(state, "SELECT json_valid('[\"a\" \"b\"]') + json_valid('{\"a\":1}}') + json_valid('[[[[') + json_valid('\"unterminated')", false);
        executeContextStatement(state, "SELECT json_valid('''unterminated') + json_valid('{a b:1}') + json_valid('{\"a\":01}') + json_valid('{\"a\":1e}')", false);
        executeContextStatement(state, "SELECT json_valid('{\"a\":1e+}') + json_valid('{\"a\":.}') + json_valid('{\"a\":-}') + json_valid('{\"a\":+}')", false);
        executeContextStatement(state, "SELECT json_valid('{\"a\":0x}') + json_valid('{\"a\":0xZ}') + json_valid('{\"a\":tru}') + json_valid('{\"a\":fals}')", false);
        executeContextStatement(state, "SELECT json_valid('{\"a\":nul}') + json_valid('{\"a\":Infinit}') + json_valid('{\"a\":Na}') + json_valid('[1,2,3')", false);
        executeContextStatement(state, "SELECT json_valid('{\"a\":[1,2}') + json_valid('nan') + json_valid('inf') + json_valid('--1')", false);
        executeContextStatement(state, "SELECT json_valid('1.2.3') + json_valid('{\"a\":1}{\"b\":2}') + json_valid('[] []')", false);
        executeContextStatement(state, "SELECT json_valid(x'', 8) + json_valid(x'00', 8) + json_valid(x'01', 8) + json_valid(x'02', 8) + json_valid(x'03', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'04', 8) + json_valid(x'05', 8) + json_valid(x'06', 8) + json_valid(x'07', 8) + json_valid(x'08', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'09', 8) + json_valid(x'0a', 8) + json_valid(x'0b', 8) + json_valid(x'0c', 8) + json_valid(x'0d', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'0e', 8) + json_valid(x'0f', 8) + json_valid(x'1c', 8) + json_valid(x'2c', 8) + json_valid(x'3c', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'4c30', 8) + json_valid(x'5c3078', 8) + json_valid(x'6c307a', 8) + json_valid(x'7c', 8) + json_valid(x'8c00', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'9c0000', 8) + json_valid(x'ac000000', 8) + json_valid(x'bc', 8) + json_valid(x'cc', 8) + json_valid(x'0b2d', 8)", false);
        executeContextStatement(state, "SELECT json_valid(x'0b30', 8) + json_valid(x'0b3078', 8) + json_valid(x'0b307a', 8) + json_valid(x'0c2d30', 8) + json_valid(x'0c2b', 8)", false);
        executeContextStatement(state,
                "SELECT json(jsonb('{a:0x1f, b:0X7FFFFFFF, c:-0xff, d:+0x10}')), "
                        + "json(jsonb('[0x0, 0xff, 0xffff, 0x7fffffffffffffff]')), "
                        + "json(jsonb('{a:+1, b:-1, c:+1.5, d:-1.5, e:.5, f:-.5, g:5., h:-5.}')), "
                        + "json(jsonb('{a:1e5, b:1E5, c:1e+5, d:1e-5, e:-1e5}'))",
                false);
        executeContextStatement(state,
                "SELECT json_valid(substr(jsonb('{\"a\":[1,2,3],\"b\":\"xyz\"}'), 1, 3), 8) "
                        + "+ json_valid(substr(jsonb('{\"a\":[1,2,3],\"b\":\"xyz\"}'), 1, 6), 8) "
                        + "+ json_valid(substr(jsonb('{\"a\":[1,2,3],\"b\":\"xyz\"}'), 2, 12), 8) "
                        + "+ json_valid(jsonb('{\"a\":1}') || x'ff', 8) "
                        + "+ json_valid(x'ff' || jsonb('{\"a\":1}'), 8)",
                false);
        // Whitespace between tokens has a branch per space character in the skipper.
        executeContextStatement(state,
                "SELECT json('{ \"a\" : 1 , \"b\" : 2 }'), json('{' || char(9) || '\"a\"' || char(9) "
                        + "|| ':' || char(9) || '1}'), json('{' || char(10) || '\"a\":1' || char(10) || '}'), "
                        + "json('{' || char(13) || '\"a\":1' || char(13) || '}'), json('   {\"a\":1}   ')",
                false);
        return ok;
    }

    private static boolean setupMultiSelectOrderByContext(SQLite3GlobalState state) {
        boolean ok = true;
        ok &= executeContextStatement(state,
                "CREATE TABLE IF NOT EXISTS egraph_multiselect_probe(id INTEGER PRIMARY KEY, v INTEGER, tag TEXT)",
                true);
        ok &= executeContextStatement(state, "DELETE FROM egraph_multiselect_probe", false);
        ok &= executeContextStatement(state,
                "INSERT INTO egraph_multiselect_probe(id, v, tag) VALUES "
                        + "(1, 10, 'alpha'), (2, 20, 'beta'), (3, 30, 'gamma'), (4, 40, 'delta')",
                false);
        if (ok) {
            executeContextStatement(state,
                    "SELECT v, tag FROM egraph_multiselect_probe WHERE v <= 20 "
                            + "UNION ALL SELECT v, tag FROM egraph_multiselect_probe WHERE v >= 30 "
                            + "ORDER BY tag DESC, v ASC LIMIT 8",
                    false);
            executeContextStatement(state,
                    "SELECT v FROM egraph_multiselect_probe WHERE tag LIKE 'a%' "
                            + "UNION SELECT v FROM egraph_multiselect_probe WHERE tag LIKE 'd%' "
                            + "ORDER BY 1 DESC",
                    false);
        }
        return ok;
    }

    private static String getEGraphContextIndexName(String tableName, String columnName) {
        return "egraph_ctx_idx_" + sanitizeIdentifierPart(tableName) + "_" + sanitizeIdentifierPart(columnName);
    }

    private static boolean hasUsableIdentifier(SQLite3Column column) {
        return column != null && hasUsableIdentifier(column.getName());
    }

    private static boolean hasUsableIdentifier(String identifier) {
        return identifier != null && !identifier.isBlank();
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String sanitizeIdentifierPart(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "unnamed";
        }
        String sanitized = identifier.replaceAll("[^A-Za-z0-9_]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        return sanitized;
    }

    private static boolean executeContextStatement(SQLite3GlobalState state, String sql, boolean couldAffectSchema) {
        if (state == null) {
            return false;
        }
        try {
            SQLQueryAdapter query = new SQLQueryAdapter(sql, couldAffectSchema || contextStatementCouldAffectSchema(sql));
            boolean success = query.execute(state, false);
            if (success) {
                logExecutedContextStatement(state, query);
                EGraphContextReplayWriter.record(query.getQueryString());
                // Also kept for this thread alone, so the trunk referee can put a second engine in
                // the same position - including the databases this attached from memory, which a
                // copy of the database file does not carry.
                EGraphContextSnapshot.record(query.getQueryString());
            }
            return success;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean contextStatementCouldAffectSchema(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("CREATE ") || normalized.startsWith("DROP ") || normalized.startsWith("ALTER ")
                || normalized.startsWith("ATTACH ") || normalized.startsWith("DETACH ")
                || normalized.startsWith("VACUUM");
    }

    private static void logExecutedContextStatement(SQLite3GlobalState state, SQLQueryAdapter query) {
        if (state.getState() != null) {
            state.getState().logStatement(query);
        }
        if (state.getOptions() != null && state.getOptions().logEachSelect() && state.getLogger() != null) {
            state.getLogger().writeCurrent(query.getQueryString());
        }
    }

    private static final class EGraphCoverageContext {
        private final EGraphCoverageShape shape;
        private final String tableName;
        private final String columnName;
        private final String secondColumnName;
        private final String indexName;
        private final String groupByColumns;

        private EGraphCoverageContext(EGraphCoverageShape shape, String tableName, String columnName,
                String secondColumnName, String indexName, String groupByColumns) {
            this.shape = shape;
            this.tableName = tableName;
            this.columnName = columnName;
            this.secondColumnName = secondColumnName;
            this.indexName = indexName;
            this.groupByColumns = groupByColumns;
        }
    }

    private static final class EGraphBaseQuery {
        private final String sql;
        private final String shapeName;

        private EGraphBaseQuery(String sql, String shapeName) {
            this.sql = sql;
            this.shapeName = shapeName;
        }
    }

    private static final class SelectOnlyFrom {
        private final String tableName;
        private final String alias;

        private SelectOnlyFrom(String tableName, String alias) {
            this.tableName = tableName;
            this.alias = alias;
        }
    }

    private static final class SelectOnlyMapper {
        private final List<SQLite3Column> targetColumns;
        private final Map<String, String> mappedColumns = new HashMap<>();

        private SelectOnlyMapper(List<SQLite3Column> targetColumns) {
            this.targetColumns = targetColumns;
        }

        private String map(String sourceIdentifier) {
            String key = normalizeIdentifier(sourceIdentifier);
            return mappedColumns.computeIfAbsent(key, ignored -> {
                SQLite3Column target = targetColumns.get(mappedColumns.size() % targetColumns.size());
                return quoteIdentifier(target.getName());
            });
        }

        private int mappedColumnCount() {
            return mappedColumns.size();
        }
    }

}
