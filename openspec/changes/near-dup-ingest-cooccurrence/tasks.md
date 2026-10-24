# tasks · near-dup-ingest-cooccurrence（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。
判据 R0–R7 与决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 授权与上限（硬约束，先记住）

- 外呼上限：**S0 18 次 + S1 144 次 = 累计 ≤162 次**。**能少调用就必须少调用**，不许拿上限当下限。
- `src/main` **零 diff**（D3）；既有 IT / 六份锚 / 签收锚 / 语料生成器 **零改动**（D4/D5）。
- 停步阈值：**S0 `gatePassRate < 4/6` ⇒ 立刻停，不许进入 S1**。

## A · 零外呼可行性（R0）

- [x] 1. 复算六份既有锚 SHA-256，与上一卡 §0 表逐份比对（证明基线未漂）
      `sha256sum` 实测六锚 = 82d24204…dc65 / 43c54ba6…03f4 / 62e43dfb…e36d / d84838c2…d9a0 / 5444719a…9ad9b / 3adde4ec…319b，与上一卡 §0 表逐字符相等；签收锚 dbe79947…d022 一并复算；基线未漂。
- [x] 2. 新增 `NearDupIngestPdfBuilder`：把 `NearDuplicateCorpus` 的变体图按 ≤20 图/份包成**合成 PDF**（PDFBox），返回 `List<byte[]>`
      NearDupIngestPdfBuilder.java 新建（88 行）：一图一页、页尺寸=图尺寸、LosslessFactory 无损内嵌、无时间戳确定性；>20 图按序切多份（第 k 份第 j 页 ↔ 输入第 20k+j 张，闸门拒图不破坏映射）。
- [x] 3. 写零外呼自检：合成 3 图 PDF → `PdfImageExtractor.extract(bytes, 20)` 抠出的**图数与像素尺寸逐一对上**（R0）
      NearDupIngestGateProbeGuardTest#syntheticPdfRoundTripFaithful 绿：3 图（64/77/90px）合成 → 生产提取器抠 3 图、宽高逐一对上。
- [x] 4. 自检覆盖边界：单图 PDF、>20 图 PDF（应被帽截断）、非 PDF 字节（应返回空表），逐条断言
      #builderBoundaries 绿：单图 PDF 抠 1；21 图构造侧切 20+1 且提取侧帽截断恰 20；非 PDF 字节经生产 buildSlices 返回空表且桩计数 0（旁路吞异常但零外呼）。
- [x] 5. 贴 R0 实测输出（3 图 → 抠出 3 图、尺寸相等）
      work/_nds0-guard-green.log `Tests run: 3, Failures: 0, Errors: 0`；S0 真跑逐图 R0 保真断言（抠出 1 图+宽高一致）6/6 过（work/_nds0-write7.log）。

## B · S0 闸门探针（R1，18 次外呼）

- [x] 6. 新增 `NearDupIngestGateProbeIT`（opt-in `RAG_NEAR_DUP_INGEST_REAL=1`）：取 6 图 = **3 个 base + 3 个最差变体**（`crop-12` / `crop-04` / `scale-070`），走真实 ingest 路径
      GateProbeIT 新建（未 opt-in skip / write 真跑 / 默认复跑逐字节三模式）；选取规则冻结于锚 selectionRule：带内首对且变体过生产提取器 64px 下限（不合规顺延，无合规带内实例回落语料序），crop-12 取 base 不重合合规变体；实测落点 p1-b1 + p1-b1@crop-04（带内 0.9934）/ p16-b2 + p16-b2@scale-070 / p18-b3 + p18-b3@crop-12。
- [x] 7. 逐图落 `(assetKey, kind, vlmTokens, gatePass, failReason)` + `gatePassRate` + `externalCalls`（实测，非计算）
      锚 images[] 6 条逐图全落；gatePassRate=6/6（gatePassCount 由逐图重算，守卫复算一致）；externalCalls=18 由 BudgetedTrackingProvider 真调用点累加，写锚模式断言 embedImage/embed 恰等于过闸门图数。
