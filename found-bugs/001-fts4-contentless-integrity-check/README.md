# 001 无内容 FTS4 表自 3.44.0 起在 integrity_check 中必然报错

- **状态**：已确认并修复。修复提交 ca8de9a304（GitHub 镜像 cb5651e），作者 Dan Kennedy；新增回归测试 `test/fts4content.test` 14.x，测试里的表名就沿用了报告中的 `a`。
- **报告帖**：https://sqlite.org/bugs/forumpost/442c6e4a5af8adc63c91e5eebb980e7405d8f859538f4897231e7b5617e13be8
  - 标题："PRAGMA integrity_check fails on every contentless FTS4 table since 3.44.0"
  - 2026-10-02 12:22 UTC 发帖，同日 14:34 UTC 官方回复已修复，状态 Resolved
- **组件**：FTS3/FTS4 的 xIntegrity 实现（`fts3IntegrityMethod` → `sqlite3Fts3IntegrityCheck`）
- **类别**：完整性检查误报（健康的数据库被报成损坏）

## 现象

```sql
CREATE VIRTUAL TABLE a USING fts4(x, content='');
PRAGMA integrity_check;
-- 3.43.2:          ok
-- 3.44.0 .. trunk: unable to validate the inverted index for FTS4 table main.a: SQL logic error
```

空表、写入数据后、quick_check 结果都一样。MATCH 查询正常。升级场景：用 3.43.2 创建并体检通过的库文件，文件不做任何改动，用 3.44.0 及以后版本打开，体检就失败。

## 版本对照

| 用例 | 3.43.2 | 3.44.0 | 3.53.4 | trunk 2026-10-01 (99aab2c992) | 修复后 ca8de9a304 |
|---|---|---|---|---|---|
| A 空的无内容 FTS4，integrity_check / quick_check | ok | error | error | error | ok |
| B 写入一行后 integrity_check | ok | error | error | error | ok |
| C FTS4 自带的 'integrity-check' 命令 | error | error | error | error | ok |
| D 对照：普通 FTS4 | ok | ok | ok | ok | ok |
| E 对照：外部内容 FTS4 (`content='src'`) | ok | ok | ok | ok | ok |
| F 对照：无内容 FTS5 | ok | ok | ok | ok | ok |

引入版本是 3.44.0：这一版加入了 xIntegrity，把 FTS 的自检接进了 PRAGMA integrity_check。用例 C 说明命令本身的局限早已存在，只是以前不会在每次体检时被调用。

## 根因

`sqlite3Fts3IntegrityCheck()` 会对内容表执行 `SELECT <列>`，把原文重新分词后和倒排索引比对。无内容表没有内容表，prepare 失败，返回的 SQLITE_ERROR 被当成了完整性问题上报。FTS5 遇到无内容表会跳过这一比对。

官方的修复给 `sqlite3Fts3IntegrityCheck` 加了 `bContentless` 判断：无内容表跳过内容表校验和的比对，只检查索引是否可读。这与报告中的分析一致。

## 文档依据

- fts3.html 6.2.1 节列出了无内容表不支持的操作（UPDATE、DELETE、读取非 docid 列、snippet、offsets），其中没有完整性检查。
- vtab.html 规定 xIntegrity 只应在表内容有问题时报错。
- 先例：只读库上 FTS 的 xIntegrity 误报，当天由 b855886c4c 修复，并回补到 3.45 分支。

## 发现过程（论文用）

1. 2026-10-01 的长跑中，某个工作线程报了 SQLITE_CORRUPT（`Freelist: size is 465 but should be 462`）。
2. 分诊时对长跑留下的各个库跑 `PRAGMA integrity_check`，发现**每个库**都另有一条 FTS4 报错。这些库里都有 e-graph 判定器为覆盖率专门建的上下文表 `egraph_fts4_desc`，即 `fts4(a, order=DESC, content='')`。
3. 最小化后确认与 `order=DESC`、表中数据都无关，只要是无内容 FTS4 就会触发。随后逐版本二分出引入版本是 3.44.0，在源码里定位到具体函数，并在文档、论坛和源码时间线上查重。
4. 原来那起 CORRUPT 本身没能复现，不能归因，也未报告。

**渠道**：测试负载（判定器上下文里建出的特殊表）+ 分诊时的完整性检查。这是测试工作负载产生的副作用，不是蜕变判定直接报出的。此后长跑会自动读取所有完整性检查的结果（`EGraphIntegrityCheck`，2026-10-07），这一渠道已自动化。在 3.53.4 上跑基准时，它会照常报出这个已修的问题，可作为该渠道的阳性对照。

## 时间线

| 时间（UTC） | 事件 |
|---|---|
| 2026-10-01 | 长跑出现 CORRUPT，分诊时发现 FTS4 报错 |
| 2026-10-02 上午 | 版本二分、源码定位、文档核实、全渠道查重（论坛 4 组关键词、源码时间线 "contentless" 29 个提交、"xIntegrity" 15 个提交，均无人提过） |
| 2026-10-02 12:22 | 发帖 |
| 2026-10-02 14:34 | Dan Kennedy 回复已修复 ca8de9a304，状态 Resolved |
| 2026-10-02 | 本地编译 ca8de9a304 验证 A/B/C 及升级场景全部 ok |

## 文件

- `repro.sql`：复现和对照组
- `report-draft.html`：发帖时用的中英对照稿（英文部分就是实际发出的正文）
