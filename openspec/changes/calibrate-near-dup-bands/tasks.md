# 任务清单 · calibrate-near-dup-bands

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。
> 档位：**A 档**（真跑 + 外呼 + 定标锚改写）。复核四判据见 `proposal.md` §8。
> 顺序纪律：**§1 → §2 → §3 → §4 → §5 → §6 → §7 → §8**（§3 真跑前必须先用 §1/§2 的默认测试证明语料与落带口径正确，否则一次外呼就是白烧）。
> 硬边界（全卡有效）：`src/main/**` 零 diff；`frontend/**` 零改动；v1 锚 / bands-v1 / `sources.json` / `RealPdfAssets` / `ImageThresholdCalibration*` 冻结；不下载新语料、不新增二进制；不 merge、不 push。

## 1. 立项与变体语料（零外呼）
- [x] 1.1 从 master 检出分支 `feature/calibrate-near-dup-bands`，首个 commit 提交本卡 `openspec/changes/calibrate-near-dup-bands/{proposal,tasks}.md`（`docs(openspec): add calibrate-near-dup-bands card`）
      补勾依据（2026-10-23 实测）：`git rev-parse --abbrev-ref HEAD` = `feature/calibrate-near-dup-bands`，基座 `git log --oneline` 首条为 `b6f0c0e docs(openspec): add calibrate-near-dup-bands card`、其父为 master `d94877c`；`git diff master --name-only` 无 `src/main`。
- [x] 1.2 新增 `src/test/java/com/slz/crm/quality/NearDuplicateCorpus.java`：base 选取（NIST 101r1 前 **8** 张 / NIST 183 前 **4** 张 / DOE 前 **12** 张，经 `RealPdfAssets.extract(pdf, n)`）+ 按 SHA-256 去重（去重后实际值记账，**不得凑数**）+ 5 类变体（`jpeg-q80` / `scale-085` / `scale-070` / `crop-04` / `crop-12`，定义见 `proposal.md` §2）；变体最短边 < 48px 者跳过并进 `skips()`；**无随机源、无时间戳**
      补勾依据（2026-10-23 实测）：`git show --stat 5ee724c` 含 `NearDuplicateCorpus.java`（353 行）；`plan()` = 101r1:8 / 183:4 / doe:12，`KINDS` 五类，`JPEG_QUALITY=0.80F`、`MIN_EDGE_PX=48` 写死；`generate()` 内无 `Random`/`Clock`/网络，`NearDuplicateCorpusTest#hasNoNetworkOrClockDependency` 反射扫字段通过。
- [x] 1.3 新增 `src/test/java/com/slz/crm/quality/NearDuplicateCorpusTest.java`（surefire，零外呼）：断言 `bases().size()`、`variants().size()`、`images()` 计数与 `skips()` 自洽；同 base 两次生成**字节相等**；每变体 SHA-256 必与 base 不同；每变体 `kind` 与 `baseKey` 可回指；不得出现网络/Clock 依赖
      预期证据：`mvn -B -ntp test -Dtest=NearDuplicateCorpusTest` 的 Tests run 与实测计数（bases / variants / skips / images）
      补勾依据（2026-10-23 实测）：`mvn -B -ntp test -Dtest=NearDuplicateCorpusTest` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`；实测计数 bases=24 / variants=120 / skips=0 / images=144（`countsAreSelfConsistent` 断言 24、120+0=24×5、144=24+120 全过）。

## 2. 落带分析纯函数（零外呼）
- [x] 2.1 新增 `src/test/java/com/slz/crm/quality/NearDuplicateBandAnalyzer.java`：`labelOf(cosine, threshold)` / `bandOf(cosine, threshold)` 与 `ImageSimilarityGate`（`effectiveFloor` / `lowBandUpperBound` / `highBandLowerBound` / 边界值归上一档）**逐式对齐**；分位 `p05/p25/median/p75/p95`；按 `kind` 强度序 `jpeg-q80 < scale-085 < scale-070 < crop-04 < crop-12` 的全局中位数单调性（允许单点反序并明细记录）；`productionView`（floor 0.9887 / threshold 0.9944）与 `anchorView`（floor = 实测 `crossImageCeiling`、threshold = 其与 `sameImageFloor` 中点）**双视图**；带内精度（`near-duplicate` 对占该带全部对的比例）
      补勾依据（2026-10-23 实测）：`git show --stat 5ee724c` 含 `NearDuplicateBandAnalyzer.java`（868 行）；`productionView` 的 floor/threshold 直接取 `ImageSimilarityGate.NEAR_DUPLICATE_FLOOR` / `DEFAULT_THRESHOLD`（零硬编码），`bandOf(cosine,threshold)` 直接委托 `ImageSimilarityGate.bandOf`，锚视图通用式与闸门同形；`NearDuplicateBandAnalyzerTest#gateAlignmentSweep` 在 0.98–1.00 扫描 2000 点逐点比对闸门，全等。
