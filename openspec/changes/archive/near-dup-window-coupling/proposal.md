# proposal · near-dup-window-coupling（窗口判据的耦合修正 + 标记/位移解耦）

> 卡 33。**零外呼判据卡**：只读冻结锚 + 纯算术，不调任何 provider、不写生产代码。
> 零迁移 / 零契约 / 零权限 / `src/main` 零 diff / 既有锚零字节变动 / **不改任何阈值或窗口常量**。

## §1 问题陈述

卡 32 第一次让降权**真的发生**了（`downWeightedCount=12`，4/6 裁切变体落带内），但它留下的判据有一个**已实测的缺陷**：

`windowCheck` 的判法是「`gap > 0.30×(1−w)` ⇒ 不可能翻转」，其中 `gap = fusedB_该候选 − 同查询下位竞争者最大 fusedB`。这个式子**默认竞争者不动**。而实测有 4 条 `gap ≤ window`（按上式「可能翻转」）**却一条都没翻**——因为紧邻下位竞争者**同为被降权候选**，两边一起下沉，相对次序不变。

⇒ 现行窗口判据只能当**上界速筛**（它的结论方向是保守的），**不能当作「翻转可否」的判据**。真判据必须把**两侧的位移都算进去**。

同时卡 32 的锚暴露出第二处口径混用：`downWeightedCount=12` 记的是**被标记**降权的候选数，其中 4 条的分数**根本没动**（`delta == 0`，`fusedA == fusedB`）。于是「降权发生了 12 次」这句话，在「位移」这一面只有 8 次为真。

⇒ 本卡两件事，一卡答两个问题：

- **腿 A（判据）**：给出**精确的耦合判据**——逐事件比较「A 跑空间里该候选 vs 下位竞争者」的间距，用两侧实测位移算 `margin_A`；证明现行窗口是它的**保守特例**（充分不必要），并逐条解释卡 32 的 4 条假阳性。
- **腿 B（计数）**：把「事件发生次数」拆成 `marked`（被标记）与 `displaced`（分数真的动）两个量，给出卡 32 那 4 条 `delta=0` 的候选身份与档位，并把「哪些量能触发翻转」写死为 `displaced`。

## §2 侦察已得事实（执行者不必重查）

