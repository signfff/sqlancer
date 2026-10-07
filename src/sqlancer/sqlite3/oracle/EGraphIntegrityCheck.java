package sqlancer.sqlite3.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import sqlancer.SQLConnection;
import sqlancer.sqlite3.SQLite3GlobalState;

/**
 * Reads what the context setup's integrity checks answer.
 *
 * <p>
 * The setups run {@code PRAGMA integrity_check}, {@code quick_check} and {@code rtreecheck()}, but through a call that
 * only says whether a statement ran, so a database reporting itself corrupt went unnoticed. Anything other than a
 * single {@code ok} is now a finding: logged to {@code -Degraph.integrity.log} and, the first
 * {@code -Degraph.integrity.perMessage} (3) times per message with its numbers blanked, kept as a bundle under
 * {@code -Degraph.repro.dir} like a mismatch. It does not end the check: a defect that reports on every database
 * (contentless FTS4 did, 2026-10-01) would otherwise end every check.
 * </p>
 */
public final class EGraphIntegrityCheck {

    private static final Pattern CHECK = Pattern.compile(
            "^\\s*(PRAGMA\\s+(\\w+\\.)?(integrity_check|quick_check)\\b|SELECT\\s+rtreecheck\\s*\\()",
            Pattern.CASE_INSENSITIVE);
    private static final String LOG = System.getProperty("egraph.integrity.log", "");
    private static final String REPRO_DIR = System.getProperty("egraph.repro.dir", "");
    private static final int PER_MESSAGE = Integer.getInteger("egraph.integrity.perMessage", 3);
    private static final Map<String, AtomicInteger> SEEN = new ConcurrentHashMap<>();
    private static final AtomicInteger BUNDLES = new AtomicInteger();

    private EGraphIntegrityCheck() {
    }

    public static boolean isCheck(String sql) {
        return sql != null && CHECK.matcher(sql).find();
    }

    /**
     * Runs the check and records its answer when it is not {@code ok}.
     *
     * @return whether the statement ran, as the context setup's other statements report it
     */
    public static boolean run(SQLite3GlobalState state, String sql) {
        List<String> rows = new ArrayList<>();
        try (Statement s = ((SQLConnection) state.getConnection()).createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(String.valueOf(rs.getString(1)));
            }
        } catch (SQLException | RuntimeException e) {
            return false;
        }
        if (rows.size() == 1 && "ok".equalsIgnoreCase(rows.get(0).strip())) {
            return true;
        }
        record(state, sql, rows);
        return true;
    }

    private static void record(SQLite3GlobalState state, String sql, List<String> rows) {
        String first = rows.isEmpty() ? "(no rows)" : rows.get(0);
        String key = first.replaceAll("\\d+", "N").toLowerCase(Locale.ROOT);
        int seen = SEEN.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (seen == 1) {
            System.err.println("[EGRAPH] integrity check reported a problem: " + sql + " -> " + first);
        }
        String nl = System.lineSeparator();
        StringBuilder entry = new StringBuilder("-- EGRAPH_INTEGRITY ").append(sql).append(nl);
        rows.stream().limit(20).forEach(r -> entry.append("-- ").append(r).append(nl));
        if (seen <= PER_MESSAGE) {
            Path bundle = saveBundle(state, sql, rows);
            if (bundle != null) {
                entry.append("-- bundle: ").append(bundle).append(nl);
            }
        }
        entry.append(nl);
        if (LOG.isBlank()) {
            return;
        }
        synchronized (EGraphIntegrityCheck.class) {
            try {
                Files.writeString(Path.of(LOG), entry.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The stderr line still says it happened.
            }
        }
    }

    /** The database file, the context and TEMP objects, then the check: as EGraphReproBundle does for mismatches. */
    private static Path saveBundle(SQLite3GlobalState state, String sql, List<String> rows) {
        if (REPRO_DIR.isBlank() || state.getDatabaseName() == null) {
            return null;
        }
        Path database = Path.of("databases", state.getDatabaseName() + ".db");
        if (!Files.isRegularFile(database)) {
            return null;
        }
        try {
            Path dir = Path.of(REPRO_DIR, String.format("integrity-%04d", BUNDLES.incrementAndGet()));
            Files.createDirectories(dir);
            Files.copy(database, dir.resolve("database.db"), StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : new String[] { "-wal", "-journal" }) {
                Path side = Path.of(database + suffix);
                if (Files.isRegularFile(side)) {
                    Files.copy(side, dir.resolve("database.db" + suffix), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String nl = System.lineSeparator();
            StringBuilder replay = new StringBuilder();
            for (String statement : EGraphContextSnapshot.current()) {
                replay.append(terminate(statement)).append(nl);
            }
            for (String statement : EGraphTempSchemaDump.statements(state.getConnection())) {
                replay.append(terminate(statement)).append(nl);
            }
            replay.append(".print == integrity check").append(nl).append(terminate(sql)).append(nl);
            Files.writeString(dir.resolve("replay.sql"), replay.toString(), StandardCharsets.UTF_8);
            StringBuilder caseText = new StringBuilder("integrity check: ").append(sql).append(nl)
                    .append("answer:").append(nl);
            rows.stream().limit(50).forEach(r -> caseText.append("  ").append(r).append(nl));
            caseText.append("reproduce: sqlite3_trunk database.db < replay.sql (the context replays first;"
                    + " a check of an attached database needs the context's ATTACH)").append(nl);
            Files.writeString(dir.resolve("case.txt"), caseText.toString(), StandardCharsets.UTF_8);
            return dir;
        } catch (IOException | RuntimeException e) {
            System.err.println("[EGRAPH] could not save an integrity bundle: " + e);
            return null;
        }
    }

    private static String terminate(String sql) {
        String s = sql.strip();
        return s.endsWith(";") ? s : s + ";";
    }

    /** One line per distinct problem for the end-of-run report. */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        SEEN.forEach((k, v) -> lines.add(String.format(Locale.ROOT, "  # %8d  %s", v.get(), k)));
        return lines;
    }
}
