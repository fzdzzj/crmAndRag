# 提案：把已有门禁接到会触发的点上（operationalize-harness-gates）

> 变更 ID：`operationalize-harness-gates` ｜ 能力域：`harness-gates` ｜ 序列：独立小提案（只动门禁接线与治理文档，不碰业务代码、检索链路与迁移链）
> 来源：2026-09-21 一轮 `better-harness` 全量评审（7 项发现）后的仓库侧收敛。本提案只收 5 项**仓库自有**发现；另 2 项属评审工具自身的观测面（会话来源根、Memory 标题扫描），不在仓库范围内，见 Non-Goals。
> **与上一轮评审的关系（2026-09-21 第 1 轮复核补正）**：`docs/harness-summary-2026-09-19.md` 已记录同域 9 项发现。逐条对账结果——其 #1（DashScope 外发闸门）与 #2（V27 未登记 `EXPECTED_VERSIONS`）**已修复**（实测 `ModelProviderImplDashScopeIT:41` 已挂 `RAG_BENCHMARK_REAL` 双门控、`FlywayMigrationIT:29` 版本表已含 V27/V28）；其 #4（traceId 不进日志行）**已修复**（`logback-spring.xml` pattern 已含 `%X{traceId}`）；但其 #3（三段门禁与真实合入路径不同路、无 hooks）与 #5（入口文档基线数字互相矛盾）**今日仍成立**，本提案的 Why-1/3/5 即其复发项。复发本身是本提案的立项理由：上一轮的结论没有被接到会触发的点上。
> 铁律：下文所有行号与数字都是本轮实测（`git config --show-origin`、`git worktree list`、`grep`、`git ls-files`、`cat scripts/test-baseline.txt`），不引用评审结论、不复制历史快照。

## Why

1. **提交期唯一的机械阻断点当前不生效。** `git config --show-origin --get core.hooksPath` 实测返回 `.git/config` 里写入的绝对路径 `<仓根>\.git\hooks`；该目录内只有 `post-commit` 与 `post-checkout` 两个 Qoder 遥测钩子，两者都以 `|| true` 收尾（永不阻断）。也就是说 `frontend/.githooks/pre-commit` 这个真正的检查器**从不执行**。而 `frontend/AGENTS.md` 的 Git Hook Policy 自己写了期望值——`git config --get core.hooksPath` 应输出 `frontend/.githooks`。当前状态与仓库声明的契约直接冲突，且从工作副本外观分辨不出来。
2. **静态分析门禁：两个插件被关掉，四个阈值没人读。** `ci.yml:107-113` 执行 `mvn -B -ntp checkstyle:check spotbugs:check pmd:check spotless:check`，并注入 `CHECKSTYLE_MAX_VIOLATIONS:0`、`SPOTBUGS_MAX_HIGH:0`、`PMD_MAX_VIOLATIONS:5`、`SPOTLESS_APPLY_DIFF:0`。本轮全仓 grep（排除 worktree 副本）显示这 4 个变量**只出现在 ci.yml 和 work/mailbox 的一次交接记录里**，`pom.xml` 与 `scripts/` 没有任何读者——阈值是装饰。同时 `pom.xml:418`、`pom.xml:452` 各有一处 `<skip>true</skip>`，引入提交 `ba57e2b` 正文自陈"temporarily skipped due to configuration issues, will be activated in follow-up fixes"，但 tracked 文档无一处登记该豁免及其到期位置。
   **执行期实测补正（2026-09-21，P3 落位时测得，比上面这条判断更严重）**：光把 `<skip>` 摘掉并不等于"门禁就活了"。① SpotBugs 的 `onlyAnalyze` 原值 `com.slz.crm.**` 在 4.8.6 是非法模式，去 skip 后直接 `BUILD FAILURE`（`Dangling meta character '*'`）——即该门禁**自合入起从未成功跑过一次**，"临时跳过"的提交说明低估了问题；合法写法是 `com.slz.crm.-`。② PMD 摘掉 skip 后**确实会跑**（退出 1、`target/pmd.xml` 有产物、122 条违规），但 pom 从未写过 `<rulesets>`，`src/main/resources/pmd-rules.xml` 是**孤儿配置**，实跑用的是插件内置 quickstart，其中 6 个规则根本不在仓库声明的 28 条之内；而那份声明的 ruleset 自身 28 条 ref 里有 10 条在 PMD 7.9.0 已不可解析（9 条规则已不存在 + 1 条分类文件名写成 `codestyle.java/`）。结论：这不是"一个开关没打开"，而是**两把尺子从未真正架上过被测物**。