- **卡 32 锚是唯一数据源**：`docs/rag-quality/near-dup-band-entry-v1.json`，`candidates` **30 行**（6 查询 × TOP_K 5）、`windowCheck.entries` **12 条**、`downWeightedCount=12`、`verdict=NO-RANK-CHANGE`。**该锚不可修改**（守卫 `NearDupBandEntryGuardTest` 逐字节钉死）。
- **12 条事件的位移分布（已实测，执行者可直接复算）**：`delta > 0` 的 **8 条**（0.044216 / 0.045000 / 0.048000×2 / 0.074216×2 / 0.075000×2），`delta == 0` 的 **4 条**（q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`）。
- **4 条 naive 假阳性**（`gap ≤ window` 却未翻）：q0 `@crop-02` gap=0.005159 / q1 `@crop-02` gap=0.017033 / q2 `@crop-02` gap=0.000943（三者的下位竞争者**都是**同查询的 `@crop-04`，其 `delta=0.075 > 该事件 delta`）；q5 `@crop-04` gap=0.043989（该事件本身 `delta=0`，根本无位移）。
- **位移与档位的关系**：`delta = fusedB − fusedA`，实测值 ≤ `window = 0.30×(1−w)`；`w` 取 `windowCheck.entries[i].weight`（0.75/0.60/0.50 三档）。
- **窗口公式的出处**：`NearDupBandEntryIT.java:145` `IMAGE_ROUTE_WEIGHT = 0.30D`（`RetrievalConfigResolver` 字面值），`:810` 计算 `window`。**本卡不改它**。
- **`tier` 字段可为 null**：`candidates` 里非图像路候选 `tier == null`（如 q0 的 A=4/A=5）。判据必须容忍 null（null ⇒ 视为未降权，`δ_r = 0`）。

## §3 判据（R0–R8）

- **R0 零外呼**：本卡**不设任何 opt-in**，真跑类一律不碰；全部输入为冻结锚 + 纯算术。任何一条断言若需要重新跑检索才能成立，**判不合格**（改为记缺口，见 D3）。
- **R1 精确判据（耦合）**：对每个被标记的降权事件 `e`（取 `windowCheck.entries[i]`）与其「下位最强竞争者 `r`」（取同条 `strongestCompetitorBelowFusedB` 对应的 `candidates` 行）定义
  - `δ_i := fusedB_i − fusedA_i`（下位候选 `r` 若不在 `windowCheck` 内，则 `δ_r = 0`；`e` 自身 `δ_e` 直接取条目 `delta`）；
  - `margin_A(e,r) := (fusedB_e − δ_e) − (fusedB_r − δ_r)`；
  - `flipRisk(e,r) := margin_A(e,r) ≤ 1e-12`（并列容差 `TIE_EPS`，按「可能翻转」计，见 R5c；与锚 `formulas.flipRisk` 同源同值）；
  - **无下位竞争者**（`strongestCompetitorBelowFusedB` 为 null）⇒ `gap` / `δ_r` / `margin_A` 记 `null`（**不造 0**）、`flipRisk = false`。
  判据只用锚内已有字段，**禁反解** `i_r`（不引入新的推导量）。
- **R2 保守性定理（现行窗口是特例）**：因 `δ_e ≤ window_e = 0.30×(1−w_e)` 且 `δ_r ≥ 0`，故 `gap > window_e ⇒ margin_A > 0`。断言两条：
  - **正向**：`gapExceedsWindow == true` **或** `gap > window_e + 1e-12` 的条目 `flipRisk == false`（逐条断言；`1e-12` 与实现同容差，见 §8）；
  - **逆否**：所有 `flipRisk == true` 的条目必须 `gap ≤ window`（即窗口从不漏报）。
  这两条合起来说明：**窗口判据可以与精确判据并存，但窗口说了「可能」不等于「会」**。
- **R3 假阳性刻画**：`gap ≤ window` 且 `flipRisk == false` ⟺ `δ_r > δ_e − gap`。逐条输出 `(q, 事件, 竞争者, δ_e, δ_r, gap, margin_A, 归因桶)`，归因桶取两类之一：
  - `COMPETITOR-SYNKS`（竞争者 δ_r > 0 且把它托住了）；
  - `NO-DISPLACEMENT`（δ_e == 0，事件本身没位移）。
  卡 32 的 4 条必须分别落入 3×`COMPETITOR-SYNKS` + 1×`NO-DISPLACEMENT`，**桶计数落锚**。
- **R4 标记 vs 位移（事件计数口径）**：锚内必须分别给出 `markedCount`（`windowCheck.entries` 条数）与 `displacedCount`（`delta > 0` 条数），并断言 `displacedCount ≤ markedCount`；同时落 `displacedEvents` 明细（`delta`、`weight`、`variantKey`）与 `markedButNotDisplaced` 明细（4 条的身份 + `tier` + `weight` + `delta=0` 的证据字段）。
  **判据的触发量是 `displacedCount`，不是 `markedCount`**（板规 26 的同类补丁：先分清「发生了几次」指的是哪一次）。
- **R5 判据必须能取反（正/负对照）**：用**构造数据**（不读锚、不跑检索）给出 3 组必须成立的对照，逐组断言：
  - (a) `δ_e > 0, δ_r = 0, gap ≤ δ_e` ⇒ `flipRisk == true`（判据能报翻转）；
  - (b) `δ_r == δ_e` ⇒ `margin_A == gap > 0` ⇒ `flipRisk == false`（耦合托住）；
  - (c) `margin_A == 0`（并列）⇒ `flipRisk == true`，并落 `tieBreak = "EQUAL-MARGIN-COUNTS-AS-RISK"`。
- **R6 锚与守卫**：锚 `docs/rag-quality/near-dup-window-coupling-v1.json`（**LF**，写模式 `-Dnear.dup.window.coupling.write=true`，默认逐字节复算、剔除顶层 `runtime`）+ `.md`（≤40 行）；守卫 `NearDupWindowCouplingGuardTest`（零外呼）钉死：R1 逐事件复算、R2 两向断言、R3 桶计数与归因、R4 计数口径、R5 三组对照、上游锚 SHA、**禁出现「建议改窗口/阈值」类字段或措辞**。
- **R7 边界**：`src/main` 零 diff；`pom.xml`、CI YAML 零改；**既有锚全量零字节变动**（尤其 `near-dup-band-entry-v1.json`）；`NearDupBandEntryIT` / `NearDupBandEntryGuardTest` / `NearDupS1CropCorpus` / `NearDupTierFillAnalysisTest` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*` **不得修改**（只读）。
- **R8 判据修订记**：本卡自己就是「判据卡」，若 R1–R5 中任何一条在实现时发现不可满足（例如锚里根本没有能定位「下位竞争者身份」的字段），**停手上报**并在 proposal §8 记，不得手改锚、不得反解、不得放宽断言换绿。

