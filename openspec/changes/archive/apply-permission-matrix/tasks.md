# Tasks — apply-permission-matrix

> 执行契约见 `openspec/git-workflow.md`；权威拍板输入 = `docs/permission-matrix-audit.md`（audit-permission-matrix 已合入）。
> 当前基线：master surefire 615、failsafe 无 Docker 下限 13；门禁 `PermissionCoverageAuditIT` 已在线（PENDING_DECISION 57 条 = AI 34 + 报表 6 + 新发现 17）。
> 拍板已完成（2026-09-14，见 proposal.md 拍板记录）：D1=方案A、D2=复用 501/502/503、D3=纳入。
> 硬约束：全程 ¥0 外发（跑 failsafe IT 前设 DASHSCOPE_API_KEY 为空）；禁改共享 `init_data.sql`（用 in-test jdbcTemplate seeding）；禁改既有 V1..V25 迁移（补种只走 V26）。

## 对账回填说明（2026-09-22）

本提案代码早已合入 master（`--no-ff` 合并提交 **`fad493b`**，父提交 `897766a` + `a3911b7`；任务组提交
`d96e920` 0.x / `b402018` 1.x-2.x / `337de73` 3.x / `a3911b7` 4.x），但交付时 **tasks.md 未勾、HANDOFF/AGENTS 未回填**。
本轮做的是**逐项核验 + 补账**（不重跑实现）：每项行尾补「证据」= 文件:行号 或本轮命令实测。

**本轮自跑实测（三档口径以此为准）**

- 门禁 `PermissionCoverageAuditIT`（纯 JVM、无 Docker）：
  `controller=28（登记 28），端点=215，SECURED=195 / INTENTIONAL_OPEN=20 / PENDING_DECISION=0 / CRITICAL=0 / WARN=0`，2 tests 绿。
  取数方式：为拿到不受工作树污染的口径，在 master 上另开临时 worktree 单跑
  `mvn -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=PermissionCoverageAuditIT`（BUILD SUCCESS），随后删除 worktree。
- **3.6 原文的期望值 186/22/0 是错的，勿再引用**：它出自首轮 27 × 208 口径的推算，且把"转 INTENTIONAL_OPEN"记成 17 条（实际 15 条）。
  正确口径 **215 = 195 + 20 + 0**，勘误细节见 `docs/permission-matrix-audit.md` §6「口径勘误」。
- 聚合门禁本轮两跑：
  ① `--with-verify`（HEAD=`acf8363`）**未全绿，但三个红点全部可归因于并发 lane 的在途改动**（与权限矩阵无关）：
  `[unit]` 红在并发未格式化改动、`[it]` 红在并发改动编译失败、`[pmd-baseline]` 红在并发把 PMD 存量 325→308 需下调登记值；
  PASS 的是 `[spotbugs]` / `[pmd]` / `[baseline]` / `[frontend-unit]` / `[hook]` / `[bijection]`。
  ② 并发 lane 提交 `9eb7bfa`（分片D）之后，**HEAD=`9eb7bfa` 重跑默认序列全绿（EXIT=0，8 个子门禁全 PASS）**。
  该提交**未触及权限面**（可复核：`git diff acf8363 9eb7bfa --stat -- src/main/java/com/slz/crm/common/annotation src/main/java/com/slz/crm/server/interceptor src/test/java/com/slz/crm/integration/permission src/main/resources/db/migration` 输出为空；`UserController` 的 `@RequirePermission` 计数前后均为 7），故上面三档实测数对 `9eb7bfa` 同样成立。
  `--with-verify` 的 failsafe 本轮受"本机 Docker 守护进程未启动"限制（实测 `docker info` / `docker version` 均连不上 `npipe:////./pipe/dockerDesktopLinuxEngine`），未纳入第二次运行。

## 0. 拍板记录回填

- [x] 0.1 `docs/permission-matrix-audit.md` 新增 §6 拍板记录：D1=方案A（800 段）/ D2=复用 501/502/503 / D3=纳入；两个工程默认（全量业务角色授权保持现状访问面；template×2 转开放）一并留痕
      —— 证据：`docs/permission-matrix-audit.md:202` §6 标题 + `:207-211` D1/D2/D3 决策表 + `:213-219` 两个工程默认（角色授权策略 / template×2 转开放）。
