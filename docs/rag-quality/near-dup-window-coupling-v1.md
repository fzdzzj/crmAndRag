# near-dup-window-coupling-v1 · 窗口是上界速筛，精确判据是 margin_A

- 卡 33（零外呼判据卡）：只读卡 32 冻结锚 `docs/rag-quality/near-dup-band-entry-v1.json` + 纯算术。
- 输入实测：`candidates` 30 行（6 查询 × TOP_K 5）、`windowCheck.entries` 12 条、`downWeightedCount` 12、`verdict` NO-RANK-CHANGE。

## 结论 1 · 窗口只能当上界速筛，精确判据是 margin_A

- 现行窗口：`window = 0.30×(1−w)`，判法 `gap > window ⇒ 不可能翻转`；它默认下位竞争者不动。
- 精确判据（耦合）：`δ_i = fusedB_i − fusedA_i`；`margin_A(e,r) = (fusedB_e − δ_e) − (fusedB_r − δ_r)`；`flipRisk = margin_A ≤ 1e-12`（并列容差 `TIE_EPS`，按风险计）。
- 保序：`δ_e ≤ window_e` 且 `δ_r ≥ 0` ⇒ `gap > window_e ⇒ margin_A > 0`；正向断言取 `gap > window_e + 1e-12 ⇒ flipRisk = false`（与实现同容差），即窗口判据是精确判据的保守特例（充分不必要）。
- 实测：12 条事件 `flipRisk = true` 的 **0 条**（逆否在真实数据上空真，非空可证性由构造对照提供）；`gapExceedsWindow = true` 或 `gap > window + 1e-12` 的 8 条全部 `flipRisk = false`（8/8 逐条，其中 q3 `@crop-04` 无下位竞争者，故 `gap`/`δ_r`/`margin_A` 记 `null`、不造 0）。
- `gap ≤ window` 的 4 条一条都没翻 ⇒ 窗口说「可能」不等于「会」。

## 结论 2 · marked ≠ displaced

- `markedCount = 12`（被标记 = `entries` 条数）；`displacedCount = 8`（分数真的动 = `delta > 0`）；`triggerCount = displacedCount`。
- 判据的触发量只能是 `displacedCount`：`downWeightedCount = 12` 记的是「被标记」12 次，其中 4 条 `fusedA == fusedB` 分数没动。
- 4 条 `delta = 0` 的身份：q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`（`tier` 均为 NEAR_DUPLICATE，`downWeighted = true`）。
- 其成因 **INCONCLUSIVE**（决策 D3）：锚内没有「降权是否真的应用」的字段，不新跑检索、不编因果，缺口记在锚 `openQuestions.OQ1`。

## 4 条 naive 假阳性（`gap ≤ window` 却未翻）归因

| 查询 | 事件 | δ_e | δ_r | gap | margin_A | 归因桶 |
| --- | --- | --- | --- | --- | --- | --- |
| q0 | `p1-b1@crop-02` | 0.074216 | 0.075000 | 0.005159 | 0.005943 | COMPETITOR-SYNKS |
| q1 | `p1-b1@crop-02` | 0.044216 | 0.045000 | 0.017033 | 0.017817 | COMPETITOR-SYNKS |
| q2 | `p1-b1@crop-02` | 0.074216 | 0.075000 | 0.000943 | 0.001727 | COMPETITOR-SYNKS |
| q5 | `p16-b2@crop-04` | 0.000000 | 0.000000 | 0.043989 | 0.043989 | NO-DISPLACEMENT |

- 3×`COMPETITOR-SYNKS`：q0/q1/q2 的 `@crop-02`，下位竞争者都是同查询的 `@crop-04`，`δ_r`(0.075 / 0.045 / 0.075) 大于该事件的 `δ_e`，两边同步下沉 ⇒ 相对次序不变。
- 1×`NO-DISPLACEMENT`：q5 `p16-b2@crop-04` 自身 `δ_e = 0`，根本没有位移可谈。

## 边界

- **不改任何阈值 / 窗口常量**：`0.30×(1−w)`、三档权重 0.75/0.60/0.50 原样引用卡 32，一个不动；本卡只定「判据怎么算」。
- 零外呼：无 `*_REAL`、无 Spring 上下文、无 provider；上游 4 项锚 SHA-256 实算已核对（锚 `upstreamAnchors.sha256`）。
- 细节锚：`docs/rag-quality/near-dup-window-coupling-v1.json`（默认按本次全量重算逐字节比对，剔除顶层 `runtime`）。