## §4 交付物

- 新分析测试：`src/test/java/com/slz/crm/quality/NearDupWindowCouplingAnalysisTest.java`（写模式生成锚；默认逐字节复算）
- 新守卫：`src/test/java/com/slz/crm/quality/NearDupWindowCouplingGuardTest.java`
- 结论锚：`docs/rag-quality/near-dup-window-coupling-v1.json` + `.md`（≤40 行）

## §5 成本

**0 次外呼**（不设 opt-in，不碰真跑类）；0 迁移、0 契约、0 权限、0 新依赖。CPU 级：读一个 226 KB 的锚 + 算术。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1 卡 32 锚只读**：它是被 `NearDupBandEntryGuardTest` 逐字节钉死的成品；本卡只能**读它**，任何「顺手修正」都是破坏。
- **D2 用实测位移，不反解**：`δ` 直接取 `fusedB − fusedA`（或条目 `delta`），**不**从 `cos` 或 `i_r` 反推——反解会引入第二个误差源。
- **D3 解释不通就记缺口**：`delta=0` 的 4 条若在锚内**找不到**能解释的字段（例如图像路 rerank 分未落锚），如实记 `openQuestion + 缺失字段`，判本项 `INCONCLUSIVE`；**不许**新跑检索去补（那要外呼），也不许编因果。
- **D4 只改表述与断言，不改常量**：`window = 0.30×(1−w)` 与三档权重、`NEAR_DUPLICATE_FLOOR` **一个不动**；本卡产出的是「判据该怎么算」，不是「阈值该定多少」。
- **D5 判据不可取反即判不合格**：R5 少一组对照，或删掉某一组让测试变绿，判不合格（板规 22）。

## §7 与卡 26/27/28/31/32 的关系

- 卡 26 建装置 → 卡 27 归因 `COUNTER-SUPPRESSED` → 卡 28 零外呼 S0 判 `NO-RANK-CHANGE`（无窗内共现）→ 卡 31 30 外呼但质量型全落带外 → 卡 32 几何裁切 4/6 进带，**首次降权发生**并按板规 26 判 `NO-RANK-CHANGE`。
- 卡 32 的 `windowCheck` 是**为 NO-RANK-CHANGE 找理由**的近似式，它自己就承认「扫描不到同步下沉」。本卡把那条近似式升级为精确判据，并把「事件发生次数」的两种含义拆开——**这是卡 32 遗留的两个洞，也是它自己写进 md 的边界**。

## §8 判据修订记

- **2026-10-26（复核后 fix1，朝更保守方向消歧）**：复核 MINOR-3 指出「`flipRisk ≤ 0`（三处文本）」与「实现 `≤ 1e-12`」不一致。按板规 12「事后消歧只能朝更保守方向」，取**并列按风险计**：`flipRisk := margin_A ≤ 1e-12`，并把正向断言同步为 `gap > window_e + 1e-12 ⇒ flipRisk = false`（否则在 `margin_A ∈ (0, 1e-12]` 时正向断言会与实现矛盾）。三处文本（proposal R1 / 锚 `criteria.R1` / 锚 `formulas.flipRisk`）与 md 已统一为 `1e-12`；同日补记「无下位竞争者 ⇒ `gap`/`δ_r`/`margin_A` 记 null、`flipRisk = false`」，消除 R1 原文「`δ_r = 0`」与锚内 `events[9]` 记 `null` 的表述冲突（`null` 更诚实：无对手时 `margin_A` 无定义）。
- 另：`r2.forwardHolds` / `r2.contrapositiveHolds` 由字面 `true` 改为**实数推导**（`events.allMatch(...)`），并新增 `r2.tieEps`；守卫改为断言 `forwardChecked` 与推导值相等 + `forwardHolds` 为真（原字段无人读，属「硬写恒真」隐患）。
