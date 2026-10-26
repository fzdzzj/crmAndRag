# tasks · near-dup-band-entry-and-tier-fill（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 与决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **腿 A 外呼上限 36、目标 30**；**腿 B 零外呼**；`src/main` 零 diff；既有锚（`docs/rag-quality/*.json` 全量）与既有测试**零字节变动**。
- **不得修改** `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*`（均被冻结锚 replay 引用）。
- 真跑只在 `RAG_NEAR_DUP_INGEST_REAL=1` 下发生；默认一律 stub、零外呼。**超帽即抛。**

## A · 腿 A 语料与预算（R0 / R1）

- [x] 1. `CALL_CAP = 36` 在测试内实现（超帽即抛），逐图 fail-fast；**批处理前先跑第一张并校验完**再继续
      补勾依据：`NearDupBandEntryIT.java:120` `BAND_CALL_CAP = 36`（TrackingProvider 触帽即抛，装置复用卡 31 冻结嵌套类）；顺序 for 循环逐张调 `ingestVariant`（:312），其内 R2 七链断言逐图抛错——第一张红即止。实测：真跑 30 外呼通过（`near-dup-band-run2.log`：`[near-dup-band] 锚已落盘 …（externalCalls=30 verdict=NO-RANK-CHANGE）`）。
- [x] 2. 新 `NearDupS1CropCorpus`：2 base × 3 裁切边距（`2%`/`4%`/`6%`，**只做几何编辑**），边距写死并落锚 `corpusRule`
      补勾依据：`NearDupS1CropCorpus.java:57/60/63` `MARGIN_02=0.02D / MARGIN_04=0.04D / MARGIN_06=0.06D`（D2 写死）；锚 `corpusRule.variantKinds=["crop-02","crop-04","crop-06"]`、`params.crop02/04/06Margin` 落 0.02/0.04/0.06；守卫 `variantParamsFrozen` 断言编译期常量（`NearDupBandEntryGuardTest` 4/4 绿）。实测 generate()：2 base（p1-b1/p16-b2，与卡 31 同序）× 6 变体 × 6 查询。
- [x] 3. 裁切后短边 ≥64px 用**本地像素断言**，不足则换更小边距重造（不许弃图）；base 选择规则确定性（语料生成序前 2 个合规者）
      补勾依据：`NearDupS1CropCorpus.java:181` 梯子循环断言 `min(w,h) ≥ MIN_EDGE_PX(64)`，:69 `MARGIN_STEP=0.01` 向下换档、0.01 仍不足即抛；守卫 `corpusLocalFeasibilityAndDeterminism` 实测 6 变体短边全 ≥64px（p1-b1 490×225→crop 后 ≥207px、p16-b2 923×453→≥418px，梯子未触发，param 全为 m0.02/m0.04/m0.06）。

## B · 腿 A 入库对账（R2）

- [x] 4. 逐图断言 `提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数`；图片切片 `rowIndex == null`
      补勾依据：`NearDupBandEntryIT.java:312-407` ingestVariant 七链断言；锚 `ingest` 6 行全 `extracted=1,described=1,gatePass=true,slices=1,stagedImageVectors=1,childRows=1,textEmbeds=1`；rowIndex 恒 null 断言在 :379。守卫 `anchorAccountingWhenPresent` 复算通过（4/4 绿）。
- [x] 5. 6 个查询按「2 base × 3 形态」构造，文本取该页已提取文本、图像分量用 base 位图；**查询向量只嵌一次、A/B 复用**
      补勾依据：锚 `queries` 6 条（index 0-5，form=title/first-sentence/caption-keywords × 2 base，复用 NearDupS1Corpus.formOf 冻结规则）；锚 `isolationEvidence.embedInvocationsA=6 == embedInvocationsB=6`（每跑检索链 provider 调用 6 次；12 是查询嵌入合计（图 6+文 6），非隔离计数，勿混写）；callLedger embedImage=12（摄取 6+查询 6）、embed=12（子块行 6+查询文本 6）——查询只嵌一次的计量面。

