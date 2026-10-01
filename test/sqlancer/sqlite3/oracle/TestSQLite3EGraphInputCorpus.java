package sqlancer.sqlite3.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.sqlite3.SQLite3Options;

public class TestSQLite3EGraphInputCorpus {

    @Test
    public void testReplayFileParsing() throws Exception {
        Path file = Files.createTempFile("sqlite-egraph-corpus", ".sql");
        Files.writeString(file,
                "-- header\n"
                        + "CREATE TABLE t0(c0 TEXT); -- 0ms;\n"
                        + "INSERT INTO t0 VALUES('a;b'); -- 1ms;\n"
                        + "SELECT * FROM t0; -- 2ms;\n"
                        + "SELECT * FROM t0 WHERE c0 = 'a;b'; -- 2ms;\n"
                        + "WITH q AS (SELECT c0 FROM t0) SELECT * FROM q; -- 3ms;\n",
                StandardCharsets.UTF_8);

        SQLite3Options options = new SQLite3Options();
        options.egraphInputFile = file.toString();

        List<String> statements = SQLite3EGraphInputCorpus.readStatements(options);
        assertEquals(5, statements.size());
        assertEquals("CREATE TABLE t0(c0 TEXT)", statements.get(0));
        assertEquals("INSERT INTO t0 VALUES('a;b')", statements.get(1));

        assertFalse(SQLite3EGraphInputCorpus.isQueryInput(statements.get(0)));

        List<SQLite3EGraphInputCorpus.CorpusQueryInput> records = SQLite3EGraphInputCorpus
                .readQueryInputRecords(options);
        assertEquals(1, records.size());
        assertTrue(records.get(0).getQuery().startsWith("SELECT * FROM t0"));
        assertTrue(records.get(0).hasSetupStatements());
        assertEquals(List.of("CREATE TABLE t0(c0 TEXT)", "INSERT INTO t0 VALUES('a;b')"),
                records.get(0).getSetupStatements());
    }

    @Test
    public void testBlobAndIntegerHexInputsAreAccepted() {
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT * FROM t0 WHERE c0 <= x'7979'"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT * FROM t0 WHERE c0 = X''"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT * FROM t0 WHERE c0 <= 0x7979"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT * FROM t0 WHERE c0 = 'x''7979'''"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT \"x'7979\" FROM t0 WHERE c0 <= 1"));
    }

    @Test
    public void testQueryWithoutWhereNeedsASubqueryToRewrite() {
        assertFalse(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT c0 FROM t0"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput(
                "SELECT (SELECT s.c1 FROM t3 s WHERE a.k = s.c1 ORDER BY s.c1 LIMIT 1) FROM t0 a"));
        assertTrue(SQLite3EGraphInputCorpus.isSafeEGraphQueryInput("SELECT 20 IN (SELECT y FROM t2)"));
    }
}
