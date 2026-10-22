# 任务清单 · pdf-ingest-image-eval

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 真 PDF fixture
- [x] 1.1 确定性 PDF 生成：同一 PDF 含内嵌结构图（同图变体）+ 异图（跨图型）+ 纯白易负例，走 `PdfImageExtractor` 可抽取的 `PDImageXObject`（复用/强化 `ImageVectorSpaceIT#pdfWithImages` 生成器为三型）
      补勾依据（2026-10-21，`mvn -B -ntp test -Dtest=PdfIngestImageFixtureTest` → Tests run 7, Failures 0）：
      `PdfIngestImageFixture` 生成 3 页 PDF（`pdf-structure` 结构图 / `pdf-bars` 跨图型柱状图 / `pdf-blank` 纯白易负例，一图一页、
      lossless `PDImageXObject`、最短边 220px ≥ 抽取器 64px 闸门）；`pdfCarriesThreeExtractableImagesInCorpusOrder` /
      `fixtureIsDeterministic` / `threeTypesAreVisuallyDistinct` 三例绿（结构图↔柱状图像素差异 >15%，纯白图近白占比 >99%）。

## 2. PDF 摄取链评测 IT
- [x] 2.1 `PdfIngestImageBenchmarkIT`：真 PDF 字节 → `PdfImageSliceIngestService#buildSlices`（真 `embedImage` opt-in）→ `ImageVectorFlushSupport` → image collection → query-by-image → 复用 `ImageCrossModalEvaluator` 余弦判据 → `Report` 落盘 `docs/rag-quality/pdf-ingest-image-baseline-v1.json`；无 key 整类 Assume 跳过
      补勾依据（2026-10-21 真跑：`RAG_VISION_EMBED_REAL=1 mvn -B -ntp failsafe:integration-test failsafe:verify
      -Dit.test=PdfIngestImageBenchmarkIT -Drag.pdf.image.vision-model=qwen3-vl-plus` → Tests run 1, Failures 0, EXIT=0）：
      摄取 `pages=3 slices=2 flushed=2`；落盘 `docs/rag-quality/pdf-ingest-image-baseline-v1.json`（7845 B，
      `suiteVersion=PDF-IMAGE-SUITE-1.0`）；真视觉 `sameImageHitRate=1.0` / `crossImageFalsePositiveRate=0.0` /
      `retrievalRecallAtK=1.0` / `passRate=1.0`。默认无 key 口径：`mvn -B -ntp clean verify` 里本类 Skipped=1（零外呼）。
- [x] 2.2 对照文本降维：同一 PDF 的 VLM 描述文本切片走文本 collection，同框架判分，两路指标并列
      补勾依据（同一轮真跑）：文本降维路走真 VLM 描述文本 + 真文本 embedding + 生产检索链，指标与真视觉路并列写进同一 JSON
      （`vision` / `textBaseline` / `comparison` 段）：判别间隔 0.453（真视觉）vs 0.333（文本降维），
      `discriminationMarginDelta=+0.120`；异图余弦 0.547 vs 0.667（描述文本把跨图型拉近，增量位点成立）。

## 3. 测试与验证
- [x] 3.1 单测：PDF fixture 生成器 / 三型图判分口径 / 无 key 跳过（fake 向量回函，零真外呼）
      补勾依据（2026-10-21，`mvn -B -ntp test -Dtest=PdfIngestImageFixtureTest` → Tests run 7, Failures 0, Errors 0,
      Skipped 0）：覆盖 fixture 三型/确定性、`buildSlices` + `flush` 全链（Mockito 桩：vision 3 次、embedImage 3 次，
      **零真外呼**）、payload 口径（`knowledgeBaseId`/`sourceType=IMAGE`/`imageAssetId=chunkId`/
      `SourceReferenceFactory` → `IMAGE:{行主键}`）、开关关零调用、阈值两侧判分边界、无 opt-in/无 key 均抛
      `TestAbortedException`。
- [x] 3.2 `mvn -B -ntp clean verify` BUILD SUCCESS（Testcontainers 真库，Docker 已启动）+ 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
      补勾依据（2026-10-21）：`mvn -B -ntp clean verify` → **BUILD SUCCESS**（Total time 16:49），surefire
      `Tests run: 1953, Failures: 0, Errors: 0, Skipped: 0`，failsafe `Tests run: 132, Failures: 0, Errors: 0, Skipped: 10`
      （含本卡 opt-in 1 skipped，**skipped 9→10 如实上调，未压低**；Testcontainers 真库 IT 真跑，Docker 在线的
      `ImageVectorSpaceIT`/`ImageSliceRetrievalIT` 等全绿）；`bash scripts/check-test-baseline.sh --update` 写入
      `surefire 315/1953/0` + `failsafe 39/132/10`，随后 `bash scripts/check-test-baseline.sh` → **EXIT=0**（门禁通过）；
      `bash scripts/merge-gate.sh --with-verify` → **11/11 PASS**（unit/checkstyle/spotbugs/spotless/pmd/it/baseline/
      frontend-unit/hook/bijection/pmd-baseline；本卡写作时门禁已扩到 11 项，实际 11/11 而非卡面 10/10）。

## 4. 收口
- [ ] 4.1 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）
      进度：**停步纪律已遵守**（本卡全程未 merge、未 push、未 reset/rebase/amend、未删分支；工作分支
      `feature/pdf-ingest-image-eval`，只在 `src/test/com/slz/crm/quality/` 与 `docs/rag-quality/` 落文件，`src/main` diff=0，
      `frontend/typed-router.d.ts` 跑门禁产生的行尾噪声已 `git checkout --` 还原）。归档 / handoff / 看板更新留给收口轮，本项未勾。

## 实测留痕（本卡的两个真发现，细节见 `docs/rag-quality/pdf-ingest-image-baseline-v1.md` §5/§7）

1. **纯白易负例在真链路上进不了 image 集合**：`PdfImageSliceIngestService` 要求 VLM 描述过质量闸门（≥40 字），纯白图真描述不足
   40 字 → 该图连描述切片带视觉向量一起被丢弃（实测 `page=3` 命中该分支）。故 `PG-03`（结构图 ↔ 纯白）无向量可作探针，连同原因
   写进基线 JSON 的 `skippedCases` 段，**未改语料、未放宽闸门**。是否要为"易负例"在摄取链上留判分位，属待决策项。
2. **本机视觉模型可用性**：生产默认 `qwen-vl-plus` 在该 `DASHSCOPE_API_KEY` 上返回 `404 model_not_found`（同机既有
   `VisionPdfRealPilotIT` 同症状，与本卡改动无关）。真跑改用该 key 可用的 `qwen3-vl-plus`（`-Drag.pdf.image.vision-model=`），
   实际模型名落盘在 `pdfIngest.visionModel`。换环境若生产默认可用则去掉该属性。
