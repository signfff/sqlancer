package sqlancer.sqlite3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.sqlite3.gen.ddl.SQLite3TableGenerator;

/**
 * The table generator's foreign keys, in a database that holds what an EGRAPH corpus case leaves behind: a view whose
 * repeated columns SQLite names "b:1", and a table with a column name that needs quoting. The 2026-10-03 trunk run
 * generated {@code REFERENCES v1(b:1, c:1, b, c)}, a syntax error that killed the thread.
 */
public class TestSQLite3ForeignKeyTargets {

    @Test
    public void foreignKeysSkipViewsAndQuoteOtherTablesNames() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE b0(b, c)");
            statement.execute("CREATE VIEW v1 AS SELECT b, c, b, c FROM b0");
            statement.execute("CREATE TABLE p(\"odd col\")");

            SQLite3GlobalState state = new SQLite3GlobalState();
            state.setConnection(new SQLConnection(connection));
            state.setMainOptions(new MainOptions());
            state.setDbmsSpecificOptions(new SQLite3Options());
            state.setRandomly(new Randomly(7));
            state.updateSchema();

            int intoOtherTable = 0;
            for (int i = 0; i < 5000; i++) {
                String sql = SQLite3TableGenerator.createTableStatement("x" + i, state).getQueryString();
                if (!sql.contains("REFERENCES")) {
                    continue;
                }
                assertFalse(sql.contains("REFERENCES v1") || sql.contains("REFERENCES \"v1\""),
                        "a foreign key into a view: " + sql);
                if (sql.contains("REFERENCES \"")) {
                    intoOtherTable++;
                }
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    if (e.getMessage().contains("syntax error")) {
                        fail(e.getMessage() + ": " + sql);
                    }
                }
            }
            assertTrue(intoOtherTable > 0, "no foreign key into another table was generated");
        }
    }
}
