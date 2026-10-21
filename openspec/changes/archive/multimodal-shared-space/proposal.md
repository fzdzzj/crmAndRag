# 提案 · multimodal-shared-space（多模态真共享空间升级）

## 背景与动机

上一卡 `add-kb-image-slice-retrieval` 采用**方案 B 文本降维**：PDF 内嵌图片 → VLM 描述文本 → 文本切片入
既有 chunk/embed 链路，`sourceType=IMAGE` 仅作回显标记。看板 §3 明确把「多模态真共享空间」登记为待立项
升级卡，触发条件 = 文本降维暴露出跨模态召回缺口。**Owner 2026-10-21 拍板跳过灰度真跑、直接强推立项**，
并锁定四项选型（见下）。

## 现状锚点（三源实读，2026-10-21）

- **摄取侧零视觉向量**：`PdfImageSliceIngestService#toChunk`（L222-235）只把 VLM **描述文本**写成
  `DocumentChunk`，走单一文本 embedding（`EmbeddingService#embed`）。
- **查询侧 imageVector 是「文本降维」非视觉**：`AiChatImageUnderstandingService#generateImageVector`
  （L196-224）把图片「摘要+OCR+实体+聚焦+问题」**拼接成文本后 `provider.embed`**——与文本切片同一 embedding
  空间，不是真视觉向量。
- **向量库单 collection 写死**：`QdrantVectorStore` 所有操作用 `properties.getCollection()`
  （`knowledge_chunk`）；但 `QdrantProperties` 已有 `crmEntityCollection` 双集合先例，
  `CrmEntityVectorStoreConfiguration` 已示范「派生 properties 指向第二集合 + 包装类型避免裸 bean 歧义」的成熟范式。
- **检索侧接口/融合已存在**：`RetrievalQuery.imageVector` + `KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases`
  （L243-256）的 imageVector 路由 + `RetrievalConfigResolver#fuseRoutes`（文本 0.7 + 图片 0.3）均已在，
  缺的只是「摄取侧生产真视觉向量」与「视觉向量独立落库」。
- **`ModelProvider` 无视觉 embed 接口**：只有 `embed(EmbeddingRequest)`（文本，DashScope `EmbeddingModel`）
  与 `vision(Prompt)`（多模态文本输出），无「图 → 视觉向量」入口。

## 目标（真跨模态共享空间）

引入真视觉 embedding，让「用户上传的图片」与「知识库内嵌图片」在**同一个视觉向量空间**里可互相召回，
与既有文本空间并行（双空间 + 跨模态融合），实现真 query-by-image。

## Owner 四项拍板（2026-10-21，缺一不可）

1. **视觉 embedding = 真视觉（CLIP 式图→向量）**，不再文本降维。
2. **向量存储 = 独立 image collection**，维度可与文本不同。
3. **真跑 = 授权**（视觉 embedding 真实外呼，`RAG_VISION_EMBED_REAL` opt-in）。
4. **模型来源 = DashScope 文本-图像联合 embedding 端点**，与现有 dashscope 栈一致、零新依赖。

## 方案骨架

1. **视觉 embed 接口**（新）：`ModelProvider` 增 `embedImage`（或等价多模态 embedding 入口），
   `ModelProviderImpl` 走 DashScope 文本-图像联合 embedding 端点；复用 `guard.call("model-vision-embed", ...)`
   超时/熔断范式与 `TokenUsageType.EMBEDDING` 计量。
2. **独立 image collection + 维度**：`QdrantProperties` 增 `imageCollection="knowledge_image"` +
   `imageDimensions`（可能 ≠ 文本 `dimensions`）；新增 `ImageVectorStore` 包装类型（对齐
   `CrmEntityVectorStore` 范式），建 `ImageVectorStoreConfiguration`（qdrant 态派生 properties、in-memory 态独立实例）。
3. **摄取侧生产视觉向量**：`PdfImageSliceIngestService` 落 PNG 时并行 `embedImage(png)` → 视觉向量 +
   asset 引用写入 image collection（`VectorRecord` 维度 = `imageDimensions`）。
4. **查询侧真 query-by-image**：`AiChatImageUnderstandingService#generateImageVector` 改为 `embedImage`
   （图片字节直接入视觉向量，不再文本拼接）。
5. **跨模态融合**：`retrieveFromKnowledgeBases` 的 imageVector 路由改查 image collection；
   `fuseRoutes` 适配「文本空间 + 视觉空间」异构候选（fusionKey 去重策略需定夺：视觉切片与文本切片不同源，
   不得误合并）。
6. **开关默认关**：视觉摄取 `rag.retrieval.image-vector-ingest.enabled` 默认 false（关 = 零视觉向量、
   零真外呼，行为与升级前一致）；成本类键按既有口径不进 12 键 `KbRetrievalStrategyWhitelist`。

## 边界与不改动面

- **不改**既有文本 embeddings/检索/`SourceReference` 十字段契约（冻结契约改动需另走受控解冻）。
- **不动** `crm_entity` collection 与 `CrmEntityVectorStore`（业务实体向量库与本卡无关）。
- **不删**文本降维描述切片（方案 B 产物保留为文本召回路，与视觉路并行）。

## 档位判定

**A 档（最高）**：embedding 模型选型 + 向量库 schema（新 collection/维度）+ 契约面（`ModelProvider`
冻结契约扩接口需受控解冻）+ 真跑（视觉 embedding 真实外呼）。必须独立复核四判据（迁移/权限/真跑/冻结契约）。

## 验收判据

1. 摄取侧图片生产真视觉向量入独立 image collection，维度与文本 collection 可不同。
2. 查询侧 imageVector 是真视觉向量，query-by-image 能召回知识库内相似图片切片。
3. 视觉摄取默认关（开关 false = 零真外呼零落盘，默认行为与升级前逐字节一致）。
4. 真跑验证视觉召回质量（`RAG_VISION_EMBED_REAL=1`）；独立复核四判据全过。