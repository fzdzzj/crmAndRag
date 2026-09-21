# Tasks — operationalize-harness-gates

> 执行契约见 `openspec/git-workflow.md`（分支 / 提交 / 合并序列、本机坑：无 remote 禁 push）。本提案**不新增 Java 用例**，`scripts/test-baseline.txt` 的 surefire/failsafe 数字应保持不变；任一任务导致其变化即视为越界。
> 前置事实（2026-09-21 实测，执行前自行复验一遍再动手）：`core.hooksPath` = `<仓根>\.git\hooks`；`pom.xml:418`/`:452` 为 `<skip>true</skip>`；`ci.yml` 无 `check_baseline`；`frontend/package.json` 无 `test` 脚本。
> 与上一轮评审的对账（第 1 轮复核结论）：`docs/harness-summary-2026-09-19.md` 的 9 项里 #1/#2/#4/#7/#8 已修复（实测 `ModelProviderImplDashScopeIT:41`、`FlywayMigrationIT:29`、`logback-spring.xml`、`frontend/.gitignore:41`、`AbstractMySqlIT` 的 `assumeTrue`），**#3 与 #5 未修**，即本提案组 2/组 5/组 6 的靶子。开工前不需要重做它们的对账。
> 阻塞关系：组 1 → 组 3、组 4（须先拿到实测结论）；组 2 的 Q1 已拍板为**选项 A**（见 proposal.md 拍板记录），不再阻塞；组 5、组 6 可与组 2-4 并行；组 7 收尾必须最后。
> 每次只处理 1 个 step，做完立刻把 `[ ]` 改 `[x]` 并在行尾补实测证据摘要。

## 1. 定夺前的实测（零代码改动，产出后续分支决策的依据）

- [x] 1.1 跑 `mvn -B -ntp spotbugs:check`，记录真实结论（是否被 configuration 级 skip 吸收、违规数、耗时）。若命令本身无法产出结论，写明"该门禁当前不可判定"的具体原因
      → P0 实测 + 主 agent 复验：退出 0 但 `[INFO] Spotbugs plugin skipped`，**不可判定**；旁路直跑 High=10（6 类/4 型，缺 auxclasspath 故为**下界**，且 target/ 无产物未落盘）。主 agent 静态复验确认 `pom.xml:418` 是字面量 `<skip>true</skip>`（非 `${spotbugs.skip}`），故 `-Dspotbugs.skip=false` 抬不动 → **只能改 pom**。既有天然读者：`effort=Max` / `threshold=High` / `onlyAnalyze=com.slz.crm.**`（`:414-417`）
- [x] 1.2 跑 `mvn -B -ntp pmd:check`，同上记录违规数与报告路径
      → P0 实测：违规数**测不到**，无 pmd.xml/csv/html 产物；根因=`src/main/resources/pmd-rules.xml` 28 条 ref 中 10 条在 PMD 7.9.0 已不存在（主 agent 复验 ruleset 确为 28 条 ref）。`pom.xml:452` 同为字面量 `<skip>`；真实严格度读者是 `failurePriority=4`（`:446`），而 `:438` 注释写"阈值≤5"、ci.yml 写 `PMD_MAX_VIOLATIONS=5`——三处口径互不相干
- [x] 1.3 在 `frontend/` 跑 `pnpm exec vitest run`，记录用例数 / 通过数 / 失败清单 / 耗时；确认它是否能在无浏览器、无后端条件下独立跑通（决定组 4 能否进 CI）
      → P0 实测：**16 文件 / 163 通过 / 0 失败，85.72s**，无浏览器·无后端·无 Docker 可跑；`vite.config.ts:66` 已排除 `e2e/**`（主 agent 复验）；`node_modules` 原本在位，未装依赖。**副作用已处置**：vitest 触发 unplugin-vue-router 重写 `frontend/typed-router.d.ts`，主 agent 证明其与 HEAD 仅差 CR 后 `git checkout` 还原，开工基线恢复为仅 `?? openspec/changes/operationalize-harness-gates/` + `?? work/`
