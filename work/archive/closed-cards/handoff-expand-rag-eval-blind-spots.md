# handoff · expand-rag-eval-blind-spots（评测盲区：多轮/时序/拒答，B 档）

- 分支：`feature/expand-rag-eval-blind-spots`（权威树自 master@3480c0c 检出）
- 停步点：未 merge、未 push，分支保留
- 改动面：`src/main/java/com/slz/crm/quality/`（4 文件）+ `src/test/java/com/slz/crm/quality/`（8 文件）+ `src/test/java/com/slz/crm/server/ai/eval/`（4 文件）
  + `src/test/resources/ai-eval/cases.json` + `docs/rag-quality/baseline-v1.json`（fixtureRegression 段 regen）+ `scripts/test-baseline.txt`（--update）
- 零真外呼（未设 `RAG_BENCHMARK_REAL`，未跑任何真跑 IT）/ 零迁移 / 零依赖（pom.xml 零 diff）/ 零新错误码 / 前端零 diff / 既有 63 例内容零改动

## 1. 新增用例组清单

### 1.1 RAG 基准（`RagBenchmarkSuite`，63 → 72）

| 编号 | 类别 | history 形态 | 黄金锚（既有占位 id） | 考察面 |
|---|---|---|---|---|
| MT-01 | TEXT | 1 轮（user「合同生效后金额能改吗」/ assistant「需重新走合同变更审批，审批链与新建合同一致」） | `sales-flow-4` | 指代消解：问句「那它的审批流程具体怎么走」 |
| MT-02 | TEXT | 1 轮（user「回款逾期了会怎么处理」/ assistant「分三档升级，第一档由销售经理跟进催收」） | `payment-plan-2` | 上下文追问：「那第二档呢，会有什么影响」 |
| MT-03 | LEXICAL | 1 轮（user「XR-500 的保修到什么时候」/ assistant「保修截止 2027-05-31」） | `model-xr500` | 指代消解（专名/编号）：「那它的备件放在哪个库位」 |
| MT-04 | TEXT | 1 轮（user「帮我看看合同的事」/ assistant「哪一类？审批流程、归档要求还是变更审批？」） | `sales-flow-3` | 澄清触发：「归档那边有什么要求」 |
| MT-05 | TEXT | 1 轮（user「客户建档要走哪些步骤」/ assistant「分四步，先在系统录入客户基础信息」） | `customer-onboard-1` | 上下文追问：「那第二步是谁来核验」 |
| TP-01 | TEMPORAL | 无（单轮） | `price-tiers` | 「本季度价格梯度表复核之后，1000 台上档位的折扣率还适用吗」 |
| TP-02 | TEMPORAL | 无 | `policy-001` | 「上季度的华东区冲刺奖励什么时候发」 |
| TP-03 | TEMPORAL | 无 | `sla-credit-1` | 「最近这个月 P1 级故障响应超时了一次，要赔多少」 |
| TP-04 | TEMPORAL | 无 | `customer-onboard-2` | 「最近一次客户分级复评的输入是哪几个季度」 |

- 类别配比：TEXT 17→21 / LEXICAL 14→15 / TEMPORAL 0→4，TABLE 13 / IMAGE 6 / EDGE 4 / MISMATCH 9 不变。
- 黄金锚**全部复用既有 44 个占位 id**（`standardSuitePlaceholdersAllAlignToRealFixtureChunks` 仍一一对应），
  因此 `chunkCount=57` / `fixtureCount=15` 不变——语料与既有 63 例零改动。
- 多轮维度与内容类别正交：卡只授权 Category 增 TEMPORAL，故多轮组沿用 TEXT/LEXICAL，由 `history` 组件 + `MT-` 编号表达（见偏差 2）。

### 1.2 AI 工具选择（`ai-eval/cases.json`，40 → 43）

| 编号 | history 形态 | 省略式话术 | 期望工具 / 参数 |
|---|---|---|---|
| multi-turn-stage-approval-reference | 1 轮（user 查「智能制造平台一期合同」） | 「它的审批到哪一步了？」 | `queryStageApproval` / 无约束 |
| multi-turn-order-followup | 1 轮（user 查「订单 1001 的详情」） | 「那第二笔呢？」 | `getOrderDetail` / 无约束 |
| multi-turn-clarification-customer-detail | 1 轮（user「看下客户数据」/ assistant 反问三类） | 「客户公司档案吧，55 号那个」 | `getCustomerCompanyDetail` / `companyId=55` |