- [x] 2.2 新增 `src/test/java/com/slz/crm/quality/NearDuplicateBandAnalyzerTest.java`：≥10 例，至少覆盖 ①边界值（0.9887 / 0.9906 / 0.9925 / 0.9944）归上一档 ②`Δ` 随阈值缩放 ③不可分退化（`crossImageCeiling >= sameImageFloor` 时 `anchorView` 不给阈值）④单调性反序可检出 ⑤空表退化（分位返回 null 而非 0）⑥带内精度分母为 0 时返回 null
      预期证据：`mvn -B -ntp test -Dtest=NearDuplicateBandAnalyzerTest` 的 Tests run / Failures
      补勾依据（2026-10-23 实测）：`mvn -B -ntp test -Dtest=NearDuplicateBandAnalyzerTest` → `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`；六项覆盖逐条有测试方法（`boundaryValuesGoToUpperBand` / `deltaScalesWithThreshold` / `inseparableAnchorViewGivesNoThreshold` / `detectsInversion` / `emptyStatsAreNull` / `nullPrecisionWhenNoSamples`）。

## 3. 真跑 IT（v2 锚的唯一写入口）
- [x] 3.1 新增 `src/test/java/com/slz/crm/quality/ImageNearDuplicateCalibrationIT.java`（failsafe）：opt-in 门控**沿用** `RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`（env 优先、回落仓库根 `.env`，与 `ImageThresholdCalibrationIT` 同序）；每图编码两次（同图下限口径）；样本对 = `same-image` 每图一对（144）+ `near-duplicate` base×5 变体（120）+ `cross` **仅 base 两两**（276，同出版物 = `same-class` / 跨出版物 = `cross-class`）；装配 `NearDuplicateBandAnalyzer` 双视图与判定树输入；**全部编码成功后一次性**写 `docs/rag-quality/image-threshold-calibration-v2.json`（`calibrationVersion = IMAGE-NEAR-DUPLICATE-CALIBRATION-2.0`，含 `corpus` / `strata` / `productionView` / `anchorView` / `nearDuplicateByKind` / `monotonicity` / `candidateFloor` / `pairs`）；单次调用失败即整体失败，**不得落半成品锚**
      补勾依据（2026-10-23 实测）：`git show --stat cd499a7` 含 `ImageNearDuplicateCalibrationIT.java`（478 行）；`git log --name-only` 里引用 v2 锚路径的只有 IT（写）/ `ImageNearDuplicateCalibrationAnchorTest`（读）/ stats 脚本（读），`src/main` 零引用；落盘语句只有一处 `Files.writeString`、位于全部编码与样本对计数断言之后。
- [x] 3.2 干跑自证（零外呼）：未 opt-in 时该 IT 走 `Assumptions` 跳过，`mvn -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=ImageNearDuplicateCalibrationIT` 输出 skipped 且 `docs/rag-quality/image-threshold-calibration-v2.json` **未被创建/未被修改**（`git status --porcelain docs/rag-quality/` 无该文件）
      补勾依据（2026-10-23 实测）：`git status --porcelain docs/rag-quality/` 在干跑后 0 行；`mvn verify` 时 failsafe 报 `Tests run: 1, Skipped: 1`（`RAG_VISION_EMBED_REAL` 未设 → `Assumptions` 跳过），锚目录零变化。