- [x] 1.4 跑 `git config --show-origin --get core.hooksPath` 与 `ls .git/hooks`，复验提案 Why-1 的两项事实仍然成立（防止期间被 IDE 改写）
      → 仍成立：`core.hooksPath` = `<仓根>\.git\hooks`，目录内仅 `post-commit`/`post-checkout` 两个 `|| true`，无 `pre-commit`
- [x] 1.5 把 1.1-1.4 的结论写进本文件行尾，并回填 `proposal.md` 的 Q2 处置口径（Q1 已裁定为选项 A，无需再问）
      → **主 agent 裁定（2026-09-21）**：SpotBugs 走**甲**（去 `<skip>`）+ `src/main/resources/spotbugs-exclude.xml` 承载当前 High 基线（每条含类/型/理由/复测命令/到期条件），使新出现的 High 立即变红；PMD 走**乙**（继续 `<skip>`，登记豁免到 `docs/migration-runbook.md` §6），因修 ruleset 越出 P3 文件面 → 单列遗留。Q2 未被推翻，按默认"改名归档不删除"生效。P4 的 ci.yml env 处置裁定见 P4 派发词

## 2. 提交期机械门禁恢复（Q1 已裁定：选项 A）

- [x] 2.1 扩展 `frontend/scripts/setup-hooks.mjs` 落地选项 A：**不改** `core.hooksPath`（保持现状指向 `.git/hooks`），改为在生效 hooks 目录内安装 `pre-commit` 转发器；安装器仍是唯一入口、必须幂等，且 MUST NOT 覆盖或删除生效目录内已存在的其它钩子（Qoder 的 `post-commit`/`post-checkout`）
      → P1 完成 + 主 agent 复验：`setup-hooks.mjs` 重写为"解析生效 hooks 目录 → 装转发器"（含 `--uninstall`），全程未写 `core.hooksPath`；`ls .git/hooks` 现为 `pre-commit` + 原有两个遥测钩子（哈希不变）；遇非本安装器同名文件 exit 1 且对方逐字未改
- [x] 2.2 写转发器本体：`<生效 hooks 目录>/pre-commit` 定位仓根后调用 `frontend/.githooks/pre-commit`（沿用其自带的 staged 短路逻辑）；`frontend/.githooks/pre-commit` 不存在时**静默跳过而非报错**；转发器不得硬编码 Qoder 钩子里的版本化绝对路径
      → 已装（该文件不入版本控制，属预期）；两次安装转发器 sha256 一致（`f4085d38`）；只暂存后端文件时 `sh .git/hooks/pre-commit` 退出 0（643ms），确认不误伤
- [x] 2.3 更新 `frontend/AGENTS.md`「Git Hook Policy」：期望值从 `frontend/.githooks` 改写为"生效 hooks 目录内存在转发到 `frontend/.githooks/pre-commit` 的 `pre-commit`"，并给出安装、复验、卸载三条命令与判别式；与根 `AGENTS.md` 路线段交叉引用一句（只指路，不复制正文）
      → 已改 `frontend/AGENTS.md`（diff 23 行，仅 Git Hook Policy + Commit Workflow 两段，其余段未动）
- [x] 2.4 判别式实测：造一个会被 `pnpm precommit:check` 拒绝的临时前端改动 → `git commit` **必须被阻断** → 记录退出码与输出 → 还原改动（不得 `--no-verify` 绕过）
      → 真阻断成立：`git commit` 退出 **1**，由 lint 规则 `@typescript-eslint/no-explicit-any` 触发，输出含 `Running frontend pre-commit checks (1 staged file(s)...)`；HEAD 仍 `ff0f26e` 未产生提交；未用 `--no-verify`；探针已删、暂存清空
- [x] 2.5 生效面复验并记录：`git worktree list`（实测 17 个，全部为链接工作树，`.git` 指回 `.git/worktrees/<name>`）+ 在任一 worktree 内执行 `git config --get core.hooksPath` 确认与主仓同源 → 结论应为"装一次全生效，仅全新 clone 需重装"；据此在 `frontend/AGENTS.md` 写一句安装时机说明
      → 抽查 2 个链接工作树，`git rev-parse --git-path hooks` 均返回同一主仓 `.git\hooks` → **装一次全生效**已实证
      → 本包口径结论：**当前只会自动红的门禁只有"暂存了 `frontend/` 文件的那次 git commit"**（跑 `precommit:check`=lint+type-check）；`mvn test/verify`、`check-test-baseline`、ci.yml、spotbugs/pmd 仍全靠人跑
      → 遗留移交 P4：把"转发器在位且真能阻断"接进 `scripts/merge-gate.sh` 判别式（属 P4 文件面，P1 未越界）