### 1.3 组件形态

- `RagBenchmarkCase` 增 `List<HistoryTurn> history`（第 4 分量）；`HistoryTurn(userText, assistantText)` 为嵌套 record；
  6 参兼容重载委托 `history=List.of()` → 既有 63 例构造零改动。
- `AiEvalCase` 增 `List<AiEvalTurn> history`（第 6 分量）；`AiEvalTurn(user, assistant)` 嵌套 record；5 参兼容重载 → 既有 40 例零改动；
  `AiEvalCaseLoader` 解析可选 `history` 数组（缺字段=单轮；轮次缺 user/assistant 即加载失败）。
- `AiEvalRunner`：用例整体（含 history）传入 predictor，摘要行追加 `multiTurnCases=N`（见偏差 4）。

## 2. SUITE_VERSION 6.0 版本注释（原文）

`RagBenchmarkSuite` 常量 javadoc 追加条目：

> v6.0 = expand-rag-eval-blind-spots：新增多轮组 5 条（MT-01~05，history 组件承载上下文）+ TEMPORAL 时间限定组 4 条（TP-01~04，
> 63 → 72：TEXT 21 / TABLE 13 / IMAGE 6 / LEXICAL 15 / EDGE 4 / MISMATCH 9 / TEMPORAL 4），并升 `CaseOutcome.refused` 的
> D16 判分收紧——v6.0 真跑落锚 `baseline-v7.json` 属另卡授权（沿 v3.0「暂未授权真跑落锚」先例，本次不产出新锚点）。

跨版本不可比语义保留：报告 `suiteVersion` 仍取自该常量，比较前先核对版本。

## 3. C-5 判分口径前后对照

`RagQualityReport.CaseOutcome` 增 `boolean refused`（第 8 分量），7 参兼容重载按 `refused=false`。

| 场景 | 旧口径 ansC | 新口径 ansC | 影响面 |
|---|---|---|---|
| expected 非空（正常用例） | `answerPointCoverage` | `answerPointCoverage` | **零改动**；refused 不参与 |
| expected 空 + refused=true（诚实拒答） | `answerPointCoverage` | `1` | E-01/E-03 闲聊、E-02/E-04 零命中诚实拒答 |
| expected 空 + refused=false（借近邻编造） | `answerPointCoverage`（编造答案可被判卷给高分 → 漏判） | `0` | C-5 幻觉面被覆盖 |
| expected 空 + 旧 7 参构造（适配层未填报） | `answerPointCoverage` | `0` | 拒绝"未填报=默认放行" |
| 检索异常（失败用例兜底） | 0（覆盖度 0） | 0（refused 默认 false） | 不变 |

`recall / precision / MRR / citationPrecision` 公式与方法体**零改动**（仅 ansC 的取值源经新增私有方法 `answerConsistency(expected, outcome)` 切换）。

### 3.1 受口径影响的既有单测逐处说明（不得弱化，逐处列明）

| 文件 | 处 | 改动 | 是否弱化 |
|---|---|---|---|
| `RagQualityEvaluatorTest` | `CANNED` 的 C2 | 7 参 → 8 参、`refused=true`（C2 语义本就是"零召回 + 诚实拒答"） | 否，并**新增** `c2.answerConsistency()==1.0` 断言 |
| `RagQualityEvaluatorTest` | `lexicalCasesScoreBySameSemanticsAsOtherCategories` 的 LX-2 分支 | 7 参 → 8 参、`refused=true` | 否，并**新增** `lexicalEdge.answerConsistency()==1.0` |
| `RagQualityEvaluatorTest` | `standardSuiteCoversAllFiveCategoriesWithRequiredCounts` | 追加 `Category.TEMPORAL` 必须被覆盖 + `TEMPORAL>=3` | 否，**加强** |
| `RagBenchmarkDataPreparerTest` | `suiteSizeAndVersionAfterRecalibration` | 63→72、`"5.0"`→`"6.0"`、TEXT 17→21、LEXICAL 14→15、新增 TEMPORAL=4；七类 21/13/6/15/4/9/4 | 否（套件规模随版本升，断言逐类精确值不变松） |
| `RagBenchmarkCarrierScoringTest` | `cannedOutcomes` 边界分支 | 7 参 → 8 参、`refused=true`（该 fake 代表诚实空召回） | 否 |
| `RagBenchmarkCarrierScoringTest` | `carrierScoringRaisesCitpWithoutTouchingRecallOrAnswerConsistency` | 硬编码 `63` → `RagBenchmarkSuite.standard().size()` | 否，**去魔数**（仍断言"每条用例都有 citP 期望集登记"） |
| `RagBenchmarkV6AnchorValidationTest` | `report(...)` | `RagBenchmarkSuite.SUITE_VERSION` → 新常量 `V6_ANCHOR_SUITE_VERSION="5.0"`（照 V5 锚点测硬编码 `"4.0"` 的先例） | 否；锚点版本是历史常量，不随 live 常量前移 |
| `RagBenchmarkV6AnchorValidationTest` | `suiteVersionIsFiveZeroAfterClearOnb2` → `suiteVersionIsSixZeroAfterEvalBlindSpots` | 断言 live=6.0 **且** 锚点常量=5.0 | 否，**双断言加强** |
| `RagQualityRegressionTest` | `toOutcome` | javadoc 补 refused 说明（不填报：该门禁不评生成层，ansC 不在断言面） | 否 |
| `RagQualityRegressionTest` | `docs/rag-quality/baseline-v1.json` `fixtureRegression` 段 | 走唯一合法写入口 `-Drag.baseline.regen=true` regen | 见下 |