- [x] 0.2 顺修报告既有笔误：§1 与 §4.3「新发现 22」→ 17（34+6+17=57 自洽）；§2 WARN 语义描述与实现对齐（WARN 非空即 fail）；`OpenEndpointRegistry` Javadoc 同步 17 口径
      —— 证据：§1 已为「新发现 17」（`:21-22`、`:28`）；§2 已写明「WARN 非空同样判 fail」（`:35-36`）；`OpenEndpointRegistry.java:15-16`/`:37` 已为「摸底 40 + 新发现 17 = 57」。
      **本轮另修 4 处交付时漏改的失实口径**（原文只改了"22→17"，其余没跟上）：① §1 四档分布 `208 / SECURED 146` → `215 / SECURED 153`（`:25-27`）；② §2 登记数 `27` → `28`（`:40`）；③ §3.1 标题与正文 `SECURED 146 / 其余 143` → `153 / 其余 150`（`:76-79`）；④ §4 前言 `:93-94` 与文末 `:238-240` 仍写"待拍板、禁止直接落地"，与 §6 已落地冲突 → 改为已落地实况。

## 1. AI 模块 34 端点（D1 方案A：800 段常量 + 挂注解）

- [x] 1.1 `PermissionOperates` 新增 800 段常量：`AI_ASSIST_VIEW(800)` / `AI_ASSIST_APPLY(801)` / `AI_ASSIST_HANDLE(802)` / `AI_CHAT_SESSION(803)` / `AI_CHAT_STREAM(804)` / `AI_CHAT_CANCEL(805)` / `AI_ACTION_VIEW(806)` / `AI_ACTION_CONFIRM(807)`（中文 Javadoc 标注「apply-permission-matrix 任务 1.1」，注明 AI 模块段）
      —— 证据：`src/main/java/com/slz/crm/common/enumeration/PermissionOperates.java:259`（段注释「AI 模块权限 (800-899)」）+ `:261-276` 八个常量与中文 Javadoc 齐全。
      ⚠️ **未达成项（纯注释溯源，无功能影响，本轮按硬约束未改 `src/main/java`）**：任务要求的 Javadoc 标注「apply-permission-matrix 任务 1.1」**实测缺失**——`grep -c "apply-permission-matrix" PermissionOperates.java` = 0；同文件 `:277` 的 900 常量带 `add-knowledge-admin-api 任务 2.1` 先例，可作为补齐时的格式参考。
- [x] 1.2 `AssistController`（23 端点）/ `AiChatController`（7）/ `AiActionController`（4）按报告 §4.1 方案A 列逐方法挂 `@RequirePermission`（只挂方法级；Javadoc 标注「apply-permission-matrix 任务 1.2」）
      —— 证据：注解数与提案标注数一致：`AssistController.java` 23/23（`:66`…`:326`）、`AiChatController.java` 7/7（`:55`…`:197`）、`AiActionController.java` 4/4（`:29`/`:41`/`:53`/`:68`）；抽查映射与 §4.1 方案A 逐条相符：`GET /assist/my`→800、`PUT /assist`→802、`POST /assist/apply`→801、`DELETE /assist/{id}/attachments`→802、`POST /ai/chat/stream`→804、`POST /ai/actions/{pendingId}/confirm`→807。
- [x] 1.3 新迁移 `V26__permission_seed.sql`（AI 段）：`permissions` 种 800-807（表结构与 INSERT 语法参照 V21 种植 1174-4045 的写法，V1 建表为准）+ `role_permission` 给全部业务角色授权（INSERT...SELECT 现有角色，排除 roleId 0/1/2 特殊角色——超管由 interceptor 直通、冻结/离职不授；具体角色口径执行时以种子/V1 实测为准，拿不准停下问用户）；头部注释标注「apply-permission-matrix 任务 1.3，D1 拍板方案A」
      —— 证据：`src/main/resources/db/migration/V26__permission_seed.sql:1` 头注释（含「任务 1.3，D1 拍板方案A」）+ `:18-26` 种 800-807 + `:28-35` 授权语句（`INSERT ... SELECT ... CROSS JOIN sys_role` + `r.id NOT IN (0,1,2)` + `r.is_deleted = b'0'`）；`git show --stat b402018` 只新增 V26，**未改 V1..V25**。
