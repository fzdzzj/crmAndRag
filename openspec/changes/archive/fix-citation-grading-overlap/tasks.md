# tasks — fix-citation-grading-overlap

> 红线：所有测试/门禁运行前 `DASHSCOPE_API_KEY` 置空字符串；surefire 只增不减；旧锚点 20 文件 SHA256 零漂移；indexedText/embedText 逐字节不变；生产链路（server/ai、knowledge 切分）零改动；真跑（RAG_BENCHMARK_REAL=1）未经 owner 授权不得执行。

## 任务组 1 · 承载判定实现（quality 评测域）

- [x] 1.1 RagBenchmarkDataPreparer 增 chunk 区间游标（顺序 indexOf 求每块 [start,end)），红测先行：三形态（重叠复本承载 / 跨块段落承载 / 无承载零误报）
- [x] 1.2 黄金句 span 界定 + 承载集计算（`goldenToCarrierChunkIds` 多值映射，MIN_CARRIAGE_CHARS=16；主锚块恒在集内；tornMarkers 场景自然涵盖）
- [x] 1.3 runner 接线：citP 的 expected 入参切承载集；recall/ansC 口径不动（主锚块）；RagQualityEvaluator 仅 Javadoc 注明口径
- [x] 1.4 向量输入红线断言：indexedText/embedText 与改动前逐字节一致；旧锚点 20 文件 SHA256 跑前跑后一致

## 任务组 2 · 测试与口径锁

- [x] 2.1 既有 RagBenchmarkDataPreparerTest 10 测适配全绿；新增承载判定单测（含零误报形态与 MIN_CARRIAGE 边界）
- [x] 2.2 mock 全量评测断言：承载集非空例 citP 不降、确定性可复算；recall/ansC 逐例不变（防口径分离失守）
- [x] 2.3 中文 Javadoc 标注「fix-citation-grading-overlap 任务 x.x」

## 任务组 3 · spec 登记与门禁

- [x] 3.1 specs/rag-quality/spec-delta.md 登记：旧口径 → 多块承载新口径冻结条款 + 「判卷侧不改切分」辨权声明（D4）
- [x] 3.2 全量门禁：`mvn -B -ntp test` 0 失败；四静态 0 违规；三守卫 CLEAN；merge-gate 全绿；surefire 基线 `--update` 只增不减（1091 → 1092+）
- [x] 3.3 停步回报：分支自证、提交笔、门禁 raw 关键行、写集清单、锚点零漂移证明

## 任务组 4 · 真跑锚点切换（另授权，本段未授权不执行）

- [x] 4.1 【另授权】owner 授权 RAG_BENCHMARK_REAL=1 后：63 例全量单轮真跑（预估 ~290s / tokens ~60k），产出 baseline-v4.json（suiteVersion=3.0 + scoringVersion=v2）
- [x] 4.2 【另授权】锚点校验增量切 v4 + scoringVersion 登记 + tasks.md §0 真跑留痕（清开关自证 + 锚点前后 SHA256）
- [ ] 4.3 【另授权】修复笔/证据笔提交与 push 请示（提交已按组分笔完成：fd30d6c feat + 本 docs 笔；push 请示=停步回报，待 owner 处置）

## §0 执行留痕（执行方：执行子 agent，2026-10-11，权威树 feature/fix-citation-grading-overlap）

**红测先行（1.1）**：先落 `RagBenchmarkCarrierTest`（7 测）+ `RagBenchmarkCarrierScoringTest`（2 测）再实现。红态留痕 `work/_red-run.log`：`BUILD FAILURE`，COMPILATION ERROR——找不到符号（实际日志无 MVN_EXIT 字面标记，以 BUILD FAILURE + 缺符号清单为红态实证） `goldenToCarrierChunkIds` / `citationExpectedByCase` / `computeCarrierChunkIds` / `MIN_CARRIAGE_CHARS` 与 4 参 `evaluate`（新 API 未实现）。实现后同两文件 + 既有 10 测 + evaluator 7 测共 26/26 绿。

