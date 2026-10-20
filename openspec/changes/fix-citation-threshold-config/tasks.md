# 任务清单 · fix-citation-threshold-config

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. 阈值动态化
- [x] 1.1 CitationThresholds record（keepMin/remapMin/tieMargin + 范围校验）；align 三参重载，两参形态委托默认常量
      补勾依据（2026-10-20 实测：新增 `server/ai/CitationThresholds.java`（record + [0,1] 有限数构造校验 + `defaults()`）；`CitationAligner.align(answer, sources, thresholds)` 三参重载，两参形态 `return align(..., CitationThresholds.defaults())`。定向 `mvn -B -ntp "-Dtest=CitationThresholdsTest,..." test` → Tests run: 82, Failures: 0, Errors: 0，BUILD SUCCESS；其中 `CitationThresholdsTest` 3/3 绿（三态边界 + 构造校验 + 默认等价）。）
- [x] 1.2 生产调用点实读确认（AiChatStreamFinalizer/其他）并从动态配置解析注入；核心类零 Spring 保持
      补勾依据（2026-10-20 实测：全仓 grep CitationAligner 的唯一生产 align( 调用点 = `AiChatStreamFinalizer#finishStreamNormally`（`RuleSelfRagReflector` 只用 clauseAround/KEEP_MIN 常量、`LlmSelfRagReflector` 只用 clauseAround，均非 align 调用点）。注入链：`AiChatStreamLifecycle` 新增 `@Autowired(required=false) ObjectProvider<DynamicConfigService>` → 构造参数 `Supplier<CitationThresholds>`（`CitationThresholdsResolver.resolve`）→ 收尾时 `resolveCitationThresholds()`（null 回落 defaults）。核心三单元零 Spring：CitationAligner/CitationSupport/CitationThresholds/Resolver 对 org.springframework 零命中；Resolver 只依赖平台冻结契约 `platform.contract.DynamicConfigService`。定向 82/82 绿含 `AiChatServiceImplTest` 30/30 与三条 Lifecycle 测试。）
- [x] 1.3 DynamicConfigKeyRegistry 增 rag.retrieval.citation.{keepMin,remapMin,tieMargin} 三键（校验+tier 定级+默认值=现常量）
      补勾依据（2026-10-20 实测：registry 三键 DOUBLE、默认 "0.12"/"0.22"/"0.08"、范围 "0.0"~"1.0"；`ConfigKeyTierPolicy` OPERATIONAL 32→35（与 `rag.retrieval.minScore` 同档：检索质量调参，无模型边际成本、不触库结构/入库语义），census 63 键 = 35/23/5。`CitationThresholdKeysRegistryTest` 3/3 绿、`ConfigKeyTierPolicyTest` 2/2 绿、`DynamicConfigKeyRegistryTest` 11/11 绿、`CitationThresholdsResolverTest` 3/3 绿（含「键已登记且默认值=常量」联测）。docs/dynamic-config-keys.md 同步三行 + 计数 63/35。）

## 2. 评测同源回灌
- [x] 2.1 RagRealRetrievalBenchmarkIT 适配层 citations 上报前经 CitationAligner.align（生产同款）
      补勾依据（2026-10-20 实测：`RagRealRetrievalBenchmarkIT#evaluateCase` 在 `CaseOutcome.citations` 上报前已经 `CitationAligner.align`（该接入由 56f1297「生产落库与评测抽取接入引用对齐」引入，非本卡新增）；本卡改为显式 `align(answer.text(), result.sources(), CitationThresholds.defaults())` 并注释钉住生产默认阈值以保证跨次可比。零真跑：未设 RAG_BENCHMARK_REAL，`mvn clean verify` 中 `RagRealRetrievalBenchmarkIT` Tests run: 1, Skipped: 1。）
- [x] 2.2 回灌证据单测：fake 构造黄金引用被 DROP 场景，断言 citP 下降
      补勾依据（2026-10-20 实测：`CitationPrecisionBackfillEvidenceTest` 2/2 绿。缺口态：retrieved=[gold-1]、citations=[1]（对齐前抽取）→ citP=1.0；回灌后：align 对该子句支撑分 0 → DROP → citations=[] → citP=0.0，meanCitationPrecision=0.0；对照组有支撑引用 KEEP [1] → citP 仍 1.0；同时断言 recall/ansC 不受影响。）

## 3. 测试与基线
- [x] 3.1 thresholds 注入单测（三态决策边界）+ 默认零漂移回归 + registry 键校验测试
      补勾依据（2026-10-20 实测：`CitationThresholdsTest` 用同一 clause/sources（支撑分 0.50 vs 0.75）证：默认阈值 REMAP[2]、tieMargin=0.5 KEEP[1]、keepMin=0.6+remapMin=0.8 DROP；默认零漂移由「两参形态 == 三参默认形态（text+citations 双口径）」断言 + 既有 `CitationAlignerTest` 6/6 零改动全绿 + `PerfBaselineSmokeTest` 绿。registry 校验：`CitationThresholdKeysRegistryTest` 3 键默认值=常量、上下界放行、越界/非数/空值拒绝。）
- [x] 3.2 定向全绿 + 全量 mvn -B -ntp clean verify（Docker 实测；不设 RAG_BENCHMARK_REAL）
      补勾依据（2026-10-20 实测：`docker info` → 29.6.2 在线；`mvn -B -ntp "-Dtest=..." test` → 82/0/0 BUILD SUCCESS（日志 work/targeted-citation-threshold.log）；`mvn -B -ntp clean verify` → BUILD SUCCESS 12:25 min，surefire 296 报告/1838 用例/0 skip、failsafe 32 报告/119 用例/7 skip，PMD/spotless/checkstyle/spotbugs 全绿（日志 work/verify-fix-citation-threshold.log）。未设 RAG_BENCHMARK_REAL。）
- [x] 3.3 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate 10/10
      补勾依据（2026-10-20 实测：`D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update`（树净，source-revision=650b501）→ surefire 296/1838/0、failsafe 32/119/7；复跑校验模式 EXIT=0「回归基线门禁通过」（日志 work/baseline-update.log、work/baseline-check.log）。`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → PASS [unit]/[checkstyle]/[spotbugs]/[spotless]/[pmd]/[baseline]/[frontend-unit]/[hook]/[bijection]/[pmd-baseline] 共 10/10，末行「merge-gate 通过」（日志 work/merge-gate.log）；typed-router 生成物已 `git checkout -- frontend/typed-router.d.ts` 还原。）
- [x] 3.4 handoff（注入路径图/三键对照/回灌断言/零漂移论证）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-20 实测：work/handoff-fix-citation-threshold-config.md 落盘（含路径图/三键对照表/调用点清单/回灌断言/零漂移论证/基线四元组/门禁结果/提交哈希/偏差清单）；docs/dynamic-config-keys.md 已补三行并同步计数；本 tasks.md 已勾选。停步：未 merge、未 push，分支 feature/fix-citation-threshold-config 保留。）