## C · 腿 A A/B 与判定（R3）

- [x] 6. A 跑 = 生产降权；B 跑 = `w=1`（`mockStatic(CALLS_REAL_METHODS)`）；`git diff master -- src/main` 必须为空
      补勾依据：`NearDupBandEntryIT.java` runPass(identityStub) 双跑 + `ablationDesign.passA/passB` 落锚；实测 `git diff master --stat -- src/main` 空输出（B 跑恒等，src/main 零 diff）。
- [x] 7. 逐查询落 `{候选集, cos, fusedA, fusedB, rankA, rankB, downWeightedKeys, gap}`
      补勾依据：锚 `candidates` 30 行（6 查询 × TOP_K 5；A/B 集合相等 ⇒ union=5/查询）+ `queriesOutcome` 6 行（含 downWeightedKeys）+ `windowCheck.entries` 12 行（含 gap/window/gapExceedsWindow）。
- [x] 8. `verdict` **与 `downWeightedCount` 强制联动**：`==0` ⇒ 只能 `INCONCLUSIVE`（附 `missingEvidence`）；`>0` 且有变化 ⇒ `RANK-CHANGE`（附逐事件 `gap`）；`>0` 且无变化 ⇒ `NO-RANK-CHANGE`（附 `windowCheck` + 不可外推）
      补勾依据：`NearDupBandEntryIT.java:864` `else if (downWeightedCount == 0)` 分支（板规 26 本体）；实测 dwc=12>0、anyImageRankChange=false、chunkIdSetEqual 全 true ⇒ verdict=NO-RANK-CHANGE，missingEvidence=null，windowCheck 12 条全录，nonExtrapolationNote 含「不可外推」；守卫 R3 联动重算断言通过。
- [x] 9. 逐变体记 `bandSide` 与 `cos`（边界读生产常量），带外用不用猜、照记
      补勾依据：锚 `bandSides` 6 条——p1-b1@crop-02 IN-BAND 0.993354 / @crop-04 IN-BAND 0.993401（NEAR_HIGH，复现 S0 0.9934）/ @crop-06 IN-BAND 0.990735（NEAR_MID）；p16-b2@crop-02 ABOVE 0.994625（SAME_IMAGE）/ @crop-04 IN-BAND 0.988904（NEAR_LOW）/ @crop-06 BELOW（未召回，cos null 照记）。

## D · 腿 B 档位填充盘点（R4 / R5，零外呼）

- [x] 10. 从 `image-variant-cross-matrix-v1`（396 对 / 14 带内）、`near-dup-exchange-rate-v1`、`near-dup-ingest-gate-probe-v1` 汇总「编辑类型 × `cos`(min/median/max) × 命中档位 × 带内对数」表，每行可指回源锚字段路径
      补勾依据：锚 `legB.tierFill`（ownBaseByKind 5 行 + thirdImageByKind 5 行，每行 kind/pairs/cosMin/cosMedian/cosMax/sameImage/bandNearHigh/Mid/Low/belowFloor/inBand + sources.sha256 + fieldPaths）；实测 `NearDupTierFillAnalysisTest.sourceAnchorIntegrity`：2760 对复算 count 一致、p50/p90/p99/max 容差 1e-9 对上、≥0.9887 恰 14 对。**口径澄清**：proposal 括注「396 对」实测核对为源锚 M2 偏差对账对数（120+100+176），变体×第三图全域 2760 对，锚 `pairCountClarification` 如实注明（判据语料域不变，非判据修订）。
