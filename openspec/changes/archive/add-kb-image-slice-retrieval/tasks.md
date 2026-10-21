# 任务清单 · add-kb-image-slice-retrieval

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 摄取侧图片资产化
- [x] 1.1 PDF 内嵌图片对象提取（PDResources/XObject，仅 PDF）+ 图片资产落盘（对齐现有文档文件存储基建，删除/物理双清联动清理）
      补勾依据（2026-10-21 实现 + 实测）：`PdfImageExtractor`（PDResources#getXObjectNames → PDImageXObject，非整页渲染，页内按 XObject 名排序、最短边 <64px 跳过）；资产落盘走既有 `FileStorageService`（MinIO/InMemory 同一抽象，无新造对象存储）；删除联动 `ImageAssetCleanup.deleteAssets` 已接入 `KnowledgeAdminService#deleteFile`（物理删行前按 `extra_metadata_json` 取 key 删除）与 `DocumentIngestionService#reingest`（重建前清旧资产）。实测 `mvn -B -ntp test -Dtest=ImageAssetCleanupTest` 4/4 绿。
- [x] 1.2 每图 VLM 描述：复用 ModelProvider.vision + VISION 计量 + 质量闸门 + 预算帽（对齐 PdfVisionTranscriber 形态）
      补勾依据（2026-10-21 实现 + 实测）：`PdfImageSliceIngestService#describe` 走 `ModelProvider.vision(Prompt, ModelCallOptions.defaults())`；闸门与 VISION 计量抽到唯一实现 `VisionIngestSupport`（对齐 SemanticHookSupport「唯一实现 + 薄门面」先例），`PdfVisionTranscriber` 与图片描述两侧共用，未复制粘贴；预算帽 = 每文档图片数帽（默认 5，1~20 封顶）。实测 `PdfImageSliceIngestServiceTest` 10/10 绿（含闸门失败/外呼异常/计量 success=false 用例）。
- [x] 1.3 描述文本切片入现有 chunk/embed 链路 + 切片→资产追溯引用（payload/元数据；零迁移优先，确需新列/资产表走下一 Flyway 号并论证）
      补勾依据（2026-10-21 实现 + 实测）：图片描述转成 `DocumentChunk`（新增可空 `extraMetadata`，7 参兼容构造保持既有调用点零改动），在 `DocumentIngestionService#parseWithImageSlices` 与文本切片合并、统一重排 `chunkIndex` 后交 `DocumentIngestionSupport#chunkEmbedAndWrite`；资产引用写既有 `document_vector_chunk.extra_metadata_json`（V3 已有列）与 `VectorRecord.metadata`（`sourceType=IMAGE` / `imageAssetId` / `imageStorageKey`）。**零迁移**：无新表、无新列、无新 Flyway 号。实测 `ImageSliceRetrievalIT` 2/2 绿（真 MySQL 落库 + payload 断言）。
- [x] 1.4 开关 rag.retrieval.image-slice-ingest.enabled 默认 false + KB 灰度 + 每文档图片数帽（默认 5，动态配置）
      补勾依据（2026-10-21 实现 + 实测）：三键注册进 `DynamicConfigKeyRegistry`（enabled=false / kb-allowlist="" / max-images-per-document=5，范围 1~20）并登记 `ConfigKeyTierPolicy` COST 档 + `docs/dynamic-config-keys.md`；未登记进 `KbRetrievalStrategyWhitelist`（该白名单是 12 键封闭集，成本类键按既有口径永不得进入 per-KB 覆盖，见 handoff D2）。实测 `DynamicConfigKeyRegistryTest` / `ConfigKeyTierPolicyTest` 绿。

