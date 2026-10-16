# 交接快照 · add-queryuser-dept-isolation（queryUser 部门隔离）

> 实现子 Agent：完工停步（不 merge、不 push），回贴 Owner 审核。
> 分支：`feature/add-queryuser-dept-isolation`（自 master@e33fb97 检出，线性提交）
> 档位：B 档（零 DB 迁移 / 零权限常量 / 零冻结契约 / 零 LLM 外呼 / 零前端改动）

## 1. 提交清单（本卡 4 笔，均为 git commit -m 内联）

| # | 类型 | 哈希 | 文件域 |
|---|------|------|--------|
| 1 | feat(server) | （见 tip） | `AiExtendedQueryToolExecutors.java`（queryUser 部门隔离实现）、`AiToolRegistry.java`（工具描述同步） |
| 2 | test(server) | （见 tip） | `AiQueryToolExecutorsTest.java`（四向单测 + 既有 executeQueryUser 用例适配） |
| 3 | test(baseline) | （见 tip） | `scripts/test-baseline.txt`（基线 1480→1484 --update 写库） |
| 4 | docs(ops) | （见 tip） | `openspec/changes/add-queryuser-dept-isolation/{proposal,tasks}.md`、`work/handoff-add-queryuser-dept-isolation.md`、`work/task-card-add-queryuser-dept-isolation.md` |

分支 tip 哈希请以 `git rev-parse --short HEAD` 实测为准（见树净自证段）。

## 2. 豁免清单与依据（不新造豁免规则，仅对齐既有口径）

**豁免角色：超管 `roleId=1`（保持全量可见，不注入部门过滤）。**

- 依据 1：`PermissionsInterceptor.checkPermission` L97：`user.getRoleId() == 1` → 超级管理员直通，拥有所有权限。
- 依据 2：`DataScopeLevelResolver.getHighestDataScopeLevel` L43-46：`roleId.equals(1L)` → `DataScopeLevel.ALL`（不添加条件，全量）。
- 依据 3：`DataScopeUserScopeGuard.levelOf/visibleDeptIds` L26-27/L39-41：`user.isSuperAdmin()` → `ALL`。

> **口径纠偏（相对卡面文字）**：卡面写「超管 roleId=0 直通」为速记笔误。实测既有口径中 **roleId=1 才是超管**；roleId=0 为冻结账号状态（`PermissionsInterceptor` L79-80 → `USER_IS_FROZEN`）。本实现以既有代码 true 口径（roleId=1）对齐。

**实现位置**：`AiExtendedQueryToolExecutors.executeQueryUser` → `isQueryUserDeptExempt(currentUser)`（roleId==1 豁免）。

## 3. 边界裁决（操作用户无 deptId）

非豁免操作者 `deptId == null` 时，**返回空列表**（fail-closed，不触发全量泄露）。

- 依据：`DataScopeUserScopeGuard.visibleDeptIds` 对非 ALL 且 deptId 缺失返回空集合（"无可见范围"语义）；`canRead` 对非 ALL 且 deptId 缺失返回 false。
- 结论：与数据面实际约束一致——无部门边界的操作者看不到同边界任何用户，返回空列表而非全量。操作者上下文缺失（`BaseUnit.getCurrentRole()==null`）同判 fail-closed 空列表。

## 4. 边界方法决策：隔离实现不含共享 DTO 改动（spec/ 零 diff 保证）

首版实现曾往共享 `GetUserDTO` 加 `deptId` 并在 `UserQueryFilterSupport.applyUserPageFilters` 注入部门条件，但该 DTO 被 `UserController.findUser`（`POST /user/find`）绑定，造成 `OpenApiContractExportTest`「前后端契约漂移」红（前后端 openapi 契约零 diff 被破坏，触碰红线「spec/ 零 diff」）。

**已回退并改为**：隔离完全收敛在 AI 工具 `executeQueryUser` 内——查询后按 `UserVO.deptId` 过滤结果，不触碰共享查询 DTO，`/user/find` 等既有接口 OpenAPI 契约保持零漂移。`OpenApiContractExportTest` 复绿（2 run 全绿）。

## 5. 四向单测（挂 `AiQueryToolExecutorsTest`，纯 JVM mock）

| 方向 | 用例 | 断言要点 |
|------|------|----------|
| 同部门可见 | `executeQueryUser_sameDept_nonExempt_returnsOnlySameDept` | 非豁免（roleId=3,deptId=11）仅保留 deptId=11 记录 |
| 跨部门不可见 | `executeQueryUser_crossDept_excludedByDeptFilter` | 混入 deptId=99 跨部门记录被过滤，结果仅含 deptId=11 |
| 豁免角色全量 | `executeQueryUser_superAdmin_exemptFromDeptFilter_allVisible` | 超管（roleId=1,deptId=null）保留跨部门全部 2 条 |
| 无部门边界 | `executeQueryUser_noDept_nonExempt_returnsEmpty` | 非豁免 deptId=null → 空列表 |

该类 27 run 全绿（原 23 + 新增 4）。单测零 LLM / 零向量库 / 零真实 DB 外呼，全 mock。

## 6. 评测面 / 工具描述一致性核对

- `src/test/resources/ai-eval/cases.json` 两条 queryUser 用例（L284-289 / L322-328）仅断言 `expectedTool: queryUser` 与 `expectedParams.realName`，**不涉及工具描述字样** → 无需同步。
- `AiToolRegistryContextTest` / `AiEvalCaseSetTest` / `AiEvalMetricsTest` 仅断言工具名与指标，**不断言描述文本** → 无需同步。

## 7. 门禁数字与树净自证

- `mvn -B -ntp test`：Tests run **1484**，Failures 0, Errors 0, Skipped 0（基线 1480 → 1484，+4）。
- 基线更新：`bash scripts/check-test-baseline.sh --update` 已写库（surefire.tests=1484）。
- `bash scripts/merge-gate.sh --with-verify`：**9/9 全绿**（unit / spotbugs / pmd / it / baseline / frontend-unit / hook / bijection / pmd-baseline）。Docker 在线（29.6.2）。端点权限覆盖审计 SECURED=250 / INTENTIONAL_OPEN=13 / PENDING_DECISION=0 / CRITICAL=0；schema 漂移审计 finding=7（皆已知豁免，无 CRITICAL）。
- typed-router：零 diff（`git status --porcelain -- frontend/typed-router.d.ts` 为空；vitest 行尾噪声已 `git checkout --` 还原）。
- 树净：完工后 `git status --porcelain` 仅剩白名单 4 项（`openspec/changes/fix-review-mechanical-check-true/`、`work/task-card-fix-review-mechanical-check-true.md`、`work/task-card-wire-customer-insight-panel.md` + 本卡 `work/task-card-add-queryuser-dept-isolation.md` 提交期纳入本卡 commit）。
- **未 merge、未 push**：本分支独立存在，master 未动；`git push` 未经 Owner 授权绝不执行（可 `git log --oneline -1 master` 复核 master 仍为 e33fb97）。

## 8. 自检已覆盖红线

零迁移（未建 Vxx）、零权限常量、零冻结契约（spec/ 零 diff，`/user/find` OpenAPI 契约未变）、未改 queryUser 之外的 AI 工具、未动 PermissionsInterceptor 本体（仅对齐其既有 roleId=1 豁免口径）、零 LLM/向量库外呼、预期零前端改动（实际零）。

> 合入 master@51028c2（merge --no-ff，4+1 笔），复核 GO 报告 raw：work/tmp/qdi-review/review-evidence.md