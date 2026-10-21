# handoff · multimodal-shared-space（多模态真共享空间升级）

> 档位：A 档（真视觉 embedding + 向量库 schema + 契约受控解冻 + 真跑，独立复核四判据）
> 分支：feature/multimodal-shared-space（永久保留）｜M1 merge --no-ff `69190fd` → 基线收口 `581d906`/`71bfe27`

## §1 目标与背景
上一卡 add-kb-image-slice-retrieval 采用方案 B 文本降维（图片→VLM 描述文本→文本 embedding）。本卡升级为**真跨模态**：
引入真视觉 embedding（DashScope 文本-图像联合端点），摄取侧图片生产视觉向量入独立 image collection，查询侧真 query-by-image，
与文本空间并行融合。Owner 四项拍板：真视觉（CLIP 式）/ 独立 image collection / 授权真跑 / DashScope 文本-图像联合 embedding。

## §2 方案与实现面（21 改 + 13 新 = 33 文件，+384/−97）
- **契约**：`ModelProvider` 增 `default embedImage(byte[], String)`（默认抛 UnsupportedOperationException，既有实现方零改动）；
  contracts-frozen.md §修正轮4 条目14/15/16 受控解冻登记 + 变更日志。
- **真视觉**：`ImageEmbeddingSupport`(167) 直连 DashScope 文本-图像联合 embedding（`multimodal-embedding-v1`，RestClient 零新依赖）；
  `ModelProviderImpl` 接 guard/超时/EMBEDDING 计量；`ModelProviderProperties`+`application.yml` 增 image-embedding-model/-endpoint。
- **独立 image 空间**：`QdrantProperties` 增 `imageCollection`/`imageDimensions`；`ImageVectorStore`(46) + `ImageVectorStoreConfiguration`(53)（对齐 CrmEntityVectorStore 范式）。
- **摄取**：`ImageVisualVectorSupport`(81) + `ImageVectorFlushSupport`(87)（chunk 落库后回填真实 chunkId）；
  `ImageVectorCleanupRegistry`(64) 静态钩子接 chunkEmbedAndWrite/ImageAssetCleanup/IngestionRecoveryPolicy 三处双清；开关 `rag.retrieval.image-vector-ingest.enabled` 默认 false（COST 档）。
- **查询**：`AiChatImageUnderstandingService` 图字节直入视觉向量；`KnowledgeRetrievalServiceImpl#recallImage` 查 image 集合失败降级；`fuseRoutes` 异构不误合并（同 slice 才累加）。
- **测试**：5 类单测 14 例 + `ImageVectorSpaceIT`(真库3例) + `VisionEmbeddingRealIT`(opt-in)。

## §3 零迁移论证
`git diff -- db/migration` 零命中（复核①）；配置全走 runtime `application.yml` + `QdrantProperties`，非 schema 变更。

## §4 权限论证
零新 controller/零新 PermissionOperates/零新 OpenEndpointRegistry/零新权限常量（复核②）；`embedImage` 仅内部链路。

## §5 真跑论证（A 档判据）
`VisionEmbeddingRealIT` 真 opt-in（`RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`），真 HTTP base64 data URI 外呼，
阈值即判据（同图>0.9、异图<0.9、同>异）；**复核方独立复跑 `Tests run:1 Skipped:0 Failures:0 3.869s` 证实非假绿**。

## §6 冻结契约论证（A 档判据）
`embedImage` 为 default 方法（+18/−0 纯插入），既有签名零改动；解冻登记 contracts-frozen.md:129 + :156；
SourceReference/VectorRecord/TokenUsageType/文本 embed 均不在改动清单（复核④）。

## §7 偏差处置与治理留痕
- **D1（typed-router.d.ts 污染）**：执行端跑 merge-gate 的 frontend-unit 触发 unplugin-vue-router 加 salesLeadList 噪声
  （红线文件，AGENTS.md 严禁碰触）；主 Agent 审 diff 发现 → Owner 拍板 `git checkout --` 还原，零提交。
- **D2（执行端未 commit）**：执行端产出 34→33 文件悬浮工作区未提交（停步未 merge 未 push，但漏了 commit）；主 Agent 闭环时
  分三笔补 commit（feat/test+基线/docs）。
- **D3（基线 skipped 口径漂移，本卡最重偏差）**：执行端 test 笔正确记 `failsafe.skipped=8`（新增 opt-in 真跑 IT 默认无 key 会 skip）；
  复核方独立复跑真跑 IT 后 `VisionEmbeddingRealIT` 报告变 Skipped=0，污染 target，主 Agent M1 首次 `--update` 误写 skipped=7。
  **修正**：不带 key 重跑该 IT 回到 Skipped=1 → `--update` 重写 skipped=8 → 校验 EXIT=0。基线 `source-revision=71bfe27`、`failsafe.skipped=8`。
- **D4（default 关空操作）**：默认关仍有 image 集合装配期 ensure 一次 + 每次摄取一次空 deleteByDocumentId（零向量落盘零外呼）；
  Owner 拍板「接受现状」，不造懒 ensure；O1 文案已改「零向量落盘/零外呼」。
- **D5（O1/O2 非阻断观察）**：O1 = docs 文案「零集合读写」vs 装配期 ensure 空集合（已修正）；O2 = 真跑证据无留档（复核方已复跑证实，非回退项）。

## §8 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`8c3ac8f..71bfe27`）。未 reset/rebase/amend/删分支。