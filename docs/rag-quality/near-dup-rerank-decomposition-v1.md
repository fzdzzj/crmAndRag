# near-dup-rerank-decomposition-v1 · r = wv·normVec + wb·normBm25 的本地零外呼分解
- 卡 35（零外呼）：只读卡 32/33/34 三锚 + 冻结 `corpusMaterial`；复算链 = 生产 `InMemoryVectorStore.search` → `ImageSimilarityGate.gate` → `DefaultWeightedReranker.rerank`（内含生产 `Bm25Scorer`）。不启 Spring、不 instance provider、不设任何 `*_REAL`，`externalCalls = 0`。
- 归一化不重写（D2）：`normVec` / `normBm25` 由生产 `normalizeScores` 的输出反解——文本全空探针得 `wv·normVec`，向量分常数探针得 `wv + wb·normBm25`；两次都直调生产 `rerank`。
- 权重实测：`wv = 0.6`、`wb = 0.4`（卡 32 配置矩阵不覆盖两键 ⇒ 生产回落默认字面）；负对照（矩阵写 0.90/0.10）探针同步给出 0.9 / 0.09999999999999998。
- 候选集重建与卡 32 锚逐条一致：30 行里 cos 非空的 15 行（图像路成员）身份 + cos + tier/band 全等，cos 空的 15 行确认不在图像路；`routeListSize` = [3,3,3,2,2,2]。
## R1 · 复算 r 与卡 32 反算 r
- `r = δ / (0.30 × (1 − w))`，δ = fusedB − fusedA；逐条比对（容差 1e-9）：**12/12 一致**，`maxAbsDiff = 5.551115123125783E-16`。
## R2 · 两分支分桶（退化 = 生产 min-max 某分支归一值全同）
| q | form | route | marked | normVec 分支 | normBm25 分支 | bucket |
|---|---|---|---|---|---|---|
| 0 | title | 3 | 3 | 正常（min → 0） | 退化（全同 1.0，max == min） | DEGENERATE-NOT-EVIDENCE |
| 1 | first-sentence | 3 | 3 | 正常（min → 0） | 退化（全同 0.0，max <= 0） | DEGENERATE-NOT-EVIDENCE |
| 2 | caption-keywords | 3 | 3 | 正常（min → 0） | 退化（全同 1.0，max == min） | DEGENERATE-NOT-EVIDENCE |
| 3 | title | 2 | 1 | 正常（min → 0） | 退化（全同 0.0，max <= 0） | DEGENERATE-NOT-EVIDENCE |
| 4 | first-sentence | 2 | 1 | 正常（min → 0） | 退化（全同 0.0，max <= 0） | DEGENERATE-NOT-EVIDENCE |
| 5 | caption-keywords | 2 | 1 | 正常（min → 0） | 退化（全同 0.0，max <= 0） | DEGENERATE-NOT-EVIDENCE |
- 卡 34 `consistencySummary`（degenerate 3 / nonDegenerate 3，口径 = 被标记候选数 == 1）与本卡口径交叉：分桶口径说明：R2 的「退化」指生产 normalizeScores 的三分支退化（max <= 0 → 全 0；max == min → 全 1），不是卡 34 consistencySummary 的「被标记候选数 == 1」。本卡实测两把尺子不重合（生产退化查询：q0,q1,q2,q3,q4,q5），故分开列、不合并；合并会把退化分支里的 r = 0 当末位证据。
## 4 条零位移事件（δ = 0）
| q | variantKey | cos | normVec | normBm25 | r | normVecAtZero | normBm25AtZero | bucket |
|---|---|---|---|---|---|---|---|---|
| 1 | nist-sp800-101r1-p1-b1@crop-06 | 0.9907350787192692 | 0.0 | 0.0 | 0.0 | true | true | DEGENERATE-NOT-EVIDENCE |
| 3 | nist-sp800-101r1-p16-b2@crop-04 | 0.988903987313388 | 0.0 | 0.0 | 0.0 | true | true | DEGENERATE-NOT-EVIDENCE |
| 4 | nist-sp800-101r1-p16-b2@crop-04 | 0.988903987313388 | 0.0 | 0.0 | 0.0 | true | true | DEGENERATE-NOT-EVIDENCE |
| 5 | nist-sp800-101r1-p16-b2@crop-04 | 0.988903987313388 | 0.0 | 0.0 | 0.0 | true | true | DEGENERATE-NOT-EVIDENCE |
## R3 · 结论
- `verdict = UNDETERMINED`：复算 r 与卡 32 反算 r 12/12 条一致（容差 1e-9，maxAbsDiff = 5.551115123125783E-16）；4 条零位移事件里 4/4 条两分支归一值同时为 0.0（向量分支全部为末位），0/4 条落在两分支皆非退化的查询（零位移事件所在查询：q1,q3,q4,q5） ⇒ 向量分支的末位是实测成立的（4/4 的 normVec = 0.0 且确为最小 cos），但 bm25 分支在 q0/q2（bm25 分支 全同 1.0，max == min）、q1/q3/q4/q5（bm25 分支 全同 0.0，max <= 0） 上退化：该分支全体归一值相同（全 0.0 或全 1.0），所有候选并列最小/并列最大，「末位」在该分支里不可分辨 ⇒ 按 R2/D3 只能记 DEGENERATE-NOT-EVIDENCE，R3 的 PROVEN 条件（4/4 落在两分支皆非退化的查询）不成立，故落 UNDETERMINED（不升格、不改 R3 迁就数据）
- 结构发现（数据层面，不改 verdict）：r = 0 要求 wv·normVec + wb·normBm25 = 0，两权重皆正 ⇒ 必须 wb·normBm25 = 0。本语料 6 条查询的 bm25 分支全部退化：q0/q2 全同 1.0（实测直接成因：同 route 的候选 `text` 逐字节相同，长度归一化后同分 ⇒ max == min；这是本语料构造的性质，不是 BM25 饱和的普遍规律，query 实际只命中 `nist` 一个 token）；q1/q3/q4/q5 全同 0.0（查询词零命中 ⇒ max <= 0）。在 bm25 全 1.0 的查询里 r ≥ wb·1 = 0.4 > 0，零位移不可能出现；4 条零位移事件（q1,q3,q4,q5）恰好全部落在 bm25 全 0 的查询里。这些查询里 bm25 项恒为 0，故 r = 0 的可观测成因只剩「向量分支末位」，而它在 4/4 条上实测成立（normVec = 0.0 且该候选的 cos 为其 route 内最小）。受 R2/R3 约束（PROVEN 要求两分支皆非退化）本条仍记 UNDETERMINED——这不是缺证据，而是本语料的 bm25 分支在图像描述文本上不是连续量（要么全命中、要么零命中），使「末位」在那一支不可分辨
- 三值可达路径：`PROVEN` = 12/12 复算一致 ∧ 4/4 零位移两分支同时末位 ∧ 4/4 落在两分支皆非退化的查询；`REFUTED` = 复算一致但零位移事件非两分支末位；`UNDETERMINED` = 复算对不上 / 候选集重建不全 / 零位移事件只落在退化分支（按 R2 不构成末位证据）。
- R4 埋点：`executed = false`（required = true、wouldInstrumentationHelp = false）；本地能算的都算了，卡住的不是「缺字段」而是「数据本身让该分支不可分辨」：q0/q2（bm25 分支 全同 1.0，max == min）、q1/q3/q4/q5（bm25 分支 全同 0.0，max <= 0） 的 bm25 分支归一值全同 ⇒ 该分支里所有候选并列同一个归一值，「末位」不可分辨。运行期快照只会复现本次复算的同一批数字（同一份 corpusMaterial、同一套生产实现），因此再加埋点也改不了本条判定。解锁只能靠换语料/查询让 bm25 分化，或由 Owner 在另卡修订口径（见锚 `unlockPathsIfOwnerWantsProven`）。
## 腿 B · `NEAR_DUPLICATE_FLOOR` 0.90 决策页（两栏并列，不给倾向值，不改任何常量）
| 口径 | 维持 0.9887 | 降到 0.90 |
|---|---|---|
| 本 base 近重复保留 | 70/120（58.33%） | 113/120（94.17%） |
| 变体↔第三图进带 | 13/2760（0.47%） | 93/2760（3.37%） |
| 同文档图被压（S_same 分母） | 2/100 | 8/100（多压 6 张） |
| 新增进带 | 0 | 本 base +43（/120）、变体↔第三图 +80（/2760） |
| 已记录代价 | 无新增（近重复保留仅 70/120） | R = 94.17%（113/120）、多压 6 张同文档图 |
- 结构性惰性提示：现行带内 12 条被标记候选中已有 4 条实测 r = 0（结构空操作，降权不产生任何位移）；0.90 新增进带对（本 base +43、第三图 +80）是否也含这类惰性子集，本卡无数据、不外推；且新增候选会改变 min-max 的 min ⇒ 既存候选的 r 可能同步改变，属未测项
- 现行生产参照（非选项）：`NEAR_DUPLICATE_FLOOR = 0.982`，第三图进带 22/2760。
- 待 Owner 决策项：`NEAR_DUPLICATE_FLOOR` 是否变更、变更到哪一档；本卡只给两栏与代价。细节锚：`docs/rag-quality/near-dup-rerank-decomposition-v1.json`（默认全量重算后按树规范化比对，剔除顶层 `runtime`）。