**span 口径实测校准（1.2，偏差披露）**：初版按「标记起点至句末（首个句读符 。！？\n）」实现，实测 `overlapReplicaChunkCarriesGoldenSentence` 红——payment-plan-1 复本块不承载。核对 fixture：标记位于**段首、标注整段**，取证 T-02 被复本的「项目明确每一期的验收前置条件；风险识别…」是段尾内容，span 截到首个句号漏掉段尾。校准为**段末（首个换行）**口径（与 fixture 标注语义一致），MIN_CARRIAGE 合成边界测试同步重排几何（span 末界由换行控制，交集 = 40 − tail），T-15 几何经 fixture 实测核实后保持预测（完整标记在 customer-sop-2、段落延入 customer-sop-3 约 250 字符）。校准已锁进单测与 Javadoc。

**既有 10 测适配（2.1）**：`RagBenchmarkDataPreparerTest` 10 测**零适配**全绿——主锚映射、标记剥离、撕裂检出零行为变更的机械证明。

**向量输入红线（1.4）**：实现前临时探针 `VectorInputDigestProbe`（跑毕即删）实测 57 切片：indexedTexts SHA256=`f51a8ae4…e8bf4a3`、embedTexts SHA256=`e929b831…3679c`（全值见 `RagBenchmarkCarrierTest.vectorInputsStayByteIdentical` 钉值）；实现后该测试断言绿 = 逐字节不变。探针残留报告曾致基线门禁一次红（target/surefire-reports 口径外残留判红，与 gold-marker-tearing 卡同源坑），删残留报告后 PASS。

**基线 bump（3.2）**：全量真跑后一次 `bash scripts/check-test-baseline.sh --update`：surefire reports 184→186、tests **1091→1100**、skipped 0；failsafe 27/98/6 不变；source-revision=798c8a0。diff 核对仅上述行变化（只增不减）。全量 `mvn -B -ntp test`：`Tests run: 1100, Failures: 0, Errors: 0, Skipped: 0`。

**门禁 raw（3.2）**：四静态——checkstyle `0 Checkstyle violations`；spotless `BUILD SUCCESS`；spotbugs `BugInstance size is 0` + 双射 `RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）`；pmd 实测 0 + `RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）`。merge-gate 第一轮（HEAD=c478bbf，组 3 文档笔前树）：`GATE_EXIT=0`，[unit][spotbugs][pmd][baseline][frontend-unit][hook][bijection][pmd-baseline] 8/8 PASS，其中 [unit] `Tests run: 1100, Failures: 0`、[baseline] surefire 186/1100/0/0（基线 >=186/>=1100/<=0）、failsafe 27/98/6。组 3 文档笔提交后复跑 merge-gate 为最终证据（见停步回报），三守卫（check-dirty / check-write-set / check-line-endings）同轮留证。

**外呼防线**：每条 mvn/门禁命令均以 `export DASHSCOPE_API_KEY=""` 前缀执行（宿主环境存在真实 key，任务书红线即为此设）；本轮全部运行仅 surefire 域（failsafe IT 未执行），`RAG_BENCHMARK_REAL` 未设置，零真实外呼。

**真跑另授权节点（任务组 4，2026-10-11 owner 授权后执行）**：

