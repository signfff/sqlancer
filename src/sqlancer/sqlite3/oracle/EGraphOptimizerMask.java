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

    static boolean isOrderSensitive(String query) {
        return query != null && ORDER_SENSITIVE.matcher(query).find();
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
