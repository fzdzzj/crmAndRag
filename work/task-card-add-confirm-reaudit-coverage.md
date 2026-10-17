# 任务卡 · add-confirm-reaudit-coverage（确认链路权限再审计测试加固，C 档，D4/D6 双卡 2/2）

> 目标：补 confirm 链路端到端无权限反向单测（机制已闭合，本卡纯测试纵深）。
> 锚点：PendingActionServiceImpl confirm 链路 L203（requirePermission 调用点）；
> 参照 PendingActionNewDraftPermissionTest 既有 mock 模式。

## 红线

- 零 src/main 改动（`git diff master --stat -- src/main` 为空）；预期零前端（typed-router 零 diff）；
- 断言非空洞：错误码 + 状态不流转 + executor 零交互三重验证。

## 交付纪律

- 分支 `feature/add-confirm-reaudit-coverage`（自 master 检出，1~2 笔线性，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格依据行；handoff 真实披露；
- C 档不派独立复核（机械核对：diff 域 + 用例断言真实性 + 基线持平对账）。
