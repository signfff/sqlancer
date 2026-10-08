-- 002: a single-element IN list uses an explicit COLLATE on its (constant) element,
--      contrary to datatype3.html 7.1 and to the same list with two elements.
-- Run: sqlite3 :memory: < repro.sql
-- Expected by the documentation ("x IN (y, z, ...)" uses the collating sequence of x = BINARY here):
--   every IN query returns only ABC, every NOT IN query abc and x, and the scalar line 0|0|0.
-- Observed (3.51.0, 3.53.4, trunk 2026-10-06 9f05c6e): the one-element lists compare with NOCASE.

CREATE TABLE t(c TEXT);
INSERT INTO t VALUES('abc'),('ABC'),('x');

.print == IN, one element            (observed: abc, ABC)
SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE);
.print == IN, the same element twice (observed: ABC)
SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);
.print == NOT IN, one element        (observed: x)
SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE);
.print == NOT IN, twice              (observed: abc, x)
SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);
.print == scalar: one element, twice, one element plus another value   (observed: 1|0|0)
SELECT 'abc' IN ('ABC' COLLATE NOCASE),
       'abc' IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE),
       'abc' IN ('ABC' COLLATE NOCASE, 'zzz');

-- Boundary of the behaviour
CREATE TABLE u(c TEXT, d TEXT);
INSERT INTO u VALUES('abc','ABC');
.print == non-constant element: not rewritten, uses c's collation   (observed: 0|0)
SELECT c IN (d COLLATE NOCASE), c IN (d COLLATE NOCASE, 'zzz') FROM u;
.print == constant expression element: rewritten                     (observed: 1|0)
SELECT c IN (('AB' || 'C') COLLATE NOCASE), c IN (('AB' || 'C') COLLATE NOCASE, 'zzz') FROM u;
.print == explicit COLLATE on the left wins in both forms             (observed: 0)
SELECT (c COLLATE BINARY) IN ('ABC' COLLATE NOCASE) FROM u;
.print == subquery form, documented to behave like x = y (not part of the report)   (observed: 1)
SELECT c IN (SELECT 'ABC' COLLATE NOCASE) FROM u;
