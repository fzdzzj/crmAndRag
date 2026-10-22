# 任务清单 · close-permission-module-gap

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。
> 复核档位：**A 档**（权限域 + `/permission/list` 值集合变化）。

## 1. 后端模块归属单一事实源
- [x] 1.1 新增 `com.slz.crm.common.enumeration.PermissionModule`：枚举常量按展示顺序声明（客户管理模块权限 / 销售管理模块权限 / 财务管理模块权限 / 联络任务模块权限 / 统计报表模块权限 / 权限管理模块权限 / 隐私信息查看权限 / **AI 模块权限** / **知识库管理模块权限** / **其他模块权限**），每项带 `displayName`；提供 `fromPermissionId(Long)`（先 `PermissionOperates.fromId` 命中，未命中 ⇒ 其他；命中后按 id 十进制首位取段）与 `fromOperates(PermissionOperates)`
      补勾依据（2026-10-22）：新建 `src/main/java/com/slz/crm/common/enumeration/PermissionModule.java`（10 常量按展示顺序、`displayName`/`fromPermissionId(Long)`/`fromOperates(PermissionOperates)`，先 `fromId` 命中再取十进制首位）；`mvn -B -ntp test "-Dtest=PermissionModuleResolverTest"` 15 用例全绿。
- [x] 1.2 `PermissionModuleResolver` 改薄壳：`moduleNames()` 返回有序模块名（含「其他模块权限」末位，顺序同枚举声明）、`resolve(Long permissionId)` 委托 `PermissionModule`；**删除 `permissionsName` 前缀匹配分支**（DB 名已是枚举裸名，该分支自 `PermissionSyncRunner:100-102/218-219` 起即死代码）
      补勾依据（2026-10-22）：该文件现仅 `moduleNames()` / `resolve(Long)` 两个方法且全部委托 `PermissionModule`，7 个英文冒号前缀分支已删（`git diff` 可见整段删除）；`PermissionModuleResolverTest#moduleNamesAreOrdered` 绿（10 项、「其他模块权限」末位）。
- [x] 1.3 `PermissionSyncRunner.getModuleByEnumName` 改为委托 `PermissionModule`（两份映射合一）；仅影响启动日志分组文本，零业务行为变化
      补勾依据（2026-10-22）：`getModuleByEnumName` 及其 7 分支已删，改为 `getModule(PermissionOperates)` → `PermissionModule.fromOperates(operation).displayName()`；模块桶初始化也改用 `PermissionModule.values()`。日志文本 `客户管理模块` → `客户管理模块权限`，纯日志零行为，已记 `docs/permission-module-catalog.md` §3。
- [x] 1.4 `PermissionServiceImpl.getKeyList()` 改为委托 `PermissionModuleResolver.moduleNames()`，删掉 7 个硬编码模块名字符串
      补勾依据（2026-10-22）：方法体改为单行 `return PermissionModuleResolver.moduleNames();`；`Select-String PermissionServiceImpl.java -Pattern 客户管理模块权限` 零命中。
- [x] 1.5 `getAllPermissionsGroupedByModuleGrouped` **不再丢弃**任何行：未知归属落「其他模块权限」桶（删掉 `moduleMap.get(...)` 为 null 时的跳过分支）；保证 DB 行数 = 各模块 main + sub 合计
      补勾依据（2026-10-22）：改为按 `permission.getId()` 解析 + `computeIfAbsent` 入桶，null 跳过分支已删；`PermissionServiceImplModuleGroupingTest#groupsEveryRowExactlyOnceBySegment` 断言 7 行 → main+sub 合计 7 条、无重复、9999 落「其他模块权限」，绿。
- [x] 1.6 删除死方法 `getAllPermissionsGroupedByModule()` + `PermissionService` 接口声明（全仓零调用，且 `:229` 对未知模块会 NPE）
      补勾依据（2026-10-22）：删除前 `grep -rn "getAllPermissionsGroupedByModule(" src frontend/src` 仅命中 `PermissionService.java:115`（接口声明）与 `PermissionServiceImpl.java:210`（实现）两行自身，零调用点；删除后同命令零命中，`mvn -B -ntp test-compile` BUILD SUCCESS。

## 2. 后端测试（surefire，无 Docker）
- [x] 2.1 `PermissionModuleResolverTest`：遍历 `PermissionOperates.values()`，断言**每个**常量都落到非「其他模块权限」的模块（新增权限漏归段即红）；断言 `moduleNames()` = 10 项且「其他模块权限」在末位；抽查 101/1171→客户、2041/2295→销售、3045→财务、4044→联络任务、501→报表、608→权限管理、701→隐私、800/810→AI、900→知识库；`id = null` 与不在枚举的 id（如 `9999L`）→「其他模块权限」
      补勾依据（2026-10-22）：15 用例（3 普通 + 12 `@CsvSource` 抽查）全绿；`mvn -B -ntp test "-Dtest=PermissionModuleResolverTest,PermissionServiceImplModuleGroupingTest"` → `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`。
- [x] 2.2 `PermissionServiceImpl` 模块分组单测（Mockito `@InjectMocks` + mock `PermissionsMapper.selectList`，参照 `PermissionServiceImplMessageCodeTest` 既有写法）：返回 map **恒含 10 个键**（空模块保留为空列表）；每条权限恰好出现一次（无丢弃、无重复）；800/810 落 AI、900 落知识库、未知 id 落其他；data-scope 权限进 `subPermissions`（带 `parentPermissionId`）、其余进 `mainPermissions`
      补勾依据（2026-10-22）：`PermissionServiceImplModuleGroupingTest` 3 用例全绿（空库恒 10 键且空列表；7 行零丢弃无重复 + 800/810→AI、900→知识库、9999→其他；1171 进 subPermissions 且 parentPermissionId=117、800 留在 main）。
