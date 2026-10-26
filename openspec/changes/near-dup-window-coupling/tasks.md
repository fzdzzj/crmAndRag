# tasks · near-dup-window-coupling（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 与决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。
> 本卡行号以**符号**（类名 / 方法名 / 锚字段名）为主：fix1 后行号漂移，符号可 grep 且不会过期（板规 28）。

## §0 · 硬约束

- **零外呼**：本卡不设任何 `*_REAL` opt-in，不 instance 任何真 provider；全部输入是 `docs/rag-quality/near-dup-band-entry-v1.json`（只读）+ 算术。
- `src/main` 零 diff；`pom.xml` / `.github/**` 零改；**既有锚全量零字节变动**。
- **不得修改**：`NearDupBandEntryIT` / `NearDupBandEntryGuardTest` / `NearDupS1CropCorpus` / `NearDupTierFillAnalysisTest` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*`（全部只读引用）。
- 锚写模式 `-Dnear.dup.window.coupling.write=true`；默认（无该属性）逐字节复算、零外呼。

## A · 精确判据实现（R1 / R2）

- [x] 1. 新 `NearDupWindowCouplingAnalysisTest`：只读加载卡 32 锚（`candidates` 30 行 + `windowCheck.entries` 12 条），**不启动 Spring 上下文、不引用任何 provider**
      补勾依据：类 import 只有 Jackson / `java.nio.file` / `java.util` / JUnit，无 Spring / HTTP / Testcontainers；`mvn -B -ntp test -Dtest=NearDupWindowCouplingAnalysisTest` → `Tests run: 1, Failures: 0`，耗时 **0.467 s**（无容器无网络）；`grep "_REAL"` 3 处全在「本类不设任何 *_REAL」声明句内，真实读取 0 处。
- [x] 2. 逐 12 条事件落 `margin_A` 与 `flipRisk`（`margin_A ≤ 0` 记风险），全部写入锚 `events`
      补勾依据：锚 `events` 12 条，每条含 `queryIndex / variantKey / band / weight / deltaE / competitorChunkId / deltaR / gap / marginA / flipRisk`；`marginA` 由 `(fusedB − δ_e) − (fusedB_r − δ_r)` 现算（`flipRisk = marginA ≤ TIE_EPS`，`TIE_EPS = 1e-12`）；实测 `flipRisk=true` **0 条**；`q3 @crop-04` 无下位竞争者 ⇒ `gap`/`deltaR`/`marginA` 全记 `null`（不造 0）。
- [x] 3. R2 正向断言：12 条中 `gapExceedsWindow == true` 者，`flipRisk` 必须为 `false`
      补勾依据：`NearDupWindowCouplingAnalysisTest#analyzesWindowCoupling` 内 for 循环逐条 `assertFalse(event.flipRisk())`（无抽样）；条件为 `gapExceedsWindow || gap > window + TIE_EPS`；实测 `forwardChecked = 8`（含 1 条无竞争者的 `gapExceedsWindow=true`），8/8 全 `flipRisk = false`；锚 `r2.forwardChecked = 8`、`r2.forwardHolds = true`（由 `events.allMatch` 推导，非硬写）。
- [x] 4. R2 逆否断言：`flipRisk == true` 的条目必须满足 `gap ≤ window`（窗口从不漏报）
      补勾依据：同循环内 `if (event.flipRisk()) { assertTrue(gap != null && gap <= window) }`；真实数据命中 **0 条**（空真），锚 `r2.contrapositiveChecked = 0` + `r2.contrapositiveNote` 已注明 VACUOUS；非空可证性由 `controls` 的 (a)/(c) 两组承担（守卫 `windowIsOnlyAnUpperBoundFilter` 断言恰 2 组）。

## B · 假阳性归因（R3）

