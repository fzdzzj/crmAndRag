# Agent-A 提示词 · 组织与上下级数据权限（Wave 1，可最先并行）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`（注释规范+工作纪律），再读 `specs/org-data-scope/spec-delta.md`、`tasks.json`（任务5、6）、`design-decisions.md`（D6）、`agent-execution-plan.md`（§2 契约、§3 归属、§5 Flyway）、`db-table-coordination.md`。
> 源项目只读参考：CRM `D:\code\crm\back\crm-back\.worktrees\ai-createid`（**真实数据权限落点**：`server/aspect/QueryWrapperAspect.java`、`server/service/impl/DataScopeServiceImpl.java`、`server/constant/ResourceTypeConstant.java`、`pojo/ao/RoleAO.java`、`pojo/entity/SysDeptEntity.java`、`server/interceptor/PermissionsInterceptor.java`）。

## 角色与目标
你是**数据权限 agent**。目标：为 CRM 增加"本部门 / 本部门及以下（上司查看下属）"数据范围，并贯通到 AI 工具查询。

## 负责范围
tasks.json 任务 5、6。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-a-datascope`
- 分支：`feature/lane-a-datascope`（从 Agent-0 的 base 派生）
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成、base 已提交；`UserContext`/`DataScope` 契约已冻结（**契约必须已含 `userId`+`deptId`**，否则先走契约变更申请，见关键坑）。

## 独占可改
`common.enumeration.DataScopeLevel`/`PermissionOperates`、`server.service.DataScopeService` + `server.service.impl.DataScopeServiceImpl`、`server.constant.ResourceTypeConstant`、`server.aspect.QueryWrapperAspect`、`pojo.ao.RoleAO`(加 deptId)、`server.interceptor.PermissionsInterceptor`(填充 deptId)、`pojo.entity.SysDeptEntity`(加 leaderId)、Flyway `V2x__*`。
**删除**：`server.service.DataScopeResolver` + `server.service.impl.DataScopeResolverImpl`（死代码，决策 A）。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、其他 lane 的包（尤其 `com.slz.crm.knowledge.**`、`server.ai.**`）。

## 真实机制（源码已核实，务必照此改）
- 数据权限由 **AOP 切面 `QueryWrapperAspect`** 拦截带 `QueryWrapper`/`LambdaQueryWrapper` 的查询 → 调 **`DataScopeServiceImpl.addDataScopeCondition(wrapper, RoleAO, resourceType)`**（**两个重载**）注入条件；归属字段由 **`ResourceTypeConstant.getUserFieldsByTableName(表名)`** 给出。
- **不存在 `MyDataPermissionHandler`**（该类只出现在 Javadoc 文字里，别去找）。
- **`DataScopeResolver`/`DataScopeResolverImpl` 是死代码**（无调用方、逻辑与 `DataScopeServiceImpl.getHighestDataScopeLevel` 重复、表清单陈旧缺 `project_file`）→ **删除，不带入新仓**。

## 要做
1. `DataScopeLevel` 新增 `DEPT`(本部门)、`DEPT_AND_CHILD`(本部门及以下)，保留 NONE/SELF/TAGE/ALL 与 `fromCode` 兼容。
2. **删除死代码** `DataScopeResolver`/`DataScopeResolverImpl`；扩展点统一用 `DataScopeServiceImpl`。
3. `SysDeptEntity` 新增 `leaderId`；`RoleAO` 新增 `deptId`（`PermissionsInterceptor` 填充）——**现 `RoleAO` 只有 `id/roleId/permissions`，无 deptId，不加无法解析 DEPT**。
4. `PermissionOperates` 为受控表补 `_DEPT`/`_DEPT_AND_SUB`；`DataScopeServiceImpl.TABLE_PERMISSIONS` 的 `[ONLY_MY,TAGE,ALL]` 三元组结构扩展以容纳 DEPT 权限槽。
5. `getHighestDataScopeLevel` 增加 DEPT/DEPT_AND_CHILD 判定（负责人默认 `DEPT_AND_CHILD`，超管 roleId=1 走 `ALL`）；按 `parentId` 递归子树（成环防御）+ `deptId` 解析下属用户集合。
6. **两个 `addDataScopeCondition` 重载的 `switch` 都要加 `case DEPT/DEPT_AND_CHILD`**（现 `default` 落 SELF，漏改一个会被静默降级）；新增 `addDeptScopeCondition`/`addDeptAndChildScopeCondition`，按 `ResourceTypeConstant.TABLE_USER_FIELDS` 的**每表归属字段** ∈ 下属集合过滤。
7. 数据权限贯通 AI 工具查询（**面向 `DataScope` 契约**，不改 AI 助手实现）。
8. Flyway `V2x__*`：`sys_dept.leader_id` 列 + DEPT/DEPT_AND_SUB 权限种子（**默认不授予任何角色**）。

## 关键坑（源码核实）
- **`RoleAO` 无 `deptId`**：这是契约级改动。若 `DataScope`/`UserContext` 契约已冻结但未含 deptId，**先提契约变更申请**（agent-execution-plan §2），别私自改签名。
- **两个 switch 的 `default→SELF` 陷阱**：`QueryWrapper` 与 `LambdaQueryWrapper` 两个重载都要加 case，否则新级别悄悄降级为 SELF。
- **受控表两套清单不一致**：`ResourceTypeConstant.MANAGED_TABLES` 有 **10 张**（含 `customer_contact`），`DataScopeServiceImpl.TABLE_PERMISSIONS` 只有 **9 张**（`customer_contact` 无权限三元组、恒走 SELF）；补 DEPT 权限项时按此对齐，别遗漏或错配。
- **归属字段每表不同**：`sales_opportunity=[creator_id,owner_id]`、`contact_task=[creator_id,assignee_id,assigner_id]`、`sales_stage_approval=[applicant_id,approver_id]`、`project_file=[uploader_id]`、其余多为 `[creator_id]`；DEPT 过滤要覆盖该表**全部**归属字段。
- `sys_dept` 无 `leader_id`、`parent_id` 注释"支持两级"→扩 N 级递归；无种子部门树（`DataInitializer` 只建"默认部门"），测试需自建多级部门+用户。
- 新增权限默认不授予，避免改变既有可见范围。

## 注释重点（本 lane）
- **部门树递归、成环防御、下属集合解析**属非直觉逻辑，行内注释解释递归终止条件与环检测策略。
- `DataScopeLevel` 每个枚举值、`SysDeptEntity.leaderId`、`RoleAO.deptId`、新增权限项都要中文 Javadoc。
- **两个 `addDataScopeCondition` 重载**新增的 `case` 与 `addDept*ScopeCondition`：注释说明"过滤哪些归属字段（引用 `TABLE_USER_FIELDS`）、对应哪级范围、为何两个 switch 都要改"。
- 删除 `DataScopeResolver` 死代码在变更摘要写明原因（无调用方、逻辑重复、表清单陈旧）。
- Flyway `V2x` 头部注释写明"新增列/权限种子、默认不授予、回滚注意"。

## 出口条件
本人 / 本部门 / 本部门及以下 / 全部 四级可见性 + 负责人跨子部门 + 越权拦截 + 部门树成环防御 + **两个 switch 重载均生效** 测试全绿；死代码已删除且编译通过。

## 产出
变更摘要 + 测试结果 + 对契约/依赖的变更申请（如 `RoleAO`/`DataScope` 需加 deptId）。
