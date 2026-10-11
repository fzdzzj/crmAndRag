# tasks — harden-gold-marker-tearing

## 任务组 1 · 检出实现（¥0）

- [x] 1.1 权威树自证：`git rev-parse --abbrev-ref HEAD`（master @ 88ba84d）→ `git checkout -b feature/harden-gold-marker-tearing` → 再自证分支，输出贴 §0；随后落红测骨架——新增测试方法（引用 `preparation.tornMarkers()` 断言 T-15 事件非空）+ `Preparation` 尾部空表字段最小骨架，跑 `mvn -Dtest=RagBenchmarkDataPreparerTest test` 贴红（期望非空实际空）
- [x] 1.2 `RagBenchmarkDataPreparer`：实现两遍检出（fixtureGoldIds 收集 + 右残段负向后视 `(?<!【GOLD:)id】` + 左残段 FRAGMENT 非完整命中区间），新增 `TornMarkerEvent` record，`Preparation` 尾部追加 `tornMarkers` 字段，检出非空时 prepare 内打一行聚合 WARN（JUL）；中文 Javadoc 标注「harden-gold-marker-tearing 任务 1.2」；**主循环（剥离/归点/upsert/向量输入）逐字不变**；若 PMD 复杂度红允许拆协作者类 `TornMarkerScanner`（同标注）
- [x] 1.3 红转绿实测：`mvn -Dtest=RagBenchmarkDataPreparerTest test` 全绿；T-15 现场断言以实测校准（右残段 matchedFragment、左残段精确形态写进测试注释留证），输出贴 §0

## 任务组 2 · 全量回归与基线（¥0）

- [x] 2.1 全量 `mvn -B -ntp test`：0 失败 0 错误，surefire 数量按实测登记（应 ≥1090，只增不减）；`bash scripts/check-test-baseline.sh --update` 从本次真实运行写入（Git Bash 用 `D:\git\Git\bin\bash.exe`，PATH 里 `bash` 命中 WSL 不可用），输出贴 §0
- [x] 2.2 锚点对账：mvn test 前后 `docs/rag-quality/` 下既有产物（含 baseline-v1|v2|v3、after-*、i05-*、citation-forensics-v4*）SHA256 逐一对照不变，贴 §0

## 任务组 3 · 门禁与提交

- [x] 3.1 门禁自跑留证：`bash scripts/merge-gate.sh`（默认序列 with_verify=0）八子门禁全绿 + `bash scripts/tests/pmd-baseline-check.sh` 过期判别；原始输出贴 §0。口径披露：failsafe 域零改动（RagRealRetrievalBenchmarkIT 未触碰），本地不跑 failsafe 长跑，push 后 CI 远端背书（P-ae/P-ag/archive 卡先例）
- [x] 3.2 三件套按逐卡新卡轨落位 `openspec/changes/harden-gold-marker-tearing/`（主树草稿 `work/tmp-gold-tear/` 仅供对照）；单笔中文提交（3 改动文件 + 三件套，写集 ≤6 tracked）；git commit 单独执行（Git Bash 用 -F 临时文件传中文，PS 用 `2>&1 | Out-String` 包裹；禁 `git commit -- <pathspec>`），不合并不 push
- [x] 3.3 停步回报：原样粘贴 `git log --oneline --graph -5` / `git status --short` / `git show --stat HEAD` / 红绿测关键行 / mvn test 尾行 / merge-gate 尾行 / 基线脚本输出 / 锚点 SHA256 对照；任何未实际执行的命令不得出现在回报中

## §0 执行记录

