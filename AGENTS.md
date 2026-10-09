# AGENTS.md — crmAndRag 执行约束

适用于本目录及其所有子目录。目标：多步任务自己推进到干完，只在真正需要用户拍板时停下。

## 规则 1 · 多步任务必须先建计划

- 任务需要 2 个以上动作（读 → 改 → 验证 算一条链）时，第一个动作就是用你当前会话里实际可用的规划 / 任务清单能力登记步骤，每步 5-7 词。
- 每完成一步立刻更新那份清单：该步标 `completed`，下一步标 `in_progress`。全程只能有一个 `in_progress`。
- 没实际执行完的步骤不准标 `completed`。
- 中途换路线，重写这份计划并说明改动原因。
- 例外：纯问答、或一条命令就能答完的事，不建计划。

## 规则 2 · 严禁用"我接下来去做 X"结束 turn

- 一个 turn 只有两种情况可以结束：任务真的做完了；确实被用户决策卡住（见下）。
- 禁止发出预告句后不调工具。以下句式后面必须紧跟同 turn 内的工具调用，否则就别写这句：
  `明白，继续推进。先看…` / `现在我去…` / `接下来我会…` / `让我先…` / `下一步是…`
- 如果你已经说出下一步要跑什么，就在本 turn 把它跑掉。不要把它留给用户回一句"继续"。
- 禁止用问句结尾征求"可以继续吗 / 要我做吗"。以下操作才需要事先确认：
  删除或覆盖数据、`git push` / `reset` / 改写历史、改数据库结构、新增依赖、外发网络请求、任何不可逆动作。
- 其余情况选可逆的方案直接做，并在最终汇报里写明你做了什么假设。

## 规则 3 · 结论必须来自本轮实际输出

- 说"X 为空 / 不存在 / 失败了"之前，本 turn 必须有对应的工具输出支撑。上一轮的推断不算本轮验证。
- 写完文件必须在同 turn 读回来或列目录确认它真的存在，否则不算写完。
- 写大文件（如 `application.yml`）用一次整体写入写完，不要分段拼接，避免分段写入未持久化。

## 收尾自检（每次想结束 turn 前逐条过）

1. 计划里还有 `in_progress` 或 `pending` 吗？有 → 继续干，别结束这个 turn。
2. 我这句话是不是预告？是 → 立刻补上工具调用。
3. 我是不是在没跑工具的情况下断言了结果？是 → 先跑再答。

## 项目路线（只指路，不复制内容）

### 构建与校验

- `mvn -B -ntp test` —— surefire，只跑 `**/*Test.java`（排除 `**/*IT.java`），不需 Docker，不访问外网。
- `mvn -B -ntp verify` —— 再追加 failsafe，跑 `**/*IT.java` 与 `**/*IntegrationTest.java`，其中 Testcontainers MySQL 系列**需本地 Docker**。真外发 IT 需显式 opt-in（`RAG_BENCHMARK_REAL=1`），详见 runbook §6.3。
- 两者与 `.github/workflows/ci.yml` 的三段门禁同源（阶段1 surefire → 阶段2 failsafe → 阶段3 回归基线）。阶段3 的判定逻辑与阈值不在 YAML 里：本地直接跑 `bash scripts/check-test-baseline.sh` 就能复现 CI 结论，阈值存 `scripts/test-baseline.txt`，只允许用 `--update` 从一次真实运行写入。
- 坑：`-Dit.test=...` 会**覆盖** pom 里 failsafe 的 `<includes>`。写成 `-Dit.test=!XxxIT` 不是“排除一个”，而是让 failsafe 把全量单测再跑一遍。
- 本地无 Docker 时 `mvn verify` 的真实结果、哪些用例会跳、哪些会直接报错，读 `docs/migration-runbook.md` 第 6 节。**先读它再下“已验证”结论**。
- **别假定本机有没有 Docker，`docker info` 一条命令就能实测**（2026-09-21 实测在线）。把"本机没 Docker"当默认前提曾让一条门禁记录把"没跑 `[it]`"记成环境所限，实际是没开 `--with-verify`。

