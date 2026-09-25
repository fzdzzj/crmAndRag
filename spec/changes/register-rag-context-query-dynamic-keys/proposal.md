# 提案：扩命名空间并补登记 rag.context / rag.chunking / rag.query 动态配置键

> 变更 ID：`register-rag-context-query-dynamic-keys`｜能力域：dynamic-config｜方案日期：2026-09-25｜基线：权威 worktree `D:\code\crmAndRag-merge-add-knowledge-admin-api` 的 `master`（现场 `git rev-parse --short HEAD`，写作时点为 `fc7d16d`）。**全程 ¥0。不翻代码默认值，不打开 HyDE / 多查询 / 衍生问题 / LLM 压缩，不授权 reingest、真实模型、生产开关或 push。**

## Why

上一案 `register-rag-retrieval-dynamic-keys`（合入 `1e75a3d`）只登记了已落在 `rag.retrieval` 白名单内的 11 键，并明文把 `rag.context.*` / `rag.chunking.*` / `rag.query.*` 留给另立项。这些键在 `docs/dynamic-config-keys.md` 已有语义和默认值，消费点也已 `get(key, type, default)` 读取，但 `DynamicConfigKeyRegistry.NAMESPACES` 只有 `ai.prompt` / `ai.model` / `rag.retrieval` / `rag.intent` / `business`。`register()` 对非法命名空间直接抛错，因此这 13 个键今天连登记都不可能。

这不是运行期缺陷：未注册键回落代码默认，检索和摄取仍可用。缺口是**文档声称可运营，管理端看不见、写不进去**。

**当前状态**：13 键维持内联默认；管理端 `requireDefinition` 视为未知键。

**期望状态**：白名单加上三个命名空间；13 键按现有 `def(...)` 登记，默认值与代码读取点逐一对齐。缺省运行路径不变。超管可通过既有动态配置入口改这些键；打开费用开关仍不是本案授权。

## What Changes

1. `DynamicConfigKeyRegistry.NAMESPACES` 增加 `rag.context` / `rag.chunking` / `rag.query`。同步改 javadoc 与 `ConfigKeyDefinition` 上的命名空间列举，以及 `DynamicConfigKeyRegistryTest.namespacesComplete`。
2. 按现有 `def(...)` 登记下列 13 键。默认值以代码读取点为准；若文档不一致，改文档不改运行默认。`selfCheck` 要求数值上下界成对，表中上限是宽松写入护栏，不收紧消费点语义（消费点自身仍按现有 `<1` 回落 / `Math.min` 钳位）。

| 键 | 类型 | 默认 | 校验 | 代码读取点 |
|---|---|---|---|---|
| `rag.context.neighbors` | Integer | 1 | 0~1 | `ContextBuilder.java` `DEFAULT_NEIGHBORS`；`>=1` 为开，`0` 或负值为关 |
| `rag.context.token-budget` | Integer | 4096 | 1~100000 | `ContextBuilder.DEFAULT_TOKEN_BUDGET` |
| `rag.context.compressor.mode` | String | `rule` | allowed `{rule, llm}` | `ContextBuilder.activeCompressor` 默认 `rule` |
| `rag.context.compressor.llm.timeout-ms` | Long | 3000 | 1~60000 | `LlmContextCompressor.DEFAULT_TIMEOUT_MS` |
| `rag.context.parent-expand` | String | `on` | allowed `{on, off}` | `ContextBuilder` 默认 `on`；显式 `off` 回退邻居模式 |
| `rag.chunking.strategy` | String | `fixed` | allowed `{fixed, semantic, paragraph}` | `DocumentService.STRATEGY_FIXED` |
| `rag.chunking.max-chunk-size` | Integer | 480 | 1~10000 | `DocumentService.DEFAULT_MAX_CHUNK_SIZE` |
| `rag.query.multi-query.enabled` | Boolean | false | — | `MultiQueryRewriteService` 默认 false |
| `rag.query.multi-query.variants` | Integer | 3 | 1~5 | `DEFAULT_VARIANTS=3`，消费点钳位 1~5 |
| `rag.query.hyde.enabled` | Boolean | false | — | `HydeQueryExpander` 默认 false |
| `rag.query.hyde.timeout-ms` | Long | 3000 | 1~60000 | `HydeQueryExpander.DEFAULT_TIMEOUT_MS` |
| `rag.query.derived-questions.enabled` | Boolean | false | — | `DerivedQuestionService` 默认 false |
| `rag.query.derived-questions.max-per-chunk` | Integer | 2 | 1~5 | `DEFAULT_MAX_PER_CHUNK=2`，消费点钳位 1~5 |