- 2026-10-11 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`。自证：`git rev-parse --abbrev-ref HEAD` = `master`、`git log --oneline -1` = `88ba84d merge: 引用冗余取证 v4 落盘（trace-citation-redundancy，--no-ff 合入）` → `git checkout -b feature/harden-gold-marker-tearing` → 再自证 `feature/harden-gold-marker-tearing`。
- **1.1 红测**：骨架 = `Preparation` 尾字段 `tornMarkers`（构造点唯一 = prepare，传 `List.of()`）+ `TornMarkerEvent` record + 2 新测试。`mvn -Dtest=RagBenchmarkDataPreparerTest test` → `Tests run: 10, Failures: 1, Errors: 0`，失败行：
  `tornMarkerT15SceneIsDetected -- AssertionFailedError: T-15 右残段必须被检出（customer-onboard-3）: [] ==> expected: <true> but was: <false>`（骨架空表，期望非空实际空；零行为变更锁测试此阶段绿）。
- **1.2 实现**：`scanTornMarkers` 两遍检出（第一遍 GOLD_MARKER 完整扫描收 fixtureGoldIds；第二遍逐切片在剥离前原文上：右残段 `(?<!【GOLD:)` + `Pattern.quote(id)` + `】` 负向后视、左残段 FRAGMENT 命中中非完整覆盖区间者、goldId 尽力解析）+ 检出非空 `TEAR_LOGGER.warning` 聚合一行（JUL）。主循环既有行零修改，仅新增只读旁路收集行 `tornScanInputs.add(Map.entry(fixture.key(), chunks))`。PMD 实测 0 violations → 未拆协作者类（写集保持 6 tracked）。
- **1.3 转绿**：`Tests run: 10, Failures: 0, Errors: 0`，BUILD SUCCESS。实测校准（临时探针 2026-10-11，跑后即删不入提交）：customer-sop 分 6 切片——LEFT 残段在 customer-sop-1 尾部区间 [302,320) 精确形态 `【GOLD:customer-onb`（18 字符无闭合，goldId 尽力解析 `customer-onb`）；RIGHT 残段在 customer-sop-3 剥离前原文开头 `customer-onboard-3】`（19 字符）；完整标记 `【GOLD:customer-onboard-3】` 因 overlap 复制在 customer-sop-2 区间 [274,299) 唯一完整命中（归点）。已写入测试注释。
- **2.1 全量**：`mvn -B -ntp test` → `Tests run: 1091, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。`check-test-baseline.sh --update` 写入：`surefire.reports=184`（不变）、`surefire.tests=1089→1091`（+2 = 新增 2 测，只增不减）、`surefire.skipped=0`；failsafe 段 `reports=27 / tests=98 / skipped 6→5`——本地不跑 failsafe，`target/failsafe-reports` 现状为 2026-10-11 08:31 另一轮真实运行（含取证卡授权真跑 RagRealRetrievalBenchmarkIT，tests=1 skipped=0，time=144s），脚本注释明示「默认序列不跑 [it] 时 failsafe 报告本就沿用上一轮」，--update 只许从真实运行写入。过程纠偏：首轮 --update 前发现 surefire-reports 残留临时探针报告（+1 报告 +1 test → 误测 1092），删除残留后重写，1091 与 mvn 汇总行精确一致。复跑 check：`回归基线门禁通过`。
- **2.2 锚点对账**：`docs/rag-quality/` 目录实存 **20 文件**（任务书口径「17 个既有产物」以全目录 20 文件逐一对照，只多不少），SHA256 跑前（10:30，全量 test 前）/跑后（merge-gate 后）20/20 全等，输出 `ANCHORS-IDENTICAL-20-FILES`。
- **3.1 门禁**：`merge-gate.sh` rc=0，八子门禁 `PASS [unit] / PASS [spotbugs] / PASS [pmd] / PASS [baseline] / PASS [frontend-unit] / PASS [hook] / PASS [bijection] / PASS [pmd-baseline]`，尾行「== merge-gate 通过：所有已执行的子门禁绿 ==」（frontend-unit 17 文件 179 tests 全绿）。独立 `pmd-baseline-check.sh` rc=0：`实测 violations=0 == 登记 0 == pom 阈值 0`。口径披露：failsafe 域零改动（RagRealRetrievalBenchmarkIT 未触碰），本地不跑 failsafe 长跑，push 后 CI 远端背书（P-ae/P-ag/archive 卡先例）。
- **3.2 提交**：单笔中文提交（3 tracked 修改 + 三件套 3 新文件 = 6 tracked；git commit -F 临时文件传中文）；不 merge 不 push。
