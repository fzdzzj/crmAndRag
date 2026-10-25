# tasks · near-dup-ingest-s1-window-cooccurrence（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 与决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **外呼上限 36，目标 30**；`src/main` 零 diff；既有 8 锚与既有测试**零字节变动**；`NearDuplicateCorpus` / `NearDupIngestGateProbeIT` / `NearDupIngestGateProbeGuardTest` 不得修改。
- 真跑只在 `RAG_NEAR_DUP_INGEST_REAL=1` 下发生；默认复跑一律 stub、零外呼。**超帽即抛，不许吞异常继续**。

## A · 语料与预算（R0 / R1）

- [x] 1. `S1_CALL_CAP = 36` 在测试内实现（超帽即抛），并给出逐图 fail-fast：每张变体入库后立即跑第 4 条的等式，不成立即 abort 并把已花调用写入锚
      补勾依据：`NearDupIngestS1IT.java:124`（`S1_CALL_CAP = 36`）与 `:1865-1866`（`guardBudget()` 在真调用点触帽即抛、不吞）；逐图 fail-fast 在 `ingestVariant` 七链断言（`:502` 过闸、`:525` 文本embed、`:532` rowIndex）。真跑 `RAG_NEAR_DUP_INGEST_REAL=1 mvn failsafe:integration-test failsafe:verify -Dit.test=NearDupIngestS1IT -Dnear.dup.ingest.s1.write=true`（日志 %TEMP%/s1-write1.log）：`Tests run: 1, Failures: 0` + `[near-dup-s1] 锚已落盘 … externalCalls=30`——30<36 未触帽，6 变体全过零 abort；abort 落锚路径为 `writeAbortAnchor`（写锚 try 内 catch Throwable 落 s1Status=ABORTED 后原样重抛）。
- [x] 2. 新 builder `NearDupS1Corpus`：2 base × 3 变体（`jpeg-95` / `jpeg-85` / `scale-95`），变体参数写死并落锚 `corpusRule`
      补勾依据：`NearDupS1Corpus.java`（`KINDS = List.of(KIND_JPEG_95, KIND_JPEG_85, KIND_SCALE_95)`、`JPEG_95_QUALITY=0.95F`、`JPEG_85_QUALITY=0.85F`、`SCALE_95_FACTOR=0.95D` 均编译期常量）；锚 `near-dup-ingest-s1-v1.json:29` `corpusRule.params {jpeg95Quality:0.95, jpeg85Quality:0.85, scale95Factor:0.95}` + `scaleLadder [0.95,0.96,0.97,0.98]`；守卫 `NearDupIngestS1GuardTest#variantParamsFrozen` 实测绿（%TEMP%/s1-guard2.log `Tests run: 5, Failures: 0`）。
- [x] 3. base 选择规则确定性（语料生成序前 2 个合规者），且沿用纯色/低色数排除口径；短边 ≥64px 用**本地像素断言**，不足换参数重造而非弃图
      补勾依据：`NearDupS1Corpus.generate()` 按生成序过滤 `docKey==nist-sp800-101r1 ∧ min(w,h)≥64` 取前 2（纯色/低色数排除由 `NearDuplicateCorpus.generate()` 的 ExcludedBase 口径内生效，本类不另立）；本地断言 `assertMinEdge` + scale 减缩梯子换参重造（走完即抛停手）。实测选定 `nist-sp800-101r1-p1-b1`（490×225，页 1）与 `nist-sp800-101r1-p16-b2`（923×453，页 16），锚 `bases`（json:43-55）；守卫 `corpusLocalFeasibilityAndDeterminism` 双生成逐字段一致（含 png SHA）实测绿。

## B · 入库对账（R2）

- [x] 4. 逐图断言 `提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数`
      补勾依据：`NearDupIngestS1IT.ingestVariant`（`:499-532` 七链逐步断言）；真跑 6/6 全过——锚 `ingest` 6 行逐行 `extracted=1, described=1, gatePass=true, slices=1, stagedImageVectors=1, textEmbeds=childRows=1`（json:99-175，textEmbeds 见 :109/:126/:143 等）；写锚模式 `assertLedger`（`:576`）断言 provider 实测计数与派生口径逐项相等后 BUILD SUCCESS。