3. **CI 声明"任一阶段失败即阻止合入"，但本仓没有任何触发通道。** `ci.yml:1-6` 头部注释与全部 gate job 都在；实测 `git remote -v` 为空、`master` 无 upstream，且 `openspec/git-workflow.md:4` 自己写明"仓库无 remote，禁止 push"。真实验收边界是"执行者按 git-workflow.md 手跑命令后 `--no-ff` 合入"——历史上确实照做（master 上 `af5046b` 等 merge 链可见），但没有任何机制强制它。与第 1 条叠加后结论是：**本仓不存在任何一个会自行变红的检查点**。此条与上一轮评审 #3 同源，属复发项。
4. **前端 16 个单测 + 2 个 e2e spec 不属于任何自动执行的门禁。** `git ls-files` 实测 `frontend/**/*.test.ts` 16 个、`frontend/e2e/` 下 2 个 spec；`frontend/package.json` 的 16 个脚本里**没有 `test`**，vitest 只挂在 devDependencies；根 `ci.yml:168` 的 `frontend-quality` job 只跑 gen:api / lint:check / type-check:check / build / 体积预算五步。`frontend/AGENTS.md`「Testing Guidelines」把 Vitest 列为正式轨道之一，但没有执行点。边界说明：`add-frontend-ci` 提案已落地为 `frontend-quality`，只覆盖 lint+type-check 且明确禁止默认跑 Playwright——本提案不重做它。
5. **三份治理文档把执行者路由到已经不存在的门禁 owner。** `HANDOFF.md:12`、`openspec/project.md:42` 与 `openspec/git-workflow.md:4` 都写"基线数字以 `.github/workflows/ci.yml` 为准（当前 surefire 657 / failsafe 13）"，`git-workflow.md:34` 还要求"同步改 ci.yml 三处……`check_baseline target/surefire-reports` 数字"。实测：该步骤名在 HEAD 的 ci.yml 中出现 **0 次**、ci.yml 已无任何硬编码阈值；真实 owner 是 `scripts/test-baseline.txt`（surefire 724 / failsafe 66，`source-revision=e2785b2`，measured-at 2026-09-20），其**第 1 行**即"禁止手改"，判定脚本 `scripts/check-test-baseline.sh:13` 重申同一约束、`:23` 才接受 `--update`。`docs/migration-runbook.md:72` 早已按正确口径写清并点名 657/13 属历史快照。**后果不是文档不好看，而是照文档执行的 agent 要么找不到编辑目标、要么转而手改被禁改的阈值文件——反向弱化门禁。** 此条是上一轮评审 #5（当时数出"四套矛盾数字"）的收敛残留：runbook 已改对，`HANDOFF.md` 与两份 openspec 文档未跟。

## What Changes

按"先扩已有 owner，再造新机制"的次序，五项各自独立可验收：

1. **钩子归属定夺并让 pre-commit 真的会阻断**（Q1 已裁定为选项 A，见下方拍板记录）
   - 扩展既有唯一安装器 `frontend/scripts/setup-hooks.mjs`，不新写根级安装脚本；
   - 硬约束：无论最终选哪个 hooks 目录，**另一侧既有钩子必须被转发而不是被顶掉**（Qoder 遥测与前端检查器不必互斥）；
   - 同步 `frontend/AGENTS.md` Git Hook Policy 的期望值与实测判别式。
