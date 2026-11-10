# 003：IS 右侧的空 IN 列表被当作 FALSE 布尔词（真值特例），而非文档所述的常量 0

- **现象**：`x IS (y IN ())` ≡ `x IS FALSE` ≡ NOT x，而不是 `x IS 0`。`'' IS (0 IN ())` = 1，`'' IS 0` = 0。IS NOT 侧同样分叉。
- **最小复现**：`SELECT '' IS (0 IN ());` → 1（期望 0，对照 `SELECT '' IS 0;` → 0）。完整矩阵见 `repro.sql` 与 `report-draft.md`。
- **版本对照**：3.51.0 / 3.54.0 / 3.55.0（ddd6db1c）/ 最新 master（2026-11-04）全部复现。空 IN 简化自 2002 年 2.7.3（changelog 2.7.3 条目）即存在；changelog 此后 24 年无相关条目；论坛全文搜索无先例。
- **根因**：parse.y:1497–1518 空 IN 简化注释写 "simplify to constants 0 (false) and 1 (true)"，实现却经 `sqlite3ExprIdToTrueFalse` 造 TK_TRUEFALSE 布尔词；布尔词触发 IS 真值特例。修复方向：改产 TK_INTEGER 0/1（其余上下文行为不变）。次要分叉：死边含函数时走 `expr AND false` 聚合安全分支、语义反而"正确"。
- **发现过程**：长跑 20261031（EGRAPH oracle 差分测试 trunk，远程机）现场包 repro/0001：基查询 `TRIM(rt1.c1, rt1.c1) IS (-693048757 IN ())`（source: RANDOM_GENERATED_1_3），egraph 按 `x IS 0` 改写为 `(0 = TRIM(...)) IS TRUE`，行数 10 vs 0 报不一致（掩码无关）。11-09 拉回现场包，bisect 定位第三合取式；11-10 语义探测 + 文档对照定性。**诚实口径：oracle 报的不一致本身是自家求值器假阳性；SQLite 行为自洽，缺陷是文档/实现不一致，在假阳性分诊中定性。**发现渠道归类 = 渠道 3（蜕变判定器），但论文须注明"surfaced by the oracle, identified by triage analysis"。
- **时间线**：2026-11-01 现场包产出（长跑中）→ 11-08 长跑被杀（~92%）→ 11-09 拉回本机、bisect → 11-10 定性 + 草稿 → **11-10 13:17:50 UTC 发帖（artifact fb2e7f646b，Awaiting Moderator Approval）**。
- **状态**：**已提交待审核**（帖：https://sqlite.org/bugs/forumpost/fb2e7f646b ）。
- **论文要点**：第三类渠道（判定器）首个定性为 SQLite 自身问题的条目；类别是语义/文档不一致而非崩溃或错误结果；egraph 侧修复（求值器对 `IS + 空 IN RHS` 按 `x IS FALSE` 建模或禁改）与上游修复相互独立。
