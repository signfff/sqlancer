package sqlancer.sqlite3.oracle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.sqlite3.ast.SQLite3Case;
import sqlancer.sqlite3.ast.SQLite3Constant;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Expression.BetweenOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation.BinaryComparisonOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.Cast;
import sqlancer.sqlite3.ast.SQLite3Expression.CollateOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Function;
import sqlancer.sqlite3.ast.SQLite3Expression.InOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3ColumnName;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3PostfixUnaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation;
import sqlancer.sqlite3.ast.SQLite3RowValueExpression;
import sqlancer.sqlite3.ast.SQLite3UnaryOperation;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;

/**
 * Static analysis of a generated WHERE clause, run before the EGRAPH oracle sends anything to the e-graph server.
 *
 * <p>
 * Two independent filters:
 * </p>
 * <ul>
 * <li>{@link #isPotentiallySatisfiable} - four-valued reasoning that rejects clauses that can never be TRUE
 * ({@code (c0)-(c0)}, {@code (c0)<(c0)}, any comparison against NULL). An empty original result makes the metamorphic
 * comparison vacuous, so those are discarded before the probe query runs.</li>
 * <li>{@link #isEgraphCompatible} - rejects clauses that reference no column at all or nest deeper than
 * MAX_EXPR_DEPTH.</li>
 * </ul>
 */
public class EGraphPredicateFilter {

    private static final int MAX_EXPR_DEPTH = 25;
    private static final Pattern LEADING_NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");


    /**
     * Three-valued verdict on whether a WHERE clause can ever evaluate to TRUE.
     *
     * <p>
     * Measured on a 45 s run, 43% of generated base queries matched no row, and 65% of those were structurally
     * unsatisfiable rather than badly seeded: {@code (c0)-(c0)} is always 0, {@code (c0)<(c0)} is always false, and any
     * comparison against a NULL literal is always NULL. No amount of generated data makes those return a row, and an
     * empty original result makes the metamorphic comparison vacuous, so they are rejected before the data setup and
     * the probe run.
     * </p>
     *
     * <p>
     * ALWAYS_FALSE and ALWAYS_NULL are kept apart because NOT distinguishes them: {@code NOT (c0 < c0)} is TRUE for a
     * non-NULL column, while {@code NOT (c0 = NULL)} stays NULL. Collapsing them would reject satisfiable clauses.
     * </p>
     */
    private enum Truth {
        /** Definitely true for any data, which makes NOT of it definitely false. */
        ALWAYS_TRUE, POSSIBLY_TRUE, ALWAYS_FALSE, ALWAYS_NULL
    }

    private static boolean canBeTrue(Truth truth) {
        return truth == Truth.ALWAYS_TRUE || truth == Truth.POSSIBLY_TRUE;
    }

    /**
     * Nodes that yield 0, 1 or NULL rather than an arbitrary value. Their outcome domain is what lets a comparison
     * between two of them be decided statically, and they carry no column affinity.
     */
    private static boolean isBooleanProducing(SQLite3Expression expr) {
        if (expr instanceof BinaryComparisonOperation || expr instanceof SQLite3PostfixUnaryOperation
                || expr instanceof BetweenOperation) {
            return true;
        }
        if (expr instanceof SQLite3UnaryOperation) {
            return ((SQLite3UnaryOperation) expr).getOperation() == SQLite3UnaryOperation.UnaryOperator.NOT;
        }
        if (expr instanceof Sqlite3BinaryOperation) {
            Sqlite3BinaryOperation.BinaryOperator op = ((Sqlite3BinaryOperation) expr).getOperator();
            return op == Sqlite3BinaryOperation.BinaryOperator.AND
                    || op == Sqlite3BinaryOperation.BinaryOperator.OR;
        }
        return false;
    }

    /** The possible outcomes of a boolean-producing node whose truth value is already known. */
    private enum BoolDomain {
        ONE_OR_NULL, ZERO_OR_NULL, UNKNOWN
    }

