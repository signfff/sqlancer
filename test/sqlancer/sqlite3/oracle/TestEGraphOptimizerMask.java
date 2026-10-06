package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

public class TestEGraphOptimizerMask {

    @Test
    public void modesParseAndAnythingElseIsOff() {
        assertEquals(EGraphOptimizerMask.Mode.COMBINED, EGraphOptimizerMask.parseMode(" Combined "));
        assertEquals(EGraphOptimizerMask.Mode.DQP, EGraphOptimizerMask.parseMode("dqp"));
        assertEquals(EGraphOptimizerMask.Mode.OFF, EGraphOptimizerMask.parseMode("off"));
        assertEquals(EGraphOptimizerMask.Mode.OFF, EGraphOptimizerMask.parseMode("yes"));
    }

    @Test
    public void resultsThatFollowVisitingOrderAreNotMasked() {
        // Measured on trunk: the same rows, with all optimizations off, came out of group_concat in another order.
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT group_concat(DISTINCT c1) FROM t0 WHERE c0 > 3"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT q.*, (SELECT json_group_array(value) FROM p) FROM q"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT row_number() OVER (ORDER BY c0) FROM t0"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT sum(c0) over w FROM t0 WINDOW w AS (ORDER BY c1)"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT STRING_AGG(c1, ',') FROM t0"));

        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT * FROM t0 WHERE c0 IN (SELECT c0 FROM t1)"));
        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT count(*), sum(c0), max(c1) FROM t0"));
        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT over_x FROM t0 WHERE overall > 1"));
    }

    @Test
    public void aBareColumnBesideMinOrMaxIsNotMasked() {
        // The 2026-10-06 trunk run: tied on max(c2), the index plan and the full scan took different bare c1 values.
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT * FROM (SELECT ALL t0.c2, t0.c1, t0.c0 FROM t0 WHERE"
                + " (((t0.c1, t0.c2) IN (SELECT egraph_sub.\"c1\", max(egraph_sub.\"c2\") FROM \"t0\" AS egraph_sub))))"
                + " AS egraph_limit LIMIT -1 OFFSET 0"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT c1, MIN(c2) FROM t0"));
        assertTrue(EGraphOptimizerMask.isOrderSensitive("SELECT c0, count(*) FROM t0 GROUP BY c1"));

        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT max(c2) FROM t0"));
        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT c0, c1 FROM t0 WHERE c2 = (SELECT max(c2) FROM t0)"));
        assertFalse(EGraphOptimizerMask.isOrderSensitive("SELECT c0, 'max(' FROM t0"), "inside a string literal");
    }

    @Test
    public void randomMasksUseOnlyRealOptimizationBits() {
        int unused = (1 << 3) | (1 << 20);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean sawAll = false;
        for (int i = 0; i < 5000; i++) {
            int mask = EGraphOptimizerMask.randomMask(random);
            if (mask == 0xFFFFFFFF) {
                sawAll = true;
                continue;
            }
            assertEquals(0, mask & unused, Integer.toHexString(mask));
            int bits = Integer.bitCount(mask);
            assertTrue(bits >= 1 && bits <= 3, Integer.toHexString(mask));
        }
        assertTrue(sawAll, "now and then every optimization is turned off");
    }

    @Test
    public void masksAreDescribedByName() {
        assertEquals("0x0 (default)", EGraphOptimizerMask.describe(0));
        assertEquals("0xffffffff (all off)", EGraphOptimizerMask.describe(0xFFFFFFFF));
        assertEquals("0x9000 (PushDown,PropagateConst off)", EGraphOptimizerMask.describe(0x1000 | 0x8000));
    }
}
