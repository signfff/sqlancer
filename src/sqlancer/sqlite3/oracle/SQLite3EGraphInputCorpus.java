package sqlancer.sqlite3.oracle;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.sqlite3.SQLite3Options;

public final class SQLite3EGraphInputCorpus {

    private static final Map<String, List<String>> STATEMENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> SETUP_STATEMENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<CorpusQueryInput>> QUERY_INPUT_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_QUERY_INPUTS = Integer.getInteger("sqlite3.egraph.input.maxQueries", 20000);
    private static final int MAX_SETUP_STATEMENTS = Integer.getInteger("sqlite3.egraph.input.maxSetupStatements", 0);
    private static final int MAX_CASE_SETUP_STATEMENTS = Integer
            .getInteger("sqlite3.egraph.input.maxCaseSetupStatements", 80);
    private static final int MAX_CASE_SETUP_CHARS = Integer.getInteger("sqlite3.egraph.input.maxCaseSetupChars", 12000);
    private static final int MAX_CASE_SETUP_STATEMENT_CHARS = Integer
            .getInteger("sqlite3.egraph.input.maxCaseSetupStatementChars", 2000);

    private SQLite3EGraphInputCorpus() {
    }

    public static boolean isConfigured(SQLite3Options options) {
        return options != null && options.egraphInputFile != null && !options.egraphInputFile.isBlank();
    }

    public static List<String> readStatements(SQLite3Options options) {
        if (!isConfigured(options)) {
            return Collections.emptyList();
        }
        return STATEMENT_CACHE.computeIfAbsent(options.egraphInputFile, ignored -> {
            List<String> result = new ArrayList<>();
            for (String path : getInputFiles(options)) {
                result.addAll(readStatements(path));
            }
            return Collections.unmodifiableList(result);
        });
    }

    public static List<String> readSetupStatements(SQLite3Options options) {
        if (!isConfigured(options)) {
            return Collections.emptyList();
        }
        if (MAX_SETUP_STATEMENTS <= 0) {
            return Collections.emptyList();
        }
        return SETUP_STATEMENT_CACHE.computeIfAbsent(options.egraphInputFile, ignored -> {
            List<String> result = new ArrayList<>();
            List<String> inputFiles = getInputFiles(options);
            if (inputFiles.isEmpty()) {
                return Collections.emptyList();
            }
            for (String statement : readStatements(inputFiles.get(0))) {
                if (isSetupStatement(statement)) {
                    if (result.size() >= MAX_SETUP_STATEMENTS) {
                        break;
                    }
                    result.add(statement);
                }
            }
            return Collections.unmodifiableList(result);
        });
    }

    public static List<CorpusQueryInput> readQueryInputRecords(SQLite3Options options) {
        if (!isConfigured(options)) {
            return Collections.emptyList();
        }
        return QUERY_INPUT_CACHE.computeIfAbsent(options.egraphInputFile, ignored -> {
            List<CorpusQueryInput> result = new ArrayList<>();
            for (String path : getInputFiles(options)) {
                List<CorpusQueryInput> caseInputs = readCaseInputs(path);
                if (!caseInputs.isEmpty()) {
                    result.addAll(caseInputs);
                    if (result.size() >= MAX_QUERY_INPUTS) {
                        break;
                    }
                    continue;
                }
                List<String> statements = readStatements(path);
                int segmentStart = 0;
                boolean segmentHasSetup = false;
                for (int i = 0; i < statements.size(); i++) {
                    String statement = statements.get(i);
                    if (isSetupStatement(statement)) {
                        if (startsNewCorpusSegment(statement)) {
                            segmentStart = i;
                            segmentHasSetup = false;
                        }
                        segmentHasSetup = true;
                    } else if (segmentHasSetup && isSafeEGraphQueryInput(statement)) {
                        List<String> setupStatements = statements.subList(segmentStart, i).stream()
                                .filter(SQLite3EGraphInputCorpus::isSetupStatement)
                                .collect(java.util.stream.Collectors.toList());
                        String setupRejectReason = getCorpusSetupRejectReason(setupStatements);
                        if (setupRejectReason == null) {
                            result.add(new CorpusQueryInput(setupStatements, statement, path));
                        } else {
                            EGraphSqlCoverage.recordCorpusFilterSkip("setup-" + setupRejectReason);
                            result.add(new CorpusQueryInput(List.of(), statement, path));
                        }
                        if (result.size() >= MAX_QUERY_INPUTS) {
                            break;
                        }
                    } else if (!segmentHasSetup && isSafeEGraphQueryInput(statement)) {
                        result.add(new CorpusQueryInput(List.of(), statement, path));
                        if (result.size() >= MAX_QUERY_INPUTS) {
                            break;
                        }
                    }
                }
                if (result.size() >= MAX_QUERY_INPUTS) {
                    break;
                }
            }
            return Collections.unmodifiableList(result);
        });
    }