## 3. 静态分析门禁去矛盾（依赖 1.1 / 1.2）

- [x] 3.1 依 1.1/1.2 结论二选一并写明理由：**（甲）去 `<skip>` 启用** → 只改 `pom.xml` spotbugs 与 pmd 两个 plugin 块，阈值落进唯一读者（`pom.xml` 配置或新增判定脚本，二者取一并在 `ci.yml` 注释指明读者位置）；**（乙）登记豁免** → 落在既有台账 `docs/migration-runbook.md` §6「本地验证方式」（§6.2 已是"哪些检查真跑 / 优雅跳过"的同类账目，§6.3 已是 opt-in 台账），记"项 + 理由 + 复测命令 + 到期触发条件"，**不新开同义文档**；同时把 `ci.yml` 该步骤收窄为只跑真有读者的检查
      → P3 完成 + 主 agent 复验：SpotBugs 走甲（`<skip>` 已删、新增 `<excludeFilterFile>src/main/resources/spotbugs-exclude.xml</excludeFilterFile>`；全仓仅剩 `pom.xml:457` 一处 `<skip>`＝pmd）。基线 **12 条 / 7 类 / 5 型**，无 `<Or>`、无 regex、无包级通配（文件注释里另有 3 处 `<Match>` 字样，实体计数须按元素数）。`target/spotbugsXml.xml` 213KB 落盘为凭
      → **P3 推翻两条前提（已回写 proposal 的 Why-2 与 Q3）**：① 原 `onlyAnalyze` 值 `com.slz.crm.**` 在 SpotBugs 4.8.6 属非法模式，去 skip 即 `BUILD FAILURE`（`Dangling meta character '*'`）→ 该门禁**自合入起从未跑过一次**，并非"只是被 skip 关掉"；已改合法写法 `com.slz.crm.-`。② PMD 摘掉 `<skip>` **会跑**（退出 1、`target/pmd.xml` 有产物、122 条违规），P0 报的"无产物"不成立；真因是 pom 从未写 `<rulesets>`，`src/main/resources/pmd-rules.xml` 是**孤儿配置**，实跑用插件内置 quickstart（122 条里 6 个规则不在声明的 28 条内）
      → PMD 走乙已登记 **§6.7**：三条理由各带实测数字 + 复测命令 + 到期触发条件；这是该豁免第一次进被跟踪文档（此前只活在 `ba57e2b` 提交正文）
- [ ] 3.2 消除装饰性阈值：ci.yml 里 `CHECKSTYLE_MAX_VIOLATIONS`/`SPOTBUGS_MAX_HIGH`/`PMD_MAX_VIOLATIONS`/`SPOTLESS_APPLY_DIFF` 四个 env，逐个确认要么有 grep 得到的读者，要么删除；不允许"留着以后用"
      → **移交 P4 执行**（`ci.yml` 归 P4 独占），逐条处置裁定见 `dispatch/P4-ci-mergegate.md` 任务 3
- [x] 3.3 若 `pom.xml` 有任何插件配置变更，回归确认 JaCoCo 报告照常产出（`test-hygiene` 曾遇 `@{argLine}` 静默失效坑），并确认未触碰 surefire/failsafe/checkstyle/spotless 段
      → 复验 `git diff --stat`：`pom.xml` 仅 9 行变动、两个 hunk 全在 spotbugs 块内；JaCoCo 正常（`crm.exec`、359 类）；`mvn -B -ntp test` 全绿且 surefire=724 与基线一致
