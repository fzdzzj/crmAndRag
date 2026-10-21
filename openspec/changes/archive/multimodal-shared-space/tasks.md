# 任务清单 · multimodal-shared-space

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 视觉 embedding 接口（契约受控解冻）
- [x] 1.1 `ModelProvider` 增视觉 embedding 入口（`embedImage`），`ModelProviderImpl` 走 DashScope 文本-图像联合 embedding 端点；复用 `guard` 超时/熔断 + `TokenUsageType.EMBEDDING` 计量；`contracts-frozen.md` 追加解冻登记
      补勾依据（2026-10-21 `mvn -B -ntp clean verify` BUILD SUCCESS）：`ModelProvider#embedImage(byte[], String)` 以 **default** 方法新增（默认抛 `UnsupportedOperationException`，既有实现方/测试替身零改动，不构成破坏性变更）；`ModelProviderImpl#embedImage` 委托新类 `ImageEmbeddingSupport`（RestClient 直连 DashScope 原生 `multimodal-embedding` 端点，零新依赖），熔断依赖名沿用 `model-embed`、超时沿用 `callWithTimeout`；usage → `ModelCallResult.ofVector` + `TokenUsageType.EMBEDDING` 计量。解冻登记见 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` §13 修正轮 4 + 变更日志 2026-10-21 条目。
- [x] 1.2 视觉 embedding 模型/维度配置入 `ModelProviderProperties` + `application.yml`（默认关闭/缺省即不装配，零真外呼）
      补勾依据（2026-10-21 实读 `application.yml` + `ImageEmbeddingSupportTest` 5 绿）：`ModelProviderProperties` 增 `imageEmbeddingModel`（默认 `multimodal-embedding-v1`）+ `imageEmbeddingEndpoint`；`application.yml` 增 `platform.ai.model.image-embedding-model/-endpoint`（环境变量 `SLZ_AI_IMAGE_EMBEDDING_MODEL` / `SLZ_AI_IMAGE_EMBEDDING_ENDPOINT`）。文本 embedding 的模型/维度/语义零改动。

## 2. 独立 image collection + 维度
- [x] 2.1 `QdrantProperties` 增 `imageCollection` + `imageDimensions`；`ImageVectorStore` 包装类型（对齐 `CrmEntityVectorStore` 范式）+ `ImageVectorStoreConfiguration`（qdrant 派生 / in-memory 独立）
      补勾依据（2026-10-21 `mvn -B -ntp clean verify` BUILD SUCCESS）：`QdrantProperties.imageCollection`（默认 `knowledge_image`）/`imageDimensions`（默认 1024）；`knowledge/vector/ImageVectorStore`（包装 `CrmVectorStore`，不新增裸 `CrmVectorStore` bean，避免候选集歧义）；`knowledge/config/ImageVectorStoreConfiguration` qdrant 态派生 properties（collection/dims 换 image）+ in-memory 态独立实例，装配即绑定清除钩子。
- [x] 2.2 image collection 独立 ensure/维度校验（不与文本 collection 混用）；`VectorRecord` 维度 = `imageDimensions`
      补勾依据（2026-10-21 `ImageVectorSpaceIT` 3 绿）：image 库由 `QdrantVectorStore(派生 properties)` 自行 `ensureCollection`（`collection=image-collection`、`size=image-dimensions`），与文本集合零共享；`QdrantVectorStore.search` 的维度校验即拿派生维（视觉向量维度与文本解耦）。`VectorRecord` 结构零改动。

## 3. 摄取侧生产视觉向量
- [x] 3.1 `PdfImageSliceIngestService` 落 PNG 时并行 `embedImage` → 视觉向量 + asset 引用写 image collection（开关 `rag.retrieval.image-vector-ingest.enabled` 默认 false，关 = 零视觉向量零真外呼）
      补勾依据（2026-10-21 `ImageVectorFlushSupportTest` 3 绿 + `ImageVectorSpaceIT` 3 绿）：`ImageVisualVectorSupport`（新，摄取侧生产+暂存，storageKey 为键）→ `ImageVectorFlushSupport`（新，chunk 落库后按行回填 documentId/chunkId 写 image 集合，payload 复用 `createVectorRecord` 与文本路逐字段一致）；开关 `rag.retrieval.image-vector-ingest.enabled` 默认 false 已注册 `DynamicConfigKeyRegistry`（COST 档）+ `docs/dynamic-config-keys.md`。默认关实测：零 `embedImage` 调用、image 集合零写入（单测断言 `never()`）。
- [x] 3.2 删除/重建联动：`deleteFile`/`reingest` 时同步清 image collection 对应视觉向量（对齐文本切片物理双清链）
      补勾依据（2026-10-21 `ImageVectorCleanupRegistryTest` 3 绿 + `ImageVectorSpaceIT` 删除/重建 2 绿）：`ImageVectorCleanupRegistry` 钩子在 `DocumentIngestionSupport#chunkEmbedAndWrite`（重建前清）、`ImageAssetCleanup#deleteAssets`（`deleteFile` 与 reingest 共用链）、`IngestionRecoveryPolicy#cleanup`（失败清理）三处接入，与文本 `vectorStore.deleteByDocumentId` 并列成「文本集合 + image 集合」双空间双清；未装配 = 零调用。

