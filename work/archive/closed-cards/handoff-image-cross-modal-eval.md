# handoff · image-cross-modal-eval（图文跨模态召回质量评测）

> 档位：A 档（真跑 opt-in 落锚；零生产代码/零迁移/零权限/零新依赖）
> 分支：feature/image-cross-modal-eval（永久保留）｜M1 merge --no-ff `90fc2cb` → 基线收口 `03e0f7b`

## §1 目标与背景
承接 multimodal-shared-space：真视觉向量路已落地，本卡量化「真视觉 query-by-image 召回」vs「文本降维描述召回」的
检索质量对照，验证真视觉路的增量价值。Owner 三项拍板：独立 IMAGE-SUITE 套件 / 授权真跑 + 独立落锚 / 同异图余弦判别。

## §2 方案与实现面（8 文件 +1849/−7，纯 src/test + docs，零 src/main）
- `ImageCrossModalSuite`(128)：IMAGE-SUITE-1.0，独立编号 IM-01~IM-05。
- `ImageCrossModalFixture`(290)：6 张确定性程序化图（4 内容 + 记卡 + 纯白易负例），生产同口径 payload 幂等 upsert。
- `ImageCrossModalEvaluator`(329)：余弦/阈值/同图召回率/异图误召率/判别间隔 + 基线 JSON 装配。
- `ImageCrossModalBenchmarkIT`(431)：`RAG_VISION_EMBED_REAL=1` opt-in 真跑，无 key 整类 Assume skip（默认零外呼）。
- `ImageCrossModalSuiteTest`(288)：8 单测（套件/阈值边界/fake 向量端到端/无 key 跳过），零外呼。
- `docs/rag-quality/image-baseline-v1.json`(297) + `image-baseline-v1.md`(79) 落锚。

## §3 实测结论（真跑 opt-in）
真视觉判别间隔 **0.505**、异图误召 **0%**、同图召回 **100%**、passRate 1.0；文本降维对照 **0.194**、异图误召 **60%**、
passRate 0.4。真视觉增量成立（判别间隔 2.6×，异图误召 −60pp）。同一 `ImageCrossModalEvaluator` 喂两路，指标可直接相减。

## §4 零迁移 / 零权限 / 零契约论证
- `git diff --stat master...feature -- src/main/` 零命中（复核①）；pom.xml / application.yml 未触。
- 零新 controller/端点/权限常量（纯 src/test）；IT 仅 mock 授权桩 + UserContextHolder。
- `RagBenchmarkSuite` / `SUITE_VERSION="6.0"` / `Category.IMAGE` / baseline-v1~v6 全未动（只新增 image-baseline-v1）。

## §5 真跑论证（A 档判据）
`ImageCrossModalBenchmarkIT` 真链路：`provider.embedImage`（真外呼）→ `KnowledgeRetrievalServiceImpl` 装 `imageVectorStore`
→ `recallImage` 查 image 集合（knowledgeBaseId + category=IMAGE 过滤）；JSON 可逐条复算（视 0.49509/间隔 0.50491 vs 文
0.80610/0.19390），非硬编码；含自损指标（retrievalCrossImageRate 0.8、文本 passRate 0.4、IM-05 同图 1.0000000000000002），
测试不钉数值、退化为文本降维必红；复核方独立复算非假绿。

## §6 偏差处置与治理留痕
- **D1（文档缺陷，复核方非阻断观察）**：`image-baseline-v1.md` §1 表格文本路 per-case 异图余弦误抄为均值
  （0.81/0.81/0.80/~0.80/低），JSON 实为 0.991/0.991/0.945/0.615/0.488，且 IM-04（0.615<0.90）误标「误召」实为「正确拒答」；
  **主 Agent M1 顺手修正**（`5254dd1`，提交进分支再 merge）。
- **D2（VLM 描述文本是确定性替身）**：未真调 `ModelProvider#vision`，文本降维路用「VLM 描述文本的确定性替身」；
  只影响「文本降维路上限」严格程度，不影响两路判分口同型；已在文档 §4 登记为后续卡增强项。
- **D3（语料不含 PDF 摄取链）**：程序化确定性图，走 `ImageVectorFlushSupport` 同口径 payload 直入 image 集合；
  「PDF 内嵌图 → 视觉召回」评测需 Docker 真库 + 真 PDF 资产，属后续卡（该段已由 `ImageVectorSpaceIT` + multimodal-shared-space 覆盖）。
- **D4（配色差异图真视觉余弦 0.98）**：仅配色不同的蓝/橙流程图真视觉余弦约 0.98（跨模态 embedding 主场 = 结构非配色），
  已在文档 §2 登记为判据边界，不作异图负例。

## §7 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`6b5248b..03e0f7b`）。未 reset/rebase/amend/删分支。