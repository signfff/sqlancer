-- 001: PRAGMA integrity_check fails on every contentless FTS4 table since 3.44.0
-- Run: sqlite3 :memory: < repro.sql
-- Expected (3.43.2 and fixed builds, ca8de9a304+): every check prints "ok".
-- Buggy (3.44.0 .. trunk before ca8de9a304): case A and B print
--   unable to validate the inverted index for FTS4 table main.a: SQL logic error

.print == A: empty contentless FTS4
CREATE VIRTUAL TABLE a USING fts4(x, content='');
PRAGMA integrity_check;
PRAGMA quick_check;

.print == B: after a write (MATCH keeps working)
INSERT INTO a(docid, x) VALUES (1, 'hello world');
SELECT docid FROM a WHERE a MATCH 'hello';
PRAGMA integrity_check;

.print == C: the FTS4 'integrity-check' command (failed even in 3.43.2 before the fix)
INSERT INTO a(a) VALUES('integrity-check');

.print == D: control, ordinary FTS4
CREATE VIRTUAL TABLE d USING fts4(x);
INSERT INTO d(x) VALUES ('hello');

.print == E: control, external-content FTS4
CREATE TABLE src(x);
INSERT INTO src VALUES ('hello');
CREATE VIRTUAL TABLE e USING fts4(x, content='src');
INSERT INTO e(e) VALUES('rebuild');

.print == F: control, contentless FTS5
CREATE VIRTUAL TABLE f USING fts5(x, content='');
INSERT INTO f(rowid, x) VALUES (1, 'hello');

.print == integrity of D, E, F alongside A
PRAGMA integrity_check;