- [x] 3.4 判别式实测：`mvn -B -ntp checkstyle:check spotbugs:check pmd:check spotless:check` 的结论与 `pom.xml`、`ci.yml` 三者不再互相矛盾，逐条贴出证据
      → 正向：`spotbugs:check` 退出 0、日志无 `skipped`、`BugInstance size is 0`。**反向（主 agent 特别要求，证明门禁真能变红）**：注入不在白名单的 `GateP3Probe`（`DM_DEFAULT_ENCODING`）→ 退出 **1** 并指名类与行号 → 还原后复绿退出 0、探针残留 0。防呆双射校验 12↔12、每条恰命中 1、过期豁免 0
      → 遗留：① `pmd-rules.xml` 28 条 ref 中 10 条不可解析（9 条 PMD 7.9.0 已不存在 + 1 条文件名写成 `codestyle.java/`）与 `<rulesets>` 接线 → 另立项；② staleness 校验器暂在 `work/` 下，须移入 `scripts/tests/` 才算耐久 → 交 P4 落位

## 4. 前端单元轨接入自动门禁（依赖 1.3）

- [x] 4.1 在 `frontend/package.json` 增加最小 `test` 脚本，命令口径复用 `frontend/AGENTS.md` 已声明的 `pnpm exec vitest run`（不新造命令、不改 vitest 配置语义）
      → P4 完成 + 主 agent 复验：`scripts.test = "vitest run"`（16→17 个脚本），未改 vitest 配置
- [x] 4.2 在根 `.github/workflows/ci.yml` 的 `frontend-quality` job 增加**一步**执行该脚本（放在 Install/Generate 之后、体积预算之前）；不新增 job，不动已落地的 lint / type-check / build 四步
      → 已加为该 job 第 8 步（Type-check 后、build 前）；job 名随之改为 `Frontend Test / Lint / Type-check / Build Budget`；未新建 job
- [x] 4.3 按 Q2 默认口径处置 `frontend/.github/workflows/ci.yml` 与 `e2e-official.yml`：改名归档保留（`*.prev-*` 风格），若用户要求删除则先存 patch 再删（需单独授权）
      → `git mv` 为 `*.prev-host-unread`，`git diff --cached --name-status` 仅这两条 `R100`（内容零改动、未提交）
      → **改名造成的连带漂移由主 agent 收**：`frontend/docs/ci.md` 的工作流一览原按旧文件名描述（且写"push 到 main"，与本仓 `master` 不符），已改写为"权威定义=根 ci.yml 的 frontend-quality + 本仓无触发通道 + 归档件为何归档"，并给旧配置节加了适用范围警示
- [x] 4.4 判别式实测：本地一条命令（如 `cd frontend && pnpm test`）跑完单元轨；确认 Playwright/e2e **未被**接入默认路径
      → 实跑 **163/163 通过**（16 文件全量，84.20s，退出 0）；`vite.config.ts:66` 显式排除 `e2e/**`，实跑 e2e 命中 0
      → 主 agent 补写 `frontend/AGENTS.md`：新增 `pnpm test` 一条，并明写"`precommit:check` 只含 lint+type-check，**不含 Vitest**"——防止 agent 误以为提交钩子已覆盖单元轨

### 4bis. R3 实质收口（主 agent 裁定，回应 P4 遗留②）

P4 正确指出：接了 CI 一步在本仓仍是"不会自动执行"（无触发通道），而唯一会自己红的 pre-commit 不含 Vitest → R3 只满足了字面。裁定：把前端单元轨并入本地合并门禁。

- [x] 4bis.1 `scripts/merge-gate.sh` 增加 `[frontend-unit]` 子门禁（默认 `pnpm -C frontend test`，带 `MERGE_GATE_CMD_FRONTEND` 测试钩子）；`frontend/node_modules` 缺失时**不判红**（装依赖属联网动作、需授权），但在通过态尾部显式打印"本证据未覆盖前端 Vitest 单元轨"，不许静默跳过
- [x] 4bis.2 自测同步扩到 **42 条断言**（原 34 + 8）：`[frontend-unit]` 失败→聚合非零且指名、缺依赖→零退出但显式声明未覆盖、依赖在位→不误报未覆盖。主 agent 实跑 `bash scripts/tests/merge-gate-selftest.sh` 退出 0
- [x] 4bis.3 附带处理 P4 遗留③：vitest 会重写 tracked 的 `frontend/typed-router.d.ts`（纯 CR）。门禁现在跑完自检该路径，dirty 时打 `NOTE` 并给出不提交该噪声的还原命令；主 agent 每轮验收后照此还原

