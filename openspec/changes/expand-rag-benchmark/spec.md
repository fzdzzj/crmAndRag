# Spec — expand-rag-benchmark（RAG 基准集 18 → 54 条，SUITE_VERSION 2.0）

> 对应 proposal.md 的技术规格。实现以本文件 + tasks.md 为准；与 proposal 冲突时以本文件为准（proposal 已同步 17/13/6/14/4）。

## 1. 语料 fixture 规范

### 1.1 存放位置与登记

- 目录：`src/test/resources/rag-quality/fixtures/`，既有 6 个 + 新增 5 个 = 11 个。
- 新增文件：
  - `customer-sop.md`（流程类文本 + 嵌套步骤）
  - `pricing-policy.md`（数值/条件分支密集）
  - `regional-policy.txt`（条款型）
  - `sla-terms.md`（表格 + 条款混排）
  - `maintenance-schedule.xlsx`（多行多列表格，用制表符/管道分隔的**纯文本表达**，参照 `regional-sales-q3.xlsx` 现有格式）
- 登记：`RagBenchmarkDataPreparer.FIXTURES`（`src/test/java/com/slz/crm/quality/RagBenchmarkDataPreparer.java`），key 与文件名对应，注释标注「expand-rag-benchmark 任务 1.3」。

### 1.2 GOLD 标记格式（与既有 fixtures 完全一致）

- 段内嵌 `【GOLD:占位id】`，占位 id 为 kebab-case（如 `policy-004`、`maint-xr500-q`）。
- 一个段落可含多个 GOLD 标记；标记仅在段落首行附近，剥离后索引文本**不得残留任何标记残段**（`indexedTextIsCleanAndMetadataMatchesRetrievalFilter` 与 `ChunkNgramRecallGateIT` 双保险，用 GOLD_MARKER_FRAGMENT 宽松模式兜底剥离）。
- 密度：与既有 6 个 fixtures 相当，每文件 5–10 个标记。

### 1.3 自洽性硬约束

- 人名 / 编号 / 数值必须与既有 6 语料**无冲突**（LEXICAL 精确召回不串扰）：新增编号段如 `RSP-2024-04`、`CUS-2026-0088`、`WO-2026-0521` 不得与既有 `HT-2024-0889`、`BK-2024`、型号 `XR-500`/`ZB-220`/`KQ-9000` 重复或近似；人名不得复用既有语料中的人名。

## 2. 基准集规格（RagBenchmarkSuite）

### 2.1 分类分布（用户确认，锁死）

| 分类 | 既有 | 新增 | 合计 | 难度面 |
|---|---|---|---|---|
| TEXT | 5 | +12 | 17 | 同义改写 ×4、跨 chunk 关联 ×4、多条件组合 ×4 |
| TABLE | 3 | +10 | 13 | 多列交叉 ×4、数值区间 ×3、聚合比较 ×3 |
| IMAGE | 2 | +4 | 6 | 客户建档流程图 / 折扣审批决策树 / 服务台架构图 / 维保流程图 图注各 1 |
| LEXICAL | 6 | +8 | 14 | 编号精确 ×3、人名 ×3、版本号 ×2 |
| EDGE | 2 | +2 | 4 | 近义闲聊 ×1、近邻主题误导 ×1 |
| **合计** | **18** | **+36** | **54** | 单条权重 5.6% → 1.9% |

### 2.2 用例结构与 id 命名

- 用例：`new RagBenchmarkCase(id, category, query, expectedChunkIds占位id集, 要点词List, kbOn)`。
- id 前缀：`T-` / `TB-` / `I-` / `L-` / `E-` + 两位序号，新用例接续既有编号（T-06…T-17、TB-04…TB-13、I-03…I-06、L-07…L-14、E-03…E-04）。
- EDGE 用例 `expectedChunkIds` 必须为空集（E-01/E-03 kbOff、E-02/E-04 kbOn 但知识库无此主题）。
- 全部新增用例中文注释标注「expand-rag-benchmark 任务 2.1」；Javadoc 标注版本语义（任务 2.2）。

### 2.3 占位 id 与 GOLD 对齐契约

- 套件内 `expectedChunkIds` 集合 ∪ = 全部语料 GOLD 占位 id 集合，**多、少、拼错都算失败**（`standardSuitePlaceholdersAllAlignToRealFixtureChunks` 断言 Set 相等）。
- `RagBenchmarkDataPreparer.rewriteSuite` 对齐失败显式抛 `IllegalStateException` 并带出未对齐 id（既有机制，不新增）。
- 黄金片段分布健康度：44 个占位 id 至少映射 30 个不同切片（防全部挤在同一切片使 recall 失真）。

## 3. 版本与锚点

- `SUITE_VERSION = "2.0"`；v1.0=18 条（`docs/rag-quality/baseline-v1.json`），v2.0=54 条（`docs/rag-quality/baseline-v2.json`，任务组 4 授权后产出）。
- 两版**不可直接比较**：单条权重 5.6% → 1.9%；报告比较前先核对 suiteVersion。
- 任务组 4 未授权则合入不含 baseline-v2.json，HANDOFF 标注「v2 锚点待授权后补跑」。

## 4. ¥0 验证链

### 4.1 RagBenchmarkDataPreparerTest

- `expandedSuiteV2SizeAndCategoryBreakdown`：总数 54 + 五类 17/13/6/14/4。
- `standardSuitePlaceholdersAllAlignToRealFixtureChunks`：占位 id 与 GOLD 一一对应 + 分布健康度。
- `rerunProducesIdenticalChunkIdSetAndMapping`：11 份语料全部产切片（幂等）。
- `indexedTextIsCleanAndMetadataMatchesRetrievalFilter`：索引文本无 GOLD 残段 + metadata 同生产口径。

### 4.2 ChunkNgramRecallGateIT（免外呼，真 MySQL）

- LEXICAL 全量（含新增 L-07…L-14）黄金切片必须进 ngram 全文检索 top-5。
- **InnoDB FTS 坑（已实测）**：批量插入后相关度分数全 0 是 index cache 未刷盘所致；ingest 后必须 `OPTIMIZE TABLE document_vector_chunk` 强制刷盘再查询，否则闸门误红。
- Docker 不可用时记录跳过，不虚报绿。

### 4.3 surefire 基线

- `mvn -B -ntp test` 全绿；实测计数同步 ci.yml 三处（口径A 注释、check_baseline、错误提示行）。

## 5. 任务组 4 真外呼（授权节点）

- 授权前置：任务组 3 全绿 + 用户确认成本预估（54 条 ≈ 旧 18 条单跑 3 倍调用量，¥ 个位数，以实际账单为准）。
- 命令：`$env:RAG_BENCHMARK_REAL='1'; $env:DASHSCOPE_API_KEY='<key>'; mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-v2.json"`。
- 产出指标：recall@5 / precision@5 / MRR / 引用率 / 拒答正确率 / token / 时延。

## 6. 非目标（明确不改）

- 既有 6 个 fixtures、既有 18 条用例文本与黄金 id。
- 检索 / 分块 / 重排任何主代码（`RagRealRetrievalBenchmarkIT` 管线不动）。
- baseline-v1.json 历史锚点；不引入多查询 / HyDE / 生产语料重评。