    private static BoolDomain boolDomain(SQLite3Expression expr, int depth) {
        if (!isBooleanProducing(expr)) {
            return BoolDomain.UNKNOWN;
        }
        switch (analyzeTruth(expr, depth)) {
            case ALWAYS_TRUE:
                return BoolDomain.ONE_OR_NULL;
            case ALWAYS_FALSE:
            case ALWAYS_NULL:
                return BoolDomain.ZERO_OR_NULL;
            default:
                return BoolDomain.UNKNOWN;
        }
    }

    /**
     * Decides a comparison between two boolean-producing nodes with known outcome domains. NULL on either side yields
     * NULL, which is not true, so only the concrete pairs need checking.
     */
    private static Truth compareDomains(BoolDomain left, BinaryComparisonOperator op, BoolDomain right) {
        if (left == BoolDomain.UNKNOWN || right == BoolDomain.UNKNOWN) {
            return Truth.POSSIBLY_TRUE;
        }
        int leftValue = left == BoolDomain.ONE_OR_NULL ? 1 : 0;
        int rightValue = right == BoolDomain.ONE_OR_NULL ? 1 : 0;
        boolean holds;
        switch (op) {
            case SMALLER:
                holds = leftValue < rightValue;
                break;
            case SMALLER_EQUALS:
                holds = leftValue <= rightValue;
                break;
            case GREATER:
                holds = leftValue > rightValue;
                break;
            case GREATER_EQUALS:
                holds = leftValue >= rightValue;
                break;
            case EQUALS:
                holds = leftValue == rightValue;
                break;
            case NOT_EQUALS:
                holds = leftValue != rightValue;
                break;
            default:
                return Truth.POSSIBLY_TRUE;
        }
        // The concrete pair may still be NULL on either side, so a holding
        // comparison is only "possibly" true, while a failing one can never be true.
        return holds ? Truth.POSSIBLY_TRUE : Truth.ALWAYS_FALSE;
    }

    // Escape hatch for A/B measurement and for the case where the analysis ever
    // rejects something it should not.
    private static final boolean SATISFIABILITY_FILTER = !"false"
            .equalsIgnoreCase(System.getProperty("sqlite3.egraph.satisfiabilityFilter", "true"));

    /** Returns false only when the clause provably never evaluates to TRUE, whatever the data. */
    public static boolean isPotentiallySatisfiable(SQLite3Expression expr) {
        if (!SATISFIABILITY_FILTER) {
            return true;
        }
        if (expr == null) {
            return false;
        }
        return canBeTrue(analyzeTruth(expr, 0));
    }