## 5. CI 声明与真实交付边界对齐

- [x] 5.1 改 `.github/workflows/ci.yml` 头部注释：写明本仓当前无触发通道、该文件何时生效（镜像到托管平台后），并把"任一阶段失败即阻止合入"改为指向本地必跑聚合命令的表述。只改注释，不改 job 语义
      → P4 完成 + 主 agent 复验：`ci.yml` +53/-11；静态检查步骤的四个装饰性 env **全部删除**（该步骤已无 `env:` 块），`pmd:check` 一并移出命令，改为逐步写明"判定依据在 pom 哪几行"（checkstyle `:387/:388`、spotbugs `:418/:423`、spotless `:488-500`），并指路 `docs/migration-runbook.md §6.7` 与双射校验脚本
      → P4 附带发现（主 agent 认同并保留）：命令前置 `compile` 是必需的——独立 checkout 无前置构建，而 **`target/classes` 缺失时 spotbugs 会对空集恒绿**；同时实测 `mvn compile` 会连带触发 checkstyle-check 与 spotless-check
- [x] 5.2 建 `scripts/merge-gate.sh`：把 `git-workflow.md` 现有"缺一不合"散文收敛为一条命令，子门禁 = `mvn -B -ntp test` + `bash scripts/check-test-baseline.sh` + `mvn -B -ntp spotbugs:check`（P3 已启用，必须纳入），按需 `mvn -B -ntp verify`；另加两项**存在性判别**（P1/P3 移交）：生效 hooks 目录内 `pre-commit` 能追踪到 `frontend/.githooks/pre-commit`（用 `git rev-parse --git-path hooks` 定位）、SpotBugs 基线双射校验可跑。风格照 `scripts/fail-fast-gate.sh`（自带断言、失败具名出口、不靠 grep 拼凑）
      → 已建（125 行，`[unit]/[spotbugs]/[it]/[baseline]/[hook]/[bijection]` + 主 agent 追加的 `[frontend-unit]`）。`[hook]` 按 spec R2 做**三道**判别（文件在位 / 内容可追踪到被转发目标 / 目标本体存在），只查配置值或只看文件存在都不算
      → 主 agent 独立反向判别（不走子 agent 的自测）：把 hooks 目录指向空目录 → 退出 1 且指名 `[hook]`；放一个"存在但不转发"的假 pre-commit → **仍红**；真跑 → `[bijection]` 打印 `12 条豁免与 12 条 High 一一对应`
- [x] 5.3 建 `scripts/tests/merge-gate-selftest.sh`：至少覆盖"任一子门禁失败时聚合脚本必须非零退出"与"全过时零退出"两个场景（沿用 `check-test-baseline-selftest.sh` 的先例）。**若写不出可信自测，本组降级为只改 5.1 的声明，不留下无自测的门禁脚本**
      → 已建，未降级：8 组场景、P4 交付 34 条断言；主 agent 追加 4bis 后扩到 **42 条**，实跑退出 0。测试钩子沿用 `check-test-baseline.sh` 的 `BASELINE_*` 先例（`MERGE_GATE_CMD_*` / `MERGE_GATE_HOOKS_DIR` / `MERGE_GATE_FRONTEND_MODULES`），不新造注入机制
- [x] 5.4 把 `openspec/git-workflow.md` 的合并前必跑序列指向 `scripts/merge-gate.sh`，并保留既有的成本闸门口径（真外发模型调用需先获用户授权、`RAG_BENCHMARK_REAL=1` 才发外网）
      → 已改（+19/-6）：`grep -c merge-gate` = 2；`check_baseline` = **0 命中**；`(surefire|failsafe) <数字>` = **0 命中**；`RAG_BENCHMARK_REAL` 与"无 remote 禁 push"口径逐字保留
