# 任务卡 · fix-review-mechanical-check-true（OBS-1 脚本修复，C 档，三卡序列 3/3）

> 目标：`scripts/tests/review-mechanical-check.sh` 移除 `|| true` 吞错，核对失败真实传退出码。
> 边界：只修错误传递，不改核对逻辑，不升格 CI 门禁（维持 AGENTS.md 非门禁定位）。

## 验收

- 两向自测：应失败场景退出码非 0 / 正常场景 0（留证）；Java 侧零改动（基线 1472 持平）。

## 交付纪律

- 分支 `feature/fix-review-mechanical-check-true`（自 master 检出，1 笔即可，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格依据行；handoff 真实披露。
