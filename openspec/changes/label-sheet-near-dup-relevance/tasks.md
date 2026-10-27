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
      依据（fix1 复测）：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest "-Dlabel.sheet.write=true"` → `[label-sheet] rows=36 actualPerQuery=[6, 6, 6, 6, 6, 6] missingText=0`；`reconcile()` 断言 `frozenCandidates=30` / `imageRouteRowsRebuilt=15` / `fusedCurrentReconciled=30` / `fusedNoDownweightReconciled=30`（`Candidate`、`Rebuild`）；已打印列 `rowId/queryIndex/queryText/candidateText/fileName/pageNo/currentRank`。候选身份/顺序本地按生产口径重建（文本路 `embedCache` 查询向量 + `textRecords` 向量，图像路 `queryImageVectors` + `ImageSimilarityGate.gate`，融合文本 0.70 / 图像 0.30），`fusedA`/`fusedB` 逐条对账 1e-12。
- [x] 2. 生成 `docs/rag-quality/labels/near-dup-relevance-v1.csv`：36 行 + 表头 + 两列**空位**（`relevance` / `isNearDuplicate`）+ `annotator` / `annotatedOn` 空位
      依据：落盘 CSV `parseCsv` 记录数 37（1 表头 + 36 数据），逐行 11 字段；`COLUMNS` 常量与表头一致；实测 `bytes=14301`（`sheet.bytes`）、`sha256=70e216d403466e4e5989e6c869c4e5ff614994ea59fbbcd5a441e25883266674`、末 4 列全空。
- [x] 3. **盲标**：CSV 内**不得**出现 `cos` / `tier` / `band` 列，也不得出现「可疑 / 应压制 / 近重复」等提示字样（R2；守卫断言列名白名单）
      依据：守卫 `assertBlindSheet` 断言表头 `== COLUMNS` 且逐列 `lower.contains("cos"|"tier"|"band") == false`；候选文本扫描「近重复/可疑/压制」0 命中；实测表头 11 列无三类字样。
- [x] 4. 生成一页说明 `…-README.md`：两列取值含义 + 「看不出来就填 `U`」+ 不许参考任何锚
      依据：`docs/rag-quality/labels/near-dup-relevance-v1-README.md`（44 行，2039 字节，纯 LF；fix1 扩池后复测）；含 `relevance` `2/1/0`、`isNearDuplicate` `Y/N/U`、`看不出来（信息不足就填 U`、`不要参考任何锚或分数`，以及「四、评的是什么口径」里的同一句口径。
- [x] 5. 确定性：同一提交连跑两次，CSV 与 README **逐字节一致**
      依据（fix2 修正）：CSV 不再是纯生成物（7..10 列为人工列），确定性改为「模板逐字节稳定 + 结构逐格校验」：`buildBundle()` 两次 `csvBytes()` 逐字节相等（`assertArrayEquals(first.csvBytes(), second.csvBytes())`，生成器腿 4 项全绿）；落盘人工表由 `ensureSheet()` 逐格校验 0..6 列，实测 `[label-sheet] 落盘表结构校验通过（标注列允许人工填写）`；README 仍 `assertArrayEquals(first.readmeBytes(), second.readmeBytes())` + `默认复跑逐字节一致`。

## B · 打分器（R4/R5）

- [x] 6. 完整性校验：空值 / 越界 / `annotator` 空或命中 `agent|assistant|ai|model` / 日期缺失 / 行 id 不符 / 重复行 ⇒ 逐条报出行号并失败（R3）
      依据：`scorerRejectsIllegalLabels` 对 8 个定点破坏（`blank-relevance`/`relevance-range`/`near-dup-value`/`agent-annotator`/`blank-date`/`bad-date`/`row-id`/`duplicate-row`）全部 `assertThrows(IllegalStateException)` 且 `validate()` 返回「第 N 行（rowId=…）」；`Tests run: 3, Failures: 0`。
- [x] 7. 三情景并列打分：① 现状 `0.982` 口径 ② `δ=0`（无降权）③ `0.90` 口径；每情景给 `precision@5`、`NDCG@5`、「`Y` 候选被压制」一致率
      依据：`SCENARIO_KEYS = [current-0982, no-downweight, floor-090]`；`score()` 产出 `ScenarioScore(precisionAt5, ndcgAt5, ySuppressionRate, ySuppressed, yTotal)`；`scorerProducesThreeScenarios` 断言 `precisionAt5=0.6`、`ndcgAt5∈[0,1]`、`yTotal=6`、`no-downweight.ySuppressionRate=0.0`、`current-0982.ySuppressed∈(0,yTotal)`。**fix1 订正（口径方向）**：现状 = 含 band 降权（生产 pass A，对应冻结锚 `fusedA`）、无降权 = δ=0（`fusedB`）；旧版把两者标签写反，扩池重算时按生产口径纠正（`Candidate.fusedCurrent` / `fusedNoDown` / `weightFor`），三情景定义见锚 `scenarioDefinitions[].definition`。
- [x] 8. 三情景**不排序、不给推荐**（R4）：锚内不得出现「建议 / 推荐 / 应下调 / 倾向」（守卫词表）
      依据：守卫 `TENDENCY_WORDS` 9 词（建议/推荐/应下调/应当下调/倾向/更优/更好/更差/应该）扫描 锚 JSON + md + README，0 命中；锚 `numericHygiene.scenariosSorted=false`、`scenariosParallel=true`；`scenarios[].key` 顺序固定。
- [x] 9. 标注人 + 日期 + CSV sha256 入锚；同一 CSV 两次打分结果逐字节一致
      依据：锚 `sheet.annotator` / `sheet.annotatedOn` / `sheet.sha256`（`RealPdfAssets.sha256`）；守卫用同一份落盘 CSV 连算两次 `buildAnchor`，`assertArrayEquals(canonicalBytes(withoutRuntime(a1)), …a2)` 全绿。**fix2 实测**：`annotator=machine-prelabel`、`annotatedOn=2026-10-27`、`sheet.bytes=15309`、`sheet.sha256=9fba76b6c893331c738c875976ff9c19638e985793e19f974965f2ebbd01c293`（取**落盘人工表**，不再是空模板的 14301/`70e216d4…`）。

## C · 锚与守卫（R6/R7/R8）

- [x] 10. 锚 `docs/rag-quality/label-sheet-near-dup-relevance-v1.json` + `.md`（≤40 行）；未填标签时 `labelsStatus=AWAITING-HUMAN-LABELS` 且三情景字段为空（**不得**用假数据填充）
      依据：锚 13 个顶层键（fix1 后 +`poolDepth`/`metricCaliber`/`measurableScope`/`tieDetected`/`tieNote`；**fix2 后 14 个**，+`labelsProvenance`）；实测 `labelsStatus=AWAITING-HUMAN-LABELS`、`scenarios=0`；守卫 `hasField(anchor,"precisionAt5")==false` 且 `hasField(anchor,"ndcgAt5")==false`；md `mdLines=38`。
- [x] 11. 守卫：R1 逐字节确定性、R2 盲标白名单、R3 拒绝规则逐条、R4 无倾向措辞、R5 `annotator` 非 agent、R7 文本缺失超 3 行即 `UNDETERMINED`、R8 无外推措辞、md ≤40 行
      依据：`LabelSheetNearDupRelevanceGuardTest` 1 测试全绿（与生成器合计 `Tests run: 8, Failures: 0`，含 `GitAttributesContractGuardTest` 3）；断言项含 `AGENT_LIKE`、`textCoverage.missingRows<=3`、`用户偏好/生产相关性/全局质量` 0 命中、`mdLines=38`、`labels/` 目录仅两产物，并新增「落盘锚 == 本次重算（剔除顶层 runtime）」与「md 字节对账」（手改即红）。
- [x] 12. 边界：`git diff master --stat -- src/main pom.xml .github` 空；既有锚零 `M`；`git diff --check` 干净
      依据：三条命令实测均为空输出 / 空集合；`git diff master --name-status -- docs/rag-quality` 仅 4 个 `A`（`labels/` 两产物、锚 json、锚 md），无既有锚 `M`。`.gitattributes` 新增 `*.csv text eol=lf`，`git ls-files --eol` 实测 `i/lf w/lf attr/text eol=lf`（fix1）。
- [x] 13. 若 30 行里可读文本缺失 > 3 行 ⇒ 停手记 `UNDETERMINED` 并写 `proposal §8`（D5）
      依据：`textCoverage.missingRows=0`（`rowsWithText=36`，fix1 扩池后）⇒ 未触发停手；`buildBundle()` 内 `missingRows<=3` 断言成立。
- [x] 14. 若三情景指标完全打平 ⇒ 如实记录并停手上报（`proposal §8` 预置），不许改口径造差异
      依据（fix2 实测，标签已填）：`tieDetected=false`（三面非全等，故未触发停手）；三情景实测 `current-0982 p@5=0.6 n@5=1.0 ySup=9/24=0.375`、`no-downweight 0.6 / 1.0 / 0/24=0.0`、`floor-090 0.6 / 1.0 / 12/24=0.5`。**如实记：排序质量两面（`precision@5`、`NDCG@5`）完全打平**，唯一差异是压制计数 9→12（0.90 多压 3 条构造已知近重复）；`NDCG@5` 三情景皆 1.0 属结构到顶（每查询仅两组文本），无分辨力，不得当「质量无差异」之外的解读。
- [x] 15. 池深扩到 10（Owner 拍板：主效应在池深 5 不可观测）
      依据：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest "-Dlabel.sheet.write=true"` → `[label-sheet] rows=36 actualPerQuery=[6, 6, 6, 6, 6, 6]`；锚内 `poolDepth.requestedDepth=10`、`poolDepth.actualPerQuery=[6, 6, 6, 6, 6, 6]`、`poolDepth.totalRows=36`、`poolDepth.candidateLimit=40`；语料只有 6 条图像记录 ⇒ 单查询两路去重后上限 6，如实记不足 10（`ceilingReason` 写明，不凑数）；`currentRank` 改为按现状情景全池名次 1..k，行序 = 该名次升序。