- [x] 5.5 **耐久化 P3 的过期豁免校验器**：P3 的双射校验脚本目前写在 `work/`（会话暂存区，不入库），必须移入 `scripts/tests/`（建议名 `spotbugs-exclude-staleness-check.sh`）、改成可独立运行且退出码有意义，并被 `merge-gate.sh` 调用。留在 `work/` 不算完成——那正是本提案要消灭的"结论活在临时记录里"的形态
      → P4 落位：`scripts/tests/spotbugs-exclude-staleness-check.sh` + `scripts/tests/spotbugs-high-baseline.tsv`（快照）；按 **XML 元素**计数（绕开 naive grep=15 的坑），带 `SPOTBUGS_POM_FILE` 测试钩子，被 `[bijection]` 调用，自测场景 8 证明"接线缺失→校验器判 2→门禁读成红并透传原因"。`work/` 下的 `.mjs` 原件仍在，属会话暂存待清理

## 6. 治理文档口径归一到唯一权威源

- [x] 6.1 改 `HANDOFF.md:12`（**活文档**，非历史记录，必须跟）与 `openspec/project.md:42`：验收命令口径保留，权威源改为 `scripts/test-baseline.txt` + `bash scripts/check-test-baseline.sh`，删掉"以 ci.yml 为准"与写死的 surefire/failsafe 数字（与 `docs/migration-runbook.md:72` 同口径，不重复其正文）
      → P2 完成 + 主 agent 复验。**P2 多查到 5 处同型指针**（`HANDOFF.md:80/:83/:91/:132/:174`，原提案只点了 `:12`），已一并改指权威文件；`:88/:93/:94/:95` 的"ci.yml 已同步上调"属**过去时动作记录**，正确地未改写。复验：`HANDOFF.md`/`openspec/project.md`/`AGENTS.md` 内 `以 ci.yml 为准` 命中 **0**、`grep -nE "(surefire|failsafe) [0-9]+"` **无匹配**、`check_baseline` **0 命中**；`bash scripts/check-test-baseline.sh` 实跑通过（读自该文件：724/66）
- [x] 6.2 改 `openspec/git-workflow.md:4`：同上；并明确"无 remote、禁 push"仍是硬约束
      → P4 完成（该文件归它独占）：`:4` 的"基线数字一律以 ci.yml 为准 + surefire 657/failsafe 13"改为指向 `scripts/test-baseline.txt` + 判定脚本；"无 remote、禁止 push"口径逐字保留
- [x] 6.3 改 `openspec/git-workflow.md:33-35`：删除对已消失 CI 步骤（`check_baseline target/surefire-reports`、错误提示行数字）的三处同步要求，替换为"一次带 `--update` 的真实运行写入"，并复述 `scripts/check-test-baseline.sh:13` 的"不要手改"既有约束
      → P4 完成。注意实际有三处 stale 指针（`:4`、`:33` 的"以 ci.yml 为准"、`:35` 的"当前值以 ci.yml 为准"），原提案只点了 `:4` 与 `:34`
- [x] 6.4 同类漂移收口（一行改动、同一口径）：`AGENTS.md` 的 Flyway 号段行今日已过期——实测 `ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1` = `V28`，文中仍写"当前最高 V27"→ 改为只给该实测命令、不写数字。**只改这一行**；若你认为 `AGENTS.md` 归主线治理不宜由本提案动，勾掉本步并在汇报里列为遗留
      → P2 完成 + 主 agent 复验：`AGENTS.md` diff = **1 增 1 删**（只动号段行），新行给出实测命令并写明"本文不记号段上界数字，任何'当前最高 Vxx'的写法都会过期"；文中剩余的 `V27` 只在 `:95` 的"含 V27 及之后新增者"下界表述里，属正确用法
- [x] 6.5 判别式实测：`grep -n "check_baseline" HANDOFF.md openspec/project.md openspec/git-workflow.md` 命中 0；这三份文档内 grep 不到任何 `surefire <数字> / failsafe <数字>` 形式的快照；四份治理文档（含 `AGENTS.md`）与 `docs/migration-runbook.md` 对同一问题给出**同一个**权威 owner（逐文件贴行号）
      → 主 agent 跨包联测（P2/P4 分属不同文件面，只能由主线合验）：`check_baseline` 三份全 **0**；`(surefire|failsafe) [0-9]+` 在 `HANDOFF.md`/`openspec/project.md`/`AGENTS.md`/`openspec/git-workflow.md` **全部无匹配**；`grep -l scripts/test-baseline.txt` 命中 `HANDOFF.md`、`openspec/project.md`、`AGENTS.md`、`openspec/git-workflow.md`、`docs/migration-runbook.md` **五份同一 owner**；`openspec/git-workflow.md` 内 `test-baseline.txt` 出现 4 次并已 2 次指向 `merge-gate`
