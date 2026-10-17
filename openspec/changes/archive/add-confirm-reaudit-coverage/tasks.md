# 任务清单 · 确认链路写权限再审计测试加固 (add-confirm-reaudit-coverage)

<!-- id: add-confirm-reaudit-coverage -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 测试加固

- [x] 1.1 confirm 链路无权限反向单测（mock permissionService 拒绝 → PERMISSION_DENIED + 状态不落 CONFIRMED + executor 零交互）；基线只增后 `--update`；merge-gate 8/8；`git diff master --stat -- src/main` 为空；产出 `work/handoff-add-confirm-reaudit-coverage.md`（真实披露）
      - 新增 `src/test/java/com/slz/crm/server/service/impl/PendingActionConfirmPermissionAuditTest.java`（1 用例）：从 `PendingActionServiceImpl#confirm` 入口穿过实体归属校验与终态幂等判定，`when(permissionService.hasPermission(2L, SALES_APPEND_ORDER)).thenReturn(false)` 驱动 confirm 链路 fail-closed
      - 三重断言：① `thrown.getCode() == ErrorCode.PERMISSION_DENIED.getCode()`（12002）且 message 含「无权限」「CREATE_ORDER」；② `entity.getStatus()` 仍为 `PENDING`（非 `CONFIRMED`）、`getConfirmedTime()` 为 null、`verify(mapper, never()).update(any(), any())`；③ `verifyNoInteractions(executor)` 且 `verifyNoInteractions(validator)`
      - 断言非空洞已实测：临时把 `thenReturn(false)` 改为 `true` 复跑 → `Tests run: 1, Failures: 1`（`expected: 12002`），确认权限闸被绕过时用例必红；改回后 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS（`-Dtest=PendingActionConfirmPermissionAuditTest`）
      - `mvn -B -ntp clean verify` → surefire `Tests run: 1584, Failures: 0, Errors: 0, Skipped: 0`、failsafe `Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`、BUILD SUCCESS；随后 `bash scripts/check-test-baseline.sh --update` 写入 `surefire.reports=256` / `surefire.tests=1584`（源 1583→1584，source-revision=1f890ab）
      - `bash scripts/merge-gate.sh` → 8/8 PASS（unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline），EXIT=0；`git diff master --stat -- src/main` 为空，`frontend/` 零 diff（vitest 改写的 `typed-router.d.ts` 行尾已 `git checkout --` 还原）
