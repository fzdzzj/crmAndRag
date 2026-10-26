# tasks · near-dup-rerank-decomposition（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（命令 + 实测值 + 可 grep 的符号/字段名）。**禁止**写「已完成」了事。三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 / 决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。
> 行号会漂移；依据以类名 / 方法名 / 锚字段名为锚点。

## §0 · 硬约束

- **首选零外呼**：腿 A 先本地复算；只有判 `UNDETERMINED` 才交付埋点方案（不执行）。腿 B 恒定零外呼。
- 只读输入：`near-dup-band-entry-v1.json`、`near-dup-window-coupling-v1.json`、`near-dup-oq1-attribution-v1.json`（**均不可改**）。
- `src/main` / `pom.xml` / `.github/**` 零 diff；既有锚零字节变动；11 个禁改 `NearDup*` 类只读。
- 锚写模式 `-Dnear.dup.rerank.decomposition.write=true`。

## A · 腿 A · `r` 分解复算（R1 / R2）

- [x] 1. 用锚内 `corpusMaterial`（`textRecords` / `imageRecords` / `embedCache` / `queryImageVectors`）重建 6 查询的图像路候选集，**不跑检索、不调 provider**
      补勾依据：复算链 = 生产 `InMemoryVectorStore.search` → `ImageSimilarityGate.gate` → `Bm25Scorer` → `DefaultWeightedReranker`；锚 `reconstruction` 记候选 **30 行全重建**（图像路 15 + 路外 15，`routeListSize = 3/3/3/2/2/2`）；`acceptedCandidates=30`。零检索调用（`runtime.externalCalls=0`）。
- [x] 2. 直接调用生产 `Bm25Scorer` 与 `DefaultWeightedReranker` 的复算口径（**不自己重写 min-max**，D2），落每候选 `normVec` / `normBm25` / `r`
      补勾依据：`normVec`/`normBm25` 取自生产 `rerank` 探针输出；守卫 `assertNoSelfWrittenMinMax` 断言无自写归一化（复核侧亦确认「未换尺子」，并自写 BM25 独立重算 12/12 条 `r`、`maxAbsDiff=5.55e-16`）。逐候选见锚 `decomposition` 表 15 行 + `zeroDisplacementEvents` 4 行。
- [x] 3. 实测记录 `rag.retrieval.rerank.vector-weight` / `bm25-weight` 的生效值（默认 0.60 / 0.40），落锚 `weights`
      补勾依据：锚 `weights = {vector: 0.6, bm25: 0.4}`；反解方法两条受控探针直接调生产 `rerank`（负对照 0.9/0.1 同步还原 0.9/0.1，证明读的是 `resolveRatio` 而非硬编码常量）。
- [x] 4. 与卡 32 锚 `candidates[]` 的 `fusedB − fusedA` 反算 `r` 逐条比对（容差 1e-9），落 `reconciliation`（逐条 + 一致条数）
      补勾依据：`reconciliationSummary = {total: 12, consistent: 12, tolerance: 1e-9, maxAbsDiff: 5.55e-16}`；卡 32/34 恒等式 `r = δ/(0.30(1−w))` 只比数值、不重推。
- [x] 5. 逐条落 `(queryIndex, variantKey, normVec, normBm25, r, normVecAtZero, normBm25AtZero, bucket)`；`bucket ∈ {NON-DEGENERATE, DEGENERATE-NOT-EVIDENCE}`
      补勾依据：字段名已按复核 M5 改为 `normVecAtZero` / `normBm25AtZero`（语义 = 该分支归一值恰为 0，不是「分支最小值」）；`bucketSummary` 6 条查询全落 `DEGENERATE-NOT-EVIDENCE`（bm25 分支 6/6 退化），`bucketRule` 落锚。
- [x] 6. 断言：`r = 0 ⟺ (normVec = 0 ∧ normBm25 = 0)`；4 条零位移事件全落 `NON-DEGENERATE` 且两分支均为最小
      补勾依据：等价式逐行成立（`violationsZeroEquivalence = 0`、`checked = 15`）；4 条零位移事件 `normVec = 0.0` 且 `normBm25 = 0.0` 但桶为 `DEGENERATE-NOT-EVIDENCE`（bm25 全分支退化）⇒ 本条**只完成前半**，后半按 R3 判 `UNDETERMINED`（判据不成立，见第 7 条）。

## B · 腿 A · 结论（R3 / R4）

