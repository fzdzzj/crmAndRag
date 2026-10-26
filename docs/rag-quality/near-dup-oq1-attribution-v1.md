# near-dup-oq1-attribution-v1 · OQ1 归因：证得到的 no-op，证不到的 min-max 末位

- 卡 34（零外呼归因卡）：输入仅卡 32 冻结锚 `near-dup-band-entry-v1.json` + 卡 33 锚 `near-dup-window-coupling-v1.json`（只读）与三处源码字面；不启动 Spring、不 instance provider、不设任何 `*_REAL`。
- 问题（卡 33 记 OQ1）：12 条被标记降权的事件里 **4 条** `fusedA == fusedB`（δ = 0），被标记却一分没动。

## 结论 1 · PROVEN：δ = 0.30(1−w)r 且 δ = 0 ⇔ r = 0 ⇒ 降权对 r = 0 的候选是结构空操作

- 机制三处（片段一字不差，守卫逐处 grep）：`DefaultWeightedReranker#normalizeScores` min-max（min → 0 / `max ≤ 0` → 全 0 / `max == min` → 全 1）；`RetrievalConfigResolver#mergeRoute` 的 `rerankScore × routeWeight` 累加且 `DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30`；`ImageSimilarityGate#downWeightNearDuplicates` 的 `rerankScore × weight`。
- 恒等式：`δ = fusedB − fusedA = 0.30 × (1 − w) × r`；因 `0.30 > 0`、`w ∈ {0.75, 0.60, 0.50} < 1` ⇒ `δ = 0 ⇔ r = 0`。
- 实测逐条（12/12，禁抽样）：`r = δ / (0.30 × (1 − w))` 全部落在 [0.0000, 1.0000]；4 条零位移事件的 `r` 恰为 0，逐条算式 `0.30 × (1 − w) × 0 = 0`。
- 该结论**不依赖缺失字段**：δ 与 w 都是卡 32 锚实测，r 由恒等式反算（D2：不拿 `cos` 当 `r`，量纲不同）。

## 结论 2 · CONSISTENT-NOT-PROVEN：r = 0 的成因很可能是 min-max 把末位归 0

- 源码上确有这条路径（末位 → 归一化 0.0），且 `r = 0.60 × normVec + 0.40 × normBm25` 要求两条分支同时为 0（两权重为正）⇒ 该候选必须是两条分支的末位。
- 一致性检查（6/6 无矛盾）：q0/q1/q2 里「反算 r 最大者」与「cos 最大者」是同一候选（`@crop-04`）；q3/q4/q5 只有 1 条被标记候选，检查退化为恒真（非退化 3 条 / 退化 3 条）。
- 辅助观察（同样不升级）：4 条 `r = 0` 的候选在各自查询 cos 有值的行里均为 cos 最小；q0–q2 的 r 次序与 cos 次序完全一致（`@crop-04` > `@crop-02` > `@crop-06`）。
- **这两句不是结论**：`cos` 只是图像向量相似度，`r` 是重排分；没有 `r`、`routeListSize` 就分不清「末位归 0」与「该候选只有 1 条 / 根本不在图像路」。

## 结论 3 · MISSING-EVIDENCE：差的字段

- `imageRerankScoreA / imageRerankScoreB`（`r` 本身）、`routeListSize`、`bm25` 归一值、`rag.retrieval.rerank.vector-weight / bm25-weight` 实测值（`r ∈ [0,1]` 以两者按默认 0.60/0.40 求和为 1 为前提）。

## 判定 · NEEDS-INSTRUMENTED-RUN（埋点方案未执行）

- `verdict = NEEDS-INSTRUMENTED-RUN`：no-op 恒等式已证，但 OQ1 问的是「r 为什么是 0」，其因果不在锚内，故不取 `MECHANISM-CONFIRMED`。
- 最小字段集（4 项）：`imageRerankScore` / `imageRankInRouteList` / `routeListSize` / `fusionImageContribution`；预算 6 摄取 × 3 + 6 查询 × 2 = **30 外呼**。
- 为什么不能再省：少 `imageRerankScore` 分不清「r = 0」与「r ≠ 0 但融合抵消」；少 `imageRankInRouteList` / `routeListSize` 分不清「末位归 0」与「列表退化」；少 `fusionImageContribution` 仍要反解 δ。
- **本方案未执行、不得据其声明结论**（D5）。细节锚：`docs/rag-quality/near-dup-oq1-attribution-v1.json`（默认按本次全量重算逐字节比对，剔除顶层 `runtime`）。
