package sqlancer.common.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.SQLConnection;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.sqlite3.SQLite3GlobalState;

public class TestEGraphMetamorphicOracle {

    @Test
    public void testResultRowsUseMultisetComparison() {
        List<String> first = List.of("S1:a|S1:x", "S1:a|S1:x", "S1:b|S1:y");
        List<String> sameRowsDifferentOrder = List.of("S1:b|S1:y", "S1:a|S1:x", "S1:a|S1:x");
        List<String> differentDuplicateCount = List.of("S1:b|S1:y", "S1:b|S1:y", "S1:a|S1:x");

        assertTrue(EGraphMetamorphicOracle.resultRowsMatch(first, sameRowsDifferentOrder));
        assertFalse(EGraphMetamorphicOracle.resultRowsMatch(first, differentDuplicateCount));
    }

    @Test
    public void testOrderedQueriesUseSequenceComparison() {
        List<String> first = List.of("S1:a", "S1:b");
        List<String> sameRowsDifferentOrder = List.of("S1:b", "S1:a");

        // The same rows in a different order are not a finding, ORDER BY or not: an ORDER BY inside a
        // wrapper does not order the query around it, and one on a column with ties does not fix the
        // order of the tied rows. Such a difference is counted elsewhere instead.
        assertTrue(EGraphMetamorphicOracle.resultRowsMatch(first, sameRowsDifferentOrder,
                "SELECT c0 FROM t0 ORDER BY c0", "SELECT c0 FROM t0 ORDER BY c0"));
        assertTrue(EGraphMetamorphicOracle.resultRowsMatch(first, sameRowsDifferentOrder,
                "SELECT c0 FROM t0", "SELECT c0 FROM t0"));
        assertFalse(EGraphMetamorphicOracle.resultRowsMatch(first, List.of("S1:a", "S1:c"),
                "SELECT c0 FROM t0 ORDER BY c0", "SELECT c0 FROM t0 ORDER BY c0"));
    }

    @Test
    public void testResultValueEncodingKeepsNullDistinctFromStringNull() {
        assertFalse(EGraphMetamorphicOracle.encodeResultValue(null)
                .equals(EGraphMetamorphicOracle.encodeResultValue("N")));
    }

    @Test
    public void testSingleSideEmptyRowsAreMismatches() {
        assertTrue(EGraphMetamorphicOracle.hasSingleSideEmptyMismatch(List.of("S1:1"), List.of()));
        assertTrue(EGraphMetamorphicOracle.hasSingleSideEmptyMismatch(List.of(), List.of("S1:1")));
        assertFalse(EGraphMetamorphicOracle.hasSingleSideEmptyMismatch(List.of(), List.of()));
        assertFalse(EGraphMetamorphicOracle.hasSingleSideEmptyMismatch(List.of("S1:1"), List.of("S1:2")));
    }

    @Test
    public void testNumericSpellingOnlyDifference() {
        // 1511914780 as INTEGER vs REAL: the rows agree once the spellings are folded.
        List<String> integerSpelling = List.of("S10:1511914780");
        List<String> floatSpelling = List.of("S12:1511914780.0");
        assertTrue(EGraphMetamorphicOracle.isNumericSpellingOnlyDifference(integerSpelling, floatSpelling));
        // Same rows exactly: no difference at all.
        assertFalse(EGraphMetamorphicOracle.isNumericSpellingOnlyDifference(integerSpelling, integerSpelling));
        // A value difference is not a spelling difference, whatever the spelling of the other side.
        assertFalse(EGraphMetamorphicOracle.isNumericSpellingOnlyDifference(integerSpelling, List.of("S1:1")));
        // Different row counts can never fold together.
        assertFalse(EGraphMetamorphicOracle.isNumericSpellingOnlyDifference(integerSpelling,
                List.of("S12:1511914780.0", "S12:1511914780.0")));
    }

