# 提案：上下文压缩与邻居增强

> 变更 ID：`add-context-compression-and-enrichment` ｜ 能力域：`rag-context` ｜ 序列：第 3/5 个 ｜ 前置：`add-rag-quality-baseline`（基线对照）；与提案 2 无代码冲突可并行
> 对应 rag-kb 方案：**10 Context Compression + 04 Context Enriched**；S11 生产级矩阵四件套之三。

## Why

**rag-kb 方案 10 的解决对象**：召回内容过载、Token 浪费、注意力分散。**方案 04**：命中块缺少前后文承接，长文推理断链。

**代码现状**（已核）：

1. `KnowledgeRetrievalServiceImpl.buildContext()`（L199-L209）把 top-K 块**全文拼接**直喂 prompt，无任何压缩/筛选——06/07 号大文档场景 token 成本与注意力分散都在放大。
2. 切片已有 `chunkIndex`/`pageNo` 锚点且 `document_vector_chunk` 表可查（`DocumentVectorChunkEntity`），**邻居块零重嵌入即可实现**——方案 04 的最低成本落法。
3. 消费方 `AiChatKnowledgeRetrievalService.retrieve()` 硬编码 `topK=4`（L46），未消费 DynamicConfig 的 `rag.retrieval.topK` 键。
4. 平台已有 Token 计量治理（`TokenUsageRecorder` 契约）——压缩的计量挂点现成，不新造治理。

**收益链**：邻居增强提升答案连贯性（答案要点覆盖/一致性口径可量）；压缩降低 token（基准报告有 token 字段可量）——两者都以提案 1 基线做"不回退"验收。

## What Changes

### 1. 邻居上下文增强（方案 04）
- 新组件 `ContextBuilder`（knowledge/retrieval 内部）：对每个命中块，按 `documentId` + 相邻 `chunkIndex`（同页优先）从 `document_vector_chunk` 取前/后邻居，拼装为 `[前置] [命中] [后置]` 结构。
- 邻居**只进上下文、不进 `SourceReference`**（引用与跳页锚点仍指命中块，citationPrecision 口径不受污染）。
- 开关：`rag.context.neighbors = 0 | 1`（默认 1，取一侧；0 = 关闭回退现行为）。

### 2. 上下文压缩（方案 10）
- `Compressor` 抽象：默认实现 = **确定性规则压缩**（按 token 预算截断/保锚点句、去重复段）；可选实现 = LLM 要点化压缩（`ModelProvider`，失败回退规则）。
- token 预算：`rag.context.token-budget`（DynamicConfig）；超预算触发压缩。
- 计量：压缩用 LLM 调用挂 `TokenUsageRecorder`（补齐计量盲点，对齐 HANDOFF 教训）。

### 3. 引用编号完整性
- 压缩/邻居拼装后，上下文 `[n]` 编号与 `sources` 列表的对应关系 MUST 不变（答案内联引用与 citationPrecision 依赖此映射）。

### 4. 消费侧参数化
- `AiChatKnowledgeRetrievalService` 的硬编码 `topK=4` → 走 `rag.retrieval.topK`（DynamicConfig 已有键，补消费方）。

## Impact

- **规范**：`specs/rag-context/spec.md`（ADDED Requirements，全新能力域）。
- **代码**：`knowledge/retrieval/` 新增 `ContextBuilder`/`Compressor`（+LLM 实现）；`server/ai/AiChatKnowledgeRetrievalService` 参数化。
- **配置**：新键 `rag.context.neighbors` / `rag.context.token-budget` / `rag.context.compressor.mode = rule | llm`（默认 rule）。
- **契约**：`KnowledgeRetrievalPort.RetrievalResult`（context/sources 结构）不变。
- **DB**：无迁移。

## 风险

- 邻居块引入噪声（跨话题邻居）→ 只取紧邻 chunkIndex、开关可关；基准 answerConsistency 不回退做闸门。
- LLM 压缩增延迟/成本 → 默认 rule 模式；llm 模式失败回退规则链。
- 压缩误删承重句 → 规则压缩保留含锚点词/数字句；验收含要点覆盖不回退。

## Non-Goals

- 不做 Sentence Window（方案 09）的独立实现——邻居增强（04）与其同机制，若基准显示需更细粒度滑窗，作为本能力域后续演进。
- 不改召回/重排（提案 2 范围）、不改切分（提案 4 范围）。
- 不做 prompt 模板重构（生成侧属 ai-assistant 既有能力域）。
