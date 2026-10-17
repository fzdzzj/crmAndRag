# 任务卡 · wire-built-unfinished-infra（已建未接线基建接线合卡，B 档，本批三卡 3/3）

> 目标：预算闸 + 内容安全接线启用 + 死键清理，消掉暗线①「已管控错觉」家族大半。
> 锚点：v2 池暗线①（J-9 内容安全零调用 / TokenBudget 预算闸 / ai.model.* 死键全锚点）。

## 红线

- 零迁移 / 零权限常量 / 零真实外呼（内容安全纯本地规则）；死基建三表（lifecycle/batch）不入本卡；
  只接线不动内部实现；预期零前端。
- 验收：接线处命中/放行两向单测；死键清理无悬空引用；基线只增；merge-gate 8/8。

## 交付纪律

- 分支 `feature/wire-built-unfinished-infra`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；B 档独立复核（门禁 exit 0 主证 + 写集 diff 审查）。
