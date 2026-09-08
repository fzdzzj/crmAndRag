# Agent-A 提示词 · 组织与上下级数据权限（Wave 1，可最先并行）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`（注释规范+工作纪律），再读 `specs/org-data-scope/spec-delta.md`、`tasks.json`（任务5、6）、`design-decisions.md`（D6）、`agent-execution-plan.md`（§3 归属、§5 Flyway）。
> 源项目只读参考：CRM `D:\code\crm\back\crm-back\.worktrees\ai-createid`（重点看 `DataScopeLevel`、`DataScopeResolverImpl`、`SysDeptEntity`、`MyDataPermissionHandler`）。

## 角色与目标
你是**数据权限 agent**。目标：为 CRM 增加"本部门 / 本部门及以下（上司查看下属）"数据范围，并贯通到 AI 工具查询。

## 负责范围
tasks.json 任务 5、6。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-a-datascope`
- 分支：`feature/lane-a-datascope`（从 Agent-0 的 base 派生）
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成、base 已提交；`UserContext` 与 `DataScope` 契约已冻结。

## 独占可改
`common.enumeration.DataScopeLevel` / `PermissionOperates`、`server.service.impl.DataScopeResolverImpl`、`MyDataPermissionHandler`、`pojo.entity.SysDeptEntity`、Flyway `V2x__*`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、其他 lane 的包（尤其 `com.slz.crm.knowledge.**`、`server.ai.**`）。

## 要做
1. `DataScopeLevel` 新增 `DEPT`(本部门)、`DEPT_AND_CHILD`(本部门及以下)，保留 NONE/SELF/TAGE/ALL 与 `fromCode` 兼容。
2. `SysDeptEntity` 新增 `leaderId`(部门负责人)；基于 `parentId` 构建部门树。
3. `PermissionOperates` 为受控表补 `_DEPT`/`_DEPT_AND_SUB` 权限项 + 权限种子。
4. `DataScopeResolver`：按 `parentId` 递归解析子树部门集合、按 `user.deptId` 解析下属用户集合；`getHighestDataScopeLevel` 纳入新级别（负责人默认 `DEPT_AND_CHILD`，超管 roleId=1 走 `ALL`）。
5. `MyDataPermissionHandler`：受控表按归属人(`owner_id`/`creator_id`) ∈ 下属集合注入 SQL 过滤。
6. 数据权限贯通 AI 工具查询（**面向 `DataScope` 契约**，不改 AI 助手实现）。
7. Flyway `V2x__*`：`sys_dept.leader_id` 列 + DEPT/DEPT_AND_SUB 权限种子（**默认不授予任何角色**）。

## 关键坑（源码盘点）
- `sys_dept` 现无 `leader_id`；`parent_id` 注释写"支持两级"，你要扩成 **N 级递归 + 成环防御**。
- 现状无种子部门树，`DataInitializer` 只建一个"默认部门"；测试需自建多级部门+用户。
- 受控表清单见 `DataScopeResolverImpl.TABLE_PERMISSIONS`；归属人列名按表确认。
- 新增权限默认不授予，避免改变既有可见范围。

## 注释重点（本 lane）
- **部门树递归、成环防御、下属集合解析**属非直觉逻辑，必须行内注释解释递归终止条件与环检测策略。
- `DataScopeLevel` 每个枚举值、`SysDeptEntity.leaderId` 字段、新增权限项都要中文注释。
- `MyDataPermissionHandler` 注入 SQL 的每段拼接注释说明"过滤哪个归属人列、对应哪个数据范围级别"。
- Flyway `V2x` 头部注释写明"新增列/权限种子，默认不授予，回滚注意"。

## 出口条件
本人 / 本部门 / 本部门及以下 / 全部 四级可见性 + 负责人跨子部门 + 越权拦截 + 部门树成环防御 测试全绿。

## 产出
变更摘要 + 测试结果 + 对契约/依赖的变更申请（如有）。
