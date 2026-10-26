# proposal · near-dup-conditional-min-criterion（末位判据条件化 + 0.90 变更的影响与重锚清单）

> 卡 36。**零外呼两腿一卡**：腿 A 修订判据（把卡 35 卡死的那句改成条件化形式），腿 B 给出「降门槛到 0.90」的**影响清单与重锚清单**。
> **不改任何生产常量**（`NEAR_DUPLICATE_FLOOR` / 三档权重 / `BAND_REFERENCE_FLOOR` 一个不动）；零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动。

## §1 问题陈述

**腿 A 的由来**：卡 35 把 `r` 拆开了（`r = wv·normVec + wb·normBm25`，12/12 复算一致），但判 `UNDETERMINED`——因为卡 34 的 R3 要求「4 条零位移事件全在**非退化**查询、且两分支同时取最小」，而 bm25 分支在**本语料 6/6 查询退化**（q0/q2 全 1.0、q1/q3/q4/q5 全 0.0）。那条判据**不可满足**（板规 31）。

实测事实已经足够把它写成**条件化判据**：

| bm25 分支状态 | 代入 `r = wv·normVec + wb·normBm25` | 关于 `r = 0` 的结论 |
| --- | --- | --- |
| 全 0（`max<=0`） | `r = wv·normVec` | `r = 0 ⟺ normVec = 0`（**向量分支末位即充分必要**） |
| 全 1（`max==min>0`） | `r ≥ wb > 0` | **`r = 0` 不可能**（可证的不可能性） |
| 非退化 | 两项都参与 | 需两分支同时取最小（卡 34 原判据 = 本判据的特例） |

**腿 B 的由来**：0.90 议题过去只到「给选项与代价」（卡 32/35）。但真降门槛不是改一个常量——**卡 28/31/32/33/34 的冻结锚都是在 `0.9887` 口径下测的**，门槛一降，它们记录的 `tier` / `band` / 带内对数就与现行常量自相矛盾，守卫会红。本腿把「哪几张会废、各自怎么重建、要不要花钱」列清，作为 Owner 拍板的输入。**本卡不改常量、不执行重建。**

## §2 侦察已得事实（执行者不必重查）

- **判据的三个分支已在卡 35 锚里落值**：`decomposition` 15 行含每候选 `normVec` / `normBm25` / `r`；`bucketSummary` 记 `degenerateQueries`；`weights = {vector: 0.6, bm25: 0.4}`（受控探针 + `0.9/0.1` 负对照反解）。
- **依赖门槛的冻结锚（按用途分类，执行者须逐张核对后落锚）**：
  - 把 `tier` / `band` / `bandSide` **分类结果**冻进锚的：`near-dup-floor-signoff-v1`、`near-dup-ingest-gate-probe-v1`、`near-dup-ingest-s1-v1`、`near-dup-band-entry-v1`、`near-dup-window-coupling-v1`、`near-dup-oq1-attribution-v1`、`near-dup-rerank-decomposition-v1`；
  - 只冻结**相似度数值/矩阵**、不冻分类的：`near-dup-exchange-rate-v1`、`image-variant-cross-matrix-v1`、`near-dup-displacement-evidence-v1`、`near-dup-fusion-impact-v1`、`image-near-dup-truth-v1`、`near-dup-ablation-v1`、`image-slice-ingest-chain-v1`、`eval-port-route-v1`、`ui-permission-coverage-v1`。
  > 上表是**待核对假设**，执行者必须逐张打开源锚确认「有没有把分类结果写死」，并把核对结果与依据字段落锚；**不许照抄本表当结论**。
- **关键工程事实**：`tierOf` / `bandOf` 是 `cos` 与门槛的**纯函数**。若锚内已冻结 `cos`（或向量），则「降门槛后的分类」可以**零外呼本地重算**；只有需要**新样本**时才要外呼。这一条决定腿 B 的代价量级，必须逐锚判。
- **不再改生产**：本卡零外呼、零 `src/main` diff；0.90 的实际变更属**另卡**，且须 Owner 拍板。

## §3 判据（R0–R8）

- **R0 零外呼**：两腿都不设 `*_REAL`、不启动 Spring、不调 provider；输入是既有锚 + 生产纯函数 + 源码字面。
- **R1 判据改写（腿 A 的核心交付）**：把「末位」判据写成**三分支条件式**，逐分支给出「`r = 0` 的充要条件」：
  1. bm25 全 0 ⇒ `r = 0 ⟺ normVec = 0`；
  2. bm25 全 1 ⇒ `r ≥ wb`，`r = 0` 不可能；
  3. 两分支非退化 ⇒ `r = 0 ⟺ normVec = 0 ∧ normBm25 = 0`（原判据，作为特例）。
  三分支必须**互斥且穷尽**，并在锚内给出可执行判定式（用 `max`/`min`/归一整值判，不引入新推导量）。
