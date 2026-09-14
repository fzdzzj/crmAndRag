# Tasks — apply-permission-matrix

> 执行契约见 `openspec/git-workflow.md`；权威拍板输入 = `docs/permission-matrix-audit.md`（audit-permission-matrix 已合入）。
> 当前基线：master surefire 615、failsafe 无 Docker 下限 13；门禁 `PermissionCoverageAuditIT` 已在线（PENDING_DECISION 57 条 = AI 34 + 报表 6 + 新发现 17）。
> 拍板已完成（2026-09-14，见 proposal.md 拍板记录）：D1=方案A、D2=复用 501/502/503、D3=纳入。
> 硬约束：全程 ¥0 外发（跑 failsafe IT 前设 DASHSCOPE_API_KEY 为空）；禁改共享 `init_data.sql`（用 in-test jdbcTemplate seeding）；禁改既有 V1..V25 迁移（补种只走 V26）。

## 0. 拍板记录回填

- [ ] 0.1 `docs/permission-matrix-audit.md` 新增 §6 拍板记录：D1=方案A（800 段）/ D2=复用 501/502/503 / D3=纳入；两个工程默认（全量业务角色授权保持现状访问面；template×2 转开放）一并留痕
- [ ] 0.2 顺修报告既有笔误：§1 与 §4.3「新发现 22」→ 17（34+6+17=57 自洽）；§2 WARN 语义描述与实现对齐（WARN 非空即 fail）；`OpenEndpointRegistry` Javadoc 同步 17 口径

## 1. AI 模块 34 端点（D1 方案A：800 段常量 + 挂注解）

- [ ] 1.1 `PermissionOperates` 新增 800 段常量：`AI_ASSIST_VIEW(800)` / `AI_ASSIST_APPLY(801)` / `AI_ASSIST_HANDLE(802)` / `AI_CHAT_SESSION(803)` / `AI_CHAT_STREAM(804)` / `AI_CHAT_CANCEL(805)` / `AI_ACTION_VIEW(806)` / `AI_ACTION_CONFIRM(807)`（中文 Javadoc 标注「apply-permission-matrix 任务 1.1」，注明 AI 模块段）
- [ ] 1.2 `AssistController`（23 端点）/ `AiChatController`（7）/ `AiActionController`（4）按报告 §4.1 方案A 列逐方法挂 `@RequirePermission`（只挂方法级；Javadoc 标注「apply-permission-matrix 任务 1.2」）
- [ ] 1.3 新迁移 `V26__permission_seed.sql`（AI 段）：`permissions` 种 800-807（表结构与 INSERT 语法参照 V21 种植 1174-4045 的写法，V1 建表为准）+ `role_permission` 给全部业务角色授权（INSERT...SELECT 现有角色，排除 roleId 0/1/2 特殊角色——超管由 interceptor 直通、冻结/离职不授；具体角色口径执行时以种子/V1 实测为准，拿不准停下问用户）；头部注释标注「apply-permission-matrix 任务 1.3，D1 拍板方案A」
- [ ] 1.4 `FlywayMigrationIT.EXPECTED_VERSIONS` 加 '26'，执行数断言同步

## 2. 报表/统计 6 端点（D2 复用 501/502/503）

- [ ] 2.1 `ReportController`：contract/business 挂 `REPORT_VIEW_REPORT(501)`；`DataStatisticsController`：opportunityStageDistribution/summary 挂 501、chartData/chart 挂 `REPORT_GENERATE_REPORT(502)`（Javadoc 标注「apply-permission-matrix 任务 2.1」）
- [ ] 2.2 V26 同脚本补种段：`permissions` 501-504 + `role_permission` 授权（同任务 1.3 角色口径）
- [ ] 2.3 新注解端点 IT：in-test jdbcTemplate seeding 授权行（禁改共享 init_data.sql）——已授权非超管角色正向（code 1）+ 未授权角色反向（12002）；若既有 IT/冒烟依赖这些端点匿名可达，同样 seeding 修复

## 3. 新发现 17 端点消解（报告 §4.3）

- [ ] 3.1 `DELETE /user` 挂 `SYSTEM_UPDATE_USER(603)`；`POST /user/find` 挂 `SYSTEM_VIEW_USER(602)`（与 UserController 其他端点口径一致）
- [ ] 3.2 自服务 4 项转 INTENTIONAL_OPEN：`POST /user/password` / `POST /user/update/my` / `GET /user/my` / `GET /user/options`（理由：自服务/协助人选择器）
- [ ] 3.3 `GET /company/template` / `GET /contact/template` 转 INTENTIONAL_OPEN（理由：静态 Excel 模板无数据面，导入流程依赖，工程默认 2）
- [ ] 3.4 `GET /contact/auditor` / `GET /role` 转 INTENTIONAL_OPEN（自查/下拉，与 `/permission/auditor` 同款）
- [ ] 3.5 `DynamicConfigAdminController` 7 端点转 INTENTIONAL_OPEN（理由：服务层已强制 roleId=1 抛 96005，避免双重鉴权漂移）
- [ ] 3.6 `OpenEndpointRegistry` PENDING_DECISION 区清空（57 条全消解）；`PermissionCoverageAuditIT` 真跑绿：SECURED 146→186 / INTENTIONAL_OPEN 5→22 / PENDING_DECISION 57→0

## 4. 冻结/离职绕过修复（D3 纳入）

- [ ] 4.1 `PermissionsInterceptor`：roleId=0 冻结 / roleId=2 离职状态检查移到 `@RequirePermission` 判空之前（中文注释标注「apply-permission-matrix 任务 4.1，D3 拍板纳入」）；注意保留 deptId 填充逻辑位置不变
- [ ] 4.2 反向 IT（Testcontainers 既有设施口径）：冻结/离职用户调零注解端点（如 `GET /user/my`）被拒（沿用既有错误码语义）；在职用户调零注解端点仍放行（现状口径防回归）
- [ ] 4.3 回归：`PermissionControllerIT`、助手/聊天相关 IT 全绿（状态检查前置不破坏正常路径）

## 5. 回归与收尾

- [ ] 5.1 `mvn -B -ntp test` 全绿（surefire 615 → 615+N 实测计数）；`PermissionCoverageAuditIT` 绿；`FlywayMigrationIT` 绿（V26 加入后）；`ci.yml` 三处基线同步（口径A surefire、check_baseline、错误提示行；口径B failsafe 若新增 IT 按实测上调）
- [ ] 5.2 `HANDOFF.md` 更新（57 端点消解记录、800 段常量、V26、冻结绕过修复、surefire 新基线）；AGENTS.md 门禁章节补「PENDING_DECISION 已清零」状态与 800 段常量说明
- [ ] 5.3 git 收尾：分支 `feature/apply-permission-matrix`，提交按任务组（`type(scope): 中文描述`），亲验 `mvn -B -ntp test` + `git status` 干净后 `--no-ff` 合入 master，汇报带 commit hash + PENDING_DECISION 消解前后对照（57→0）+ 四档分布（SECURED 186 / OPEN 22）
