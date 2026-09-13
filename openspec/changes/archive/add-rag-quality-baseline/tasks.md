# Tasks — add-rag-quality-baseline

## 1. 基准集扩充与版本化

- [x] 1.1 `RagBenchmarkCase.Category` 新增 `LEXICAL`，javadoc 注明"词法精确型：型号/编号/专名，考察向量漏召下词法命中"
- [x] 1.2 `RagBenchmarkSuite.standard()` 扩至 18 条：LEXICAL 6（含 L-01/L-02 两条"向量相似度低但文本精确包含目标词"构造）、TEXT 5、TABLE 3、IMAGE 2、EDGE 2；每条 `expectedChunkIds`/`expectedAnswerPoints` 填实
- [x] 1.3 新增 `SUITE_VERSION = "1.0"` 常量并写入 `RagQualityReport.Report`（`suiteVersion` + `generatedAt` 字段）；`RagQualityEvaluatorTest.reportCarriesSuiteVersionAndTimestamp` 断言报告携带版本与时间戳
- [x] 1.4 `RagQualityEvaluatorTest.lexicalCasesScoreBySameSemanticsAsOtherCategories`：LEXICAL 有黄金=正常 recall；expected 空边界=空集合语义不变

## 2. 评测语料与数据准备

- [x] 2.1 `src/test/resources/rag-quality/fixtures/`：6 份文档（合同流程 md、回款计划 md、区域销售 xlsx、架构图描述 txt、设备型号目录 md、合同代号登记册 txt），黄金段落按 ~320 字扩写使各黄金片段独占切片
- [x] 2.2 `RagBenchmarkDataPreparer`（测试域）：清评测 KB → 真实 `DocumentService` 分块 → 剥离 `【GOLD:id】` 标记 → 入库 → 输出占位 id→chunkId 映射；`rewriteSuite` 完成黄金集占位 id 全量替换
- [x] 2.3 幂等单测 `RagBenchmarkDataPreparerTest.rerunProducesIdenticalChunkIdSetAndMapping`：同一库连续两次 + 全新库，chunkId 集合与黄金映射一致
- [x] 2.4 对齐校验 `validateAlignment`：未对齐占位 id 显式 `IllegalStateException` 并报出 id（单测 `unalignedGoldenIdFailsExplicitlyInsteadOfSilentEmptySet`）；另有套件↔语料双向同步单测

## 3. 真检索基准 runner（env 门控）

- [x] 3.1 `RagRealRetrievalBenchmarkIT`（failsafe）：`RAG_BENCHMARK_REAL=1` 且有 `DASHSCOPE_API_KEY`（env 或 .env）才执行；否则 `Assumptions` 跳过
- [x] 3.2 走真 `KnowledgeRetrievalServiceImpl` 全链路（真改写 + 真嵌入 + InMemoryVectorStore 召回 + BM25 融合重排），授权为评测桩；答案流式生成带 [n] 引用，判卷由模型逐点判定要点覆盖
- [x] 3.3 报告落盘 `docs/rag-quality/baseline-v1.json`：suite 版本、时间戳、recall@k/precision@k/MRR/hitRate/citationPrecision/答案一致性/token/TTFT/延迟/失败率
- [x] 3.4 无 env 实测：`failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT` → `Tests run: 1, Skipped: 1`，BUILD SUCCESS，不发外网请求，口径 B 测试数不减

## 4. 基线记录与门禁确认

- [x] 4.1 在有 key 的环境跑出基线，确认 `docs/rag-quality/baseline-v1.json` 存在且指标齐全（run-baseline-ladder 第一跑已授权执行，metrics 见 `baseline.md`）
- [x] 4.2 `mvn -B -ntp test` 绿：surefire 合计 **473**（基线 467 + 新增 6）；failsafe 口径白名单实测新 IT +1（skip），全量 verify 门禁由 CI 落地确认
- [x] 4.3 `ci.yml` 阶段 2 注释补充"真检索基准 runner 为 env 门控、不进默认 CI"的说明（未改门禁逻辑与基线数字）
- [x] 4.4 `baseline.md` 已建（运行环境/指标口径/复现命令），基线数字摘要已随 4.1 首跑回填（run-baseline-ladder）
