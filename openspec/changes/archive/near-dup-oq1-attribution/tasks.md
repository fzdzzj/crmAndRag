# tasks · near-dup-oq1-attribution（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 可 grep 的符号/字段名 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 与决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。
> 行号会漂移，补勾依据以**类名 / 方法名 / 锚字段名 / grep 得到的那行代码**为锚点。

## §0 · 硬约束

- **零外呼**：不设任何 `*_REAL`、不启动 Spring 上下文、不 instance provider、不跑检索。
- 只读输入：`docs/rag-quality/near-dup-band-entry-v1.json` + `near-dup-window-coupling-v1.json`（**均不可改**）。
- `src/main` 零 diff；`pom.xml` / `.github/**` 零改；既有锚全量零字节变动；禁改测试类只读。
- 锚写模式 `-Dnear.dup.oq1.attribution.write=true`；默认复算、零外呼。

## A · 机制锚定（R1）

- [x] 1. 三处代码事实逐处落锚 `mechanism.codeFacts`：文件 + 行 + 一字不差的代码片段（min-max 三分支 / `rerankScore × routeWeight` 与 `0.30` / `rerankScore × weight`）
      补勾依据：锚 `mechanism.codeFacts` 3 条（`DefaultWeightedReranker#normalizeScores` / `RetrievalConfigResolver#mergeRoute` / `ImageSimilarityGate#downWeightNearDuplicates`），每条带 `file`、`line`、`snippet`；`python -c` 读锚打印 3 条 snippet 与源码逐字比对一致；复核侧逐行核对「一字不差」通过（review §1-⑤）。
- [x] 2. 守卫用 grep 断言这三段代码**仍存在**（片段匹配失败即红）；断言 `DEFAULT_IMAGE_ROUTE_WEIGHT` 字面 = `0.30`
      补勾依据：守卫 `mechanismCodeFactsStillPresent` 用 `Files.readString` + `contains(snippet)` 逐处断言，并断言 `DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30` 字面在位；实测 8/8 绿（`Tests run: 8, Failures: 0`）。
- [x] 3. 落 `mechanism.identity`：`δ = 0.30 × (1 − w) × r` 与 `δ = 0 ⇔ r = 0`，并注明成立前提（`w < 1`、权重 > 0、降权发生在融合前）
      补勾依据：锚 `mechanism.identity` 含恒等式与三条前提；`dataCheck.rByEvent` 的分母即 `0.30 × (1 − w)`，`w` 取卡 32 锚 `windowCheck.entries[i].weight`（0.75/0.60/0.50）。

## B · 位移-贡献反算（R2）

- [x] 4. 从卡 32 锚 12 条事件反算 `r = delta / (0.30 × (1 − w))`，逐条落 `dataCheck.rByEvent`（含 `queryIndex / variantKey / w / delta / r`）
      补勾依据：锚 `dataCheck.rByEvent` 12 条，字段 `queryIndex/variantKey/w/delta/r` 齐全；分析类现算后与锚规范化字节比对一致（`[near-dup-oq1] 默认复跑规范化字节一致`）。
- [x] 5. 断言每条 `r ∈ [0, 1]`，且 `delta == 0 ⇔ r == 0`（逐条，禁抽样）
      补勾依据：守卫 `identityHoldsPerEvent` 逐条 `assertTrue(0 ≤ r ≤ 1)` 且 `assertEquals(delta == 0, r == 0)`；实测 **12/12 成立**（`δ=0` 恰 4 条、`r=0` 恰同 4 条）；变异 A（锚内某条 `r` 0→0.4）使该断言红（`expected: <true> but was: <false>`），还原后回绿。