- [x] 6.6 历史快照处置：`drift-disposition/tasks.md` 4.1/4.2、`add-frontend-ci/*`、`apply-permission-matrix/*` 等在途/已落地提案正文里的旧数字属**历史记录**，一律不改写；只在 `openspec/project.md` 一句话说明"历史提案正文中的数字属当时快照，以 `scripts/test-baseline.txt` 与 `git log -p -- scripts/test-baseline.txt` 为准"
      → P2 完成：`openspec/project.md` +2/-1 落该声明；历史提案正文未被触碰（`git diff --stat` 内无 `openspec/changes/**` 旧提案）。P2 另报告 `openspec/project.md:41` 保留一处**带日期的 V27 快照**，落在 R5"带日期且声明不作口径"允许场景内，未强改（可选：与 AGENTS.md 新口径一句话对齐）

## 7. 回归与 Git 收尾

- [x] 7.1 `mvn -B -ntp test` 全绿，且 surefire/failsafe 计数与 `scripts/test-baseline.txt` 记录值一致（本提案不应增减 Java 用例）
      → 真实全量 `bash scripts/merge-gate.sh` 于 HEAD `ff0f26e` 跑通，退出 **0**：`[unit]` Tests run **724, F0/E0/S0**（=基线 724，未增减）、`[spotbugs]` `BugInstance size is 0`（无 skipped）、`[baseline]`、`[frontend-unit]`、`[hook]`、`[bijection]` `12↔12` 全 PASS
      → **口径限制（不可回避，脚本头部亦已声明）**：本轮未跑 `[it]`（默认跳过、本机无 Docker），`[baseline]` 的 failsafe 侧读的是上一轮遗留报告（66/skipped 6）。要在failsafe上取新鲜证据须 `--with-verify` + Docker
- [x] 7.2 `bash scripts/check-test-baseline.sh` 与 `scripts/tests/check-test-baseline-selftest.sh`（及新增的 merge-gate 自测）全部通过
      → 主 agent 亲跑：`check-test-baseline-selftest.sh` 退出 0（**34 条断言**）；`merge-gate-selftest.sh` 退出 0（**42 条断言**，含本提案追加的 8 条 `[frontend-unit]`）；`[baseline]` 在真实门禁内通过
- [x] 7.3 逐项复核 proposal.md「验收」5 条判别式，每条附命令与实际输出摘录
      → ①钩子：`.git/hooks/pre-commit` 在位且可追踪到 `frontend/.githooks/pre-commit`，真阻断实测 exit 1（lint `no-explicit-any`），假转发器仍红；②静态：`pom.xml` 仅剩 `:457` 一处 `<skip>`(pmd)，`ci.yml` 无 `env:` 块、`pmd:check` 已移出，逐步写明 pom 读者行号，PMD 豁免四要素在 runbook §6.7；③声明：`ci.yml` 头部与 `git-workflow.md` 改指本地 `merge-gate`（`grep -c merge-gate`=2），`check_baseline`=0；④前端：`pnpm test`=163/163 绿并入 CI 第 8 步与 `[frontend-unit]`，e2e 未接入默认；⑤文档：五份治理文档同指 `scripts/test-baseline.txt`，无内嵌快照数字
      → 连带处置：`merge-gate` 跑完 vitest 会再次把 `frontend/typed-router.d.ts` 刷成 CR-only 差异（已按门禁 NOTE 的 `diff -q` 证明确认正文未变并 `git checkout` 还原）