- [x] 8. 落 `VISION` 与 `EMBEDDING` 两类调用的**次数与 token**；embedding token 不可得时**显式写 `null` + 声明**（禁按字符数估算，见 `docs/token-metering.md`）
      VISION 次数 6 + token P/C/T 逐图实测（335/24/359 … 合计 2886）；EMBEDDING 次数 12（embedImage 6 + embed 6），token 全 null + constants.meteringNote 声明端点限制。
- [x] 9. 写模式跑一次落锚 `near-dup-ingest-gate-probe-v1.json`（LF），默认模式复跑逐字节一致
      写锚 work/_nds0-write7.log 绿（LF 字节确认无 \r）；复跑 work/_nds0-replay2.log「默认复跑逐字节一致（gatePassRate=1.0 verdict=PROCEED-S1 零外呼）」。
- [x] 10. **判定并留痕**：`gatePassRate ≥ 4/6` 才能进 S1；`< 4/6` ⇒ **停手上报**，附逐图失败原因与「解耦图像向量与描述」的另立卡建议
      gateVerdict=PROCEED-S1（6/6 ≥ 4/6）落锚（gateVerdictReason 字段）；全过故无逐图失败原因可列。
- [x] 11. 为 S0 锚写守卫 `NearDupIngestGateProbeGuardTest`（实算 SHA + `externalCalls` 与图数对账 + `gatePassRate` 由逐图结果**重算**得出，禁硬写）
      守卫 3 用例绿（定标锚 SHA 实算、failReason/影子分类/gatePassRate/gateVerdict 全由逐图重算、外呼对账不超帽）；守卫自证：gatePassRate 改坏→红（work/_nds0-guard-red.log 1 Failure）→逐字节还原→绿（work/_nds0-guard-green.log 3 绿）。

## C · S1 入库 + 消融（R2–R5、R7，≤144 次外呼）

> **fix1（2026-10-25）整组「不做」**：Owner 裁定 A′（S1 未授权，装置拆除，骨架留 d7abc37 供后续卡取回）。
> 以下 [x] 均为**「不做」处置勾**（记录裁定后的终态），**非完成勾**；禁补勾、禁伪造完成。

- [x] 12. 选取规则写死在代码里并从定标锚实读：带内对 = `stratum=near-duplicate ∧ cos ∈ [0.9887, 0.9944)` 共 **7 条**，取全部参与者；再从同档补非带内变体至 **≥6 张**；最终 48 图（凑不齐就按同档顺序补，**不许换语料**）
      不做（Owner 裁定 A′：S1 未授权，装置拆除）。骨架在 d7abc37 的 `NearDupIngestCooccurrenceIT`。
- [x] 13. 48 图分 3 份合成 PDF（20/20/8）入同一 KB，与既有真实 PDF 文本切片**同库共存**
      不做（同上，随组裁定）。
- [x] 14. 装配复用 `NearDupDisplacementEvidenceIT` 的双集合结构（**复制，禁改原 IT**）
      不做（同上，随组裁定）。
- [x] 15. 落 R2 对账：`图数 N` / `image 集合向量数` / `描述 chunk 数` / `externalCalls` 四值，`externalCalls == 3×N` 为断言
      不做（同上，随组裁定）。
- [x] 16. 实现消融：同一 `RetrievalQuery` 跑 A（生产权重）/ B（权重全 1.0）两遍；**断言两次 `chunkId` 集合逐条相同**（R3）
      不做（同上，随组裁定）。
- [x] 17. 逐候选落 `(chunkId, sourceType, imageRouteScore, textRouteScore, fusedScore, tier, band, rankA, rankB)`
      不做（同上，随组裁定）。
- [x] 18. 落 R5 断言：逐候选 `textRouteScore` 两次**必须相等**（消融污染文本路即红）
      不做（同上，随组裁定）。
- [x] 19. 落 R7 `textSideNearDuplicates` 计数（变体描述与 base 描述近重复），仅解释用、不作判据
      不做（同上，随组裁定）。
