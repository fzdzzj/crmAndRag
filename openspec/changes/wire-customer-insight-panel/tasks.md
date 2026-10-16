# 任务清单 · CustomerInsightPanel 双宿主接线 (wire-customer-insight-panel)

<!-- id: wire-customer-insight-panel -->
<!-- verify: bash scripts/merge-gate.sh -->

> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务组 1 · 双宿主接线

- [x] 1.1 客户详情 modal（Grep 定位既有公司详情弹窗组件）引入 `CustomerInsightPanel` 传 `companyId`，处理 `created` 事件（成功反馈 + 按需刷新），保持弹窗既有布局风格一致
      `frontend/src/components/client/CompanyEditModal.vue` 只读「查看客户」视图内 `<a-divider>客户诊断</a-divider>` + `<CustomerInsightPanel :company-id="currentCompanyId">`；`created` → `message.success` + `insightKey` 自增重挂 Panel 刷新
- [x] 1.2 `AiAssistantDrawer.vue` 引入 `CustomerInsightPanel`，与「📊 诊断客户健康度」快捷指令联动（指令触发或上下文客户明确时展示），处理 `created` 事件
      `frontend/src/components/ai/AiAssistantDrawer.vue`：`routeCompanyId`（路由 query `filters.id`）优先、对话内 CUSTOMER 业务来源兜底为 `contextCompanyId`；抽屉可见且上下文明确即展示，药丸点击额外联动；`created` → `antdMessage.success` + `sessionMessages.refetch()`

## 任务组 2 · 门禁与交付

- [x] 2.1 前端三检（lint / type-check / test）全过；`bash scripts/merge-gate.sh` 8/8（surefire 基线 1457 持平或只增，不增则无需 --update）；typed-router.d.ts 零改动还原；产出交接快照 `work/handoff-wire-customer-insight-panel.md`（真实披露 HEAD/树净/未 merge 未 push）
      `pnpm lint:check` exit 0；`pnpm type-check:check` exit 0；`pnpm test` → 20 files / 188 tests passed（基线 18 files / 184 tests，新增 2 files / 4 tests）；`bash scripts/merge-gate.sh` → 8/8 PASS（`surefire 报告=223 Tests run=1457 Failures=0`，`failsafe 报告=31 Tests run=118 Skipped=7`）；Docker 在线故另跑 `bash scripts/merge-gate.sh --with-verify` → 9/9 PASS（[it] 真跑）；`git status --porcelain -- frontend/typed-router.d.ts` 为空（工具链再生后已 `git checkout --` 还原）