## 2. 检索回显与契约解冻
- [x] 2.1 SourceReference.sourceType 扩展 IMAGE（D3 先例形态：documentId=资产id / filename=描述摘要 / chunkId=IMAGE:{资产id}）+ spec-delta 登记 + contracts-frozen.md 解冻记录追加
      补勾依据（2026-10-21 实现 + 实测）：映射逻辑从 `KnowledgeRetrievalServiceImpl` 抽为 `SourceReferenceFactory`（单一实现，IMAGE 分支 + 文档类分支逐字节保持旧行为）；spec 增量 `openspec/changes/add-kb-image-slice-retrieval/specs/knowledge-rag/spec-delta.md`；`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志追加 2026-10-21 条目（:143）。源类型常量与元数据键集中在 `ImageSliceMetadata`（不新增冻结枚举）。实测 `SourceReferenceFactoryTest` 5/5 绿。
- [x] 2.2 图片资产读取端点（鉴权对齐现有文档下载形态，权限不宽于所属 KB）+ SSE sources 下发（payload 结构零改动）
      补勾依据（2026-10-21 实现 + 实测）：`GET /knowledge/images/{assetId}` 落在 `KnowledgeChunkController`，挂既有 803 `AI_CHAT_SESSION`（与 `/knowledge/documents/{documentId}/chunks` 同口径，零新权限常量）；`KnowledgeImageAssetService` 内再按「切片行 → documentId → uploaded_file.knowledge_base」复用 `authorizedKnowledgeBaseIds`，授权集合不含该库即 12002；非图片行/软删行/缺行统一参数错误。SSE 侧 `AiSsePayloadWriter#toSourceItems` 逐字段照旧（十字段零改动）。实测 `KnowledgeImageAssetServiceTest` 6/6 绿、`SseContractTest` 10/10 绿。

## 3. 前端最小渲染
- [x] 3.1 sources 里 sourceType=IMAGE 渲染缩略图（对齐现有渲染分支；lint/type-check 全绿）
      补勾依据（2026-10-21 实现 + 实测）：`frontend/src/components/ai/AiAssistantDrawer.vue` 在既有 KB 来源 Tag 内加 `.source-thumb` 缩略图（`isImageSource` 判定 + 按资产 id 缓存 blob URL；因后端从 `token` 头取身份，用 `axiosInstance` 带 token 拉 blob，不用裸 `<img src>`）；点击打开原图，卸载时 `revokeObjectURL`。实测 `pnpm lint:check` 0 error、`pnpm type-check:check`（app+config）0 error。

## 4. 测试与验证
- [x] 4.1 单测：提取/闸门/帽/回显映射/开关矩阵；IT（Testcontainers）：图片切片入库→检索命中→SourceReference=IMAGE 断言 + 删除联动清理
      补勾依据（2026-10-21 实测，Docker 29.6.2 在线）：新增单测 4 类 25 例（`PdfImageSliceIngestServiceTest` 10 / `SourceReferenceFactoryTest` 5 / `KnowledgeImageAssetServiceTest` 6 / `ImageAssetCleanupTest` 4）全绿；IT `ImageSliceRetrievalIT` 2/2 绿（Testcontainers MySQL 8.0.36 + Flyway 全量迁移）：图片切片入库 → 内存向量库同库检索命中 → `SourceReference=IMAGE`（documentId=资产 id、chunkId=IMAGE:{id}、filename=描述摘要、pageNo）→ 删除联动清理（资产对象消失 + 物理删行后行不可查）+ KB 隔离（他库 filter 空）。**本卡无真外呼真跑**：VLM 全 mock。
- [x] 4.2 全量 mvn -B -ntp clean verify（Docker）+ 树净单次基线 --update + EXIT=0 + merge-gate 10/10 + 前端 lint/type-check（若有 diff）；契约门禁 diff 走 --update 唯一入口
      补勾依据（2026-10-21 实测，见 handoff §6 证据链）：`mvn -B -ntp clean verify` BUILD SUCCESS（surefire/failsafe/pmd 全绿）；契约生成物 diff 走唯一入口 `bash scripts/check-openapi-consistency.sh --update`（`frontend/openapi.yaml` +18 行 = 新端点，随后复跑一致）；`bash scripts/check-test-baseline.sh --update` 一次写入 + 复跑 EXIT=0；`bash scripts/merge-gate.sh` 10/10 PASS。
- [x] 4.3 handoff（存储对齐论证 / 零迁移或迁移论证 / 解冻登记链接 / 开关矩阵 / VLM 链路无真跑声明）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-21）：handoff 落 `d:\code\crmAndRag\work\handoff-add-kb-image-slice-retrieval.md`；docs 笔 = `docs/dynamic-config-keys.md` 三键表 + COST 档清单 23→26 + tier 专节计数同步；未 merge、未 push、未 reset/rebase/amend、未删分支。
