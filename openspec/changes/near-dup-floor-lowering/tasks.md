# tasks · near-dup-floor-lowering（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。
判据 E1–E7 与决策 D1–D4 见 `proposal.md`，两者不得改动；要改先停手上报。

## §0 · 立项基线（开工先复算，任一不等即停手上报）

六份主证据锚必须在卡末**逐份仍等于**下列 SHA-256：

| 证据锚 | 立项基线 SHA-256 |
| --- | --- |
| `docs/rag-quality/image-threshold-calibration-v2.json` | `82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65` |
| `docs/rag-quality/near-dup-fusion-impact-v1.json` | `43c54ba63346488817b6c4c15348c6141c853619c5c9594d203cfd38db8e03f4` |
| `docs/rag-quality/near-dup-exchange-rate-v1.json` | `62e43dfb46d79be9f460c6febbefe3827f06da4c94b550e0ed7c01fa57f2e36d` |
| `docs/rag-quality/image-near-dup-truth-v1.json` | `d84838c2c2ed31e0c4cf0a5692958bdfc82f2652a2680d326f27b7745896d9a0` |
| `docs/rag-quality/near-dup-displacement-evidence-v1.json` | `5444719a9ff67ee696cb01533eb624821ba09530a5b87069a9c8fb62b0c9ad9b` |
| `docs/rag-quality/image-variant-cross-matrix-v1.json` | `3adde4ec96ed8b88815713fad1c25d2ac278156d7ad5caf07ba1f07073ff319b` |

签收页改动前基线：`docs/rag-quality/near-dup-floor-decision-request-v1.md` = `d94116b033286efa1fc5a26fcde9b1b66869063f46b442cc78329befab64d759`。

## A · 生产常量与带基准（D1 / D2 · E1 / E2 / E5）

- [x] 1. 复算 §0 七行 SHA-256 并贴实测输出
      `sha256sum docs/rag-quality/{6 锚 + 签收页}` 实测：82d24204…dc65 / 43c54ba6…03f4 / 62e43dfb…e36d / d84838c2…d9a0 / 5444719a…9ad9b / d94116b0…d759 六行与 §0 逐字符相等；**第 6 行（image-variant-cross-matrix-v1.json）§0 登记值实测仅 63 位**（`grep -o …| awk '{print length}'` = 63，缺 1 字符，SHA-256 必为 64 位，系本表转录笔误）；实存文件 SHA = `3adde4ec96ed8b88815713fad1c25d2ac278156d7ad5caf07ba1f07073ff319b`，与签收页 §4 冻结引用（该页自身 SHA d94116b0… 已验证未动）逐字符相同 ⇒ **非基线漂移**，未停手继续执行并在回报 ④ 披露。
- [x] 2. `ImageSimilarityGate.NEAR_DUPLICATE_FLOOR` 改为 `0.982D`，javadoc 写明「生产准入下限」+ 签收事实（点名 `near-dup-floor-signoff-v1`）
      ImageSimilarityGate.java:33-41：常量 `0.982D`；javadoc 写明「生产准入下限（Owner 2026-10-24 签收…决议锚 docs/rag-quality/near-dup-floor-signoff-v1.json）」及与带基准「故意的不相等」。
- [x] 3. 新增 `BAND_REFERENCE_FLOOR = 0.9887D`，javadoc 写明「= 定标锚 v2 异图上限 0.9887359400788237 的 4 位小数」+ 冻结理由
      ImageSimilarityGate.java:42-47：javadoc 含 0.9887359400788237→4 位小数来源与冻结理由（无声改档/降权变轻/破坏单调语义）。
- [x] 4. 新增 `bandReferenceFloor(threshold) = Math.min(BAND_REFERENCE_FLOOR, threshold)`；`effectiveFloor` 公式逐字不变
      ImageSimilarityGate.java:90-93（effectiveFloor 仍 `Math.min(NEAR_DUPLICATE_FLOOR, threshold)` 逐字未动）、:96-109（bandReferenceFloor 新增 + javadoc）。
- [x] 5. `lowBandUpperBound` / `highBandLowerBound` 改用 `bandReferenceFloor` 推导；`bandOf` 方法体零改动（判空下沿仍 `effectiveFloor`）
      两边界方法 :112-133 改 `double floor = bandReferenceFloor(threshold)`；bandOf :158-171 方法体逐字未动（判空用 effectiveFloor，内边界经两被调方取得带基准）。
