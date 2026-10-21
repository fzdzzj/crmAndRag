# 提案 · image-cross-modal-eval（图文跨模态召回质量评测）

## 背景与动机

上一卡 `multimodal-shared-space` 已落地真视觉 embedding：摄取侧图片生产视觉向量入独立 image collection，
查询侧 `query-by-image` 查 image 集合，与文本空间并行融合。但**「真视觉 query-by-image 检索质量」尚未被量化评测**——
现有 RAG 评测（`RagBenchmarkSuite` 72 例）的 `Category.IMAGE` 6 例评测的是「图片理解问答走文本描述切片召回」
（方案 B 文本降维），零真视觉向量召回的评测位点。

本卡建独立图文跨模态评测套件，量化「真视觉向量召回」 vs 「纯文本降维召回」的对照，验证 multimodal-shared-space
引入的真视觉路是否真的优于/区别于纯文本描述召回，还是回归。

## 现状锚点（三源实读，2026-10-21）

- **评测四件套**：`RagBenchmarkCase`（用例 + `Category.IMAGE` 已在）/ `RagBenchmarkSuite`（`SUITE_VERSION="6.0"` 72 例）/
  `RagQualityEvaluator`（recall/MRR/hit → `Report`）/ `RagQualityReport`。
- **独立套件先例成熟**：`CustomerRetrievalBenchmarkIT` 建 `CUSTOMER-SUITE-2.0`——独立编号 C-01~C-15、**不并入 63 例 fixture、
  SUITE_VERSION 不动**、独立 suiteVersion、独立 `RagQualityEvaluator.evaluate` 判卷。`docs/rag-quality/customer-baseline-v1/v2.json` 独立落锚。
- **评测基建**：`RagBenchmarkDataPreparer`（fixture 幂等入库 + 黄金占位 id 对齐 + `rewriteSuite`）；
  `RagRealRetrievalBenchmarkIT`（真模型真跑落锚先例）；确定性 fake 向量（`DeterministicEmbedding`）支持无外呼回函。
- **真视觉路现状**：`VisionEmbeddingRealIT` 已证「同图余弦>0.9、异图<0.9、同>异」（multimodal-shared-space，opt-in 真跑）；
  `ImageVectorStore`（image collection）/ `KnowledgeRetrievalServiceImpl#recallImage`（查 image 集合失败降级）/ `fuseRoutes`（异构融合）。

## Owner 三项拍板（2026-10-21）

1. **评测形态 = 独立 IMAGE-SUITE 套件**（对齐 customer 先例）：独立编号、不并入 72 例主套件、`SUITE_VERSION` 不动。
2. **真跑落锚 = 授权真跑 + 独立 `image-baseline` 落锚**（对齐 customer-baseline 先例，不碰 baseline-v7）。
3. **判据 = 同/异图余弦判别**（同图余弦高、异图低，对齐 `VisionEmbeddingRealIT` 余弦判据）。

## 方案骨架

1. **独立套件 `ImageCrossModalSuite`**（新）：`IMAGE-SUITE-1.0`，独立编号 `IM-01~IM-N`；每条 = 图片查询字节 +
   同图变体（应召回，余弦高）+ 异图/纯白图（不应召回，余弦低）对照；不动 `RagBenchmarkSuite` / `SUITE_VERSION`。
2. **评测 runner `ImageCrossModalBenchmarkIT`**（新）：对齐 `CustomerRetrievalBenchmarkIT` 形态——fixture 幂等入库
   → 真视觉 embedding（opt-in `RAG_VISION_EMBED_REAL=1`）→ 逐条 query-by-image 召回 → 余弦判别判分 → `Report` 落盘
   `docs/rag-quality/image-baseline-v1.json`；无 key 时整类 Assume 跳过（默认零外呼）。
3. **判分口径**：同图命中（余弦>阈值）计 hit、异图不命中计正确拒答；汇总「同图召回率 / 异图误召率」对照指标，
   与文本降维描述召回的同类指标并列呈现（证明真视觉路 ≠ 纯文本路且优于/弱于的量化结论）。
4. **对照锚定**：同批跑「文本描述召回」作 baseline 对照（同一图片经 VLM 描述文本 → 文本 collection 召回），
   证明真视觉向量召回的增量价值；两者结果写入同一 `Report`（独立 suiteVersion）。

## 边界与不改动面

- **不动** `RagBenchmarkSuite` 72 例 / `SUITE_VERSION="6.0"` / `Category.IMAGE` 既有 6 例语义。
- **不碰** baseline-v1~v6 历史锚（只新增独立 `image-baseline-v1.json`）。
- **不改** 检索/摄取生产链路（纯评测面，`src/test` + `docs/rag-quality/`）；零迁移、零生产代码、零新权限、零新依赖。

## 档位判定

**A 档（真跑）**：真视觉 embedding 外呼（`RAG_VISION_EMBED_REAL` opt-in）落锚。虽零生产代码、零迁移、零权限，
但「真跑判据」触发 A 档独立复核四判据（真跑口径 / 黄金集有效性 / 落锚可复现 / 不污染主套件）。

## 验收判据

1. 独立 `IMAGE-SUITE-1.0` 套件落地，不并入主套件、`SUITE_VERSION` 不动。
2. 真跑（opt-in）跑通：同图余弦高命中、异图低不命中，量化「真视觉 vs 文本降维」对照。
3. 独立 `image-baseline-v1.json` 落锚；无 key 默认零外呼（Assume 跳过）。
4. 独立复核四判据全过（真跑口径 / 黄金集 / 落锚 / 不污染主套件）。