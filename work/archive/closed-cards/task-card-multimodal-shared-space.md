# 任务卡 · multimodal-shared-space（多模态真共享空间升级）

- 档位：**A 档**（embedding 选型 + 向量库 schema + 契约受控解冻 + 真跑，独立复核四判据）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/multimodal-shared-space`（从 master 检出）
- 目标：引入真视觉 embedding（DashScope 文本-图像联合），摄取侧图片生产视觉向量入独立 image collection，查询侧真 query-by-image，与文本空间并行跨模态融合

## Owner 四项拍板（2026-10-21，缺一不可）
1. 视觉 embedding = 真视觉（CLIP 式图→向量），不再文本降维
2. 向量存储 = 独立 image collection（维度可 ≠ 文本）
3. 真跑 = 授权（`RAG_VISION_EMBED_REAL` opt-in）
4. 模型来源 = DashScope 文本-图像联合 embedding 端点（零新依赖）

## 关键锚点（已实读，勿重复侦察）
- `PdfImageSliceIngestService#toChunk`：当前零视觉向量，只文本描述切片
- `AiChatImageUnderstandingService#generateImageVector`：当前是「文本拼接 embed」非真视觉
- `QdrantVectorStore` 单 collection 写死；`QdrantProperties` 已有 `crmEntityCollection` 双集合先例
- `CrmEntityVectorStoreConfiguration`：多 collection「派生 properties + 包装类型」成熟范式
- `KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases` + `RetrievalConfigResolver#fuseRoutes`：检索/融合接口已在
- `ModelProvider` 只有 `embed(文本)` + `vision(文本)`，无视觉 embed 接口

## 任务（写集：contract + platform/model + knowledge/vector + knowledge/document + server/ai，均有明确归属）
1. `ModelProvider` 增 `embedImage`；`ModelProviderImpl` 走 DashScope 文本-图像联合 embedding；`contracts-frozen.md` 解冻登记
2. `QdrantProperties` + `imageCollection`/`imageDimensions`；`ImageVectorStore` 包装 + `ImageVectorStoreConfiguration`
3. `PdfImageSliceIngestService` 摄取视觉向量（开关默认 false）；删除/重建联动清 image collection
4. `AiChatImageUnderstandingService` 改真视觉向量；检索路由查 image collection；`fuseRoutes` 异构融合

## 边界（禁改面）
- 禁改文本 embedding/检索/`SourceReference` 十字段（未解冻）；禁碰 `crm_entity`/`CrmEntityVectorStore`；禁删文本降维描述切片

## 验证
`mvn -B -ntp clean verify` + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10；真跑 IT（`RAG_VISION_EMBED_REAL=1`）

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字，细节留文件。