- [ ] 2.3 扩写 `PermissionControllerIT` 既有正向用例（不新增用例、不改反向用例）：`/permission/list` 响应体含「AI 模块权限」且其 main+sub 内出现 810 与 800、含「知识库管理模块权限」且含 900、含「其他模块权限」键（为空）；**同时断言 7 个既有模块键仍存在**（键名零漂移）
      补勾依据（待 failsafe 真库运行填写）

## 3. 前端授权页接后端目录
- [x] 3.1 新增纯函数模块 `frontend/src/utils/permissionCatalog.ts`：`buildPermissionCategories(record)` → 有序 `[{ module, main, sub }]`（跳过空模块、保持接口返回顺序）+ `subPermissionsOf(category, parentId)`（按 `parentPermissionId` 过滤）
      补勾依据（2026-10-22）：新增文件含 `buildPermissionCategories`（为空/未命中跳过、保持 `Object.entries` 顺序、另收集父未匹配的 `orphanSub` 兜底）与 `subPermissionsOf`（父 ID 缺失返回 `[]`）；`pnpm type-check:check` 绿。
- [x] 3.2 `EditPermissionModal.vue` 改用 `useQueryAllPermissions()`（`GET /permission/list`）：加载 / 错误 / 空态齐备，`import PERMISSIONS from '@/constants/premission'` 移除；提交链路（toAdd / toRemove / 批量提交）行为不变
      补勾依据（2026-10-22）：数据源改 `useQueryAllPermissions()` + `buildPermissionCategories`，旧快照 import 已删；加载（Spin）/ 错误（重试）/ 空态（暂无权限数据）齐备；`submit()` 的 toAdd/toRemove/`Promise.allSettled` 逻辑未改一行；`pnpm lint:check`、`pnpm type-check:check` 绿。
- [x] 3.3 `CreateRoleModal.vue` 同上（含「以既有角色为权限模板」逻辑在新目录下仍可用）
      补勾依据（2026-10-22）：同样接 `useQueryAllPermissions()`，搜索过滤按新目录结构重写（保留关键字过滤/自动展开），模板回填仍走 `getPermissionGetByRole` + `extractPermissionIds`；`pnpm lint:check`、`pnpm type-check:check` 绿。
- [x] 3.4 `frontend/src/constants/premission.ts` → `git mv` 为 `frontend/src/utils/tutorial/mockPermissionCatalog.ts`，仅 `utils/tutorialMock.ts` 引用；头注释写明「tutorial 演示专用冻结快照，不随枚举同步」；`types/permission.ts` 不再从该 fixture 推导 `PermissionName`（改 `string` 口径），`extractPermissionNames` / `extractPermissionIds` 行为不变
      补勾依据（2026-10-22）：`git status` 显示 `R  frontend/src/constants/premission.ts -> frontend/src/utils/tutorial/mockPermissionCatalog.ts`；`grep -rn "constants/premission" frontend/src` 零命中（仅 tutorialMock 引用新路径）；`types/permission.ts` 的 `PermissionName = string`，两个 extract 函数逻辑未改；`pnpm type-check:check` 绿。
- [x] 3.5 vitest 单测 `frontend/src/utils/__tests__/permissionCatalog.test.ts`：空 record → `[]`；含空模块的 record → 只保留非空且顺序不变；子权限按 `parentPermissionId` 归到父下、父不匹配的子权限不丢（挂模块级兜底）
      补勾依据（2026-10-22）：`pnpm vitest run src/utils/__tests__/permissionCatalog.test.ts` → `Test Files 1 passed (1) / Tests 5 passed (5)`（含「只有子权限的模块不算空」「父不匹配子权限进 orphanSub 且一条不丢」）。
- [ ] 3.6 `pnpm lint:check` + `pnpm type-check:check` + `pnpm build` 全绿；`bash scripts/check-openapi-consistency.sh` EXIT=0（`frontend/openapi.yaml` 预期零变更）；生成物 `frontend/typed-router.d.ts` 等一律 `git checkout --` 还原不提交
      补勾依据（待收尾门禁填写）

## 4. 文档
- [x] 4.1 新增 `docs/permission-module-catalog.md`：号段表（本卡 §4 D1 原样）+ 单一事实源说明（`PermissionModule` / `PermissionModuleResolver` / `PermissionSyncRunner` 三方关系）+ 防漂移测试名 + 「新增权限必须落在既有号段，否则防漂移单测红」
      补勾依据（2026-10-22）：新建 `docs/permission-module-catalog.md`（§1 号段表原样、§2 两步判定口径、§3 三方关系与「只许委托」、§4 三个防漂移测试名与新增权限铁律）。
- [x] 4.2 说明「授权页数据来源已由冻结快照改为 `/permission/list`」，并列出本卡实测的「改前 / 改后候选权限条数」
      补勾依据（2026-10-22）：写入 `docs/permission-module-catalog.md` §5：改前 **109**（fixture 中 `"permissionsName"` 出现 109 次，实测正则计数）、改后 **120**（`PermissionOperates` ID 字面量 120 个；同步器只增不删且 6 个迁移种子 35 行全为枚举子集 ⇒ permissions 表行数 = 120）。
- [ ] 4.3 `docs/permission-matrix-audit.md` **不改**（历史快照）；`docs/dynamic-config-keys.md` **不改**（零新配置键）
      补勾依据（待收尾门禁填写）

## 5. 验证与收口
- [ ] 5.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿
      补勾依据（待收尾门禁填写）
- [ ] 5.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项；A 档独立复核四判据通过后由 Owner 授权 merge
      补勾依据（待收尾门禁填写）
