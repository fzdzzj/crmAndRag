# 交接 · add-confirm-reaudit-coverage（确认链路写权限再审计测试加固，C 档，D6 单卡）

> 分支 `feature/add-confirm-reaudit-coverage`，自 master@1f890ab 检出；完工停步：**未 merge、未 push**。
> 执行树：权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（主协作树未触，`frontend/typed-router.d.ts` 零 diff）。

## 1 · 提交清单（线性 1 笔 + 物料同笔）

- `feat(test): add confirm-chain permission denial reverse test`（新增 `PendingActionConfirmPermissionAuditTest` +1 用例；`scripts/test-baseline.txt` 1583→1584；`openspec/changes/add-confirm-reaudit-coverage/tasks.md` 勾选与依据；本 handoff）
- 零 src/main 改动；零前端改动。

## 2 · 缺口与实现

- **机制已闭合（本卡不动）**：`PendingActionGuards#requirePermission`（`PendingActionGuards.java:21-31`）按 `ActionTypeEnum#getRequiredPermission` 集中映射，未知类型 fail-closed；confirm 链路调用点 `PendingActionServiceImpl#executePendingAction`（L203），执行链路 L351 同源。
- **残留缺口**：既有 `PendingActionServiceImplTest#confirm_noPermission_throws` 只断言 message 含「无权限」——不校验错误码、不校验状态未流转、不校验 executor 零交互；`PendingActionNewDraftPermissionTest` 是**守卫级**直调。缺的是「从 `confirm` 入口穿到执行主体」的端到端反向用例。
- **本卡新增**：`src/test/java/com/slz/crm/server/service/impl/PendingActionConfirmPermissionAuditTest.java`（1 用例，复用 `PendingActionServiceImplTest` 的 ReflectionTestUtils 注入 + `stubFind` 三路 MP 查询 stub 模式）。
  - `confirm(...)` 入口 → `getOwnedEntity` 归属校验 → 终态幂等判定 → `executePendingAction` → `requirePermission` 拒绝。
  - 三重断言：① `getCode() == ErrorCode.PERMISSION_DENIED.getCode()`（12002）+ message 含「无权限」「CREATE_ORDER」；② `entity.getStatus()` 仍 `PENDING`、`getConfirmedTime()` 为 null、`verify(mapper, never()).update(any(), any())`（未发生 CAS 抢占式写入）；③ `verifyNoInteractions(executor)` + `verifyNoInteractions(validator)`。
  - 细节两处取舍：`initStrategyMaps()` 会对 executor/validator 各调一次 `actionType()`，故 setUp 末尾 `clearInvocations(executor, validator)`，使「零交互」断言只覆盖 confirm 链路内交互；`lenient().when(mapper.update(any(), any())).thenReturn(1)` 显式声明「即使 CAS 本可抢占成功，权限闸在前，写入仍不得发生」。
- **初始写成 2 个 `@Test`**（第二个为 CAS-would-succeed 变体），因基线口径要求 1583→1584（+1 用例），已合并为 1 个用例、把 CAS stub 并入同方法，用例数精确对齐。

## 3 · 断言非空洞实测（变异验证）

- 临时把 `thenReturn(false)` 改为 `thenReturn(true)` 复跑：`Tests run: 1, Failures: 1`，失败点 `expected: 12002`——证明权限闸一旦被绕过，用例必红，断言非事后恒真。
- 改回后：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS（`-Dtest=PendingActionConfirmPermissionAuditTest`）。
- 过程事故披露：变异时首次用 PowerShell `Set-Content` 改写文件，把 UTF-8 中文写成 GBK，`spotless:check` 报 `Encoding error`；已 `Copy-Item` 自备份还原后改用 edit 工具重做变异，最终文件为 UTF-8 且 `spotless:check` 通过（未用跳过开关收尾）。

## 4 · 门禁与基线（实测）

- `mvn -B -ntp clean verify`：**EXIT=0**；surefire `Tests run: 1584, Failures: 0, Errors: 0, Skipped: 0`、failsafe `Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`；checkstyle / spotless / spotbugs / pmd 全过。
- `bash scripts/check-test-baseline.sh`（更新前）：`reports=256 tests=1584` 对照旧基线 `255/1583` 通过 → `--update` 写入 **surefire.reports=256 / surefire.tests=1584 / skipped=0**，failsafe 31/118/7 持平，`source-revision=1f890ab`，`measured-at=2026-10-17T11:12:44Z`。
- `bash scripts/merge-gate.sh`：**EXIT=0，8/8 PASS**——`[unit] [spotbugs] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`（bijection 12↔12；pmd 实测 0==台账 0==pom 阈值 0；frontend-unit 197/197）。
- 首轮 merge-gate **EXIT=1** 的真实原因：不是门禁红，而是上一轮 `[unit]` 前残留的 `frontend/typed-router.d.ts` 行尾被 vitest 改写导致工作树脏；按门禁 NOTE `git checkout -- frontend/typed-router.d.ts` 还原后重跑即 EXIT=0。

## 5 · 边界与自查

- `git diff master --stat -- src/main` → **完全为空**；`git status --porcelain frontend/` → 空。
- 未改 `PendingActionGuards` / `PendingActionServiceImpl` / `ActionTypeEnum` 映射；未扩 actionType。
- 临时日志 `work/verify-add-confirm-reaudit.log`、`work/merge-gate-add-confirm-reaudit.log` 收尾即删，未入库。
- 未执行 `git push`、未执行 `git merge`；停在等 Owner 复核与合并授权。

## 6 · 待 Owner 决策

- 合并授权（`git merge --no-ff feature/add-confirm-reaudit-coverage`）与本卡是否归档到 `openspec/changes/archive/`。
- `work/task-card-wire-customer-insight-panel.md` 为他人卡，本卡未触碰，仍为未跟踪状态。
