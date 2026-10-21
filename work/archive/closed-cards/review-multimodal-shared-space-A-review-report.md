# A 档独立复核 · multimodal-shared-space（四判据）

- 复核对象：权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/multimodal-shared-space`（`git rev-parse --abbrev-ref HEAD` 实测）。
- 复核口径：工作区 diff（执行端未 commit）。实测 `git status --porcelain` = 20 改动 + 13 未跟踪 = 33 项，`git diff --stat` 20 文件 / +367 −97，与派单描述「34→33、typed-router.d.ts 已还原」一致；`frontend/typed-router.d.ts` 不在清单内。
- 结论：**四判据全部通过，无 ACK 回退项**；附 2 项非阻断观察（§6）。
- 临时日志 `review-vision-real*.log` 已删除；`target/failsafe-reports/` 为构建产物（未跟踪），其中 `VisionEmbeddingRealIT` 报告已被本次独立复跑覆盖为真跑绿。

---

## ① 迁移（通过）

- `git diff --stat -- src/main/resources/db/migration` 与 `git status --porcelain -- src/main/resources/db/migration` **均为空**：本卡零迁移脚本，不存在改已合入脚本 checksum 的风险。
- 新增配置全部走 `application.yml` + `QdrantProperties`（运行时集合名/维度），非 schema 变更：`src/main/resources/application.yml:120-128`（`image-embedding-model/-endpoint`）、`:165-170`（`image-collection` / `image-dimensions`）。

## ② 权限语义（通过）

- 零新 controller：`src/main/java/com/slz/crm/server/controller`、`src/main/java/com/slz/crm/common`（含 `PermissionOperates`）、`knowledge` 侧 controller 的 `git status` 全空。
- 新类内的 `@RestController/@RequestMapping/@GetMapping/@PostMapping/@RequirePermission` 全量 grep 命中 0。
- `embedImage` 只出现在内部链路：契约 `src/main/java/com/slz/crm/platform/contract/ModelProvider.java:93`、实现 `ModelProviderImpl`/`ImageEmbeddingSupport`、调用方 `AiChatImageUnderstandingService`（既有服务内部方法，非端点）与 `ImageVisualVectorSupport`。
- 唯一被改的 server 侧文件是 `server/ai/AiChatImageUnderstandingService.java`（内部方法签名 `generateImageVector(image, provider)` 收窄），无新鉴权面、无新权限常量。

## ③ 真跑（通过，已独立复跑证实）

- opt-in 真实：`src/test/java/com/slz/crm/platform/model/VisionEmbeddingRealIT.java:32-36` — `assumeTrue("1".equals(System.getenv("RAG_VISION_EMBED_REAL")))` + `DASHSCOPE_API_KEY`/`SLZ_AI_API_KEY` 缺省即整类跳过。
- 非 mock：`:59-71` 直接 `new ModelProviderImpl(...)` 指向真实 `https://dashscope.aliyuncs.com/...`；`ImageEmbeddingSupport` 走 `restClient.post()` + `data:{mime};base64,{bytes}` 真外呼、解析 `output.embeddings[].embedding`（`toDataUri` / `call` / `parseResponse`）——图片原始字节入参，不存在「拼文本再 embed」。
- 阈值与判据逐条对应：`:54-56` `sameCosine > 0.9`、`sameCosine > differentCosine`、`differentCosine < 0.9`；另断言 `model == multimodal-embedding-v1`（`:50`）。夹具为内容图（形状/线条/文字）vs 纯白图（`:121-150`），故「退化成文本降维或常量向量」必红。
- 独立复跑（本轮实做，非引用执行端自述）：`RAG_VISION_EMBED_REAL=1` + `.env` 之 `DASHSCOPE_API_KEY`，`mvn -B -ntp failsafe:integration-test -Dit.test=VisionEmbeddingRealIT` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.869 s`，EXIT=0。**真外呼、真绿**。

## ④ 冻结契约（通过）

- `embedImage` 为 **default** 方法：`ModelProvider.java:93`，默认实现抛 `UnsupportedOperationException`；该文件 `git diff` 为纯插入（+18 −0），既有 `embed(EmbeddingRequest)` 等签名逐字节未动 ⇒ 既有实现方/测试替身零改动。
- 受控解冻登记齐备：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md:129`（**§13 修正轮 4**，条目 14/15/16 明确「契约面零结构改动」）+ `:156`（变更日志 2026-10-21 条目）。
- `SourceReference` / `VectorRecord` / `TokenUsageType` / 文本 embedding：三者文件均不在改动清单，`VectorRecord` 结构零改；文本 embedding 仅新增独立 `imageEmbeddingModel/imageEmbeddingEndpoint` 配置，`embedding-model`/`dimensions` 原值不动。

