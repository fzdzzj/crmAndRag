# 提案：前端 AI 助手集成与稳定性修复

## Why

`feature/ai-assistant-final` 已实现聊天面板、SSE 渲染、会话管理、确认卡片、追问进度、停止输出和实体引用跳转；本地验证显示 24 个单元测试、ESLint 和三套类型检查均通过。

但该分支仍不能直接合入 `origin/main`：它基于落后的本地 `main`，`openapi.yaml` 与最新主线冲突；后端真实下发 references（见 SseEventName + SseContractTest）；actionCard/draftProgress 未在枚举/契约测试中出现，必须降级（忽略未知事件，不渲染前端伪造卡片）；前端确认卡片按整包替换 payload，候选编辑会丢失其他字段；确认成功后的状态也被硬编码为 `CONFIRMED`。因此需要一个提案收敛契约对齐、行为修复和验证闭环。

## What Changes

- 将 `feature/ai-chat-panel`、`feature/ai-action-card`、`feature/ai-assistant-final` 的成果收敛到基于最新 `origin/main` 的分支。
- 保持 AI 助手入口、响应式 Drawer、消息流、图表、会话列表和历史消息加载能力。
- 对齐后端 SSE 契约：后端未支持的事件必须显示降级或临时隐藏，不得把前端想象态当作已交付能力。
- 修复确认卡片状态刷新、payload 编辑、终态处理和错误提示。
- 将组件中的直接 API 调用收口到 `useAiAction`。
- 强化停止输出、AbortController 清理、流式错误和可访问性。
- 补充 Vitest、Playwright 检查点、CI 单测步骤和功能验收文档。

## Impact

### 受影响的规范

- 新增 `spec/specs/ai-assistant-frontend/spec.md`，定义前端 AI 助手交互、流式渲染、确认操作、降级和质量要求。

### 受影响的代码

- `src/hooks/useAiChat.ts`
- `src/hooks/useAiSession.ts`
- `src/hooks/useAiAction.ts`
- `src/components/ai/*`
- `src/pages/(dashboard).page.vue`
- `openapi.yaml`
- `package.json`、`vitest.config.ts`、`tsconfig.test.json`

### API 变更

- 前端不新增业务端点。
- 必须以真实后端契约为准重新生成 `src/api/axios`，禁止手改生成目录。
- 若保留 `references`、`actionCard`、`draftProgress`，需要后端补齐对应 SSE 契约；否则前端暂时降级。

### 需要迁移

- [ ] 数据库迁移：否
- [ ] API 版本提升：否
- [ ] 用户沟通：否
- [ ] 文档与测试检查点更新：是

## 时间线评估

中等工作量：分支重放、契约对齐、确认卡片修复、测试补齐约需 2-3 个工作日；若后端同时补发 SSE 事件，联调再加 1-2 个工作日。

## 风险

- `openapi.yaml` 冲突：先以 `origin/main` 为基线，再用后端最终契约重新生成。
- 前后端事件不同步：按契约开关渲染，避免历史卡片误展示。
- 确认操作重复提交：loading、终态检查和幂等响应必须统一。
- 移动端入口遮挡：保留 48px 触控目标并回归固定位置。
- 直接使用 SSE fetch：需要处理 401、403、网络异常和组件卸载后的状态更新。

