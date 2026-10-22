# 变更提案 · close-permission-module-gap（权限模块归属改为号段单一事实源）

> 复核档位自 declare：**A 档**（判据：权限域语义 + `/permission/list` 响应值集合变化；见 `docs/review-intensity-policy.md` §二）。

## 1. 问题

权限的「模块归属」在仓内有两份实现，**两份都与数据实际形态不符**：

1. `PermissionModuleResolver.resolve(permissionsName)`（`server/service/impl`，36 行）按 `customer:` / `sales:` / `finance:` / `task:` / `report:` / `system:` / `project:` **英文冒号前缀**匹配，其余归「其他模块权限」。而 `permissions` 表的 `permissions_name` 自 `PermissionSyncRunner:100-102` 起写的是**枚举裸名**（`AI_ASSIST_VIEW` / `CUSTOMER_VIEW_COMPANY`），且 `PermissionSyncRunner:218-219` 会把历史行一并改写为裸名 —— 于是**所有**权限都判为「其他模块权限」。
2. `PermissionSyncRunner.getModuleByEnumName`（`:118-138`）是第二份映射，按 `CUSTOMER_` / `SALES_` / … / `SYSTEM_` / `PRIVACY_` 前缀归类，其余入「其他模块」——`AI_`、`PROMPT_TEMPLATE_`、`KNOWLEDGE_`、`PLATFORM_`（608）全部落「其他」。
   该映射只影响启动日志分组，但它证明：**模块归属没有单一事实源**。

由此产生的**实际后果**（不是理论风险）：

- `GET /permission/list` → `PermissionServiceImpl.getAllPermissionsGroupedByModuleGrouped`（`:262-284`）先按 `getKeyList()`（7 个硬编码模块名）建桶，再 `PermissionModuleResolver.resolve(...)` 归桶，`moduleMap.get(moduleName)` 为 null 时**静默丢弃**（`:272-275`）。所有权限都解析成「其他模块权限」，而「其他模块权限」不在 7 个键里 → **响应恒为 7 个空模块**，全量权限目录一条不返。
- 前端授权页并未消费这个接口，而是 `import PERMISSIONS from '@/constants/premission'`（`EditPermissionModal.vue:19`、`CreateRoleModal.vue:23`）——那是一份**冻结的 `/permission/list` 响应快照**（`frontend/src/constants/premission.ts`，615 行，`permissionsName` 仍带 `customer:` 前缀，可证快照取自前缀时代）。快照之后新增的权限（`V26` 800-807、`V27` 900、`V30` 608、`V33` 808-809、`V43` 810、`V21` 数据范围子权限）**在授权页勾不到**，只能改库。
- 死代码带 NPE 地雷：`getAllPermissionsGroupedByModule()`（`:210-233`）对未知模块直接 `permissionMap.get(moduleName).add(...)`（`:229`），一旦被调用即 NPE；实测**全仓零调用**（无 controller、无测试、无其他服务）。

一句话：**授权页看不到新增权限，不是因为没授权，而是因为模块归属这套映射从来没对上过数据。**

## 2. 目标

1. 模块归属收敛为**唯一事实源**：按权限 **ID 号段**判定（权威 = `PermissionOperates` 的段注释），新增 `PermissionModule` 枚举承载「号段 → 展示名 + 展示顺序」。
2. `GET /permission/list` 返回**完整目录**：不再丢弃任何权限行（未知归属落「其他模块权限」桶），并新增 AI / 知识库两个模块桶。
3. 前端授权页改为消费该接口（`useQueryAllPermissions`），删除冻结快照在业务路径上的使用；tutorial mock 保留自有 fixture。
4. 加**防漂移门禁**：遍历 `PermissionOperates.values()`，任一常量落「其他模块权限」即测试红 —— 以后新增权限忘了归段，第一个 `mvn test` 就炸。

## 3. 非目标

- 不改鉴权语义：`@RequirePermission`、`PermissionsInterceptor`、`roleId=1` 直通、用户状态闸**一律不动**。
- 不改授权面：`role_permissions` 与既有种子迁移不动，**零新权限常量、零新迁移脚本**（本卡不涉及 Flyway）。
- 不改 `GET /permission/getByRole` / `getMyPermission` 的响应结构；不改 `PermissionGroupedVO` / `PermissionVO` / `SubPermissionVO` 字段。
- 不改 `docs/permission-matrix-audit.md`（`apply-permission-matrix` 历史审计快照，改写会让「当时审计了什么」失真）。
- 不动权限树交互（折叠/搜索/父子联动保持现状），不做拖拽排序、不做分页。
- 零新配置键（`docs/dynamic-config-keys.md` 不动）、零新依赖、零契约解冻。

