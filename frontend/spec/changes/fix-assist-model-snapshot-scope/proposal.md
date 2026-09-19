# 协助按来源模型展示快照与附件的前端对接提案

## Why

后端将把协助终态快照改为按 `modelName` 分范围构建：审批推进协助仍展示整条商机全部活动，业务活动协助只展示当前活动，联络任务协助只展示当前任务及明确关联活动。前端如果继续把所有快照都当成“商机全部活动”，会造成页面展示越权或把无关活动误展示给协助人。

同时，协助交付物不再写入 `recordSnapshot.deliveryAttachments`，而是按 `assistId` 实时查询；终态申请人和协助人仍可读取交付物。前端必须区分“历史业务快照附件”和“协助交付物附件”，不能缓存或复用错误的下载地址。

当前前端已经使用 `@hey-api/openapi-ts` 生成 API 客户端，但 `openapi.yaml` 仍需要人工替换，后端 `OpenApiConfig` 的 multipart 修正也存在手写路径表。需要把契约同步和生成校验纳入脚本/CI，减少前后端接口漂移。

## What Changes

1. 协助详情按 `modelName` 渲染快照：
   - `SALES_STAGE_APPROVAL`：展示整条商机、全部业务活动、活动附件和审批附件；
   - `BUSINESS_ACTIVITY`：仅展示被协助活动及其附件，并展示关联商机、公司、主要联系人摘要；
   - `CONTACT_TASK`：展示被协助任务、明确关联活动及其附件，并展示关联商机、公司、主要联系人摘要。
2. `hasAssistant` 或等价协助字段只绑定当前 `modelName + recordId`，不因同一商机的其他活动/任务存在协助而显示当前记录有协助。
3. 终态详情只读 `recordSnapshot`，不请求实时来源业务来覆盖快照；快照缺失时显示明确提示，不自动拼接商机全部活动。
4. 交付物改为调用协助交付物接口按 `assistId` 实时查询。申请人和协助人即使在终态也显示查看/下载入口；上传、删除按钮继续根据后端返回的写权限决定。
5. 前端 API 契约以后端 `/v3/api-docs.yaml` 为唯一来源，自动同步 `openapi.yaml` 并执行 `pnpm gen:api`；生成目录禁止手工编辑。
6. 增加 CI 契约漂移检查：重新导出并生成后，若生成文件存在未提交差异则任务失败。

## API 配置自动化方案

推荐采用“后端导出、前端生成、CI 校验”的三段式流程：

```text
Spring Boot /v3/api-docs.yaml
        ↓
pnpm sync:api（下载并规范化 openapi.yaml）
        ↓
pnpm gen:api（生成 TypeScript 类型和 SDK）
        ↓
git diff --exit-code src/api/axios openapi.yaml
```

后端 `OpenApiConfig` 不建议一次性全部删除。普通 JSON 接口应依赖 Springdoc 自动推导；文件上传接口优先通过 `@RequestPart`、`@Parameter`、`@Schema` 标注 DTO。只有 Springdoc 无法正确表达的动态 multipart 数组保留少量集中式修正。这样可以把当前按路径硬编码的字段表逐步压缩，而不是继续在前后端各写一份。

## Non-goals

- 不改变协助授权规则；前端只消费后端返回的范围和权限结果。
- 不把终态交付物下载地址永久存入前端状态或数据库。
- 不手工修改生成的 `src/api/axios/**/*.gen.ts`。
- 不在本提案中重做普通商机详情页的全部布局。

## Impact

- 前端：协助列表、协助详情弹窗、审批/活动/联络任务详情中的协助入口和附件区域。
- API：新增或调整终态交付物查询响应的消费方式；下载 URL 仍按短期令牌使用。
- 工具链：新增 `sync:api`、`verify:api` 脚本，并在 CI 中执行契约漂移检查。
- 后端协作：后端需保证 OpenAPI 对 `recordSnapshot`、交付物附件、`hasAssistant` 和 multipart 字段有稳定描述。