### 静态分析门禁（checkstyle / spotbugs / spotless / pmd）

- **数字一律不写在本文与 `.github/workflows/ci.yml` 里**。每条口径只有一个读者，位置在 `pom.xml` 对应插件块；要看当前值就去读 pom 与台账（本文只指路）。
- PMD：尺子 = `src/main/resources/pmd-rules.xml`（由 pom pmd 块的 `<rulesets>` 加载，`grep -n "<ruleset>src" pom.xml` 定位），哪些条计入 = 同块 `<failurePriority>`，计入多少条算失败 = 同块 `<maxAllowedViolations>`（全仓唯一条数读者）。存量台账 `scripts/tests/pmd-violation-baseline.txt` 只允许 `bash scripts/tests/pmd-baseline-check.sh --update` 从一次真实 `mvn -B -ntp pmd:check` 写入且**只许下调**；过期/漂移判别跑 `bash scripts/tests/pmd-baseline-check.sh`（注意：`pmd:check` 只在实测**严格大于**登记值时才红，"该下调了"这一项它自己看不到，必须靠本脚本）。
- SpotBugs 同构：读者 = pom 该块的 `threshold` + `<excludeFilterFile>`，台账 `src/main/resources/spotbugs-exclude.xml` + `scripts/tests/spotbugs-high-baseline.tsv`，双射校验 `bash scripts/tests/spotbugs-exclude-staleness-check.sh`。
- `[pmd]` 与 `[pmd-baseline]` 在 `scripts/merge-gate.sh` 里是**显式单点调用**，不等 `[it]`（`mvn verify`）顺带——pmd 绑 verify 而默认序列不跑 verify，这是 `harness-gates` 已确认的坑。
- 启用状态、豁免历史（三条理由的消除过程）与复测命令：`docs/migration-runbook.md` §6.7；变更规格：`openspec/changes/archive/wire-pmd-ruleset/`。

### 环境与迁移入口

- `docs/migration-runbook.md`：`.env.example` → `.env`、`SPRING_PROFILES_ACTIVE=prod`、Flyway 号段归属。
- **Flyway 号段现状（以 `ls src/main/resources/db/migration` 实测为准）**：`V1__baseline` 基座 + 个位数历史段 `V3`/`V4`/`V4_1`/`V5`/`V6`（早期 lane 的 `V2x`/`V3x`/`V4x`/`V5x`/`V6x` 十位号段规划**从未启用**，lane 归属见 runbook §1 与 `spec/.../agent-execution-plan.md §5`）；自 V21 起改用顺序号，**下一可用号只以实测为准**（本文不记号段上界数字，任何"当前最高 Vxx"的写法都会过期）：`ls src/main/resources/db/migration | sed 's/__.*//' | sort -V | tail -1`。
- 库结构唯一真相源 = `src/main/resources/db/migration`；**禁改已合入脚本**（Flyway 校 checksum），改错出 `V(n+1)__fix_xxx.sql`。
- 回退：**不提供 DROP 回滚**，回退 = 恢复迁移前的数据库快照（runbook 第 4.1 步的 dump）。

### 工作树拓扑与同步纪律（权威树 vs 协作树）

- **权威工作树**（`crmAndRag-merge-add-knowledge-admin-api`）：
  - 角色：主干所在，代码实现、分支检出、所有门禁运行（Maven / Docker / 门禁脚本）、合并与 push 唯一法定工作树。
  - 判别命令：`git branch --show-current` 为 `master`，且 `git remote -v` 存在 `origin`。
- **主协作树 / 观察树**（`crmAndRag`）：
  - 角色：承载 `docs/main-agent-logbook.md`（实时执行日志，未跟踪）与 `work/`（任务卡与交接快照）；`docs/main-agent-execution.md` 已收敛为 master 上的方法论手册，严禁在此直接修改 `src/` 或执行代码合并。`frontend/typed-router.d.ts` 为他人改动，严禁碰触。
