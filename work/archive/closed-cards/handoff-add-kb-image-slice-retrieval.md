# 交接 · add-kb-image-slice-retrieval（知识库图片切片检索 MVP，方案 B，A 档）

> 执行子 Agent 交接。**停步于 merge/push 之前**：未 merge、未 push、未 reset/rebase/amend、未删分支。
> 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/add-kb-image-slice-retrieval`（自 master `b7b750f` 检出）。

## 1. 提交哈希链

| # | 笔 | 哈希 | 内容 |
|---|---|---|---|
| 0 | 基线 | `b7b750f` | master（检出点） |
| 1 | feat | `2cb8707` | 摄取/回显/前端实现 + 单测 + IT + 契约生成物（openapi.yaml +18 行） |
| 2 | test(baseline) | `0bc1a27` | 回归基线 `--update`（308/1919/0 + 35/126/7） |
| 3 | docs | `45ef9ec` | openspec 卡（proposal/tasks/spec-delta）+ `contracts-frozen.md` 解冻条目 + `docs/dynamic-config-keys.md` |

工作树收尾净（`git status --short` 空；`frontend/typed-router.d.ts` 的 vitest 行尾噪声已按 merge-gate 提示 `git checkout --` 还原）。

## 2. 交付内容（按任务组）

### 组 1 · 摄取侧图片资产化
- `PdfImageExtractor`（新）：`PDResources#getXObjectNames` → `PDImageXObject`，**非整页渲染**；页内按 XObject 名排序（可复现）；`isStencil` 与非位图跳过；最短边 < 64px 的装饰/图标跳过；解码后 `scaleToMaxEdge(1600)` + ImageIO 转 PNG；单图失败只跳过该图。
- `PdfImageSliceIngestService`（新）：开关/灰度/帽 → 每图 `ModelProvider.vision(Prompt, ModelCallOptions.defaults())` → 质量闸门 → 资产落 `FileStorageService` → 生成描述切片（`DocumentChunk.extraMetadata` 携带资产引用）。**单图任一环节失败只跳过该图**，绝不断整篇摄取。
- `VisionIngestSupport`（新，包内）：闸门（blank / 去空白 <40 字 / 「（截图不清）」÷汉字 >0.4）与 VISION 计量（`TokenUsageType.VISION`，失败也记录 success=false）的**唯一实现**；`PdfVisionTranscriber` 与图片描述两侧共用（对齐 SemanticHookSupport「唯一实现 + 薄门面」先例，未复制粘贴；`PdfVisionTranscriber` 原常量/方法签名保留，测试与真机试点 IT 零改动）。
- `ImageSliceIngestSupport`（新，包内）：文本切片 + 图片切片合并、统一重排序号、reingest 时「读回原始字节 → 合并 → 物理删行前清旧资产」。
- `ImageAssetCleanup`（新）：按切片行 `extra_metadata_json` 里的 storageKey 删除图片对象；接入 `KnowledgeAdminService#deleteFile`（C-11 双清链）与 reingest。
- `DocumentChunk`：新增可空 `extraMetadata` + `withChunkIndex`，**保留 7 参兼容构造**（既有全部调用点零改动）。
- `DocumentService`：新增 `processOrEmpty`（图片旁路生效时允许文本为空），`process` 语义与错误文案逐字节不变。
- `DocumentIngestionService`：图片旁路关闭/无图时**仍走 `process`**（默认路径逐字节不变，含空文档抛「文档解析结果为空」）；`reingest` 改为读回原始字节后再合并。
- `DocumentVectorChunkMapper`：`insertBatch` 增列 `extra_metadata_json`（既有 V3 列，**非迁移**）；新增 `selectMetadataRowsByDocumentId`（注解语句，只取 id/document_id/extra_metadata_json，独立 SqlSession 亦可执行）。