## 4. 方案

**D1 号段表（权威，写进文档与测试）**

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

现有 7 个模块名**逐字保持不变**（含「权限管理模块权限」这个别扭但既有的名字），只新增 AI / 知识库 / 其他 三个桶 —— 前端与既有文档的键名不漂移。

**D2 单一事实源**：新增 `com.slz.crm.common.enumeration.PermissionModule`（enum，声明顺序即展示顺序），提供 `displayName()`、`fromPermissionId(Long)`、`fromOperates(PermissionOperates)`。判定口径固定为两步：
① 先 `PermissionOperates.fromId(id)` 命中枚举（未命中 ⇒ 「其他模块权限」）；
② 由该枚举 id 的**十进制首位数字**取段。先查枚举再取首位，避免未来 4 位新段被误判成 1-4 段。
`PermissionModuleResolver` 保留为薄壳（`moduleNames()` 有序含「其他模块权限」末位 + `resolve(Long id)`），`PermissionSyncRunner.getModuleByEnumName` 改为委托同一枚举 —— 两份映射合一。

**D3 不丢弃**：`getAllPermissionsGroupedByModuleGrouped` 对「其他模块权限」建桶；`getKeyList()` 委托 `PermissionModuleResolver.moduleNames()`（删硬编码 7 串）。不变式：DB 权限行数 = 各模块 main + sub 合计。

**D4 删死代码**：移除 `getAllPermissionsGroupedByModule()`（接口 + 实现），消除 `:229` 的 NPE 地雷。

**D5 前端接目录**：`EditPermissionModal.vue` 与 `CreateRoleModal.vue` 改用 `useQueryAllPermissions()`；新增纯函数 `frontend/src/utils/permissionCatalog.ts`（`buildPermissionCategories` + 按父取子），供两个弹窗与单测共用。`constants/premission.ts` 用 `git mv` 迁到 `frontend/src/utils/tutorial/mockPermissionCatalog.ts`，仅 `tutorialMock.ts` 引用（tutorial 是演示 mock，允许保留冻结快照，头注释写明「演示专用、不随枚举同步」）；`types/permission.ts` 不再从该 fixture 推导 `PermissionName`（改为 `string` 口径，保留 `extractPermissionNames/Ids`）。

**接口契约**：路径与响应结构（`Result<Map<String, PermissionGroupedVO>>`）不变，只有键集合 7 → 10、值从空变实 —— `frontend/openapi.yaml` 预期**零变更**，以 `bash scripts/check-openapi-consistency.sh` EXIT=0 自证；若脚本报差异，以脚本为准同步并在 tasks 留痕。

## 5. 风险与取舍

| 风险 | 处置 |
|---|---|
| 授权页候选权限从快照的少量条目变成 120 条，面板变长 | 保持既有 Collapse（默认全展开）+ 搜索框；tasks 要求回报「改前/改后候选条数」，UX 由 Owner 视实测决定是否另卡 |
| 未知归属被塞进「其他模块权限」= 掩盖归段错误 | 防漂移单测把「落其他」直接判红；桶只为「枚举里没有的历史脏行」兜底 |
| 触发既有 IT/契约导出差异 | `PermissionControllerIT` 已覆盖 `/permission/list` 正反向，本卡只做**增量断言**；契约校验脚本必须 EXIT=0 |
| `PermissionName` 由字面量联合放宽为 `string` | 现状的类型来源是冻结快照，等于「用过期数据换假安全」；运行时真相改由 API 提供。记入看板储备：由 `openapi.yaml` / 枚举生成权限字面量类型 |
| 删接口方法波及其他调用方 | 落卡前实测零调用（`grep -rn "getAllPermissionsGroupedByModule(" src`），tasks 要求把该命令与结果写进补勾依据 |
| 启动日志文本变化（`客户管理模块` → `客户管理模块权限`） | 纯日志、零行为；在 tasks 留痕说明，不算回归 |

## 6. 验收

`tasks.md` 逐项：号段单一事实源 + 不丢弃 + 删死代码 + 前端接 API + 单测（含全枚举防漂移）+ IT 增量断言 + 文档号段表 + 全量门禁；A 档独立复核四判据（迁移 / 权限 / 真跑或真库 / 契约）。
