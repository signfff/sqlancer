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
        List<String> result = new ArrayList<>();
        if (!(connection instanceof SQLConnection)) {
            return result;
        }
        try (Statement s = ((SQLConnection) connection).createStatement()) {
            List<String[]> objects = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(
                    "SELECT type, name, sql FROM temp.sqlite_master WHERE sql IS NOT NULL ORDER BY rowid")) {
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
                    result.add(intoTemp(o[2]));
                    tables.add(o[1]);
                }
            }
            for (String table : tables) {
                result.addAll(inserts(s, table));
            }
            for (String[] o : objects) {
                if (!"table".equals(o[0])) {
                    result.add(intoTemp(o[2]));
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
        return CREATE.matcher(sql.strip()).replaceFirst("$1 temp.");
    }

    private static List<String> inserts(Statement s, String table) throws SQLException {
        String quotedTable = "temp.\"" + table.replace("\"", "\"\"") + "\"";
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("PRAGMA temp.table_xinfo(\"" + table.replace("\"", "\"\"") + "\")")) {
            while (rs.next()) {
                if (rs.getInt("hidden") == 0) {
                    columns.add("\"" + rs.getString("name").replace("\"", "\"\"") + "\"");
                }
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
}