基线 regen 实测（前 → 后）：`suiteVersion` 5.0 → 6.0；`caseCount` 63 → 72；`recallAt5` 0.8386243386243385 → 0.8310185185185185；
`mrr` 0.7116402116402117 → 0.7094907407407409；`chunkCount` 57、`fixtureCount` 15、`k=5`、`harnessVersion=1` 不变。
（数值小幅下降来自 9 例新增中省略式问句在字符 n-gram 伪向量下的天然低分；语料零改动由 chunkCount/fixtureCount 不变实证。）

### 3.2 适配层填报（真跑 IT 代码适配，本卡未运行）

`RagRealRetrievalBenchmarkIT#evaluateCase` 三处 `CaseOutcome` 补齐 refused：KB OFF 闲聊 → `true`（无知识性断言，与原 coverage=1.0 同义）；
KB ON 零命中 → `declined`；KB ON 有命中且 expected 空 → `isDecline(对齐后答案)`。`CustomerRetrievalBenchmarkIT`/`RagQualityRegressionTest`
无空期望用例或不在断言面，保持默认 `false`。

## 4. fixtures 变更清单

**无。** 未新增/修改任何 `src/test/resources/rag-quality/fixtures/` 文件，未改 `RagBenchmarkDataPreparer.FIXTURES` 登记表；
新增 9 条用例全部复用既有 44 个 `【GOLD:占位id】`。实证：regen 后 `chunkCount=57`、`fixtureCount=15` 与基线一致，
`standardSuitePlaceholdersAllAlignToRealFixtureChunks` 绿。

## 5. 测试清单与结果

| 命令 | 覆盖 | 结果 |
|---|---|---|
| `mvn -B -ntp "-Dtest=…" test`（11 个类：RagEvalBlindSpotsTest / RagQualityEvaluatorTest / RagBenchmarkDataPreparerTest / RagBenchmarkCarrierScoringTest / RagBenchmarkV6AnchorValidationTest / RagBenchmarkCarrierTest / AiEvalCaseSetTest / AiEvalMetricsTest / AiEvalMultiTurnTest / AiEvalRunnerTest） | 9 项新增用例组 + 判卷口径 + 既有受影响单测 | Tests run 52, Failures 0, Errors 0（`work/targeted-test-2.log`） |
| `mvn -B -ntp "-Dtest=RagQualityRegressionTest" -Drag.baseline.regen=true test` | fixtureRegression regen（唯一写入口） | Tests run 4 绿（`work/baseline-regen.log`） |
| `mvn -B -ntp "-Dtest=RagQualityRegressionTest" test` | regen 后校验模式 | Tests run 4 绿，recall@5/mrr 复现 |
| `mvn -B -ntp clean verify`（**未设** RAG_BENCHMARK_REAL，docker 29.6.2 在线） | 全量 surefire + failsafe | **EXIT=0**；surefire 292 报告 / 1827 tests / 0 失败 0 错误；failsafe 32 报告 / 119 tests / 7 skipped（`work/full-verify.log`） |

## 6. 基线四元组（+ skipped）

