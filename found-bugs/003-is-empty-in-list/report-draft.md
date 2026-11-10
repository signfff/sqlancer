# IS 右侧的空 IN 列表被当作 FALSE 布尔词（真值特例），而不是文档所述的常量 0

> 发帖：https://sqlite.org/bugs/forumnew（先 Preview 再 Submit）。下面英文部分是帖子正文，中文是给自己看的说明。

## 中文说明

- **现象**：`x IS (y IN ())` 被求值为 `x IS FALSE`（真值特例 ≡ NOT x），而不是 `x IS 0`。对空串/零长 blob 的 x 可观察：`'' IS (0 IN ())`=1 但 `'' IS 0`=0。`IS NOT` 侧同样分叉（`'' IS NOT (0 IN ())`=0，`'' IS NOT 0`=1）。
- **违背的依据**：
  - 文档 lang_expr.html（IN）："When the right operand is an empty set, the result of IN is false and the result of NOT IN is true, regardless of the left operand…"——即 `(y IN ())` 是常量 false ≡ 整数 0。
  - 同页（布尔标识符）：TRUE/FALSE 通常只是 1/0 的别名，但**出现在 IS 右侧时**触发"把左操作数当布尔值求"的特例。用户写的是带括号的表达式、不是 FALSE 关键字，替换不应触发特例。
  - parse.y:1497–1518 注释自己写 "simplify to constants 0 (false) and 1 (true)"，实现却用 `sqlite3ExprIdToTrueFalse` 造 TK_TRUEFALSE 布尔词——注释与实现自相矛盾。
- **尖锐点**：死边套个函数就变回"正常"（聚合安全分支走 `expr AND false`，产整数 0）：`'' IS (length('') IN ())`=0 vs `'' IS (0 IN ())`=1。加一个不影响值的函数改变了外层 IS 的语义。
- **版本**：3.51.0、3.54.0、3.55.0（ddd6db1c）和最新 master（2026-11-04）全部复现。空 IN 简化自 2002 年 2.7.3 就在（changelog 有记录），之后 24 年无条目再动它；论坛全文搜索无先例报告。
- **怎么发现的**：长跑 20261031（EGRAPH oracle 差分测试 trunk）现场包 repro/0001——求值器把 `TRIM(c1,c1) IS (-693048757 IN ())` 按 `x IS 0` 改写为 `(0 = TRIM) IS TRUE`，行数 10 vs 0 触发不一致；bisect 定位该合取式后语义探测揭示分叉。注意诚实口径：**oracle 报的不一致本身是自家假阳性**（求值器建模错），SQLite 语义坑是在假阳性分诊中定性的；帖子里只用一句话交代发现渠道。
- **修复建议**：空 IN 分支产 TK_INTEGER 0/1（即去掉 `sqlite3ExprIdToTrueFalse` 调用）。typeof/算术/CASE/等值全部上下文行为不变，只有 IS/IS NOT 变为文档所述语义。数字 LHS 上两者恰好一致（`0 IS (0 IN ())`=1），不会引入行为变化。

## English (post body)

**Title:** `x IS (y IN ())` behaves as `x IS FALSE` (boolean truth test), not as `x IS 0`

```sql
SELECT '' IS 0;
-- 0
SELECT '' IS (0 IN ());
-- 1
SELECT typeof(0 IN ());
-- integer
```

By the documentation, `(0 IN ())` is the value FALSE, an alias for the integer 0 ("When the right operand is an empty set, the result of IN is false and the result of NOT IN is true, regardless of the left operand"), and it behaves as the integer 0 in every other context:

```sql
SELECT (0 IN ()) + 1, (0 IN ()) = 0, CASE WHEN (0 IN ()) THEN 1 ELSE 0 END;
-- 1|1|0
SELECT 0 IS (0 IN ());
-- 1  (agrees with 0 IS 0, but only by luck: see below)
```

However, on the right-hand side of IS / IS NOT it behaves as the FALSE keyword, which triggers the documented truth-value special case ("if TRUE or FALSE occur on the right-hand side of an IS operator, then the IS operator evaluates the left-hand operand as a boolean value and returns an appropriate answer"):

```sql
SELECT '' IS 0, '' IS (0 IN ()), '' IS FALSE;
-- 0|1|1
SELECT '' IS NOT 0, '' IS NOT (0 IN ()), '' IS NOT FALSE;
-- 1|0|0
```

So `x IS (y IN ())` is evaluated as NOT x, and `x IS NOT (y IN ())` as the truth value of x, diverging from `x IS 0` / `x IS NOT 0` whenever x is an empty string or a zero-length blob.

