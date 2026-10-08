package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.sqlite3.ast.SQLite3Constant;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation.BinaryComparisonOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3ColumnName;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation.BinaryOperator;
import sqlancer.sqlite3.ast.SQLite3UnaryOperation;
import sqlancer.sqlite3.ast.SQLite3UnaryOperation.UnaryOperator;
import sqlancer.sqlite3.schema.SQLite3DataType;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;

public class TestEGraphPredicateFilter {

    private static final SQLite3Column COLUMN = new SQLite3Column("c0", SQLite3DataType.INT, false, false, null);

    @Test
    public void testAcceptsSupportedColumnComparison() {
        SQLite3Expression expr = new BinaryComparisonOperation(column(), SQLite3Constant.createIntConstant(1),
                BinaryComparisonOperator.EQUALS);

        assertTrue(EGraphPredicateFilter.isEgraphCompatible(expr));
    }

    @Test
    public void testAcceptsConcatenationInSubtree() {
        // The rewrite server has a concat node now; || used to be rejected here.
        SQLite3Expression concat = new Sqlite3BinaryOperation(column(), SQLite3Constant.createTextConstant("x"),
                BinaryOperator.CONCATENATE);
        SQLite3Expression expr = new BinaryComparisonOperation(concat, SQLite3Constant.createTextConstant("1"),
                BinaryComparisonOperator.EQUALS);

        assertTrue(EGraphPredicateFilter.isEgraphCompatible(expr));
    }

    @Test
    public void testAcceptsBitwiseNegationInSubtree() {
        // The rewrite server has a bitnot node now; ~ used to be rejected here.
        SQLite3Expression bitwiseNegation = new SQLite3UnaryOperation(UnaryOperator.NEGATE, column());
        SQLite3Expression expr = new BinaryComparisonOperation(bitwiseNegation, SQLite3Constant.createIntConstant(1),
                BinaryComparisonOperator.EQUALS);

        assertTrue(EGraphPredicateFilter.isEgraphCompatible(expr));
    }

    @Test
    public void testAcceptsSatisfiableComparison() {
        SQLite3Expression expr = new BinaryComparisonOperation(column(), SQLite3Constant.createIntConstant(1),
                BinaryComparisonOperator.SMALLER);

        assertTrue(EGraphPredicateFilter.isPotentiallySatisfiable(expr));
    }

    @Test
    public void testRejectsColumnComparedWithItself() {
        SQLite3Expression expr = new BinaryComparisonOperation(column(), column(), BinaryComparisonOperator.SMALLER);

        assertFalse(EGraphPredicateFilter.isPotentiallySatisfiable(expr));
    }

    @Test
    public void testRejectsSelfSubtractionAsPredicate() {
        // "WHERE (c0)-(c0)" is always 0, i.e. always false
        SQLite3Expression expr = new Sqlite3BinaryOperation(column(), column(), BinaryOperator.MINUS);

        assertFalse(EGraphPredicateFilter.isPotentiallySatisfiable(expr));
    }

    @Test
    public void testRejectsComparisonAgainstNullLiteral() {
        SQLite3Expression expr = new BinaryComparisonOperation(column(), SQLite3Constant.createNullConstant(),
                BinaryComparisonOperator.EQUALS);

        assertFalse(EGraphPredicateFilter.isPotentiallySatisfiable(expr));
    }

    private static SQLite3ColumnName column() {
        return new SQLite3ColumnName(COLUMN, null);
    }
}
