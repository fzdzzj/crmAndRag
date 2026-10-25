# tasks · near-dup-ablation-redesign（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`=完成、`[-]`=按裁定不做、`[ ]`=未做；三态之外不许自造标记。
判据 R0–R6 与决策 D1–D4 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束（先记住）

- **零外呼**：只允许冻结向量 + 桩；**禁**读 `DASHSCOPE_API_KEY`、**禁**构造真实 provider、**禁**跑 `*REAL*` opt-in IT。
- **`src/main` 零 diff**；既有六锚 + 签收锚 + 入库面两锚（`near-dup-ingest-gate-probe-v1` / `near-dup-ingest-s1-not-run-v1`）+ 位移锚（`near-dup-displacement-evidence-v1`）**零字节变动**；`NearDupDisplacementEvidenceIT` **只许纯增**（新 `@Test` + 新私有 helper），既有方法/字段/写锚路径一字不动。
- **不做修复、不提阈值主张**：只测量与判定。

## A · 装置与鉴别力（R1/R2，纯 JVM）

- [x] 1. 新增 `NearDupAblationPositiveControlTest`（包 `com.slz.crm.knowledge.retrieval`，纯 JVM 无 Spring）：直构 `RetrievalCandidate`，A 跑 = `downWeightNearDuplicates` + `fuseRoutes`，B 跑 = 只 `fuseRoutes`
      补勾依据：文件已建（3 个 @Test，无 Spring/Mockito 依赖，ObjectProvider 匿名实现供默认权重）；surefire `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`（target/_ablation-write-run5.log、_mut-restore-green-final.log）。
- [x] 2. **正对照必翻转**：`NEAR_DUPLICATE`+`NEAR_HIGH` 图像候选在两跑中名次**相反**；断言翻转归因于 `0.3×(1−0.75)` 的乘数损失，不是别的
      补勾依据：flipped=true（锚 positiveControl 节）；rank B=2 → A=3；fusedB=1.0×0.3 与 fusedA=(1.0×0.75)×0.3=0.22499999999999998 均 assertEquals 无容差逐字节相等；损失 0.075 > B 跑领先 0.006（positiveControlFlips 断言文本）。
- [x] 3. **负对照**：非 `NEAR_DUPLICATE` 档候选两跑融合分逐条相等
      补勾依据：`Double.compare(fusedTextWithDownWeight, fusedTextWithoutDownWeight)==0` 与 `fusedNonNearImage` 同（negativeControlInvariant 断言，逐字节）。
- [x] 4. **窗口算术自洽**：用 `resolveImageRouteWeight` 与档位权重算出 `0.075/0.12/0.15`，与 `near-dup-fusion-impact-v1` 的常量断言一致（不一致 ⇒ 停手上报）
      补勾依据：windowBoundsMatchFusionImpactAnchor 读融合影响锚 bounds 三档，`0.30×(1−weightOfBand(band))` 与 bound 逐一比对容差 1e-12 全过（绿）。

## B · 真语料 A/B（R3/R4，冻结向量复用）

- [x] 5. 在 `NearDupDisplacementEvidenceIT` 内**纯增**一个用例：读位移锚冻结向量建同一套输入，同一查询跑 **A（生产降权）** 与 **B（`mockStatic` 只替换 `downWeightNearDuplicates` 恒等）**
      补勾依据：纯增 @Test `runNearDupAblationAtoBOnFrozenCorpus` + 私有 helper `parseAnchorMaterial/assembleReplayService/replayAnchorQueries`（重建段/循环体纯搬运，断言原样保留）；既有方法/断言/写锚路径未动；纯搬运后 G1 复跑仍逐字节一致（`[near-dup-displacement] 默认复跑逐字节一致（queryCount=7 verdict=POSSIBLY-DISPLACED 零外呼）`，target/_ablation-displacement-replay-proof.log）。
- [x] 6. **隔离断言**：B 跑 `downWeightNearDuplicates` 恰调用 1 次且返回恒等；A 跑结果带 `BAND_WEIGHT_METADATA_KEY`；两跑所有非 `IMAGE` 来源融合分逐条相等
      补勾依据：逐查询独立 mockStatic 作用域 + `gate.verify(times(1))`（捕获调用数==查询数==7 双向断言）；B 跑返回与入参逐条 `Double.compare==0`（坑 3）；A 出参带标记条数=2（isolationEvidence 落锚）；`maxTextScoreDelta=0.0`（非 IMAGE 两跑逐字节相等，computeAblationResults 内 assertEquals 逐条）。
- [x] 7. 逐查询落 `chunkIdSetEqual` + 逐候选 `(sourceType, tier, band, fusedA, fusedB, rankA, rankB)`；落 `imageCandidateCount / downWeightedCount / maxImageScoreDelta / maxTextScoreDelta`
      补勾依据：锚 `candidates[]` 50 行含 (queryIndex, sourceType, chunkId, tier, band, downWeighted, fusedA, fusedB, rankA, rankB)，`queries[]` 7 条 chunkIdSetEqual 全 true；imageCandidateCount=23、downWeightedCount=2、maxImageScoreDelta=0.05999999999999994、maxTextScoreDelta=0.0（守卫逐项重算比对绿）。
