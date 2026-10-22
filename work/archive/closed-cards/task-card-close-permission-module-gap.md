# 任务卡 · close-permission-module-gap（权限模块归属改为号段单一事实源）

## 目标（一句话）
让 `GET /permission/list` 返回**完整**权限目录（含 AI / 知识库模块），并让前端授权页改为消费它 —— 现在授权页勾不到任何 V26 之后新增的权限。

## 现状锚点（已实测，勿再猜）
- `permissions.permissions_name` 存的是**枚举裸名**（`AI_ASSIST_VIEW` / `CUSTOMER_VIEW_COMPANY`），由 `PermissionSyncRunner:100-102` 写入、`:218-219` 把历史行一并改写；迁移种子（`V21`/`V26`/`V27`/`V30`/`V33`/`V43`）同样插裸名。
- `PermissionModuleResolver:15-35` 却按 `customer:` / `sales:` … 英文冒号前缀匹配 → **全部**权限归「其他模块权限」。
- `PermissionServiceImpl:262-284` 用 `getKeyList()`（`:85-98`，硬编码 7 个模块名）建桶，`moduleMap.get(name)` 为 null 时**静默丢弃**（`:272-275`）→ 接口恒返 7 个空模块。
- `PermissionServiceImpl:210-233` 的 `getAllPermissionsGroupedByModule()` 是死代码且 `:229` 会 NPE；实测**全仓零调用**。
- `PermissionSyncRunner:118-138` 是**第二份**模块映射（按枚举名前缀），`AI_`/`PROMPT_TEMPLATE_`/`KNOWLEDGE_`/`PLATFORM_` 全落「其他模块」；只用于启动日志分组。
- 前端 `EditPermissionModal.vue:19` 与 `CreateRoleModal.vue:23` 用 `@/constants/premission`（615 行、`permissionsName` 仍带 `customer:` 前缀的**冻结响应快照**），`types/permission.ts:1` 还从它推导 `PermissionName` 联合类型。
- 权限总数实测 **120** 个常量（`PermissionOperates`），号段：1xx/11xx 客户、2xx/20xx–22xx 销售、3xx/30xx 财务、4xx/40xx 联络任务、5xx 报表、6xx 权限管理、7xx 隐私、8xx AI、9xx 知识库。
- `PermissionControllerIT:69-131` 已有 `/permission/list` 正向 + 反向用例（**只允许增量断言**）。单测基建 `PermissionServiceImplMessageCodeTest` = Mockito `@InjectMocks` 风格，**无需 Docker**。
- 本卡**零迁移**：不新增权限常量、不新增 Flyway 脚本（当前最高 `V43`）。

## 硬约束（违反即退回）
1. **零新依赖、零契约解冻、零迁移脚本、零新配置键**；`docs/dynamic-config-keys.md` 与 `docs/permission-matrix-audit.md`（历史快照）**一律不改**。
2. 鉴权语义不动：`@RequirePermission` / `PermissionsInterceptor` / `roleId=1` 直通 / 用户状态闸 / `role_permissions` 授权面**全部保持不变**。
3. 既有 7 个模块展示名**逐字不变**（含「权限管理模块权限」）；只新增「AI 模块权限」「知识库管理模块权限」「其他模块权限」三桶。返回顺序 = `PermissionModule` 声明顺序。
4. 单一行只允许一个事实源：模块归属只能写在 `PermissionModule` 里，其它位置（`PermissionModuleResolver`、`PermissionSyncRunner`）**只许委托**，不许各自再写一份 if-else 前缀判断。
5. 前端：`frontend/typed-router.d.ts` / `dist` / `.tmp-build` 等生成物一律不提交；`pnpm lint:check` + `pnpm type-check:check` 必须绿。
6. **严禁在观察树（`D:\code\crmAndRag`）产生任何文件**（含空文件），全部改动只在权威树分支内。

## 交付面（写集）
- 后端：新 `common/enumeration/PermissionModule.java`、改 `server/service/impl/PermissionModuleResolver.java`、改 `server/service/impl/PermissionServiceImpl.java`（`getKeyList` / `getAllPermissionsGroupedByModuleGrouped` / 删 `getAllPermissionsGroupedByModule`）、改 `server/service/PermissionService.java`（删 1 个声明）、改 `server/init/PermissionSyncRunner.java`（委托）。
- 测试：新 `PermissionModuleResolverTest`、新 `PermissionServiceImpl` 模块分组单测、扩写 `PermissionControllerIT`。
- 前端：新 `utils/permissionCatalog.ts`、改 `components/role/EditPermissionModal.vue` 与 `components/role/CreateRoleModal.vue`、`git mv constants/premission.ts → utils/tutorial/mockPermissionCatalog.ts`、改 `utils/tutorialMock.ts` 与 `types/permission.ts`、新 `utils/__tests__/permissionCatalog.test.ts`。
- 文档：新 `docs/permission-module-catalog.md`（号段表 + 单一事实源 + 防漂移测试）。

## 验收（自证）
- `mvn -B -ntp clean verify` BUILD SUCCESS；`bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/merge-gate.sh` 全绿。
- 防漂移单测：`PermissionOperates.values()` 全部非「其他模块权限」（任一漏归段即红）。
- 前端：`pnpm lint:check` / `pnpm type-check:check` / `pnpm build` 全绿；`bash scripts/check-openapi-consistency.sh` EXIT=0。
- 回报必须含：改前 / 改后授权页候选权限条数（实测数字）。

## 停步条件
跑绿即停步：**不 merge、不 push、不删分支**，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项。
