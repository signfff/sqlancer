package sqlancer.sqlite3.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import sqlancer.SQLGlobalState;

/**
 * Runs the variants of a check with some of SQLite's optimizations turned off, so the two sides of a comparison take
 * different optimizer paths as well as different spellings.
 *
 * <p>
 * A metamorphic check cannot see a defect both spellings share: if the original and the variant run through the same
 * faulty optimization they are wrong the same way. Turning an optimization off on one side only is SQLite's version of
 * Differential Query Plans (Ba and Rigger, SIGMOD 2024); optimizations must not change a result, so any difference is
 * a defect in one of them. Measured on the official bug benchmark (2026-10-06), toggling alone exposed 5 live defects
 * of which e-graph rewriting found only 2, while rewriting found 7 that no single toggle exposes: the two cover
 * different defects, which is why both are kept and every finding is attributed.
 * </p>
 *
 * <p>
 * The switch is {@code sqlancer_optimizations(mask)}, a function build-trunk-jdbc.sh compiles into the native library
 * and the referee shell (JDBC cannot reach sqlite3_test_control). A set bit turns one optimization off; 0 restores the
 * default. Without the function, as with the library sqlite-jdbc ships, the mode quietly stays off.
 * </p>
 *
 * <p>
 * Modes, from {@code -Degraph.optMask}: {@code off} (default), {@code combined} (variants under a mask, the original
 * under the default) and {@code dqp} (the original against itself under a mask, no rewriting - the control arm).
 * {@code -Degraph.optMask.percent} (default 100) is the share of eligible checks that get a mask.
 * </p>
 */
public final class EGraphOptimizerMask {

    public enum Mode {
        OFF, COMBINED, DQP
    }

    /** Which side a finding needs, from re-running it with each factor alone. */
    public enum Attribution {
        /** The original query alone differs between the default and the mask: no rewriting needed. */
        OPTIMIZER,
        /** The variant differs from the original under the default optimizations: no mask needed. */
        EGRAPH,
        /** Neither alone: only the variant under the mask differs. */
        COMBINED,
        /** A re-run needed to tell failed, for instance on an expected error. */
        UNKNOWN
    }

    private static final Mode MODE = parseMode(System.getProperty("egraph.optMask", "off"));
    private static final int PERCENT = Integer.getInteger("egraph.optMask.percent", 100);
    private static final String LOG = System.getProperty("egraph.optMask.log", "");

    /** Trunk's optimization bits (sqliteInt.h); 0x8 and 0x100000 are unused. */
    static final String[] NAMES = { "QueryFlattener", "WindowFunc", "GroupByOrder", null, "DistinctOpt",
            "CoverIdxScan", "OrderByIdxJoin", "Transitive", "OmitNoopJoin", "CountOfView", "CursorHints", "Stat4",
            "PushDown", "SimplifyJoin", "SkipScan", "PropagateConst", "MinMaxOpt", "SeekScan", "OmitOrderBy",
            "BloomFilter", null, "BalancedMerge", "ReleaseReg", "FlttnUnionAll", "IndexedExpr", "Coroutines",
            "NullUnusedCols", "OnePass", "OrderBySubq", "StarQuery", "ExistsToJoin", "UnionLimit" };

    /**
     * Results a plan is free to change: an aggregate that concatenates rows in visiting order, and a window function
     * whose frame follows that order. A mask changes the plan of the whole statement, wrapper probes included, where a
     * rewrite only changes the WHERE it rewrote - so text both sides share is no longer safe here.
     */
    private static final Pattern ORDER_SENSITIVE = Pattern.compile(
            "\\b(group_concat|string_agg|json_group_array|json_group_object|jsonb_group_array|jsonb_group_object)\\s*\\("
                    + "|\\bover\\s*\\(|\\bover\\s+[a-z_]",
            Pattern.CASE_INSENSITIVE);