- [x] 5. 图片切片 `rowIndex == null`；**分母一律取实测子块行数**，锚内禁出现推导量
      补勾依据：`NearDupIngestS1IT.java:532`（`child.getRowIndex() == null` 逐行断言，卡 27 R4）；分母双侧实测对账 `:524-527`（`children.size()` 生产返回行 vs `provider.embedCalls` 真调用点计数，两侧都非推导）；锚 `ingest[].textEmbeds == ingest[].childRows` 6/6，守卫 `anchorAccountingWhenPresent` 重算一致实测绿。
- [x] 6. 6 个查询按「2 base × 3 形态」构造，文本取该页已提取文本、图像分量用 base 位图（不新增文本外呼）
      补勾依据：锚 `queries`（json:202-238）6 条 = {0..5}×{title, first-sentence, caption-keywords}，文本为该 base 所在页 PDFBox 提取的机械推导（`NearDupS1Corpus.formOf`，零文本外呼）；每条经 `ConstraintQuerySplitter.split(text).size()==1` 断言（守卫复算绿）⇒ 查询文本恰 1 次 embed；图像分量 = base 位图 `embedImage`（`runRealAndWrite` 查询循环，embedImage 实呼 6 次入台账）。

## C · A/B 检索与判定（R3 / R4 / R5）

- [x] 7. A 跑 = 生产降权；B 跑 = `w=1`（`mockStatic(CALLS_REAL_METHODS)`），`git diff master -- src/main` 必须为空；两跑**复用同一批查询向量**（各嵌一遍 = 24 次 ⇒ 总 42 超帽）
      补勾依据：`git diff master -- src/main` 实测空输出（numstat 亦空）；A/B 装置 `NearDupIngestS1IT.runPass`（A=`callRealMethod` 旁路观测、B=恒等返回入参，逐查询 `verify(times(1))`）；向量复用由 `TrackingProvider.embedCache` 冻结 + `StubEmbeddingProvider` 供缓存（A/B 期 embedImage/vision 触发即抛）——两跑零外呼、`embedInvocationsA=B=6`（锚 json:906）；总外呼 30（台账），未发生 42 超帽形态。
- [x] 8. 逐查询落 `{候选集, cos, fusedA, fusedB, rankA, rankB, downWeightedKeys}`
      补勾依据：锚 `candidates`（json:489 起）逐候选 `queryIndex/sourceType/chunkId/variantKey/tier/band/downWeighted/cos/fusedA/fusedB/rankA/rankB`；`queriesOutcome`（json:446 起）逐查询 `chunkIdSetEqual/imageCandidateCount/downWeightedKeys[]`；守卫对 `maxImageScoreDelta/imageDeltaSum/imageCandidateCount` 从逐候选表重算比对实测绿（本跑 0 降权 ⇒ delta 全 0）。
- [x] 9. `verdict` 三选一由名次面+集合面机械推导；**三值各给出可达路径说明**；`NO-RANK-CHANGE` 必附 `windowCheck`（逐条 `gap` vs `0.30×(1−w)`）+ 不可外推声明
      补勾依据：锚 `verdict=NO-RANK-CHANGE`（json:893）由 `anyImageRankChange=false ∧ 全查询 chunkIdSetEqual=true` 机械推导（`computeResults` 三分支，卡 28 fix1 口径）；`verdictReachability` 三键各附路径（json:895-899 区域）；`windowCheck.entries=0` 恰等于 `downWeightedCount=0`（json:881，无降权事件即无 gap 可记，条目数等式由代码 `assertEquals(downWeightedCount, windowNodes.size())` 与守卫双面把守）；`nonExtrapolationNote` 落锚（含「不可外推」）。
- [x] 10. 逐变体记 `bandSide`（`IN-BAND`/`ABOVE`/`BELOW`，边界读生产常量）；`verdict` 只用带内变体推导，带外样本单列
      补勾依据：锚 `bandSides`（json:287-443）6 变体 × 各 3 行（其 base 的 3 查询），逐行 `cos/tier/band/recalled` 由生产 `ImageSimilarityGate` 现算（tierOf/bandOf，锚内不抄数字）；实测 6 变体全部 `ABOVE`（SAME_IMAGE，cos 0.997653–0.999956 ≥ 0.9944），`inBandVariantKeys=[]`（json:444）+ `constructionFailure`（json:445，D3 如实落锚）；verdict 推导带内集合为空 ⇒ 未用任何带外样本凑 RANK-CHANGE（亦无位移断言可凑）。

