# 任务清单 · calibrate-near-dup-bands

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。
> 档位：**A 档**（真跑 + 外呼 + 定标锚改写）。复核四判据见 `proposal.md` §8。
> 顺序纪律：**§1 → §2 → §3 → §4 → §5 → §6 → §7 → §8**（§3 真跑前必须先用 §1/§2 的默认测试证明语料与落带口径正确，否则一次外呼就是白烧）。
> 硬边界（全卡有效）：`src/main/**` 零 diff；`frontend/**` 零改动；v1 锚 / bands-v1 / `sources.json` / `RealPdfAssets` / `ImageThresholdCalibration*` 冻结；不下载新语料、不新增二进制；不 merge、不 push。

## 1. 立项与变体语料（零外呼）
- [ ] 1.1 从 master 检出分支 `feature/calibrate-near-dup-bands`，首个 commit 提交本卡 `openspec/changes/calibrate-near-dup-bands/{proposal,tasks}.md`（`docs(openspec): add calibrate-near-dup-bands card`）
- [ ] 1.2 新增 `src/test/java/com/slz/crm/quality/NearDuplicateCorpus.java`：base 选取（NIST 101r1 前 **8** 张 / NIST 183 前 **4** 张 / DOE 前 **12** 张，经 `RealPdfAssets.extract(pdf, n)`）+ 按 SHA-256 去重（去重后实际值记账，**不得凑数**）+ 5 类变体（`jpeg-q80` / `scale-085` / `scale-070` / `crop-04` / `crop-12`，定义见 `proposal.md` §2）；变体最短边 < 48px 者跳过并进 `skips()`；**无随机源、无时间戳**
- [ ] 1.3 新增 `src/test/java/com/slz/crm/quality/NearDuplicateCorpusTest.java`（surefire，零外呼）：断言 `bases().size()`、`variants().size()`、`images()` 计数与 `skips()` 自洽；同 base 两次生成**字节相等**；每变体 SHA-256 必与 base 不同；每变体 `kind` 与 `baseKey` 可回指；不得出现网络/Clock 依赖
      预期证据：`mvn -B -ntp test -Dtest=NearDuplicateCorpusTest` 的 Tests run 与实测计数（bases / variants / skips / images）

## 2. 落带分析纯函数（零外呼）
- [ ] 2.1 新增 `src/test/java/com/slz/crm/quality/NearDuplicateBandAnalyzer.java`：`labelOf(cosine, threshold)` / `bandOf(cosine, threshold)` 与 `ImageSimilarityGate`（`effectiveFloor` / `lowBandUpperBound` / `highBandLowerBound` / 边界值归上一档）**逐式对齐**；分位 `p05/p25/median/p75/p95`；按 `kind` 强度序 `jpeg-q80 < scale-085 < scale-070 < crop-04 < crop-12` 的全局中位数单调性（允许单点反序并明细记录）；`productionView`（floor 0.9887 / threshold 0.9944）与 `anchorView`（floor = 实测 `crossImageCeiling`、threshold = 其与 `sameImageFloor` 中点）**双视图**；带内精度（`near-duplicate` 对占该带全部对的比例）
- [ ] 2.2 新增 `src/test/java/com/slz/crm/quality/NearDuplicateBandAnalyzerTest.java`：≥10 例，至少覆盖 ①边界值（0.9887 / 0.9906 / 0.9925 / 0.9944）归上一档 ②`Δ` 随阈值缩放 ③不可分退化（`crossImageCeiling >= sameImageFloor` 时 `anchorView` 不给阈值）④单调性反序可检出 ⑤空表退化（分位返回 null 而非 0）⑥带内精度分母为 0 时返回 null
      预期证据：`mvn -B -ntp test -Dtest=NearDuplicateBandAnalyzerTest` 的 Tests run / Failures