- [x] 7. 落 `verdict ∈ {PROVEN, REFUTED, UNDETERMINED}` + `verdictReason`，并写清**三值各自的实测触发条件**
      补勾依据：`verdict = UNDETERMINED`；`verdictReachability` 三值逐条写触发条件；实际触发的是 ②「两分支都是最小值，但零位移事件落在退化分支查询」。结构发现（锚 `structuralFinding`）：`r = 0` 只可能出现在 bm25 全 0 的查询（否则 `r ≥ wb = 0.4`），实测 `r = 0` 4 条全在 q1/q3/q4/q5，而 q0/q2 的最小 `r` 恰为 **0.400000**（= `wb`）—— 两侧预言都对上。
- [x] 8. 若 `UNDETERMINED`：落 `instrumentedPlan`（比卡 34 更窄：逐项写「本地卡在哪一项」+ 最小字段 + 预算），`executed = false`
      补勾依据：`instrumentedPlan.executed = false`、`budgetExternalCalls = 0`、`wouldInstrumentationHelp = false`（理由：退化来自数据分布而非缺字段，埋点改变不了 bm25 在图像描述文本上的全同/零命中）；`note` 明写「不得据其声明结论」。

## C · 腿 B · 0.90 决策页（R5）

- [x] 9. 落 `floorOptions`：维持 `0.9887` / 降到 `0.90` 两栏，逐项含「进带对数（分母写明）/ 已记录代价 / 结构性惰性提示 / 待 Owner 决策项」
      补勾依据：锚 `floorOptions` 两栏：own-base `70/120 (58.33%)` ↔ `113/120 (94.17%)`；third-image `13/2760 (0.47%)` ↔ `93/2760 (3.37%)`；same-doc `2/100` ↔ `8/100`（+6 张）；新增 `+43` / `+80`；现行 `0.982` 参照 `22/2760`；`structuralInertiaNote` 记「`r = 0` 的带内候选降权无效」。
- [x] 10. 断言锚与 md **不出现**推荐值或推荐措辞（`推荐` / `建议改为` / `应下调` / `recommend*`），正反两向断言
      补勾依据：守卫双面 grep：锚与 md 命中 0；`R5` 断言「`floorOptions` 不得含推荐字段」。

## D · 锚与守卫（R6 / R7）

- [x] 11. 锚 `docs/rag-quality/near-dup-rerank-decomposition-v1.json`（纯 LF）+ `.md`（≤40 行）；默认复跑逐字节一致（剔除顶层 `runtime`）
      补勾依据：锚 **32263 字节 / CR=0**；md **40 行 / CR=0**；默认复跑 `Tests run: 2, Failures: 0`（分析 0.517 s / 守卫 0.115 s）。复核 M2/M7 修复：`runtime.externalCalls=0` + `excludedFromReplay=true` + `externalCallsBasis`，新增 `numericHygiene.snapRule`（`|x| < 1e-12` 归 0、`|x−1| < 1e-12` 归 1、其余 clamp）。
- [x] 12. 守卫：R1 逐条复算、R2 分桶与等价式、R3 三值可达路径、R4 `executed=false`、R5 无推荐值双面 grep、上游三锚 SHA 实算、md ≤40 行
      补勾依据：守卫绿（`Tests run: 1, Failures: 0`，单方法覆盖上述各项）；上游三锚 SHA 实算并交叉比对。复核 M3 修复：`equivalence.violations` / `violationsZeroEquivalence` 改为**累加计数**（不再是字面 0）。复核 M4 修复：新增独立路径 `vectorNormalizationIndependence`（只用卡 32 锚的 `cos` 断端值映射：`checked=6`、`endpointViolations=0`、`skippedTiedRoutes=0`），守卫断言其违规为 0。
- [x] 13. 边界：`git diff master --stat -- src/main pom.xml .github` 空；既有锚零 `M`（只新增本卡 2 文件）；11 个禁改类零变动；`git diff --check` 干净
      补勾依据：`git diff master --name-status` 恰 4 个 `A`；`src/main`/`pom.xml`/`.github` 零 diff；`git status --porcelain -- docs/rag-quality` 只列本卡 2 个新文件；11 个禁改 `NearDup*` 类零变动；`git diff --check` 空。
- [-] 14. 若复算不可行 ⇒ `proposal §8` 记**具体失败步骤与样本**并判 `UNDETERMINED`；**不许**改 R3 迁就数据
      补勾依据：**未触发**——本地复算完全可行（12/12 一致）。`verdict = UNDETERMINED` 的原因是**判据 R3 的「全在非退化查询」不成立**（bm25 分支 6/6 退化），不是复算不可行；遂按规则不改 R3，记入 `proposal §8` 与锚 `verdictReason`，本条记 `[-]`。