- [x] 3.3 真跑一次（Owner 已授权外呼）：`$env:RAG_VISION_EMBED_REAL='1'` 后同上命令（key 取 `.env`）→ 落 v2 锚；回报 **实际 `imageEmbeddingCalls` / `images` / `bases` / `variants` / `skips` / 耗时 / 是否有失败调用**；锚的 `generatedAt` 与本次运行时间自洽
      预期证据：真跑命令尾行 + 锚文件关键字段（`corpus` 计数、`strata.*.count`、`candidateFloor`）
      补勾依据（2026-10-23 实测）：真跑成功轮（第 6 轮）落锚 `elapsedMillis=461683`、`failedCalls=0`、`throttledRetries=0`，`corpus` = bases 24 / variants 120 / images 144 / skips 0 / `imageEmbeddingCalls=288`（= images×2），`pairs` 540 = 144+120+276；`generatedAt=2026-10-23T03:16:01.518321800Z` 与文件 mtime 同秒、早于提交 `e80d9b1` 4 分 44 秒。**该轮数据后被 §9 判定为 6 个 base 变体退化污染**，故 §9.4 重跑并覆盖同一路径。

## 4. 复算脚本与 v2 落带文档
- [x] 4.1 扩展 `scripts/rag-quality/near-duplicate-band-stats.mjs`：新增 `--anchor <path>` 与 `--expect <SAME_IMAGE,NEAR_HIGH,NEAR_MID,NEAR_LOW,CROSS_IMAGE>`（给了 `--anchor` 未给 `--expect` → 用法错误并非零退出，**不得静默通过**）；带 `--anchor` 时额外打印「stratum × band」交叉表（v2 有 `near-duplicate` 档）；**不带任何参数时逐字维持旧行为**（仍打印 `18/0/0/1/71` 与尾行 `结果：复现 v1 落带 18/0/0/1/71 ✓（EXIT=0）`）
      补勾依据（2026-10-23 实测）：`node scripts/rag-quality/near-duplicate-band-stats.mjs` → `结果：复现 v1 落带 18/0/0/1/71 ✓（EXIT=0）` EXIT=0；`--anchor` 缺 `--expect` → `[FAIL] 用法错误` EXIT=2；`--expect 1,2,3,4,5` → `CROSS_IMAGE: 实际 340 ≠ 期望 5` EXIT=1；`--anchor … --expect 193,3,1,3,340` → 尾行 `复现 v2 生产视图落带 193/3/1/3/340 ✓（EXIT=0）`。
- [x] 4.2 用脚本复算 v2 锚并新增 `docs/rag-quality/image-near-duplicate-bands-v2.md`：v1 对照表、**生产视图**与**锚视图**双落带表、各带分位、按 `kind` 的强度单调性表、`belowFloor` / `aboveThreshold` 计数与占比、未覆盖类声明（同页重复扫描）、判定树结论（自动判定，禁止手算数字）
      预期证据：`node scripts/rag-quality/near-duplicate-band-stats.mjs --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect …` 的尾行与 EXIT；文档数字与该输出逐数一致
      补勾依据（2026-10-23 实测）：首版文档 175 行、逐数抄自脚本 11 节输出（复核判据③独立复算 PASS）；**本轮按 §9.5 已整节重写**为重跑后的数据（见 9.5）。
- [x] 4.3 `docs/rag-quality/image-near-duplicate-sampling-v2.md` §8 追加 ≤3 行：本卡已真跑、新锚路径、结论指路到 bands-v2（**不改 §1–§7 任何口径**）
      补勾依据（2026-10-23 实测）：`git diff master --numstat -- docs/rag-quality/image-near-duplicate-sampling-v2.md` = `3 0`（仅追加 3 行，插在 §8 之下、§1–§7 零改动）。