2. **静态分析先测再定夺，阈值必须有唯一读者**
   - 实跑 `mvn -B -ntp spotbugs:check`、`mvn -B -ntp pmd:check` 取真实违规数；
   - 结论可用 → 去掉对应 `<skip>`，把阈值落到唯一读者（`pom.xml` 配置或 `scripts/` 判定脚本，二选一并写进注释）；
   - 结论暂不可用 → 在 tracked 文档登记豁免（项 + 理由 + 复测命令 + 到期触发条件），并让 ci.yml 该步骤只跑真有读者的检查；
   - 4 个环境变量：**有读者或删掉**，不允许保留无人读的阈值。
3. **把 CI 声明改写成真实交付边界**
   - 不动远端、不接托管平台（需单独授权）；
   - `ci.yml` 头部与 `git-workflow.md` 的"阻止合入"表述改为：本仓当前无触发通道，合并前必须本地执行一条聚合门禁命令；
   - 聚合命令沿用仓库既有的 `scripts/*-gate.sh` 家族先例（`check-test-baseline.sh`、`fail-fast-gate.sh` 已在，`scripts/tests/` 已有自测先例），把 git-workflow.md 现有的 4 行散文收敛成一条可判别命令 + 期望输出。
4. **前端单元轨接入自动门禁**
   - `frontend/package.json` 增加最小 `test` 脚本（走 `frontend/AGENTS.md` 已声明的 `pnpm exec vitest run` 口径，不新造命令）；
   - 根 `ci.yml` 的 `frontend-quality` job 增加一步执行它；
   - Playwright/e2e 轨道本次不接（保持 `add-frontend-ci` 的禁止口径）；`frontend/.github/workflows/` 下两个不被宿主读取的定义按 Q2 处置。
5. **治理文档口径归一到唯一权威源**
   - `HANDOFF.md:12`、`openspec/project.md:42`、`git-workflow.md:4` 的"以 ci.yml 为准"改为指向 `scripts/test-baseline.txt` + `bash scripts/check-test-baseline.sh`，与 `docs/migration-runbook.md:72` 同口径；
   - 删除 `git-workflow.md:34` 对已消失 CI 步骤的同步要求，改为"一次带 `--update` 的真实运行"；
   - 被改写的这三份文档里**不得再出现具体快照数字**（无论是否标注日期），只允许"以权威文件 + `git log -p -- scripts/test-baseline.txt` 为准"的引用式写法——否则下一次 bump 又把这里改回过期状态。

## Impact

- **修改文件**：`frontend/scripts/setup-hooks.mjs`、`frontend/AGENTS.md`（Git Hook Policy 段）、`pom.xml`（**仅 spotbugs 块**：去 `<skip>` + 加 `<excludeFilterFile>`；pmd 块保持不动）、`src/main/resources/spotbugs-exclude.xml`（**新建**，存量 High 基线，与既有 `src/main/resources/pmd-rules.xml` 同级同惯例）、`docs/migration-runbook.md`（§6 追加 PMD 豁免一条）、`.github/workflows/ci.yml`（头部注释、静态检查 env 收口、`frontend-quality` 增一步）、`frontend/package.json`（增 1 个脚本）、`HANDOFF.md`、`openspec/project.md`、`openspec/git-workflow.md`；可能新增 `scripts/merge-gate.sh` 与 `scripts/tests/merge-gate-selftest.sh`（沿用家族先例，实测 `scripts/` 下无同名或同职责件）。
- **不改**：任何 `src/main/**` 业务代码、任何 `src/test/**` 用例、Flyway 迁移链、`init_data.sql`、`platform/contract/` 冻结接口、`pom.xml` 的 surefire/failsafe/checkstyle/spotless 段、`frontend/**` 的 Vue 源码与 e2e 默认执行。
- **与在途提案的互斥关系**：`add-frontend-ci`、`test-hygiene` 均已落地（实测 `ci.yml:168` 存在 `frontend-quality`、`CacheConfig` 内 8 处 `recordStats`），本提案与其无文件交集；若 `test-hygiene` 复开并再动 `pom.xml`，需先协调 `pom.xml` 归属（本提案只碰 spotbugs/pmd 两块）。
- **基线口径**：本提案不新增 Java 测试用例 → `scripts/test-baseline.txt` 的 surefire/failsafe 数字**不应变化**；若某步导致数字变化，视为该步越界。
- **用户可见影响**：提交前端代码时首次会感到"变慢/会被拒"（`pre-commit` 仅在有 staged 前端文件时才跑检查，见 `frontend/.githooks/pre-commit:9-12` 的短路）。worktree 生效面已实测收敛：`git worktree list` 17 个条目全部是**链接工作树**（其 `.git` 指回 `.git/worktrees/<name>`，`git rev-parse --git-common-dir` = `.git`），`core.hooksPath` 与钩子目录由主仓共享，在任一 worktree 内读取都返回同一个 `<仓根>\.git\hooks` → **装一次即 17 处生效**，只有全新 clone 才需要重跑安装。