## 4. 查询侧真 query-by-image
- [x] 4.1 `AiChatImageUnderstandingService#generateImageVector` 改 `embedImage`（图片字节直接入视觉向量，不再文本拼接）
      补勾依据（2026-10-21 `AiChatImageUnderstandingServiceTest` 4 绿）：`generateImageVector(image, provider)` 改为读图片字节 → `provider.embedImage(bytes, mime)`，删除 OCR/摘要/实体/问题文本拼接与文本 `embed`；单测断言 `embedImage` 收到原始字节且 `embed(...)` 零调用。失败仍降级 null。
- [x] 4.2 `KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases` 的 imageVector 路由改查 image collection；`fuseRoutes` 适配异构双空间（fusionKey 去重策略定夺：视觉切片与文本切片不同源不误合并）
      补勾依据（2026-10-21 `ImageSpaceRoutingTest` 3 绿）：`recallImage(...)` 在已装配 `ImageVectorStore` 时查 image 集合（未装配回退文本集合 = 升级前行为），失败/维度不符降级为空图片路不打断文本；融合键仍为 `documentId:chunkId`——image 集合点携带描述切片行主键，同切片两路命中才累加、不同切片永不撞键（`RetrievalConfigResolver#fuseRoutes` javadoc 记该定夺）。

## 5. 测试与验证
- [x] 5.1 单测：视觉 embed 接口/开关矩阵/多 collection 路由/摄取视觉向量落库/query-by-image 召回/删除联动清理（VLM/视觉 embed 全 mock 覆盖 + 真跑 IT）
      补勾依据（2026-10-21 `merge-gate.sh` [unit] 1938 绿）：新增 5 类单测共 17 例——`ImageEmbeddingSupportTest`(5, 本地 HttpServer 桩验请求体/解析/错误码/默认实现)、`ImageVectorFlushSupportTest`(3, 开关矩阵+落库+失败降级)、`ImageVectorCleanupRegistryTest`(3)、`ImageSpaceRoutingTest`(3, 异构路由与降级)、`AiChatImageUnderstandingServiceTest`(改 1 例为真视觉)；全部零真外呼。
- [x] 5.2 IT（Testcontainers + 真跑 opt-in）：视觉向量入库 → query-by-image 命中 → image collection 断言；`RAG_VISION_EMBED_REAL=1` 真跑视觉召回质量（A 档真跑判据）
      补勾依据（2026-10-21 实跑）：`ImageVectorSpaceIT`（Testcontainers MySQL 8.0.36 真库）3 绿——入库→query-by-image 命中 image 集合→`SourceReference=IMAGE`、删除联动清空 image 集合、重建不叠加；`VisionEmbeddingRealIT`（`RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`）1 绿——真调 DashScope：同图余弦 > 0.9 且显著高于「内容图 vs 纯白图」余弦（< 0.9），证明视觉向量真区分图像内容（非文本降维/常量向量）。
- [x] 5.3 `mvn -B -ntp clean verify` BUILD SUCCESS + 树净单次基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
      补勾依据（2026-10-21 实测）：`mvn -B -ntp clean verify` BUILD SUCCESS（surefire 1938 / failsafe 130，0 失败）；`check-test-baseline.sh --update` 写入 313/1938/0 + 37/130/8，`check-test-baseline.sh` EXIT=0；`merge-gate.sh --with-verify` 全门禁 PASS（unit/checkstyle/spotbugs/spotless/pmd/it/baseline/frontend-unit/hook/bijection/pmd-baseline）。

## 6. 收口
- [ ] 6.1 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）
      **待 Owner**：代码与验证已就绪（分支 `feature/multimodal-shared-space`，未 merge 未 push）；归档/handoff/看板由主 Agent 在复核通过后统一收口。
