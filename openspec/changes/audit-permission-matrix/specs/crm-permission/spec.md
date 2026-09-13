# 规范增量：crm-permission

## ADDED Requirements

### Requirement: 端点权限覆盖显式制
每个 HTTP 端点（`@RestController`/`@Controller` 的方法级 mapping）MUST 满足以下三者之一：① 方法级 `@RequirePermission` 注解（类级注解不生效，`@Target(METHOD)`）；② 登记于 `OpenEndpointRegistry` 的 INTENTIONAL_OPEN 区（有意匿名或有意登录即可用，附理由）；③ 登记于 PENDING_DECISION 区（已知零注解，待权限映射拍板）。任何端点不满足 → `PermissionCoverageAuditIT` MUST 失败（注解缺失=拦截器静默放行的结构性掩盖从此有 CI 信号）。

#### Scenario: 新增零注解写端点被门禁拦截
- **WHEN** 新 controller 或新方法以 POST/PUT/DELETE 暴露且无 `@RequirePermission`、未登记
- **THEN** `PermissionCoverageAuditIT` 失败，输出类名/方法/HTTP 方法/路径（CRITICAL 档）

#### Scenario: 新增零注解读端点产生 WARN
- **WHEN** 新方法以 GET 暴露且无注解、未登记
- **THEN** 门禁产出 WARN 记录（不失败），进报告待定夺

#### Scenario: 有意开放端点登记后放行
- **WHEN** 端点（如 `getMyPermission`）登记 INTENTIONAL_OPEN 且附理由
- **THEN** 门禁放行，报告归档为有意开放

### Requirement: controller 登记制防漏审
`PermissionCoverageScanner` 扫描到的 controller 数量 MUST 与登记常量一致；新 controller 合入 MUST 同步登记，否则门禁失败（同 schema 审计的实体登记制）。

#### Scenario: 漏登记新 controller
- **WHEN** 扫描到 27 个 controller 而登记 26 个
- **THEN** `PermissionCoverageAuditIT` 失败，提示补登记

### Requirement: 零行为变更边界
本提案 MUST NOT 改变任何运行期行为：不新增/修改任何 `@RequirePermission` 注解、不改 `PermissionsInterceptor`、不改 `WebMvcConfiguration` 路由与排除配置、不改 `init_data.sql`。首轮审计报告中的建议权限映射未经用户拍板 MUST NOT 直接落地。

#### Scenario: 审计不碰运行期
- **WHEN** 提案合入后运行全量测试
- **THEN** surefire/failsafe 仅因新增测试设施而计数变化，业务断言（含既有 `*ControllerIT`）行为不变

### Requirement: 审计报告为拍板权威输入
`docs/permission-matrix-audit.md` MUST 覆盖全量端点四档分布（SECURED / INTENTIONAL_OPEN / PENDING_DECISION / CRITICAL），40 个零注解端点逐一带建议映射（复用既有常量 or 新增 800 段 AI 常量 or 产品决策登录即可用），并记录冻结/离职用户绕过缺陷；报告合入后执行方 MUST 停下等用户拍板，映射落地另立提案。

#### Scenario: 已闭合缺口自然校验
- **WHEN** 报告产出 `PermissionController` 覆盖档位
- **THEN** list / addORDeletePermissionsToRole / getByRole 三端点落 SECURED 档（close-permission-read-gap 成果不被回退）
