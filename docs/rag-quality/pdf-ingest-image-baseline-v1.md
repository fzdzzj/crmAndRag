# PDF 摄取链图文评测基线 v1（PDF-IMAGE-SUITE-1.0）

> 落盘物：`docs/rag-quality/pdf-ingest-image-baseline-v1.json`（本文件的全部数字取自该 JSON）。
> 生成方式：`PdfIngestImageBenchmarkIT` 真跑（`RAG_VISION_EMBED_REAL=1`）。**只读基线**，不含任何生产代码改动。

## 1. 这条基线与 `image-baseline-v1` 的分工

| 维度 | `image-baseline-v1`（上一卡） | `pdf-ingest-image-baseline-v1`（本卡） |
|---|---|---|
| 语料入口 | 确定性 PNG **直喂** `embedImage` | 确定性 **PDF**（内嵌 3 张图）→ 真解析 |
| 经过摄取链 | 否（旁路造向量，只对齐 payload） | 是：`PdfImageExtractor` → `buildSlices` → `embedAndStage` → `flush` |
| 判据 | `ImageCrossModalEvaluator` 余弦（同图 ≥0.9 / 异图 <0.9） | **同一判据、同一阈值**（复用，不新造） |
| 套件版本 | `IMAGE-SUITE-1.0` | `PDF-IMAGE-SUITE-1.0`（独立编号，不改上一卡） |
| 落盘文件 | `image-baseline-v1.json` | `pdf-ingest-image-baseline-v1.json` |

本卡回答的问题：**真视觉的判别力在"真 PDF 摄取链"上是否仍然成立**，并把"文本降维路"（VLM 描述 → 文本向量）作为并列对照。

## 2. 真跑链路（每段都是生产实现，非桩）

1. `PdfIngestImageFixture#pdfBytes()`：确定性 PDF，3 页各 1 张 `PDImageXObject`（lossless），页序 = 语料顺序。
2. `PdfImageSliceIngestService#buildSlices`：`PdfImageExtractor` 取 `PDImageXObject` → 解码 PNG → 真 VLM 描述（质量闸门 ≥40 字）
   → 图片对象落 `FileStorageService`；`imageVectorEnabled=true` 时 `ImageVisualVectorSupport#embedAndStage` 对同一张图调真
   `embedImage` 暂存（storageKey → 向量）。
3. `ImageVectorFlushSupport#flush`：按 storageKey 把暂存向量回填到切片行（`chunkId` = 切片行主键），payload 走
   `DocumentIngestionSupport#createVectorRecord` 单一实现 → image 集合。
4. query-by-image：查询图重新真 `embedImage` → 生产 `KnowledgeRetrievalServiceImpl#recallImage`（过滤 `knowledgeBaseId` +
   `category=IMAGE`）→ 余弦判分。
5. 文本降维对照路：同一批 VLM 描述文本 → 真文本 embedding → 文本集合 → 同框架同判据判分。

## 3. 语料与用例（跑之前固定，不按结果调参）

- 语料 3 图（一图一页）：`pdf-structure`（结构示意图）/ `pdf-bars`（柱状图，跨图型异图）/ `pdf-blank`（纯白易负例）。
- 用例 3 条：`PG-01`（结构图 ↔ 柱状图）、`PG-02`（柱状图 ↔ 结构图）、`PG-03`（结构图 ↔ 纯白）。同图 = 查询图自身的再次编码。
- 黄金 id = 生产口径 `IMAGE:{切片行主键}`（`SourceReferenceFactory.IMAGE_CHUNK_PREFIX`），不是合成键。

## 4. 实测结果（2026-10-22，本机，`qwen3-vl-plus`）

摄取链：`pages=3 extracted=3 sliced=2 flushed=2`（纯白图被描述质量闸门剔除，见 §5）。

### 4.1 真实外呼计数（复核必修②：调用点实测，非公式推导）

三计数由测试侧 `CountingProvider`（真 Provider 的转发装饰器）在调用点累加，**不是** `keys/cases` 公式：

| 计数（JSON 字段） | 实测值 | 口径 |
|---|---|---|
| `visionCallCalls` | **3** | VLM 描述外呼 = 提取图数。**每次提取图都真调一次**（含纯白图）：质量闸门在调用之后，只丢弃不合格结果，不省调用 |
| `visionEmbeddingCalls` | **4** | `embedImage` 次数 = 摄取侧 2（过闸进 image 集合的图）+ 查询侧 2（query-by-image 用例数） |
| `textEmbeddingCalls` | **8** | 文本 `embed` 次数 = 描述编码 2 + 文本查询侧 6（2 条用例合计，含检索链内部再编码） |