    private static List<String> getInputFiles(SQLite3Options options) {
        if (!isConfigured(options)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String rawPath : options.egraphInputFile.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String path = rawPath.trim();
            if (!path.isEmpty()) {
                result.add(path);
            }
        }
        return result;
    }

    public static boolean isQueryInput(String statement) {
        String normalized = statement.stripLeading().toUpperCase(Locale.ROOT);
        return normalized.startsWith("SELECT ") || normalized.startsWith("WITH ");
    }

    public static boolean isSafeEGraphQueryInput(String statement) {
        return getEGraphQueryRejectReason(statement) == null;
    }

    private static String getEGraphQueryRejectReason(String statement) {
        String normalized = normalizeForFilter(statement);
        if (!normalized.startsWith("SELECT ") && !normalized.startsWith("SELECT\n")) {
            return "not-select";
        }
        // Without a WHERE the server can still rewrite the WHERE of a subquery in the SELECT list, so
        // a query carrying a subquery comes in; the server hands back no variants when it cannot.
        if (!(" " + normalized + " ").contains(" WHERE ") && !normalized.contains("(SELECT ")) {
            return "no-where";
        }
        if (normalized.matches(".*\\bSQLITE_(TEMP_)?(MASTER|SCHEMA|STAT1|STAT4)\\b.*")) {
            return "sqlite-internal-schema";
        }
        String padded = " " + normalized + " ";
        for (String token : UNSUPPORTED_CORPUS_TOKENS) {
            if (padded.contains(token)) {
                return "unsupported-" + token.strip().replace(' ', '-').toLowerCase(Locale.ROOT);
            }
        }
        if (normalized.contains(" FROM (")) {
            return "derived-from";
        }
        return null;
    }

    /**
     * Tokens that make a corpus SELECT unusable as an EGRAPH input. There used to be two copies of this list differing
     * by one entry, so a token added to one was silently absent from the other.
     */
    private static final List<String> UNSUPPORTED_CORPUS_TOKENS = buildUnsupportedCorpusTokens();

    private static List<String> buildUnsupportedCorpusTokens() {
        List<String> tokens = new ArrayList<>(List.of(" WITH ", " MATCH ", " GROUP BY ", " HAVING ", " WINDOW ",
                " OVER ", " UNION ", " INTERSECT ", " EXCEPT ", " VALUES ", " INDEXED BY ", " RETURNING ", " PRAGMA ",
                " CREATE ", " INSERT ", " UPDATE ", " DELETE ", " DROP ", " ALTER ", " REINDEX ", " ANALYZE ",
                " VACUUM ", " TRIGGER "));
        // JOIN was rejected here because the base query used to be single-table. Since
        // egraph.joinPercent the random path emits joins, so the corpus channel refusing them is
        // inconsistent - and it blocks using any join-shaped bug report as a regression corpus.
        // Opt-in for now: a corpus query spanning tables it did not create would fail to run.
        if (!Boolean.getBoolean("sqlite3.egraph.corpus.allowJoin")) {
            tokens.add(" JOIN ");
        }
        return Collections.unmodifiableList(tokens);
    }

    private static boolean isSetupStatement(String statement) {
        return !isQueryInput(statement) && !statement.isBlank();
    }

    private static boolean startsNewCorpusSegment(String statement) {
        String normalized = normalizeForFilter(statement);
        return normalized.matches("CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?T0\\b.*");
    }