3. 替换上一案留下的 `nonWhitelistedNamespacesStayUnknown`：这些键必须变为已知；不得再断言「白名单仍是五个」。
4. `docs/dynamic-config-keys.md` 标明这 13 键现已注册；删掉或改写「仍不在命名空间白名单」的注记。
5. 前端平台配置页只改展示顺序和中文标签：`frontend/src/hooks/usePlatformConfig.ts` 的 `NAMESPACE_ORDER` / `NAMESPACE_LABELS`。列表数据仍来自后端 `byNamespace`，不改页面交互、不改 OpenAPI。按 `frontend/AGENTS.md` 给 `frontend/docs/test-checkpoints.md` 补一条分组检查点。配套 Vitest 只锁新命名空间的顺序/标签。

## 不改

- 不改检索、摄取、压缩、切分、HyDE、多查询、衍生问题的实现与代码内联默认。
- 不把 `compressor.mode` 默认改成 `llm`，不把 HyDE / 多查询 / 衍生问题默认改成 true，不打开 `admin-vector` / `vision-pdf`。
- 不登记 `rag.reingest.*`（运维 runner 环境变量）和 `platform.async.derived-questions.*`（Spring 静态配置）。
- 不改冻结契约、迁移、900 权限、`pom.xml`、依赖锁。
- 不把工作树里未跟踪的热路径三件纳入本案：`docs/request-hotpath-baseline.md`、`spec/changes/add-request-hotpath-baseline/`、`src/test/java/com/slz/crm/quality/RequestHotpathBaselineTest.java`。
- 不碰 `D:\code\crmAndRag` 原工作树。
- 不归档 `add-knowledge-admin-api` / `guard-knowledge-admin-vector-cost` / `add-vision-pdf-ingest-pilot`。本案落地后是否归档，等指导 agent 复核后再说。

## Impact

### 受影响的规范
- `spec/changes/register-rag-context-query-dynamic-keys/specs/dynamic-config/spec-delta.md`

### 受影响的代码
- `src/main/java/com/slz/crm/platform/config/DynamicConfigKeyRegistry.java`
- `src/main/java/com/slz/crm/platform/config/ConfigKeyDefinition.java`（仅 javadoc 列举）
- `src/test/java/com/slz/crm/platform/config/DynamicConfigKeyRegistryTest.java`
- `docs/dynamic-config-keys.md`
- `frontend/src/hooks/usePlatformConfig.ts`
- `frontend/src/hooks/__tests__/usePlatformConfig.test.ts`
- `frontend/docs/test-checkpoints.md`

### 用户影响
- 缺省路径无变化。超管能在既有动态配置页看到并改这 13 键。
- 若超管把 `hyde.enabled` / `multi-query.enabled` / `derived-questions.enabled` 设为 true，或把 `compressor.mode` 设为 `llm`，会产生模型调用费用。本案**不授权**这些写入；描述必须写明费用。切分策略只影响**新摄取/重建**，已有向量不会自动变。

### API 变更
- 无新端点。既有动态配置读写 API 因注册表变大而多返回三个命名空间。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新

## 时间线评估

小。登记 + 单测 + 前端标签。不调用模型。

## 风险

- 登记后误开 HyDE / 多查询 / 衍生问题 / LLM 压缩会增加费用。缓解：默认保持 false / `rule`；描述写明费用；非法值写入期拒绝。
- `chunking.strategy` 改成 `semantic` / `paragraph` 只影响新切片，旧向量口径会分裂。缓解：描述写明须重建才生效；本案不跑 reingest。
- 枚举 allowed 是 trim 后精确匹配（区分大小写），比部分消费点的 `equalsIgnoreCase` 更严。写入 `ON` / `LLM` 会被拒绝。与上一案 `fusion.mode` 口径一致。
- 前端若漏改 `NAMESPACE_ORDER`，新命名空间仍会显示（排在后面、标签为原始键名），不阻断后端。仍应补标签，避免超管页看起来像漏做。
