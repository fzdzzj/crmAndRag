# tasks · label-sheet-near-dup-relevance（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（命令 + 实测值 + 可 grep 的符号/字段名）。三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R8 / 决策 D1–D5 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **零外呼**：不设任何 `*_REAL`、不启动 Spring 容器、不调 provider；只用冻结锚 + 程序化语料 + 生产纯函数。
- `src/main` / `pom.xml` / `.github/**` 零 diff；既有 46 张锚**零字节变动**；不改任何生产常量。
- 锚写模式 `-Dlabel.sheet.write=true`；CSV 与 README **纯 LF、单尾换行**。
- **执行者不得填写标注列**（R3/D4）：`relevance` / `isNearDuplicate` 只允许 Owner 填。

## A · 标注包（R1/R2）

- [x] 1. 重建 6 查询 × top-5 候选（生产类直构，语料 `NearDuplicateCorpus`），每行给出：行 id `q{i}:{chunkId}`、查询原文、候选可读文本、文件名/页号、**现行名次**
      依据：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest "-Dlabel.sheet.write=true"` → `[label-sheet] rows=30 missingText=0`；`reconcile()` 断言 `reconciled=15`/`imageRouteRowsRebuilt=15`（`Reconstruction`、`RebuiltRow`）；已打印列 `rowId/queryIndex/queryText/candidateText/fileName/pageNo/currentRank`。候选身份/顺序取冻结 `near-dup-band-entry-v1.json#candidates`（文本路无查询向量，零外呼不可重建），`cos/tier/band/r` 全部本地重算并逐条对账。
- [x] 2. 生成 `docs/rag-quality/labels/near-dup-relevance-v1.csv`：30 行 + 表头 + 两列**空位**（`relevance` / `isNearDuplicate`）+ `annotator` / `annotatedOn` 空位
      依据：落盘 CSV `parseCsv` 记录数 31（1 表头 + 30 数据），逐行 11 字段；`COLUMNS` 常量与表头一致；实测 `bytes=11277`、末 4 列全空。
- [x] 3. **盲标**：CSV 内**不得**出现 `cos` / `tier` / `band` 列，也不得出现「可疑 / 应压制 / 近重复」等提示字样（R2；守卫断言列名白名单）
      依据：守卫 `assertBlindSheet` 断言表头 `== COLUMNS` 且逐列 `lower.contains("cos"|"tier"|"band") == false`；候选文本扫描「近重复/可疑/压制」0 命中；实测表头 11 列无三类字样。
- [x] 4. 生成一页说明 `…-README.md`：两列取值含义 + 「看不出来就填 `U`」+ 不许参考任何锚
      依据：`docs/rag-quality/labels/near-dup-relevance-v1-README.md`（38 行，纯 LF）；含 `relevance` `2/1/0`、`isNearDuplicate` `Y/N/U`、`看不出来（信息不足就填 U`、`不要参考任何锚或分数`。
- [x] 5. 确定性：同一提交连跑两次，CSV 与 README **逐字节一致**
      依据：默认复跑 `[label-sheet] 默认复跑逐字节一致：…near-dup-relevance-v1.csv / …-README.md`；守卫 `assertArrayEquals(first.csvBytes(), second.csvBytes())` 与 `assertArrayEquals(first.readmeBytes(), second.readmeBytes())` 全绿。

## B · 打分器（R4/R5）

- [x] 6. 完整性校验：空值 / 越界 / `annotator` 空或命中 `agent|assistant|ai|model` / 日期缺失 / 行 id 不符 / 重复行 ⇒ 逐条报出行号并失败（R3）
      依据：`scorerRejectsIllegalLabels` 对 8 个定点破坏（`blank-relevance`/`relevance-range`/`near-dup-value`/`agent-annotator`/`blank-date`/`bad-date`/`row-id`/`duplicate-row`）全部 `assertThrows(IllegalStateException)` 且 `validate()` 返回「第 N 行（rowId=…）」；`Tests run: 3, Failures: 0`。
