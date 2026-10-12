# 发现的 SQLite 缺陷（论文用记录）

本目录记录本项目（SQLancer + e-graph 等价改写的蜕变测试）在 SQLite 中发现、确认并对外报告的缺陷。
每个缺陷一个子目录，固定包含：

- `README.md`：现象、最小复现、版本对照、根因、发现过程、时间线、状态、论文要点
- `repro.sql`：独立的复现脚本，可直接执行 `sqlite3 :memory: < repro.sql`
- `report.*`：对外发帖的原文或草稿

只收录**已确认是 SQLite 自身问题**的条目。自家工具的假阳性和修复不在这里，在项目记忆和提交历史中。

## 总表

| # | 日期 | 组件 | 缺陷类别 | 影响 | 发现渠道 | 引入版本 | 状态 |
|---|---|---|---|---|---|---|---|
| [001](001-fts4-contentless-integrity-check/) | 2026-10-02 | FTS4 / xIntegrity | 误报损坏（integrity_check 假阳性） | 每张无内容 FTS4 表在体检时都报错 | 长跑工作负载建出的表 + 分诊 CORRUPT 时跑的 integrity_check | 3.44.0 | **已修复**：ca8de9a304（Dan Kennedy，报告后约 2 小时） |
| [002](002-in-single-element-collate/) | 2026-10-08 | 解析器 / IN 运算符 / 排序规则 | 查询结果错误（逻辑 bug） | 单元素 IN 列表使用了右侧常量的显式 COLLATE，与文档和多元素列表都不一致 | 规则扩张流程：agent 提出线索 + rulegate 在真 SQLite 上验证 | 未定位（3.51.0 起各版本均可复现） | **已修复**：53a73aa03d（drh，报告后约 6.5 小时，回归测试进 TH3） |

## 发现渠道（写论文时的分类）

1. **测试负载的副产品**（001）：长跑时 e-graph 判定器为覆盖率专门建了各种上下文表，其中包括无内容 FTS4 表。后来分诊另一起 SQLITE_CORRUPT 时，对这些库跑 `PRAGMA integrity_check`，发现每个库都报错，由此定位。2026-10-07 起，长跑会自动读取上下文中所有 integrity_check / quick_check / rtreecheck 的结果（`EGraphIntegrityCheck`），这一渠道已自动化。
2. **规则验证即差分测试**（002）：为 e-graph 补充等价改写规则时，每条候选规则都要用 `coverage/sqlite/rulegate/rulegate.py` 在真 SQLite 上，对各种取值和上下文逐一判定两侧是否相等。一条按文档语义成立的规则在 SQLite 上失败，说明实现与规格不符。这次的线索来自 LLM agent 对源码的记忆，rulegate 系统地复现了它（6 条规则全部撞上同一个反例形状）。
3. **蜕变判定器本身**（长跑中原查询与变体结果不一致）：截至 2026-10-08，在 trunk 上没有发现新的 SQLite bug。期间所有报出的不一致都已定性为自家工具的问题（见项目记忆 server-long-runs、temp-table-referee-blindspot、pipeline-audit-20261007）。在发布版 3.53.4 上，判定器能稳定检出基准里的已知 bug（4/5，见 `coverage/sqlite/bugbench`）。

## 报告经验（001 两小时内被修复的原因）

报告里写清了：版本对照（找到引入版本）、对照组（只在"无内容 + FTS4"组合上出现）、源码定位（具体函数）、文档依据（哪一节允许或不允许）、同类先例（已被修复的兄弟问题）。002 的草稿按同一结构写成。

## 对外发帖

地点：https://sqlite.org/bugs/forumnew（先点 Preview，Submit 才能点；新帖进入版主审核）。由用户本人发帖。
