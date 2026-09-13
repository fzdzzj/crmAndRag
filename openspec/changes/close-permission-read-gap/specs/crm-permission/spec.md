# 规范增量：crm-permission

## ADDED Requirements

### Requirement: 权限目录读取接口鉴权
`GET /permission/list` 与 `GET /permission/getByRole` MUST 标注 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（读写同权，用户已拍板复用 606，不新增查看类常量）；未持有 606 的角色调用 MUST 收到 `PERMISSION_DENIED`（code 12002）。`GET /permission/getMyPermission`（自查权限）与 `GET /permission/auditor`（审批人下拉）MUST 保持对任何登录用户开放。

#### Scenario: 无 606 角色被拒
- **WHEN** 未持有 606 的登录用户调用 `/permission/list` 或 `/permission/getByRole`
- **THEN** 返回 code 12002（权限不足），不泄露权限目录

#### Scenario: 606 持有者不受损
- **WHEN** 被授予 606 的非超管角色（roleId ≠ 1）调用上述任一接口
- **THEN** 正常返回 code 1，存量权限管理流程不受影响

#### Scenario: 开放端点不受波及
- **WHEN** 任意登录用户调用 `/permission/getMyPermission` 或 `/permission/auditor`
- **THEN** 不因本变更引入鉴权（自查与审批人下拉为业务必需开放）

### Requirement: 缺口闭合的验收口径
缺口闭合 MUST 以 `PermissionControllerIT` 的再启用为机器验收：两个原 `@Disabled` 反向用例（12002）+ 一个正向兼容用例（授予 606 的非超管角色得 200/code 1）全绿；正向用例种子 MUST 在用例事务内自建（不改共享 `init_data.sql`）。

#### Scenario: 注解覆盖核对
- **WHEN** 在仓库根执行 `grep -n "@RequirePermission" src/main/java/com/slz/crm/server/controller/PermissionController.java`
- **THEN** 恰好 3 处命中（list / addORDeletePermissionsToRole / getByRole），AGENTS.md 的未闭合缺口章节随之闭合