- [x] 7. 三情景并列打分：① 现状 `0.982` 口径 ② `δ=0`（无降权）③ `0.90` 口径；每情景给 `precision@5`、`NDCG@5`、「`Y` 候选被压制」一致率
      依据：`SCENARIO_KEYS = [current-0982, no-downweight, floor-090]`；`score()` 产出 `ScenarioScore(precisionAt5, ndcgAt5, ySuppressionRate, ySuppressed, yTotal)`；`scorerProducesThreeScenarios` 断言 `precisionAt5=0.6`、`ndcgAt5∈[0,1]`、`yTotal=6`、`no-downweight.ySuppressionRate=0.0`。
- [x] 8. 三情景**不排序、不给推荐**（R4）：锚内不得出现「建议 / 推荐 / 应下调 / 倾向」（守卫词表）
      依据：守卫 `TENDENCY_WORDS` 9 词（建议/推荐/应下调/应当下调/倾向/更优/更好/更差/应该）扫描 锚 JSON + md + README，0 命中；锚 `numericHygiene.scenariosSorted=false`、`scenariosParallel=true`；`scenarios[].key` 顺序固定。
- [x] 9. 标注人 + 日期 + CSV sha256 入锚；同一 CSV 两次打分结果逐字节一致
      依据：锚 `sheet.annotator` / `sheet.annotatedOn` / `sheet.sha256`（`RealPdfAssets.sha256`）；守卫用同一份落盘 CSV 连算两次 `buildAnchor`，`assertArrayEquals(canonicalBytes(withoutRuntime(a1)), …a2)` 全绿。

## C · 锚与守卫（R6/R7/R8）

- [x] 10. 锚 `docs/rag-quality/label-sheet-near-dup-relevance-v1.json` + `.md`（≤40 行）；未填标签时 `labelsStatus=AWAITING-HUMAN-LABELS` 且三情景字段为空（**不得**用假数据填充）
      依据：锚 13 个顶层键；实测 `labelsStatus=AWAITING-HUMAN-LABELS`、`scenarios=0`；守卫 `hasField(anchor,"precisionAt5")==false` 且 `hasField(anchor,"ndcgAt5")==false`；md `mdLines=37`。
- [x] 11. 守卫：R1 逐字节确定性、R2 盲标白名单、R3 拒绝规则逐条、R4 无倾向措辞、R5 `annotator` 非 agent、R7 文本缺失超 3 行即 `UNDETERMINED`、R8 无外推措辞、md ≤40 行
      依据：`LabelSheetNearDupRelevanceGuardTest` 1 测试全绿（`Tests run: 4, Failures: 0`）；断言项含 `AGENT_LIKE`、`textCoverage.missingRows<=3`、`用户偏好/生产相关性/全局质量` 0 命中、`mdLines=37`、`labels/` 目录仅两产物。
- [x] 12. 边界：`git diff master --stat -- src/main pom.xml .github` 空；既有锚零 `M`；`git diff --check` 干净
      依据：三条命令实测均为空输出；`git status --porcelain -- docs/rag-quality` 仅 4 个新增路径（`labels/`、锚 json、锚 md），无既有锚 `M`。
- [x] 13. 若 30 行里可读文本缺失 > 3 行 ⇒ 停手记 `UNDETERMINED` 并写 `proposal §8`（D5）
      依据：`textCoverage.missingRows=0`（`rowsWithText=30`）⇒ 未触发停手；`buildBundle()` 内 `missingRows<=3` 断言成立。
- [-] 14. 若三情景指标完全打平 ⇒ 如实记录并停手上报（`proposal §8` 预置），不许改口径造差异
      依据：标签未填，三情景按设计未产出（`scenarios=[]`、无任何分数字段），本项待 Owner 填完 CSV 后复跑 `evaluate()` 再判定；打分器不排序、不造差异（`TENDENCY_WORDS` 0 命中）。
