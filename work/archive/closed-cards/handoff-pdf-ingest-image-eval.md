# handoff · pdf-ingest-image-eval（PDF 摄取链纳入图文评测）

> 档位：A 档（真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）
> 分支：feature/pdf-ingest-image-eval（永久保留）｜M1 merge --no-ff `f356f75` → 基线收口 `0df853c`

## §1 目标与背景
承接 image-cross-modal-eval：上一卡语料是程序化图直入 image 集合（未走 PDF 解析），本卡把「真 PDF 文件 → 真视觉 embedding
→ image collection → query-by-image」端到端贯通，落锚 PDF 摄取链独立基线。Owner 两项拍板：真 PDF + 真视觉真跑 / 复用
ImageCrossModal 框架。

## §2 方案与实现面（4 新测试类 + docs，零 src/main）
- `PdfIngestImageFixture`(397)：确定性三型 PDF（3 页各 1 图：结构图 / 异图柱状图 / 纯白易负例），lossless 编码 + 内存存储替身。
- `PdfIngestImageBenchmarkIT`(781)：真 PDF → `PdfImageSliceIngestService#buildSlices`（真 embedImage + 真 VLM opt-in）→
  `ImageVectorFlushSupport` → image 集合 → 生产 `recallImage` query-by-image → 复用 `ImageCrossModalEvaluator` 余弦判据。
- `PdfIngestImageBaselineReport`：基线 JSON 装配。
- `PdfIngestImageFixtureTest`(7 @Test)：fixture 结构/三型判分/无 key 跳过，零外呼。
- `docs/rag-quality/pdf-ingest-image-baseline-v1.{json,md}` 落锚（PDF-IMAGE-SUITE-1.0）。

## §3 实测结论（真跑 opt-in）
真跑判据全通过（PG-01/02 同图 same=1.000、异图 cross=0.547，通过率 1.0）。三计数由 `CountingProvider` 真调用点累加：
visionEmbeddingCalls=4（摄取暂存 2 + 查询 2）、textEmbeddingCalls=8（描述 2 + 文本查询 6 含检索链内部再编码）、
visionCallCalls=3（VLM 描述外呼，纯白图也真调一次——质量闸门在调用之后只丢结果不省调用）；单列 slicedImages=2 /
gateRejectedImages=1 / extractedImages=3。

## §4 零迁移 / 零权限 / 零契约论证
- `git diff --stat -- src/main/` 零命中；pom.xml/application.yml 未触。
- 零新 controller/端点/权限常量；评测 KB 授权走 Mockito 桩。
- `RagBenchmarkSuite`/`SUITE_VERSION="6.0"`/`Category.IMAGE`/baseline-v1~v6/image-baseline-v1 全未动（新增独立 PDF-IMAGE-SUITE-1.0）。

## §5 真跑论证（A 档判据）
`PdfIngestImageBenchmarkIT` 真链路：真 PDF → `PdfImageExtractor` → 真 `embedImage`（`ImageVisualVectorSupport`）→
`ImageVectorFlushSupport` → image 集合 → 生产 `KnowledgeRetrievalServiceImpl#recallImage`。余弦非硬编码（由实际向量算，
阈值复用 `ImageCrossModalSuite` 0.90）；JSON 同图 1.0000000000000002、异图双向 0.5473838705259706 双精度严格相等（手写无法复现）。

## §6 偏差处置与治理留痕
- **D1（复核① 未提交）**：执行端首轮产出全 untracked（HEAD 停在 master tip）；退回后补 4 笔 commit（ac333e7 test /
  e0ddf99 docs / 9c7b0bc 计数 / 56fab0a 重锚）→ 工作树干净。
- **D2（复核③ 计数瑕疵 + 分歧裁决）**：复核方指出三计数用公式推导（keys.size()+cases.size()）不实，参考值 visionCall=2；
  执行端改为 `CountingProvider` 真调用点累加后给 visionCallCalls=3——**分歧裁决 = 执行端正确**：质量闸门在 VLM 调用**之后**（
  `PdfImageSliceIngestService#describeAndStore` L246-256 先 `describe()` 外呼再 `passesQualityGate` 判闸门），纯白图也真调一次 VLM
  只是结果被闸门丢。复核方的「2」实为「产描述数」，执行端已单列 slicedImages=2 与 gateRejectedImages=1 双口径留痕，无歧义。这是复核
  良性交互：复核抓对「公式推导」问题，执行端真计数给出更准结论。
- **D3（纯白图被 40 字描述闸门剔除）**：PG-03 的纯白异图未进 image 集合（描述不足 40 字），落在 skippedCases 而非误判，三型语料
  判据仍可辩护（纯白图作判据地板）。

## §7 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`06952fe..0df853c`）。未 reset/rebase/amend/删分支。