- **R2 判定词升级（必须说明理由）**：原三值 `{PROVEN, REFUTED, UNDETERMINED}` 无法表达「在适用域内已证」，故引入第四值 **`PROVEN-CONDITIONAL`**，并要求**四值各自给出可达路径**（板规 24）：
  - `PROVEN-CONDITIONAL`：R1 三支成立 ∧ 全部零位移事件都落在「其所属分支的充要条件」内 ∧ 适用域在锚内写明；
  - `PROVEN`：仅在两分支都非退化且两分支同时取最小（本语料不可达，**必须写明这是可达但本语料未出现的分支**）；
  - `REFUTED`：某条零位移事件违反其所属分支的充要条件；
  - `UNDETERMINED`：复算不一致或无法判定分支状态。
- **R3 逐条复判（卡 35 锚为只读输入）**：对 12 条事件逐条判「所属分支 + 是否满足该分支充要条件」，落锚 `conditionalVerdict.rows`；断言 4 条零位移全落「bm25 全 0」桶且 `normVec = 0`；断言 8 条非零事件不违反其分支条件。
- **R4 反向断言（板规 30）**：`PROVEN`（无条件）档的文本不得出现「末位 / 两分支同时」等**未在本语料成立**的措辞；`PROVEN-CONDITIONAL` 档必须携带 `applicabilityDomain` 字段，缺则红。
- **R5 腿 B 影响清单（零外呼，只列事实）**：逐锚给出「是否冻了分类结果（字段路径）/ 降门槛后是否自相矛盾 / 重算是否可零外呼（依据：是否冻了 `cos` 或向量）/ 若需外呼的最小量（并说明为什么非跑不可）」；每行可指回源锚字段。
- **R6 腿 B 重锚清单与代价**：给出「必须重锚的锚清单 + 每张的重建方式 + 是否花钱 + 顺序（依赖关系）」，并明确**本卡不执行**；同时给出「哪些锚不受影响」的清单（同样要逐张核对）。
- **R7 边界**：`src/main` 零 diff；`pom.xml` / `.github/**` 零改；**全部既有锚零字节变动**（本卡只读）；禁改类只读。
- **R8 停手**：任何一条「影响判定」若无法从源锚字段证明（例如你说某锚冻了分类却指不出字段）⇒ 记 `UNDETERMINED`，不许猜；腿 B 的清单里**不许出现「建议降/建议不降」**（决策是 Owner 的）。

## §4 交付物

- 新分析测试：`src/test/java/com/slz/crm/quality/NearDupConditionalMinCriterionTest.java`
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupConditionalMinCriterionGuardTest.java`
- 结论锚：`docs/rag-quality/near-dup-conditional-min-criterion-v1.json` + `.md`（≤40 行）

## §5 成本

**0 次外呼**。0 迁移、0 契约、0 权限、0 新依赖。腿 B 的「若需外呼的最小量」只是**估算**，不执行。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 判据只能更精确、不能放宽**：R1 的三分支是「把原判据拆到适用域」，不是降低门槛；若某分支需要放松条件才能成立，说明推导错了，停手。
- **D2 分支判定不许反解**：分支状态用 `normBm25` 的整值全同判定（全 0 / 全 1 / 否则），不做数值拟合。
- **D3 影响判定必须指字段**：每张锚「是否冻了分类」必须给出**字段路径 + 值样例**；指不出就是 `UNDETERMINED`。
- **D4 不替 Owner 决策**：腿 B 只列事实与代价，**禁推荐**。
- **D5 不执行重建**：即使清单说「可零外呼重算」，本卡也不重算任何既有锚——那是另卡的事，且会动别人卡的成品。

## §7 与卡 32–35 的关系

卡 32 造事件、卡 33 拆计数、卡 34 证 no-op、卡 35 拆 `r` 并撞上「判据不可满足」。本卡把卡 35 撞出来的那句话改成**条件化判据**，并把 0.90 从「选项与代价」推进到「impact + 重锚清单」——两件都是零外呼能把 Owner 决策所需的输入备齐的最后一段路。

## §8 判据修订记

- （预置）本卡本身即判据修订卡：若 R1 三分支无法穷尽（出现第四种分支状态），在此记录该状态与处置（只能更保守），并把 `verdict` 落到 `UNDETERMINED`；不许放宽 R2 的四值定义。
- （追记 · 主 agent 代笔，2026-10-26）**第四种分支状态未出现**：`criterion.branches` 恰 3 条，`queriesByBranch` 三键合计 6（B0=4 / B1=2 / B2=0），穷尽性成立。
- （追记 · 主 agent 代笔，2026-10-26）**1 张锚落 `UNDETERMINED`**：`near-dup-ingest-gate-probe-v1`（`contradictionCause=AGGREGATE-ONLY`）只记聚合值，指不出行级分类字段路径 ⇒ 按 R8 不猜，判为未判定。处置（保守方向）：不进「受影响」也不进「不受影响」，单列 `impact.summary.unresolved`（1 张 / 最小外呼 18），且**不并入** `fullRerunTotal=221`（含之 239，见 `fullRerunTotalWithUnresolved`）。该处置与 D3/R8 一致，未放宽任何判据。
