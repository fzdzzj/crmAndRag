# 提案：查询侧增强（多查询 / HyDE / 衍生问题）

> 变更 ID：`enhance-query-transformation` ｜ 能力域：`rag-query` ｜ 序列：第 5/5 个（按需立项）｜ 前置：提案 1 基线归因 + 提案 4 reingest 机制（仅衍生问题依赖）
> 对应 rag-kb 方案：**07 Query Transformation（增强）+ 15 HyDE（可选）+ 06 Document Augmentation（可选）**；附 11/14 待定项台账。

## 立项触发条件（不满足则不启动）

以 `add-rag-quality-baseline` 及提案 2/3/4 后的基线归因为准：
- **07 增强**：改写失败/回退用例占可归因召回失败的比例显著（查询-文档词汇失配是主要漏召原因）。
- **15 HyDE**：存在"问题短/口语化 vs 文档书面表述"导致向量失配的用例类。
- **06 衍生问题**：入库侧反向问题能覆盖的漏召类（用户问法与文档表述差异大且改写救不回）。

三者都是**成本敏感型**增强（每查询多次 LLM/嵌入调用，或入库成本上升），默认关闭、按需启用是硬约束。

## Why

**rag-kb 方案 07**：用户不善提问、提问过窄——本项目现有 `RetrievalQueryRewriteService` 是单轮 LLM 改写（失败回退原查询），无分解、无多查询变体。**方案 15**：两端表述差异大时，先生成假设答案再匹配真证据。**方案 06**：为每段反向生成衍生问题绑定向量化，吸收"用户问法 ≠ 文档写法"的失配。

**消费的既有设施**：`ModelProvider`/`ModelCallOptions`（LLM 调用）、提案 2 的 RRF 融合（多路查询结果融合）、提案 4 的 reingest（衍生问题随重建生成）、platform async（旁路线程池，衍生问题不阻塞入库主链）。

## What Changes

### 1. 多查询生成与融合（方案 07 增强）
- `rag.query.multi-query.enabled`（默认 false）+ `rag.query.multi-query.variants`（默认 3）：改写器产出 1 原始 + N 变体（补全/拆解/视角变换）。
- N 路**并行**召回 → RRF 融合（复用提案 2 组件）→ 单一重排管线。
- 任一路失败/超时：降级为已完成路的融合；全部失败回退单查询（现行为）。

### 2. HyDE 可选模式（方案 15）
- `rag.query.hyde.enabled`（默认 **false**，幻觉误导风险 + 成本）：LLM 生成假设答案 → 嵌入 → 检索 → 结果与原查询路 RRF 融合。
- 假设答案**只用于检索**，绝不进入生成上下文（防幻觉注入）。

### 3. 衍生问题文档增强（方案 06）
- 入库旁路（platform async 执行器）：每块生成 2–3 个反向问题（`ModelProvider`）→ 嵌入 → 向量记录关联原块 chunkId（命中衍生问题 → 回原块，`SourceReference` 指原块）。
- 失败不阻塞入库主链（衍生问题缺失 = 该块退化为普通块）；随 reingest 重跑生成。
- token 计量挂 `TokenUsageRecorder`。

### 4. 待定项台账（记录进本 change，不实现）

| 项 | 触发条件 | 届时落点 |
|---|---|---|
| 11 Feedback Loop | 产品提供点赞/点踩信号（`ai_message.payload` 可挂） | 新 change：反馈 → 基准集进化 |
| 14 Hierarchical Index | 单库 chunk 量级超阈值（如 >10 万）或出现跨库路由需求 | 新 change：摘要层 + 路由 |

## Impact

- **规范**：`specs/rag-query/spec.md`（ADDED Requirements，全新能力域）。
- **代码**：`knowledge/retrieval/`（多查询编排、HyDE）；`knowledge/document/`（衍生问题旁路）；无 DB 迁移（衍生问题走向量 metadata 关联）。
- **配置**：`rag.query.multi-query.enabled/variants` / `rag.query.hyde.enabled` / `rag.query.derived-questions.enabled`（默认全关）。
- **成本**：启用后每查询 LLM 调用 ×N、嵌入 ×N；衍生问题入库成本 ×块数。
- **契约**：不变（全部走 `ModelProvider`/`DynamicConfigService` 既有接口）。

## 风险

- 多查询放大延迟与 token → 并行 + 预算闸门；基准 TTFT/token 不回退做验收。
- HyDE 假设答案误导检索（错误假设 → 错误向量）→ 默认关 + 只用于检索不进生成。
- 衍生问题质量参差 → 数量上限可配；失败静默降级为普通块。

## Non-Goals

- 11/14 本提案不实现（见台账触发条件）。
- 不做 12 Self-RAG / 13 KG RAG / 17 CRAG（openspec/project.md 总表已记录理由）。
- 不改既有单轮改写的行为（作为多查询关闭时的回退路径原样保留）。
