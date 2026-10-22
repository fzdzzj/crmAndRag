# 提案 · pdf-ingest-image-eval（PDF 摄取链纳入图文评测）

## 背景与动机

上一卡 `image-cross-modal-eval` 量化了「真视觉 query-by-image vs 文本降维」检索质量，但**语料是程序化确定性图直入
image 集合**（走 `ImageVectorFlushSupport` 同口径 payload），**未经过 PDF 解析 → 视觉向量落库整段**——见该卡
handoff §6 D3 与 `image-baseline-v1.md` §4 未解决项 2。`ImageVectorSpaceIT` 虽覆盖了「PDF 解析 → image collection」
的链路正确性，但 mock 了视觉 embedding（固定向量），无质量判据、无真外呼。

本卡把两条已有链桥接：真 PDF 文件（含内嵌图）→ `PdfImageExtractor` 解析 → `PdfImageSliceIngestService` 真
`embedImage` → image collection → 真 query-by-image → 同/异图余弦质量判据，落锚「PDF 摄取链」独立评测基线。

## 现状锚点（三源实读，2026-10-21，Docker 29.6.2 在线已实测）

- **摄取链已建**：`PdfImageExtractor`（PDResources 抽取 PDImageXObject）→ `PdfImageSliceIngestService#buildSlices`
  （L126-145，`imageVectorEnabled()` 开真视觉路）→ `ImageVisualVectorSupport#embedAndStage`（L60-88，真
  `modelProvider.embedImage(image.png())` 暂存 storageKey→向量）→ `ImageVectorFlushSupport#flush`（chunk 落库后回填 chunkId）。
- **上一卡评测框架可复用**：`ImageCrossModalSuite`（IMAGE-SUITE-1.0，独立编号 IM-01~IM-05）+ `ImageCrossModalFixture`
  （6 图确定性语料）+ `ImageCrossModalEvaluator`（余弦/阈值/同图召回率/异图误召率/判别间隔）+ `ImageCrossModalBenchmarkIT`
  （`RAG_VISION_EMBED_REAL=1` opt-in 真跑）。
- **链路正确性已覆盖**：`ImageVectorSpaceIT`（Testcontainers 真 MySQL + mock 视觉向量，3 例：入库命中/删除双清/重建）。
- **真跑判据已证**：`VisionEmbeddingRealIT`（同图>0.9、异图<0.9）。

## Owner 两项拍板（2026-10-21）

1. **真跑范围 = 真 PDF 解析 + 真视觉 embedding（`RAG_VISION_EMBED_REAL=1`）全链真跑**，落锚独立基线。
2. **评测框架 = 复用 ImageCrossModal 框架**（`ImageCrossModalEvaluator` + Suite 口径）。

## 方案骨架

1. **真 PDF fixture**（新）：确定性 PDF 生成——同一 PDF 里含内嵌结构图（同图变体）+ 异图（跨图型）+ 纯白易负例，
   走 `PdfImageExtractor` 可抽取的 `PDImageXObject`（复用 `ImageVectorSpaceIT#pdfWithImages` 生成器强化为「同图/异图/纯白」
   三型）。
2. **PDF 摄取链评测 IT**（新 `PdfIngestImageBenchmarkIT`）：真 PDF 字节 → `PdfImageSliceIngestService#buildSlices`
   （真 `embedImage`，opt-in）→ `ImageVectorFlushSupport` → image collection → 逐条 query-by-image → 复用
   `ImageCrossModalEvaluator` 的余弦判据（同图命中/异图拒答/判别间隔）→ `Report` 落盘 `docs/rag-quality/pdf-ingest-image-baseline-v1.json`。
3. **对照文本降维**：同一 PDF 的 VLM 描述文本切片走文本 collection，同框架判分，两路指标并列（证明 PDF 摄取链下
   真视觉路增量仍成立）。
4. **无 key 零外呼**：复用 `RAG_VISION_EMBED_REAL` opt-in 键，无 key 整类 Assume 跳过（默认 CI/本地 verify 恒零外呼）。

## 边界与不改动面

- **不动** 摄取/检索生产链路（`src/main` 零 diff）；纯 `src/test` + `docs/rag-quality/`。
- **不碰** baseline-v1~v6 / `image-baseline-v1.json` / `RagBenchmarkSuite` / `SUITE_VERSION`。
- **零迁移 / 零新权限 / 零新依赖 / 零契约改动**（复用既有 evaluator/suite/fixture/opt-in 键）。

## 档位判定

**A 档（真跑）**：真视觉 embedding 真跑（`RAG_VISION_EMBED_REAL`）+ 真 PDF 解析真跑，落锚独立基线。虽零生产代码，
但「真跑判据」触发 A 档独立复核四判据。

## 验收判据

1. PDF 摄取链评测 IT 落地：真 PDF → 真 embedImage → image collection → query-by-image → 余弦判据贯通。
2. 真跑（opt-in）跑通：PDF 内嵌图的同图命中/异图拒答/判别间隔量化，对照文本降维路增量成立。
3. 独立 `pdf-ingest-image-baseline-v1.json` 落锚；无 key 默认零外呼。
4. 独立复核四判据全过。