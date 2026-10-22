# 权限模块目录（号段单一事实源）

> 变更卡：`openspec/changes/close-permission-module-gap/`。本文件是「权限 ID 号段 → 模块展示名」的唯一人读台账；
> 机器可读的同一张表在 `com.slz.crm.common.enumeration.PermissionModule`。

## 1. 号段表（权威）

| 权限 ID 号段 | 模块展示名 |
|---|---|
| `1xx` / `11xx` | 客户管理模块权限 |
| `2xx` / `20xx`–`22xx` | 销售管理模块权限 |
| `3xx` / `30xx` | 财务管理模块权限 |
| `4xx` / `40xx` | 联络任务模块权限 |
| `5xx` | 统计报表模块权限 |
| `6xx` | 权限管理模块权限 |
| `7xx` | 隐私信息查看权限 |
| `8xx` | AI 模块权限 |
| `9xx` | 知识库管理模块权限 |
| 未命中 | 其他模块权限 |

- 既有 7 个展示名**逐字不变**（含「权限管理模块权限」这个别扭但既有的名字），只新增 AI / 知识库 / 其他 三个桶。
- **返回顺序 = `PermissionModule` 声明顺序**，`GET /permission/list` 的 Map 键按此顺序输出，「其他模块权限」恒末位。

## 2. 判定口径（两步，不可倒序）

1. 先 `PermissionOperates.fromId(id)` 命中枚举；**未命中 ⇒ 其他模块权限**（不猜号段）。
2. 命中后取该枚举 ID 的**十进制首位数字**定段（1171 → 1、2041 → 2、900 → 9）。

先查枚举再取首位，避免未来 4 位新号段被误判成 1-4 段。枚举外的历史脏行只在「其他模块权限」桶兜底，绝不静默丢弃。

## 3. 单一事实源的三方关系

| 位置 | 职责 | 是否允许自带映射 |
|---|---|---|
| `common/enumeration/PermissionModule` | **唯一**事实源：号段 → 展示名 + 声明顺序；`fromPermissionId(Long)` / `fromOperates(PermissionOperates)` / `displayName()` | 是（只允许写在这里） |
| `server/service/impl/PermissionModuleResolver` | 薄壳：`moduleNames()`（有序，含「其他」末位）与 `resolve(Long)`，全部委托 `PermissionModule` | **否**，只许委托 |
| `server/init/PermissionSyncRunner` | 启动同步的日志分组：`getModule(PermissionOperates)` 委托 `PermissionModule` | **否**，只许委托 |

历史包袱说明：`PermissionModuleResolver` 原先按 `customer:` / `sales:` / … 英文冒号前缀匹配，而 `permissions_name`
自 `PermissionSyncRunner` 起写的是枚举裸名，该分支早已是死代码（全部权限落「其他」，接口恒返 7 个空模块），本卡已删除；
`PermissionSyncRunner` 原先的第二份前缀映射（`CUSTOMER_` / `SALES_` / … / `PRIVACY_`）同步删除。

启动日志文本变化：`客户管理模块` → `客户管理模块权限`（纯日志、零业务行为；日志分组与展示名统一）。

## 4. 防漂移门禁

- `PermissionModuleResolverTest`：遍历 `PermissionOperates.values()`，**任一常量落「其他模块权限」即红**；并断言
  `moduleNames()` 恒 10 项、「其他模块权限」末位、各号段代表 ID 归位、`null` / 枚举外 ID（含伪 `9xx`）落其他。
- `PermissionServiceImplModuleGroupingTest`：`GET /permission/list` 的分组口径——Map 恒含 10 个键（空模块保留为空列表）、
  每条权限恰好出现一次、800/810 落 AI、900 落知识库、枚举外 ID 落其他、data-scope 权限进 `subPermissions` 且带
  `parentPermissionId`。
- `PermissionControllerIT`（真 MySQL）：`/permission/list` 响应含 10 个模块键（7 个既有键名零漂移）且 AI 桶含 800/810、
  知识库桶含 900。

**新增权限必须落在既有号段**（1–9 段之一）：忘了归段，第一个 `mvn test` 就会红，不需要等接口联调。

## 5. 授权页数据来源变更

| | 改前 | 改后 |
|---|---|---|
| 数据来源 | `frontend/src/constants/premission.ts`（冻结的 `/permission/list` 响应快照，`permissionsName` 仍带 `customer:` 前缀） | `GET /permission/list`（`useQueryAllPermissions`） |
| 候选权限条数 | **109** | **120** |
| 模块桶 | 7（快照键） | 10（接口键；前端跳过空模块渲染。「其他模块权限」只兜底枚举外的历史脏行——库表仅由迁移种子 + `PermissionSyncRunner` 从零补齐时该桶为空；本仓 IT 库因 `init_data.sql:195-205` 另种了 id 1-10 的非枚举权限行而**非空**，`PermissionControllerIT` 正是拿它证明「零丢弃」） |

- 改前 109 的实测来源：`mockPermissionCatalog.ts`（原 `constants/premission.ts`）中 `"permissionsName"` 出现 **109** 次。
- 改后 120 的实测来源：`PermissionOperates` 常量 ID 字面量 **120** 个；`permissions` 表由 `PermissionSyncRunner` 在启动时补齐
  全部枚举常量（当前迁移种子 35 行全部是枚举子集、`V1__baseline` 零权限数据、同步只增不删）⇒ 表内行数 = 120。
- 冻结快照已 `git mv` 到 `frontend/src/utils/tutorial/mockPermissionCatalog.ts`，**仅教程演示 mock 引用**，不随枚举同步；
  前端类型里的 `PermissionName` 改为 `string` 口径（不再用过期快照推导字面量联合）。
- 权限树交互（Collapse 折叠、搜索、父子联动）与提交链路（`toAdd` / `toRemove` / 批量提交）行为不变；父权限缺失的
  子权限由 `utils/permissionCatalog.ts` 的 `orphanSub` 在模块级兜底渲染，不丢弃。