- [x] 1.4 `FlywayMigrationIT.EXPECTED_VERSIONS` 加 '26'，执行数断言同步
      —— 证据：`src/test/java/com/slz/crm/integration/FlywayMigrationIT.java:37-39` 登记集含 `"26"`（同轮已含后续 `"27"`/`"28"`）；`:85`/`:94` 两处断言用登记集比对，无需另改数字。真库实跑报告 `target/failsafe-reports/com.slz.crm.integration.FlywayMigrationIT.txt` = 3 tests / 0 failures。

## 2. 报表/统计 6 端点（D2 复用 501/502/503）

- [x] 2.1 `ReportController`：contract/business 挂 `REPORT_VIEW_REPORT(501)`；`DataStatisticsController`：opportunityStageDistribution/summary 挂 501、chartData/chart 挂 `REPORT_GENERATE_REPORT(502)`（Javadoc 标注「apply-permission-matrix 任务 2.1」）
      —— 证据：`ReportController.java:34`/`:60` → `REPORT_VIEW_REPORT`；`DataStatisticsController.java:98`/`:122` → 501、`:34`/`:63` → `REPORT_GENERATE_REPORT`；两文件提案标注 2/2、4/4，与报告 §4.2 建议表逐条一致。
- [x] 2.2 V26 同脚本补种段：`permissions` 501-504 + `role_permission` 授权（同任务 1.3 角色口径）
      —— 证据：`V26__permission_seed.sql:11-15` 种 501-504（VIEW/GENERATE/EXPORT/MANAGE_TEMPLATE），授权复用 `:29-35` 同一条语句（`p.id IN (501,502,503,504,800..807)`）；报告 §5.2 记的"501/502/503 常量在、端点裸奔"双漂移至此闭合。
- [x] 2.3 新注解端点 IT：in-test jdbcTemplate seeding 授权行（禁改共享 init_data.sql）——已授权非超管角色正向（code 1）+ 未授权角色反向（12002）；若既有 IT/冒烟依赖这些端点匿名可达，同样 seeding 修复
      —— 证据：`src/test/java/com/slz/crm/integration/controller/PermissionApplyReportIT.java`（5 个 `@Test`）：正向 501 读报表 `:92-99`、正向 501 综合统计 `:101-113`、正向 502 图表 JSON `:137-181`、反向 12002 两例 `:115-135`；`@Sql("/init_data.sql")` + 用例内 `jdbcTemplate` seeding 授权行 + `@Transactional` 回滚隔离（`:28-30`、`:58-90`），`git show --stat b402018` 无 `init_data.sql`，**未改共享种子**。真跑报告：5 tests / 0 failures / 0 skipped。

## 3. 新发现 17 端点消解（报告 §4.3）

- [x] 3.1 `DELETE /user` 挂 `SYSTEM_UPDATE_USER(603)`；`POST /user/find` 挂 `SYSTEM_VIEW_USER(602)`（与 UserController 其他端点口径一致）
      —— 证据：`src/main/java/com/slz/crm/server/controller/UserController.java:64-65`（`/user/find` → `SYSTEM_VIEW_USER`）、`:148-149`（`delete` → `SYSTEM_UPDATE_USER`），两处均带「apply-permission-matrix 任务 3.1」注释。
- [x] 3.2 自服务 4 项转 INTENTIONAL_OPEN：`POST /user/password` / `POST /user/update/my` / `GET /user/my` / `GET /user/options`（理由：自服务/协助人选择器）
      —— 证据：`src/test/java/com/slz/crm/integration/permission/OpenEndpointRegistry.java:98-107` 四条登记 + 理由。
- [x] 3.3 `GET /company/template` / `GET /contact/template` 转 INTENTIONAL_OPEN（理由：静态 Excel 模板无数据面，导入流程依赖，工程默认 2）
      —— 证据：同文件 `:108-120` 两条，理由写明「拍板工程默认 2」，**未挂 118/107**。
- [x] 3.4 `GET /contact/auditor` / `GET /role` 转 INTENTIONAL_OPEN（自查/下拉，与 `/permission/auditor` 同款）
      —— 证据：同文件 `:121-128` 两条。
- [x] 3.5 `DynamicConfigAdminController` 7 端点转 INTENTIONAL_OPEN（理由：服务层已强制 roleId=1 抛 96005，避免双重鉴权漂移）
      —— 证据：同文件 `:129-171` 七条（items 列表/详情/历史 + 更新 + 回滚 + 软删 + cache/refresh），理由统一。
