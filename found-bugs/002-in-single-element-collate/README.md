# 002 单元素 IN 列表采用了右侧常量的显式 COLLATE

- **状态**：已确认、已查重（无人报过），**2026-10-08 已发帖**（用户本人发出）。新帖需版主审核，审核通过前公开列表里看不到；帖子链接和官方回复待补
- **组件**：解析器对单元素 IN 的改写（`src/parse.y`）+ 排序规则选择（`sqlite3ExprCollSeq`，`src/expr.c`）
- **类别**：查询结果错误（逻辑 bug），违背文档语义，且内部不一致

## 现象

```sql
CREATE TABLE t(c TEXT);
INSERT INTO t VALUES('abc'),('ABC'),('x');

SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE);                         -- abc, ABC
SELECT c FROM t WHERE c IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE);   -- ABC
SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE);                     -- x
SELECT c FROM t WHERE c NOT IN ('ABC' COLLATE NOCASE, 'ABC' COLLATE NOCASE); -- abc, x
```

两个列表装的是同一个值，结果却不同。按文档，四条查询都应该按 c 的排序规则（BINARY）比较：IN 只返回 ABC，NOT IN 返回 abc 和 x。

## 版本

3.51.0、3.53.4（2026-07-24 bf7c7f3003）和 trunk 3.54.0（2026-10-06 18:52:47 a435d786fe）结果相同。还没有逐版本二分出引入版本。

## 触发条件（逐项实测，见 evidence-2026-10-08.txt）

| 写法 | 结果 | 说明 |
|---|---|---|
| 单元素、元素是**常量**且带显式 COLLATE、左边**没有**显式 COLLATE | **触发** | 走了解析期的改写 |
| 单元素、元素是**常量表达式**，如 `('AB' \|\| 'C') COLLATE NOCASE` | **触发** | 常量表达式也算常量 |
| 单元素、元素**不是常量**，如 `c IN (d COLLATE NOCASE)` | 不触发（与多元素一致） | 不走改写 |
| 左边带显式 COLLATE，如 `(c COLLATE BINARY) IN ('ABC' COLLATE NOCASE)` | 不触发 | 左边的显式排序规则优先 |
| 子查询形式 `c IN (SELECT 'ABC' COLLATE NOCASE)` | 返回 1，**不算 bug** | 文档规定按 `x = y` 处理 |

## 根因（trunk 9f05c6e 源码）

`src/parse.y` 约第 1521 行：

```c
if( Y->nExpr==1 && sqlite3ExprIsConstant(pParse,pRHS) && A->op!=TK_VECTOR ){
  ...
  pRHS = sqlite3PExpr(pParse, TK_UPLUS, pRHS, 0);
  A = sqlite3PExpr(pParse, TK_EQ, A, pRHS);      /* x IN (c)  ->  x = +c */
}
```

一元 `+` 去掉了常量的亲和性，但 `sqlite3ExprCollSeq()`（expr.c 约第 273 行）在查找排序规则时会穿过 TK_UPLUS：`if( op==TK_CAST || op==TK_UPLUS ){ p = p->pLeft; continue; }`。于是常量上的显式 COLLATE 成了 `=` 右操作数的显式排序规则，而在 `=` 的选择规则里，右侧显式排序规则优先于左侧列的隐式排序规则。

## 为什么是 bug

1. **违背文档**：datatype3.html §7.1 原文是 "The collating sequence used for expressions of the form "x IN (y, z, ...)" is the collating sequence of x. If an explicit collating sequence is required on an IN operator it should be applied to the left operand"。这段话是 2020 年论坛帖 "Overriding collation of IN expressions" 之后补进文档的。
2. **违背 SQLite 自己 2014 年的修复意图**：check-in 2ff3b25f40 和 2ea4a9f75f（2014-03-20，ticket e39d032577df69）中，drh 写道 "x IN (?)" 不完全等同于 "x==?"，原因是排序规则和亲和性，并加了 EP_Generic，用来阻止 IN 右侧带入任何排序规则和亲和性信息。当时的触发场景正是 IN 右侧的 COLLATE 覆盖了左侧的隐式排序规则（测试 in4-5.1）。trunk 源码里已经找不到 EP_Generic。
3. **内部不一致**：一个元素和"同一个元素写两遍"按定义等价，结果却不同。

## 发现过程（论文用）

这个 bug 不是长跑里的蜕变判定器直接报出来的，而是在**扩充 e-graph 改写规则的流程**里发现的：

1. 规则扩张第 6 轮（2026-10-08），由 LLM agent 为新加入的 IN 列表节点（in1/in2/in3）提出改写规则。agent 在说明中指出：凭对 parse.y 的记忆，单元素 IN 会被改写成 `x = +a`，而 `+a` 可能保留 a 上的显式 COLLATE。它把 6 条"单元素 ↔ 多元素"互转的规则标成有风险，并写明"如果第一关在这里给出反例，先当作可能的 SQLite 不一致来查"。
2. 第一关检查工具 `coverage/sqlite/rulegate/rulegate.py` 会在真 SQLite 上，对变量的各种取值（字面量、各种亲和性和排序规则的列）逐一判定规则两侧是否相等。最初它只有"带显式 COLLATE 的**列**"这种来源，所以这 6 条规则全部通过了。
3. 给 rulegate 加上"带显式 COLLATE 的**字面量**"这种来源后，这 6 条规则全部失败，反例全是 `'abc'` 对 `('ABC' COLLATE NOCASE)` 这个形状。
4. 人工在 trunk 上复现、核对文档、查到 2014 年的修复、在源码中定位根因，并在论坛和 bug 区查重。

**渠道**：规则验证本身就是一种差分测试，比较的是"按文档成立的等式"与 SQLite 的实际行为。这次线索来自 agent 的源码知识，系统性的证据来自 rulegate。

**启示**：rulegate 的失败不一定说明规则错了。如果反例违反的是文档明确规定的语义，或者两个按定义等价的写法给出了不同结果，就应该当作 SQLite bug 候选来分诊，而不是直接丢掉。

这 6 条规则没有放进服务。否则长跑会持续刷出这个已知问题。

## 查重（2026-10-08）

- 论坛和 bug 区按 "IN COLLATE single"、"IN list collating sequence"、"IN one element COLLATE" 搜索，没有相同报告。
- 相关但不同的两帖：
  - 2020-05-20 论坛帖 "Overriding collation of IN expressions"（forumpost/77bf96ea94）：讨论 COLLATE 应该写在左边，结论是官方补了文档。我们这个 bug 正好违反了那次补上的文档。
  - 2026-05-31 bug 区帖 "Vuln26-7: Vector IN Step-6 Collation Mismatch"：讲的是向量 IN 子查询第 6 步的排序规则不一致，是另一个问题。

## 时间线

| 时间 | 事件 |
|---|---|
| 2026-10-08 | 第 6 轮，agent 提出风险；rulegate 加入新来源后 6 条规则失败；人工确认并定位根因，完成查重和报告草稿 |
| 2026-10-08 | 用户发帖，进入版主审核（同日 12:38 UTC 查看公开列表时尚未出现） |
| 待补 | 审核通过 / 官方回复 / 修复提交 |

## 文件

- `repro.sql`：复现脚本，包括边界条件的对照
- `evidence-2026-10-08.txt`：3.53.4、trunk、3.51.0 的实际输出，以及 parse.y 和 expr.c 的源码片段
- `report-draft.md`：中英对照的报告草稿（英文部分是准备发出的正文）