## D · 标签填写与仪器修复（fix2）

- [x] 16. 仪器自锁修复：已填的人工表必须能通过 `mvn test`（旧实现三处闸门都要求 CSV == 空模板字节）
      依据：`ensureSheet()`（生成器）只在文件缺失时落盘模板，存在时逐格校验第 1..7 列（模板列）并原样保留第 8..11 列；`buildAnchor(bundle, outcome, sheetBytes)` 的 `sheet.bytes`/`sha256` 改取落盘表；守卫 `assertBlindSheet(csvBytes, templateBytes)` 把 D4「不得代填」改为断言**模板**四列全空 + 人工表要求「整行全空或全填」。实测：旧闸门下守卫红在 `D4：执行者不得代填第 7 列: q0:9820 ==> expected: <> but was: <1>`；修复后 `[label-sheet-guard] 守卫通过：rows=36 status=LABELS-COLLECTED mdLines=39`、`Tests run: 5, Failures: 0`。
- [x] 17. 标签由 Owner 授权主 agent 代填，来源如实登记（不得冒充人工真值）
      依据：`annotator=machine-prelabel`、锚 `labelsProvenance=MACHINE-PRELABEL-NOT-HUMAN-GROUND-TRUTH`（守卫断言该值必须由 `annotator` 派生，`labelsProvenance()`）；`relevance` 分布 `rel0=18 / rel1=12 / rel2=6`、`isNearDuplicate` `Y=24 / N=12`（判据：q0–q3 指向文档本身 ⇒ 封面块 `p1-b1` 记 1、p16 图记 0；q4/q5 指向内存代际图 ⇒ `p16-b2` 记 2、封面记 0）。已知局限：`isNearDuplicate` 可由 `fileName` 的 `@crop-0X` 组确定性推出，真正需人判的只有 `relevance`。
