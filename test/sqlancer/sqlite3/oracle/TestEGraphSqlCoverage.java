package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sqlancer.sqlite3.oracle.EGraphSqlCoverage.ExecutionFeature;
import sqlancer.sqlite3.oracle.EGraphSqlCoverage.SqlFeature;

public class TestEGraphSqlCoverage {

    @BeforeEach
    public void resetCoverageCounters() {
        EGraphSqlCoverage.hits.values().forEach(v -> v.set(0));
        EGraphSqlCoverage.executionHits.values().forEach(v -> v.set(0));
        EGraphSqlCoverage.executedQueries.set(0);
        EGraphSqlCoverage.explainedQueries.set(0);
        EGraphSqlCoverage.explainFailures.set(0);
        EGraphSqlCoverage.representativeChoiceDifferences.set(0);
    }

    @Test
    public void testInputSqlFeatureCoverage() {
        EGraphSqlCoverage.analyzeWhere("SELECT * FROM t0 WHERE c0 >= 1 AND c1 IS NOT NULL");

        assertEquals(1, EGraphSqlCoverage.hits.get(SqlFeature.LOGICAL_AND).get());
        assertEquals(1, EGraphSqlCoverage.hits.get(SqlFeature.CMP_GREATER_EQ).get());
        assertEquals(1, EGraphSqlCoverage.hits.get(SqlFeature.IS_NOT_NULL).get());
        assertEquals(1, EGraphSqlCoverage.hits.get(SqlFeature.COLUMN_REF).get());
    }

    @Test
    public void testExecutionPlanFeatureCoverage() {
        EGraphSqlCoverage.analyzeExecutionPlanDetail("SCAN t0");
        EGraphSqlCoverage.analyzeExecutionPlanDetail("SEARCH t1 USING COVERING INDEX i1 (c0=?)");
        EGraphSqlCoverage.analyzeExecutionPlanDetail("USE TEMP B-TREE FOR ORDER BY");
        EGraphSqlCoverage.analyzeExecutionPlanDetail("CORRELATED SCALAR SUBQUERY 1");

        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.TABLE_SCAN).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.INDEX_SEARCH).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.COVERING_INDEX).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.TEMP_BTREE).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.ORDER_BY).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.SUBQUERY).get());
        assertEquals(1, EGraphSqlCoverage.executionHits.get(ExecutionFeature.CORRELATED_SUBQUERY).get());
    }

    @Test
    public void testRepresentativeChoiceCounter() {
        EGraphSqlCoverage.recordRepresentativeChoiceDifference("SELECT 1", "SELECT 2");
        assertEquals(1, EGraphSqlCoverage.representativeChoiceDifferences.get());
    }
}
