# Frontend Quality Spec Delta: test-frontend-use-knowledge

## 1. Scope and Target
本规范定义 `frontend/src/hooks/useKnowledge.ts` 的行为契约与前端质量守卫增量。通过为知识库管理交互层（涵盖 6 组 query/mutation 函数）建立确定性 Vitest 单元测试，将前端测试套件由 165 个用例增补至 171+ 用例，确立无网络依赖、毫秒级响应的前端回归门禁。

---

## 2. Requirements & Scenarios

### 2.1 知识库列表查询 (`useKnowledgeBases`)
- **Requirement**: `useKnowledgeBases` SHALL 使用 Query Key `['knowledgeBases']` 发起查询，调用 `getKnowledgeBases({ client: apiClient })`，并将响应数据 `res.data?.data` 映射为 `KnowledgeBaseVO[]`。
- **Scenario: 成功加载知识库列表**:
  - **GIVEN** SDK `getKnowledgeBases` 返回成功的包含 2 个知识库实体的响应
  - **WHEN** 在 `withSetup` 中执行 `useKnowledgeBases` 并等待 query 成功
  - **THEN** 返回的 `data` 长度为 2，包含预期的字段，且 API 被正确调用。
- **Scenario: 接口异常错误传递**:
  - **GIVEN** SDK `getKnowledgeBases` 拒绝抛出错误
  - **WHEN** 执行 `useKnowledgeBases` 并等待
  - **THEN** query 状态流转为 `isError=true`，数据回落为空列表。

### 2.2 知识库文件列表查询 (`useKnowledgeFiles`)
- **Requirement**: `useKnowledgeFiles(kbId?: Ref<number | null | undefined>)` SHALL 基于计算属性 Query Key `['knowledgeFiles', kbId?.value ?? null]` 发起查询。未指定 `kbId` 时不传 query 参数；指定 `kbId` 时向 `getKnowledgeFiles` 传递 `{ kbId: kbId.value }`。
- **Scenario: 无条件全量查询**:
  - **GIVEN** 未传递 `kbId` 参数
  - **WHEN** 执行 `useKnowledgeFiles()`
  - **THEN** 发起 API 调用时 `query` 参数为 `undefined`，返回全量文件列表。
- **Scenario: 响应式条件查询与重算**:
  - **GIVEN** 传递响应式 `ref(101)` 作为 `kbId`
  - **WHEN** 初始挂载后修改 `kbId.value = 202`
  - **THEN** Query Key 响应式更新为 `['knowledgeFiles', 202]`，并以新参数重新触发 API 查询。

### 2.3 文件上传 (`useKnowledgeUpload`)
- **Requirement**: `useKnowledgeUpload` SHALL 提供上传 mutation，接收 `{ file: File, kbId: number }`，调用 `postKnowledgeFiles`。成功时 MUST 触发 `['knowledgeFiles']` 缓存失效并展示 `'上传成功，摄取中...'` 成功提示；失败时 MUST 展示后端错误信息或默认失败提示。
- **Scenario: 上传成功触发缓存失效与提示**:
  - **GIVEN** SDK `postKnowledgeFiles` 成功返回摄取结果
  - **WHEN** 调用 `mutateAsync({ file, kbId: 101 })`
  - **THEN** `queryClient.invalidateQueries({ queryKey: ['knowledgeFiles'] })` 被调用，`message.success` 接收预期提示文案。
- **Scenario: 上传失败提示拦截**:
  - **GIVEN** SDK `postKnowledgeFiles` 抛出 `Error('文件格式不支持')`
  - **WHEN** 调用 `mutateAsync` 并捕获异常
  - **THEN** `message.error` 被调用并包含 `'文件格式不支持'`。

### 2.4 文件删除 (`useKnowledgeFileRemove`)
- **Requirement**: `useKnowledgeFileRemove` SHALL 提供删除 mutation，接收文档 ID 字符串，调用 `deleteKnowledgeFilesById({ path: { id } })`。成功时 MUST 触发 `['knowledgeFiles']` 缓存失效并展示 `'删除成功'`；失败时展示错误提示。
- **Scenario: 删除成功流程**:
  - **GIVEN** SDK `deleteKnowledgeFilesById` 成功返回 `true`
  - **WHEN** 调用 `mutateAsync('doc-uuid-123')`
  - **THEN** 触发 `['knowledgeFiles']` 缓存失效，`message.success('删除成功')` 被调用。
- **Scenario: 删除失败流程**:
  - **GIVEN** SDK 接口报错 `Error('文档不存在或无权限')`
  - **WHEN** 调用 `mutateAsync` 失败
  - **THEN** `message.error` 弹出相应错误。

### 2.5 单文档重建 (`useKnowledgeReingest`)
- **Requirement**: `useKnowledgeReingest` SHALL 提供重建 mutation，接收文档 ID 字符串，调用 `postKnowledgeFilesByIdReingest({ path: { id } })`。成功时 MUST 触发 `['knowledgeFiles']` 缓存失效并展示 `'重建任务已提交，摄取中...'`；失败时展示错误提示。
- **Scenario: 重建任务提交成功**:
  - **GIVEN** SDK `postKnowledgeFilesByIdReingest` 返回重建摄取结果
  - **WHEN** 调用 `mutateAsync('doc-uuid-456')`
  - **THEN** 触发 `['knowledgeFiles']` 缓存失效，`message.success('重建任务已提交，摄取中...')` 被调用。

### 2.6 检索测试 (`useRetrievalTest`)
- **Requirement**: `useRetrievalTest` SHALL 提供检索 dry-run mutation，接收 `KnowledgeAdminRetrievalRequest`，调用 `postKnowledgeRetrievalTest`。请求参数中 `topK` 缺省回落为 5，`useVector` 缺省回落为 false。调用失败时展示错误提示。
- **Scenario: 稀疏检索默认调用**:
  - **GIVEN** 仅传入 `{ query: '产品报价' }`
  - **WHEN** 调用 `mutateAsync`
  - **THEN** SDK 调用参数 body 包含 `{ query: '产品报价', topK: 5, useVector: false }`，成功返回匹配候选。
- **Scenario: 真向量检索显式参数传递**:
  - **GIVEN** 传入 `{ kbId: 1, query: '退款政策', topK: 8, useVector: true }`
  - **WHEN** 调用 `mutateAsync`
  - **THEN** SDK 调用参数 body 精准透传全部字段。
- **Scenario: 检索接口异常拦截**:
  - **GIVEN** SDK 抛出错误
  - **WHEN** 调用 `mutateAsync` 失败
  - **THEN** `message.error('检索测试失败')` 被调用。

---

## 3. Quality & Constraints

1. **测试隔离性**：
   - 必须声明 `// @vitest-environment jsdom`；
   - 必须通过 `vi.hoisted` 隔离 SDK 方法，禁止产生外部网络请求；
   - 运行测试套件耗时必须在秒级（单个测试套件执行耗时 < 5s）。
2. **零副作用**：
   - 禁止修改 `frontend/src/hooks/useKnowledge.ts` 或既有页面组件；
   - 禁止引入新的 npm 包依赖；
   - 不修改 `frontend/typed-router.d.ts`。
3. **门禁指标**：
   - Vitest: 17 passed files, >= 171 tests passed;
   - ESLint: 0 errors, 0 warnings;
   - TypeScript: 0 typecheck errors;
   - 换行符: 严格保持 LF。