## 拍板记录

- **Q1（2026-09-21 用户裁定）：选 A —— 保住 `.git/hooks`，在其中安装 `pre-commit` 转发器调用 `frontend/.githooks/pre-commit`，Qoder 遥测钩子原样保留。**
  - 连带后果（执行时必须处理）：`.git/hooks` 不入版本控制 → **全新 clone 需重跑安装**；但本仓 17 个 agent worktree 实测均为链接工作树、共享主仓 `.git/hooks` 与仓库本地 `core.hooksPath`，故**装一次即全部生效**（原提案初稿把这条代价写成"逐 worktree 重装"，第 1 轮复核已更正）。此外 `frontend/AGENTS.md` Git Hook Policy 的期望值必须从 `frontend/.githooks` 改写为"生效 hooks 目录内存在转发到 `frontend/.githooks/pre-commit` 的 `pre-commit`"，否则文档与实现继续互相矛盾。
  - 仍成立的硬约束：不许顶掉另一侧钩子；本提案不含删除或改写 Qoder 遥测钩子内容的动作（需单独授权）。
- **Q2（2026-09-21 生效，无人推翻）**：`frontend/.github/workflows/` 下两个 tracked 但宿主不读取的定义，按仓库既有习惯改名归档不删除（`*.prev-*` 口径），不直接 `git rm`；如需删除须先存 patch 并单独授权。
- **Q3（2026-09-21 主 agent 裁定，依据 P0 实测）**：静态分析两项分道处理——**SpotBugs 走甲**：去掉 `pom.xml:418` 字面量 `<skip>`，用插件原生 `<excludeFilterFile>` 承载**从一次真实运行落盘的** High 存量基线（P0 旁路测得的 10 条是缺 auxclasspath 的**下界**且无产物，不得直接抄用），使新增 High 立即变红；基线条目按"类 + bug 型 + 成员"最小粒度写，并配"登记了却不再命中 = 过期豁免"防呆（对齐 `KnownDriftRegistry` 先例）。**PMD 走乙**：保持关闭，豁免四要素已登记进 `docs/migration-runbook.md` **§6.7**（此前只存在于 `ba57e2b` 提交正文，这是第一次进被跟踪文档）。关闭理由按 P3 实测重排为三条：pom 从未写 `<rulesets>` → `pmd-rules.xml` 是**孤儿配置**、实跑用插件内置 quickstart（122 条违规）；该 ruleset 28 条 ref 中 10 条在 PMD 7.9.0 不可解析；阈值三处口径（pom 注释"≤5" / `failurePriority=4` / `PMD_MAX_VIOLATIONS=5`）互不相干——修复要动 `pmd-rules.xml` 并重定口径，**超出本提案文件面，另立项**。附带确认的硬约束：`-Dspotbugs.skip=false` / `-Dpmd.skip=false` 抬不动字面量 `<skip>`，唯一路径是改 pom。**裁定时的两条前提已被 P3 实测推翻并按 Why-2 补正为准**：`onlyAnalyze` 的 `com.slz.crm.**` 非法（去 skip 即硬错，该门禁自合入起从未跑过）、PMD 摘 skip 会跑且有产物。