- **分支与合并纪律**：
  - 子 agent 实现代码一律从权威树 `master` 检出 `feature/<card-name>` 分支；
  - 验证全绿后以 `git merge --no-ff` 合入 master，保留分支；
  - 严禁未经 owner 显式授权执行 `git push`！
- **草稿层与交接规约**：
  - `work/` 仅用于承载任务卡（`work/task-card-*.md`）与跨会话交接快照（`work/handoff-*.md`），临时产物受 `.gitignore` 规则收敛，禁止以未跟踪状态散落污染工作树。

### 前端与在途变更规格

- 前端（`frontend/`）的 pnpm 命令、pre-commit 钩子与代码约定见 `frontend/AGENTS.md`；CI 里 `frontend-quality` job 跑 `pnpm lint:check` + `pnpm type-check:check`。
- 在途变更规格位于 `openspec/changes/`（提案/tasks/验收三件套，归档在 `openspec/changes/archive/`）；与 `spec/changes/` 的分工**待 owner 确认**，改检索链路前两个目录都先看。

### 权威上下文 owner

都在 `spec/changes/add-crm-rag-fusion-platform/` 下，改对应领域前先读：

- `proposal.md` —— 变更范围与目标
- `design-decisions.md` —— 架构决策及其取舍
- `contracts-frozen.md` —— 已冻结的接口/契约，改动需先解冻
- `db-table-coordination.md` —— 跳 lane 库表归属，谁建谁用

### 模块边界（`src/main/java/com/slz/crm`）

- `server` —— CRM 业务（controller/service/mapper）与 AI 助手（`server/ai`）
- `knowledge` —— 知识库/RAG：文档摄取、向量化、检索、存储
- `platform` —— 平台治理：配额、token、审计、对账、韧性、健康、安全、跟踪、生命周期、动态配置
- `common` —— 注解、枚举、工具、异常、过滤器、结果包装
- `pojo` —— entity / dto / vo / ao / excel
- `quality` —— RAG 质量评测（benchmark 与评分）

## 已闭合的权限缺口与端点矩阵落地（close-permission-read-gap / audit-permission-matrix / apply-permission-matrix，均已合入 master）

