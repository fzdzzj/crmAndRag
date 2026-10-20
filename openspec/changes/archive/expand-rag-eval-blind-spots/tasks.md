# 任务清单 · expand-rag-eval-blind-spots

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. C-3 多轮用例模型
- [x] 1.1 RagBenchmarkCase 增 history 组件（HistoryTurn record，兼容重载，旧 63 例零改动或全量补空参）
      补勾依据（2026-10-20 `mvn -B -ntp "-Dtest=…RagEvalBlindSpotsTest,…RagQualityEvaluatorTest…" test` 52 绿）：`RagBenchmarkCase` 增
      `List<HistoryTurn>` 组件 + 嵌套 `HistoryTurn(userText, assistantText)`；6 参兼容重载委托为 `history=List.of()`，既有 63 例构造零改动
      （`RagEvalBlindSpotsTest#multiTurnHistoryReachesRetrievalFunction` 断言 T-01/E-02 history 为空表）。
- [x] 1.2 多轮用例组 4-6 例（指代消解/上下文追问/澄清触发，占位 id 走 fixtures 黄金标记约定）；SUITE_VERSION 升 6.0 + 版本注释留痕
      补勾依据（2026-10-20 同上 52 绿 + `RagBenchmarkDataPreparerTest` 12 绿）：新增 MT-01~MT-05 共 5 例（指代消解 MT-01/MT-03、上下文
      追问 MT-02/MT-05、澄清触发 MT-04），黄金锚全部复用既有 44 个占位 id（零语料改动，`standardSuitePlaceholdersAllAlignToRealFixtureChunks`
      仍一一对应）；SUITE_VERSION 5.0→6.0（63→72），版本注释补 v6.0 条目并注明真跑落锚 baseline-v7.json 属另卡授权。
- [x] 1.3 AiEvalCase 增 history + 多轮工具选择用例组；评测 runner 消费 history
      补勾依据（2026-10-20 `AiEvalCaseSetTest/AiEvalMetricsTest/AiEvalMultiTurnTest/AiEvalRunnerTest` 7 绿）：`AiEvalCase` 增
      `List<AiEvalTurn>` + 5 参兼容重载；`AiEvalCaseLoader` 解析可选 `history` 数组（缺字段=单轮，轮次空字段即加载失败）；
      `cases.json` 增 3 条多轮用例（multi-turn-stage-approval-reference / multi-turn-order-followup /
      multi-turn-clarification-customer-detail）；`AiEvalRunner` 摘要行单列 multiTurnCases 留痕，history 随用例整体传入 predictor。

## 2. C-4 TEMPORAL 类别
- [x] 2.1 Category 增 TEMPORAL + 3-4 例（时间限定词改写检索，黄金锚静态语料时效块，纯 fake 可验证）
      补勾依据（2026-10-20 `RagEvalBlindSpotsTest#temporalCasesJudgeBySameSemanticsAsNormalCases` + `RagQualityEvaluatorTest` 绿）：
      `Category.TEMPORAL` 入封闭集（javadoc 说明时间限定词改写检索）；新增 TP-01~TP-04 共 4 例，问句含「本季度/上季度/最近这个月/
      最近」，黄金锚为静态语料既有块（price-tiers / policy-001 / sla-credit-1 / customer-onboard-2），判卷按正常用例口径（recall/hit/ansC），
      零真库时效数据依赖、零外呼。

## 3. C-5 拒答断言
- [x] 3.1 CaseOutcome 增 refused 信号（适配层填报，既有构造调用兼容）
      补勾依据（2026-10-20 定向 52 绿）：`CaseOutcome` 增第 8 分量 `refused`，7 参兼容重载按 `refused=false`（未填报=未证明诚实拒答）；
      真跑 IT 适配层三处填报（KB OFF 闲聊=true、零命中=declined、有命中且 expected 空=isDecline(对齐后答案)）。
- [x] 3.2 evaluator D16 收紧：expected 空时 answerConsistency = refused ? 1 : 0；recall/precision/MRR/citP 公式零改动
      补勾依据（2026-10-20 `RagEvalBlindSpotsTest` 6 绿 + `RagQualityEvaluatorTest` 7 绿）：新增私有 `answerConsistency(expected, outcome)`
      只切 ansC 取值源；recall/precision/MRR 分支与 `citationPrecision` 方法体零改动（`refusedSignalDoesNotTouchNormalCaseFormula` 断言正常用例
      公式不变）；E-02/E-04 由该信号覆盖「借近邻编造」面（`emptyExpectedCaseScoresZeroWhenAnswerFabricatesFromNeighbouringChunks`）。
- [x] 3.3 fake 单测：拒答三态（诚实拒答满分 / 借近邻编造 0 分 / 正常召回不受影响）+ 多轮透传 + TEMPORAL 判卷
      补勾依据（2026-10-20 `RagEvalBlindSpotsTest` Tests run 6, Failures 0）：三态各一例 + 兼容构造默认非拒答一例 + 多轮 history 透传一例 +
      TEMPORAL 判卷一例。

## 4. 基线与物料
- [x] 4.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-20 `mvn -B -ntp clean verify` EXIT=0，docker 29.6.2 在线、**未设** RAG_BENCHMARK_REAL）：surefire 292 报告 /
      1827 tests / 0 失败 0 错误，failsafe 32 报告 / 119 tests / 7 skipped；定向 11 类 52 tests 绿（`work/targeted-test-2.log`、
      `work/full-verify.log`）。
- [x] 4.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-20 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` EXIT=0，树净、source-revision=41e1c3d）：
      写入 surefire 292/1827/skipped 0、failsafe 32/119/skipped 7（较 290/1818 只增不减）；复跑校验模式 EXIT=0（「回归基线门禁通过」）；
      基线笔 `6782edd`。
- [x] 4.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-20 `D:\git\Git\bin\bash.exe scripts/merge-gate.sh` EXIT=0）：PASS=10 FAIL=0（unit/checkstyle/spotbugs/spotless/
      pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）；`frontend/typed-router.d.ts` 行尾噪声已 `git checkout --` 还原，frontend 零 diff
      （`work/merge-gate.log`）。
- [x] 4.4 handoff（用例组清单/SUITE_VERSION 6.0 说明/C-5 判分前后对照）+ tasks 勾选 + proposal/tasks 随 docs 笔提交；停步未 merge 未 push
      补勾依据（2026-10-20）：`work/handoff-expand-rag-eval-blind-spots.md` 已落（9 条用例组清单含类别/history 形态/黄金锚、6.0 版本注释原文、
      C-5 前后对照表含受影响既有单测逐处说明、fixtures 变更清单=无、基线四元组、门禁 10/10、偏差 7 条）；proposal/tasks 随本 docs 笔提交；
      分支 `feature/expand-rag-eval-blind-spots` 保留，未 merge、未 push。