    @Test
    public void testRepresentativeChoiceOnly() {
        // The 2026-10-13 repro/0001 shape: each wrapped side kept one of two equal rows - the INTEGER
        // spelling on one side, the REAL on the other - while the unwrapped pair returns both rows on
        // both sides. The wrapper's representative choice explains the difference.
        List<String> wrappedInteger = List.of("S10:1511914780");
        List<String> wrappedFloat = List.of("S12:1511914780.0");
        List<String> unwrappedBoth = List.of("S10:1511914780", "S12:1511914780.0");
        assertTrue(EGraphMetamorphicOracle.isRepresentativeChoiceOnly(wrappedInteger, wrappedFloat, unwrappedBoth,
                unwrappedBoth));
        // The 2026-10-01 partial-index family: the unwrapped pair disagrees too, so the spelling
        // difference is real - one side really did return the other storage class.
        assertFalse(EGraphMetamorphicOracle.isRepresentativeChoiceOnly(wrappedInteger, wrappedFloat, unwrappedBoth,
                List.of("S12:1511914780.0", "S12:1511914780.0")));
        // Without a spelling-only difference on the wrapped side the gate never applies.
        assertFalse(EGraphMetamorphicOracle.isRepresentativeChoiceOnly(List.of("S1:1"), List.of("S1:2"),
                unwrappedBoth, unwrappedBoth));
    }

    /**
     * The 2026-10-13 repro/0001 shape, on a real engine: t3 holds 1511914780 as INTEGER and as
     * REAL. The == and BETWEEN spellings pick different indexes, which feed the two equal rows to
     * GROUP BY in opposite orders, so each wrapper keeps a different representative - while the
     * unwrapped pair returns the same rows on both sides. That is the wrapper's arbitrary choice,
     * not a defect, and the gate must excuse it.
     */
    @Test
    public void testRepro0001ShapeExcusedByTheGate() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SQLite3GlobalState state = new SQLite3GlobalState();
            state.setConnection(new SQLConnection(connection));
            state.setMainOptions(new MainOptions());
            ExpectedErrors errors = ExpectedErrors.newErrors().build();
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE t3 (c0 BLOB)");
                stmt.execute("INSERT INTO t3 VALUES (1511914780)");
                stmt.execute("INSERT INTO t3 VALUES (1511914780.0)");
                stmt.execute("CREATE INDEX i_eq ON t3(c0) WHERE c0 == 1511914780");
                stmt.execute("CREATE INDEX i_ctx ON t3(c0) WHERE c0 IS NOT NULL");
            }
            List<String> wrappedOriginal = selectRows(state, errors,
                    "SELECT c0 FROM (SELECT * FROM t3 WHERE c0 == 1511914780 ORDER BY c0 DESC LIMIT 10) GROUP BY c0");
            List<String> wrappedVariant = selectRows(state, errors,
                    "SELECT c0 FROM (SELECT * FROM t3 WHERE c0 BETWEEN 1511914780 AND 1511914780"
                            + " ORDER BY c0 DESC LIMIT 10) GROUP BY c0");
            List<String> unwrappedOriginal = selectRows(state, errors,
                    "SELECT c0 FROM t3 WHERE c0 == 1511914780 ORDER BY c0 DESC LIMIT 10");
            List<String> unwrappedVariant = selectRows(state, errors,
                    "SELECT c0 FROM t3 WHERE c0 BETWEEN 1511914780 AND 1511914780 ORDER BY c0 DESC LIMIT 10");
            // The premise: the wrapped pair really does differ, and only in numeric spelling.
            assertTrue(EGraphMetamorphicOracle.isNumericSpellingOnlyDifference(wrappedOriginal, wrappedVariant),
                    wrappedOriginal + " vs " + wrappedVariant);
            // And the gate excuses it: the unwrapped pair returns the same rows.
            assertTrue(EGraphMetamorphicOracle.isRepresentativeChoiceOnly(wrappedOriginal, wrappedVariant,
                    unwrappedOriginal, unwrappedVariant));
        }
    }

    /** Runs one SELECT through the same row encoding the oracle compares. */
    private static List<String> selectRows(SQLite3GlobalState state, ExpectedErrors errors, String sql)
            throws Exception {
        SQLQueryAdapter q = new SQLQueryAdapter(sql, errors);
        SQLancerResultSet result = q.executeAndGet(state);
        assertTrue(result != null, "the query returned nothing: " + sql);
        List<String> rows = new ArrayList<>();
        int columnCount = result.getColumnCount();
        while (result.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    row.append('|');
                }
                row.append(EGraphMetamorphicOracle.encodeResultValue(result.getString(i)));
            }
            rows.add(row.toString());
        }
        result.close();
        return rows;
    }
}