    private static final AtomicLong MASKED = new AtomicLong();
    private static final AtomicLong UNSAFE = new AtomicLong();
    private static final AtomicLong UNAVAILABLE = new AtomicLong();

    private Boolean available;
    private int applied;

    public static Mode mode() {
        return MODE;
    }

    static Mode parseMode(String value) {
        switch (value.strip().toLowerCase(Locale.ROOT)) {
        case "combined":
            return Mode.COMBINED;
        case "dqp":
            return Mode.DQP;
        default:
            return Mode.OFF;
        }
    }

    /**
     * The mask for this check, or 0 for none: when the mode is off, the function is missing, the check is outside the
     * configured share, or the query's result depends on visiting order.
     */
    public int choose(SQLGlobalState<?, ?> state, String originalQuery) {
        if (MODE == Mode.OFF) {
            return 0;
        }
        if (!isAvailable(state)) {
            UNAVAILABLE.incrementAndGet();
            return 0;
        }
        if (isOrderSensitive(originalQuery)) {
            UNSAFE.incrementAndGet();
            return 0;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(100) >= PERCENT) {
            return 0;
        }
        MASKED.incrementAndGet();
        return randomMask(random);
    }

    private static final Pattern GROUP_BY = Pattern.compile("\\bgroup\\s+by\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_MAX = Pattern.compile("\\b(min|max)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * Whether the query's result depends on which row the plan visits first, which a mask is free to change.
     *
     * <p>
     * Besides order-sensitive aggregates: a bare column beside min()/max() takes its value from one of the rows that
     * tie for the extreme, any one of them (measured 2026-10-06 on trunk: two rows tied on max(c2)=x'', the index
     * plan took the row with c1=x'db34', the full scan the one with c1=NULL, and {@code (c1, c2) IN (SELECT c1,
     * max(c2) ...)} went from 1 row to 0); and under GROUP BY every bare column is some row of its group. A rewrite
     * never re-planned such a subquery - the server refuses to touch it - but a mask re-plans the whole statement.
     * </p>
     */
    static boolean isOrderSensitive(String query) {
        if (query == null) {
            return false;
        }
        return ORDER_SENSITIVE.matcher(query).find() || GROUP_BY.matcher(query).find() || hasBareColumnBesideMinMax(query);
    }

    private static final Pattern AGGREGATE = Pattern.compile(
            "^\\s*(count|sum|total|avg|min|max|group_concat|string_agg|json_group_array|json_group_object)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /**
     * Whether some SELECT list calls min()/max() next to a bare column - an item that is neither an aggregate nor a
     * constant. Read from the text with string literals blanked; coarse on purpose (a scalar min(a, b) next to a column
     * counts too), which only ever costs a mask.
     */
    static boolean hasBareColumnBesideMinMax(String query) {
        String text = STRING_LITERAL.matcher(query.toLowerCase(Locale.ROOT)).replaceAll("''");
        int from = 0;
        while (true) {
            int select = indexOfWord(text, "select", from);
            if (select < 0) {
                return false;
            }
            List<String> items = new ArrayList<>();
            int depth = 0;
            int itemStart = select + 6;
            int end = text.length();
            for (int i = select + 6; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                    depth--;
                } else if (depth == 0 && c == ',') {
                    items.add(text.substring(itemStart, i));
                    itemStart = i + 1;
                } else if (depth == 0 && text.startsWith("from", i) && isWordAt(text, i, 4)) {
                    end = i;
                    break;
                }
            }
            items.add(text.substring(itemStart, end));
            boolean minMax = items.stream().anyMatch(item -> MIN_MAX.matcher(item).find());
            boolean bare = items.stream().anyMatch(item -> !AGGREGATE.matcher(item.replaceFirst("^\\s*(all|distinct)\\b", ""))
                    .find() && item.replaceAll("''", "").chars().anyMatch(Character::isLetter));
            if (minMax && bare) {
                return true;
            }
            from = select + 6;
        }
    }

    private static int indexOfWord(String text, String word, int from) {
        int at = text.indexOf(word, from);
        while (at >= 0 && !isWordAt(text, at, word.length())) {
            at = text.indexOf(word, at + 1);
        }
        return at;
    }

    private static boolean isWordAt(String text, int at, int length) {
        boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)) && text.charAt(at - 1) != '_';
        int after = at + length;
        boolean afterOk = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after))
                && text.charAt(after) != '_';
        return before && afterOk;
    }

    /** One to three optimizations, or now and then all of them. */
    static int randomMask(ThreadLocalRandom random) {
        if (random.nextInt(10) == 0) {
            return 0xFFFFFFFF;
        }
        int mask = 0;
        int bits = 1 + random.nextInt(3);
        while (Integer.bitCount(mask) < bits) {
            int bit = random.nextInt(NAMES.length);
            if (NAMES[bit] != null) {
                mask |= 1 << bit;
            }
        }
        return mask;
    }

    /** Sets the connection's optimizer flags, skipping the round trip when they are already set. */
    public void apply(SQLGlobalState<?, ?> state, int mask) throws SQLException {
        if (mask == applied) {
            return;
        }
        try (Statement s = state.getConnection().createStatement()) {
            s.executeQuery("SELECT sqlancer_optimizations(" + Integer.toUnsignedString(mask) + ")").close();
        }
        applied = mask;
    }

    /** Back to the default; a failure here would leave every later check masked, so it is not swallowed. */
    public void reset(SQLGlobalState<?, ?> state) throws SQLException {
        apply(state, 0);
    }

    private boolean isAvailable(SQLGlobalState<?, ?> state) {
        if (available == null) {
            try (Statement s = state.getConnection().createStatement()) {
                s.executeQuery("SELECT sqlancer_optimizations(0)").close();
                available = true;
            } catch (SQLException e) {
                available = false;
                System.err.println("[EGRAPH] -Degraph.optMask=" + MODE.name().toLowerCase(Locale.ROOT)
                        + " needs sqlancer_optimizations() from build-trunk-jdbc.sh; running without masks: "
                        + e.getMessage());
            }
        }
        return available;
    }

    public static String describe(int mask) {
        if (mask == 0) {
            return "0x0 (default)";
        }
        if (mask == 0xFFFFFFFF) {
            return "0xffffffff (all off)";
        }
        List<String> off = new ArrayList<>();
        for (int bit = 0; bit < NAMES.length; bit++) {
            if ((mask & (1 << bit)) != 0) {
                off.add(NAMES[bit] == null ? "bit" + bit : NAMES[bit]);
            }
        }
        return String.format("0x%x (%s off)", mask, String.join(",", off));
    }

    /** One line per finding under a mask, to the file named by egraph.optMask.log. */
    public static void recordFinding(Attribution attribution, int mask, String originalQuery, String variantQuery,
            int originalRows, int originalMaskedRows, int variantDefaultRows, int variantMaskedRows) {
        if (LOG.isBlank()) {
            return;
        }
        String nl = System.lineSeparator();
        String entry = "-- EGRAPH_OPTMASK " + attribution + " mask=" + describe(mask) + nl + "-- rows: original "
                + originalRows + ", original+mask " + originalMaskedRows + ", variant " + variantDefaultRows
                + ", variant+mask " + variantMaskedRows + nl + originalQuery + ";" + nl + variantQuery + ";" + nl + nl;
        synchronized (EGraphOptimizerMask.class) {
            try {
                Path file = Path.of(LOG);
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The finding itself still goes through the usual reporting.
            }
        }
    }

    public static String summary() {
        return String.format("optMask mode=%s masked=%d orderSensitiveSkipped=%d unavailable=%d",
                MODE.name().toLowerCase(Locale.ROOT), MASKED.get(), UNSAFE.get(), UNAVAILABLE.get());
    }
}