---

## 5. 附加核对（逐条）

1. **摄取开关默认 false**：`DynamicConfigKeyRegistry.java:343` 注册默认值 `"false"`（BOOLEAN，COST 档；`ConfigKeyTierPolicy.java:83` 已入 27 键封闭集），`docs/dynamic-config-keys.md:120` 登记默认 false。代码侧 `PdfImageSliceIngestService.java:64`（键常量）/`:152`（`imageVectorEnabled()` 缺省 `Boolean.FALSE`）。关 = 零外呼：`PdfImageSliceIngestService.java:135-137` 仅开关为真才 `begin()`，暂存为 null 时逐图 `embedAndStage` 空转；`ImageVectorFlushSupport.java:57-58` 见空暂存即 0 调用（开关矩阵 3 例见 `ImageVectorFlushSupportTest`）。
2. **fusionKey 不同源不误合并**：`RetrievalCandidate.java:12-13` `fusionKey = documentId + ":" + chunkId`；image 集合 payload 复用 `DocumentIngestionSupport.createVectorRecord`（`ImageVectorFlushSupport.java:63`，`toImageRecords` 内），携带同一 `documentId` + 描述切片行主键 `chunkId` ⇒ 同切片两路命中才累加、不同切片键必不同、只在视觉空间命中的切片独立成候选。定夺写入 `RetrievalConfigResolver.java:209-222` javadoc（实际合并逻辑 `:245-252`）。查询侧 `KnowledgeRetrievalServiceImpl.java:403` `recallImage` 查 `ImageVectorStore`，失败/维度不符降级为空图片路（`:409-418`）。
3. **未碰 crm_entity / 文本降维描述切片未删**：`CrmEntityVectorStore` 不在改动清单，`QdrantProperties.java:27` `crmEntityCollection` 原值保留；`ImageVectorStoreConfiguration.java:42-46` 用派生 properties 另建 backing（不进 `CrmVectorStore` 候选集）。文本降维描述切片保留：`PdfImageSliceIngestService` 的 `describe()`→`toChunk()` 主干未删（仅新增并行视觉路 `:250-253`），`ImageSliceIngestSupport.java:103` 未改。
4. **`deleteFile` 零改动**：`KnowledgeAdminService.java` 不在 `git status` 清单（仅 `:250` 既有 `ImageAssetCleanup.deleteAssets(...)` 调用）；钩子挂在静态清理链 `ImageAssetCleanup.java:41`、`IngestionRecoveryPolicy.java:85`、`DocumentIngestionSupport.java:44`，未装配 = 零调用（`ImageVectorCleanupRegistry.java:61-73`）。

## 6. 非阻断观察（不影响四判据，建议执行端顺手处理）

- O1 文案过满：`docs/dynamic-config-keys.md:115` 称默认关 = 「零 image 集合读写」，但 `ImageVectorStoreConfiguration.java:43` `backing.initialize()` 在装配期即 ensure（默认 provider=qdrant），故关态仍会建**空** image 集合（零向量落盘、零外呼，但非「零读写」）。建议改为「零向量落盘/零外呼」或注明空集合建制。
- O2 真跑证据未留档：树内唯一持久 artifact 原为 `Skipped: 1`（`clean verify` 未带 opt-in，`clean` 覆盖了此前 opt-in 报告），`tasks.md:33` 的「1 绿」无旁证；本轮已由复核方独立复跑证实可绿。建议执行端把 opt-in 实跑输出存档到 `work/`，避免下次复核重跑。
