# proposal · near-dup-oq1-attribution（卡 32 的 `delta = 0` 归因：机制定位 + 可判定性判定）

> 卡 34。**零外呼归因卡**：只读既有冻结锚 + 生产源码，不跑检索、不调 provider、不改生产。
> 零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动 / 不改任何阈值常量。

## §1 问题陈述

卡 33 把卡 32 的 12 条降权事件逐条摊开后，留下一个**明确的缺口**（锚 `near-dup-window-coupling-v1.json#openQuestions.OQ1`）：

> 12 条被标记降权的事件里，有 **4 条** `fusedA == fusedB`（`delta = 0`）：q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`。它们的 `tier` 都是 `NEAR_DUPLICATE`、`downWeighted = true`，但**分数一分没动**。

卡 33 按 D3 如实记了缺口、**没有编因果**。候选解释当时写的是「图像路该候选重排分为 0」，但它**未被实测**，因此在板、锚、任务卡三处都标注「不得引用」。

本卡要回答的正是：**这个解释能不能在零外呼的前提下被确立？** 如果能，就以可复算的方式钉死；如果不能，就把「差的到底是哪一个字段」说清楚，并给出最小埋点方案（**不执行**）。

## §2 侦察已得事实（执行者不必重查）

- **位移的公式是确定的**：降权发生在融合前，对图像路候选的 `rerankScore` 乘 `w`（`ImageSimilarityGate#downWeightNearDuplicates`），融合时按路由权重累加（`RetrievalConfigResolver#mergeRoute`：`rerankScore × routeWeight`），图像路由权重 `0.30`（`RetrievalConfigResolver:19` `DEFAULT_IMAGE_ROUTE_WEIGHT`）。故
  `δ = fusedB − fusedA = 0.30 × (1 − w) × r`，其中 `r` 是该候选在**图像路的重排分**。
- **`δ = 0 ⇔ r = 0`**（因 `0.30 > 0` 且 `w ∈ {0.75, 0.60, 0.50} < 1`）。这条是**恒等式**，不依赖任何测量。
- **`r` 可以为 0，机制在源码里**：`DefaultWeightedReranker#rerank` = `normalizeScores(向量分)·w_vec + normalizeScores(bm25)·w_bm25`；而 `#normalizeScores` 是 **min-max**：
  - 候选列表的**最小值 ⇒ 归一化 0.0**（逐分支明确）；
  - `max <= 0` ⇒ 全 0；`max == min` ⇒ 全 1.0。
  ⇒ **每个查询里排在末位的图像候选，其图像贡献天然是 0**，对它降权必然是 no-op（`0 × w = 0`）。
- **锚里没有 per-route 分数**：卡 32 的 `candidates` 只有 `fusedA / fusedB / cos / tier / band / rankA / rankB`；`cos` 是图像向量余弦（`hit.score()`），**不是** `r`。⇒ 「用锚证明 `r = 0` 的成因是 min-max」**做不到**，只能做一致性检查。
- **卡 32/33 的锚都不可改**（各自守卫逐字节钉死）；本卡只能读。

## §3 判据（R0–R8）

- **R0 零外呼**：不设任何 `*_REAL`、不启动 Spring 上下文、不 instance provider、不跑检索；输入 = `near-dup-band-entry-v1.json` + `near-dup-window-coupling-v1.json`（只读）+ 源码字面。
- **R1 机制锚定（代码，三处）**：逐处给出 `文件:行 + 一字不差的代码片段`，落锚 `mechanism.codeFacts`：
  1. `DefaultWeightedReranker#normalizeScores` 的 min-max 与三条分支（最小值→0、`max<=0`→全 0、`max==min`→全 1）；
  2. `RetrievalConfigResolver#mergeRoute` 的 `rerankScore × routeWeight` 累加，且 `DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30`；
  3. `ImageSimilarityGate#downWeightNearDuplicates` 的 `rerankScore × weight`。
  守卫须用 grep 断言这三段代码**仍存在**（防上游漂移；不存在的即判红，不许改成「我记得是这样」）。
- **R2 位移-贡献恒等式**：从卡 32 锚反算 12 条 `r = delta / (0.30 × (1 − w))`，落锚 `dataCheck.rByEvent`（含 `queryIndex / variantKey / w / delta / r`）；断言 `r ∈ [0, 1]`，且 `delta == 0 ⇔ r == 0`（逐条）。
- **R3 结构性推论（本卡最强的可证结论）**：`r = 0 ⇒ 融合里该候选的图像贡献为 0 ⇒ 对它降权是**结构空操作**（no-op）`，与它是否带 `NEAR_DUPLICATE` 标记无关。断言：4 条零位移事件 `r` 恰为 0，且锚内 `downWeighted = true` 同时成立——**两者并存不是矛盾，而是「标记了但无贡献」**。
  该推论必须写成**可复算的形式**：`0.30 × (1 − w) × 0 = 0`，逐条落在锚 `dataCheck.noOpProof`。
