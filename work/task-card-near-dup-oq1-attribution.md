# 任务卡 · near-dup-oq1-attribution（卡 34 · B 档 · 零外呼归因卡）

## §1 目标

卡 32 有 4 条「被标记降权却 `fusedA == fusedB`」的事件（q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`），卡 33 把它记为 OQ1 并明令「未经实测的解释不得引用」。

本卡回答：**这个解释在零外呼前提下能被确立到哪一步？**

- **能证的**：`δ = 0.30×(1−w)×r` ⇒ `δ = 0 ⇔ r = 0`；`r = 0` 的候选在融合里图像贡献为 0 ⇒ **降权对它是结构空操作**（与是否带标记无关）。这三步不依赖任何缺失字段。
- **证不了的**：`r` 为什么是 0（`DefaultWeightedReranker` 的 min-max 让末位候选得 0 分）——锚里没有 per-route 分数，只能做一致性检查，**不许升格成结论**。
- **缺什么**：`imageRerankScore` / `imageRankInRouteList` / `routeListSize` / `fusionImageContribution` 四项；给出最小埋点方案（**不执行**，预算另请授权）。

## §2 背景（三条必须记住的教训）

1. **卡 33 的 D3**：缺口就记缺口，候选解释写明「不得引用」。本卡若把「合理」写成「已证」，等于把那条纪律撕了。
2. **板规 28/29**：数字从锚里抄、判据文本与实现同容差；能由数据推出的布尔不许字面写 `true`。
3. **卡 32/33 的锚是成品**：各自守卫逐字节钉死。本卡只读。

## §3 写集（**只许写这 4 个文件**，无其他写入者）

| 路径 | 内容 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupOq1AttributionAnalysisTest.java` | 读两个锚 + 读源码字面 → 生成锚（写模式） |
| `src/test/java/com/slz/crm/quality/NearDupOq1AttributionGuardTest.java` | 守卫：三处 grep、恒等式、空操作、三档、判定取值、上游 SHA、md 行数 |
| `docs/rag-quality/near-dup-oq1-attribution-v1.json` | 写模式生成（纯 LF） |
| `docs/rag-quality/near-dup-oq1-attribution-v1.md` | ≤40 行 |

写模式属性：`-Dnear.dup.oq1.attribution.write=true`（无该属性 ⇒ 只比对）。

## §4 禁改面（越界即判死）

- `src/main/**`、`pom.xml`、`.github/**` 零 diff；不新增依赖。
- 既有锚零字节变动（`near-dup-band-entry-v1.json`、`near-dup-window-coupling-v1.json` 是**只读输入**）。
- 禁改：`NearDupBandEntryIT` / `NearDupBandEntryGuardTest` / `NearDupWindowCouplingAnalysisTest` / `NearDupWindowCouplingGuardTest` / `NearDupS1CropCorpus` / `NearDupTierFillAnalysisTest` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*`。
- **零外呼**：不设 `RAG_*_REAL`、不启动 Spring、不跑检索。

## §5 成本铁律

零外呼。不许为了「验证 min-max」去跑检索——那正是 `instrumentedPlan` 存在的理由。

## §6 验收（摘要）

1. `δ = 0.30(1−w)r` 逐条成立；`δ=0 ⇔ r=0`；4 条零位移 `r` 恰为 0 且 `tier=NEAR_DUPLICATE`、`downWeighted=true` 并存。
2. 空操作推论逐条带算式，且注明「不依赖缺失字段」。
3. 三档 `claimClass` 齐全；一致性检查结果落锚且**未被升格**。
4. `verdict` 三值之一 + `verdictReason`；若 `NEEDS-INSTRUMENTED-RUN` 则 `instrumentedPlan.executed == false`。
5. 锚 LF、默认复跑逐字节一致、上游 SHA 实算、md ≤40 行、`src/main` 及既有锚零 diff。

## §7 停手条件

- 一致性检查出现反例 ⇒ 判 `INCONCLUSIVE` 并记 `proposal §8`（查询 + 数值），**不许**改 R4 迁就数据。
- 想让 `CONSISTENT-NOT-PROVEN` 升格为 `PROVEN` ⇒ 判死：那需要埋点，不是本卡能做的事。
- 需要改卡 32/33 锚才能过 ⇒ 判死。

## §8 回报（≤300 字）

提交 SHA / 4 文件行数 / 12 条 `r` 值（可给区间与 4 个 0）/ `δ=0 ⇔ r=0` 是否逐条成立 / 一致性检查结论 / `verdict` + `verdictReason` / 三档条数 / `instrumentedPlan.executed` / 未解决项 / 待决策项。

## §9 细节留档位

执行者在此节追加：三处代码 grep 的实测行文、12 条 `r` 表、4 条零位移的算式、一致性检查逐查询结果、判定理由、任何偏离 proposal 的选择与理由。

### 卡 34 执行留档（2026-10-26，feature/near-dup-oq1-attribution）

- **输入**：卡 32 锚（12 事件 / 30 候选）+ 卡 33 锚（`openQuestions.OQ1`），均只读。
- **机制三处**（grep 一字不差，守卫钉住）：`DefaultWeightedReranker#normalizeScores`（min→0 / `max<=0`→全 0 / `max==min`→全 1）、`RetrievalConfigResolver#mergeRoute`（`rerankScore × routeWeight`，`DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30`）、`ImageSimilarityGate#downWeightNearDuplicates`（`rerankScore × weight`）。
- **反算**：`r = δ/(0.30(1−w))`，`δ=0 ⇔ r=0` **12/12** 成立；4 条零位移 `r` 恰为 0（q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`），且 `tier=NEAR_DUPLICATE`、`downWeighted=true` 三者并存 ⇒ 结论：**降权对 `r=0` 的候选是结构空操作**（可证）。
- **一致性检查**：6 查询中 `nonDegenerate=3`、`degenerate=3`，`counterExamples=0`；`upgraded=false`。⇒ 「min-max 末位」只在非退化查询上可谈，**仍停在 `CONSISTENT-NOT-PROVEN`**。
- **判定**：`verdict=NEEDS-INSTRUMENTED-RUN`，理由是该值覆盖不了 OQ1 的因果问句；`instrumentedPlan`（4 字段 + 30 外呼）`executed=false`。
- **实测**：write 1/1（0.568 s）、默认 8/8（0.626 + 0.098 s）+ 规范化字节一致；md 30 行 / `CR=0`；锚 `CR=0` / 26559 字节。
- **复核后 fix1（2026-10-26，主 agent 代笔）**：① `PROVEN` 反向断言（禁「末位 / min-max 把 / 归 0 造成 / 成因是」，注入即红，实测过）；② md 补退化分支（`max ≤ 0` 全体归 0、`max == min` 全体 1，原句只在非退化成立）；③ 「逐字节」措辞改为「规范化字节（JSON 树规范化 / md 真字节）」；④ tasks 17 条补勾（`MISSING-EVIDENCE` 实为 4 条，含 M4 权重本身也是推断）。
