# 单元素 IN 列表采用了右侧元素的显式 COLLATE（与文档及多元素列表不一致）

> 发帖：https://sqlite.org/bugs/forumnew（先 Preview 再 Submit）。下面英文部分是帖子正文，中文是给自己看的说明。

## 中文说明

- **现象**：`x IN (y)` 只有一个元素时，`y` 上的显式 `COLLATE NOCASE` 会生效，连左侧列的默认排序规则都被它覆盖；同样的元素写两遍 `x IN (y, y)`，排序规则又变回 x 的。同一个集合判断，只因元素个数不同，结果就不同。
- **违背的依据**：
  - 文档 datatype3.html §7.1："`x IN (y, z, ...)` 形式使用的排序规则是 x 的排序规则；若需要显式排序规则，应加在左操作数上。"
  - 2014-03-20 的 check-in 2ff3b25f40（ticket e39d032577df69）和 2ea4a9f75f：drh 指出 "x IN (?)" 不完全等同于 "x==?"，并加了 EP_Generic 标记，专门阻止 IN 右侧的亲和性和排序规则信息，起因正是右侧的 COLLATE 覆盖了左侧的隐式排序规则（测试 in4-5.1）。
- **版本**：3.51.0、3.53.4 和 trunk（2026-10-06 a435d786fe）行为一致，属于长期存在的问题，不是近期回归。
- **怎么发现的**：做 e-graph 改写规则时，agent 提出"单元素 IN 有可能被改写成 `x = +y`，从而带上 y 的 COLLATE"，用真 SQLite 一测就复现了。
- **注意**：`x IN (SELECT y)` 按文档本来就和 `x = y` 一样处理，那个不是问题；问题只出在列表形式。

## English (post body)

**Title:** A single-element IN list uses an explicit COLLATE on its element, contrary to the documentation and to a two-element list

```sql
CREATE TABLE t(c TEXT);
INSERT INTO t VALUES('abc'),('ABC'),('x');

SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE);
-- abc, ABC

SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);
-- ABC

SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE);
-- x

SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);
-- abc, x

SELECT 'abc' IN ('ABC' COLLATE NOCASE), 'abc' IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);
-- 1|0
```

The two lists hold the same value, but the one-element list compares with NOCASE (the element's explicit collation), while the two-element list compares with BINARY (the collation of c).

datatype3.html section 7.1 says: "The collating sequence used for expressions of the form "x IN (y, z, ...)" is the collating sequence of x. If an explicit collating sequence is required on an IN operator it should be applied to the left operand". By that rule, both queries should use BINARY and return only `ABC` (and the NOT IN queries `abc, x`).

This looks like the situation check-in 2ff3b25f40 / 2ea4a9f75f (2014-03-20, ticket e39d032577df69) addressed. That check-in explained that "x IN (?)" is not the same as "x==?" because of collation and affinity, and added EP_Generic so that the right-hand side of IN contributes no collation. The single-element rewrite seems to let the element's explicit COLLATE through again, and it even overrides the implicit collation of the left column.

Seen on 3.51.0, 3.53.4 and trunk (2026-10-06 18:52:47 a435d786fe0e245e352d08d2e2ddf8c1e15154809e3b471810cc327e9d90ef85).

(`x IN (SELECT y ...)` is documented to behave like `x = y`, so the subquery form is not part of this report.)