- [x] 8. **分母实测**：落 `childRows`（图片切片数 + 文本块数，逐条 `chunkId`）与 `embedInvocations`（未命中计）；**禁**任何 `×常数` 推导；两数不等须写原因
      补勾依据：childRows=50（imageRecords 7 + textRecords 43，`childRowChunkIds` 逐条列出，守卫跨锚重算==位移锚 corpusMaterial 行数和）；embedInvocations=11（embedCountingProvider 在 stub 上实计，A/B 两跑相等断言）；不等原因落锚 `childRowsVsEmbedInvocationsReason`（库行面 vs 单轮查询嵌入请求，两数均实测无推导）。
- [x] 9. `verdict` 三选一由**测量值机械推导**（`RANK-CHANGE` / `NO-RANK-CHANGE` / `INCONCLUSIVE`），`NO-RANK-CHANGE` 必附窗口算术 + 不可外推声明（R6）
      补勾依据：verdict=RANK-CHANGE（anyImageScoreChange=true / anyImageRankChange=false / allChunkIdSetsEqual=true，机械分支）；windowCheck 逐降权事件 gap=0.3856 > window=0.15（w=0.5 取 A 出参实测标记）；`nonExtrapolationNote` 落锚。

## C · 锚与守卫

- [x] 10. 锚 `docs/rag-quality/near-dup-ablation-v1.json`（**LF**，写模式 `-Dnear.dup.ablation.write=true`；默认模式逐字节复算一致）+ `.md`（≤40 行）
      补勾依据：锚 sha256=78b249ff11a19b9bdc373c3819e204d79884c7d09f3d9274a02a2829fb57d3eb，实测 LF_ONLY（od -c 无 \r，测试内另有 `!contains("\r")` 断言）；默认复跑逐字节一致（`[near-dup-ablation] 默认复跑逐字节一致（verdict=RANK-CHANGE …）`，target/_ablation-final-verify.log）；near-dup-ablation-v1.md 实测 14 行。
- [x] 11. 守卫 `NearDupAblationGuardTest`：verdict 三选一 + 条件字段、分母两数关系、正对照结论位、`maxImageScoreDelta` 与逐候选表自洽
      补勾依据：6 用例全绿（Tests run: 6, Failures: 0）：verdict 三选一+逐候选表/查询集重算（含 maxTextScoreDelta/imageCandidateCount/downWeightedCount）、逐降权事件窗口算术（条目数==事件数 + window 公式重算）、分母 childRows 跨锚重算+chunkId 逐条、正对照结论位（flipped=true + 0.225s vs 0.3s 结构复核）、externalCalls==0、上游两锚 SHA 实算。
- [x] 12. **变异自证三连**：改任一 `fusedB` 数字 ⇒ 红；`verdict` 改第四种 ⇒ 红；把正对照的 B 跑改回带降权 ⇒ 正对照用例红。三处还原后复绿，留红/绿日志
      补勾依据：mut1 改 q0 IMAGE:9415 fusedB(−0.01) ⇒ 守卫红 `imageDeltaSum expected: <0.10999999999999988> but was: <0.11999999999999988>`（target/_mut1-guard-red.log；首轮曾漏红——两条对称 delta 行下 max 聚合有盲区，补锚字段 imageDeltaSum 逐行和后任一行必红）；mut2 verdict=MAYBE-RANK-CHANGE ⇒ 红 `verdict 越界（只允许三值）`（target/_mut2-guard-red.log）；mut3 正对照 B 跑改回带降权 ⇒ `positiveControlFlips` 红 `正对照未翻转（…R1 命门）`（target/_mut3-pc-red2.log）；三处 cmp 字节一致还原后 9/9 复绿（target/_mut-restore-green-final.log）。

## D · 门禁与收尾

- [x] 13. `mvn -B -ntp test` 全绿（surefire 只增不减，贴统计行）；`mvn -B -ntp verify` 中位移 IT 复跑绿，**failsafe skipped 仍 16**
      补勾依据：`mvn -B -ntp test` 全量 `Tests run: 2159, Failures: 0, Errors: 0, Skipped: 0`（基线 2150 只增不减，target/_final-mvn-test.log）；`mvn -B -ntp verify` failsafe 总计 `Tests run: 149, Failures: 0, Errors: 0, Skipped: 16`（skipped 保持 16、用例 +1；NearDupDisplacementEvidenceIT 2 用例 1 skip 1 绿，target/_final-mvn-verify.log）；`bash scripts/check-test-baseline.sh` 通过（surefire 349 报告/2159 ≥ 347/2150，failsafe 47/149 ≥ 47/148，EXIT=0）。
- [x] 14. `git diff master -- src/main` **空**；`git status` 只含写集；`git diff --check` 干净；临时脚本/日志/.bak 已删；外呼 **0** 已对账
      补勾依据：`git diff master -- src/main` 空输出；`git status --porcelain` 仅写集（位移 IT 修改 + 正对照/守卫/锚 json+md 新增 + 主 agent 预置的 openspec/ 两文件与任务卡）；`git diff --check` 干净；写集六文件 od -c 实测无 \r；临时产物全部在 gitignore 的 target/（_mut-backup 已删）；外呼 0 对账：stub embedImage/vision/chat 触发即抛（复跑零触发）、锚 externalCalls==0 + zeroExternalCallBasis 结构声明、TrackingProvider 未构造。
