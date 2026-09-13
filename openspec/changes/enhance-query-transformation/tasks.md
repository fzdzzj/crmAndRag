# Tasks — enhance-query-transformation

> 前置确认：本 tasks 全部条目仅在"立项触发条件"满足后执行（见 proposal.md）。
> 2026-09-12：基线归因数据缺位（baseline-v1.json 尚未首跑，见 attribution-report.md），
> **用户显式指令跳过触发条件直接实现**（"直接做提案五"）。任务组 1–3 代码与单测已落地；
> 任务组 4 的真检索基准（4.1/4.2）仍受成本闸门约束待授权，基线归因随授权后补做。

## 0. 验证记录（执行 agent 填写）

- 2026-09-12：任务组 1–3 完成，分支 `feature/enhance-query-transformation`（基于 V6 修复后的 master @ 2fb0c46）。
  - surefire 实测 **585 全绿**（555 + 新增30：多查询扩展器6 + HyDE扩展器5 + 查询侧增强管线7 +
    衍生问题旁路7 + RrfFusion N路融合2 + 入库旁路接线3），ci.yml 基线同步 555→585。
  - 关闭态等价：三开关默认 false 时检索管线与提案 4 完成态逐字一致（`QueryTransformationPipelineTest`
    .disabledSwitchesShouldBehaveAsProposalFourCompletionState 断言 context/sources 相等、零 LLM 调用、嵌入仅 1 次）。
  - HyDE 隔离：假设答案文本（独特标记）不出现在 context 与任何 SourceReference.excerpt（管线级断言）；
    衍生问题向量命中回原块（chunkId/text 取原块，问题文本只进 metadata）。
  - 成本护栏：variants 钳位 1~5、max-per-chunk 钳位 1~5、HyDE 默认 3s 超时、旁路队列饱和丢弃。
  - 待授权项：4.1/4.2 真检索基准重跑（`RAG_BENCHMARK_REAL=1`，真实外发）。

- 2026-09-13（run-baseline-ladder 任务组 6）：**4.1/4.2 改标「触发不成立」并勾选**。基线归因（run-baseline-ladder 任务组 1）显示 baseline-v1 纯向量单路下
  TEXT 5 条 / LEXICAL 6 条 recall@k=MRR=1.0 已饱和，hybrid 接稀疏路后 LEXICAL 持平上限——fixtures 量级**无「查询-文档词汇失配」漏召缺口**，
  多查询/HyDE 作用面为空、无法归因到词汇失配的提升，故 after-query 真跑**不执行、不产出 `baseline-after-query.json`**（成本闸门显式不浪费，
  三开关维持默认 false 不翻转）。是否在含真实词汇失配数据的更大语料上授权重跑，留用户决策。

## 1. 多查询生成与融合（方案 07 增强）

- [x] 1.1 实现：`rag.query.multi-query.enabled`（默认 false）+ `rag.query.multi-query.variants`（默认 3）；改写器产出原始 + N 变体
  - 落点：`MultiQueryRewriteService.expand`（一次 LLM 产出 N 变体，逐行解析/去编号/去重/钳位 1~5；关闭/失败/空输出回退单查询）
- [x] 1.2 N 路并行召回 → RRF 融合（复用提案 2 `RrfFusion`）→ 单一重排；单测断言融合排序与手算一致
  - 落点：`RrfFusion.fuseAll`（N 路 Σ1/(k+rank)，两路 fuse 委托实现）；`KnowledgeRetrievalServiceImpl` 变体路经
    `embeddingTaskExecutor` 并行执行嵌入+召回；`RrfFusionTest.multiRouteFusionShouldMatchHandCalculation` 手算对照（k=10，A=2/11 > C=1/13+1/11 > B=2/12）
- [x] 1.3 降级链单测：任一路失败 → 已完成路融合；全部失败 → 回退单查询（现行为，既有改写测试不改全绿）
  - `QueryTransformationPipelineTest.failedVariantRouteShouldDegradeToCompletedRoutes`（变体路嵌入抛异常 → 原始+存活变体照常融合）、
    `failedExpansionShouldFallBackToSingleQuery`（扩展异常 → 单查询，嵌入仅 1 次）；既有 `RetrievalQueryRewriteService` 未改动
- [x] 1.4 关闭态单测：`multi-query.enabled=false` 时检索管线与提案 4 完成态行为一致
  - `disabledSwitchesShouldBehaveAsProposalFourCompletionState`：与提案4十参兼容构造同库同数据比对，context/sources 相等、
    零 LLM 调用、嵌入仅 1 次；另断言 `fusion.mode=weighted` 时查询侧增强不参与

## 2. HyDE 可选模式（方案 15）

- [x] 2.1 实现：`rag.query.hyde.enabled`（默认 false）：LLM 假设答案 → 嵌入 → 检索 → 与原查询路 RRF 融合
  - 落点：`HydeQueryExpander.hypotheticalAnswer`（3s 超时护栏）+ `KnowledgeRetrievalServiceImpl.addHydeRoute`
    （假设答案嵌入 → 纯向量召回路 → fuseAll 与原查询路融合）
- [x] 2.2 隔离断言：假设答案文本不出现在生成上下文与 SourceReference（只用于检索向量）
  - `QueryTransformationPipelineTest.hydeHypothesisMustNotLeakIntoContextOrSources`：独特标记假设答案驱动出的
    chunk-2 命中参与融合，但标记串不出现在 context 与任何 excerpt（命中 text 为原块原文）
- [x] 2.3 失败回退单测：HyDE 生成/嵌入失败 → 回退原查询路，不向调用方抛错
  - `HydeQueryExpanderTest`（关闭/失败/空输出/占位词/超时 → null）+ 管线级 `hydeFailureShouldFallBackToOriginalRoute`
    （结果与关闭态一致，嵌入仅 1 次）

