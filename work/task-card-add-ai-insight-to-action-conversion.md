# 任务卡 · AI 诊断建议一键转 Action（add-ai-insight-to-action-conversion，B 档）

> 目标：客户 360° 诊断的每条 `actionSuggestions` 可一键转 `PendingAction` 草稿（待办/活动二选），
> 经既有确认流（`ActionTypeEnum` 映射权限）落库，打通 Insight → Suggestion → PendingAction → Confirm 闭环。

## 边界与红线

- 零 DB 迁移（禁碰 V1~V42）、零新增权限常量、零 LLM 外呼、零冻结契约触碰；
- 禁碰 `frontend/typed-router.d.ts`（他人/生成物）；
- payload 必须对齐 `AiDraftToolExecutors` 既有草稿形状（执行器消费格式）；
- actionType 仅 `CREATE_CONTACT_TASK` / `CREATE_BUSINESS_ACTIVITY`；
- 不改 `PendingActionService` 确认流与任何 `AiActionExecutor`。

## 关键锚点

- 端点：`POST /ai/insight/customer/{companyId}/to-action`，body `{ suggestionIndex, actionType? }`，挂 `CUSTOMER_VIEW_COMPANY`(117)；
- 诊断源：`CustomerInsightService` → `CustomerInsightResult.actionSuggestions`（List<String>）；
- 草稿落库：`PendingActionService.submitDraft`；确认权限：既有 ActionType 映射。

## 验收

- `mvn -B -ntp test` 全绿且基线 1450 只增；四静态 0；`merge-gate.sh` 8/8；前端三检全过；openapi 一致；
- 单测锁死：payload 映射、空建议/越界/非法 actionType 400、pendingId 返回。

## 交付纪律

- 分支：`feature/add-ai-insight-to-action-conversion`（自 master 检出，线性提交，完工树净）；
- 提交后停步等 Owner：不 merge、不 push；
- tasks.md 逐项勾选 + 6 空格实测依据行；handoff 真实披露（禁止虚报哈希/基线/树净状态）。