The meaning also depends on whether the dead operand contains a function: the aggregate-safety branch takes over and the result is the documented one again:

```sql
SELECT '' IS (length('') IN ());
-- 0
```

The cause is the empty-IN simplification in parse.y (trunk, around line 1497), which substitutes a TRUE/FALSE token for the whole expression despite the comment above it saying "simplify to constants 0 (false) and 1 (true)":

```c
Expr *pB = sqlite3Expr(pParse->db, TK_STRING, N ? "true" : "false");
if( pB ) sqlite3ExprIdToTrueFalse(pB);
```

sqlite3ExprIdToTrueFalse() produces a TK_TRUEFALSE token, which is exactly what the IS truth-value special case looks for, so the substitution — transparent everywhere else — becomes observable under IS. Emitting a TK_INTEGER 0/1 instead (i.e. not calling sqlite3ExprIdToTrueFalse() here) would leave the behavior identical in every other context (typeof, arithmetic, CASE, comparisons) and make IS / IS NOT follow the documented "result of IN is false" semantics.

Seen on 3.51.0, 3.54.0, 3.55.0 and trunk (2026-11-04). Found by differential-testing trunk with SQLancer's EGRAPH oracle, which rewrote `x IS (y IN ())` into `(0 = x) IS TRUE`; the resulting row-count difference on an R-Tree query (`TRIM(c1,c1) IS (-693048757 IN ())`) led to this report.

## 正文中文翻译（供自己核对，不对外发）

**标题：** `x IS (y IN ())` 被当作 `x IS FALSE`（布尔真值判断）求值，而不是 `x IS 0`

```sql
SELECT '' IS 0;
-- 0
SELECT '' IS (0 IN ());
-- 1
SELECT typeof(0 IN ());
-- integer
```

根据文档，`(0 IN ())` 是值 FALSE，即整数 0 的别名（"当右操作数为空集时，IN 的结果为 false，NOT IN 的结果为 true，与左操作数无关"），并且它在其他所有上下文中都按整数 0 行事：

```sql
SELECT (0 IN ()) + 1, (0 IN ()) = 0, CASE WHEN (0 IN ()) THEN 1 ELSE 0 END;  -- 1|1|0
SELECT 0 IS (0 IN ());  -- 1   （与 0 IS 0 一致，但只是巧合：见下文）
```

然而，当它出现在 IS / IS NOT 的右侧时，它表现得像 FALSE 关键字，从而触发了文档所述的真值特例（"如果 TRUE 或 FALSE 出现在 IS 运算符的右侧，则 IS 运算符将左操作数作为布尔值求值并返回相应结果"）：

```sql
SELECT '' IS 0, '' IS (0 IN ()), '' IS FALSE;
-- 0|1|1
SELECT '' IS NOT 0, '' IS NOT (0 IN ()), '' IS NOT FALSE;
-- 1|0|0
```

因此 `x IS (y IN ())` 被求值为 NOT x，`x IS NOT (y IN ())` 被求值为 x 的真值——当 x 是空字符串或零长度 blob 时，与 `x IS 0` / `x IS NOT 0` 分叉。

语义还取决于死操作数是否包含函数：此时走聚合安全分支，结果又变回文档所述：

```sql
SELECT '' IS (length('') IN ());
-- 0
```

原因是 parse.y（trunk，约 1497 行）中的空 IN 简化，它把整个表达式替换成 TRUE/FALSE 词法单元，尽管其上方的注释写着 "simplify to constants 0 (false) and 1 (true)"：

```c
Expr *pB = sqlite3Expr(pParse->db, TK_STRING, N ? "true" : "false");
if( pB ) sqlite3ExprIdToTrueFalse(pB);
```

sqlite3ExprIdToTrueFalse() 产出 TK_TRUEFALSE 词法单元，而这正是 IS 真值特例所匹配的东西，于是这个在其他地方完全透明的替换在 IS 下变得可观察。改为产出 TK_INTEGER 0/1（即此处不调用 sqlite3ExprIdToTrueFalse()）会在其他所有上下文（typeof、算术、CASE、比较）保持行为不变，并让 IS / IS NOT 遵循文档所述"空集时 IN 的结果为 false"的语义。

在 3.51.0、3.54.0、3.55.0 和 trunk（2026-11-04）上均可复现。用 SQLancer 的 EGRAPH oracle 对 trunk 做差分测试时发现：oracle 把 `x IS (y IN ())` 改写为 `(0 = x) IS TRUE`，在一条 R-Tree 查询（`TRIM(c1,c1) IS (-693048757 IN ())`）上产生的行数差异引出了本报告。