- [x] 3.6 `OpenEndpointRegistry` PENDING_DECISION 区清空（57 条全消解）；`PermissionCoverageAuditIT` 真跑绿：SECURED 146→186 / INTENTIONAL_OPEN 5→22 / PENDING_DECISION 57→0
      —— 证据：`OpenEndpointRegistry.java:175-180` `buildPendingDecision()` 返回空表（注释「已全部消解…本区保持为空」）；门禁本轮实测 **SECURED 195 / INTENTIONAL_OPEN 20 / PENDING_DECISION 0**（+CRITICAL 0 / WARN 0 = 215），2 tests 绿。
      ⚠️ **口径更正（实测为准）**：任务原文的 `186 / 22 / 0` 是错的——它出自**首轮 27 × 208 口径**的推算，且把"转 INTENTIONAL_OPEN"记成 17 条（实际 15 条：自服务 4 + 模板 2 + 下拉自查 2 + DynamicConfig 7；§4.3 的 17 条里有 2 条是**挂注解**的 `DELETE /user` / `POST /user/find`）。相对口径实为 **SECURED 153→195（+42 = AI 34 + 报表 6 + user 2）、OPEN 5→20（+15）、PENDING 57→0**；差异另含 2026-09-18 `add-knowledge-admin-api` 补登记的 `KnowledgeAdminController` 7 端点（SECURED 基线 146→153）。详见 `docs/permission-matrix-audit.md` §6「口径勘误」。

## 4. 冻结/离职绕过修复（D3 纳入）

- [x] 4.1 `PermissionsInterceptor`：roleId=0 冻结 / roleId=2 离职状态检查移到 `@RequirePermission` 判空之前（中文注释标注「apply-permission-matrix 任务 4.1，D3 拍板纳入」）；注意保留 deptId 填充逻辑位置不变
      —— 证据：`src/main/java/com/slz/crm/server/interceptor/PermissionsInterceptor.java:67-69` 状态闸前置（注释含提案与任务号），`checkUserStatus(user)` 在 `checkPermission(...)`（`:71`）之前；`deptId` 填充仍在 `:60-65`、位置未变；状态闸实现 `:76-88`（roleId=0 → 12006 / roleId=2 → 12007 / 其他 → 状态异常）。
- [x] 4.2 反向 IT（Testcontainers 既有设施口径）：冻结/离职用户调零注解端点（如 `GET /user/my`）被拒（沿用既有错误码语义）；在职用户调零注解端点仍放行（现状口径防回归）
      —— 证据：`src/test/java/com/slz/crm/integration/controller/PermissionsInterceptorStatusIT.java`（3 个 `@Test`，`:80-105`）：冻结 roleId=0 → 12006、离职 roleId=2 → 12007、在职 → `code=1`；seeding 全在用例内（含 `NO_AUTO_VALUE_ON_ZERO` 处理 `:49-66`），未改 `init_data.sql`。
      ⚠️ **本轮未复跑**：本机 Docker 守护进程未启动——实测 `docker info` / `docker version` 两次均报连不上 `npipe:////./pipe/dockerDesktopLinuxEngine`，`ps -W | grep -i docker` 计数 0，故所有 `AbstractMySqlIT` 子类本轮只能判 skip。可用证据是**上一份真跑报告**（`target/failsafe-reports/`，mtime 2026-09-22 11:24，非本轮）：`PermissionApplyReportIT` 5 / `PermissionControllerIT` 3 / `PermissionsInterceptorStatusIT` 3，**0 failures / 0 skipped**。无 Docker 的兜底单测 `unit/server/interceptor/PermissionsInterceptorTest#preHandleShouldEnforceUserStatusGateWhenContextPresent`（`:50-72`）本轮单跑绿。
- [x] 4.3 回归：`PermissionControllerIT`、助手/聊天相关 IT 全绿（状态检查前置不破坏正常路径）
      —— 证据：同上真跑报告：`PermissionControllerIT` 3 tests / 0 failures；权限相关无 Docker 单测本轮在 master worktree 单跑 **17 tests / 0 failures / 0 skipped**（`PermissionCoverageComparatorTest` 6 + `PermissionCoverageScannerTest` 7 + `PermissionsInterceptorTest` 4）。
      ⚠️ **部分未本轮复验**：Docker 未启动，`AbstractMySqlIT` 系 IT 本轮无法重跑；待 Docker 起来后补一次 `mvn -B -ntp verify` 即可闭合。