| 项 | 改造前 | 改造后 |
|---|---|---|
| surefire.reports | 290 | **292**（+2：RagEvalBlindSpotsTest、AiEvalMultiTurnTest） |
| surefire.tests | 1818 | **1827**（+9：6 新增 + 3 多轮 AiEval） |
| failsafe.reports | 32 | 32 |
| failsafe.tests | 119 | 119 |
| failsafe.skipped（上限） | 7 | 7 |

`scripts/check-test-baseline.sh --update` EXIT=0（树净单次，source-revision=`41e1c3d`）；复跑校验模式 **EXIT=0**。

## 7. merge-gate

`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → **EXIT=0，PASS=10 / FAIL=0**：

`[unit]` `[checkstyle]` `[spotbugs]` `[spotless]` `[pmd]` `[baseline]` `[frontend-unit]` `[hook]` `[bijection]` `[pmd-baseline]`

- `[baseline]` 读数与台账一致：surefire 292/1827、failsafe 32/119/7。
- `[frontend-unit]` 21 文件 / 197 tests 通过；vitest 改写了 `frontend/typed-router.d.ts` 行尾（正文未变），已 `git checkout --` 还原 → **frontend 零 diff**。
- 日志：`work/merge-gate.log`。

## 8. 提交哈希

| 笔 | 哈希 | message |
|---|---|---|
| 1 | `41e1c3d` | `feat(rag-eval): close multi-turn temporal and refusal blind spots (suite 6.0)` |
| 2 | `6782edd` | `test(baseline): re-anchor regression counts to suite 6.0 run (surefire 292/1827)` |
| 3 | 本文件所在提交（docs 笔） | `docs(rag-eval): record blind-spot case groups grading table and handoff` |

## 9. tasks.md 勾选证据

1.1~3.3 六项已勾、4.1~4.4 四项已勾，证据行见 `openspec/changes/expand-rag-eval-blind-spots/tasks.md`（每项下一行 6 空格 + 「补勾依据（2026-10-20 …）」）。

## 10. 偏差清单

1. **work 任务卡未入库**：按卡指令「复制到权威树 work/ 根，不入库」，`work/task-card-expand-rag-eval-blind-spots.md` 保持未跟踪；
   注：仓库 `.gitignore` 对 `work/task-card-*.md` 有反向例外、历史 16 份任务卡均为跟踪状态——是否改为入库请 owner 定夺。
2. **多轮组未新增类别**：卡只授权 `Category` 增 `TEMPORAL`，故 MT-01~05 沿用 TEXT(4)/LEXICAL(1)，多轮维度由 `history` 组件 +
   `MT-` 编号表达（类别与 history 正交）。若需独立 `MULTI_TURN` 类目，属另卡（会再动类别封闭集与配比断言）。
3. **未新增时效语料块**：TEMPORAL 4 例锚定既有语料中已含时效语义的块（"每季度复核/次月/单月内/季度冲刺奖励"），以满足
   「既有 63 例与语料零改动」；代价是 `chunkCount`/`fixtureCount` 不变（这是有意为之的守边界选择，而非遗漏）。
4. **AiEval「runner 消费 history」的实现粒度**：用例整体（含 history）传入 predictor + 摘要行 `multiTurnCases` 留痕；未改
   `AiEvalMetrics` 公式与 `AiEvalReport` 结构（避免动既有指标口径与报告 JSON）。判别证据由 `AiEvalMultiTurnTest` 双向锁住：
   读 history 的 predictor 全中（toolSelectionAccuracy=1.0），无视 history 的 predictor 全错（=0）。
5. **fixtureRegression 基线数值下降**：recallAt5 −0.76pp、mrr −0.21pp，经唯一合法写入口 regen 写入（该门禁契约即「regen + code review」），
   非手改；语料未变，下降来自新增 9 例的省略式/时间限定问句在伪向量下的天然低分。
6. **v6.0 真跑锚点缺席**：未产出 `baseline-v7.json`（另卡授权）。因此**若**获得授权真跑，本轮 6.0 报告会与 v3/v4/v5/v6 四个历史锚点的
   `suiteVersion` 失配而红——这是「跨版本不可比」的预期行为（同 v3.0 先例），须由落锚卡处理；本卡未运行任何真跑 IT。
7. **PowerShell 假 exit 1**：`git`/`mvn` 写 stderr 时 `Tee-Object` 管道会报 exit 1，以日志内 `BUILD SUCCESS` / `GATED` / 显式 `EXIT_*` 行为判据
   （全部已按此判绿并留证）。