## D · 锚与守卫（R6 / R8）

- [x] 11. 锚 `docs/rag-quality/near-dup-ingest-s1-v1.json`（**LF**、默认逐字节复算）+ `.md`（≤40 行）；`callLedger.total` = 三分项之和且 ≤ 36
      补勾依据：`od -c near-dup-ingest-s1-v1.json | grep -c '\\r'` = 0、`.md` 同法 = 0（纯 LF）；默认复跑（opt-in 无 write）实测 `[near-dup-s1] 默认复跑逐字节一致（verdict=NO-RANK-CHANGE externalCalls=30 零外呼）`（%TEMP%/s1-replay1.log，剔除顶层 runtime 块后逐字节比对）；锚 `callLedger`（json:239）`vision 6 + embedImage 12 + embed 12 = total 30 ≤ cap 36`；`.md` 实测 `wc -l` = 19 ≤ 40。
- [x] 12. 守卫 `NearDupIngestS1GuardTest`（零外呼）：锚逐字节复算 + 台账等式 + verdict 可达性 + 既有 8 锚 sha256 与 master 全等
      补勾依据：锚落盘后 `mvn test -Dtest=NearDupIngestS1GuardTest` 实测 `Tests run: 5, Failures: 0`（%TEMP%/s1-guard2.log），覆盖：台账等式与硬帽、逐变体七链自洽、verdict 三选一与逐候选表重算、windowCheck 公式 `window=imageRouteWeight×(1−w)`、bandSide 六变体齐备、三值可达路径、判据 R0–R8 在锚、上游 4 锚 SHA-256 实算比对（gateProbe/notRun/ablation/displacement）；既有锚与 master 全等由 `git diff master --numstat` 空输出举证（见第 14 条）——既有 8 锚及既有测试零字节变动。

## E · 收尾（R6 / R7）

- [x] 13. 默认复跑（无 opt-in、无 write）与磁盘锚逐字节一致（剔除顶层 `runtime` 块），零外呼
      补勾依据：`RAG_NEAR_DUP_INGEST_REAL=1 mvn failsafe:integration-test failsafe:verify -Dit.test=NearDupIngestS1IT`（无 write 属性）实测 `Tests run: 1, Failures: 0` + `[near-dup-s1] 默认复跑逐字节一致（… 零外呼）`（%TEMP%/s1-replay1.log）；零外呼由结构保证（StubEmbeddingProvider 对 embedImage/vision/chat 触发即抛，全程未触发）。
- [x] 14. `git diff master --numstat`：写集只含 5 个新文件（2 测试 + builder + 锚 2 份 + 卡目录/任务卡），既有文件零字节变动
      补勾依据：`git diff master --numstat` 实测**空输出**（exit 0）——对 master 无任何已跟踪文件改动（既有 8 锚、既有测试、src/main、pom、CI 全零字节变动）；`git status --porcelain` 未跟踪恰为 `src/test/…/NearDupS1Corpus.java`、`NearDupIngestS1IT.java`、`NearDupIngestS1GuardTest.java`、`docs/rag-quality/near-dup-ingest-s1-v1.json/.md`、`openspec/changes/near-dup-ingest-s1-window-cooccurrence/`（卡目录）、`work/task-card-n…md`。
- [x] 15. 实测外呼数与报批数对账：贴计量点输出，`total ≤ 36`，真跑日志留档
      补勾依据：计量点输出（%TEMP%/s1-write1.log）：`[near-dup-s1] 锚已落盘: …（externalCalls=30 verdict=NO-RANK-CHANGE）`；锚 `callLedger` 三分项 `vision=6, embedImage=12（摄取6+查询6）, embed=12（子块行6+查询文本6）, total=30, cap=36, authorization="Owner 2026-10-25 报批 36"`；日志留 `C:\Users\fzdzzj\AppData\Local\Temp\s1-write1.log / s1-replay1.log / s1-guard2.log`（=Git Bash /tmp，不入库）。
- [x] 16. `git status` 只含写集；`git diff --check` 干净；`src/main` 空 diff
      补勾依据：`git status --porcelain` 实测仅第 14 条所列写集（7 个未跟踪路径，无已跟踪改动、无暂存残留）；`git diff --check` 无输出（工作树对 HEAD 无已跟踪改动可查）；`git diff master -- src/main` 空输出（head -3 零行）。
