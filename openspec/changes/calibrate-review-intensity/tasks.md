# 任务清单：calibrate-review-intensity

## §0 铁律与留痕

- 只许动：`docs/review-intensity-policy.md`（新）、`scripts/tests/review-mechanical-check.sh`（新）、AGENTS.md「复核档位」小节（≤3 行）、本卡目录文件。超范围 ＝ 偏差，如实留痕披露。
- 脚本恒 exit 0、不接 CI / merge-gate.sh（D4 边界）；对 b2 在途分支只读不写。
- 停步点：四组全完 + 门禁留证 ＝ 停步回报，不合并不 push。
- 门禁基线数字一律以 pom / 基线文件实读为准，本文不复制数字。

## 任务组 1 · 政策文档

- [ ] 1.1 新建 `docs/review-intensity-policy.md`：D1 三档判据、执行 / 抽查 / 复核职责矩阵（A/B/C × 执行自跑 / 指导抽查 / 复核动作）、勾选哈希留痕规则引用、生效语义（合入后新卡生效，在途卡按旧规则）
- [ ] 1.2 政策页注明本卡为首个 C 档试点

## 任务组 2 · 机械核对脚本

- [ ] 2.1 新建 `scripts/tests/review-mechanical-check.sh`：两树 git 状态与 HEAD 位置、分支 vs master diff 文件清单、指定卡 tasks.md 未勾项计数、基线文件 vs master 漂移检查；恒 exit 0 只报告不判定
- [ ] 2.2 `bash -n` 语法过 + 对 feature/b2-suite-recalibration 只读实跑一次，输出表贴回停步回报

## 任务组 3 · AGENTS.md 指针

- [ ] 3.1 AGENTS.md 新增「复核档位」小节（≤3 行）指向 `docs/review-intensity-policy.md`，不改其余任何条款

## 任务组 4 · 门禁与回报

- [ ] 4.1 `mvn -B -ntp test` 全绿且 `bash scripts/check-test-baseline.sh` 不红（本卡无 Java 变更，surefire 数应不动）
- [ ] 4.2 回报声明 pmd / spotbugs 基线不动（无 Java 变更，计数不变即可声明）；tasks 全勾 + §0 留痕，停步回报