- [x] 7.4 分支 `feature/operationalize-harness-gates`，按任务组提交 `type(scope): 中文描述`（提案三件套随首个提交入库）；`git status` 干净（`work/mailbox/**` 等已知未跟踪件不提交、不删除）后 `--no-ff` 合入 master
      → 7 个提交：`bb79148` 提案三件套 ｜ `e009f0f` 钩子 ｜ `e14df31` 静态门禁 ｜ `dee2a55` 后续提案 ｜ `8db51cb` CI+前端轨（含 2 个 `R100` 归档改名）｜ `3575239` merge-gate ｜ `fd20e28` 治理文档。汇总 29 文件 / +1654 / -61
      → **钩子在真实提交里首次自动跑起来**：`e009f0f` 与 `8db51cb` 两次提交都触发了 `pnpm precommit:check`（lint + type-check）并全绿通过；`bb79148`（无前端文件暂存）正确短路，证明不误伤后端提交
      → 分批提交一律用显式 pathspec（`git commit -F … -- <路径>`），索引里预暂存的 2 个改名直到 `8db51cb` 才被认领，未被卷进无关提交；`work/`、`target/` 全程未入库
- [x] 7.5 汇报：commit hash + 5 条判别式输出摘录 + Q1 最终选择的落地形态 + "哪些门禁现在会自己变红、哪些仍靠人跑"的清单；**不 push**
      → 已交回主 agent 会话：7 个实现提交 + 收尾提交；Q1=选项 A；生效清单见本文件组 2/组 3/组 4/组 5 行尾与 7.6
- [x] 7.6 **合并后重跑全量门禁抓出一条平台相关缺陷（2026-09-21，已修）**：在合并后的 master（`123c4a9`）上重跑 `bash scripts/merge-gate.sh` → `[bijection]` 红，`High=12 <Match>=12 MISS=11 STALE=11`，且 **MISS 与 STALE 是同一批 11 行**。根因：`scripts/tests/spotbugs-high-baseline.tsv` 与 `spotbugs-exclude.xml` 一旦被 git 跟踪，`core.autocrlf=true` 的 checkout 会经 smudge 把它们变成 CRLF，`\r` 挂在第 4 列尾巴上使 `comm` 判为不同行；只有文件末行（无尾换行）能配上，故 12 里恰好 1 对"通过"。
      → 为什么交付时没发现：**台账未入库前是 LF，P3/P4 的实测与自测都在那个形态上跑，所以当时确实绿**；是我 `git add` + 切分支 checkout 之后字节才变。结论：台账类文件的判别必须在"被检出的形态"下验证，不能只在生成它的会话里验证。
      → 修法（读入侧归一，不让数据迁就平台）：`spotbugs-exclude-staleness-check.sh` 的 `truth_rows()` 两个分支与 `ledger` 解析一律 `tr -d '\r'`；回归锁加在 `merge-gate-selftest.sh` 场景 10（CRLF 台账+ CRLF 快照必须仍 BIJECTION_OK；把成员名改坏必须变红；并断言本场景不得污染被跟踪文件），断言数 42 → **47**。
      → 已把同一要求写进 `wire-pmd-ruleset` 的 P2 派发词（`pmd-violation-baseline.txt` 的读取侧必须容 CRLF，并照场景 10 做正/反两判）。
      → 遗留：更彻底的做法是给这类台账加 `.gitattributes`（`text eol=lf`）从源头禁止转换；本轮先按读入侧归一，避免顺带改动全仓文本归因。
      → Q1 落地形态=选项 A：`core.hooksPath` 未改动，生效目录内新增可追踪转发器，Qoder 遥测钩子原样在位
      → **会自己变红的**：① 暂存了 `frontend/` 文件的那次 `git commit`（lint + type-check，实测两次触发）；② `bash scripts/merge-gate.sh` 的 6 个子门禁（含 SpotBugs 新 High、基线只增不减、前端单元轨、钩子在位、双射过期）——但它是"人跑一次、一次全查"，不是自动触发
      → **仍全靠人跑的**：`mvn test/verify`、`check-test-baseline.sh`、`merge-gate.sh` 本身、整份 `ci.yml`（无远端 → 无触发通道）
      → **仍关闭的**：PMD（`pom.xml:457` 字面量 skip，豁免见 runbook §6.7，修复另见 `wire-pmd-ruleset`，其 Q1 待拍板、推荐 `maxAllowedViolations` 只降不升）
      → 未 push；本轮未跑 `[it]`，failsafe 结论读自上一轮报告