### 组 2 · 检索回显与契约解冻
- `SourceReferenceFactory`（新，public）：从 `KnowledgeRetrievalServiceImpl` 抽出的**唯一映射实现**；新增 IMAGE 分支（`sourceType=IMAGE`、`documentId`=资产 id=切片行主键、`filename`=描述摘要 60 字截断、`chunkId=IMAGE:{资产id}`、`pageNo`=页码、`rowIndex=null`）；文档类分支逐字节保持旧行为（含 fileType 空 → 后缀 → txt 回落）。
- `KnowledgeImageAssetService`（新）+ `GET /knowledge/images/{assetId}`（落在既有 `KnowledgeChunkController`）：挂既有 **803 `AI_CHAT_SESSION`**（与 `/knowledge/documents/{documentId}/chunks` 同口径，零新权限常量），服务内再走「切片行 → documentId → uploaded_file.knowledge_base → `authorizedKnowledgeBaseIds`」。**权限不宽于所属 KB**：授权集合不含该库 → 12002；非图片行/软删行/非法 id/缺文档 → 参数错误（不区分，避免探测）；单图读取上限 1 MiB（有界 `readNBytes`）。
- SSE `sources`：`AiSsePayloadWriter#toSourceItems` **零改动**（十字段逐字段照旧；`SseContractTest` 10/10 绿）。
- 受控解冻登记：`openspec/changes/add-kb-image-slice-retrieval/specs/knowledge-rag/spec-delta.md`（spec 侧唯一登记）+ `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志 2026-10-21 条目（:143，对齐 :139-141 三次先例格式）；`SourceReference` javadoc 取值集补登记（结构零改动）。

### 组 3 · 前端最小渲染
- `frontend/src/components/ai/AiAssistantDrawer.vue`：KB 来源 Tag 内 `.source-thumb` 缩略图；`isImageSource` 判定 `sourceType=IMAGE`；**缩略图用带 token 的 `axiosInstance` 拉 blob**（后端从 `token` 头取身份，裸 `<img src>` 会 401）→ `URL.createObjectURL`，按资产 id 缓存、`onUnmounted` 统一 revoke；点击打开原图。`lint:check` / `type-check:check` 均 EXIT=0。

## 3. 存储对齐论证（对齐 DocumentService 现形态）

- 侦察结论：文档原件存储走 `knowledge/storage/FileStorageService`（`MinioFileStorageService` 生产 / `InMemoryFileStorageService` 测试），`store()` 返回 storageKey 落 `uploaded_file.storage_key`；**没有本地磁盘目录约定**。
- 本卡因此**复用同一 `FileStorageService`**：图片对象用 `store(png, "image-{page}-{seq}.png", "image/png")` 落同一存储基建，不新造对象存储、不引入新依赖。
- 资产→文档的归属**不新建资产表**：storageKey 与 MIME/尺寸写切片行既有 `extra_metadata_json`（V3 列，varchar(1000)），资产 id 直接用切片行主键。
- 删除联动对齐 C-11：`deleteFile` = 软删 uploaded_file → 清向量点位 → **删图片对象（新增）** → 物理删切片行；reingest = 读回字节 → 合并 → **删旧图片对象** → 物理删旧行 → 重写。顺序不可颠倒（行删后无从得知 storageKey）。单对象删除失败只 WARN：读取端点需切片行过 KB 授权，行没了对象即不可达，不放大读取面。

## 4. 零迁移论证（本卡未新增 Flyway 号）

1. 资产引用只需 `storageKey + mime + 尺寸 + 资产 id` 四个标量 → 既有 `extra_metadata_json`（varchar 1000，实测最大 JSON ≈ 160 字节）足够；
2. 检索回显所需信息全部进向量 payload（`sourceType/imageAssetId/imageStorageKey`），命中即可回填 `SourceReference`，无需回查资产表；
3. 资产 id 复用 `document_vector_chunk.id`（自增主键，删除即失联，天然防悬空引用）；
4. 删除/重建清理走 `selectMetadataRowsByDocumentId`（既有表 + 既有列），无需新索引（`document_id` 已有唯一键前缀）。
→ **结论：零新表、零新列、零新索引、零 Flyway 号**。若后续要做真多模态（图文共享向量空间 + collection 路由），届时另行立项（本卡不预埋）。

## 5. 开关矩阵（全部动态配置，COST 档，超管专写）

| 配置 | 默认 | 生效语义 |
|---|---|---|
| `rag.retrieval.image-slice-ingest.enabled` | **false** | 关 = 零提取、零 VLM、零落盘、零图片切片（默认行为与升级前逐字节一致） |
| `rag.retrieval.image-slice-ingest.kb-allowlist` | 空 | 空 = 总开关开启时全部 KB 生效；非空 = 逗号分隔 KB id 命中才摄取（KB 灰度） |
| `rag.retrieval.image-slice-ingest.max-images-per-document` | 5（1~20） | 每文档最多处理的内嵌图片张数，超出不送 VLM 不落盘 |

矩阵（判定顺序：存储装配 → 是否 PDF → 总开关 → KB 灰度 → 帽）：

| enabled | kb-allowlist | 文件 | 结果 |
|---|---|---|---|
| false | 任意 | PDF | 不提取、零外呼、零切片 |
| true | 空 | PDF | 全部 KB 摄取（≤帽） |
| true | 命中该库 | PDF | 摄取（≤帽） |
| true | 不含该库 | PDF | 不摄取、零外呼 |
| 任意 | 任意 | 非 PDF | 不摄取（本卡仅 PDF） |
| 任意 | 任意 | PDF 但未装配存储 | 不摄取（WARN 一次，文本切片照常） |

> 未登记 `KbRetrievalStrategyWhitelist`：该白名单是 **12 键封闭集**（OPERATIONAL 档运营调参），成本类键按既有口径「永远不得进入 per-KB 覆盖」（与 `vision-pdf.*` 同处置，见 `docs/dynamic-config-keys.md` 与 whitelist javadoc）。tuple 登记见 `ConfigKeyTierPolicy` COST 档 + `docs/dynamic-config-keys.md` 三键表。

## 6. 验证证据链（2026-10-21 实测）

- 环境：Docker **29.6.2**（`docker info --format '{{.ServerVersion}}'` 实测在线；未启动 Docker Desktop）。
- 定向：`PdfImageSliceIngestServiceTest` 10/10、`SourceReferenceFactoryTest` 5/5、`KnowledgeImageAssetServiceTest` 6/6、`ImageAssetCleanupTest` 4/4、`ImageSliceRetrievalIT` 2/2（Testcontainers MySQL 8.0.36 + Flyway 全量迁移）。
- 全量：`mvn -B -ntp clean verify` → **BUILD SUCCESS**（surefire **1919 / 0 fail / 0 error / 0 skip**；failsafe **126 / 0 fail / 0 error / 7 skip**；checkstyle / spotbugs / spotless / pmd 全绿）。
- 基线：`bash scripts/check-test-baseline.sh --update` → `308/1919/0 + 35/126/7`（旧 `304/1894/0 + 34/124/7`，**只增不减**）；复跑 `bash scripts/check-test-baseline.sh` → **EXIT=0**（“回归基线门禁通过”）。
- 契约：`bash scripts/check-openapi-consistency.sh --update`（唯一入口）→ `frontend/openapi.yaml` +18 行（新端点）→ 脚本复跑「契约一致」。
- 前端：`pnpm lint:check` EXIT=0；`pnpm type-check:check`（app + config）EXIT=0；`pnpm gen:api` 重新生成 SDK（目录 `frontend/src/api/axios` 为 gitignored，无 diff）后 lint/type-check 复跑仍 EXIT=0。
- merge-gate：`bash scripts/merge-gate.sh` → **10/10 PASS**（unit / checkstyle / spotbugs / spotless / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline），`[it]` 按脚本默认跳过（failsafe 由上面全量 `clean verify` 单独实证，未使用 `--with-verify` 以免重复跑一遍 verify）。

## 7. 偏差清单（D 编号）

- **D1 · 合并/清理逻辑落在新类 `ImageSliceIngestSupport`（而非 `DocumentIngestionService`/`DocumentIngestionSupport`）**：两者都已被 PMD `NcssCount`（类级 150）压在上限附近，把分支/循环放进主类会直接顶破道闸（实测：放入 `DocumentIngestionSupport` 后该类 160、放入 `DocumentIngestionService` 后 151，均红）。抽独立类后 pmd:check 0 violations。行为不变。
- **D2 · `rag.retrieval.image-slice-ingest.enabled` 未登记进 `KbRetrievalStrategyWhitelist`**：该白名单是 12 键 OPERATIONAL 封闭集，成本类键按既有口径永不得进入 per-KB 覆盖（与 `vision-pdf.*` 同处置）。KB 灰度改由独立 STRING 键 `kb-allowlist` 承接（默认空 = 全库）。
- **D3 · 图片读取端点的权限形态**：仓库内**没有知识库文档下载端点**可对齐（`GET /knowledge/files/{id}` 只返回元数据 VO），故对齐最接近的先例 `GET /knowledge/documents/{documentId}/chunks`（803 + 服务内 KB 授权链双保险），权限面**不宽于**所属 KB；未新增权限常量。
- **D4 · 基线 `source-revision=2cb8707-dirty`**：按卡内笔序（feat → test(baseline) → docs）执行，`--update` 时 docs/spec/openspec 尚未入库，脚本按自身口径写入 `-dirty` 后缀。数值口径不受影响（同一棵树、同一批报告），docs 笔提交后复跑 check 模式 **EXIT=0**。
- **D5 · `SourceReference` javadoc 压缩**：新增 IMAGE 取值后触发 PMD `CommentSize`（注释 maxLines=20），把六个受控解冻取值的字段口径收敛为「见 contracts-frozen.md 变更日志 + 各卡 spec-delta」，权威映射仍在冻结契约变更日志与 spec 增量中，未丢信息。
- **D6 · `reingest` 改为读回原始字节**：为复用图片提取（不二次打开存储）而读一次 `readAllBytesBounded`（上限仍是既有 100MB 闸），比旧路径多一次内存缓冲；配额/上限语义未变。
- **D7 · 前端未新增 Vitest 用例**：本卡前端只加渲染分支（缩略图 + 原图打开），merge-gate `[frontend-unit]` 既有套件全绿；新增行为由后端 IT 与 lint/type-check 覆盖。
- **D8 · 孤儿对象边界**：`ingest` 失败（如嵌入异常）时已落盘的图片对象会残留（无行引用、读取端点不可达）。已在 reingest/delete 路径清理；若需 ingest 失败即时回滚资产，另立小卡（建议随生产灰度真跑一并定）。

## 8. VLM 无真跑声明

**本卡无真外呼真跑**：单测与 IT 的 `ModelProvider` 全为 mock（固定描述文本），无 DashScope key、无网络、`RAG_VISION_PDF_REAL`/`RAG_BENCHMARK_REAL` 均未设置；`VisionPdfRealPilotIT` 仍按原闸门跳过。生产灰度真跑（真实 VLM 描述质量/成本基线）由 Owner 另行授权。

## 9. 待裁决项

1. **迁移论证**：未触发（零新表/列/索引/号段，见 §4）。若复核认为资产引用应落独立资产表（便于独立生命周期/审计），需下一 Flyway 号另开小卡。
2. **frontend diff 裁决**：`AiAssistantDrawer.vue` 为 sources 渲染分支新增（缩略图 + 打开原图 + blob 缓存/回收），无依赖变更、无样式系统改动；按 batch3 D1 先例请示裁决是否放行。
3. **合并授权**：本分支 3 笔已就绪（`2cb8707` → `0bc1a27` → `45ef9ec`），merge-gate 10/10 + 全量 verify + 基线 EXIT=0，**停步等 merge/push 授权**（严禁自行 merge/push）。

## 10. 复核切入建议（A 档四判据）

- **迁移**：`git show 2cb8707 --stat` 应无 `db/migration` 变更；`extra_metadata_json` 为 V3 既有列。
- **权限语义**：`KnowledgeImageAssetService#load` 全链 + `KnowledgeChunkController#getImageAsset`（803 + KB 授权）；反向用例见 `KnowledgeImageAssetServiceTest` 六例。
- **真跑**：`grep -rn "RAG_VISION_PDF_REAL\|RAG_BENCHMARK_REAL"` 无新增；`PdfImageSliceIngestService` 只在开关开启时才触达 `modelProvider.vision`。
- **冻结契约**：`Spec/changes/.../contracts-frozen.md` 仅 1 行追加；`SourceReference` record 签名零改动（`git diff b7b750f -- src/main/java/com/slz/crm/platform/contract/SourceReference.java` 只应见 javadoc）。