## 3. 真跑 IT（v2 锚的唯一写入口）
- [ ] 3.1 新增 `src/test/java/com/slz/crm/quality/ImageNearDuplicateCalibrationIT.java`（failsafe）：opt-in 门控**沿用** `RAG_VISION_EMBED_REAL=1` + `DASHSCOPE_API_KEY`（env 优先、回落仓库根 `.env`，与 `ImageThresholdCalibrationIT` 同序）；每图编码两次（同图下限口径）；样本对 = `same-image` 每图一对（144）+ `near-duplicate` base×5 变体（120）+ `cross` **仅 base 两两**（276，同出版物 = `same-class` / 跨出版物 = `cross-class`）；装配 `NearDuplicateBandAnalyzer` 双视图与判定树输入；**全部编码成功后一次性**写 `docs/rag-quality/image-threshold-calibration-v2.json`（`calibrationVersion = IMAGE-NEAR-DUPLICATE-CALIBRATION-2.0`，含 `corpus` / `strata` / `productionView` / `anchorView` / `nearDuplicateByKind` / `monotonicity` / `candidateFloor` / `pairs`）；单次调用失败即整体失败，**不得落半成品锚**
- [ ] 3.2 干跑自证（零外呼）：未 opt-in 时该 IT 走 `Assumptions` 跳过，`mvn -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=ImageNearDuplicateCalibrationIT` 输出 skipped 且 `docs/rag-quality/image-threshold-calibration-v2.json` **未被创建/未被修改**（`git status --porcelain docs/rag-quality/` 无该文件）
- [ ] 3.3 真跑一次（Owner 已授权外呼）：`$env:RAG_VISION_EMBED_REAL='1'` 后同上命令（key 取 `.env`）→ 落 v2 锚；回报 **实际 `imageEmbeddingCalls` / `images` / `bases` / `variants` / `skips` / 耗时 / 是否有失败调用**；锚的 `generatedAt` 与本次运行时间自洽
      预期证据：真跑命令尾行 + 锚文件关键字段（`corpus` 计数、`strata.*.count`、`candidateFloor`）

## 4. 复算脚本与 v2 落带文档
- [ ] 4.1 扩展 `scripts/rag-quality/near-duplicate-band-stats.mjs`：新增 `--anchor <path>` 与 `--expect <SAME_IMAGE,NEAR_HIGH,NEAR_MID,NEAR_LOW,CROSS_IMAGE>`（给了 `--anchor` 未给 `--expect` → 用法错误并非零退出，**不得静默通过**）；带 `--anchor` 时额外打印「stratum × band」交叉表（v2 有 `near-duplicate` 档）；**不带任何参数时逐字维持旧行为**（仍打印 `18/0/0/1/71` 与尾行 `结果：复现 v1 落带 18/0/0/1/71 ✓（EXIT=0）`）
- [ ] 4.2 用脚本复算 v2 锚并新增 `docs/rag-quality/image-near-duplicate-bands-v2.md`：v1 对照表、**生产视图**与**锚视图**双落带表、各带分位、按 `kind` 的强度单调性表、`belowFloor` / `aboveThreshold` 计数与占比、未覆盖类声明（同页重复扫描）、判定树结论（自动判定，禁止手算数字）
      预期证据：`node scripts/rag-quality/near-duplicate-band-stats.mjs --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect …` 的尾行与 EXIT；文档数字与该输出逐数一致
- [ ] 4.3 `docs/rag-quality/image-near-duplicate-sampling-v2.md` §8 追加 ≤3 行：本卡已真跑、新锚路径、结论指路到 bands-v2（**不改 §1–§7 任何口径**）

## 5. 默认门禁守卫（已提交锚的结构自检）
- [ ] 5.1 新增 `src/test/java/com/slz/crm/quality/ImageNearDuplicateCalibrationAnchorTest.java`（surefire，默认跑、零外呼）：校验已提交 v2 锚 ①`calibrationVersion` ②**关系式自洽**（base 去重后实际值记账，故断言关系而非魔法数）：`corpus.bases >= 20`、`nearDuplicate 对数 == bases × 5`、`same-image 对数 == images`、`cross 对数 == bases×(bases−1)/2`、`pairs.length == 三者之和` ③`stratum` 取值集合 ⊆ {`same-image`,`near-duplicate`,`same-class`,`cross-class`} ④每个 `near-duplicate` 对的 `rightKey` 前缀 = 其 `leftKey` ⑤所有 `cosine ∈ (0, 1]`
      预期证据：`mvn -B -ntp test -Dtest=ImageNearDuplicateCalibrationAnchorTest` 的 Tests run / Failures；并做一次故意破坏（改锚里一个计数）证明它会红，随后还原