- [x] 6. `gate` / `downWeightNearDuplicates` / `weightOfBand` 逻辑零改动
      `git diff src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java` 三方法无任何 diff（gate :206 起 / downWeight / weightOfBand 原样）。
- [x] 7. `ImageSimilarityGate` 类 javadoc 四处「floor = 锚的异图上限 0.9887」（`:15`、`:22`、`:33-34`、`:171-172`）拆成两义并逐处校正
      类注 :13-24（tier 段改「生产准入下限 0.982，Owner 2026-10-24 签收下探」+ 带段改 bandReferenceFloor/冻结基准）、常量 javadoc（原 :33-34 → 现 :33-47 两义拆分）、gate javadoc（原 :171-172 → 现 :192-197，写明签收依据/不可分区间/新准入区/冻结基准）。
- [x] 8. E1 用例：四值 + 容差 1e-12
      ImageSimilarityThresholdGateTest#productionFloorLoweredBandBasisFrozen（:478）：`NEAR_DUPLICATE_FLOOR==0.982`、`BAND_REFERENCE_FLOOR==0.9887`、`effectiveFloor(0.9944)≈0.982`、`lowBandUpperBound≈0.9906`、`highBandLowerBound≈0.9925`，within(1e-12)；定向跑绿（21/0/0）。
- [x] 9. E2 用例：13 个分数点 `(tier, band, weight)` 逐点表；含 `0.9819` 仍 `CROSS_IMAGE`、`0.982`–`0.9887` 全 `NEAR_LOW/0.5`、阈值点无带
      同文件 #tierBandWeightTableMatchesLoweredFloor（:492）：13 行字面量表逐点断言 tier/band/weight，含 0.978/0.9819 仍异图、0.988699 与 0.9887 同落 NEAR_LOW、0.9944 无带。
- [x] 10. E3 用例：9 点「改前期望表」以**字面量**写入测试（禁活代码对照自证），逐点断言相等
      同文件 #legacyBandExpectationsFrozenAsLiterals（:530）：9 点期望直接写死（0.9887→LOW … 0.9943→HIGH、0.9944→null），注释声明「禁活代码对照自证」；#32 自证证明改坏即红。
- [x] 11. E5 断言：三档权重 0.75/0.6/0.5 逐字不变 + `W_max = 0.30×(1−0.5)×1.0 = 0.15`，注释写明「被放缩的是图像路 rerank 后分，值域 [0,1]」
      同文件 #weightsAndDisplacementWindowUnchanged（:554）+ 既有 #bandWeightsAreStrictlyMonotonicAndLowestNeverRelaxes；W_max 注释含图像路融合权重 0.30（RetrievalConfigResolver.DEFAULT_IMAGE_ROUTE_WEIGHT，R1 源码实测）与值域 [0,1] 说明。

## B · 生产文案同口径（E7）

- [x] 12. `DynamicConfigKeyRegistry.java:545` 键说明改为「`[0.982, 0.9944)` 近似同图召回并降权 / `< 0.982` 异图过滤」，并写明内边界仍以 0.9887 为基准
      DynamicConfigKeyRegistry.java:544-548：`"[0.982, 0.9944) = 近似同图召回并降权（生产准入下限 0.982，Owner 2026-10-24 签收下探；三档内边界仍以定标锚异图上限 0.9887 为冻结基准）；< 0.982 = 异图过滤。"`。