- [x] 5. 输出「naive 报风险而精确不报」的全部条目，逐条给 `(δ_e, δ_r, gap, margin_A)` 并归因到两桶之一：`COMPETITOR-SYNKS`（δ_r > 0 托住）或 `NO-DISPLACEMENT`（δ_e == 0）
      补勾依据：锚 `falsePositives.entries` 4 条 + `bucketCounts` = `{COMPETITOR-SYNKS: 3, NO-DISPLACEMENT: 1}`；实测 q0 `@crop-02`(δ_e 0.074216 / δ_r 0.075 / gap 0.005159 / m 0.005943)、q1 `@crop-02`(0.044216/0.045/0.017033/0.017817)、q2 `@crop-02`(0.074216/0.075/0.000943/0.001727) 归 SYNKS；q5 `@crop-04`(δ_e 0 / δ_r 0 / gap 0.043989 / m 0.043989) 归 NO-DISPLACEMENT。运行日志：`falsePositives=4 (COMPETITOR-SYNKS=3, NO-DISPLACEMENT=1)`。
- [x] 6. 断言归因判据本身可取反：对每条假阳性，验证 `δ_r > δ_e − gap`（`COMPETITOR-SYNKS`）或 `δ_e == 0`（`NO-DISPLACEMENT`）至少一条成立，且**两桶判据互不重叠**
      补勾依据：`analysis` 与 `guard` 各自的 `attribute()` 实现在 `synks && noDisplacement`（两桶重叠）与 neither（第三类）两种情形都 `throw IllegalStateException`；实测 4/4 `overlap=false, neither=false`；桶计数 `3+1 = falsePositives.count` 相等断言落守卫。

## C · 计数口径（R4）

- [x] 7. 落 `counts.markedCount`（= `windowCheck.entries` 条数，实测 12）与 `counts.displacedCount`（`delta > 0` 条数，实测 8），断言 `displacedCount ≤ markedCount`
      补勾依据：运行日志 `events=12 marked=12 displaced=8`；锚 `counts.markedCount=12`、`displacedCount=8`、`triggerCount=8`；分析类 `assertTrue(displaced <= marked)` 通过。
- [x] 8. 落 `markedButNotDisplaced` 明细：4 条的 `queryIndex / variantKey / tier / weight / delta(0) / fusedA == fusedB` 证据字段
      补勾依据：锚 `markedButNotDisplaced` 4 条 = q1 `p1-b1@crop-06`、q3/q4/q5 `p16-b2@crop-04`，逐条带 `queryIndex + chunkId + variantKey + tier(NEAR_DUPLICATE) + downWeighted(true) + weight + delta(0) + fusedA + fusedB`，且 `fusedA == fusedB` 逐条成立；守卫逐字段与 `candidates` 行比对通过。
- [x] 9. 判据代码里**禁止**引用 `markedCount` 作为触发量：断言只由 `displacedCount`/逐事件 `δ` 推导（板规 26 同类补丁）
      补勾依据：锚 `counts.triggerCount = displacedCount`（=8）；全仓 `markedCount` 仅出现在落锚与提示文案，无断言拿它当触发量（`grep markedCount` 命中 4 处，均为字段写入/README 文案）；判据 `flipRisk` 只由逐事件 `margin_A`（含 `δ_e`）推出。

## D · 正负对照（R5，不依赖外部数据）

- [x] 10. 三组构造对照逐组断言，全部落锚 `controls`
      补勾依据：锚 `controls` 3 组：(a) `δ_e=0.05, δ_r=0, gap=0.03` ⇒ `marginA=-0.02`、`flipRisk=true`；(b) `δ_e=δ_r=0.05, gap=0.03` ⇒ `marginA=0.03`、`flipRisk=false` 且断言 `margin_A == gap`；(c) `δ_e−δ_r == gap` ⇒ `marginA=-3.47e-18`、`flipRisk=true` + `tieBreak="EQUAL-MARGIN-COUNTS-AS-RISK"`；守卫 `controlsAreFalsifiable` 断言 `controls.size()==3` 与逐组复算，`default -> throw AssertionError`（删组即红）。

## E · 锚与守卫（R6）

- [x] 11. 锚 `docs/rag-quality/near-dup-window-coupling-v1.json`：**纯 LF**、顶层含 `events` / `falsePositives` / `counts` / `markedButNotDisplaced` / `controls` / `upstreamAnchors.sha256` / `runtime`
      补勾依据：实测锚 **23812 字节 / 625 行 / `CR=0` / 末尾单 LF**；`python -c "...raw.count(b'\r')"` → `CR=0`；顶层键 `criteria / formulas / r2 / events / falsePositives / counts / displacedEvents / markedButNotDisplaced / controls / upstreamAnchors / runtime / openQuestions` 全在。
