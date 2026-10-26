# 任务卡 · near-dup-window-coupling（卡 33 · B 档 · 零外呼判据卡）

## §1 目标

卡 32 第一次让降权真的发生（`downWeightedCount=12`），但它给的窗口判据有**已实测的缺陷**：`gap > 0.30×(1−w)` 默认竞争者不动，而实测 4 条 `gap ≤ window` 一条没翻——因为下位竞争者也被降权，两边一起下沉。

本卡把近似式换成**精确判据**，并把「事件发生次数」的两种含义拆开：

- **腿 A**：逐事件算 `margin_A = (fusedB_e − δ_e) − (fusedB_r − δ_r)`，`margin_A ≤ 0` 才算「可能翻转」；证明窗口判据是它的**保守特例**（`gap > window ⇒ margin_A > 0`），并逐条解释卡 32 的 4 条假阳性（3 条竞争者同步下沉 + 1 条事件本身无位移）。
- **腿 B**：`markedCount`（被标记 12）与 `displacedCount`（分数真的动 8）必须分开落锚；判据的触发量是 `displacedCount`。

## §2 背景（三条必须记住的教训）

1. **卡 32 的窗口判据是「找理由」的近似式**：它算 `gap` 用的是未降权跑（`fusedB`）里的竞争者分数，天然假设竞争者不动。它自己 md 里写了「静态 gap 不反映同步下沉」，但没升级。
2. **板规 26 的同类病换了个位置**：`downWeightedCount=12` 看着像「降权发生了 12 次」，实际只有 8 次分数真的动了（`delta==0` 的 4 条：q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`）。**先把「发生」定义清楚，再谈影响。**
3. **卡 32 锚是成品**：被 `NearDupBandEntryGuardTest` 逐字节钉死。本卡**只读**它，任何「顺手修正」都会把别人卡的守卫打红。

## §3 写集（**只许写这 4 个文件**，无其他写入者）

| 路径 | 动作 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupWindowCouplingAnalysisTest.java` | 新增：读锚 + 复算 + 写锚（写模式） |
| `src/test/java/com/slz/crm/quality/NearDupWindowCouplingGuardTest.java` | 新增：守卫（R1–R5 + 上游 SHA + 措辞 grep） |
| `docs/rag-quality/near-dup-window-coupling-v1.json` | 新增（写模式生成，LF） |
| `docs/rag-quality/near-dup-window-coupling-v1.md` | 新增（≤40 行） |

## §4 禁改面（越界即判死）

- `src/main/**` 一行不改；`pom.xml`、`.github/**` 不改；不新增依赖。
- **既有锚全量零字节变动**，尤其 `near-dup-band-entry-v1.json`（卡 32，只读输入）。
- **既有测试不得修改**：`NearDupBandEntryIT`、`NearDupBandEntryGuardTest`、`NearDupS1CropCorpus`、`NearDupTierFillAnalysisTest`、`NearDuplicateCorpus`、`NearDupS1Corpus`、`NearDupIngestGateProbe*`、`NearDupIngestS1*`。

## §5 成本铁律

- **零外呼**：不设 `RAG_*_REAL` 任何开关、不启动 Spring 上下文、不碰真 provider。跑一次 ≈ 读 226 KB JSON + 算术，秒级。
- 不许为了「解释 delta=0」去跑检索（那要外呼）。解释不出来就按 D3 记缺口。

## §6 验收（摘要）

1. 12 条事件逐条 `margin_A` + `flipRisk`；`gapExceedsWindow ⇒ !flipRisk`、`flipRisk ⇒ gap ≤ window` 两向都断言。
2. 4 条假阳性 = 3×`COMPETITOR-SYNKS` + 1×`NO-DISPLACEMENT`，逐条带 `(δ_e, δ_r, gap, margin_A)`。
3. `markedCount=12` / `displacedCount=8` 分开落锚，4 条无位移候选**带身份**（queryIndex+chunkId+variantKey）。
4. 三组构造对照（必翻 / 耦合托住 / 并列）逐组断言，判据可取反。
5. 锚 LF、默认复跑逐字节一致、上游锚 SHA 实算、md ≤40 行、无「建议改阈值」措辞。

## §7 停手条件