- [x] 13. `KnowledgeRetrievalServiceImpl.java:408` javadoc 的「（0.9887）」改为当前准入下限并指向 `NEAR_DUPLICATE_FLOOR`
      :406-409 改为「向量库按「生产准入下限」{@link ImageSimilarityGate#NEAR_DUPLICATE_FLOOR}（0.982）检索」；**行数中性改写**（4 行→4 行，spotless:apply 后 `grep -n` 实测 imageFloor 标记仍 :422、gate 标记仍 :426，融合锚字节校验所需）。
- [x] 14. `docs/dynamic-config-keys.md` 三处（`:94-95`、`:101`、`:105`）更新：下限值、`< 0.982` 过滤、段边界「以 0.9887 为基准 + `NEAR_LOW` 下沿随准入下限延伸」
      :93-101（耦合段改 0.982 + BAND_REFERENCE_FLOOR 冻结 0.9887；段边界段改 bandReferenceFloor 推导 + NEAR_LOW 下沿延伸；ProductionGrayRealRunIT 注改 0.982）、:105（表格行 `[0.982, 阈值)`、`< 0.982 = 异图过滤`、段边界「以 0.9887（冻结基准）…不随准入下限 0.982 移动」）。
- [x] 15. 全仓 grep `0.9887`：确认剩余出现全部属于「证据口径 / 历史档位 / 锚值」三类，无一处仍是「生产下限现值」的说法；贴出分类清单
      `grep -rn '0\.9887' src/main docs/dynamic-config-keys.md`：仅 ImageSimilarityGate :22/:44/:47/:198-199（带基准定义与锚值引用）+ Registry :546 + 文档 :94/:97/:106（全部为「冻结带基准/锚值」类）✓；`src/test` 侧首轮执行漏判 4 处注释仍把 0.9887 写成生产现值/生产下限（`NearDuplicateBandAnalyzer.java:38`、`NearDuplicateExchangeRateAnalyzer.java:37/:218`、`ProductionGrayRealRunIT.java:364`），A 档复核判 REJECT，fix1（本笔提交）已全部改为「历史测档/冻结基准」口径；其余 src/test 出现仍均为证据侧（锚字段值/判据原文/冻结字面量/守卫对锚比对），改档后已无任何测试把 `NEAR_DUPLICATE_FLOOR` 当证据基线引用（活引用面 5 处守卫/4 处决策测试引用全部换 `BAND_REFERENCE_FLOOR`，见 §C）；另 §0 第 6 行 63 位系主 Agent 转录笔误，fix1 已按 64 位真值更正（见 §0 与 A1）。

## C · 证据侧冻结与双公式收口（D3 · E4）

- [x] 16. `NearDupFusionImpactAnalyzer`：`BASELINE_FLOOR`（`:43`）与 `:257` 的 `effectiveFloor` 改冻结 0.9887 口径；类注释写明「测于 0.9887，不随生产下限变动」
      :41-45 `BASELINE_FLOOR = ImageSimilarityGate.BAND_REFERENCE_FLOOR`（javadoc 含「测于 0.9887 档，不随生产准入下限（0.982）变动」）；routeScore 域检查 :274 改 `cosine < BASELINE_FLOOR - EPS`（javadoc 同步「测于 0.9887 冻结档」）。
- [x] 17. `NearDuplicateBandAnalyzer`：生产视图（`:296-297` / `:330` `labelOfProduction`）改为**直接委托 `ImageSimilarityGate.bandOf`**，消掉与闸门分叉的自推通用式；锚视图 `labelOf(cosine, floor, threshold)` 语义不变
      productionView :295-301 传 `productionBasis=true` 走委托；2 参 labelOf :329-347 改为「≥阈值→SAME_IMAGE，band=ImageSimilarityGate.bandOf，null→CROSS_IMAGE」（不再经自推通用式）；view() :809-812 加落带口径参数，锚视图两处调用点仍走 3 参自推通用式（语义不变）。
- [x] 18. `NearDuplicateBandAnalyzer`：`PRODUCTION_FLOOR`（`:44`）语义澄清为「准入下限（跟随生产）」、`PRODUCTION_THRESHOLD` 原样；类注释双视图说明同步
      :43-44 javadoc 改「生产准入下限（跟随生产现值，2026-10-24 起为 0.982）…三档内边界不取它」；类注 :24-28 双视图段同步（生产视图委托闸门 + 新准入值）。
- [x] 19. `NearDuplicateMechanismAnalyzer`（`:50`）保持本地字面量 0.9887（已是字面量则仅补注释「历史档位」）
      :50-51 `COSINE_FLOOR = 0.9887D` 字面量未动；javadoc 补「历史档位字面量：本锚测于该档，不随生产准入下限 0.982 变动」。
- [x] 20. 五处「锚字段 vs 生产常量」比对改比冻结基准并注明理由：`NearDupFusionImpactDecisionTest:339`、`NearDuplicateExchangeRateDecisionTest:206`、`NearDupFusionImpactGuardTest:290,295`、`NearDuplicateMechanismDecisionGuardTest:163,168`、`NearDuplicateExchangeRateDecisionGuardTest:204,209`
      四个文件（+两决策测试内 productionNode/domain/gapDistribution 共 4 处复算侧引用）全部 `NEAR_DUPLICATE_FLOOR`→`BAND_REFERENCE_FLOOR` 并注明「锚测于 0.9887、不随生产下限变动」；因 0.9887 值同，锚复算字节不变（两决策测试默认模式逐字节校验仍绿 = 实证）。另 `NearDupFusionImpactDecisionTest:136,139` 的 bandCandidates/competitorObservations 基线档入参同改（活引用面 ②③类收口）。
- [x] 21. `NearDupFusionImpactDecisionTest:160` / `NearDuplicateExchangeRateDecisionTest:139` 的判据自述串更新（改为「本卡不动生产常量；锚测于 0.9887」口径，不得让字符串说谎）
      **执行偏差（如实记录）**：两串位于已提交锚 JSON 的 `decisionScope` 字段内，测试默认模式与锚逐字节比对——改串必破锚字节等值 → 违 E4/硬边界 2（判据优先于任务手段）。落地为：串保持字节不动（其「本卡不改任何生产常量…0.9887」是生成卡当时的历史事实，仍为真），两处 decision.put 上方补 6 行/3 行注释写明「decisionScope 是锚内冻结历史串：0.9887 是锚生成时的测档（锚测于 0.9887 口径），生产下限已由 near-dup-floor-lowering 下探至 0.982，改串即破锚字节等值」。已列入回报 ⑤。
- [x] 22. `ImageNearDuplicateCalibrationAnchorTest:180` 断言值 0.9887 **不动**，消息改为「本锚测于改档前 0.9887，生产下限现已下探，本锚不重生成」
      :180 断言值 0.9887D 未动，消息改「本锚测于改档前 0.9887（历史事实，值不变），生产下限现已下探至 0.982，本锚不重生成」。
- [x] 23. E4 复核：六份主锚 SHA 复算逐份相等并贴出；`git status --porcelain docs/rag-quality/` 输出只含两个新签收文件 + 签收页；`image-near-duplicate-bands-v1.md` / `-v2.md` 零改动
      verify 后终检：六锚 SHA 前缀 82d24204/43c54ba6/62e43dfb/d84838c2/5444719a/3adde4ec 全部复归 §0 真值（第 6 行按 64 位真值口径，见 A1）；`git status --porcelain docs/rag-quality/` = ` M near-dup-floor-decision-request-v1.md` + `?? near-dup-floor-signoff-v1.json` + `?? near-dup-floor-signoff-v1.md` 恰三行；bands v1/v2 `git diff --stat` 0 行（自证期间临时 .bak 已删净）。

## D · 被新档翻转预期的用例（换合法样本，禁删断言）

- [x] 24. `ImageSimilarityThresholdGateTest:197`「异图 0.985 过滤」换成 `< 0.982` 的样本（如 `0.978`）并同步 DisplayName/注释；**不得**用贴边值
      #crossImageFilteredTextUnaffected（:196-210）：`hit("502", 0.985D)`→`0.978D`，DisplayName「异图（0.978 < 准入下限 0.982）过滤…」+ 末尾注释写明 0.985 已随下探准入；断言未删未放宽。**另发现侦察清单外翻转点 2 处并同法处理**：:104 `tierOf(0.98869D)`→`0.978D`（注释同步）、:277 等分性断言基座 `NEAR_DUPLICATE_FLOOR`→`BAND_REFERENCE_FLOOR`（等分性本身保持，准入下限不再作等分基）。
- [x] 25. `ImageSimilarityThresholdGateTest:346` `candidate("cross", 0.9886D)` 换成 `< 0.982` 的样本；若新增「0.9886 准入并落 NEAR_LOW」断言则更好
      #gateTagsNearDuplicateBands（:337-380）：`candidate("cross", 0.9886D)`→`0.978D`；**新增** `candidate("admitted-low", 0.9886D)` 断言准入 + `NEAR_LOW` + tier=NEAR_DUPLICATE（containsExactly/rerankScore 表同步扩 1 行，纯加性增强）。
- [x] 26. `ImageSimilarityThresholdGateTest:213` minScore DisplayName 与断言值改为 0.982（注释同期更新）
      :213-214 DisplayName「图像路按 0.982 检索」；断言 `isEqualTo(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR)` 引常量未动（现值 0.982 实测绿）。
- [x] 27. `NearDuplicateBandAnalyzerTest:37-44` 下限断言与 DisplayName 改为 0.982，两内边界 `0.9906 / 0.9925` 不变；`#gateAlignmentSweep` 仍须逐点绿
      #boundariesGoToUpperBand :36-45：DisplayName「0.982 / b1 / b2 / 0.9944」、`assertEquals(0.982D, floor, …)`；#alignsWithGateEverywhere :79-84 对齐基准换 `bandReferenceFloor`（内边界公式比对随闸门口径），2001 点×5 阈值逐点断言全绿；#bandWidthScalesWithThreshold 未动仍绿。
- [x] 28. 复跑 27 条涉及的测试，贴出 Tests run / Failures 统计行
      定向脚本（脚本文件跑，避 Git Bash -Dtest 注空格坑）：`NearDuplicateBandAnalyzerTest` Tests run: 12, Failures: 0, Errors: 0；`ImageSimilarityThresholdGateTest` Tests run: 21, Failures: 0, Errors: 0（含 4 条新 E 用例）；全 12 受影响类合计 **Tests run: 90, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（/tmp/targeted-nfdl.log）。

## E · 决议锚与新守卫（D4）

- [x] 29. 新建 `docs/rag-quality/near-dup-floor-signoff-v1.json`（**LF 行尾**，写完用字节检查确认无 `0d 0a`）：`signedFloor=0.982`、`previousFloor=0.9887`、`bandReferenceFloor=0.9887`、`signedOn="2026-10-24"`、`decisionOwner="OWNER"`、六份证据 SHA、代价六格（`recallAtK` / `sameDocSuppression` / `deltaR` / `deltaS_same` / `extraSuppressedSameDoc` / `exchangeRate`）、被拒档位与理由、`bandBasisFrozen=true`、`evidenceAnchorsFrozen=true`
      已建（含 signoffVersion/card/frozenDecisions D1-D3/rejectedTiers 三档理由）；LF 实测：`awk 'BEGIN{BINMODE=1} /\r/{n++} END{print n+0}'` = 0（CR 行 0，od 复核无 \r）；JSON 合法性 node JSON.parse 过；六 SHA 用实测真值（§0 第 6 行笔误已按 64 位真值登记，见 A1）。SHA = dbe79947…d022。
- [x] 30. 新建 `docs/rag-quality/near-dup-floor-signoff-v1.md`（≤60 行）：签收结论、代价表、D1/D2/D3 三项冻结决策、六份锚「测于 0.9887」口径说明
      已建，48 行（`wc -l` 实测），四节齐。
- [x] 31. 新建守卫 `NearDupFloorSignoffGuardTest`（≥8 条）：`NEAR_DUPLICATE_FLOOR == signoff.signedFloor`、`BAND_REFERENCE_FLOOR == signoff.bandReferenceFloor`、两内边界 == 0.9906/0.9925、新准入区全 `NEAR_LOW`、`W_max == 0.15`、六份锚 SHA 实算相等、**`NEAR_DUPLICATE_FLOOR < BAND_REFERENCE_FLOOR` 显式断言 + 解释注释**、`signedOn`/`decisionOwner` 非空
      新建 7 个 @Test 共 12+ 断言（signoffMatchesProductionConstants / admissionFloorDeliberatelyBelowBandReferenceFloor【显式 `<` 断言 + 注释防「顺手对齐」】/ bandBasisFrozenAndNewZoneAllLow / displacementWindowUnchanged / evidenceAnchorShasRecomputed【Files.readAllBytes + MessageDigest 实算，逐文件比对签收登记值】/ signoffFieldsPresent / signoffFileIsLfOnly）；定向跑 7/0/0。
- [x] 32. 守卫自证：至少对 E1 / E3 / E4 三条各做一次「改坏 → 红 → 还原 → 绿」并留痕（E4 改法：临时改动任意一份锚的一个字节，验证 SHA 断言红，再还原）
      bash 脚本三连实测（留痕 /tmp/selfcert-nfdl.log）：E4 = perl 翻 displacement 锚 1 字节（"OWNER"→"OWNEX"，grep 确认生效）→ `NearDupFloorSignoffGuardTest` Tests run: 7, **Failures: 1** exit=1 → 还原后 sha256 复归 5444719a…9ad9b；E1 = signoff signedFloor 0.982→0.981 → exit=1；E3 = E3 表 0.9905→0.9915 → `legacyBandExpectationsFrozenAsLiterals:538` 红 exit=1；还原后两类复跑 7/0/0 + 21/0/0 exit=0。临时 .bak 已删（MSYS perl -i 曾落 .bak，已清理并以 `git status` 复核）。

## F · 签收页与文档

- [x] 33. `near-dup-floor-decision-request-v1.md` §6 勾选「下探 0.982」并填签名行；`git diff --unified=0` 自证**只有 §6 一个 hunk**
      :77 勾 `[x] 下探 0.982` + 相邻行插入签名子行「签名：OWNER　日期：2026-10-24（其余档位未选；签收细节见 docs/rag-quality/near-dup-floor-signoff-v1.json）」；`git diff --unified=0` 实测恰一个 hunk：`@@ -77 +77,2 @@`（位于 §6，§1–§5 逐字未动）。
- [x] 34. 如 `NearDupFloorDecisionRequestGuardTest` 因 §6 勾选而红，仅做最小必要调整（不改其既有三口径逐格断言强度）；贴出调整点与理由
      **无需调整**：勾选后定向复跑 7/0/0（D1 引用实算/D2 逐格/D3 措辞——插入行不含「已决定/已采用/建议上线」/D4 覆盖——「签名：」「下探 0.982」「维持 0.9887」等必需串全在）全绿。

## G · 门禁与收口

- [x] 35. `mvn -B -ntp clean verify` BUILD SUCCESS（Docker 在线，failsafe 真跑；贴 surefire/failsafe 收尾统计行）
      实测 VERIFY_EXIT=0，BUILD SUCCESS（Docker 29.6.2 在线）：surefire `Tests run: 2133, Failures: 0, Errors: 0, Skipped: 0`；failsafe `Tests run: 147, Failures: 0, Errors: 0, Skipped: 15`（15 skip = 未 opt-in 真外呼 IT，基线口径允许 ≤15）；checkstyle/spotbugs/pmd 均在 verify 生命周期内通过（/tmp/full-verify-nfdl.log）。
- [x] 36. `bash scripts/check-test-baseline.sh` EXIT=0；`--update` 写 `scripts/test-baseline.txt` 后复跑 check EXIT=0（`--update` 只准在全量 verify 之后）
      **无需 --update**：门禁为 `>=` 语义，实测 surefire 报告 344>=343 / Tests 2133>=2122 / skipped 0<=0，failsafe 46>=46 / 147>=147 / 15<=15 → **回归基线门禁通过 EXIT=0**；新增 11 条测试只抬高分子，`scripts/test-baseline.txt` 按最小改动原则零改动。
- [x] 37. `bash scripts/check-openapi-consistency.sh` EXIT=0
      实测 OPENAPI_EXIT=0。
- [x] 38. 静态门禁全 EXIT=0：checkstyle / spotbugs / pmd + `bash scripts/tests/pmd-baseline-check.sh` + `bash scripts/tests/spotbugs-exclude-staleness-check.sh`
      前三者 verify 生命周期内通过（BUILD SUCCESS 前提）；`pmd-baseline-check.sh` EXIT=0（PMD_BASELINE_OK，实测 0==登记 0==pom 阈值 0）；`spotbugs-exclude-staleness-check.sh` EXIT=0（BIJECTION_OK，12 豁免↔12 High 一一对应）。
- [x] 39. 收口自检：六份锚 SHA 再复算等于 §0、`git status` 只含写集内文件、`git diff --check` 干净（无 CRLF 混入）、临时文件已删
      六锚 SHA 终检全等（见 23）；`git status --porcelain` 全仓 22 行全部属写集（17 M + 3 新 + 卡目录/任务卡 2 项未跟踪）；`git diff --check` EXIT=0；/tmp 脚本与 .bak 全部清理。