## 5. 默认门禁守卫（已提交锚的结构自检）
- [x] 5.1 新增 `src/test/java/com/slz/crm/quality/ImageNearDuplicateCalibrationAnchorTest.java`（surefire，默认跑、零外呼）：校验已提交 v2 锚 ①`calibrationVersion` ②**关系式自洽**（base 去重后实际值记账，故断言关系而非魔法数）：`corpus.bases >= 20`、`nearDuplicate 对数 == bases × 5`、`same-image 对数 == images`、`cross 对数 == bases×(bases−1)/2`、`pairs.length == 三者之和` ③`stratum` 取值集合 ⊆ {`same-image`,`near-duplicate`,`same-class`,`cross-class`} ④每个 `near-duplicate` 对的 `rightKey` 前缀 = 其 `leftKey` ⑤所有 `cosine ∈ (0, 1]`
      预期证据：`mvn -B -ntp test -Dtest=ImageNearDuplicateCalibrationAnchorTest` 的 Tests run / Failures；并做一次故意破坏（改锚里一个计数）证明它会红，随后还原
      补勾依据（2026-10-23 实测）：`mvn -B -ntp test -Dtest=ImageNearDuplicateCalibrationAnchorTest` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`；故意把锚 `corpus.bases` 24→25 → `pairCountsAreRelational` 红（`expected: <120> but was: <125>`）→ `git checkout` 还原 → 复跑 6 绿。**本轮按 §9.3 已新增第 ④ 条锚级防退化断言，并重做注入-还原**（见 9.3）。

## 6. 判定树照判（禁止事后改口径）
- [x] 6.1 按 `proposal.md` §4 判定树逐支判 F0–F5，命中情况与依据写进 bands-v2 文档「判定」节；**任何分支都不改常量**；若 F1/F2/F3 命中，只写"建议另立卡 + 建议内容"，不实施
      预期证据：文档中 F0–F5 每支的命中/未命中 + 触发所依据的具体数字（引用锚字段名）
      补勾依据（2026-10-23 实测）：首轮锚判 F1 命中（`nearDuplicateBelowFloor=67/120=0.5583>0.5`）+ F4 命中（3/1/3<30），F0/F2/F3/F5 未命中；`src/main` 零 diff 即证常量未改。**F1 依据后被 §9 判为退化污染，本轮按同一口径重判**（见 9.5）。

## 7. 硬边界自证
- [x] 7.1 `git diff master -- src/main` 空输出（含 `ImageSimilarityGate.java` 全部常量）；`git diff master -- docs/rag-quality/image-threshold-calibration-v1.json docs/rag-quality/image-near-duplicate-bands-v1.md src/test/resources/rag-quality/real-pdf` 空输出；`git diff master -- frontend` 空输出
      补勾依据（2026-10-23 实测）：三条命令输出均为 0 行（`src/main` / v1 锚 + bands-v1 + `real-pdf` + `sources.json` + `RealPdfAssets` + `ImageThresholdCalibration*` / `frontend`）；本轮 §9.6 重做。
- [x] 7.2 `git status --porcelain` 无未跟踪的图片/日志/临时脚本（锚是唯一新增产物，且必须是 IT 写的）；确认未新增依赖（`pom.xml` 零 diff）
      补勾依据（2026-10-23 实测）：`git status --porcelain` 0 行；`git diff master --stat` 13 文件（9 新增 + 4 修改）无二进制；`git diff master -- pom.xml` 0 行；`docs/rag-quality/image-threshold-calibration-v2.json` 的 `generatedAt`/mtime 与 IT 真跑自洽（手工不可能造出 540 条自洽余弦）。

## 8. 全量门禁与停步回报
- [x] 8.1 **先清除 opt-in**（`Remove-Item Env:RAG_VISION_EMBED_REAL -ErrorAction SilentlyContinue`）再跑：`mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿 + `bash scripts/check-openapi-consistency.sh` EXIT=0
      预期证据：五条命令的尾行与 EXIT；baseline `--update` 后的 `surefire`/`failsafe` 计数（本卡新增 4 个 surefire 类 + 1 个 failsafe IT）
      补勾依据（2026-10-23 实测）：`mvn -B -ntp clean verify` → BUILD SUCCESS，surefire `Tests run: 2056, Failures: 0, Errors: 0, Skipped: 0`、failsafe `Tests run: 144, Failures: 0, Errors: 0, Skipped: 13`；`check-test-baseline.sh --update` EXIT=0（328 报告/2056 用例；43/144/13）；`check-test-baseline.sh` → 「回归基线门禁通过」EXIT=0；`merge-gate.sh` → 10 项全 PASS + 「== merge-gate 通过：所有已执行的子门禁绿 ==」；`check-openapi-consistency.sh` → 「前后端契约一致」EXIT=0。本轮 §9.6 重跑。