- [x] 12. 默认复跑（无 write 属性）与磁盘锚**逐字节一致**（剔除顶层 `runtime`），零外呼，打印一行 `[near-dup-window] 默认复跑逐字节一致（...）`
      补勾依据：`mvn -B -ntp test -Dtest=NearDupWindowCoupling*Test` → `[near-dup-window] 默认复跑逐字节一致（剔除顶层 runtime）：…near-dup-window-coupling-v1.json` + `（md）` 两行均打印，`Tests run: 7, Failures: 0`；`runtime.externalCalls = 0`。
- [x] 13. `docs/rag-quality/near-dup-window-coupling-v1.md`（**≤40 行**）：写清「窗口是上界速筛、精确判据是 margin_A」与「marked ≠ displaced」两条结论，并声明**不改任何阈值/窗口常量**
      补勾依据：md 实测 **37 行 / 3354 字节 / CR=0**；含「窗口只能当上界速筛」「marked ≠ displaced」两节 + 「不改任何阈值/窗口常量」声明。
- [x] 14. 守卫 `NearDupWindowCouplingGuardTest`（零外呼）钉死：R1 逐事件复算、R2 两向、R3 桶计数与归因、R4 计数与触发量、R5 三组对照、上游锚 4 项 SHA 实算、md ≤40 行
      补勾依据：守卫 6 个 `@Test` 全绿（`Tests run: 6, Failures: 0`），含 `recomputesCouplingCriterionForEveryEvent` / `windowIsOnlyAnUpperBoundFilter` / `attributesFalsePositivesToTwoExclusiveBuckets` / `countsSeparateMarkedFromDisplaced` / `controlsAreFalsifiable` / `upstreamAnchorsAndReportBoundaries`（后者实算 4 项上游 SHA 并与卡 32 锚 `sourceAnchors` 交叉比对）。
- [x] 15. 守卫断言锚与 md **不出现**「建议改为 / 推荐阈值 / 应下调」类措辞与 `recommend*` 字段名（grep 双面断言）
      补勾依据：守卫内 `BANNED_PHRASES` 5 词 + 正则 `"recommend[a-zA-Z]*"\s*:` 对锚与 md 双面 `assertFalse`；实测命中 0（锚内仅 `thresholdDisposition`「本锚**不主张**任何阈值取值」等否定句）。

## F · 收尾（R7 / R8）

- [x] 16. 边界实测：`git diff master --stat -- src/main pom.xml .github` 空；既有锚 `git status --porcelain -- docs/rag-quality` 只含 2 个新文件（无其他既有锚 `M`）；8 个禁改测试类零字节变动；`git diff --check` 干净
      补勾依据：`git diff master --name-status` = 恰 4 行全 `A`（锚 json/md + 2 新测试类）；`git diff master --stat -- src/main pom.xml .github` **空输出**；`git status --porcelain -- docs/rag-quality` 只列本卡 2 个新文件（既有锚无 `M`）；6 个点名禁改类 + `NearDupIngestGateProbe*` / `NearDupIngestS1*` 的 `git diff master --name-only` **空**；`git diff --check` **空**。
      **口径修正（复核 MINOR-2）**：`git status` 会额外列出 2 条**未跟踪的卡面输入**（`openspec/changes/near-dup-window-coupling/` 与 `work/task-card-near-dup-window-coupling.md`），它们是 owner 派发的卡面而非实现写集 ⇒ 正确口径是「**已跟踪**改动只含本卡写集」。
- [-] 17. 若 R1–R5 任一条不可满足或归因出现第三类 ⇒ 在 `proposal.md §8` 记修订记并判该项 `INCONCLUSIVE`
      补勾依据：**未触发**——R1–R5 全部可满足、归因恰为 3+1 无第三类。`proposal §8` 已另记一条**复核后 fix1 的消歧**（`flipRisk` 容差统一为 `1e-12`、无竞争者记 `null`、`r2.forwardHolds` 改实数推导），那是「折中口径显式化」而非判据作废，故本条记 `[-]`。
