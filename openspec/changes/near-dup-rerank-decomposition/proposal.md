# proposal · near-dup-rerank-decomposition（OQ1 定案 + 0.90 决策页，两腿一卡）

> 卡 35。**两腿一卡**，且**首选零外呼**：腿 A 先在本地把 `r` 拆开复算（不花钱），复算不成立才落埋点方案（30 外呼，**本卡不执行**）；腿 B 纯零外呼。
> 零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动 / **不改任何阈值或权重常量**。

## §1 问题陈述

卡 34 把 OQ1 收口到「能证/不能证」的分界：

- **已证**：`δ = 0.30(1−w)·r`，`δ = 0 ⇔ r = 0`；`r = 0` ⇒ 图像贡献 0 ⇒ 降权是**结构空操作**（12/12 逐条）。
- **未证**：`r` 为什么是 0。源码里确实有 `DefaultWeightedReranker#normalizeScores` 的 min-max（最小值→0），但卡 32 锚里没有 per-route 分数，只能停在 `CONSISTENT-NOT-PROVEN`，`verdict = NEEDS-INSTRUMENTED-RUN`。

**本卡的不同之处**：卡 34 给的埋点方案是「重跑 30 外呼 + 4 字段」，但侦察发现**大部分材料已经在锚里**——

- `corpusMaterial` 冻结了 `textRecords` / `imageRecords` / `embedCache` / `queryImageVectors`（真跑用的**真实向量与文本**），所以 replay 能逐字节复现分数；
- 卡 32 的 `candidates` 已有 `cos`（= `hit.score()`，即向量分支原分）；
- `Bm25Scorer` 是**纯本地**的（输入 query 文本 + 候选文本，无网络）。

⇒ 理论上可以**零外呼**把 `r = wv·normVec + wb·normBm25` 拆开，验证「4 条零位移候选在两条分支上是否都取到最小值」。若成立，把 `min-max 末位` 从「相容未证」推到 `PROVEN`；若不成立或不一致，如实落 `REFUTED` / `UNDETERMINED`，并给出**更窄的**埋点方案。

腿 B 顺手把 0.90 议题做成决策页（`NEAR_DUPLICATE_FLOOR` 变更仍属另卡、须 Owner 拍板）。

## §2 侦察已得事实（执行者不必重查）

- **卡 32 锚的 `corpusMaterial` 含 4 项**：`textRecords` / `imageRecords` / `embedCache` / `queryImageVectors`；A/B 消融与 replay **共用同一实现**（`NearDupBandEntryIT` 注释原文），默认复跑逐字节一致。
- **`r` 的分解式**：`r = wv · normalizeScores(向量分) + wb · normalizeScores(bm25)`（`DefaultWeightedReranker#rerank`），`normalizeScores` 为 min-max，三分支：最小值→0、`max<=0`→全 0、`max==min`→全 1。
- **`r = 0` 的等价条件**（需 `wv>0 ∧ wb>0`）：`normVec = 0 ∧ normBm25 = 0`，即该候选在**向量分与 bm25 分两个序列里都取到最小值**。`wv/wb` 取自 `rag.retrieval.rerank.vector-weight` / `bm25-weight`（默认 0.60 / 0.40，**本卡须实测记录**）。
- **向量分支末位已有半成品证据**：卡 34 锚 `attribution.routeLevelCosObservation.zeroRIsMinCosAmongRowsWithCos = true`、`checkedZeroREvents = 4`。
- **退化查询占一半**：卡 34 锚 `consistencySummary.degenerate = 3 / nonDegenerate = 3` —— `max<=0` 或 `max==min` 的查询里「末位」不适用，**必须先分桶再判**。
- **腿 B 的数据已在**：`near-dup-band-entry-v1#legB.tierFill` + `thresholdOptions`（own 70→113/120、third 13→93/2760、多压 6 张同文档图、R=94.17%）；卡 34 新增事实：带内可能存在**结构性惰性子集**（`r=0` 者降权无效）。

## §3 判据（R0–R8）

- **R0 零外呼优先（板规 27：把 fail-fast 放在最贵调用之前）**：腿 A 的**第一件事**是本地复算，不许开局就打算花钱；腿 B 恒定零外呼。全卡若最终落 `NEEDS-INSTRUMENTED-RUN`，也只交付方案、不执行。
- **R1 `r` 分解复算（本地）**：对卡 32 的 6 个查询，用锚内冻结的文本/向量重建图像路候选集，按 `DefaultWeightedReranker` 的口径（min-max + 权重）复算每个候选的 `normVec` / `normBm25` / `r`；与卡 32 锚 `candidates[].fusedB − fusedA` 反算的 `r` 逐条比对（容差 1e-9）。
  复算口径必须与生产**同源**：直接调用生产 `Bm25Scorer` / `DefaultWeightedReranker`（其实现只依赖输入，不依赖外部服务）；权重取 `RetrievalConfigResolver` 同名字面默认值，并**实测记录**两个权重值。
