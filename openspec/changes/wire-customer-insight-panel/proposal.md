# 提案 · CustomerInsightPanel 双宿主接线 (wire-customer-insight-panel)

> 档位：B 档轻（纯前端，预期 <30 行改动；零后端/零迁移/零权限/零契约）
> 来源：`add-ai-insight-to-action-conversion` §112 范围裁剪留档（Panel 已产未接线）
> 基线：master@70027c6，Surefire 1457 / Failsafe 118

## 1. 背景与目标

§112 已交付 `CustomerInsightPanel.vue`（诊断建议「转待办/转活动」完整交互），但宿主未定而未接线——用户实际看不到转化入口。本卡将 Panel 接线到双宿主，收口 Insight → Suggestion → PendingAction → Confirm 最后一公里：

1. **客户详情 modal**：客户公司详情弹窗内嵌入 Panel（传 companyId），浏览详情即可诊断并转化；
2. **AiAssistantDrawer**：与既有「📊 诊断客户健康度」快捷指令联动展示 Panel（上下文客户明确时）。

## 2. 非目标

- 不改 CustomerInsightPanel.vue 本体（除非接线必需的 prop 默认值/emit 契约微调）；
- 不动后端 / 迁移 / 权限常量 / openapi 契约；不重建确认 UI（既有入口）。

## 3. 验收

- 两宿主均可查看诊断并完成转待办/转活动，`created` 事件有可见反馈；
- 前端三检全过；`merge-gate.sh` 8/8（surefire 基线 1457 持平或只增）；
- 可选：宿主渲染 Vitest 1-2 例（不强制）。
