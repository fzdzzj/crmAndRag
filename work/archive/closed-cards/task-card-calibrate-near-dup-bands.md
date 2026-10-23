# 任务卡 · calibrate-near-dup-bands

- **卡名**：`calibrate-near-dup-bands`（近重复三档定标真跑 v2）
- **档位**：**A 档**（真跑 + 外呼 + 定标锚改写）
- **分支**：`feature/calibrate-near-dup-bands`（从 权威树 `master@d94877c` 检出）
- **规格真相源**：`openspec/changes/calibrate-near-dup-bands/{proposal.md,tasks.md}`
- **Owner 授权**：「推真跑定标，语料你自己找，授权外呼」（2026-10-23）。本授权覆盖**一次**真跑（≈288 次视觉 embedding 外呼；修订 v1.1 后的第二次真跑因语料收缩只为 ≈216 次，仍在同一授权量级内）；**不覆盖改权重常量、merge、push**。
- **状态**：首轮已跑 + A 档复核四判据 PASS，但**主 Agent 裁决退回修复**（退化 base 6 个导致 F1 依据失效、`candidateFloor=0.42` 作废），待修订 v1.1（`proposal.md` §9 / `tasks.md` §9）二次真跑。

## 1. 一句话目标

把 `ImageSimilarityGate` 三档降权 `0.75/0.6/0.5` 从"未经样本校准"变成**有数的结论**：
造出同源近重复对，第一次看清间隔带 `[0.9887, 0.9944)` 里装的是真重复还是异图，并落 v2 锚 + v2 落带文档。
**结论可能是缺陷**（带不可达 / 带内装异图 / 单阈值前提失效），如实写，不改常量。

## 2. 硬边界（越界即退回）

| # | 边界 | 判别命令 |
|---|---|---|
| B1 | `src/main/**` 零 diff（含 `ImageSimilarityGate` 全部常量） | `git diff master --stat -- src/main` 空 |
| B2 | v1 资产冻结：v1 锚、`bands-v1.md`、`sources.json`、三份 PDF、`RealPdfAssets`、`ImageThresholdCalibration*` | `git diff master -- docs/rag-quality/image-threshold-calibration-v1.json docs/rag-quality/image-near-duplicate-bands-v1.md src/test/resources/rag-quality/real-pdf src/test/java/com/slz/crm/quality/RealPdfAssets.java src/test/java/com/slz/crm/quality/ImageThresholdCalibrationIT.java` 空 |
| B3 | 不下载语料、不新增二进制、不新增依赖 | `git status --porcelain` 无图片；`pom.xml` 零 diff |
| B4 | 不 merge / 不 push / 不删分支 | 分支仍在，`master` 未动 |
| B5 | v1 复算自检不得被破坏 | `node scripts/rag-quality/near-duplicate-band-stats.mjs`（无参数）仍 `18/0/0/1/71` 且 EXIT=0 |

## 3. 交付物

1. `NearDuplicateCorpus` / `NearDuplicateBandAnalyzer`（新，零外呼纯逻辑）+ 各自 surefire 单测。
2. `ImageNearDuplicateCalibrationIT`（新，opt-in 门控）——**v2 锚唯一写入口**。
3. `docs/rag-quality/image-threshold-calibration-v2.json`（IT 产物，禁手改）。
4. `docs/rag-quality/image-near-duplicate-bands-v2.md`（双视图落带 + 判定树结论 + 未覆盖类）。
5. `scripts/rag-quality/near-duplicate-band-stats.mjs` 加 `--anchor` / `--expect`（无参数行为逐字不变）。
6. `ImageNearDuplicateCalibrationAnchorTest`（默认跑，守已提交锚的结构常量）。
7. `image-near-duplicate-sampling-v2.md` §8 追加 ≤3 行指针。

## 4. 语料（Owner 已授权"自己找"）

三份已登记公有领域 PDF（`sources.json`）取 base：NIST 101r1 **8** 张 / NIST 183 **4** 张 / DOE **12** 张，SHA-256 去重 ⇒ 期望 **24**；每 base 5 类变体（`jpeg-q80` / `scale-085` / `scale-070` / `crop-04` / `crop-12`）⇒ 120 变体 ⇒ **144 图 / 288 次外呼**。
变体在内存生成、只落余弦，仓库零二进制增长。

## 5. 判定树（跑前固定，见 proposal §4）

F0 带可达且每带 ≥30 ⇒ 三档记"有样本支撑的现值"；F1 `belowFloor > 50%` ⇒ 近似带基本不可达（缺陷证据 + `candidateFloor = nearDuplicate.p05`）；F2 `anchorView` 不可分 ⇒ 单阈值前提失效；F3 带 ≥30 但带内近重复占比 < 0.5 ⇒ 降权方向需重议；F4 某带 < 30 ⇒ 该带权重维持现值；F5 锚视图样本多于生产视图 ⇒ 记录 floor 取值挡掉真重复的证据强度。
**任何分支都不改常量**；需改则"建议另立卡"。

## 6. 验收

`tasks.md` §1–§8 全勾 + 六条门禁绿（`clean verify` / baseline update+check / merge-gate / openapi consistency）+ 真跑实跑一次（回报实际调用数、耗时、失败调用数）+ 硬边界 B1–B5 自证 + 停步回报（不 merge、不 push）。

## 7. 已知环境坑

- 门禁跑前**必须**清除 `RAG_VISION_EMBED_REAL`，否则 `clean verify` 会把真跑 IT 带上（又烧一次外呼）。
- 本地 `pnpm build` 有宿主 esbuild `Access is denied` 历史坑；本卡不碰前端，无需跑。
- PowerShell 里 `.NET` 相对路径按进程 CWD 解析 → 读锚/写证据一律用绝对路径。
- 提交信息单行 ASCII 风格：`feat(test): …` / `docs(rag-quality): …` / `test(guard): …`。
- 外呼节奏：首轮实测 429 限流与连接超时各踩过一次 ⇒ 保留 `连接超时 60 s` + `调用节奏 1100 ms` + `429 有界退避` 三条设置，别为了提速去掉。

## 8. 修订 v1.1（复核退回后）

- **R1 数据质量（阻断）**：变体栅格化先铺白底；合成后唯一色数 ≤ 1 的 base 判为退化、剔除并登记 `corpus.excludedBases`；加两条默认跑守卫（像素级 + **锚级"同 base 五类余弦不得全等"**）。
- **R2 结论作废**：`candidateFloor = 0.4199…` 由最退化的 2 个 base 算出，若实施等于把 floor 从 0.9887 降到 0.42（几乎全部图像命中改为降权而非过滤）⇒ 一律作废；F1 不命中时 `candidateFloor` 写 `null`。
- **R3 口径不变**：判定树 F0–F5 一字不改，仅明确 `nearDuplicate` 对的分母 = 有效 base 的变体对（写在第二次真跑之前）。
- **R4 流程**：`tasks.md` §1–§8 的 17 项必须补勾并带 6 空格补勾依据（首轮 0 勾选，属流程 FAIL）。