- **红测先行（4.2）**：`RagBenchmarkV4AnchorValidationTest` 10 测先落。红态留痕 `work/_fixcitation-tg4-red-run.log`：`BUILD FAILURE`，COMPILATION ERROR 找不到符号 13 处（实际日志无 MVN_EXIT 字面标记，以 BUILD FAILURE + 缺符号清单为红态实证）（`V4_ANCHOR_FILE_NAME` / `validateAgainstV4Anchor` / `V4_SCORING_VERSION` / `withScoringVersionRegistration` 等新 API 未实现）。实现后 10/10 绿。首跑曾被 spotless GJF javadoc 重排挡一道（validate 先于编译），`spotless:apply` 归一（只动本卡新文件）后取得编译红。
- **锚点校验切换形态（4.2，design D3）**：产物文件名=baseline-v4.json **且锚点在场**才对账——citP 按 scoringVersion=v2（多块承载）对锚点 `meanCitationPrecision`，recall/ansC 沿用**同一锚点文件** `meanRecallAtK`/`meanAnswerConsistency`，下限 = 记录值 × 0.95（RagQualityRegressionTest FLOOR_RATIO 房风，聚合均值粒度）；v4 缺席（首次真跑产出前）或产物非 v4 整体跳过——锚点校验不因本卡变红，CI failsafe 跳过口径恒 6 不变。写侧：v4 产物落盘登记 `scoringVersion=v2`（紧跟 suiteVersion，与 v3 键序对齐）；非 v4 产物永不获得该键。
- **真跑命令原样（4.1，唯一授权外呼）**：`RAG_BENCHMARK_REAL=1 mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=RagRealRetrievalBenchmarkIT" "-Drag.benchmark.out=docs/rag-quality/baseline-v4.json" "-Drag.benchmark.trace.out=work/_fixcitation-tg4-forensics.json"`。
- **偏差披露（trace.out 改道）**：先例卡 P-ai 真跑未带 trace.out、随跑覆写并提交了 i05-forensics.json（c4a3491 同笔可见）；本卡红线是 docs/rag-quality 既有 20 文件 SHA256 零漂移，故加 `-Drag.benchmark.trace.out` 把取证旁路引到 work/（gitignore 域），i05-forensics.json 跑后零改动（SHA256 前后一致见停步回报）。
- **Docker 与耗时**：`docker info` 实测在线（Server 29.6.2）；实跑日志无 Testcontainers 容器拉起（RagReal 本体为纯 JVM + InMemoryVectorStore + DashScope 外呼，MySQL 依赖属 failsafe 域其他 IT）。Maven 全程 **310s**（failsafe `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` 耗时 **287.3s**，与先例 290.7s 同量级）；`totalTokens=61584`（答案生成+判卷两段口径，先例 61734 同口径），未触额度。
- **63 例 V1 指标摘要**：caseCount=63 / failureRate=0 / hitRate=1.0 / meanRecallAtK=**0.955026455026455**（与 v3 逐位一致）/ mrr=0.9206349206349206（与 v3 逐位一致）/ meanAnswerConsistency=0.9550264550264548（与 v3 逐位一致）/ **meanCitationPrecision=0.9243386243386243**——v3（v1 主锚单块口径）0.8272486772486772 → v4（v2 承载口径）**+0.0971 方向上升**，承载集消除邻块引用误判的方向实证成立；recall/precision/MRR/ansC 跨口径直接对账零漂移。
- **产物（4.1）**：`docs/rag-quality/baseline-v4.json`——suiteVersion=3.0 + scoringVersion=v2 + 63 例 V1 全量；落盘 CRLF 与在位 v3 工作树形态一致，入库经 `.gitattributes *.json eol=lf` 归一为 LF。
- **清开关自证**：`RAG_BENCHMARK_REAL` 以命令内联前缀作用单轮，跑毕新 shell 实测 unset；真实 key 仅真跑命令经宿主环境继承使用（长度 35 自证在位、值未打印），其余全部 mvn/门禁命令前置 `DASHSCOPE_API_KEY=""`；真跑之外零真实外呼。
- **基线红线**：surefire 1100→1110 只增不减；`scripts/test-baseline.txt` 零改动（`--update` 未执行），只读裁决真跑前后双 PASS（surefire 187/1110/0 ≥ 186/1100/0；failsafe 真跑后 27/98/**5** ≤ skipped 上限 6，任务书预警的「5≠上限判红」按脚本只读语义未成立，按在案修复路径无需刷新）。

**提交笔**：c8911d2 docs 三件套 → f211bbc feat 任务组 1 → 798c8a0 test 任务组 2 → c478bbf chore 基线写回 → 912e62a docs 任务组 3。

**提交笔（补记任务组 4）**：fd30d6c feat（4.2 锚点校验增量支持 v4 + 10 测）→（本笔）docs+data（4.1 真跑产物 baseline-v4.json + 本 §0 留痕与 4.1/4.2 勾选）。4.3 的 push 请示未执行（铁律禁 push），以停步回报交 owner 处置。

      归档批次订正（2026-10-11 archive-batch-2 复核 MINOR 7 条收尾）：work/ 域历史日志按原样保留——_green-run1.log 实为 span 校准前红跑、_green-run2.log spotless 925 系临时探针在场时点、改 IT 文件的第二轮 spotless:apply 归一只动本卡新文件。