- [x] 8.2 **停步**：不 merge、不 push、不删分支；回报分支名 / commit 列表 / 门禁尾行 / 真跑实际调用数与耗时 / 判定树命中分支 / 常量零改动自证 / 未解决项（≤300 字）
      补勾依据（2026-10-23 实测）：`git rev-parse master` = `d94877c`（未变）、`git rev-parse origin/master` = `d94877c`、`git rev-list --count origin/master..master` = 0；分支 `feature/calibrate-near-dup-bands` 存在且未合并；首轮回报已交付（7 commit + 五条门禁尾行 + 288 次/461683 ms + F1+F4），未执行 merge/push。

## 9. 修订 v1.1 · 退化数据修复 + 第二次真跑（复核退回后新增，依据 `proposal.md` §9）

> 背景：首轮四判据 PASS，但 24 个 base 中 6 个的五类变体余弦逐位全等（变体坍缩），使 F1 依据失效、`candidateFloor=0.42` 作废。判定树口径不变，只修数据质量。
> **先补 §1–§8 的 17 项勾选与补勾依据（本轮已有实测可复现证据），再做本节。**

- [x] 9.1 变体栅格化**先铺白底再合成**（`fillRect` 白色）后再做五类变换；逐 base 记录像素属性（`colorModel` / `hasAlpha` / 唯一色数 / 均值、标准差）并写入锚的 `corpus.bases` 明细
      补勾依据（2026-10-23 实测）：`NearDuplicateCorpus.rgb/scale/crop` 三处均改为 `setColor(WHITE)+fillRect` 后再 `drawImage`（`git diff master` 可见）；逐 base 明细落锚 `corpus.baseDetail`（24 行 × {baseKey, colorModel, hasAlpha, uniqueColors, mean, stdDev}），脚本 §10 打印并与 `corpus.bases` 逐数对账一致。字段名说明：`corpus.bases` 保持**计数**（既有复算契约），明细放 `corpus.baseDetail`。
- [x] 9.2 退化 base 剔除并登记：合成后唯一色数 ≤ 1 的 base 不参与语料，写进 `corpus.excludedBases`（含 `key` / 原因 / 唯一色数）；**不得静默保留或丢弃**；实际 base / 变体 / 图数按剔除后记账
      补勾依据（2026-10-23 实测）：判据在 `NearDuplicateCorpus.generate()`（`uniqueColors <= 1` → `ExcludedBase`，且不生成其变体），落锚 `corpus.excludedBases` + `excludedBaseCount`；本轮实测**剔除 0 个**——24 个 base 唯一色数 57–268051，原 6 个可疑 base 修复后为 83/83/83/83/88/89（`mean 246.85–250.18`、`stdDev 14.18–20.29`），无一是纯色图，故语料未收缩（24/120/144）。`documents[].taken` 之和 = `corpus.bases` 由 IT 断言；空数组 + 0 计数即「未静默保留、未静默丢弃」的留痕。
- [x] 9.3 防退化守卫（surefire，默认跑、零外呼）：`NearDuplicateCorpusTest` 增 ①每 base 唯一色数 > 1 ②同 base 五变体两两 SHA-256 不同 ③变体与 base 字节不同；`ImageNearDuplicateCalibrationAnchorTest` 增 ④任一 base 的五类余弦不得全等（本轮缺陷的锚级回归检查）
      预期证据：三条新断言各自的失败注入-还原记录（故意把某 base 变体置同字节 → 必红 → 还原），与 `mvn -B -ntp test -Dtest=NearDuplicateCorpusTest+ImageNearDuplicateCalibrationAnchorTest` 的 Tests run
      补勾依据（2026-10-23 实测，四条均做注入-还原各一次，明细见 bands-v2 §11 表格）：① `scale()` 去掉 `drawImage` → 红（`变体白底合成后像素全同（唯一色数 = 1）: nist-sp800-101r1-p1-b1@scale-085`）→ 还原绿；② `scale-070` 改用 0.85 系数 → 红（`expected: <5> but was: <4>`）→ 还原绿；③ 变体字节改 base 字节 → 红（`变体与 base 字节相同…@jpeg-q80`）→ 还原绿；④ 锚里把 `doe-renewable-energy-framework-p4-b5` 五余弦改同值 → 红（`…→ 0.9823611815125848 × 5`）→ 还原绿（文件 hash 与注入前一致）。还原后：`-Dtest=NearDuplicateCorpusTest` = `Tests run: 8, Failures: 0`；`-Dtest=ImageNearDuplicateCalibrationAnchorTest` = `Tests run: 7, Failures: 0`。守卫④在**修复前的旧锚**上直接红并点名 6 个坍缩 base，证明它挡得住本轮缺陷。