## 风险

- **打开 spotbugs/pmd 可能一次性暴露大量违规**：先测后定夺（What Changes 第 2 条），必要时走"登记豁免 + 到期条件"而不是硬塞阈值；本轮不承诺两项一定同时打开。
- **`@{argLine}` 类静默失效风险**（`test-hygiene` 已踩过一次）：任何改 `pom.xml` 插件配置的步骤都要回归 JaCoCo 报告照常产出。
- **聚合门禁命令变长 → 执行者跳过**：新增的 `scripts/merge-gate.sh` 必须自带断言与明确失败出口（照 `fail-fast-gate.sh` 的既有口径），否则不如不建；若自测脚本写不出来，则该步降级为"仅改文档声明"。
- **无远端 = 无法证实 CI 真能跑绿**：本提案所有 CI 侧改动只能静态审查 + 本地等价命令验证，汇报时不得写成"CI 已验证通过"。

## Non-Goals

- 不接入远端仓库、不改 CI 触发配置、不动托管平台（外部写，需单独授权）。
- 不默认执行 Playwright e2e，不改 `frontend/e2e/**` 与 `docs/test-checkpoints.md` 累积机制。
- 不删除、不改写 Qoder 遥测钩子内容。
- 不做 `better-harness` 自身的会话来源根与 Memory 标题扫描可见性修复（owner 在 provider 侧，非仓库能力）；本轮评审已把这两项作为证据边界声明。
- 不重做 `add-frontend-ci`（lint / type-check job 已存在）、不重做 `close-permission-read-gap` 与 `drift-disposition` 已闭合的门禁。
- 不改任何业务/检索代码、不做指标看板、不调缓存参数。

## 验收

- **逐条判别式**（每项须给出命令与实际输出，不接受"应该好了"）：
  1. 生效 hooks 目录内的 `pre-commit` 可被追踪到转发调用 `frontend/.githooks/pre-commit`，且 Qoder 遥测钩子仍在位；并存在一次"故意提交坏前端文件被阻断"的实测记录（Q1 已选 A，故不再以 `core.hooksPath` 等于 `frontend/.githooks` 为判别式）。
  2. `mvn -B -ntp checkstyle:check spotbugs:check pmd:check spotless:check` 与 `pom.xml`、ci.yml 三者不再互相矛盾；ci.yml 里每个 env 变量都能 grep 到读者，或已被删除。
  3. ci.yml 头部与 git-workflow.md 的"阻止合入"表述与实际触发方式一致；聚合门禁命令一条可跑、失败时有具名出口。
  4. 一条命令跑完前端单元轨并在 `frontend-quality` 步骤列表里可见；`mvn -B -ntp test` 数字相对基线**不变**（只增不减口径下等于不变）。
  5. `grep -n "check_baseline" HANDOFF.md openspec/project.md openspec/git-workflow.md` 无命中，且这三份文档内 grep 不到任何 `surefire <数字> / failsafe <数字>` 形式的快照数字；`bash scripts/check-test-baseline.sh` 与文档口径一致。
- **回归**：`mvn -B -ntp test` 全绿；`scripts/tests/` 既有自测照常通过。
- **Git 收尾**：分支 `feature/operationalize-harness-gates`；提案三件套随首个提交入库；按任务组提交 `type(scope): 中文描述`；亲验全绿且 `git status` 干净（`work/mailbox/**` 等已知未跟踪件不提交也不删除）后 `--no-ff` 合入 master；汇报带 commit hash + 上述 5 条判别式的实际输出摘录；**不 push**。