- [x] 20. 落 R6 `verdict` 三选一 + 逐条 `displacedEvidence`（查询、图像候选、被谁压过、两次排名、两个分）
      不做（同上，随组裁定）。
- [x] 21. 锚 `near-dup-ingest-cooccurrence-v1.json`（LF）+ `.md`（≤80 行：方法、四值对账、verdict、逐条证据、`missingEvidence`）
      不做（Owner 裁定 A′）：S1 锚**必须不存在**（守卫断言负空间）；负结论改由 `docs/rag-quality/near-dup-ingest-s1-not-run-v1.json` 预注册。
- [x] 22. 守卫 `NearDupIngestCooccurrenceGuardTest`（实算 SHA + `verdict` ∈ 三选一 + `externalCalls ≤ 144` 且 `> 0` + 带内覆盖 ≥7 + R5 一致性由数据重算）
      不做（Owner 裁定 A′）：守卫随装置拆除；其负空间断言（NOT-RUN 锚在位自洽 / S1 锚不存在）由 `NearDupIngestGateProbeGuardTest#s0OnlyClosureAndFrozenProjection` 承接。

## D · 收口

> **fix1（2026-10-25）整组按 Owner 裁定 A′ 收束**（S1 未授权，装置拆除，骨架留 d7abc37）：S1 相关条目记**不做**，
> 范围中立条目（25/28/29）由 fix1 实际执行并留真凭据（task card §11）。23 为 fix1 前历史完成记录，原样保留。

- [x] 23. 两个新 IT 在**无 opt-in** 时必须 skip（不许失败、不许真跑），贴 `Tests run … Skipped` 行
      work/_skip-check.log：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 2`（两 IT 各 Skipped: 1，零外呼）。（fix1 前历史记录，保留）
- [x] 24. 守卫自证：改坏 → 红 → 还原 → 绿（至少 S0 的 `gatePassRate` 与 S1 的 `verdict` 各一次）
      不做（Owner 裁定 A′）：S1 `verdict` 半随裁定作废；S0 `gatePassRate` 半已于首轮自证（task card §10：_nds0-guard-red/green 红绿记录）。
- [x] 25. `mvn -B -ntp clean verify` BUILD SUCCESS（Docker 在线；贴 surefire/failsafe 统计行）
      fix1 实际执行全绿（统计行见 task card §11）；随组记收束，不作为原卡 S1 范围的完成项。
- [x] 26. `bash scripts/check-test-baseline.sh` EXIT=0；`--update` 后复跑 EXIT=0（`--update` 只准在全量 verify 之后）
      不做（Owner 裁定 A′）：fix1 实测 check EXIT=1 如实上报——计数全部上漂且在下限内（surefire 345/2137、failsafe 47/148），红在 failsafe skipped 上限 16 > 15（多出的 1 skip = d7abc37 引入的 S0 gate-probe IT，基线锚定于其前的 0d42809）；`--update` 重锚属 M1 阶段（主 agent），本卡禁止。
- [x] 27. `bash scripts/check-openapi-consistency.sh`、`bash scripts/tests/pmd-baseline-check.sh`、`bash scripts/tests/spotbugs-exclude-staleness-check.sh` 全 EXIT=0
      不做（不在 fix1 验收序列；删 3 测试文件后 pmd/spotbugs 台账的「该下调」复测归 M1 重锚一并做）。
- [x] 28. `git status` 只含写集内文件；`src/main` 零 diff 自证（`git diff master -- src/main` 空）；六份既有锚与签收锚 SHA 复算等值
      fix1 实际执行留真（task card §11）：src/main 对 master 空 diff；六锚（d84838c2/82d24204/5444719a/62e43dfb/43c54ba6/3adde4ec）+ 签收锚 dbe79947 SHA 复算与 A.1 台账等值。
- [x] 29. 收口自检：临时脚本/日志已删；`git diff --check` 干净；外呼总次数 ≤162 已对账
      fix1 实际执行留真：零新外呼（累计 153 ≤ 162）；临时文件已清；`git diff --check` 干净（task card §11）。