## 6. 判定树照判（禁止事后改口径）
- [ ] 6.1 按 `proposal.md` §4 判定树逐支判 F0–F5，命中情况与依据写进 bands-v2 文档「判定」节；**任何分支都不改常量**；若 F1/F2/F3 命中，只写"建议另立卡 + 建议内容"，不实施
      预期证据：文档中 F0–F5 每支的命中/未命中 + 触发所依据的具体数字（引用锚字段名）

## 7. 硬边界自证
- [ ] 7.1 `git diff master -- src/main` 空输出（含 `ImageSimilarityGate.java` 全部常量）；`git diff master -- docs/rag-quality/image-threshold-calibration-v1.json docs/rag-quality/image-near-duplicate-bands-v1.md src/test/resources/rag-quality/real-pdf` 空输出；`git diff master -- frontend` 空输出
- [ ] 7.2 `git status --porcelain` 无未跟踪的图片/日志/临时脚本（锚是唯一新增产物，且必须是 IT 写的）；确认未新增依赖（`pom.xml` 零 diff）

## 8. 全量门禁与停步回报
- [ ] 8.1 **先清除 opt-in**（`Remove-Item Env:RAG_VISION_EMBED_REAL -ErrorAction SilentlyContinue`）再跑：`mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿 + `bash scripts/check-openapi-consistency.sh` EXIT=0
      预期证据：五条命令的尾行与 EXIT；baseline `--update` 后的 `surefire`/`failsafe` 计数（本卡新增 4 个 surefire 类 + 1 个 failsafe IT）
- [ ] 8.2 **停步**：不 merge、不 push、不删分支；回报分支名 / commit 列表 / 门禁尾行 / 真跑实际调用数与耗时 / 判定树命中分支 / 常量零改动自证 / 未解决项（≤300 字）

## 9. 修订 v1.1 · 退化数据修复 + 第二次真跑（复核退回后新增，依据 `proposal.md` §9）

> 背景：首轮四判据 PASS，但 24 个 base 中 6 个的五类变体余弦逐位全等（变体坍缩），使 F1 依据失效、`candidateFloor=0.42` 作废。判定树口径不变，只修数据质量。
> **先补 §1–§8 的 17 项勾选与补勾依据（本轮已有实测可复现证据），再做本节。**

- [ ] 9.1 变体栅格化**先铺白底再合成**（`fillRect` 白色）后再做五类变换；逐 base 记录像素属性（`colorModel` / `hasAlpha` / 唯一色数 / 均值、标准差）并写入锚的 `corpus.bases` 明细
- [ ] 9.2 退化 base 剔除并登记：合成后唯一色数 ≤ 1 的 base 不参与语料，写进 `corpus.excludedBases`（含 `key` / 原因 / 唯一色数）；**不得静默保留或丢弃**；实际 base / 变体 / 图数按剔除后记账
- [ ] 9.3 防退化守卫（surefire，默认跑、零外呼）：`NearDuplicateCorpusTest` 增 ①每 base 唯一色数 > 1 ②同 base 五变体两两 SHA-256 不同 ③变体与 base 字节不同；`ImageNearDuplicateCalibrationAnchorTest` 增 ④任一 base 的五类余弦不得全等（本轮缺陷的锚级回归检查）
      预期证据：三条新断言各自的失败注入-还原记录（故意把某 base 变体置同字节 → 必红 → 还原），与 `mvn -B -ntp test -Dtest=NearDuplicateCorpusTest+ImageNearDuplicateCalibrationAnchorTest` 的 Tests run
- [ ] 9.4 第二次真跑（覆盖同一锚路径；预计 base 18 / 变体 90 / 图 108 / 外呼 216 次）：回报实际计数、耗时、失败调用数，并确认 `corpus.imageEmbeddingCalls` 与 `images×2` 相等
- [ ] 9.5 用脚本重算并**整节重写** `image-near-duplicate-bands-v2.md`：双视图落带表、`excludedBases` 明细与像素属性、F0–F5 重新照判、`candidateFloor` 按 §9.5 处置（F1 不命中则一律 `null` 并注明原因）、§1.1 台账与锚 `corpus.imageEmbeddingCalls` 显式对账（累计 720 次的作废轮说明保留）
- [ ] 9.6 重跑门禁（清 opt-in 后 `clean verify` + baseline `--update` + baseline check + `merge-gate.sh` + `check-openapi-consistency.sh`）并重做 §7 硬边界自证；仍**不 merge、不 push**，回报 ≤300 字
