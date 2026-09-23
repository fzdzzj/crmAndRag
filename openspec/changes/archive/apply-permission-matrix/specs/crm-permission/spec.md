# 规范增量：crm-permission

## ADDED Requirements

### Requirement: PENDING_DECISION 端点全量消解（拍板已定）
`audit-permission-matrix` 门禁登记的 PENDING_DECISION 端点（57 条 = AI 34 + 报表 6 + 新发现 17）MUST 按已拍板结论（2026-09-14：D1=方案A / D2=复用 501/502/503 / D3=纳入）逐条消解：40 条挂方法级 `@RequirePermission`（转 SECURED）+ 17 条登记 `OpenEndpointRegistry` INTENTIONAL_OPEN（附理由）。消解后 `PermissionCoverageAuditIT` MUST 输出 PENDING_DECISION=0；拍板结论与两个工程默认 MUST 留痕于 `docs/permission-matrix-audit.md` §6。

#### Scenario: AI 端点挂 800 段注解后落 SECURED
- **WHEN** Assist/AiChat/AiAction 34 端点挂 800 段 `@RequirePermission` 并完成 V26 种植
- **THEN** 门禁输出对应端点落 SECURED 档，PENDING_DECISION 减 34，门禁绿

#### Scenario: 报表端点复用 501/502 后角色可读
- **WHEN** 已授权业务角色调用 `/report/contract` 或 `/dataStatistics/summary`
- **THEN** 通过鉴权（code 1）；未授权角色得到 12002

#### Scenario: 转 INTENTIONAL_OPEN 的 17 端点登记有理由
- **WHEN** 自服务/模板/下拉/DynamicConfig 端点移入 INTENTIONAL_OPEN
- **THEN** 每条登记附理由（自服务、静态模板、服务层已闸等），门禁绿

### Requirement: AI 模块 800 段权限常量与种植
`PermissionOperates` 新增 800 段常量（800-807）MUST 与既有 id 段不冲突；MUST 随 V26 迁移种入 `permissions` 表并给全部业务角色落 `role_permission` 授权（工程默认 1：保持现状访问面——超管 roleId=1 由 interceptor 直通无需授权行，roleId=0/2 特殊角色不授）；`FlywayMigrationIT.EXPECTED_VERSIONS` MUST 含 '26'。挂注解与种植 MUST 同轮交付，否则"登录即退化"（功能从可用变 12002）。

#### Scenario: 常量与种植同步
- **WHEN** 新常量合入并挂注解、V26 应用
- **THEN** `permissions` 表存在 800-807 与 501-504 行、业务角色存在授权、`FlywayMigrationIT` 绿、门禁绿

### Requirement: 冻结/离职状态检查前置（D3 纳入）
`PermissionsInterceptor` 的用户状态检查（roleId=0 冻结 / roleId=2 离职）MUST 在 `@RequirePermission` 判空之前执行——任何登录请求先过状态闸，再判注解；零注解端点不再对冻结/离职用户放行。

#### Scenario: 冻结用户调零注解端点被拒
- **WHEN** roleId=0 用户调用零注解端点（如 `GET /user/my`）
- **THEN** 请求被拒（沿用既有错误码语义），而非静默放行

#### Scenario: 在职用户调零注解端点仍放行
- **WHEN** 在职用户调用零注解端点
- **THEN** 行为与审计现状一致（放行），不因状态检查前置而改变

#### Scenario: 正常路径回归
- **WHEN** 在职用户调用既有注解端点（如 PermissionController 三端点）
- **THEN** 鉴权行为与修复前一致（`PermissionControllerIT` 全绿）

### Requirement: 共享种子禁改与前向迁移
本提案 MUST NOT 修改共享 `init_data.sql`；测试需的角色授权用 in-test jdbcTemplate seeding（@Transactional rollback）隔离。禁改既有 V1..V25 迁移脚本；补种只走 `V26__permission_seed.sql`（Flyway 校 checksum）。

#### Scenario: 测试种子隔离
- **WHEN** IT 需要某角色具备 800/501 权限
- **THEN** 在用例内 jdbcTemplate 插入，不回写共享种子文件
