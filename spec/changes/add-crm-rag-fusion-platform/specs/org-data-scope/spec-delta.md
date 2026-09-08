# 规范差异：org-data-scope（部门架构与上下级数据权限）

本文件包含对 `spec/specs/org-data-scope/spec.md` 的规范变更。

## MODIFIED 需求

### Requirement: 数据范围级别
**Previous**：`DataScopeLevel` 仅有 `NONE(0)`、`SELF(1)`、`TAGE(2)`、`ALL(3)` 四级，`SELF` 表示仅本人（含共享），无部门/上下级维度。

系统 SHALL 在保留既有级别的基础上，新增 `DEPT`（本部门）与 `DEPT_AND_CHILD`（本部门及以下/含下属）两级数据范围，且 `fromCode` 等既有行为保持向后兼容。

#### Scenario: 解析本部门范围
GIVEN 用户所属部门为 D2，且拥有某资源的“本部门”查看权限
WHEN 用户查询该资源列表
THEN 系统仅返回归属人属于部门 D2 的记录
AND 不返回其他部门或本人之外的越权记录

#### Scenario: 解析本部门及以下范围
GIVEN 用户为部门 D1 的负责人，D1 有子部门 D2、D3，D2 有子部门 D4
WHEN 用户查询受控资源
THEN 系统返回归属人属于 D1、D2、D3、D4 的记录
AND 上级部门负责人可看到其所有下级部门的数据

#### Scenario: 既有级别不受影响
GIVEN 用户仅拥有 `SELF` 权限
WHEN 新增 DEPT/DEPT_AND_CHILD 级别后查询
THEN 系统仍仅返回本人及被共享的记录
AND 既有 `SELF`/`TAGE`/`ALL` 语义与编码不变

---

## ADDED 需求

### Requirement: 部门树与负责人建模
系统 SHALL 基于 `sys_dept.parentId` 维护部门层级树，并 SHALL 支持为部门指定负责人（`leaderId`）以显式表达“上司”身份。

#### Scenario: 构建部门树
GIVEN 部门表存在 parentId 层级关系
WHEN 系统解析某部门的子树
THEN 返回该部门及其所有直接/间接子部门
AND 结果可用于下属用户集合解析

#### Scenario: 防止层级成环
GIVEN 部门 parentId 配置可能形成循环引用
WHEN 系统构建部门树
THEN 系统检测到环并拒绝或安全终止递归
AND 不产生无限循环或栈溢出

#### Scenario: 指定部门负责人
GIVEN 管理员为部门 D1 指定负责人为用户 U1
WHEN 保存部门信息
THEN 系统记录 D1 的 leaderId 为 U1
AND U1 在 D1 范围内默认获得“本部门及以下”数据可见性

### Requirement: 下属用户集合解析
系统 SHALL 依据部门树与用户所属部门（`user.deptId`）解析给定上司的下属用户集合，用于数据归属过滤。

#### Scenario: 解析下属集合
GIVEN 上司 U1 负责部门 D1，D1 及其子部门下有用户 {U1,U2,U3,U4}
WHEN 系统解析 U1 的下属用户集合
THEN 返回 {U1,U2,U3,U4}
AND 该集合用于按记录归属人过滤业务数据

#### Scenario: 用户无部门归属
GIVEN 某用户 deptId 为空或为 0
WHEN 解析下属集合
THEN 系统按初始化策略将其归入默认部门或排除出下属范围
AND 不因空部门导致查询异常

### Requirement: 部门数据权限项与解析接入
系统 SHALL 为受数据权限控制的业务表提供“本部门/本部门及以下”权限项，并接入数据权限解析器与 SQL 注入处理器。

#### Scenario: 权限项驱动范围判定
GIVEN 角色拥有某表的“本部门及以下”查看权限
WHEN 解析该用户对该表的最高数据范围
THEN 系统返回 `DEPT_AND_CHILD`
AND 查询按下属用户集合过滤归属人

#### Scenario: 未授予新权限时保持最小可见
GIVEN 角色未被授予任何 DEPT/DEPT_AND_CHILD 权限
WHEN 用户查询受控表
THEN 系统回退到既有的 `SELF`（本人+共享）范围
AND 新增权限默认不授予，需按角色显式配置

#### Scenario: 超级管理员
GIVEN 用户为超级管理员（roleId=1）
WHEN 查询任意受控表
THEN 系统返回 `ALL` 范围，不受部门维度限制

### Requirement: 数据权限贯通 AI 与知识库
系统 SHALL 使部门/上下级数据权限口径贯通 AI 助手的工具查询，并 SHALL 与知识库授权协同约束可见范围。

#### Scenario: AI 工具查询遵循数据范围
GIVEN 上司 U1 通过 AI 助手查询“下属的商机”
WHEN AI 助手调用业务查询工具
THEN 工具查询按 U1 的 `DEPT_AND_CHILD` 范围过滤
AND 返回结果不包含越权部门的记录

#### Scenario: 普通用户 AI 查询受限
GIVEN 普通用户仅有 `SELF` 范围
WHEN 通过 AI 助手查询业务数据
THEN 系统仅返回本人及被共享的记录
AND 拒绝其获取他人或他部门数据

---

## 备注

- 本能力域对应决策 D6（上司下属建模：部门树 + 负责人字段）。
- **现状（代码证据）**：`sys_dept` 仅有 `id`/`dept_name`/`parent_id`/`sort`/`status`，`parent_id` 注释标注“支持两级”，**无 `leader_id`**；`user.dept_id` 已存在；`DataInitializer` 仅创建一个“默认部门”根节点，无种子部门树。
- **落地**：新增 `sys_dept.leader_id`（部门负责人）；将“支持两级”扩展为按 `parent_id` 递归的 N 级子树解析（带成环防御）；下属用户集合 = `dept_id ∈ 子树部门` 的用户。
- **数据过滤**：受控表按归属人（`owner_id`/`creator_id` 等）∈ 下属用户集合过滤，经 `MyDataPermissionHandler` 注入 SQL；具体列映射按表确认。
- 可选增强 `user.managerId`（跨部门汇报线）留待后续，不在本期强制范围。
