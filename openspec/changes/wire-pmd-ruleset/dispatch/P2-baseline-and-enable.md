# 派发词 PMDC-P2 · 写基线 + 摘 skip + 回接门禁（`wire-pmd-ruleset` 组 3 → 组 4 → 组 5）

**前置（硬性）**：必须已有 `work/mailbox/tasks/PMDC-P1/report.md` 且其 2.1 结论为"**失效引用会硬错**"。若 P1 报的是"静默跳过"或状态为阻塞 → **不要执行本包**，直接回传"前置未满足"。

你是 crmAndRag 仓的执行子 agent。目标：把 PMD 从"豁免关闭"变成"启用的真门禁"——基线值来自 P1 的实测，不是猜的。

## 必读（自包含）
- `openspec/changes/wire-pmd-ruleset/proposal.md`（What Changes 2/3/4/5、拍板记录 Q1=A、风险第 1 条）
- `openspec/changes/wire-pmd-ruleset/tasks.md`：**组 3（3.1-3.4）→ 组 4（4.1-4.4）→ 组 5（5.1-5.3）** ← 你的全部任务
- `openspec/changes/wire-pmd-ruleset/specs/static-analysis/spec.md` 的 **R3、R4**
- `work/mailbox/tasks/PMDC-P1/report.md` + 其归档产物（逐规则命中清单、两把尺子差异表）
- `docs/migration-runbook.md` §6.7（要撤销的豁免登记）与 §6.2（真跑记录口径先读再下结论）

## 你独占的文件
- `pom.xml`（pmd 块：加 `<maxAllowedViolations>`、改那句"阈值≤5"注释、**删除 `<skip>`**）
- `scripts/tests/pmd-violation-baseline.txt`（新建，基线值台账）
- `scripts/tests/pmd-baseline-check.sh`（新建，过期/只降不升校验）
- `scripts/merge-gate.sh` 与 `scripts/tests/merge-gate-selftest.sh`（加 `[pmd]` 与 `[pmd-baseline]`）
- `.github/workflows/ci.yml`（把 `pmd:check` 加回静态检查步骤 + 注明判定依据行号）
- `docs/migration-runbook.md`（§6.7 改写 + 真跑记录）
- `work/mailbox/tasks/PMDC-P2/report.md`（新建）

## 禁止
- **基线数字只能来自 P1 报告的实测值**。不得自拟、不得沿用 122（那是内置 quickstart 在**未接线**状态下的数，与仓库声明的 28 条尺子不是同一把）。若 P1 报告里找不到明确总数 → 停下报"前置不足"，不要自己再测一遍来编。
- 不得删规则、放宽 `failurePriority`、排除源文件目录来压低条数。
- 不得修改 `pmd-rules.xml`（P1 已定稿，若发现新问题写进遗留）、`spotbugs-exclude.xml`、`scripts/tests/spotbugs-*`、`frontend/**`、`src/main/**`、`src/test/**`、迁移链。
- 不得增减 Java 测试用例（`mvn -B -ntp test` 计数必须与 `scripts/test-baseline.txt` 一致）。
- 不得 `git add` / `git commit` / `git push`；不得联网（一律 `mvn -o`）；Bash 命令不得含非 ASCII 字符（exit 127），中文检索用检索工具。

## 任务（按序）
1. **3.1 落阈值**：读 P1 报告的实测条数，写进 pmd 块 `<maxAllowedViolations>`，并建 `scripts/tests/pmd-violation-baseline.txt` 存该值 + 生成命令 + 日期 + `source-revision`（口径照 `scripts/test-baseline.txt` 的头三行）。注释里写明"只允许由一次真实运行写入、只许下调"。
2. **3.2 清掉假注释**：`pom.xml:443` 那句"代码异味检测（阈值≤5）"改为与真读者一致；全仓确认没有第二处"条数阈值"表述（`scripts/`、`docs/`、`openspec/` 里的**引用式指针**不算）。
3. **3.3 建过期校验** `scripts/tests/pmd-baseline-check.sh`：读台账值与 `target/pmd.xml`（或等价产物）的实测条数，**登记值 > 实测值 → 非零退出并要求下调**；独立可跑、退出码有意义、参数只有 `--update`（用真实运行重写台账，且**含失败时拒绝写入**，照 `check-test-baseline.sh` 的语义）。
4. **4.1 摘 skip** → `mvn -o -B -ntp pmd:check` 在基线内为绿。
5. **4.2/4.3 回接**：`ci.yml` 静态步骤加回 `pmd:check` 并注明判定依据（pom 第几行是条数读者）；`scripts/merge-gate.sh` 加 `[pmd]`（`mvn -B -ntp pmd:check`）与 `[pmd-baseline]`（调 3.3 脚本），**不依赖 `[it]`**（pmd 绑 verify 而默认序列不跑 verify，这是 `harness-gates` 已确认的坑）；自测补场景：`[pmd]` 失败→聚合非零且指名、基线过期→非零。
6. **4.4 反向判别（做不出=本包失败，不许改用配置让它看起来绿）**。台账只降不升的语义要用三态实测证明：
   - **登记值 > 实测值**（台账留了冗余）→ `pmd-baseline-check.sh` 必须**非零**并提示下调；
   - **登记值 < 实测值**（新引入违规）→ 必须**非零**并报"新增违规超基线"；
   - **登记值 = 实测值** → 必须**零退出**。
   三个态各贴一次命令与退出码；测试用的临时改动（含台账值的篡改）全部还原。
   另外：新增一个必然违反已加载规则集的临时 Java 文件 → `pmd:check` 非零，且指出的规则**必须属于声明的 28 条**（不是内置 quickstart 的规则，用来反证接线生效）；全部还原后 `pmd:check` 复绿、`git status` 回到开工基线。
7. **5.1/5.2/5.3 登记与路由**：§6.7 由"豁免"改写为"已启用 + 基线指针 + 阈值口径"，**三条原理由逐条标注"已于 <日期> 消除"而非删除**；补 `mvn -B -ntp verify` 真跑记录并区分"PMD 真跑=是 / failsafe 新鲜度=否（无 Docker）"；`openspec/project.md` 与 `AGENTS.md` 只加指针不改数字（`harness-gates` R5 口径）。
8. **6.1/6.2 回归**：`mvn -B -ntp test` 全绿且计数=基线；`bash scripts/merge-gate.sh` 全绿；三份自测（baseline / merge-gate / pmd-baseline）通过。

## 回传短包（≤16 行）
```
【回传】PMDC-P2
状态：通过/失败/阻塞
基线值：<数> 来源=PMDC-P1 报告 §<n>（命令+日期），台账 revision=<sha>
skip：已删；pmd:check 退出<码>
假注释：pom:443 处置=<改写为…|已删>；第二处条数表述=无/列出
3.3 校验：登记>实测→退出<码>；实测>登记→退出<码>
反向判别：注入违规→退出<码>，命中规则=<名>(属声明28条?)；还原后复绿=<是/否>
merge-gate：[pmd]/[pmd-baseline] 已加，自测断言 <旧>→<新>，全绿=<是/否>
mvn test：724/0/0/0 一致=<是/否>
§6.7：已改写为已启用+保留历史对照=<是/否>；真跑记录已补=<是/否>
改了哪些文件：<清单>
遗留：<一句，或"无">
报告：work/mailbox/tasks/PMDC-P2/report.md
```
