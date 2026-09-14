# 提案：RAG 基准集扩容（18 → 54 条，SUITE_VERSION 2.0 + 新锚点授权节点）

> 变更 ID：`expand-rag-benchmark` ｜ 能力域：`rag-quality` ｜ 序列：RAG 质量线深化（ladder-report 明确 18 条量级饱和）
> 来源：`docs/rag-quality/ladder-report` 结论——18 条基准集对多数指标已达统计量级饱和上限，无法兑现"优化提升 X%"量化主张；扩容是后续任何检索优化有说服力的前提。

## Why

1. **量级饱和**：18 条（TEXT 5 / TABLE 3 / IMAGE 2 / LEXICAL 6 / EDGE 2）跑出的 recall@5 波动 ±1 条即 ±5.6%，单次涨跌无法区分真实提升与噪声；扩到 54 条把单条权重降到 1.9%，指标才有判别力。
2. **覆盖面偏窄**：现有查询多为"单跳直查"，缺同义改写、跨 chunk 关联、数值区间、多约束组合等真实用户难度面。
3. **优化主张需要锚点**：混合检索/压缩/切分各提案的"不回退"对照都要落在基准上，集太小则对照失真。

## What Changes

### 1. 评测语料扩容（fixtures 6 → 11 个文档）
新增 5 个 CRM 业务向语料（`src/test/resources/rag-quality/fixtures/`，含 `【GOLD:占位id】` 标记，与既有格式一致）：
- 客户管理 SOP（`customer-sop.md`，流程类文本 + 嵌套步骤）
- 产品价格与折扣政策（`pricing-policy.md`，数值/条件分支密集）
- 区域销售政策问答（`regional-policy.txt`，条款型）
- 服务平台 SLA 条款（`sla-terms.md`，表格 + 条款混排）
- 设备维保周期表（`maintenance-schedule.xlsx`，多行多列表格）
语料内容必须自洽（编号/人名/数值无冲突），黄金标记密度与既有 fixtures 相当。

### 2. 查询集扩容（18 → 54 条，SUITE_VERSION 1.0 → 2.0）
`RagBenchmarkSuite` 扩容配比与覆盖面（合计 +36，用户确认分布 17/13/6/14/4）：
- TEXT 5→17：+12（同义改写 ×4、跨 chunk 关联 ×4、多条件组合 ×4）
- TABLE 3→13：+10（多列交叉 ×4、数值区间 ×3、聚合比较 ×3）
- IMAGE 2→6：+4（客户建档流程图 / 折扣审批决策树 / 服务台架构图 / 维保流程图 图注各 1）
- LEXICAL 6→14：+8（编号精确 ×3、人名 ×3、版本号 ×2）
- EDGE 2→4：+2（近义闲聊 ×1、知识库近邻主题误导 ×1）
每条新用例登记 `expectedChunkIds` 占位 id 与要点词；`SUITE_VERSION` 升 "2.0"（历史 baseline-v1.json 锚定 1.0 不可比，跨版本比较先核对版本）。

### 3. ¥0 验证链（无需授权，先全绿）
- `RagBenchmarkDataPreparerTest`：新语料黄金对齐单测（占位 id 全对齐、解析成功）；
- `ChunkNgramRecallGateIT`（免外呼门禁）：新旧全集 ngram 召回质量闸门；
- surefire 全绿，基线上调同步 ci.yml。

### 4. 真基准新锚点（授权节点：停下等用户确认）
- 跑 `RagRealRetrievalBenchmarkIT`（`RAG_BENCHMARK_REAL=1` + DASHSCOPE_API_KEY，54 条全程真外呼）产出 `docs/rag-quality/baseline-v2.json`；
- **成本预估**：旧 18 条单跑 ≈ 18×4 次 LLM 调用（改写/嵌入/生成/判卷）；54 条 ≈ 3 倍调用量，token 量级同比例，估算成本 ¥个位数（以实际账单为准）；
- **执行边界**：任务组 3 全绿后停下，向用户报成本预估并等授权，授权后才跑任务组 4——本提案不含未授权外呼。

## Impact

- **新增**：5 个 fixture 语料文件；`RagBenchmarkSuite` +36 条用例（改 main 源码，中文 Javadoc 标注「expand-rag-benchmark 任务 x.x」）。
- **修改**：`RagBenchmarkDataPreparerTest`（对齐校验扩容）；ci.yml 基线（surefire 计数随新增单测上调）。
- **不改**：既有 6 个 fixtures、既有 18 条用例文本与黄金 id、`RagRealRetrievalBenchmarkIT` 管线、检索/分块/重排任何主代码。

## 风险

- **新语料与既有冲突**：人名/编号/数值跨文档冲突会干扰 LEXICAL 精确召回——语料设计必须自洽，单测黄金对齐兜底；
- **SUITE_VERSION 跨版本误比**：v1 锚点与 v2 锚点不可直接比较——报告版本字段已在（Report 含 SUITE_VERSION），文档显著标注；
- **成本超预期**：54 条全真外呼若单条异常重试会放大调用量——授权节点前先报预估，跑时观察 token 计量。

## Non-Goals

- 不改检索管线/分块/重排任何代码（那是用新基准去度量的对象，不是本提案的改动面）；
- 不做多查询/HyDE/生产语料重评（挂起项，另行授权）；
- 不动 baseline-v1.json 历史锚点。