- R2 逆否断言失败（`flipRisk` 却 `gap > window`）⇒ **停手**：说明窗口判据会漏报，属判据方向错误，记 proposal §8。
- 归因出现第三类（既非 `COMPETITOR-SYNKS` 也非 `NO-DISPLACEMENT`）⇒ 停手上报，不得硬塞桶。
- `delta == 0` 的 4 条在锚内找不到可复算的解释字段 ⇒ 按 D3 记 `openQuestion + 缺失字段`，该项判 `INCONCLUSIVE`，**不许**编因果或新跑检索。
- 需要改卡 32 锚才能让断言过 ⇒ 判死：那说明本卡判据设计错了。

## §8 回报（≤300 字）

提交 SHA / 文件数 / `markedCount` / `displacedCount` / 12 条中 `flipRisk=true` 的条数 / 4 条假阳性归因桶计数 / 三组对照结果 / 上游锚 SHA 是否实算一致 / 未解决项 / 待决策项。

**「无翻转风险」必须同回合附 `displacedCount` 与按桶归因；「判据可取反」必须附三组对照的实测值。**

## §9 细节留档位

执行者在此节追加：锚加载与定位算法（含并列 `tieBreak` 实测）、12 条事件表、4 条假阳性的逐字段证据、`delta==0` 的解释或缺口记录、三组对照的输入输出、abort/异常记录、任何偏离 proposal 的选择与理由。

### 卡 33 执行留档（2026-10-26，feature/near-dup-window-coupling）

- **输入**：卡 32 锚（`near-dup-band-entry-v1.json`，226264 字节）**只读**；`candidates` 30 行、`windowCheck.entries` 12 条、`delta > 0` 8 条、`verdict=NO-RANK-CHANGE`。
- **判据实现**：`δ_i = fusedB_i − fusedA_i`；竞争者按同查询 `fusedB` **精确匹配**（并列取 `rankB` 最小并留 `tieBreak` 位）；`r` 未降权或不在 `windowCheck` ⇒ `δ_r = 0`；**无下位竞争者 ⇒ `gap`/`deltaR`/`marginA` 记 `null`**；`flipRisk = margin_A ≤ 1e-12`（并列按风险计）。
- **12 条实测**：`flipRisk=true` **0 条**；`gapExceedsWindow || gap > window + 1e-12` 的 **8 条**全部 `flipRisk=false`。
- **假阳性 4 条归因**：3×`COMPETITOR-SYNKS`（q0/q1/q2 `@crop-02`，下位竞争者均为同查询 `@crop-04`，`δ_r` 分别 0.075/0.045/0.075 > 该事件 `δ_e`，间距反而变宽）+ 1×`NO-DISPLACEMENT`（q5 `@crop-04`，`δ_e = 0`）。
- **计数**：`markedCount = 12`、`displacedCount = 8`、`triggerCount = displacedCount`；4 条无位移候选带身份（q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`，`tier` 均 NEAR_DUPLICATE、`downWeighted = true`、`fusedA == fusedB`）。
- **OQ1（D3 缺口，本卡未解）**：这 4 条为何「被标记降权却分数未动」——锚内没有「降权是否真的施加」的字段，按 D3 **不新跑检索、不编因果**，缺口记在锚 `openQuestions.OQ1`（候选解释：图像路该候选重排分为 0，故 `0.30×(1−w)×0 = 0`，**未经实测，不得引用**）。
- **实测成本**：零外呼；write 模式 1/1（0.467 s）、默认复跑 7/7（0.533 s + 0.033 s）+ 逐字节一致；锚 23812 字节 / 625 行 / `CR=0`；md 37 行 / `CR=0`。
- **复核后 fix1（2026-10-26，主 agent 代笔）**：① `flipRisk` 容差三处文本（proposal R1 / 锚 `criteria.R1` / `formulas.flipRisk`）与实现统一为 `1e-12`，正向断言随之改为 `gap > window + 1e-12`；② 补记「无竞争者 ⇒ 记 null」，消除 R1 原文「`δ_r = 0`」与 `events[9]` 记 null 的冲突；③ `r2.forwardHolds` / `contrapositiveHolds` 从字面 `true` 改为 `events.allMatch(...)` 实数推导，新增 `r2.tieEps`，守卫改断「`forwardChecked` 与推导值相等」+「两条文本同容差」+「不得残留 `≤ 0；`」；④ 重生成锚后默认复跑仍逐字节一致、7/7 绿。
