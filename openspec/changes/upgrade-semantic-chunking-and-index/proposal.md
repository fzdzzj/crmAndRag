# 提案：语义切分与索引重构（块头 + 大小块 + 重建入库）

> 变更 ID：`upgrade-semantic-chunking-and-index` ｜ 能力域：`document-chunking` ｜ 序列：第 4/5 个 ｜ 前置：提案 2/3 验收后启动（避免重嵌入两遍）
> 对应 rag-kb 方案：**02 Semantic Chunking + 05 Chunk Header + 03 Small-to-Big**；rag-kb 路径 B：索引侧改造在度量与召回侧稳定后进行。

## Why

**rag-kb 方案 02 的解决对象**：固定硬切导致上下文丢失与幻觉——切在语义中间，块内话题断裂。**方案 05**：碎片块缺乏全局视野，块脱离文档上下文后检索语义漂移。**方案 03**：小块定位准、大块上下文全，检索单元 ≠ 生成单元。

**代码现状**（已核）：

1. `DocumentService`：`CHUNK_SIZE=320 / CHUNK_OVERLAP=40` 固定字符硬切（L29-L31）——02 所指的"硬切"原样存在；切分参数无 DynamicConfig 键。
2. 嵌入输入 = 裸 `chunk.text()`（`DocumentIngestionService.ingest()` L90）——无文件名/类目/页码前缀，05 所指的"碎片块无全局视野"。
3. 检索什么喂什么（`buildContext` 直接拼命中块）——无父子粒度，03 缺失。
4. **阻塞依赖**：02/05 都改嵌入输入 → 存量文档必须重嵌入；当前无按 documentId 的幂等重建机制，直接上线会造成新旧块语义混杂。

**为什么排第 4**（用户已确认）：改切分是上游变更、需重嵌入全量文档（API 成本 + 周期），且基线归因（提案 1）应先确认召回失败中"切分导致"的占比；提案 2/3 的召回与上下文改造不动嵌入，先行收益更快。

## What Changes

### 1. 语义切分策略（方案 02）
- 切分器策略抽象：`rag.chunking.strategy = fixed | semantic`（DynamicConfig，默认 fixed = 现行为回退）。
- semantic：按段落/标题/转折词边界切分，段长上限仍受 `rag.chunking.max-chunk-size` 约束；**`pageNo`/`rowIndex` 锚点语义 MUST 保留**（D15 页级来源引用是冻结行为）。

### 2. 块头注入（方案 05）
- 嵌入文本 = `【文件名 | 类目 | 页码】` + 块文本；**展示文本分离**：DB `chunk_text`、`SourceReference.excerpt` 仍存原文（引用可读性不受前缀污染）。
- 嵌入/展示分离只在摄取侧构造，检索链路无感知。

### 3. 双粒度索引（方案 03 Small-to-Big）
- 小块（检索单元，嵌入）+ 父块（生成单元）。
- **设计决策——父块建模**：推荐 DB 增列 `parent_chunk_id`（Flyway **V23**，语义切分时按"逻辑段"生成父块记录），命中子块 → 展开父块进上下文；`SourceReference` 仍指子块（锚点精度不降）。
- 备选：父块=页（pageNo 分组，零迁移）——txt/md 单页文档退化为全文，不采用为默认。
- 与提案 3 衔接：`ContextBuilder` 的邻居拼装在双粒度下升级为父块展开（保留邻居模式开关）。

### 4. 幂等重建入库（reingest）
- 按 documentId 重跑：清向量 → 清 chunk 行 → 重切分 → 重嵌入 → 重写 DB；失败清理复用 `markFailed` 语义（不留半量）。
- 触发：超管运维入口（管理端点或 runner），走平台审计。
- 存量迁移：全量 reingest 跑批（状态机复用 `batch_task` 或独立 runner，二选一在任务里定）。

## Impact

- **规范**：`specs/document-chunking/spec.md`（ADDED Requirements，全新能力域）。
- **代码**：`knowledge/document/`（切分器策略、块头构造、父块生成、reingest runner）；`knowledge/retrieval/ContextBuilder`（父块展开）。
- **DB**：Flyway **V23**（`document_vector_chunk.parent_chunk_id` 等列）。
- **配置**：`rag.chunking.strategy` / `rag.chunking.max-chunk-size` / 父块展开开关。
- **成本**：存量文档全量重嵌入（模型 API 调用 × 现有 chunk 总量）——**需用户在执行前确认成本**。
- **契约**：`VectorRecord`/`SourceReference` 不变（metadata 增字段属开放 Map）。

## 风险

- 重嵌入成本与停机窗口 → reingest 幂等可分批；跑批状态机沿用既有"失败可恢复"语义。
- 语义切分回归风险 → `strategy=fixed` 一键回退现行为；切分单测含锚点保留断言。
- 父块展开稀释相关性 → 基准 citationPrecision/要点覆盖不回退做闸门。
- 入库耗时上升 → 语义切分是纯本地计算，增量可忽略；重嵌入才是成本项。

## Non-Goals

- 不做 Document Augmentation 衍生问题（提案 5 范围，依赖本提案的 reingest 机制）。
- 不做 Hierarchical Index 摘要层（方案 14，触发条件未到，见提案 5 台账）。
- 不动文档解析格式支持（pdf/txt/md/xlsx 现有集合不变）。
