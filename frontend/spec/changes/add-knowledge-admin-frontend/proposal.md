# 提案：知识库管理台前端（依赖主仓 add-knowledge-admin-api）

## Why

主仓提案 `add-knowledge-admin-api`（crmAndRag）为 knowledge 域暴露首批 HTTP 管理面（`/knowledge` 7 端点：KB 列表、文档分页/上传/删除/状态/重建、检索 dry-run）。当前前端 `openapi.yaml` 无任何 knowledge 端点，也没有知识库页面——运营无法查看文档摄取状态、无法重建、无法测试检索效果。

## What Changes

- **契约同步**：后端合入后从 `/v3/api-docs.yaml` 重新生成 `openapi.yaml`，执行 `pnpm gen:api`；`src/api/axios/` 生成目录禁手改。
- **hooks**：新增 `src/hooks/useKnowledge.ts`（`useKnowledgeBases` / `useKnowledgeFiles` / `useKnowledgeUpload` / `useKnowledgeFileRemove` / `useKnowledgeReingest` / `useRetrievalTest`），全部走 Vue Query，组件内禁止直接调 API。
- **页面**：新增 knowledge 路由组（`src/pages/(dashboard)/knowledge/`）：
  - KB 选择器（按后端返回的可见 KB 过滤）；
  - 文档表格：文件名、状态、chunk 数、页数、摄取时间，操作列（删除/重建，均带确认弹窗）；
  - 上传：文件选择 + 拖拽，摄取中状态轮询；
  - 检索测试面板：输入 query → 候选 chunk 列表 + 分数（默认稀疏零外呼口径，页面上明示）。
- **规范**：Tailwind-only（无 `<style>`）、PascalCase 组件、日期 `yyyy-MM-dd HH:mm:ss`；Playwright 检查点记入 `docs/test-checkpoints.md`；hooks 补 Vitest。

## Impact

### 受影响的规范

- 新增 `spec/specs/knowledge-admin-frontend/spec.md`（页面交互、状态轮询、确认弹窗、零外呼明示要求）。

### 受影响的代码

- `openapi.yaml`、`src/api/axios/`（生成）
- `src/hooks/useKnowledge.ts`（新增）
- `src/pages/(dashboard)/knowledge/`（新增路由组）
- `docs/test-checkpoints.md`

### API 变更

- 前端不新增端点；消费主仓 `/knowledge/*` 7 端点。

### 需要迁移

- [ ] 数据库迁移：否
- [ ] API 版本提升：否
- [ ] 用户沟通：否
- [ ] 文档与测试检查点更新：是

## 依赖与顺序（硬前置）

1. 主仓 `add-knowledge-admin-api` 已合入且部署环境可用——否则契约不存在，禁止开工。
2. 本提案在主仓合入后基于最新 `origin/main` 建分支。

## 成本闸门

- 上传摄取/重建/真向量检索均有模型成本：开发期用后端测试环境 mock；页面上生产操作属授权行为，不在本提案内顺手执行。

## 时间线评估

中等偏大工作量：契约与 hooks 0.5 天，页面（列表/上传/轮询/检索面板）1.5–2 天，测试与检查点 0.5 天。

## 风险

- 后端未合入先开工 → 契约为空；必须核对主仓 merge hash 后再动。
- 异步摄取状态轮询：后端无推送，轮询间隔与终止条件要明确（完成/失败/超时）。
- 检索面板误触发真向量检索 → 默认稀疏，真向量入口需显式确认文案。
