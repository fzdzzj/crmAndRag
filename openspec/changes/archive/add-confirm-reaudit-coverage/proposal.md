# 提案 · 确认链路写权限再审计测试加固 (add-confirm-reaudit-coverage)

> 档位：C 档（纯测试加固，src/test 单侧，零 src/main 改动）
> 来源：`work/_paa-candidates-pool.md` §7.3 决策点 D6；2026-10-16 主 Agent 调研降级定案
> 基线：master@17008e2（排队卡：待 harden-oppo-stage-approval 闭环后派发）

## 1. 背景与目标

D6 原范围（草稿确认二次写权限审计）**机制已闭合**：`PendingActionGuards#requirePermission`
（L21-31，actionType → `ActionTypeEnum#getRequiredPermission` 集中映射，未知类型即拒 fail-closed）
已在 confirm 链路（`PendingActionServiceImpl` L203）与执行链路（L351）两处生效，拆自
tighten-pmd-residual-325 任务 6.3（行为等价重构，机制先于该卡存在）；单元级
`PendingActionNewDraftPermissionTest` 三用例（正向/无权限拒绝/未知类型拒绝）在案。

残留缺口仅一处：**confirm 链路端到端「无权限被拒」无专属反向单测**。本卡补齐：

1. confirm 链路反向用例：mock permissionService 无权限 → 断言 PERMISSION_DENIED 且草稿状态不落 CONFIRMED、
   executor 零交互（verifyNoInteractions）。

## 2. 非目标

零 src/main 改动；不改 PendingActionGuards / PendingActionServiceImpl；不扩 actionType 映射。

## 3. 验收

- 新增反向用例绿；基线只增（1484 起）；merge-gate 8/8；`git diff master --stat -- src/main` 为空。
