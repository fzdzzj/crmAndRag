# 提案 · multimodal-gray-and-eval-threshold（多模态灰度授权 + 异图阈值定标 + embedding 计量口径）

## 背景与动机

多模态真共享空间系列（multimodal-shared-space → image-cross-modal-eval → pdf-ingest-image-eval →
anchor-v6-baseline-v7 → production-gray-real-run）已全线闭环，但留下三个长期储备收尾项，Owner 2026-10-22 拍板
**硬合一张卡**一次做清：

1. **生产开关打开授权**：成本基线（production-gray-cost-v1）已产出「≈1 371 token / 9.8s / 图」，但生产开关
   `rag.retrieval.image-vector-ingest.enabled` 仍默认 false，需登记「运行期灰度路径」（Owner 拍板：不改代码，超管运行时写动态配置）。
2. **异图硬阈值定标**：production-gray-real-run 诚实标注「真实语料异图间隔可低至 +0.011，不套 0.90」，需用多图同类/跨类语料定标真实语料的判别阈值。
3. **embedding token 计量补全**：文本 embed 与视觉联合 embed 两端点 token 恒 0（DashScope 端点不返 usage），需补诚实计量口径。

## 现状锚点（三源实读，2026-10-22）

- **生产开关**：`DynamicConfigKeyRegistry` `rag.retrieval.image-vector-ingest.enabled` 默认 false（COST 档，超管专写）、
  `kb-allowlist`（KB 灰度）、`max-images-per-document`（默认 5）；「打开」是**运行期动态配置操作**，非代码改动。
- **异图阈值**：`ImageCrossModalSuite` 阈值 0.90（程序化语料）；production-gray-real-run 实测真实语料异图余弦最大 0.9887
  （间隔 +0.011），仅断言「异图 < 同图」顺序，硬阈值缺失。
- **embedding 计量**：`ModelProviderImpl.embed`（L171-176）取 `EmbeddingResponse.getMetadata().getUsage()` 但 DashScope
  文本 embedding 端点不返 usage → null；`ImageEmbeddingSupport.parseResponse`（L129-132）`positiveOrNull` 取 `input_tokens`
  /`total_tokens` 但联合 embedding 端点也不返 → 0。调用次数已由 `VisionIngestSupport.recordEmbeddingUsage`（EMBEDDING 类型）计入
  `TokenUsageRecorder`，缺的只是 token 值（端点客观限制）。

## Owner 拍板（2026-10-22）

1. **生产开关 = 只登记运行期灰度路径，不改代码**（默认 false 保持，超管运行时写 enabled=true + kb-allowlist 灰度）。
2. **三卡硬合一张 A 档卡**。

## 方案骨架

1. **生产开关运行期灰度路径登记**（纯文档）：看板/文档登记「打开开关 = 超管在管理端把 `rag.retrieval.image-vector-ingest.enabled`
   写 true + 可选 `kb-allowlist` 灰度 KB + `max-images-per-document` 按生产帽（默认 5）」，附成本基线引用；不改任何代码、不改默认值。
2. **异图硬阈值定标**（A 档评测真跑）：复用 `ImageCrossModalEvaluator` 余弦判据框架，造「多图同类/跨类」语料（同类变体应判同、跨类应判异、
   真实版式图类边界），真跑 `RAG_VISION_EMBED_REAL=1`，用真实语料分布定出「同图下限 / 异图上限」的判别阈值（替代程序化语料 0.90），落锚 `image-threshold-calibration-v1.json`。
3. **embedding 计量口径诚实化**（B 档 src/main 小改）：把「token 恒 0」的 embedding 计量改为显式「调用次数计量 + token 不可得声明」口径——
   在 `TokenUsageRecord` 或调用侧补「embedding 端点不返 token，仅记调用次数」的语义位（不伪造 token、不新增端点），使成本账单诚实可读；
   涉及 `platform/model` 或 `platform/contract` 的极小改动 + 契约解冻（若 `TokenUsageRecord`/`ModelCallResult` 口径变）。

## 边界与不改动面

- **不改**生产开关默认值（`image-vector-ingest.enabled` 仍 false）；**不改**摄取/检索链路行为。
- **不碰**既有 baseline（v1~v7 / image / pdf / production-gray 锚）——本卡只新增定标锚 + 计量口径。
- **零迁移 / 零新权限**；embedding 计量改动如需触碰冻结契约（`TokenUsageRecord`/`ModelCallResult`）走受控解冻。

## 档位判定

**A 档**：异图阈值定标真跑（`RAG_VISION_EMBED_REAL`）+ embedding 计量可能触碰冻结契约（受控解冻）。生产开关登记为纯文档（非 A 档面）。

## 验收判据

1. 生产开关「运行期灰度路径」登记齐备（动态配置键 + 灰度步骤 + 成本基线引用），零代码改动、默认值不变。
2. 异图硬阈值定标跑通（多图同类/跨类语料真跑），产出真实语料判别阈值锚点。
3. embedding 计量口径诚实化（调用次数已记、token 不可得明示），零伪造 token。
4. 独立复核四判据全过（尤其④ 冻结契约：embedding 计量改动若碰 TokenUsageRecord/ModelCallResult 需受控解冻）。