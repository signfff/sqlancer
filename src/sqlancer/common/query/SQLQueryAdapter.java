package sqlancer.common.query;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import sqlancer.GlobalState;
import sqlancer.Main;
import sqlancer.SQLConnection;

public class SQLQueryAdapter extends Query<SQLConnection> implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int STATEMENT_TIMEOUT_SECONDS = Integer.getInteger("sqlancer.statementTimeoutSeconds", 0);

    private final String query;
    private final ExpectedErrors expectedErrors;
    private final boolean couldAffectSchema;
    private transient String swallowedError;

    public SQLQueryAdapter(String query) {
        this(query, new ExpectedErrors());
    }

    public SQLQueryAdapter(String query, boolean couldAffectSchema) {
        this(query, new ExpectedErrors(), couldAffectSchema);
    }

    public SQLQueryAdapter(String query, ExpectedErrors expectedErrors) {
        this(query, expectedErrors, guessAffectSchemaFromQuery(query));
    }

    private static boolean guessAffectSchemaFromQuery(String query) {
        return query.contains("CREATE TABLE") && !query.startsWith("EXPLAIN");
    }

    public SQLQueryAdapter(String query, ExpectedErrors expectedErrors, boolean couldAffectSchema) {
        this(query, expectedErrors, couldAffectSchema, true);
    }

    public SQLQueryAdapter(String query, ExpectedErrors expectedErrors, boolean couldAffectSchema,
            boolean canonicalizeString) {
        if (canonicalizeString) {
            this.query = canonicalizeString(query);
        } else {
            this.query = query;
        }
        this.expectedErrors = expectedErrors;
        this.couldAffectSchema = couldAffectSchema;
        checkQueryString();
    }

    private String canonicalizeString(String s) {
        if (s.endsWith(";")) {
            return s;
        } else if (!s.contains("--")) {
            return s + ";";
        } else {
            // query contains a comment
            return s;
        }
    }

    private void checkQueryString() {
        if (!couldAffectSchema && guessAffectSchemaFromQuery(query)) {
            throw new AssertionError("CREATE TABLE statements should set couldAffectSchema to true");
        }
    }

    @Override
    public String getQueryString() {
        return query;
    }

    @Override
    public String getUnterminatedQueryString() {
        String result;
        if (query.endsWith(";")) {
            result = query.substring(0, query.length() - 1);
        } else {
            result = query;
        }
        assert !result.endsWith(";");
        return result;
    }

    /**
     * This method is used to mostly oracles, which need to report exceptions. We set the reportException parameter to
     * true by default meaning that exceptions are reported.
     *
     * @param globalState
     * @param fills
     *
     * @return whether the query was executed successfully
     *
     * @param <G>
     *
     * @throws SQLException
     */
    @Override
    public <G extends GlobalState<?, ?, SQLConnection>> boolean execute(G globalState, String... fills)
            throws SQLException {
        return execute(globalState, true, fills);
    }

    /**
     * This method is used to DQE oracles, DQE does not check exception separately, while other testing methods may
     * need. We use reportException to control this behavior. For a specific DBMS used DQE oracle, we call this method
     * and pass a boolean value of false as an argument.
     *
     * @param globalState
     * @param reportException
     * @param fills
     *
     * @return whether the query was executed successfully
     *
     * @param <G>
     *
     * @throws SQLException
     */
    public <G extends GlobalState<?, ?, SQLConnection>> boolean execute(G globalState, boolean reportException,
            String... fills) throws SQLException {
        return internalExecute(globalState.getConnection(), reportException, fills);
    }

    protected <G extends GlobalState<?, ?, SQLConnection>> boolean internalExecute(SQLConnection connection,
            boolean reportException, String... fills) throws SQLException {
        Statement s;
        if (fills.length > 0) {
            s = connection.prepareStatement(fills[0]);
            for (int i = 1; i < fills.length; i++) {
                ((PreparedStatement) s).setString(i, fills[i]);
            }
        } else {
            s = connection.createStatement();
        }
        applyStatementTimeout(s);
        try {
            if (fills.length > 0) {
                ((PreparedStatement) s).execute();
            } else {
                s.execute(query);
            }
            Main.nrSuccessfulActions.addAndGet(1);
            return true;
        } catch (Exception e) {
            Main.nrUnsuccessfulActions.addAndGet(1);
            if (isStatementTimeout(e)) {
                return false;
            }
            if (reportException) {
                checkException(e);
            }
            return false;
        } finally {
            s.close();
        }
    }

    /**
     * The message of the last error this query swallowed as expected, or "statement timeout", or null. The expected
     * errors include "[SQLITE_ERROR] SQL error or missing database", which every SQLite error message starts with, so
     * a swallowed error is otherwise indistinguishable from a syntax error.
     */
    public String getSwallowedError() {
        return swallowedError;
    }

    public void checkException(Exception e) throws AssertionError {
        Throwable ex = e;

        while (ex != null) {
            if (expectedErrors.errorIsExpected(ex.getMessage())) {
                swallowedError = ex.getMessage();
                return;
            } else {
                ex = ex.getCause();
            }
        }

        throw new AssertionError(query, e);
    }

    @Override
    public <G extends GlobalState<?, ?, SQLConnection>> SQLancerResultSet executeAndGet(G globalState, String... fills)
            throws SQLException {
        return executeAndGet(globalState, true, fills);
    }

    public <G extends GlobalState<?, ?, SQLConnection>> SQLancerResultSet executeAndGet(G globalState,
            boolean reportException, String... fills) throws SQLException {
        return internalExecuteAndGet(globalState.getConnection(), reportException, fills);
    }

    protected <G extends GlobalState<?, ?, SQLConnection>> SQLancerResultSet internalExecuteAndGet(
            SQLConnection connection, boolean reportException, String... fills) throws SQLException {
        Statement s;
        if (fills.length > 0) {
            s = connection.prepareStatement(fills[0]);
            for (int i = 1; i < fills.length; i++) {
                ((PreparedStatement) s).setString(i, fills[i]);
            }
        } else {
            s = connection.createStatement();
        }
        applyStatementTimeout(s);
        ResultSet result;
        try {
            if (fills.length > 0) {
                result = ((PreparedStatement) s).executeQuery();
            } else {
                result = s.executeQuery(query);
            }
            Main.nrSuccessfulActions.addAndGet(1);
            if (result == null) {
                return null;
            }
            return new SQLancerResultSet(result);
        } catch (Exception e) {
            s.close();
            Main.nrUnsuccessfulActions.addAndGet(1);
            if (isStatementTimeout(e)) {
                swallowedError = "statement timeout";
                return null;
            }
            if (reportException) {
                checkException(e);
            }
            return null;
        }
    }

    private static void applyStatementTimeout(Statement statement) {
        if (STATEMENT_TIMEOUT_SECONDS <= 0) {
            return;
        }
        try {
            statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
        } catch (SQLException ignored) {
        }
    }

    private static boolean isStatementTimeout(Exception e) {
        if (STATEMENT_TIMEOUT_SECONDS <= 0) {
            return false;
        }
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("query timeout") || normalized.contains("query timed out")
                        || normalized.contains("statement timeout") || normalized.contains("sqlite_interrupt")
                        || normalized.contains("interrupted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public boolean couldAffectSchema() {
        return couldAffectSchema;
    }

    @Override
    public ExpectedErrors getExpectedErrors() {
        return expectedErrors;
    }

    @Override
    public String getLogString() {
        return getQueryString();
    }
}
