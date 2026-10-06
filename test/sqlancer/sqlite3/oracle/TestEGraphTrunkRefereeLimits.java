package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestEGraphTrunkRefereeLimits {

    @Test
    public void limitMinusOneTakesEveryRow() {
        // The wrapper the 2026-10-06 mismatch was filed as UNSTABLE_QUERY for.
        assertFalse(EGraphTrunkReferee.limitsWithoutTotalOrder(
                "SELECT * FROM (SELECT ALL t0.c2 FROM t0 WHERE c0 > 1) AS egraph_limit LIMIT -1 OFFSET 0"));
        assertFalse(EGraphTrunkReferee.limitsWithoutTotalOrder("SELECT * FROM t0 LIMIT -1"));
        assertFalse(EGraphTrunkReferee.limitsWithoutTotalOrder("SELECT * FROM t0 LIMIT -1;"));
    }

    @Test
    public void aRealLimitWithoutOrderStillIsUnstable() {
        assertTrue(EGraphTrunkReferee.limitsWithoutTotalOrder("SELECT * FROM t0 LIMIT 10"));
        assertTrue(EGraphTrunkReferee.limitsWithoutTotalOrder("SELECT * FROM t0 LIMIT -1 OFFSET 5"),
                "an offset skips rows");
        assertTrue(EGraphTrunkReferee.limitsWithoutTotalOrder(
                "SELECT * FROM (SELECT * FROM t0 LIMIT 3) AS q LIMIT -1 OFFSET 0"), "the inner limit still counts");
        assertFalse(EGraphTrunkReferee.limitsWithoutTotalOrder("SELECT * FROM t0 ORDER BY c0 LIMIT 10"));
    }

    @Test
    public void bundlesCountAQueryShapeOnceWhateverItsLiterals() {
        assertEquals(EGraphReproBundle.shape("SELECT * FROM t0 WHERE c0 = 'a''b' AND c1 > 1.5e3"),
                EGraphReproBundle.shape("SELECT * FROM t0 WHERE c0 = 'zz'   AND c1 > 7"));
    }
}