- [x] 6. 落 `dataCheck.rDistribution`：4 条零位移的 `r` 全为 0、8 条非零的 `r` 值逐个列出
      补勾依据：锚 `dataCheck.rDistribution` 记 `zeroCount=4`、`nonZeroCount=8` 与逐条值；4 条身份为 q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`，与卡 32 锚 `candidates` 行逐字段一致。

## C · 结构性推论（R3）

- [x] 7. 落 `dataCheck.noOpProof`：4 条零位移事件各自给出 `0.30 × (1 − w) × 0 = 0` 的逐条算式，并断言其 `tier == NEAR_DUPLICATE` 且 `downWeighted == true` **同时成立**
      补勾依据：锚 `dataCheck.noOpProof` 4 条，每条带 `w`、算式字符串与 `tier`/`downWeighted`；守卫逐条断言 `tier=NEAR_DUPLICATE && downWeighted=true && r=0` 三者并存（`noOp` 断言通过）。
- [x] 8. 落 `dataCheck.noOpStatement`：明确写「该候选在融合里的图像贡献为 0 ⇒ 降权是结构空操作」，并注明**该结论不依赖缺失字段**
      补勾依据：锚 `dataCheck.noOpStatement` 原文含「结构空操作」与「不依赖缺失字段（`r` / `routeListSize` / `bm25` 归一值均不需要）」；该句同时出现在 md「结论 1」。

## D · 可判定性分级（R4）

- [x] 9. 落 `attribution.claimClass` 三档逐条：`PROVEN`（R1+R2+R3）/ `CONSISTENT-NOT-PROVEN`（min-max 末位）/ `MISSING-EVIDENCE`（`r`、`routeListSize`、`bm25` 归一值）
      补勾依据：锚 `attribution.claimClass` 三档为数组：`PROVEN` **3** 条（P1–P3）、`CONSISTENT-NOT-PROVEN` **2** 条、`MISSING-EVIDENCE` **4** 条（M1 `imageRerankScore` / M2 `routeListSize` / M3 `bm25` 归一值 `imageRerankBm25Normalized` / M4 `rag.retrieval.rerank.vector-weight`·`bm25-weight` 的运行时取值——即 `r = 0.60·normVec + 0.40·normBm25` 里那两个权重本身也是推断的）；守卫断言三档非空且 `MISSING-EVIDENCE` 四项齐全。
- [x] 10. 实现一致性检查（不升级为证明）：逐查询比较「反算 `r` 最大者」与「`cos` 最大者」是否同一候选，结果落 `attribution.consistencyCheck`
      补勾依据：锚 `attribution.consistencyCheck` 6 条（逐查询 `markedCount`/`rMaxVariantKey`/`cosMaxVariantKey`/`sameCandidate`/`status`）+ `consistencySummary`（`queries=6`、`consistent`、`counterExamples`、`upgraded=false`、`degenerate + nonDegenerate = 6`）；辅助观察 `routeLevelCosObservation`（`zeroRIsMinCosAmongRowsWithCos`、`checkedZeroREvents=4`）。
- [x] 11. 落 `verdict`，取值只允许 `MECHANISM-CONFIRMED` / `INCONCLUSIVE` / `NEEDS-INSTRUMENTED-RUN`，并附 `verdictReason`（哪几条支撑、哪几条不足以支撑）
      补勾依据：锚 `verdict = NEEDS-INSTRUMENTED-RUN`、`verdictReason` 明写「不取 MECHANISM-CONFIRMED 的理由：该值只能覆盖『降权对 r=0 的候选是 no-op』这层恒等式，覆盖不了 OQ1 的因果问句」；守卫断言取值在三值内且 `verdictReason` 非空。
      **复核 MINOR-2 修复**：守卫新增反向断言——`claimClass.PROVEN` 的文本不得命中「末位 / min-max 把 / 归 0 造成 / 成因是」；实测把「该候选是图像路的末位」注入 `PROVEN` 后守卫红（`命中禁式「末位」`），还原后 8/8 回绿。

## E · 埋点方案（R6，仅当 `NEEDS-INSTRUMENTED-RUN`）

- [x] 12. 落 `instrumentedPlan`：最小字段集（`imageRerankScore` / `imageRankInRouteList` / `routeListSize` / `fusionImageContribution`）+ 复用卡 32 语料的预算估算（30 外呼）+ 「为什么不能再省」的逐项理由
      补勾依据：锚 `instrumentedPlan.minimalFields` 恰 4 项（每项带 `field`/`where`/`why`）、`callBudget.total = 30`（6 摄取 × 3 + 6 查询 × 2）、`whyNotFewer` 3 条（少一项各分不清什么）。
- [x] 13. 断言 `instrumentedPlan.executed == false` 且 md 明写「本方案未执行、不得据其声明结论」
      补勾依据：守卫 `verdictAndInstrumentedPlan` 断言 `executed=false` 且 `status="PROPOSED-NOT-EXECUTED"`；md 原文含「**本方案未执行、不得据其声明结论**（D5）」；变异 B1（`executed`→`true`）使该断言红，还原后回绿。

## F · 锚与守卫（R6/R7）

- [x] 14. 锚 `docs/rag-quality/near-dup-oq1-attribution-v1.json`（纯 LF、单尾换行）+ `.md`（≤40 行）；默认复跑逐字节一致（剔除顶层 `runtime`）
      补勾依据：md 实测 **30 行 / 3928 字节 / `CR=0`**；锚 `CR=0`；默认复跑打印 `[near-dup-oq1] 默认复跑规范化字节一致（JSON 树规范化，剔除顶层 runtime）` 与 `默认复跑逐字节一致（md）` 两行，8/8 绿。
      **复核 MINOR-4 修复**：JSON 比对实为「树规范化后按规范化字节比较」，原措辞「逐字节」不准确 ⇒ 已把注释、断言消息、控制台行、md 全部改为「**规范化字节**（JSON 走树规范化、md 走真字节）」。
- [x] 15. 守卫 `NearDupOq1AttributionGuardTest` 钉死：R1 三处 grep、R2 逐条恒等、R3 逐条算式、R4 三档齐全、R5 判定取值合法、上游锚 SHA 实算、md ≤40 行
      补勾依据：守卫 7 个 `@Test` 全绿（`Tests run: 7, Failures: 0`），含 `mechanismCodeFactsStillPresent` / `identityHoldsPerEvent` / `noOp` / `claimClass` 三档 / `consistencyCheck` / `verdictAndInstrumentedPlan` / `upstreamAnchorsAndMdBudget`（后者实算上游 SHA 并断 md ≤40 行）。
      **复核 MINOR-3 修复**：md「结论 2」补写退化分支——`max ≤ 0` 时**全体**归一化为 `0.0`（任何候选都 `r=0`，与「末位」无关）、`max == min` 时全体 `1.0`，并指向锚 `consistencySummary.degenerate`，明确原句「必须是末位」只在非退化查询成立。
- [x] 16. 边界实测：`git diff master --stat -- src/main pom.xml .github` 空；既有锚零 `M`（只新增本卡 2 文件）；禁改测试类零字节变动；`git diff --check` 干净
      补勾依据：`git diff master --name-status` 恰 4 个 `A`；`git diff master --stat -- src/main pom.xml .github` **空**；`git status --porcelain -- docs/rag-quality` 只列本卡 2 个新文件；11 个禁改 `NearDup*` 类 `git diff master --name-only` **空**；`git diff --check` **空**。已跟踪改动只含本卡写集（未跟踪的卡面 2 条除外）。
- [-] 17. 若一致性检查出现反例 ⇒ 按 R5 判 `INCONCLUSIVE` 并在 `proposal §8` 记反例（查询 + 数值）；**不许**回头改 R4 迁就数据
      补勾依据：**未触发**——6 个查询一致性检查全 `CONSISTENT`（`counterExamples=0`），故 `proposal §8` 无反例记录；判定按 R5 取 `NEEDS-INSTRUMENTED-RUN`（缺字段而非反例），本条记 `[-]`。
