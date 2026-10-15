# 任务清单 · AI 诊断建议一键转 Action (add-ai-insight-to-action-conversion)

<!-- id: add-ai-insight-to-action-conversion -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 后端建议转 Action 转换服务

- [x] 1.1 新建 `InsightToActionService`（含实现）：读取 `CustomerInsightService` 诊断结果 `actionSuggestions[index]`，组装对齐执行器消费格式的 payload（参照 `AiDraftToolExecutors` 既有草稿形状），调 `PendingActionService.submitDraft` 返回 pendingId 与草稿摘要
      - 新建 `src/main/java/com/slz/crm/server/ai/InsightToActionService.java`（142 行）；payload 直接复用 `AiContactTaskDraftPayloadDTO` / `AiBusinessActivityDraftPayloadDTO` 序列化锁死执行器消费字段形状
      - `mvn -B -ntp test -Dtest=InsightToActionServiceTest` → `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS
- [x] 1.2 校验语义：actionType 仅接受 `CREATE_CONTACT_TASK` / `CREATE_BUSINESS_ACTIVITY`，越界/空建议/index 越界均 400 明确报错，不静默
      - 缺省 `CREATE_CONTACT_TASK`；非法 actionType → `BaseException(PARAM_FORMAT_ERROR)`；空建议/越界 → `PARAM_OUT_OF_RANGE`；负 index → `PARAM_REQUIRED`；见 `InsightToActionServiceTest` 空建议越界非法三反例断言

## 任务组 2 · API 端点、权限与契约同步

- [x] 2.1 `AiInsightController` 新增 `POST /ai/insight/customer/{companyId}/to-action`，挂 `@RequirePermission(CUSTOMER_VIEW_COMPANY)`，`PermissionCoverageScanner.CONTROLLER_REGISTRY` 按既有口径登记（该 Controller 已在册则确认无需重复）
      - `AiInsightController.java` 新增 `convertSuggestionToAction` POST 方法，`@RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)`
      - 该 Controller 已在 `PermissionCoverageScanner.CONTROLLER_REGISTRY`（`src/test/.../PermissionCoverageScanner.java:264`）在册，无需重复登记；`PermissionCoverageAuditIT` 纯 JVM 静态扫描随 surefire 全量复跑（见 4.2）
- [x] 2.2 `check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml` 并 `pnpm gen:api` 重建 SDK（gitignored 产物）
      - `bash scripts/check-openapi-consistency.sh --update` → 导出测试 `Tests run: 2, Failures: 0`，输出「契约一致」
      - `frontend/openapi.yaml` 新增 `/ai/insight/customer/{companyId}/to-action`（含 `InsightToActionRequest`/`InsightToActionResult` schema）；`pnpm gen:api` 生成 `postAiInsightCustomerByCompanyIdToAction`（sdk.gen.ts:991）与类型，产物 `src/api/axios/*` 为 gitignored

## 任务组 3 · 前端转化交互

- [x] 3.1 诊断结果建议区每条 `actionSuggestions` 附「转待办 / 转活动」操作，调用新端点，成功提示 pendingId 并引导既有确认入口；失败（400）透出明确错误信息
      - 新建 `frontend/src/components/ai/CustomerInsightPanel.vue`：每条建议渲染「转待办(CREATE_CONTACT_TASK)/转活动(CREATE_BUSINESS_ACTIVITY)」；成功 `antdMessage.success('已生成待确认草稿：<pendingId>')` 并 `emit('created', pendingId)` 供宿主接入既有 `useAiAction` 确认入口；失败 400 透出 `error.value`
- [x] 3.2 补充/扩展前端数据 hook 与类型定义，路由与菜单零改动
      - 新建 `useCustomerInsight.ts`（拉诊断）+ `useCustomerInsightToAction.ts`（转动作，返回 pendingId）；新增 5 条 Vitest 单测（`src/hooks/__tests__/useCustomerInsight.test.ts`）；未改路由/菜单，`pnpm lint:check`/`type-check:check`/`test` 全绿（184 用例）

## 任务组 4 · 测试闭环、回归基线与门禁验证

- [x] 4.1 编写 `InsightToActionServiceTest` 单测：payload 映射对齐 `AiDraftToolExecutors` 既有形状断言、空建议/越界/非法 actionType 拒绝、两种 actionType 全路径、pendingId 返回
      - 新建 `src/test/java/com/slz/crm/server/ai/InsightToActionServiceTest.java`（7 用例）：捕获提交的 `payloadJson` 反序列化到执行器消费的 `AiContactTaskDraftPayloadDTO`/`AiBusinessActivityDraftPayloadDTO` 断言字段形状，覆盖默认/显式 actionType、空建议/越界/负 index/非法 actionType 四个反例、pendingId 返回
- [x] 4.2 运行 `mvn -B -ntp test`（基线 1450 只增不减），全绿后 `check-test-baseline.sh --update` 写入真实值；`bash scripts/merge-gate.sh` 8/8；前端三检全过；产出交接快照 `work/handoff-add-ai-insight-to-action-conversion.md`（真实披露 HEAD/基线/树净/未 merge 未 push）
      - `mvn -B -ntp test` → `Tests run: 1457, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（基线 1450→1457，仅 +7 我新增，符合「只增不减」口径），raw 见 `work/review-surefire.log`
      - `bash scripts/check-test-baseline.sh --update` → 已写入 `surefire.tests=1457`（`scripts/test-baseline.txt`，`source-revision=246a849-dirty`）
      - 前端三检：`pnpm lint:check`=0 / `pnpm type-check:check`=0 / `pnpm test`=0（18 文件 184 用例，raw 见 `work/review-frontend-*.log`）
      - `bash scripts/merge-gate.sh` → **8/8 全 PASS，exit 0**：unit(1457) / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline（[it] 默认不跑）；首轮仅 `FAIL [pmd]`（`InsightToActionService` `OnlyOneReturn`），改单返回后复跑全绿，raw 见 `work/review-merge-gate.log`
      - 交接快照 `work/handoff-add-ai-insight-to-action-conversion.md` 已产出（HEAD 基 246a849 / 基线 1457 / 未 merge 未 push）