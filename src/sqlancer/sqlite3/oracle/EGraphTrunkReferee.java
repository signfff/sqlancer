package sqlancer.sqlite3.oracle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Asks a build of SQLite's trunk whether a mismatch is one upstream has already fixed.
 *
 * <p>
 * The oracle reports that two queries which must agree did not, but not whose fault it is. Run the same two queries on
 * the same database under a build that carries every fix merged since the release under test: if they agree there, the
 * release has a defect upstream has since repaired, and the run does not need to report it again. If they disagree
 * there too, upstream has not fixed it - that one deserves a person's attention.
 * </p>
 *
 * <p>
 * Configured with {@code -Degraph.referee.command}, the command that starts the trunk shell, for instance
 * {@code wsl.exe -e /home/me/sqlite-trunk/src/sqlite3}. Unset, no refereeing happens and every mismatch stays a
 * finding. Record which check-in the build came from: trunk moves daily, so a verdict against "latest" cannot be
 * rechecked later.
 * </p>
 */
public final class EGraphTrunkReferee {

    /** What the trunk build says about a mismatch the release produced. */
    public enum Verdict {
        /** The two queries agree on trunk: the release carries a defect upstream has fixed. */
        FIXED_UPSTREAM,
        /** They disagree on trunk as well: upstream has not fixed this one. */
        LIVE_UPSTREAM,
        /**
         * The query does not pin its own result down, so the two spellings were free to differ. A LIMIT takes whichever
         * rows the plan reached first, and a plan change is exactly what a rewrite causes.
         */
        UNSTABLE_QUERY,
        /** No referee configured, or it could not answer. */
        UNKNOWN
    }

    private static final String COMMAND = System.getProperty("egraph.referee.command", "").strip();
    private static final long TIMEOUT_SECONDS = Long.getLong("egraph.referee.timeoutSeconds", 30);
    private static final AtomicLong FIXED = new AtomicLong();
    private static final AtomicLong LIVE = new AtomicLong();
    private static final AtomicLong UNSTABLE = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    private EGraphTrunkReferee() {
    }

    public static boolean isConfigured() {
        return !COMMAND.isEmpty();
    }

    /**
     * Runs both queries against a copy of the database under the trunk build.
     *
     * @param databaseFile the database the mismatch was produced on; it is copied first, so the running check is not
     *                     disturbed and the referee cannot write to it
     */
    public static Verdict judge(File databaseFile, String originalQuery, String variantQuery) {
        return judge(databaseFile, originalQuery, variantQuery, 0, List.of());
    }