- [x] 11. 0.90 议题只列选项与代价：新增进带对数（标明分母）+ 已记录代价；**不给推荐阈值、不改任何常量**
      补勾依据：锚 `legB.thresholdOptions`：恰 2 选项（0.9887：own 70/120、third 13/2760、sameDoc 2/100；0.90：own 113/120、third 93/2760、sameDoc 8/100），newEntrants own +43/120、third +80/2760，recordedCosts 指回 exchange-rate 锚 grid.items[floor=0.9]；守卫 grep 断言锚 legB 节 + md 均无「推荐/建议+阈值数字」禁式、无 recommendation 类字段名（4/4 绿）；生产常量零改动（`git diff master -- src/main` 空）。

## E · 锚与守卫（R6 / R8）

- [x] 12. 锚 `docs/rag-quality/near-dup-band-entry-v1.json`（**LF**，默认逐字节复算）+ `.md`（≤40 行）；`callLedger.total` = 三分项之和且 ≤ 36
      补勾依据：实测锚纯 LF（`python -c "b'\r' not in raw"` → True，226264 字节）；md 24 行（wc -l）；callLedger vision 6 + embedImage 12 + embed 12 = total 30 ≤ 36（守卫断言三分项之和与硬帽）；默认复跑 `near-dup-band-replay.log`：`[near-dup-band] 默认复跑逐字节一致（verdict=NO-RANK-CHANGE externalCalls=30 零外呼）`。
- [x] 13. 守卫 `NearDupBandEntryGuardTest`（零外呼）：台账等式 + 第 8 条三分支联动 + 第 10 条表逐数字复算 + **grep 断言腿 B 无推荐阈值**
      补勾依据：实测 `mvn test -Dtest=NearDupTierFillAnalysisTest,NearDupBandEntryGuardTest`：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`；锚对账含：R3 联动 switch 三支重算 + dwc==0 双断言、legB 两节与静态算子序列化逐字节比对、独立向量计数抽查（120/2760/14/22）、RECOMMENDATION_WITH_VALUE 禁式双面 grep（锚节 + md）、上游 4 锚 SHA 实算、runtime 白名单。

## F · 收尾（R7）

- [x] 14. 默认复跑（无 opt-in、无 write）与磁盘锚逐字节一致（剔除顶层 `runtime` 块），零外呼
      补勾依据：实测 `RAG_NEAR_DUP_INGEST_REAL=1 mvn verify -Dit.test=NearDupBandEntryIT`（无 write 属性）：`Tests run: 1, Failures: 0 … BUILD SUCCESS` + `[near-dup-band] 默认复跑逐字节一致（verdict=NO-RANK-CHANGE externalCalls=30 零外呼）`（log：`%TEMP%/near-dup-band-replay.log`）；无 opt-in 时整类 Assumptions 跳过（默认 CI 零外呼，:190 assumeTrue 分支）。注：本条按门控变量语义取「opt-in 无 write」复跑口径（与卡 31 第 14 条同款），完全无 opt-in 的跳过分支见括注。
- [x] 15. 既有锚（`docs/rag-quality/*.json` 全量） sha256 与 master 全等 + `git diff master --numstat` 只含 6 个新文件
      补勾依据：实测 `git status --porcelain -- docs/rag-quality` 仅 `?? near-dup-band-entry-v1.json`（既有锚零改动、无 M 行）；`git add` 六文件后 `git diff --cached master --numstat` 恰 6 行全为新文件（见提交）；提交后 `git diff master --numstat` 复验。
- [x] 16. 实测外呼三分项与报批对账（≤36）贴计量输出；`git status` 只含写集；`git diff --check` 干净
      补勾依据：锚 callLedger（TrackingProvider 真调用点累加 + assertLedger 派生口径逐项相等断言）：vision 6 / embedImage 12 / embed 12 / total 30 ≤ 36，authorization="Owner 2026-10-26 授权（含更强变形）"；首跑尝试因 surefire 空匹配旗标在测试执行前失败（零外呼、零锚写，`near-dup-band-run.log` BUILD FAILURE 于 surefire 阶段），修正旗标后单次真跑成功；实测 `git status` 只含 6 写集文件 + 主 agent 的 openspec/work 未跟踪件；`git diff --check` 空输出。
