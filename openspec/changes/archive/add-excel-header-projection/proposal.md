# 提案：Excel 表头投影到数据行（TB-01 / TB-10 检索侧小切口）

> 变更 ID：`add-excel-header-projection` ｜ 能力域：`document-chunking` ｜ 序列：RAG 质量闭环第二单（检索侧，表格常理化）
> 来源：`docs/rag-quality/baseline-v2-anchor.md` TB-01 / TB-10 零召回；用户「先优化其他」（引用对齐复测挂起，不并行重开任务组 4）。

## Why

v2 锚点 13 条 TABLE 里，**只有 TB-01、TB-10 recall=0**，其余 11 条（含数值区间 TB-08、聚合比较 TB-11）recall=1。不是「表格检索全坏」，是两类查询打在「行文本缺列名」上。

| 已验证事实 | 证据 |
|---|---|
| TB-01「第三季度各区域销售额」recall=0 | 黄金 `sales-q3-华东` + `sales-q3-华北` 未进 top-5 |
| TB-10「计划工时超过 2 人时的维保项」recall=0 | 黄金 `maint-xr500-q` + `maint-kq9000-m` 未进 top-5 |
| TB-04「XR-500 季度维保需要多少工时」recall=1 | 查询带了行内词 `XR-500` / `季度` |
| TB-08「Q3 销售额在 800 到 1000 万」recall=1 | 查询带了行内词 `Q3`，不是因为系统会做区间计算 |
| Excel 行文本 = 单元格值 tab 拼接，**不含列名** | `DocumentService.normalizeRow` 只 `sorted keys → strip → tab join` |
| 维保数据行形如 `XR-500\t季度\t4\t2\t...` | 表头「计划工时(人·时)」在第 1 行独立成块；数据行没有「计划工时」「人时」 |
| 销售数据行形如 `华东\tQ3\t1280\t112%` | 没有「销售额」「第三季度」（单元格是 `Q3`） |
| v2 锚点 run=V1 | 纯向量、sparseOff；表头投影仍能改嵌入文本，不依赖开稀疏路 |

**当前假设**（基线 JSON 不存 retrieved chunkIds）：TB-01/TB-10 的向量邻居被其他含「销售额 / 维保」的散文块占满，因为数据行缺列名、数字是裸 `4`/`1280`。把表头投影进每一行后，行块与查询共享「计划工时 / 销售额 / 区域」，top-5 应能装下黄金行。若复测仍为 0，再立项数值谓词过滤（本单不做）。

本单刻意不做 Text-to-SQL / 结构化元数据过滤——那是大提案。表头投影是摄取常理（LlamaIndex / Unstructured 行块都带列名），改动面只在 `parseExcel`。

## What Changes

### 1. Excel 解析：符合表头启发式时，把列名投影进数据行
改 `DocumentService.parseExcel` / `normalizeRow`（Javadoc 标「add-excel-header-projection 任务 1.1」）：

- **启用条件**（同时满足，否则与现行为逐字一致）：
  1. 首行非空单元格数 ≥ 2；
  2. 首行每个非空单元格长度 ≤ 32（表头不是长句）。
- **启用后**：
  - 首行当列名，**不再作为独立 chunk 入库**（列名已在每条数据行里，单独索引表头会占 top-K）；
  - 后续行序列化为 `列名：值`，空值省略，列之间仍用 tab；
  - 列名为空时回退 `列{1-based序号}`；重名列名加 `_2` 后缀。
- **不启用**：单列长句表（现有 `semanticStrategyPreservesRowIndexAnchorsOnExcel`）保持现状。
- `rowIndex` 仍为工作表原始行号（1 起，含表头行计数），D15 锚点不变。
- 不改 PDF / md / txt，不改 `ChunkingStrategy`，不改 `SourceReference`。

示例（维保表数据行）：

```
设备型号：XR-500	维保周期：季度	计划工时(人·时)：4	参与人数：2	...
```

### 2. ¥0 单测
- `DocumentServiceTest` 新增多列表头用例：数据行文本含「计划工时」，表头行不单独成块，`rowIndex` 仍是原始行号。
- 既有单列 Excel 锚点单测必须继续绿（启发式不触发）。
- `RagBenchmarkDataPreparerTest` 增补：解析 `maintenance-schedule.xlsx` / `regional-sales-q3.xlsx` 后，黄金行索引文本含列名「计划工时」或「销售额」（GOLD 标记仍剥离）。

### 3. 真基准复测（授权节点，本单可不跑）
与引用对齐任务组 4 一样：¥0 全绿后停下。授权后纯默认矩阵落 `docs/rag-quality/baseline-after-excel-header.json`，禁止覆盖 v1/v2/after-citation。验收盯 TB-01 / TB-10 recall，以及其余 11 条 TABLE 不回退。

生产已入库 xlsx **不会自动变**：需 `KnowledgeReingestRunner`（另授权，本单 Non-Goal）。评测 IT 每次重新解析 fixtures，不必 reingest。

## Impact

- **修改**：`DocumentService.parseExcel` / `normalizeRow`；`DocumentServiceTest`；`RagBenchmarkDataPreparerTest`；surefire 基线随新增单测上调。
- **不改**：检索融合 / 改写 / 压缩、54 条用例文本与黄金 id、Flyway、`init_data.sql`、动态配置键、SSE 契约。
- **挂起（不在本单）**：引用对齐 54 条复测、Docker 攒批、生产 reingest、T-14、数值谓词/Text-to-SQL。

## 风险

- **无表头的多列数据表被误投影**：启发式要求首行短单元格；若真表第一行就是数据，会把数据当列名。对策：单测覆盖「首行超长不投影」；踩到再加开关，本单不先加动态配置。
- **表头行不再入库**：只靠列名查询「有哪些列」会召不回。业务查询几乎都带行值；可接受。
- **TB-01 黄金只标了华东+华北、漏华南/西南**：投影成功后四行都可能进 top-5，recall 仍按两块黄金算——只要这两块进了就是 1.0。不改黄金、不升 SUITE_VERSION。
- **数字比较本身仍不会做**：`超过 2` 不会在检索期过滤 `4` vs `2`。本单只让行进 top-5，比较交给生成侧。若 TB-10 行进了 top-5 但 ansC 仍 0，记入复测差异，不在本单加谓词。

## Non-Goals

- 不做数值谓词预筛、结构化 metadata 过滤、Text-to-SQL。
- 不改查询改写词表（第三季度↔Q3），不默认打开稀疏路。
- 不跑引用对齐的 after-citation（仍待授权）。
- 不触发生产 reingest。
- 不改 TABLE 用例与 fixtures 内容。

## 失败场景（停下汇报）

1. 既有单列 Excel 锚点单测红。
2. 黄金对齐失败（GOLD 标记找不到）——投影不得把标记行吞掉。
3. 复测授权后 TB-01/TB-10 仍为 0，或其余 TABLE recall 回退：停下，不把启发式放宽到「任何首行都当表头」。