    private static Truth analyzeTruth(SQLite3Expression expr, int depth) {
        if (expr == null || depth > MAX_EXPR_DEPTH) {
            return Truth.POSSIBLY_TRUE; // unknown shape: never reject on ignorance
        }
        if (expr instanceof SQLite3Constant) {
            return constantTruth((SQLite3Constant) expr);
        }
        if (expr instanceof BinaryComparisonOperation) {
            return comparisonTruth((BinaryComparisonOperation) expr, depth);
        }
        if (expr instanceof Sqlite3BinaryOperation) {
            return binaryTruth((Sqlite3BinaryOperation) expr, depth);
        }
        if (expr instanceof SQLite3UnaryOperation) {
            SQLite3UnaryOperation unary = (SQLite3UnaryOperation) expr;
            Truth inner = analyzeTruth(unary.getExpression(), depth + 1);
            switch (unary.getOperation()) {
                case NOT:
                    // NOT flips both definite outcomes; NULL stays NULL.
                    switch (inner) {
                        case ALWAYS_FALSE:
                            return Truth.ALWAYS_TRUE;
                        case ALWAYS_TRUE:
                            return Truth.ALWAYS_FALSE;
                        case ALWAYS_NULL:
                            return Truth.ALWAYS_NULL;
                        default:
                            return Truth.POSSIBLY_TRUE;
                    }
                case MINUS:
                case PLUS:
                    // Sign changes neither zero-ness nor NULL-ness, but a definite
                    // non-zero value stays definitely true only in magnitude terms.
                    return inner == Truth.ALWAYS_NULL ? Truth.ALWAYS_NULL
                            : inner == Truth.ALWAYS_FALSE ? Truth.ALWAYS_FALSE : Truth.POSSIBLY_TRUE;
                default:
                    return Truth.POSSIBLY_TRUE; // ~0 is -1, which is true
            }
        }
        if (expr instanceof SQLite3PostfixUnaryOperation) {
            SQLite3PostfixUnaryOperation post = (SQLite3PostfixUnaryOperation) expr;
            if (analyzeTruth(post.getExpression(), depth + 1) == Truth.ALWAYS_NULL) {
                // Only ISNULL holds for a value that is always NULL.
                String name = post.getOperation().toString().toUpperCase(java.util.Locale.ROOT);
                return name.contains("ISNULL") || name.contains("IS_NULL") ? Truth.ALWAYS_TRUE : Truth.ALWAYS_FALSE;
            }
            return Truth.POSSIBLY_TRUE;
        }
        if (expr instanceof BetweenOperation) {
            BetweenOperation between = (BetweenOperation) expr;
            if (analyzeTruth(between.getExpression(), depth + 1) == Truth.ALWAYS_NULL) {
                return Truth.ALWAYS_NULL; // NULL BETWEEN ... is NULL
            }
            return Truth.POSSIBLY_TRUE;
        }
        return Truth.POSSIBLY_TRUE;
    }

    private static Truth constantTruth(SQLite3Constant c) {
        if (c.isNull()) {
            return Truth.ALWAYS_NULL;
        }
        Double numeric = numericValueOf(c);
        if (numeric == null) {
            return Truth.POSSIBLY_TRUE;
        }
        return numeric == 0.0 ? Truth.ALWAYS_FALSE : Truth.ALWAYS_TRUE;
    }

    private static Truth comparisonTruth(BinaryComparisonOperation cmp, int depth) {
        BinaryComparisonOperator op = cmp.getOperator();
        boolean nullAware = op == BinaryComparisonOperator.IS || op == BinaryComparisonOperator.IS_NOT;
        if (!nullAware) {
            // Any comparison with a NULL operand yields NULL - not only against a
            // literal NULL, but also against a subexpression that is always NULL.
            if (isNullConstant(cmp.getLeft()) || isNullConstant(cmp.getRight())
                    || analyzeTruth(cmp.getLeft(), depth + 1) == Truth.ALWAYS_NULL
                    || analyzeTruth(cmp.getRight(), depth + 1) == Truth.ALWAYS_NULL) {
                return Truth.ALWAYS_NULL;
            }
            Truth domainVerdict = compareDomains(boolDomain(cmp.getLeft(), depth + 1), op,
                    boolDomain(cmp.getRight(), depth + 1));
            if (domainVerdict == Truth.ALWAYS_FALSE) {
                return Truth.ALWAYS_FALSE;
            }
        }
        String left = canonical(cmp.getLeft(), 0);
        String right = canonical(cmp.getRight(), 0);
        if (left == null || right == null || !left.equals(right)) {
            return Truth.POSSIBLY_TRUE;
        }
        // Both sides are the same expression, so the outcome depends only on the operator.
        switch (op) {
            case SMALLER:
            case GREATER:
            case NOT_EQUALS:
            case IS_NOT:
                return Truth.ALWAYS_FALSE;
            case IS:
                return Truth.ALWAYS_TRUE; // X IS X holds even for NULL
            default:
                return Truth.POSSIBLY_TRUE; // =, <=, >=, LIKE, GLOB hold for a non-NULL value
        }
    }