- **已闭合**：`GET /permission/list` 与 `GET /permission/getByRole` 已加 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（取值复用 606，读写同权，用户已拍板，不新增 608 常量），任何登录用户不再能枚举全量权限清单。
- **机制（保留说明）**：鉴权靠方法级注解 `com.slz.crm.common.annotation.RequirePermission`（`@Target(METHOD)`，打在类上不生效），由 `PermissionsInterceptor#preHandle` 执行（`WebMvcConfiguration#addInterceptors` 注册）；**注解缺失时拦截器直接放行**，校验不过才抛 `ErrorCode.PERMISSION_DENIED`（code 12002）。放行前先过**用户状态闸**（冻结 roleId=0 → 12006 / 离职 roleId=2 → 12007 / 其他非正常状态 → 状态异常），该闸自 apply-permission-matrix 起位于注解判空**之前**（详见下条）；`deptId` 填充仍在权限判断之前、位置未变。
- **当前状态**：`PermissionController` 三处 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（list / addORDeletePermissionsToRole / getByRole）。
- **测试**：`src/test/java/com/slz/crm/integration/controller/PermissionControllerIT.java` 两个 `@Disabled` 已移除并启用（2 反向 + 1 正向共 3 绿，本地 Docker 实测）。
- **不放开**：`getMyPermission`（自查）、`/auditor`（审批人下拉）仍为业务必需的开放接口，不在收紧范围。
- **覆盖门禁（audit-permission-matrix，已合入）**：`src/test/java/com/slz/crm/integration/permission/PermissionCoverageAuditIT` 永久门禁（纯 JVM 静态扫描，无 Docker，本地与 CI 均真跑）——每个端点强制三选一：方法级 `@RequirePermission` / `OpenEndpointRegistry` INTENTIONAL_OPEN 登记 / PENDING_DECISION 登记，写语义裸奔端点直接红；**新增 controller 必须同步登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`**，否则门禁红。首轮审计报告 `docs/permission-matrix-audit.md`。
- **矩阵已落地（apply-permission-matrix，已合入 master `fad493b`）**：首轮 57 条 PENDING_DECISION（= 摸底 40 + 新发现 17）**已全部消解，PENDING_DECISION 区已清零**（`OpenEndpointRegistry#buildPendingDecision` 返回空表，任何新零注解端点直接触发门禁 CRITICAL/WARN）。落地内容四条：
  1. **800 段 AI 模块权限常量**（D1 方案A）：`PermissionOperates` 新增 `AI_ASSIST_VIEW(800)` / `AI_ASSIST_APPLY(801)` / `AI_ASSIST_HANDLE(802)` / `AI_CHAT_SESSION(803)` / `AI_CHAT_STREAM(804)` / `AI_CHAT_CANCEL(805)` / `AI_ACTION_VIEW(806)` / `AI_ACTION_CONFIRM(807)`，Assist(23)/AiChat(7)/AiAction(4) 三 controller 逐方法挂注解；
  2. **报表权限激活**（D2）：Report 挂 501、DataStatistics 按 501/502 挂，`V26__permission_seed.sql` 补种 501-504 与 800-807 并给全部业务角色授权（roleId 0/1/2 特殊角色不授，超管由拦截器直通）；
  3. **冻结/离职绕过修复**（D3）：用户状态检查已**前置**到 `@RequirePermission` 判空之前（`PermissionsInterceptor#authorize`），零注解端点不再被冻结/离职用户绕过，反向 IT `PermissionsInterceptorStatusIT` 覆盖；
  4. 拍板记录与实测四档分布见 `docs/permission-matrix-audit.md` §6（**本文件不复制门禁计数**）。
- **新 controller/端点加入时的校验面**：门禁三档（SECURED / INTENTIONAL_OPEN / PENDING_DECISION）里 PENDING_DECISION 已关闭，所以新零注解端点**只有两条合法出路**——挂方法级注解，或登记 INTENTIONAL_OPEN 并附理由；登记表变更后 `PermissionCoverageComparatorTest` / `PermissionCoverageScannerTest` 会一并校验。

  ```bash
  grep -n "@RequirePermission" src/main/java/com/slz/crm/server/controller/PermissionController.java
  ```

## 已闭合的 schema 漂移定夺（drift-disposition，已合入）

- **门禁**：`src/test/java/com/slz/crm/integration/schema/SchemaDriftAuditIT`（真 MySQL CRITICAL 非空即 fail，Docker assumeTrue 守卫）。实体↔迁移链真库漂移：
  - **CRITICAL**：实体表/列在真库缺失（运行期必炸）→ fail；
  - **KNOWN**：WARN/INFO 命中 `KnownDriftRegistry`（7 项已定夺豁免，2026-09-14，W1-W5 类型不亲和 + I1 生成列 + I2 预留表），仅计数；
  - **NEW**：未命中登记的 WARN/INFO，显式打印提醒定夺（不失败）。
- **新漂移处置契约**：新出现的 WARN/INFO 漂移走 NEW 登记流程——要么修订 `KnownDriftRegistry` 登记豁免，要么先停下向用户要授权处置；**禁任其累积**。单测防呆 `SchemaDriftComparator.unmatchedKnownDrifts` 保证每项登记必须仍产出真实漂移，登记过期/写错即报错。
- **边界**：不做任何类型对齐改造、不删生成列/预留表、不为预留表补实体、不动 CRITICAL 门禁语义与迁移链（**一切已合入 master 的脚本禁改**，含 V27 及之后新增者）。
