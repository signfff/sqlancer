package sqlancer.common.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.sqlite3.SQLite3OracleFactory.TruncationKind;

/** The yes/no form of the variant loop's comparison, which the attribution of a masked finding re-uses. */
public class TestEGraphSameOutcome {

    @Test
    public void sameOutcomeFollowsTheComparisonTheCheckMakes() {
        List<String> ab = List.of("a", "b");
        List<String> ba = List.of("b", "a");
        List<String> ac = List.of("a", "c");

        assertTrue(EGraphMetamorphicOracle.sameOutcome(TruncationKind.NONE, ab, ba), "order is not compared");
        assertFalse(EGraphMetamorphicOracle.sameOutcome(TruncationKind.NONE, ab, ac));
        assertTrue(EGraphMetamorphicOracle.sameOutcome(TruncationKind.TRAILING, ab, ac), "only the count is");
        assertTrue(EGraphMetamorphicOracle.sameOutcome(TruncationKind.NESTED, ab, List.of("x")), "nothing is");
        assertFalse(EGraphMetamorphicOracle.sameOutcome(TruncationKind.NESTED, ab, List.of()),
                "one side empty is a finding whatever the truncation");
    }
}