    private static Truth binaryTruth(Sqlite3BinaryOperation bin, int depth) {
        Sqlite3BinaryOperation.BinaryOperator op = bin.getOperator();
        Truth left = analyzeTruth(bin.getLeft(), depth + 1);
        Truth right = analyzeTruth(bin.getRight(), depth + 1);
        switch (op) {
            case AND:
                if (left == Truth.ALWAYS_TRUE && right == Truth.ALWAYS_TRUE) {
                    return Truth.ALWAYS_TRUE;
                }
                if (left == Truth.ALWAYS_FALSE || right == Truth.ALWAYS_FALSE) {
                    return Truth.ALWAYS_FALSE; // false AND anything is false, even NULL
                }
                if (!canBeTrue(left) || !canBeTrue(right)) {
                    return Truth.ALWAYS_NULL;
                }
                return Truth.POSSIBLY_TRUE;
            case OR:
                if (left == Truth.ALWAYS_TRUE || right == Truth.ALWAYS_TRUE) {
                    return Truth.ALWAYS_TRUE; // true OR anything is true, even NULL
                }
                if (canBeTrue(left) || canBeTrue(right)) {
                    return Truth.POSSIBLY_TRUE;
                }
                return left == Truth.ALWAYS_NULL || right == Truth.ALWAYS_NULL ? Truth.ALWAYS_NULL
                        : Truth.ALWAYS_FALSE;
            default:
                break;
        }
        // Arithmetic and bitwise operators propagate NULL: any operand that is
        // always NULL makes the whole value NULL, which is never true.
        if (left == Truth.ALWAYS_NULL || right == Truth.ALWAYS_NULL) {
            return Truth.ALWAYS_NULL;
        }
        switch (op) {
            case MULTIPLY:
            case DIVIDE:
                // 0 * x and 0 / x are 0; x / 0 is NULL. An operand that can never be
                // true is either 0 or NULL, so the result can never be true either.
                if (!canBeTrue(left) || !canBeTrue(right)) {
                    return Truth.ALWAYS_FALSE;
                }
                return isProvablyZero(bin, 0) ? Truth.ALWAYS_FALSE : Truth.POSSIBLY_TRUE;
            case ARITHMETIC_AND:
                // 0 & x is 0.
                if (!canBeTrue(left) || !canBeTrue(right)) {
                    return Truth.ALWAYS_FALSE;
                }
                return Truth.POSSIBLY_TRUE;
            case PLUS:
            case ARITHMETIC_OR:
            case CONCATENATE:
                // Only zero on both sides keeps the result at zero.
                if (left == Truth.ALWAYS_FALSE && right == Truth.ALWAYS_FALSE) {
                    return Truth.ALWAYS_FALSE;
                }
                return Truth.POSSIBLY_TRUE;
            case MINUS:
            case REMAINDER:
                // x - x is 0; x % x is 0 or NULL.
                if (left == Truth.ALWAYS_FALSE && right == Truth.ALWAYS_FALSE) {
                    return Truth.ALWAYS_FALSE;
                }
                return isProvablyZero(bin, 0) ? Truth.ALWAYS_FALSE : Truth.POSSIBLY_TRUE;
            default:
                return Truth.POSSIBLY_TRUE;
        }
    }

