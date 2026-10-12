# 任务清单：calibrate-review-intensity

## §0 铁律与留痕

- 只许动：`docs/review-intensity-policy.md`（新）、`scripts/tests/review-mechanical-check.sh`（新）、AGENTS.md「复核档位」小节（≤3 行）、本卡目录文件。超范围 ＝ 偏差，如实留痕披露。
- 脚本恒 exit 0、不接 CI / merge-gate.sh（D4 边界）；对 b2 在途分支只读不写。
- 停步点：四组全完 + 门禁留证 ＝ 停步回报，不合并不 push。
- 门禁基线数字一律以 pom / 基线文件实读为准，本文不复制数字。

## 任务组 1 · 政策文档

- [x] 1.1 新建 `docs/review-intensity-policy.md`：D1 三档判据、执行 / 抽查 / 复核职责矩阵（A/B/C × 执行自跑 / 指导抽查 / 复核动作）、勾选哈希留痕规则引用、生效语义（合入后新卡生效，在途卡按旧规则）
      补勾依据（本卡自勾）：提交 0099753（`git show --stat 0099753` = docs/review-intensity-policy.md +46 行新建，内容含三档判据表、A/B/C×执行/抽查/复核职责矩阵、勾选哈希留痕规则引用=archive/close-openspec-task-residue/specs/openspec-task-residue/spec-delta.md、生效语义小节）
- [x] 1.2 政策页注明本卡为首个 C 档试点
      补勾依据（本卡自勾）：提交 0099753 政策页头部「状态」行标注「本卡（calibrate-review-intensity）为首个 C 档试点」（`:read docs/review-intensity-policy.md` 实测 L4）

## 任务组 2 · 机械核对脚本

- [x] 2.1 新建 `scripts/tests/review-mechanical-check.sh`：两树 git 状态与 HEAD 位置、分支 vs master diff 文件清单、指定卡 tasks.md 未勾项计数、基线文件 vs master 漂移检查；恒 exit 0 只报告不判定
      补勾依据（本卡自勾）：提交 a742c78（`git show --stat a742c78` = scripts/tests/review-mechanical-check.sh +109 新建）；四项分别输出（运行 `bash scripts/tests/review-mechanical-check.sh` 实测四节齐全），末行恒「机械核对完成（恒 exit 0，仅报告不判定）」且 `exit 0` 收尾
- [x] 2.2 `bash -n` 语法过 + 对 feature/b2-suite-recalibration 只读实跑一次，输出表贴回停步回报
      补勾依据（本卡自勾）：`bash -n scripts/tests/review-mechanical-check.sh` 实测退出 0；对 b2 只读实跑 `bash scripts/tests/review-mechanical-check.sh --branch feature/b2-suite-recalibration --master-tree D:/code/crmAndRag --tasks openspec/changes/b2-suite-recalibration/tasks.md` 实测退出 0（四节输出表见停步回报）；`feature/b2-suite-recalibration`(42fb2f7) 相对 master 三档 diff 为 0（该卡已合入 master，脚本只作报告不作判定）

## 任务组 3 · AGENTS.md 指针

- [x] 3.1 AGENTS.md 新增「复核档位」小节（≤3 行）指向 `docs/review-intensity-policy.md`，不改其余任何条款
      补勾依据（本卡自勾）：提交 042ecca（`git show --stat 042ecca` = AGENTS.md +4 行）；`git diff 042ecca^ 042ecca -- AGENTS.md` 仅新增「### 复核档位」小节 1 行指针，无其余任何条款改动（实测 diff 见执行留痕）

## 任务组 4 · 门禁与回报

- [x] 4.1 `mvn -B -ntp test` 全绿且 `bash scripts/check-test-baseline.sh` 不红（本卡无 Java 变更，surefire 数应不动）
      补勾依据（本卡自勾）：`$env:DASHSCOPE_API_KEY=""; mvn -B -ntp test > work/mvn-test-g4.log 2>&1` 实测 `MVN_EXIT=0`，末尾 key 行 `[INFO] Tests run: 1173, Failures: 0, Errors: 0, Skipped: 0` + `[INFO] BUILD SUCCESS`；`bash scripts/check-test-baseline.sh` 实测退出 0、输出「回归基线门禁通过」。surefire 实测 1173 = master 同源（本卡 diff 零 .java/pom/.sql，见 4.2 依据），本卡零增；基线文件 1151 为 master 侧既有陈旧（实测漂移，非本卡引入，详见停步回报偏差披露）
- [x] 4.2 回报声明 pmd / spotbugs 基线不动（无 Java 变更，计数不变即可声明）；tasks 全勾 + §0 留痕，停步回报
      补勾依据（本卡自勾）：`git diff --name-status master...feature/calibrate-review-intensity` 实测仅 5 文件（AGENTS.md、docs/review-intensity-policy.md、openspec/...、scripts/tests/review-mechanical-check.sh），零 .java/pom.xml/.sql → 无 Java 变更靠构造即证 pmd/spotbugs 基线不动；§0 留痕本项目卡文件内全勾 + 各提交哈希（b1a2899/0099753/a742c78/042ecca）与门禁 raw key 行载明
