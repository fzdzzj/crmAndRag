# Tasks — test-frontend-use-knowledge

> 执行契约见 `openspec/git-workflow.md`。执行基线：`master@b0b24a2`。
> 约束：受控写集严格限定在 `frontend/src/hooks/__tests__/useKnowledge.test.ts` 与提案/任务卡文件；禁止碰触业务代码；零新增依赖；测试文件与规范全量纯 LF。

---

## 0. 执行记录（执行 agent 填写）
- 特性分支：`feature/test-frontend-use-knowledge`
- 测试文件：`frontend/src/hooks/__tests__/useKnowledge.test.ts`
- 新增用例预期：>= 6 用例
- 最终汇报项：新增用例数、Vitest 全量总数（165 -> 171+）、`pnpm lint:check` 0 errors 0 warnings 结果、`pnpm type-check:check` 结果、`pnpm build` 结果。
- 实测结果（2026-10-01，D:\code\crmAndRag-merge-add-knowledge-admin-api）：
  - 新增 `useKnowledge.test.ts` 共 14 个用例（1 个测试文件），全仓前端套件由 16 files / 165 tests 增至 **17 files / 179 tests passed**；
  - `pnpm test`：Test Files 17 passed (17)、Tests 179 passed (179)、0 failed；
  - `pnpm lint:check`：退出码 0，0 errors / 0 warnings；
  - `pnpm type-check:check`：`vue-tsc --noEmit`（tsconfig.app.json + tsconfig.config.json）退出码 0；
  - `pnpm build`：`✓ built in 25.21s`（含 `Measure-Command` 总耗时 29.84s），退出码 0，`frontend/dist/index.html` 已产出；
  - 换行符：`check-line-endings lf` 对两文件均 `LINE_ENDINGS_OK`（无 CR、无 BOM，UTF-8）；
  - `check-dirty`：`STATUS: CLEAN`；受控写集校验仅含上述 2 个文件；
  - 未执行 `git push`（铁律，等待 owner 授权）。
  - 补勾依据（2026-10-01）改用「门禁命令实测输出」替代逐行哈希留痕：本任务卡非归档批逐格核验场景，证据集中在上一级「实测结果」块。

---

## 1. 测试脚手架与 Mock 准备
- [x] 1.1 创建 `frontend/src/hooks/__tests__/useKnowledge.test.ts`，配置 `// @vitest-environment jsdom`
- [x] 1.2 使用 `vi.hoisted` 与 `vi.mock` 构造 SDK 隔离层（mock `@/api/axios/sdk.gen.ts` 的 6 个 API 方法：`getKnowledgeBases`, `getKnowledgeFiles`, `postKnowledgeFiles`, `deleteKnowledgeFilesById`, `postKnowledgeFilesByIdReingest`, `postKnowledgeRetrievalTest`）
- [x] 1.3 Mock `@/api/apiClient.ts` 与 `ant-design-vue` 的 `message.success` / `message.error` / `message.warning`
- [x] 1.4 实现通用的 `withSetup` 测试辅助函数，正确挂载 `QueryClient` 与 `VueQueryPlugin`

---

## 2. 查询类 Hooks 测试覆盖（Queries）
- [x] 2.1 编写 `useKnowledgeBases` 用例：断言正确调用 `getKnowledgeBases` 并返回映射后的数组
- [x] 2.2 编写 `useKnowledgeFiles` 用例：
  - 测试不传 `kbId` 时的全量查询行为
  - 测试传入响应式 `ref(kbId)` 时的参数传递，以及 `kbId` 响应式变更时的重新发起查询行为

---

## 3. 变更类 Hooks 测试覆盖（Mutations & Invalidation）
- [x] 3.1 编写 `useKnowledgeUpload` 用例：
  - 测试正常文件与知识库 ID 上传成功，断言触发 `knowledgeFiles` 查询缓存失效并弹出成功 Toast
  - 测试上传异常时正确拦截并弹出错误 Toast
- [x] 3.2 编写 `useKnowledgeFileRemove` 用例：
  - 测试根据文档 ID 删除成功，断言触发缓存失效并弹出成功 Toast
  - 测试删除失败时的异常提示
- [x] 3.3 编写 `useKnowledgeReingest` 用例：
  - 测试触发文档重建成功，断言触发缓存失效并弹出成功 Toast
  - 测试重建失败时的异常提示

---

## 4. 检索 Dry-Run 测试 Hook 覆盖
- [x] 4.1 编写 `useRetrievalTest` 用例：
  - 测试稀疏检索参数（`useVector=false`）的调用与返回候选映射
  - 测试真向量检索参数（`useVector=true`, 指定 `kbId` 与 `topK`）的调用与返回
  - 测试接口异常时的错误 Toast 拦截

---

## 5. 前端全量质量门禁与验证
- [x] 5.1 运行 `pnpm test`（Vitest），确证新增测试用例 100% 通过且既有 165 个测试用例无破坏
- [x] 5.2 运行 `pnpm lint:check`，确证新增代码保持 0 errors, 0 warnings
- [x] 5.3 运行 `pnpm type-check:check`，确证 TypeScript 严格类型检查 0 错误
- [x] 5.4 运行 `pnpm build`，确证打包构建顺利通过
- [x] 5.5 运行换行检查与写集校验，确证全量纯 LF 且工作树 clean