`pdfIngest` 段的图数语义（勿混读）：`extractedImages=3`（生产提取器真实提取图数，经反射调用
`PdfImageExtractor#extract` 实测）、`slicedImages=2`（过闸切片数）、`gateRejectedImages=1`、
`stagedVisionVectors=2`（实际暂存的视觉向量条数）；另有 `ingestImageEmbeds=2` / `queryImageEmbeds=2` /
`descriptionTextEmbeds=2` / `queryTextEmbeds=6` 为上述三计数的分段明细。

| 指标 | 真视觉路（`pdf-vision-query-by-image`） | 文本降维路（`pdf-text-caption-baseline`） | 差值（正 = 真视觉更好） |
|---|---|---|---|
| 用例数 | 2 | 2 | — |
| 同图命中率 | 1.00 | 1.00 | 0.00 |
| 异图误召率 | 0.00 | 0.00 | 0.00 |
| 同图余弦均值 | 1.000 | 1.000 | — |
| 异图余弦均值 | 0.547 | 0.682 | — |
| 判别间隔均值 | **0.453** | **0.318** | **+0.134** |
| 检索层同图召回率@5 | 1.00 | 1.00 | 0.00 |
| 逐条判据通过率 | 1.00 | 1.00 | — |

逐条（真视觉）：`PG-01` same=1.000 / cross=0.547；`PG-02` same=1.000 / cross=0.547。

**读法**：两条路的同图命中都是 1.00（查询图是同一视觉内容的再次编码，这是链路自检），差别在异图侧——描述文本把"结构图/柱状图"
拉到 0.682，真视觉把它们压到 0.547，**判别间隔 +0.134**。增量方向成立，但幅度小于上一卡（上一卡 6 图语料 +0.311），原因是
本卡异图只有一对跨图型且描述文本差异较大；本卡的价值在"链路贯通 + 方向可量化"，不在扩大差距。文本侧绝对值随 VLM 描述文本
逐次措辞略有漂移（同一语料重跑约 ±0.015），方向与量级稳定。

## 5. 本卡的真发现（不是缺陷绕过，是链路事实）

**纯白底图在真链路上不会进 image 集合**：`PdfImageSliceIngestService` 要求 VLM 描述过质量闸门（`VisionIngestSupport`
`MIN_COMPACT_CHARS=40`），纯白图的真描述不足 40 字 → 该图连描述切片带视觉向量一起被丢弃（本机实测 `page=3` 命中该分支）。
后果：`PG-03`（结构图 ↔ 纯白）**无法判分**（没有它的向量，拿它当异图探针等于自造向量）。

处置：`PG-03` 连同原因写进基线 JSON 的 `skippedCases` 段，不静默丢、也不为凑三型去改语料或放宽闸门。这也意味着"纯白易负例在
PDF 摄取链上天然零误召风险"——它在摄取期就被拦下，不需要检索期判别力。上一卡（旁路直喂 `embedImage`）里纯白图的余弦 0.184
仍可作判据地板，两卡结论互补。

## 6. 复现命令

```bash
# 1) 真跑（落盘 baseline；无 key 或未 opt-in 时整类 Assume 跳过，零外呼）
RAG_VISION_EMBED_REAL=1 mvn -B -ntp failsafe:integration-test failsafe:verify \
  -Dit.test=PdfIngestImageBenchmarkIT

# 2) 默认全量（零外呼，本类 +1 skipped）
mvn -B -ntp clean verify
```

**本机模型事实（留痕，影响复现）**：生产默认视觉模型 `qwen-vl-plus` 在该 `DASHSCOPE_API_KEY` 上返回
`404 model_not_found`（同机既有 `VisionPdfRealPilotIT` 同症状，与本卡改动无关），故真跑用
`-Drag.pdf.image.vision-model=qwen3-vl-plus`；实际使用的模型写进 JSON 的 `pdfIngest.visionModel`。换环境若生产默认可用，去掉该
属性即可，落盘物会如实记录模型名。

## 7. 未解决项 / 待决策项

1. **纯白易负例的可判性**（待决策）：要在 PDF 摄取链上给"易负例"留判分位，需要它过描述闸门。可选：① 接受现状（记为
   `skippedCases`，本卡做法）；② 把第三型换成"近纯白 + 少量浅灰网格/一行小字"的低信息图（会偏离"纯白"字面）；
   ③ 生产侧把视觉向量路从描述闸门解耦（属 `src/main` 改动，本卡禁改，需另开卡）。
2. **视觉模型可用性属环境事实**：本机无法用生产默认 `qwen-vl-plus` 真跑，基线里的模型名是覆盖值；若 CI 要真跑需固定该属性。
3. **异图判据幅度**：本卡异图仅一对跨图型，判别间隔 +0.12；若要更强的增量证据，应在后续卡扩语料（不改判据阈值）。
