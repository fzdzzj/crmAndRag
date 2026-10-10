# 提案：知识库管理前端 Hook 单元测试套件建设（test-frontend-use-knowledge）

> 变更 ID：`test-frontend-use-knowledge` ｜ 能力域：`frontend` / `knowledge` ｜ 执行基线：`master@b0b24a2`
> 依赖：前序 `add-knowledge-admin-api`（已合入 master）、`resolve-frontend-lint-warnings`（已合入 master）

---

## 1. Why（背景与痛点）

在完成后端知识库管理端点暴露与真外呼试点（任务组 1–6）后，前端通过 `frontend/src/hooks/useKnowledge.ts` 实现了知识库管理界面的核心数据交互层，涵盖知识库列表查询、文档列表过滤、文件上传、文档删除、文档重建与检索 dry-run 等 6 组核心 query/mutation 操作。

然而现场取证排查发现：
1. **测试覆盖缺口**：全仓既有核心 hooks（如 `useAiChat.test.ts`、`usePlatformConfig.test.ts`、`useTutorial.test.ts`）均有完善的 Vitest 单元测试保护，而 `useKnowledge.ts` 目前缺少专属单元测试套件；
2. **防回归与状态契约**：前端 VueQuery 的 Query Invalidation（例如上传/删除/重建后触发文档列表自动刷新）、错误时的全局 Toast 提示（`message.error`）缺乏自动化回归断言。

因此，立项为 `useKnowledge.ts` 建设高标准单元测试套件，彻底填补这一质量盲区。

---

## 2. What Changes（变更内容）

### 2.1 新建单元测试套件
新建 `frontend/src/hooks/__tests__/useKnowledge.test.ts`，基于 `jsdom` 环境与 `@tanstack/vue-query` 的 `withSetup` 范式，覆盖以下 6 大场景：

1. **`useKnowledgeBases`**：
   - 验证调用 `getKnowledgeBases` API，成功返回格式化后的 `KnowledgeBaseVO[]` 列表；
   - 验证 API 异常时的错误状态传递。
2. **`useKnowledgeFiles`**：
   - 验证无 `kbId` 时查询全量可见文档列表；
   - 验证传入 `ref(kbId)` 响应式参数时正确传递 query 参数，并在 `kbId` 变化时触发查询重算。
3. **`useKnowledgeUpload`**：
   - 验证成功上传文件后触发 `knowledgeFiles` 查询失效（`invalidateQueries`）并弹出成功 Toast；
   - 验证上传失败时捕获错误并弹出错误 Toast（`message.error`）。
4. **`useKnowledgeFileRemove`**：
   - 验证成功删除文档后触发 `knowledgeFiles` 查询失效并弹出成功 Toast；
   - 验证删除失败时的错误提示。
5. **`useKnowledgeReingest`**：
   - 验证成功触发单文档重建后调用 `invalidateQueries` 并弹出提示；
   - 验证重建失败时的错误处理。
6. **`useRetrievalTest`**：
   - 验证传递稀疏检索（`useVector=false`）或真向量检索（`useVector=true`）请求参数时正确调用后端端点；
   - 验证返回候选列表与相似度分数，以及调用失败时的错误捕获。

---

## 3. Impact & Boundary（影响与边界）

- **受控写集**：
  1. `openspec/changes/archive/test-frontend-use-knowledge/`（提案三件套）
  2. `frontend/src/hooks/__tests__/useKnowledge.test.ts`（新建测试文件）
  3. `work/task-card-frontend-use-knowledge-test.md`（任务卡）
- **零侵入保证**：
  - 零修改业务源码（不修改 `useKnowledge.ts` 或 `index.page.vue`）；
  - 零修改后端 Java 代码与数据库迁移；
  - 零新外部依赖（直接复用既有 vitest、@tanstack/vue-query、ant-design-vue 依赖）。

---

## 4. 验收标准

1. **测试通过率**：
   - 新增用例全部通过（预计 6~8 个用例），全仓前端测试用例集由 165 增至 171+（17 passed files）；
2. **代码卫生与门禁**：
   - `pnpm lint:check` 保持 0 errors, 0 warnings；
   - `pnpm type-check:check` 0 errors；
   - `pnpm build` 顺利产出构建产物；
   - 换行符全量保持纯 LF。