## 5. 回归与收尾

- [x] 5.1 `mvn -B -ntp test` 全绿（surefire 615 → 615+N 实测计数）；`PermissionCoverageAuditIT` 绿；`FlywayMigrationIT` 绿（V26 加入后）；`ci.yml` 三处基线同步（口径A surefire、check_baseline、错误提示行；口径B failsafe 若新增 IT 按实测上调）
      —— 证据（本轮实测）：① `mvn -B -ntp test` 在 master 干净 worktree 单跑全绿 —— **Tests run=724，Failures=0，Errors=0，Skipped=0**（BUILD SUCCESS）；② `bash scripts/merge-gate.sh` 默认序列（HEAD=`9eb7bfa`）**EXIT=0 全绿**，其中 `[unit]` 同为 724/0/0/0、`[baseline]` 读数 `surefire 报告=142，Tests run=724，Failures=0，Errors=0，Skipped=0｜failsafe 报告=19，Tests run=66，Failures=0，Errors=0，Skipped=6`；③ `PermissionCoverageAuditIT` 2 tests 绿（本轮单跑）；④ `FlywayMigrationIT` 3 tests / 0 failures（真跑报告，V26 加入后）。
      ⚠️ **口径更正**：本项后半段"`ci.yml` 三处基线同步"**已过时**——自 `git-workflow.md` §4 口径迁移后，回归阈值唯一读者是 `scripts/test-baseline.txt`（当前 `surefire.tests=724` / `failsafe.tests=66`），`ci.yml` 只调用 `bash scripts/check-test-baseline.sh` 裁决、**不再含硬编码数字**（`grep -n "surefire\|failsafe" .github/workflows/ci.yml` 只剩注释）。本轮 `[baseline]` 子门禁读数与该文件一致（`报告=142，Tests run=724`｜`报告=19，Tests run=66，Skipped=6`）。
- [x] 5.2 `HANDOFF.md` 更新（57 端点消解记录、800 段常量、V26、冻结绕过修复、surefire 新基线）；AGENTS.md 门禁章节补「PENDING_DECISION 已清零」状态与 800 段常量说明
      —— 证据：本轮回填 `HANDOFF.md`：权限矩阵条目（首轮审计 → 落地实况 + 三档实测 195/20/0）、`§3` 未决事项、`§4` openspec 变更清单 均已改写；`AGENTS.md:84` 章节标题 + `:92-95` 新增「矩阵已落地（apply-permission-matrix）」四条（含 800 段常量清单、PENDING_DECISION 区已清零、状态闸前置、新端点只剩两条合法出路），并在 `:87` 机制条补状态闸位置与 deptId 不变。
      另：本轮顺带修掉 HANDOFF 里三处已失实记载 —— `add-paragraph-chunking`「尚未开始」→「已完成并合入 `ccbdbc1`（tasks 已全勾，默认策略仍 fixed）」、`frontend/` 工作区状态（已纳入跟踪、不再是在途未跟踪工作区）、迁移链补 `V28__add_source_to_customer_company`。
- [x] 5.3 git 收尾：分支 `feature/apply-permission-matrix`，提交按任务组（`type(scope): 中文描述`），亲验 `mvn -B -ntp test` + `git status` 干净后 `--no-ff` 合入 master，汇报带 commit hash + PENDING_DECISION 消解前后对照（57→0）+ 四档分布（SECURED 186 / OPEN 22）
      —— 证据：合并提交 **`fad493b`**（`git rev-list --parents -n 1 fad493b` = `897766a` + `a3911b7`，确为 `--no-ff` 双亲），`git merge-base --is-ancestor fad493b master` 成立；任务组提交 `d96e920`(0.x) / `b402018`(1.x,2.x) / `337de73`(3.x) / `a3911b7`(4.x)。**PENDING_DECISION 消解对照：57 → 0**。
      ⚠️ **口径更正**：任务原文的四档「SECURED 186 / OPEN 22」按首轮 27×208 口径推算，已过时；**当前实测四档 = SECURED 195 / INTENTIONAL_OPEN 20 / PENDING_DECISION 0 / CRITICAL 0 / WARN 0（28 controller × 215 端点）**。
