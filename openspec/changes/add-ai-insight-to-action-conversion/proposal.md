# 提案 · AI 诊断建议一键转 Action (add-ai-insight-to-action-conversion)

> 档位：B 档（零 DB 迁移、零权限常量新增、零外呼、零冻结契约触碰）
> 来源：`work/_paa-candidates-pool.md` §7.2 断点 2（AI 主动赋能链路断裂，最高价值突破口）
> 基线：master@246a849，Surefire 1450 / Failsafe 118

## 1. 背景与动机

客户 360° AI 诊断（`add-ai-customer-360-insight`）输出 `summary` / `riskAlerts` / `actionSuggestions` 后链路即断：用户看到拜访建议后仍需手动切换菜单创建任务或活动。而系统草稿-确认流（`PendingActionService.submitDraft/confirm`）与 9 个 `AiActionExecutor`（实测枚举：CREATE_ORDER / CREATE_CONTRACT / CREATE_CUSTOMER / CREATE_CONTACT / CREATE_OPPORTUNITY / CREATE_PAYMENT / CREATE_INVOICE / CREATE_CONTACT_TASK / CREATE_BUSINESS_ACTIVITY；§110 调研笔记的"11 个"为含计划项口径）完全就绪，从未被诊断消费。本卡打通 **Insight → Suggestion → PendingAction → Confirm** 闭环，是 AI 主动赋能的最后一公里。

## 2. 目标

1. 诊断报告的每条 `actionSuggestions` 可一键转为 `PendingAction` 草稿，支持 `CREATE_CONTACT_TASK`（默认，转跟进待办）与 `CREATE_BUSINESS_ACTIVITY`（转拜访活动）两种目标；
2. 草稿进入既有确认流：确认时按 `ActionTypeEnum` 既有映射 `requirePermission`（TASK_CREATE_TASK / SALES_CREATE_BUSINESS_ACTIVITY），**零新增权限语义**；
3. 全链纯结构化映射，不发起任何新的 LLM 外呼（诊断的 LLM 调用为既有能力）。

## 3. 方案要点

- **后端**：新建 `InsightToActionService`——读取 `CustomerInsightService` 诊断结果的 `actionSuggestions[index]`，组装对齐执行器消费格式的 payload（**参照 `AiDraftToolExecutors` 既有草稿的 payload 形状**），调 `PendingActionService.submitDraft`，返回 pendingId 与草稿摘要；
- **端点**：`AiInsightController` 新增 `POST /ai/insight/customer/{companyId}/to-action`，body `{ suggestionIndex, actionType? }`，挂 `@RequirePermission(CUSTOMER_VIEW_COMPANY)`（与诊断端点同锚：可见即可转，写权限闸由确认流既有映射把关，与 AI 草稿工具同口径）；
- **前端**：诊断结果建议区每条附「转待办 / 转活动」操作，调用新端点，成功后提示 pendingId 并引导既有确认入口；
- **降级语义**：诊断结果不含建议（空列表）、index 越界、actionType 非法均返回 400 明确报错，不静默。

## 4. 非目标（明确不做）

- 不新建 ActionType / AiActionExecutor；不做建议→多类型智能路由（LLM 分类，留后续卡）；
- 不改 `PendingActionService` 确认流与任何执行器；不动 PendingAction 表结构；
- 前端不重建确认 UI（复用既有确认入口）；不碰冻结契约。

## 5. 影响面与风险

- **写集（预期）**：新增 1 服务类 + 1 端点方法 + 单测；修改 `frontend/openapi.yaml`、前端诊断展示组件与 hook；`PermissionCoverageScanner.CONTROLLER_REGISTRY` 若需登记则按既有口径；
- **主要风险**：payload 与执行器消费格式不一致 → 由单测对齐 `AiDraftToolExecutors` 既有 payload 形状断言锁死；
- **权限链**：草稿创建与 AI 工具草稿同口径（挂 null 无条件注入语义不变），确认时既有映射把关。

## 6. 验收（B 档门禁）

- `mvn -B -ntp test` 全绿且基线只增（1450 起）；四静态 0 违规；`bash scripts/merge-gate.sh` 8/8；前端 `pnpm lint:check` / `type-check:check` / `test` 全过；`check-openapi-consistency.sh` 一致；
- 单测覆盖：建议→草稿 payload 映射、空建议拒绝、index 越界拒绝、actionType 校验、pendingId 返回。
