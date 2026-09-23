# 派发词 P4 · CI 与合并门禁包（组 4 全部、组 5 全部、组 6 的 6.2/6.3、组 3 的 env 侧）

**前置：P0/P1/P2/P3 四包均已回传并通过主 agent 现场复验。** 现状（勿再自行判断）：SpotBugs **已启用**——`pom.xml` 的 `<skip>` 已删、`<excludeFilterFile>src/main/resources/spotbugs-exclude.xml</excludeFilterFile>` 已接，阈值唯一读者是 pom 该块的 `threshold=High` + 那份 12 条基线；PMD **仍关闭**，豁免已登记在 `docs/migration-runbook.md` **§6.7**；`pom.xml` 现存唯一 `<skip>` 在 `:457`（pmd 块）。提交期钩子已生效（`.git/hooks/pre-commit` 转发器在位，实测能阻断）。
**开工基线**：`git status --porcelain` 应看到 ` M AGENTS.md`、` M HANDOFF.md`、` M docs/migration-runbook.md`、` M frontend/AGENTS.md`、` M frontend/scripts/setup-hooks.mjs`、` M openspec/project.md`、` M pom.xml` + `?? openspec/changes/operationalize-harness-gates/`、`?? src/main/resources/spotbugs-exclude.xml`、`?? work/`。**这些是已完成包的产物，一律不许还原、不许提交、不许顺手改**；你只允许新增/修改你自己文件面里的东西。收尾"回到基线"以上述集合为准。

你是 crmAndRag 仓的执行子 agent。目标：让"门禁声明"与"实际会触发的东西"一致，并把前端单元轨与合并前序列接到可自动执行的路径上。**你是 `.github/workflows/ci.yml` 与 `openspec/git-workflow.md` 的唯一写者。**

## 必读（自包含）
- `openspec/changes/operationalize-harness-gates/proposal.md`（Why-2/3/4/5、What Changes 第 2/3/4/5 条、Non-Goals）
- `openspec/changes/operationalize-harness-gates/tasks.md` 的 **组 4、组 5、6.2、6.3**
- `openspec/changes/operationalize-harness-gates/specs/harness-gates/spec.md` 的 **R1、R3、R4、R5**

## 你独占的文件
- `.github/workflows/ci.yml`（头部注释 + 静态检查步骤的 env + `frontend-quality` 增一步）
- `frontend/package.json`（只加 1 个 `test` 脚本）
- `frontend/.github/workflows/ci.yml`、`frontend/.github/workflows/e2e-official.yml`（改名归档处置）
- `scripts/merge-gate.sh`、`scripts/tests/merge-gate-selftest.sh`（新建）
- `openspec/git-workflow.md`
- `work/mailbox/tasks/GATE-P4/report.md`（新建）

## 禁止
- 不得改 `pom.xml`、`frontend/scripts/setup-hooks.mjs`、`frontend/AGENTS.md`、`HANDOFF.md`、`openspec/project.md`、根 `AGENTS.md`、`docs/**`（属其他包）。
- **不得为 `pnpm test` 去改 `frontend/AGENTS.md` 的 Testing Guidelines**——把需要补的那一句写进报告，由主 agent 收尾统一落笔。
- 不得新增/删除任何 Java 测试，不得改 `mvn` 命令语义；不得改 `frontend/**` 的 Vue 源码、vitest/playwright 配置语义。
- 不得默认接入 Playwright/e2e；不得接入真外发模型调用；不改 CI 的触发分支、不加远端、不接托管平台。
- 不得 `git commit` / `git push`。`git mv` 会自动入暂存区：用完必须在报告里贴 `git diff --cached --name-status` 全清单，交主 agent 核对，**不许自行提交**。
- Bash 命令不得含中文（exit 127）。

## 已实测前提
- `frontend/package.json` 现有 16 个脚本里没有 `test`；`vitest` 在 devDependencies；被跟踪的 `frontend/**/*.test.ts` = 16 个、`frontend/e2e/*.spec.ts` = 2 个。
- `frontend-quality` job 在 `ci.yml:168`，现有五步（gen:api / lint:check / type-check:check / build / bundle-budget），`working-directory: frontend`。
- 静态检查步骤 `ci.yml:107-113` 注入的 4 个 env（`CHECKSTYLE_MAX_VIOLATIONS`/`SPOTBUGS_MAX_HIGH`/`PMD_MAX_VIOLATIONS`/`SPOTLESS_APPLY_DIFF`）**全仓无读者**。
- `scripts/` 现无 merge-gate 同职责件；先例：`scripts/check-test-baseline.sh`、`scripts/fail-fast-gate.sh`、`scripts/tests/check-test-baseline-selftest.sh`。

