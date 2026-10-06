package sqlancer.sqlite3.oracle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps what it takes to reproduce a mismatch, whatever it is later judged to be.
 *
 * <p>
 * A mismatch the referee or the known-bug signatures classify is logged as two queries and a row count and the check
 * moves on, so nothing of its data survived: the random tables are dropped and rebuilt after every corpus check. That
 * was safe only as long as the classification was right. On 2026-10-06 a wrapper's {@code LIMIT -1 OFFSET 0} (no limit)
 * was read as an unordered LIMIT, the mismatch was filed as UNSTABLE_QUERY, and it could be reproduced only because the
 * row it returned happened to have values unique enough to find in the statement log.
 * </p>
 *
 * <p>
 * Each bundle is what the referee replays: a copy of the database file, the context statements (some tables live in
 * databases attached from memory, which the file does not carry), then the original under the default optimizations
 * and the variant under the mask it ran with. {@code sqlite3 database.db < replay.sql} reproduces it with a shell that
 * has {@code sqlancer_optimizations()}. Written to {@code -Degraph.repro.dir}, at most
 * {@code -Degraph.repro.perShape} (3) per query shape and {@code -Degraph.repro.max} (300) in all, so a defect that
 * answers every check cannot fill the disk.
 * </p>
 */
public final class EGraphReproBundle {

    private static final String DIR = System.getProperty("egraph.repro.dir", "");
    private static final int PER_SHAPE = Integer.getInteger("egraph.repro.perShape", 3);
    private static final int MAX = Integer.getInteger("egraph.repro.max", 300);
    private static final AtomicInteger WRITTEN = new AtomicInteger();
    private static final Map<String, AtomicInteger> PER_SHAPE_COUNT = new ConcurrentHashMap<>();

    private EGraphReproBundle() {
    }

    /** Saves one bundle, unless switched off or past a limit. Never throws: the check's own reporting comes first. */
    public static void save(File databaseFile, String source, String rewriteQuery, String originalQuery,
            String variantQuery, int variantMask, List<String> originalRows, List<String> variantRows) {
        if (DIR.isBlank() || databaseFile == null || !databaseFile.isFile()) {
            return;
        }
        if (PER_SHAPE_COUNT.computeIfAbsent(shape(rewriteQuery), k -> new AtomicInteger()).incrementAndGet() > PER_SHAPE) {
            return;
        }
        int n = WRITTEN.incrementAndGet();
        if (n > MAX) {
            return;
        }
        try {
            Path dir = Path.of(DIR, String.format("%04d", n));
            Files.createDirectories(dir);
            Files.copy(databaseFile.toPath(), dir.resolve("database.db"), StandardCopyOption.REPLACE_EXISTING);
            String nl = System.lineSeparator();
            StringBuilder replay = new StringBuilder();
            for (String statement : EGraphContextSnapshot.current()) {
                replay.append(terminate(statement)).append(nl);
            }
            replay.append(".print == original (default optimizations)").append(nl);
            replay.append("SELECT sqlancer_optimizations(0);").append(nl);
            replay.append(terminate(originalQuery)).append(nl);
            replay.append(".print == variant (mask ").append(EGraphOptimizerMask.describe(variantMask)).append(")")
                    .append(nl);
            replay.append("SELECT sqlancer_optimizations(").append(Integer.toUnsignedString(variantMask)).append(");")
                    .append(nl);
            replay.append(terminate(variantQuery)).append(nl);
            replay.append("SELECT sqlancer_optimizations(0);").append(nl);
            Files.writeString(dir.resolve("replay.sql"), replay.toString(), StandardCharsets.UTF_8);
            StringBuilder caseText = new StringBuilder();
            caseText.append("source: ").append(source).append(nl);
            caseText.append("variant mask: ").append(EGraphOptimizerMask.describe(variantMask)).append(nl);
            caseText.append("original rows: ").append(originalRows.size()).append(", variant rows: ")
                    .append(variantRows.size()).append(nl);
            caseText.append("base query sent to the rewrite server:").append(nl).append(terminate(rewriteQuery))
                    .append(nl);
            caseText.append("original rows (first 10):").append(nl);
            originalRows.stream().limit(10).forEach(r -> caseText.append("  ").append(r).append(nl));
            caseText.append("variant rows (first 10):").append(nl);
            variantRows.stream().limit(10).forEach(r -> caseText.append("  ").append(r).append(nl));
            caseText.append("reproduce: sqlite3_trunk database.db < replay.sql").append(nl);
            Files.writeString(dir.resolve("case.txt"), caseText.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("[EGRAPH] could not save a reproduction bundle: " + e);
        }
    }

    /** The query with its literals blanked, so the same shape on other data counts once. */
    static String shape(String query) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("'(?:[^']|'')*'", "'?'").replaceAll("\\b\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\b", "?")
                .replaceAll("\\s+", " ").strip();
    }

    private static String terminate(String sql) {
        String s = sql == null ? "" : sql.strip();
        return s.endsWith(";") ? s : s + ";";
    }
}