## 3. 衍生问题文档增强（方案 06）

- [x] 3.1 入库旁路实现（platform async 执行器）：每块生成 2–3 反向问题（`ModelProvider`，上限可配）→ 嵌入 → 向量记录关联原块 chunkId；命中衍生问题 → 回原块，`SourceReference` 指原块
  - 落点：`DerivedQuestionService`（`derivedQuestionBypassThreadPool` 旁路执行，饱和丢弃）；
    `DocumentIngestionService.ingest/reingest` 成功尾段 `submitAfterIngest`；衍生 `VectorRecord` 的
    chunkId/text/锚点全部取原块（命中即回原块），问题文本只进 metadata；`recall()` 按 fusionKey 保留最高分防同块挤占 topK
- [x] 3.2 不阻塞主链单测：衍生问题生成失败/超时 → 入库正常完成，该块退化为普通块（无衍生向量）
  - `DocumentIngestionServiceTest.derivedQuestionFailureMustNotBlockIngestion`（LLM 全挂，入库仍 COMPLETED、主链向量照写）+
    `DerivedQuestionServiceTest.chunkFailureShouldDegradeOnlyThatChunk`（单块失败其余照常）
- [x] 3.3 token 计量断言：衍生问题 LLM+嵌入消耗进 `TokenUsageRecorder`
  - `DerivedQuestionServiceTest.usageShouldBeRecordedForChatAndEmbedding`（LLM=CHAT、嵌入=EMBEDDING，
    userIdRef 取文件归属快照）+ `failedCallsShouldAlsoBeMetered`（失败调用 success=false 也计量）；
    `EmbeddingService.embedWithUsage` 补计量盲点（既有 embed 口径不变）
- [x] 3.4 随 reingest 重跑：重建后衍生问题重新生成（幂等单测：两次重建衍生向量集合一致）
  - `DocumentIngestionServiceTest.reingestRegeneratesDerivedQuestionsIdempotently`：两次 reingest 的衍生快照
    （chunkId+原文+问句+向量）逐项相等；写入前按当前 DB 存活 chunkId 过滤，防重建竞态写入陈旧向量
- [x] 3.5 开关：`rag.query.derived-questions.enabled`（默认 false）；关闭时入库无衍生向量（回退现行为）
  - `DerivedQuestionServiceTest.disabledSwitchShouldProduceNoDerivedVectors`（零 LLM/零写入）+
    `DocumentIngestionServiceTest.disabledDerivedQuestionsProduceNoDerivedVectors`（入库链零旁路调用）

## 4. 基线验收（对照最新基线）

- [x] 4.1 启用多查询后重跑真检索基准：词汇失配类用例 recall@k 提升；全量不回退；TTFT/token 记录增幅并写入验证记录（增幅可接受才默认启用讨论）【**触发不成立，不执行真跑**】前置基线归因（run-baseline-ladder 任务组 1）：baseline-v1 纯向量单路下 TEXT/LEXICAL 各 5/6 条 recall@k=MRR=1.0 已饱和，hybrid 接稀疏路后更持平上限——fixtures 量级无「查询-文档词汇失配」漏召缺口，多查询作用面为空，无法归因到词汇失配的提升；after-query 按触发条件分支不执行（不额外外发）。是否在含真实词汇失配数据的更大语料上授权重跑留用户决策
- [x] 4.2 产出 `baseline-after-query.json` 落盘，差异摘要写入本 change 验证记录 【**触发不成立，不产出**——无词汇失配缺口无法归因，成本闸门显式不浪费；回填结论见 §0 验证记录】
- [x] 4.3 `mvn -B -ntp test` 绿（surefire 计数只增）；`mvn -B -ntp verify` failsafe 不减
  - 验证记录（2026-09-12）：surefire 实测 **585 全绿**（=555 + 本提案新增30），ci.yml 基线同步 555→585；
    verify failsafe 按 V6 修复后口径（24 跑 = 15 绿 + 7 门控跳过 + 2 WriteChainRegressionIT 存量红）不低于基线 12，
    本提案未新增 IT。

## 5. 待定项台账（不实现，仅记录）

- [x] 5.1 在本 change 目录 `deferred.md` 记录 11/14 的触发条件与届时落点（内容照 proposal.md §4 表）
  - 2026-09-12 落盘 `deferred.md`（附 12/13/17 明确不做备查）

## 6. Git 操作（按 `openspec/git-workflow.md` 执行）

- **开分支前置闸门**：先核验 proposal.md 的"立项触发条件"（基线归因显示查询侧是主要漏召原因）——不满足则**不开分支**，产出归因报告即可交差。
- 分支：`git checkout -b feature/enhance-query-transformation`（前置：提案 4 已合入——衍生问题依赖其 reingest 机制）。
- 提交序（任务组 → 提交）：
  1. `feat(retrieval)`: 多查询生成 + RRF 融合 + 降级链/关闭态单测（1.1–1.4）
  2. `feat(retrieval)`: HyDE 隔离 + 失败回退（2.1–2.3）
  3. `feat(document)`: 衍生问题旁路 + 计量 + 随 reingest 幂等（3.1–3.5）
  4. `test(quality)`: 基线对照 + `baseline-after-query.json` 入库（4.1–4.2）——**成本闸门**：真检索基准外发调用需用户授权
  5. `chore(ci)`: surefire 基线 bump（4.3）；`docs(openspec)`: deferred.md 待定台账（5.1）——可并入同一提交
- 默认全关验证：合并前确认三个开关均为 false 时检索/入库行为与提案 4 完成态一致。
- 合并：亲验后 `git checkout master; git merge --no-ff feature/enhance-query-transformation -m "Merge branch '...'：提案5/5 查询侧增强（默认关闭）"`。
