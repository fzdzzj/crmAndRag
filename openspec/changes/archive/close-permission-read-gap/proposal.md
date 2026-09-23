# 提案：闭合权限目录读取接口的授权缺口（复用 606）

> 变更 ID：`close-permission-read-gap` ｜ 能力域：`crm-permission` ｜ 序列：安全债收口（无前置依赖）
> 来源：AGENTS.md「未闭合的授权缺口」＋ `PermissionControllerIT` 两个 `@Disabled` 理由串。**用户已授权本行为变更，并已拍板注解取值：复用 `SYSTEM_ASSIGN_PERMISSION(606)`，不新增 608。**

## Why

1. **信息暴露已登记在案**：`GET /permission/list`（[PermissionController.java:44-48](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/server/controller/PermissionController.java#L44-L48)）与 `GET /permission/getByRole`（L67-70）没有任何权限注解——本项目鉴权机制为方法级 `@RequirePermission`（`@Target(METHOD)`），由 `PermissionsInterceptor#preHandle` 强制执行，**注解缺失时拦截器直接放行**。现状 = 任何登录用户可枚举全量权限目录（系统权限清单 + 各角色持有情况）。
2. **启用条件已全部满足**：`PermissionControllerIT` 两个 `@Disabled` 理由串写明三项机械核对条件——① list() 加注解；② getByRole() 加注解；③ 取值二选一（复用 606 / 新增常量）。本提案三项全落（取值已由用户拍板复用 606）。
3. **复用 606 的依据**（用户已确认）：两接口本就是"给角色配权限"界面的数据源，写侧（L57 分配接口）已由 606 把关，读写同权 = 工作流内聚；零种子/零角色授权/零前端同步改动，有 606 的存量角色行为不变；符合枚举头注释的合并原则（"查看操作合并为统一的查看权限"）。

## What Changes

### 1. 注解落地（src/main，行为变更核心）
- `list()` 与 `getByRole()` 各加 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`，Javadoc 标注提案号与"复用 606（读写同权）"决策。
- **不动**：`getMyPermission`（自查权限，任何登录用户合法）、`/auditor`（审批人下拉，普通用户发起审批需选人，业务必需的开放）。

### 2. IT 启用 + 正向兼容用例（测试域）
- 移除 `PermissionControllerIT` 两个 `@Disabled`；类 Javadoc 的"未闭合缺口"长说明重写为已闭合记录。
- 新增 1 条正向用例：**非超管角色**被授予 606 后两接口返回 200——证明注解值与库表按 ID 比对（`RoleAO.hasPermission` 按 ID）对齐、存量 606 持有者不受影响。种子走**用例内 jdbcTemplate**（参照 `normalUserToken()` 模式：sys_role + permissions(606) + role_permissions + sys_user roleId≠1），`@Transactional` 回滚，**不改共享的 `init_data.sql`**（零波及其他 IT）。
- 反向用例沿用 `TestRole.NORMAL`（roleId=3 无任何授权）断言 12002。

### 3. 文档与基线
- `AGENTS.md`「未闭合的授权缺口」章节按其自述的核对命令闭合（3 处 `@RequirePermission` 命中后移除 `@Disabled` 的既定路径）。
- 本地 Docker 实测 `PermissionControllerIT` 3 绿（2 反向 + 1 正向）；`ci.yml` 口径B 推算注释同步（**基线 12 不动**——本类 IT 依赖 Docker，不进无 Docker 下限）。
- surefire **602 不变**（本提案零新增单测），亲验全绿。
- `HANDOFF.md` 增补闭合记录。

## Impact

- **产品行为变更（授权已取得）**：无 606 的角色调用 `list`/`getByRole` 将开始收到 12002（`PERMISSION_DENIED`）。影响面已核：测试代码仅 `PermissionControllerIT` 自身调用这两个端点，主代码仅 controller 自用（后端仓库无前端），无其他破坏点。
- **不改**：`init_data.sql`、DB schema（零迁移）、`getMyPermission`/`auditor` 行为、其他 controller 注解。
- **成本**：¥0（本地 Docker + 代码，无模型调用）。

## 风险

- 行为变更对存量调用方的影响 = 本提案目的本身（收紧）；正向用例保证 606 持有者不受损。
- `AGENTS.md` 是 workspace 规则文件，编辑仅限"未闭合的授权缺口"一节的闭合改写，不动其余条款。

## Non-Goals

- 不新增 `SYSTEM_VIEW_PERMISSION(608)` 常量（已拍板否决）。
- 不动 `/permission/auditor`、`/permission/getMyPermission` 的开放性。
- 不全量审计其他 controller 的注解覆盖（另案）。