    private static List<CorpusQueryInput> readCaseInputs(String inputFile) {
        List<CorpusQueryInput> result = new ArrayList<>();
        StringBuilder setup = new StringBuilder();
        StringBuilder query = new StringBuilder();
        // Captured cases are delta encoded: a full snapshot every keyframe, and in
        // between only the statements that rebuild what changed. Replaying the
        // accumulated statements reproduces the database of any single case.
        String accumulatedSetup = "";
        boolean inCase = false;
        boolean rowsDetermined = false;
        boolean inSetup = false;
        boolean inQuery = false;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(inputFile), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(EGraphCorpusCaseWriter.CASE_BEGIN)) {
                    inCase = true;
                    inSetup = false;
                    inQuery = false;
                    rowsDetermined = trimmed.contains(" rows_determined=1");
                    setup.setLength(0);
                    query.setLength(0);
                    continue;
                }
                if (!inCase) {
                    continue;
                }
                if (trimmed.equals(EGraphCorpusCaseWriter.SETUP_BEGIN)) {
                    inSetup = true;
                    inQuery = false;
                    setup.setLength(0);
                    continue;
                }
                if (trimmed.equals(EGraphCorpusCaseWriter.SETUP_DELTA)) {
                    inSetup = true;
                    inQuery = false;
                    setup.setLength(0);
                    setup.append(accumulatedSetup);
                    continue;
                }
                if (trimmed.equals(EGraphCorpusCaseWriter.BASE_QUERY)) {
                    if (inSetup) {
                        accumulatedSetup = setup.toString();
                    }
                    inSetup = false;
                    inQuery = true;
                    continue;
                }
                if (trimmed.equals(EGraphCorpusCaseWriter.CASE_END)) {
                    addCaseInput(result, setup.toString(), query.toString(), inputFile, rowsDetermined);
                    if (result.size() >= MAX_QUERY_INPUTS) {
                        break;
                    }
                    inCase = false;
                    inSetup = false;
                    inQuery = false;
                    continue;
                }
                if (inSetup) {
                    setup.append(line).append('\n');
                } else if (inQuery) {
                    query.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read SQLite EGRAPH input file: " + inputFile, e);
        }
        return result;
    }

    private static void addCaseInput(List<CorpusQueryInput> result, String setupText, String queryText,
            String inputFile, boolean rowsDetermined) {
        List<String> setupStatements = readStatementsFromText(setupText);
        if (!isSafeCorpusSetupForReplay(setupStatements)) {
            EGraphSqlCoverage.recordCorpusFilterSkip("setup-" + getCorpusSetupRejectReason(setupStatements));
            return;
        }
        for (String statement : readStatementsFromText(queryText)) {
            String queryRejectReason = getEGraphQueryRejectReason(statement);
            if (queryRejectReason == null) {
                result.add(new CorpusQueryInput(setupStatements, statement, inputFile, rowsDetermined));
                return;
            } else {
                EGraphSqlCoverage.recordCorpusFilterSkip("query-" + queryRejectReason);
            }
        }
    }

    private static boolean isSafeCorpusSetupForReplay(List<String> setupStatements) {
        return getCorpusSetupRejectReason(setupStatements) == null;
    }

    /**
     * The longest single setup statement this reader accepts, so the writer can size what it emits from the limit that
     * will be applied to it rather than from a copy of the number. The copy is how the corpus came to hold 9273 cases of
     * which 874 could be loaded.
     */
    public static int maxCaseSetupStatementChars() {
        return MAX_CASE_SETUP_STATEMENT_CHARS;
    }

    /** The most setup statements this reader will replay for one case. */
    public static int maxCaseSetupStatements() {
        return MAX_CASE_SETUP_STATEMENTS;
    }

    /** The most setup characters this reader will replay for one case. */
    public static int maxCaseSetupChars() {
        return MAX_CASE_SETUP_CHARS;
    }

    /** Why this reader would refuse a case with these setup statements, or null when it would take it. */
    public static String corpusSetupRejectReason(List<String> setupStatements) {
        return getCorpusSetupRejectReason(setupStatements);
    }

    private static String getCorpusSetupRejectReason(List<String> setupStatements) {
        if (setupStatements.size() > MAX_CASE_SETUP_STATEMENTS) {
            return "too-many-statements";
        }
        int totalChars = 0;
        for (String statement : setupStatements) {
            totalChars += statement.length();
            if (totalChars > MAX_CASE_SETUP_CHARS) {
                return "too-many-chars";
            }
            if (statement.length() > MAX_CASE_SETUP_STATEMENT_CHARS) {
                return "statement-too-long";
            }
            String normalized = normalizeForFilter(statement);
            if (normalized.contains("SQLITE_MASTER") || normalized.contains("SQLITE_SCHEMA")
                    || normalized.contains("SQLITE_TEMP_MASTER") || normalized.contains("SQLITE_TEMP_SCHEMA")) {
                return "sqlite-schema";
            }
            if (normalized.contains("SQLITE_STAT1") || normalized.contains("SQLITE_STAT4")) {
                return "sqlite-stat";
            }
            if (normalized.matches("^PRAGMA\\s+(WRITABLE_SCHEMA|PAGE_SIZE|MAX_PAGE_COUNT|JOURNAL_MODE|LOCKING_MODE"
                    + "|INCREMENTAL_VACUUM|AUTO_VACUUM|SYNCHRONOUS|INTEGRITY_CHECK|QUICK_CHECK)\\b.*")) {
                return "unsafe-pragma";
            }
            if (normalized.matches("^(VACUUM|ATTACH|DETACH|BEGIN|COMMIT|ROLLBACK|SAVEPOINT|RELEASE)\\b.*")) {
                return "transaction-or-vacuum";
            }
            if (normalized.contains("WITH RECURSIVE") || normalized.contains("RANDOMBLOB(")) {
                return "expensive-expression";
            }
            if (normalized.matches("^INSERT\\b.*\\bSELECT\\b.*")) {
                return "insert-select";
            }
        }
        return null;
    }

    private static List<String> readStatements(String inputFile) {
        try (BufferedReader reader = Files.newBufferedReader(Path.of(inputFile), StandardCharsets.UTF_8)) {
            return readStatements(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read SQLite EGRAPH input file: " + inputFile, e);
        }
    }

    private static List<String> readStatementsFromText(String text) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            return readStatements(reader);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> readStatements(BufferedReader reader) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String line;
        while ((line = reader.readLine()) != null) {
            String uncommented = stripLineComment(line);
            for (int i = 0; i < uncommented.length(); i++) {
                char c = uncommented.charAt(i);
                current.append(c);
                if (c == '\'' && !inDoubleQuote) {
                    if (i + 1 < uncommented.length() && uncommented.charAt(i + 1) == '\'') {
                        current.append(uncommented.charAt(++i));
                    } else {
                        inSingleQuote = !inSingleQuote;
                    }
                } else if (c == '"' && !inSingleQuote) {
                    if (i + 1 < uncommented.length() && uncommented.charAt(i + 1) == '"') {
                        current.append(uncommented.charAt(++i));
                    } else {
                        inDoubleQuote = !inDoubleQuote;
                    }
                } else if (c == ';' && !inSingleQuote && !inDoubleQuote && canSplitStatement(current)) {
                    addStatement(result, current);
                    current.setLength(0);
                }
            }
            current.append('\n');
        }
        addStatement(result, current);
        return Collections.unmodifiableList(result);
    }

    private static String stripLineComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (c == '"' && !inSingleQuote) {
                if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inDoubleQuote = !inDoubleQuote;
                }
            } else if (c == '-' && line.charAt(i + 1) == '-' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static boolean canSplitStatement(StringBuilder current) {
        String statement = current.toString().trim();
        String normalized = normalizeForFilter(statement);
        return !normalized.startsWith("CREATE TRIGGER ") || normalized.endsWith(" END;");
    }

    private static void addStatement(List<String> result, StringBuilder current) {
        String statement = current.toString().trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (!statement.isEmpty()) {
            result.add(statement);
        }
    }

    private static String normalizeForFilter(String statement) {
        return statement.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public static final class CorpusQueryInput {
        private final List<String> statements;
        private final List<String> setupStatements;
        private final int setupStartInclusive;
        private final int queryIndex;
        private final String query;
        private final String sourceName;
        private final boolean rowsDetermined;

        private CorpusQueryInput(List<String> statements, int setupStartInclusive, int queryIndex, String query) {
            this.statements = statements;
            this.setupStatements = null;
            this.setupStartInclusive = setupStartInclusive;
            this.queryIndex = queryIndex;
            this.query = query;
            this.sourceName = "flat-corpus";
            this.rowsDetermined = false;
        }

        private CorpusQueryInput(List<String> setupStatements, String query, String sourceName) {
            this(setupStatements, query, sourceName, false);
        }

        private CorpusQueryInput(List<String> setupStatements, String query, String sourceName,
                boolean rowsDetermined) {
            this.statements = null;
            this.setupStatements = List.copyOf(setupStatements);
            this.setupStartInclusive = 0;
            this.queryIndex = 0;
            this.query = query;
            this.sourceName = sourceName;
            this.rowsDetermined = rowsDetermined;
        }

        /**
         * Whether the case says which rows its query returns. False for a case written before this was recorded, which
         * only means its rows have to be compared by count when the query truncates them.
         */
        public boolean rowsDetermined() {
            return rowsDetermined;
        }

        public String getQuery() {
            return query;
        }

        public List<String> getSetupStatements() {
            if (setupStatements != null) {
                return setupStatements;
            }
            return statements.subList(setupStartInclusive, queryIndex).stream()
                    .filter(SQLite3EGraphInputCorpus::isSetupStatement)
                    .collect(java.util.stream.Collectors.toList());
        }

        public boolean hasSetupStatements() {
            return !getSetupStatements().isEmpty();
        }

        public String getSourceName() {
            return sourceName;
        }
    }
}