    /**
     * @param variantMask optimizer flags the variant ran under (see EGraphOptimizerMask), replayed with
     *                    sqlancer_optimizations() before it; 0 for the default. Without it a defect the mask exposed
     *                    would not reproduce here, be judged FIXED_UPSTREAM and end up suppressed as a known bug.
     * @param tempStatements the connection's TEMP tables and rows (see EGraphTempSchemaDump), replayed after the context
     */
    public static Verdict judge(File databaseFile, String originalQuery, String variantQuery, int variantMask,
            List<String> tempStatements) {
        if (!isConfigured()) {
            return Verdict.UNKNOWN;
        }
        if (databaseFile == null || !databaseFile.isFile()) {
            unanswered("no-database-file", String.valueOf(databaseFile), originalQuery);
            return Verdict.UNKNOWN;
        }
        Path copy = null;
        try {
            copy = Files.createTempFile("egraph-referee", ".db");
            Files.copy(databaseFile.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
            String original = run(copy, originalQuery, 0, tempStatements);
            String variant = run(copy, variantQuery, variantMask, tempStatements);
            if (original == null || variant == null) {
                FAILED.incrementAndGet();
                unanswered(original == null ? "original-did-not-run" : "variant-did-not-run",
                        lastFailure, original == null ? originalQuery : variantQuery);
                return Verdict.UNKNOWN;
            }
            // Compare the rows as a multiset, never in order. SQL fixes the order of a result only
            // where an ORDER BY says so, and an ORDER BY inside a subquery does not fix the order of
            // the query around it - nor does a LIMIT choose the same rows on two different plans.
            // Comparing the output verbatim made those spellings look like defects upstream had
            // failed to fix, which is the opposite of what this is here to tell apart.
            original = sortLines(original);
            variant = sortLines(variant);
            if (original.equals(variant)) {
                FIXED.incrementAndGet();
                log("FIXED_UPSTREAM", originalQuery, variantQuery, original, variant);
                return Verdict.FIXED_UPSTREAM;
            }
            if (limitsWithoutTotalOrder(originalQuery) || limitsWithoutTotalOrder(variantQuery)) {
                UNSTABLE.incrementAndGet();
                log("UNSTABLE_QUERY", originalQuery, variantQuery, original, variant);
                return Verdict.UNSTABLE_QUERY;
            }
            LIVE.incrementAndGet();
            log("LIVE_UPSTREAM", originalQuery, variantQuery, original, variant);
            return Verdict.LIVE_UPSTREAM;
        } catch (IOException | InterruptedException e) {
            FAILED.incrementAndGet();
            unanswered("exception", String.valueOf(e), originalQuery);
            return Verdict.UNKNOWN;
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless.
                }
            }
        }
    }

    /**
     * Writes what the trunk build answered, to the file named by {@code egraph.referee.log}. A verdict is only worth
     * acting on if it can be read back: a LIVE_UPSTREAM entry may be a defect nobody has fixed, or a query whose
     * result SQL does not pin down at all - a LIMIT without an ORDER BY may return different rows on either engine.
     */
    private static void log(String verdict, String originalQuery, String variantQuery, String originalOutput,
            String variantOutput) {
        String path = System.getProperty("egraph.referee.log");
        if (path == null || path.isBlank()) {
            return;
        }
        StringBuilder entry = new StringBuilder();
        entry.append("-- EGRAPH_REFEREE ").append(verdict).append(System.lineSeparator());
        entry.append(originalQuery).append(System.lineSeparator());
        entry.append(variantQuery).append(System.lineSeparator());
        entry.append("-- trunk original (").append(countRows(originalOutput)).append(" rows): ")
                .append(firstLines(originalOutput)).append(System.lineSeparator());
        entry.append("-- trunk variant  (").append(countRows(variantOutput)).append(" rows): ")
                .append(firstLines(variantOutput)).append(System.lineSeparator());
        entry.append(System.lineSeparator());
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, entry.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The counts still reach the report.
        }
    }

    /**
     * Whether the query takes a LIMIT without an ORDER BY that could fix which rows it takes. Checked on the text,
     * which is coarse: a LIMIT ordered by a unique key does pin its rows down and is called unstable here anyway.
     * The trade is deliberate - such a mismatch cannot be told apart from a defect without running the plan.
     */
    static boolean limitsWithoutTotalOrder(String query) {
        if (query == null) {
            return false;
        }
        // LIMIT -1 with no OFFSET or OFFSET 0 takes every row: the wrappers end on it, and reading it as a limit
        // filed a reproducible mismatch as UNSTABLE_QUERY on 2026-10-06 and kept none of its data.
        String upper = NO_LIMIT.matcher(query.toUpperCase(Locale.ROOT)).replaceAll(" ");
        return upper.contains(" LIMIT ") && !upper.contains("ORDER BY");
    }

    private static final java.util.regex.Pattern NO_LIMIT = java.util.regex.Pattern
            .compile("\\sLIMIT\\s+-1(?:\\s+OFFSET\\s+0)?(?!\\s*OFFSET)(?=\\s|;|\\)|$)");

    private static int countRows(String output) {
        return output.isEmpty() ? 0 : output.split("\n", -1).length;
    }

    private static String firstLines(String output) {
        String[] lines = output.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            sb.append(i == 0 ? "" : " | ").append(lines[i]);
        }
        if (lines.length > 3) {
            sb.append(" | ... ").append(lines.length - 3).append(" more");
        }
        return sb.toString();
    }

    /**
     * Separates the query's output from the output of the context statements that had to run first. They share one
     * invocation because a database attached from memory does not outlive the process that attached it.
     */
    private static final String MARKER = "EGRAPH_REFEREE_OUTPUT_BEGINS";

    /** The query's output under the trunk build, or null when the build could not run it. */
    private static String run(Path database, String query, int mask, List<String> tempStatements)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(COMMAND.split("\\s+")));
        command.add(translatePath(database));
        Path script = Files.createTempFile("egraph-referee", ".sql");
        // To a file: a pipe nobody drains blocks the shell once context errors fill it.
        Path errors = Files.createTempFile("egraph-referee", ".err");
        try {
            StringBuilder sql = new StringBuilder();
            // The wrapper shapes reach for tables the context setup created, some of them in
            // databases attached from memory, which the copied file does not carry. Replaying the
            // context first is what lets this answer for those checks at all. Failures are left
            // alone: a statement that no longer applies to this database is not the question.
            int statementCount = 0;
            for (String statement : EGraphContextSnapshot.current()) {
                sql.append(stripTrailingSemicolon(statement)).append(';').append(System.lineSeparator());
                statementCount++;
            }
            for (String statement : tempStatements) {
                sql.append(stripTrailingSemicolon(statement)).append(';').append(System.lineSeparator());
                statementCount++;
            }
            if (mask != 0) {
                // Before the marker, so the function's own output is not taken for the query's.
                sql.append("SELECT sqlancer_optimizations(").append(Integer.toUnsignedString(mask)).append(");")
                        .append(System.lineSeparator());
            }
            sql.append("SELECT '").append(MARKER).append("';").append(System.lineSeparator());
            // The shell names the line an error came from, which tells the query's own error from a
            // context statement's (.bail on cannot: it quits at once if anything before it failed).
            // An error goes to stderr only, and an unseen one read as zero rows - that made a missing
            // TEMP table look FIXED_UPSTREAM.
            String queryLine = "near line " + (sql.chars().filter(c -> c == '\n').count() + 1) + ":";
            sql.append(stripTrailingSemicolon(query)).append(';').append(System.lineSeparator());
            Files.writeString(script, sql.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectInput(script.toFile());
            builder.redirectError(errors.toFile());
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                lastFailure = "timed out after " + TIMEOUT_SECONDS + "s with " + statementCount + " context statements";
                return null;
            }
            // The exit code reflects the last statement that failed, context statements included, so
            // it cannot say whether the query itself ran. The marker can: no marker means the script
            // did not get that far.
            int marker = output.lastIndexOf(MARKER);
            if (marker < 0) {
                lastFailure = "no marker in output; exit " + process.exitValue() + " after " + statementCount
                        + " context statements";
                return null;
            }
            for (String error : Files.readAllLines(errors, StandardCharsets.UTF_8)) {
                if (error.contains(queryLine)) {
                    lastFailure = "query failed: " + error.strip();
                    return null;
                }
            }
            int afterMarker = output.indexOf('\n', marker);
            return afterMarker < 0 ? "" : normalise(output.substring(afterMarker + 1));
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(errors);
        }
    }

    /**
     * A trunk build reached through WSL sees Windows paths as /mnt/&lt;drive&gt;/..., and the shell's own output ends
     * its lines differently from the release build's, so both sides are normalised the same way.
     */
    private static String translatePath(Path path) {
        String absolute = path.toAbsolutePath().toString();
        if (!COMMAND.toLowerCase(Locale.ROOT).contains("wsl")) {
            return absolute;
        }
        if (absolute.length() > 2 && absolute.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(absolute.charAt(0)) + absolute.substring(2).replace('\\', '/');
        }
        return absolute.replace('\\', '/');
    }

    private static String normalise(String output) {
        // Only trailing whitespace goes: a value that lost its leading space is exactly the kind of
        // difference this is here to see.
        List<String> lines = new ArrayList<>(List.of(output.replace("\r\n", "\n").split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines.stream().map(line -> line.replaceAll("\\s+$", ""))
                .collect(Collectors.toList()));
    }

    /** Why the last run() gave no answer, for the log line that follows it. */
    private static volatile String lastFailure = "";

    /**
     * Records a mismatch the referee could not judge. A count alone left no way to tell a timeout from a query the
     * trunk build refused, and in a four hour capture every one of 127 attempts came back unanswered.
     */
    private static void unanswered(String reason, String detail, String query) {
        String path = System.getProperty("egraph.referee.log");
        if (path == null || path.isBlank()) {
            return;
        }
        String entry = "-- EGRAPH_REFEREE UNANSWERED " + reason + System.lineSeparator() + "-- detail: "
                + firstLines(String.valueOf(detail)) + System.lineSeparator() + query + System.lineSeparator()
                + System.lineSeparator();
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The count still reaches the report.
        }
    }

    private static String sortLines(String output) {
        List<String> lines = new ArrayList<>(List.of(output.split("\n", -1)));
        lines.sort(null);
        return String.join("\n", lines);
    }

    private static String stripTrailingSemicolon(String query) {
        String trimmed = query.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }

    /** Lines for the end-of-run report, empty when the referee never answered. */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        if (FIXED.get() == 0 && LIVE.get() == 0 && UNSTABLE.get() == 0 && FAILED.get() == 0) {
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "  # %-40s %8d", "already fixed upstream", FIXED.get()));
        lines.add(String.format(Locale.ROOT, "  # %-40s %8d", "STILL LIVE upstream - look at these", LIVE.get()));
        if (UNSTABLE.get() > 0) {
            lines.add(String.format(Locale.ROOT, "  # %-40s %8d", "query does not pin its rows down (LIMIT)",
                    UNSTABLE.get()));
        }
        if (FAILED.get() > 0) {
            lines.add(String.format(Locale.ROOT, "  # %-40s %8d", "referee could not answer", FAILED.get()));
        }
        return lines;
    }
}