- [x] 9.4 第二次真跑（覆盖同一锚路径；预计 base 18 / 变体 90 / 图 108 / 外呼 216 次）：回报实际计数、耗时、失败调用数，并确认 `corpus.imageEmbeddingCalls` 与 `images×2` 相等
      补勾依据（2026-10-23 实测）：`$env:RAG_VISION_EMBED_REAL='1'` 真跑 → `锚已落盘 …（images=144 calls=288 耗时=469111 ms）`，`Tests run: 1, Failures: 0, Errors: 0`；锚 `corpus` = bases **24** / variants **120** / images **144** / skips 0 / `imageEmbeddingCalls` **288**（= 144×2，脚本 §10 机械校验通过）/ `failedCalls=0` / `throttledRetries=0` / `paceMillis=1100`。**实际未收缩**（退化剔除 0 个，见 9.2），故外呼是 288 而非预估 216；`generatedAt=2026-10-23T06:01:52.956643300Z` 与本轮 mtime 同轮次。
- [x] 9.5 用脚本重算并**整节重写** `image-near-duplicate-bands-v2.md`：双视图落带表、`excludedBases` 明细与像素属性、F0–F5 重新照判、`candidateFloor` 按 §9.5 处置（F1 不命中则一律 `null` 并注明原因）、§1.1 台账与锚 `corpus.imageEmbeddingCalls` 显式对账（累计 720 次的作废轮说明保留）
      补勾依据（2026-10-23 实测）：`node … --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect 207,3,1,6,323` → 尾行 `结果：复现 v2 生产视图落带 207/3/1/6/323 ✓（EXIT=0）`，§11「锚内已存块与本次复算一致」+ §10「语料口径自洽」；文档 15 节全部逐数抄该输出（双视图 207/3/1/6/323 与 202/2/3/3/330、交叉表、分位、按 kind 单调性、F0–F5、像素属性表）。`candidateFloor` 落锚为 `null`（F1 未命中 50/120=0.4167），脚本同样复算为 null 并注明「p05 仅作分布参考」。§1.1 台账累计 **1008** 次（144+288+288+288），与锚只记单次成功运行 288 次的区别写在同一节。无参脚本仍 `18/0/0/1/71 ✓（EXIT=0）`。
- [x] 9.6 重跑门禁（清 opt-in 后 `clean verify` + baseline `--update` + baseline check + `merge-gate.sh` + `check-openapi-consistency.sh`）并重做 §7 硬边界自证；仍**不 merge、不 push**，回报 ≤300 字
      补勾依据（2026-10-23 实测）：`Remove-Item Env:RAG_VISION_EMBED_REAL` 后五条门禁全绿——`mvn -B -ntp clean verify` BUILD SUCCESS（surefire `Tests run: 2059, Failures: 0, Errors: 0, Skipped: 0`、failsafe `Tests run: 144, Failures: 0, Errors: 0, Skipped: 13`、12:43 min）；`check-test-baseline.sh --update` EXIT=0（328/2059/0 与 43/144/13，`source-revision=1fad351`）；`check-test-baseline.sh` →「回归基线门禁通过」EXIT=0；`merge-gate.sh` 十项子门禁全 PASS（unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline，实测 PMD 0 violations、SpotBugs High=12 与 12 条豁免双射）→「== merge-gate 通过 ==」EXIT=0；`check-openapi-consistency.sh` →「前后端契约一致」EXIT=0。硬边界重做：`git diff master -- src/main` 0 行、v1 锚/bands-v1/sources.json/real-pdf/RealPdfAssets/ImageThresholdCalibration*/pom.xml/frontend 均 0 行、`git status --porcelain` 仅本卡文件（merge-gate 顺带重生成的 `frontend/typed-router.d.ts` 已 `git checkout --` 还原，还原后 frontend 0 行）、无二进制。`master` 仍 `d94877c`，未 merge、未 push、未删分支。