    /** True when the expression's numeric value is 0 for every possible input. */
    private static boolean isProvablyZero(SQLite3Expression expr, int depth) {
        if (expr == null || depth > MAX_EXPR_DEPTH) {
            return false;
        }
        if (expr instanceof SQLite3Constant) {
            SQLite3Constant c = (SQLite3Constant) expr;
            if (c.isNull()) {
                return false;
            }
            Double numeric = numericValueOf(c);
            return numeric != null && numeric == 0.0;
        }
        if (expr instanceof SQLite3UnaryOperation) {
            SQLite3UnaryOperation unary = (SQLite3UnaryOperation) expr;
            switch (unary.getOperation()) {
                case MINUS:
                case PLUS:
                    return isProvablyZero(unary.getExpression(), depth + 1);
                default:
                    return false;
            }
        }
        if (expr instanceof Sqlite3BinaryOperation) {
            Sqlite3BinaryOperation bin = (Sqlite3BinaryOperation) expr;
            switch (bin.getOperator()) {
                case MINUS:
                case REMAINDER: {
                    String left = canonical(bin.getLeft(), 0);
                    String right = canonical(bin.getRight(), 0);
                    return left != null && left.equals(right);
                }
                case MULTIPLY:
                    return isProvablyZero(bin.getLeft(), depth + 1) || isProvablyZero(bin.getRight(), depth + 1);
                default:
                    return false;
            }
        }
        return false;
    }

    private static boolean isNullConstant(SQLite3Expression expr) {
        return expr instanceof SQLite3Constant && ((SQLite3Constant) expr).isNull();
    }

