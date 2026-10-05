package sqlancer.sqlite3;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.SQLConnection;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.oracle.EGraphSqlCoverage;

/**
 * An error swallowed as expected keeps its message. The EGRAPH expected errors include "[SQLITE_ERROR] SQL error or
 * missing database", the prefix of every SQLite error, so without it a variant-only error read the same whether it was
 * malformed JSON or a syntax error in the rewritten query.
 */
public class TestSwallowedErrorIsRecorded {

    @Test
    public void theSwallowedMessageReachesTheVariantOnlyLog() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SQLite3GlobalState state = new SQLite3GlobalState();
            state.setConnection(new SQLConnection(connection));
            state.setMainOptions(new MainOptions());
            ExpectedErrors errors = ExpectedErrors.newErrors()
                    .with("[SQLITE_ERROR] SQL error or missing database").build();

            SQLQueryAdapter q = new SQLQueryAdapter("SELECT json('x')", errors);
            assertNull(q.executeAndGet(state), "the error is swallowed");
            assertTrue(q.getSwallowedError().contains("malformed JSON"), q.getSwallowedError());

            Path log = Files.createTempFile("variant-only", ".log");
            String previous = System.getProperty("egraph.variantOnlyError.log");
            System.setProperty("egraph.variantOnlyError.log", log.toString());
            try {
                EGraphSqlCoverage.recordVariantOnlyError(
                        "(expected error swallowed by SQLQueryAdapter) " + q.getSwallowedError(), "SELECT 1",
                        "SELECT json('x')");
            } finally {
                if (previous == null) {
                    System.clearProperty("egraph.variantOnlyError.log");
                } else {
                    System.setProperty("egraph.variantOnlyError.log", previous);
                }
            }
            String header = Files.readAllLines(log).get(0);
            assertTrue(header.contains("malformed JSON"), "the reason survives the key's truncation: " + header);
            Files.delete(log);
        }
    }
}