- **R2 二分支末位判定（分桶）**：逐条给出 `(queryIndex, variantKey, normVec, normBm25, r, 是否末位)`；判定「`r = 0` ⟺ `normVec = 0 ∧ normBm25 = 0`」，**且必须与 `degenerate / nonDegenerate` 分桶交叉**：退化查询里 `r = 0` 不构成「末位」证据，只能记 `DEGENERATE-NOT-EVIDENCE`。
- **R3 结论三值（腿 A）**：`PROVEN`（复算 `r` 与锚反算逐条一致 **且** 4 条零位移事件全落 `normVec = 0 ∧ normBm25 = 0` 且全在非退化查询）／ `REFUTED`（复算一致但零位移事件不是两分支末位）／ `UNDETERMINED`（复算与锚对不上，或文本/向量不足以重建候选集）。
  三值的**可达路径**必须写清（板规 24）：什么实测输入会让它出现。
- **R4 埋点方案**：仅当 `UNDETERMINED` 时交付，且必须**比卡 34 的更窄**：逐项说明本地复算卡在哪（哪一项缺/对不上），再给最小字段与预算；`executed = false`。
- **R5 腿 B 决策页（零外呼，不给推荐阈值）**：把「维持 `0.9887` / 降到 `0.90`」写成 lexicographic 对照表：口径、新增/减少进带对数（分母写明）、已记录代价、结构性惰性子集提示、待 Owner 决策项。**禁出现推荐值**（守卫 grep 断言），**不改任何常量**。
- **R6 锚与守卫**：锚 `docs/rag-quality/near-dup-rerank-decomposition-v1.json`（**LF**，写模式 `-Dnear.dup.rerank.decomposition.write=true`，默认逐字节复算、剔除顶层 `runtime`）+ `.md`（≤40 行）；守卫钉死 R1 逐条复算、R2 分桶、R3 三值可达路径、R4 方案未执行、R5 无推荐值（正反两向断言）。
- **R7 边界**：`src/main` 零 diff；`pom.xml` / `.github/**` 零改；既有锚（含卡 32/33/34 三张）零字节变动；`NearDupBandEntry*` / `NearDupWindowCoupling*` / `NearDupOq1Attribution*` / `NearDupS1CropCorpus` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*` **只读**。
- **R8 停手**：本地复算若需要跑检索才能进行 ⇒ 停手（那不是本地复算）；复算口径与生产不同源（例如自己重写 min-max）⇒ 判不合格，必须直接调用生产实现。

## §4 交付物

- 新分析测试：`src/test/java/com/slz/crm/quality/NearDupRerankDecompositionTest.java`（写模式生成锚；默认逐字节复算）
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupRerankDecompositionGuardTest.java`
- 结论锚：`docs/rag-quality/near-dup-rerank-decomposition-v1.json` + `.md`（≤40 行）

## §5 成本

**首选 0 次外呼**（本地复算 + 冻结材料 + 纯本地 `Bm25Scorer`）。若判 `UNDETERMINED`，只交付埋点方案，**不执行**；届时预算另请授权。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 先本地后花钱**：任何「跑一次看看」的念头先按下，本地能把 `r` 拆开就不要外呼。
- **D2 口径必须同源**：直接调生产 `Bm25Scorer` / `DefaultWeightedReranker`（或其在测试侧可用的最小子集），**不许自己重写 min-max**——重写就等于换了一把尺子再宣称量到了。
- **D3 退化分桶不可合并**：`degenerate` 的查询里 `r = 0` 不算证据；把两者混算即判不合格。
- **D4 腿 B 不给推荐**：0.90 是 Owner 决策，本卡只给选项与代价。
- **D5 复算对不上就承认**：`UNDETERMINED` 是可接受结局；把不确定写成 `PROVEN` 即判死。

## §7 与卡 32/33/34 的关系

卡 32 造出 12 条降权事件（4 条零位移）→ 卡 33 拆出 `marked ≠ displaced` 并把成因记 OQ1 → 卡 34 证明「no-op 可证、成因待埋点」并留下 4 个缺失字段。**本卡的关键转折是：那 4 个字段里有 3 项其实能从冻结材料本地算出**——真正的缺口只剩「bm25 是否可复算」与「两个权重的运行时值」。

## §8 判据修订记

- （预置）若本地复算在 bm25 侧不可行（分词/IDF 语料域不一致），在此记录**具体哪一步失败**与失败样本，再把 `verdict` 落到 `UNDETERMINED`；**不许**回头改 R3 去迁就数据。