## 任务
1. **先普查**：确认 `ci.yml` 里所有 job 的现有依赖链（`needs:`）与你要插入的位置；确认 `frontend/vite.config.ts` 的 `test.exclude` 已把 `e2e/**` 排除（若无排除，停下报告，不要把 e2e 带进默认路径）。
2. **前端轨接入**：加 `test` 脚本（复用 `frontend/AGENTS.md` 已声明的 `pnpm exec vitest run` 口径，不新造命令），并在 `frontend-quality` 增**一步**跑它；本地实跑一次该步等价命令，贴退出码与用例数（依赖 P0 的"可无网跑=是"；若 P0 判为不可，本步不做并在报告说明）。
3. **静态检查 env 收口（裁定已定，逐个处置，不许保留"以后可能用"）**：
   - `SPOTBUGS_MAX_HIGH: 0` → **删除**。真实读者在 pom（`threshold=High` + P3 新增的 `excludeFilterFile`），该 env 无人读且语义与插件机制不符。
   - `PMD_MAX_VIOLATIONS: 5` → **删除**，并在该步骤注释里写明 pmd 当前处于关闭状态、豁免账目见 `docs/migration-runbook.md` **§6.7**（含三条真实理由：pom 从未写 `<rulesets>` 致 `pmd-rules.xml` 成孤儿配置、摘 skip 会以内置 quickstart 报 122 条、28 条 ref 中 10 条在 PMD 7.9.0 不可解析）。命令里是否仍留 `pmd:check` 由你按"跑了不产生结论就别装成在跑"的原则定夺，但结论必须与 pom 一致。
   - `CHECKSTYLE_MAX_VIOLATIONS: 0` → **删除**。checkstyle 的真实读者是 `pom.xml:387` 的 `failOnViolation=true`（无计数阈值概念）。
   - `SPOTLESS_APPLY_DIFF: 0` → 先查证 spotless 是否真读它；确认无读者则删，有读者则保留并在注释标出读者位置。
   - 收口后该步骤的注释必须逐个写明"这条检查的判定依据在哪一行配置"，做到 grep 得到读者。
4. **CI 头部注释改写**：把"任一阶段失败即阻止合入"改为与实际一致的表述——本仓当前无触发通道，合并前必须本地执行 `bash scripts/merge-gate.sh`；只改注释，不动 job 语义与触发条件。
5. **建 `scripts/merge-gate.sh`**：收敛"合并前必跑"为一条命令，至少含 `mvn -B -ntp test` + `bash scripts/check-test-baseline.sh` + `mvn -B -ntp spotbugs:check`（P3 已启用，必须纳入），按需 `mvn -B -ntp verify`。要求：只读、不访问外网、每个子门禁失败时给出**具名**失败出口。**另须包含两项已在位的机制存在性检查**（缺失即红，这是 P1/P3 留给本包的判别式）：
   - 生效 hooks 目录内存在 `pre-commit` 且能追踪到 `frontend/.githooks/pre-commit`（用 `git rev-parse --git-path hooks` 定位，不许硬编码绝对路径）；
   - SpotBugs 基线双射校验可用：`pom.xml` 里 `<excludeFilterFile>` 在位、`src/main/resources/spotbugs-exclude.xml` 的 `<Match>` **实体**条数与最近一次未过滤 High 条数一致（注意文件头注释里也含 `<Match>` 字样，计数必须按 XML 元素而非 grep 行数）。
5b. **把 P3 的 staleness 校验器耐久化**：P3 的"过期豁免双射校验"脚本目前写在 `work/`（不入库），把它移入 `scripts/tests/`（建议名 `spotbugs-exclude-staleness-check.sh`），改成可独立运行、退出码有意义，并被 `merge-gate.sh` 调用。**不许留在 `work/` 就算完**——那是会话暂存区。
6. **建 `scripts/tests/merge-gate-selftest.sh`**：至少覆盖"任一子门禁失败 → 聚合非零且指名"与"全过 → 零退出"两个场景。
   - **停止规则**：若你写不出可信自测（例如需要真 Docker 或真网络才能构造失败），**放弃 5/6 两步**，只保留第 4 步的声明改写，并在报告里按 R4 的"缺自测的门禁脚本不许存在"场景说明降级理由。不许留无自测的门禁脚本。
7. **`openspec/git-workflow.md`**：① 第 4 行的基线口径改为指向 `scripts/test-baseline.txt` + 判定脚本、不内嵌数字；② 删除第 33-35 行对已消失 CI 步骤（`check_baseline target/surefire-reports`、错误提示行）的三处同步要求，改为"一次带 `--update` 的真实运行"；③ 合并前序列指向 `scripts/merge-gate.sh`；④ 保留"无 remote 禁 push"与成本闸门口径原文。
8. **Q2 处置**：`frontend/.github/workflows/` 两个定义改名归档保留（`*.prev-*` 风格），不删除。
9. 判别式：`grep -c "check_baseline" openspec/git-workflow.md` = 0；`grep -n "surefire [0-9]" openspec/git-workflow.md` = 0；`bash scripts/tests/merge-gate-selftest.sh` 通过；`bash scripts/merge-gate.sh` 在当前 HEAD 上的真实结论；`git diff --cached --name-status` 清单。

## 回传短包（≤18 行）
```
【回传】GATE-P4
状态：通过/失败/阻塞
改了：<文件清单>
前端轨：test 脚本=<加/未加>，实跑 <用例>/<通过>，是否入 CI=<是/否+依据>
静态 env 处置：有读者=<文件:行> / 已删除=<列出>
merge-gate：建=<是/否>，自测=<通过/未写→已按 R4 降级>，当前 HEAD 结论=<绿/红+原因>
git-workflow.md：check_baseline=0命中，数字内嵌=0，序列已指向 merge-gate
入暂存区清单（git diff --cached --name-status）：<逐行>
需主 agent 补的 frontend/AGENTS.md 句子：「<原样一句>」
遗留：<一句，或"无">
报告：work/mailbox/tasks/GATE-P4/report.md
```