- **R4 可判定性判定（诚实边界，本卡的核心交付）**：明确区分三类陈述，落锚 `attribution.claimClass`：
  - `PROVEN`：R1 的三处代码 + R2 的恒等式 + R3 的空操作（这些不依赖缺失字段）；
  - `CONSISTENT-NOT-PROVEN`：`r = 0` 由 min-max 末位造成——只能用**一致性检查**支持（见下），**不得**升格为结论；
  - `MISSING-EVIDENCE`：`r` 本身、`routeListSize`、`bm25` 归一值均未落锚。
  一致性检查（必须实现，结果落锚）：对每个查询，取该查询**已标记**候选的 `cos` 与反算 `r`，检查「`r` 最大者的 `cos` 是否也最大」——若一致则记为「与 min-max 结构相容」，**仍不升级为证明**。
- **R5 三值判定**：`verdict` 只能取
  - `MECHANISM-CONFIRMED`（R1 三处代码齐全 + R2 恒等式成立 + R3 逐条成立 + R4 一致性检查不矛盾）；
  - `INCONCLUSIVE`（一致性检查出现反例，且不是浮点噪声）；
  - `NEEDS-INSTRUMENTED-RUN`（要把「min-max 末位」写成结论所需字段不在锚内）——**本卡预期落在这里或 `MECHANISM-CONFIRMED`，两者都可接受**；禁止为了得到前者而放宽 R4。
- **R6 埋点方案（仅当 `NEEDS-INSTRUMENTED-RUN` 时交付，**不执行**）**：给出最小字段集（建议：`imageRerankScore`、`imageRankInRouteList`、`routeListSize`、`fusionImageContribution` 四项）+ 复用卡 32 语料重跑所需外呼估算（`6 摄取 × 3 + 6 查询 × 2 = 30`）+ **为什么不能再省**（少一项就无法区分「r=0 因为 min-max」与「r=0 因为该候选根本没进图像路」）。落锚 `instrumentedPlan`，并写明**本卡未执行、不得据其声明结论**。
- **R7 边界**：`src/main` 零 diff；`pom.xml` / `.github/**` 零改；既有锚全量零字节变动；`NearDupBandEntry*` / `NearDupWindowCoupling*` / `NearDupS1CropCorpus` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*` **只读**。
- **R8 停手**：任何一条「合理但未测」的解释被写成结论 ⇒ 判不合格（沿用卡 33 D3）。R4 的 `claimClass` 必须让读者一眼看出**哪句是证出来的、哪句只是不矛盾**。

## §4 交付物

- 新分析测试：`src/test/java/com/slz/crm/quality/NearDupOq1AttributionAnalysisTest.java`（写模式生成锚；默认逐字节复算）
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupOq1AttributionGuardTest.java`（含源码 grep 断言）
- 结论锚：`docs/rag-quality/near-dup-oq1-attribution-v1.json` + `.md`（≤40 行）

## §5 成本

**0 次外呼**（不设 opt-in、不碰真跑类）；0 迁移、0 契约、0 权限、0 新依赖。CPU 级：读两个锚（约 250 KB）+ 算术 + 源码 grep。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 只读既有锚**：不得为补字段而重跑卡 32 的 IT（那要 30 次外呼，属另卡）。
- **D2 反算只用恒等式**：`r = delta/(0.30(1−w))`；**禁止**拿 `cos` 当 `r`（`cos` 是向量相似度，`r` 是重排分，两者量纲不同——卡 27 的量纲纪律）。
- **D3 结论分级不可省**：`PROVEN` / `CONSISTENT-NOT-PROVEN` / `MISSING-EVIDENCE` 三档必须逐条落；把第二档写成第一档即判不合格。
- **D4 不改生产、不改常量**：本卡只产「机制在哪 + 缺什么」，不改 `rerank`、不改权重、不改 `downWeightNearDuplicates`。
- **D5 埋点方案是决策附件不是执行单**：写了方案不等于获准跑；预算另请授权。

## §7 与卡 32/33 的关系

- 卡 32 造出 12 条被标记的降权事件，其中 4 条无位移；卡 33 把「标记 ≠ 位移」拆开并把成因记为 OQ1，**明确写了「不得引用未经实测的解释」**。
- 本卡是那条缺口的正式归因尝试：**先把「能不花钱证明的」证明掉**（降权对图像贡献为 0 的候选是 no-op），**再明确「证明不了的」缺哪个字段**——这样下一位读者不会再把「min-max 末位」当既成事实引用。

## §8 判据修订记

- （预置）若一致性检查出现反例，按 R5 判 `INCONCLUSIVE` 并在本节记录反例的查询与数值；**不许**回头改 R4 的判据去迁就数据。