    /** Numeric value of a constant under SQLite's truthiness rules, or null when it cannot be determined. */
    private static Double numericValueOf(SQLite3Constant c) {
        try {
            switch (c.getDataType()) {
                case INT:
                    return (double) c.asInt();
                case REAL:
                    return c.asDouble();
                case BINARY:
                    return 0.0; // a blob converts to 0 in a numeric context
                case TEXT: {
                    String text = c.asString();
                    if (text == null) {
                        return null;
                    }
                    Matcher matcher = LEADING_NUMBER.matcher(text.trim());
                    if (!matcher.find()) {
                        return 0.0; // text with no numeric prefix converts to 0
                    }
                    return Double.parseDouble(matcher.group());
                }
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Canonical text for the subtrees the satisfiability analysis needs to compare. Returns null for node kinds it
     * cannot render, which makes the caller treat the two sides as possibly different rather than identical.
     */
    private static String canonical(SQLite3Expression expr, int depth) {
        if (expr == null || depth > MAX_EXPR_DEPTH) {
            return null;
        }
        if (expr instanceof SQLite3ColumnName) {
            SQLite3Column col = ((SQLite3ColumnName) expr).getColumn();
            return col == null ? null : "col:" + col.getFullQualifiedName();
        }
        if (expr instanceof SQLite3Constant) {
            SQLite3Constant c = (SQLite3Constant) expr;
            if (c.isNull()) {
                return "const:null";
            }
            try {
                return "const:" + c.getDataType() + ":" + c.toString();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (expr instanceof SQLite3UnaryOperation) {
            SQLite3UnaryOperation unary = (SQLite3UnaryOperation) expr;
            String inner = canonical(unary.getExpression(), depth + 1);
            return inner == null ? null : "un:" + unary.getOperation() + "(" + inner + ")";
        }
        if (expr instanceof SQLite3PostfixUnaryOperation) {
            SQLite3PostfixUnaryOperation post = (SQLite3PostfixUnaryOperation) expr;
            String inner = canonical(post.getExpression(), depth + 1);
            return inner == null ? null : "post:" + post.getOperation() + "(" + inner + ")";
        }
        if (expr instanceof Sqlite3BinaryOperation) {
            Sqlite3BinaryOperation bin = (Sqlite3BinaryOperation) expr;
            String left = canonical(bin.getLeft(), depth + 1);
            String right = canonical(bin.getRight(), depth + 1);
            return left == null || right == null ? null
                    : "bin:" + bin.getOperator() + "(" + left + "," + right + ")";
        }
        if (expr instanceof BinaryComparisonOperation) {
            BinaryComparisonOperation cmp = (BinaryComparisonOperation) expr;
            String left = canonical(cmp.getLeft(), depth + 1);
            String right = canonical(cmp.getRight(), depth + 1);
            return left == null || right == null ? null
                    : "cmp:" + cmp.getOperator() + "(" + left + "," + right + ")";
        }
        return null;
    }

    public static boolean isEgraphCompatible(SQLite3Expression expr) {
        if (expr == null) return false;
        if (expr instanceof SQLite3Constant || expr instanceof SQLite3ColumnName) {
            return false;
        }
        // Bitwise operators, ||, shifts and unary minus used to be rejected here as something the
        // e-graph could not rewrite. It has nodes for &, |, ||, ~ and unary minus (and keeps a shift
        // as one opaque atom), so rejecting them only kept those operators out of the workload.
        // Full tree scan: column reference + depth
        EgraphCompatChecker c = new EgraphCompatChecker();
        c.walk(expr, 0);
        return c.hasColumn && !c.unsupported && c.maxDepth <= MAX_EXPR_DEPTH;
    }

    /** Scans for column references and tracks max nesting depth. */
    private static class EgraphCompatChecker {
        boolean hasColumn;
        boolean unsupported;
        int maxDepth;

        void walk(SQLite3Expression e, int depth) {
            if (e == null || unsupported) return;
            if (depth > maxDepth) maxDepth = depth;
            if (e instanceof SQLite3ColumnName) {
                hasColumn = true;
            } else if (e instanceof BinaryComparisonOperation) {
                BinaryComparisonOperation c = (BinaryComparisonOperation) e;
                walk(c.getLeft(), depth + 1); walk(c.getRight(), depth + 1);
            } else if (e instanceof Sqlite3BinaryOperation) {
                Sqlite3BinaryOperation b = (Sqlite3BinaryOperation) e;
                walk(b.getLeft(), depth + 1); walk(b.getRight(), depth + 1);
            } else if (e instanceof BetweenOperation) {
                BetweenOperation b = (BetweenOperation) e;
                walk(b.getExpression(), depth + 1);
                walk(b.getLeft(), depth + 1); walk(b.getRight(), depth + 1);
            } else if (e instanceof SQLite3UnaryOperation) {
                walk(((SQLite3UnaryOperation) e).getExpression(), depth + 1);
            } else if (e instanceof SQLite3PostfixUnaryOperation) {
                walk(((SQLite3PostfixUnaryOperation) e).getExpression(), depth + 1);
            } else if (e instanceof InOperation) {
                // The rewrite server has no node for IN, so it keeps the whole predicate as one
                // opaque atom and offers the identity rewrites for it. That is still worth sending:
                // the atom is where the row-value and IN-subquery reports live.
                InOperation in = (InOperation) e;
                walk(in.getLeft(), depth + 1);
                if (in.getRightExpressionList() != null) {
                    for (SQLite3Expression right : in.getRightExpressionList()) {
                        walk(right, depth + 1);
                    }
                }
            } else if (e instanceof SQLite3RowValueExpression) {
                for (SQLite3Expression element : ((SQLite3RowValueExpression) e).getExpressions()) {
                    walk(element, depth + 1);
                }
            } else if (e instanceof Cast) {
                walk(((Cast) e).getExpression(), depth + 1);
            } else if (e instanceof CollateOperation) {
                walk(((CollateOperation) e).getExpression(), depth + 1);
            } else if (e instanceof Function) {
                for (SQLite3Expression argument : ((Function) e).getArguments()) {
                    walk(argument, depth + 1);
                }
            } else if (e instanceof SQLite3Case) {
                SQLite3Case caseExpression = (SQLite3Case) e;
                for (SQLite3Case.CasePair pair : caseExpression.getPairs()) {
                    walk(pair.getCond(), depth + 1);
                    walk(pair.getThen(), depth + 1);
                }
                walk(caseExpression.getElseExpr(), depth + 1);
            }
            // Anything else is a shape this walk does not know. It is not rejected: the rewrite
            // server keeps such a subtree as one opaque atom and its rules work around it. It simply
            // contributes no column and no depth.
        }

    }
}
