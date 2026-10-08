package sqlancer.sqlite3.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import sqlancer.SQLConnection;

/**
 * The connection's TEMP objects and their rows as SQL, for replaying a mismatch somewhere else.
 *
 * <p>
 * The generator creates TEMP tables, and those live only in the connection, never in the database file. A bundle or a
 * referee run given only the file found no such table, the shell printed the error on stderr, and both sides read as
 * zero rows. On 2026-10-07 that made a mismatch on a TEMP table look FIXED_UPSTREAM, which files it away as a known
 * bug: any real defect on a TEMP table would have been suppressed the same way.
 * </p>
 *
 * <p>
 * Tables come first, then their rows through {@code quote()}, then indexes, views and triggers, so no trigger fires
 * on the replayed rows. Generated columns are left out of the inserts. A statement the replay cannot run is skipped
 * there like any other context statement.
 * </p>
 */
public final class EGraphTempSchemaDump {

    private EGraphTempSchemaDump() {
    }

    /** The statements, or an empty list when there are no TEMP objects or they could not be read. */
    public static List<String> statements(Object connection) {
        return statements(connection, "temp");
    }

    /**
     * The same for a schema of the connection, "main" or "temp", read through the connection so that rows of a
     * transaction it has not committed are included - which a copy of the database file does not have. SQLite's own
     * tables (sqlite_stat1, sqlite_sequence, ...) are left to {@link #internalTables}.
     */
    public static List<String> statements(Object connection, String schema) {
        List<String> result = new ArrayList<>();
        if (!(connection instanceof SQLConnection)) {
            return result;
        }
        try (Statement s = ((SQLConnection) connection).createStatement()) {
            List<String[]> objects = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "SELECT type, name, sql FROM " + schema + ".sqlite_master WHERE sql IS NOT NULL "
                            + "AND substr(name, 1, 7) <> 'sqlite_' ORDER BY rowid")) {
                while (rs.next()) {
                    objects.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
                }
            }
            // A virtual table makes its own shadow tables and takes its rows through itself.
            List<String> virtualTables = new ArrayList<>();
            for (String[] o : objects) {
                if ("table".equals(o[0]) && o[2].toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL")) {
                    virtualTables.add(o[1] + "_");
                }
            }
            List<String> tables = new ArrayList<>();
            for (String[] o : objects) {
                if ("table".equals(o[0]) && virtualTables.stream().noneMatch(o[1]::startsWith)) {
                    result.add(into(schema, o[2]));
                    tables.add(o[1]);
                }
            }
            for (String table : tables) {
                result.addAll(inserts(s, schema, table));
            }
            for (String[] o : objects) {
                if (!"table".equals(o[0])) {
                    result.add(into(schema, o[2]));
                }
            }
        } catch (SQLException | RuntimeException e) {
            return new ArrayList<>();
        }
        return result;
    }

    private static final Pattern CREATE = Pattern.compile(
            "^(CREATE\\s+(?:UNIQUE\\s+INDEX|INDEX|VIRTUAL\\s+TABLE|TABLE|VIEW|TRIGGER))\\s+",
            Pattern.CASE_INSENSITIVE);

    /**
     * SQLite stores a TEMP object's text without the TEMP keyword, so replayed as it is the object would land in main.
     * The stored text is normalised up to the name, which makes qualifying the name enough.
     */
    static String intoTemp(String sql) {
        return into("temp", sql);
    }

    private static String into(String schema, String sql) {
        return CREATE.matcher(sql.strip()).replaceFirst("$1 " + schema + ".");
    }

    private static List<String> inserts(Statement s, String schema, String table) throws SQLException {
        String quotedTable = schema + ".\"" + table.replace("\"", "\"\"") + "\"";
        List<String> columns = new ArrayList<>();
        boolean integerPrimaryKey = false;
        int primaryKeyColumns = 0;
        try (ResultSet rs = s.executeQuery(
                "PRAGMA " + schema + ".table_xinfo(\"" + table.replace("\"", "\"\"") + "\")")) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    primaryKeyColumns++;
                    integerPrimaryKey = "INTEGER".equalsIgnoreCase(rs.getString("type"));
                }
                if (rs.getInt("hidden") == 0) {
                    columns.add("\"" + rs.getString("name").replace("\"", "\"\"") + "\"");
                }
            }
        }
        // The rowid too, where it is not a column already: a plan can depend on it, and a fresh
        // insert would number the rows anew.
        if (!(integerPrimaryKey && primaryKeyColumns == 1) && !columns.isEmpty()) {
            try (ResultSet rs = s.executeQuery("SELECT rowid FROM " + quotedTable + " LIMIT 0")) {
                columns.add(0, "rowid");
            } catch (SQLException withoutRowid) {
                // A WITHOUT ROWID table, or a virtual table without one.
            }
        }
        List<String> result = new ArrayList<>();
        if (columns.isEmpty()) {
            return result;
        }
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : " || ',' || ").append("quote(").append(columns.get(i)).append(')');
        }
        select.append(" FROM ").append(quotedTable);
        String prefix = "INSERT INTO " + quotedTable + "(" + String.join(", ", columns) + ") VALUES (";
        try (ResultSet rs = s.executeQuery(select.toString())) {
            while (rs.next()) {
                result.add(prefix + rs.getString(1) + ")");
            }
        }
        return result;
    }

    /**
     * Rows of SQLite's own tables in a schema - the planner's statistics and AUTOINCREMENT counters - as statements
     * that put them back after the objects exist: ANALYZE creates the statistics tables, the rows replace whatever it
     * computed, and ANALYZE sqlite_schema makes the planner load them.
     */
    public static List<String> internalTables(Object connection, String schema) {
        List<String> result = new ArrayList<>();
        if (!(connection instanceof SQLConnection)) {
            return result;
        }
        try (Statement s = ((SQLConnection) connection).createStatement()) {
            List<String> present = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT name FROM " + schema + ".sqlite_master WHERE type = 'table' "
                    + "AND name IN ('sqlite_stat1', 'sqlite_stat4', 'sqlite_sequence')")) {
                while (rs.next()) {
                    present.add(rs.getString(1));
                }
            }
            boolean stats = present.contains("sqlite_stat1") || present.contains("sqlite_stat4");
            if (stats) {
                result.add("ANALYZE " + schema);
            }
            for (String table : present) {
                result.add("DELETE FROM " + schema + "." + table);
                String select = "sqlite_stat4".equals(table)
                        ? "SELECT quote(tbl) || ',' || quote(idx) || ',' || quote(neq) || ',' || quote(nlt) || ',' || "
                                + "quote(ndlt) || ',' || quote(sample) FROM " + schema + "." + table
                        : "sqlite_stat1".equals(table)
                                ? "SELECT quote(tbl) || ',' || quote(idx) || ',' || quote(stat) FROM " + schema + "."
                                        + table
                                : "SELECT quote(name) || ',' || quote(seq) FROM " + schema + "." + table;
                try (ResultSet rs = s.executeQuery(select)) {
                    while (rs.next()) {
                        result.add("INSERT INTO " + schema + "." + table + " VALUES (" + rs.getString(1) + ")");
                    }
                }
            }
            if (stats) {
                result.add("ANALYZE " + schema + ".sqlite_schema");
            }
        } catch (SQLException | RuntimeException e) {
            return new ArrayList<>();
        }
        return result;
    }
}
