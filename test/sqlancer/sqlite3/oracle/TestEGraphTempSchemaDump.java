package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.SQLConnection;

class TestEGraphTempSchemaDump {

    private static final String[] SETUP = {
            "CREATE TABLE t2(c0 INT)", "INSERT INTO t2 VALUES (99)",
            "CREATE TEMPORARY TABLE IF NOT EXISTS t2 (c0 TEXT, c1 AS (c0 || 'x'), UNIQUE(c0))",
            "INSERT INTO temp.t2(c0) VALUES ('K0X3O''s'), (NULL), (x'00ff'), (1.5e300)",
            "CREATE INDEX temp.i0 ON t2(c1)", "CREATE TEMP VIEW v0 AS SELECT c0 FROM t2",
            "CREATE TEMP TRIGGER tr0 AFTER INSERT ON t2 BEGIN DELETE FROM t2; END",
            "CREATE VIRTUAL TABLE temp.rt USING rtree(id, x0, x1)", "INSERT INTO temp.rt VALUES (1, 0.5, 2.5)" };

    @Test
    void replayedTempObjectsMatchTheOriginal() throws Exception {
        try (Connection a = DriverManager.getConnection("jdbc:sqlite::memory:");
                Connection b = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement s = a.createStatement()) {
                for (String sql : SETUP) {
                    s.execute(sql);
                }
            }
            try (Statement s = b.createStatement()) {
                s.execute("CREATE TABLE t2(c0 INT)");
                s.execute("INSERT INTO t2 VALUES (99)");
                for (String sql : EGraphTempSchemaDump.statements(new SQLConnection(a))) {
                    s.execute(sql);
                }
            }
            for (String q : new String[] { "SELECT type, name, sql FROM temp.sqlite_master ORDER BY name",
                    "SELECT quote(c0), quote(c1) FROM t2 ORDER BY 1", "SELECT * FROM main.t2", "SELECT * FROM v0",
                    "SELECT * FROM rt" }) {
                assertEquals(rows(a, q), rows(b, q), q);
            }
        }
    }

    @Test
    void qualifiesTheNameIntoTemp() {
        assertEquals("CREATE UNIQUE INDEX temp.i ON t(c)", EGraphTempSchemaDump.intoTemp("CREATE UNIQUE INDEX i ON t(c)"));
        assertEquals("CREATE TABLE temp.\"a b\"(c)", EGraphTempSchemaDump.intoTemp("CREATE TABLE \"a b\"(c)"));
    }

    private static List<String> rows(Connection c, String query) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }
}
