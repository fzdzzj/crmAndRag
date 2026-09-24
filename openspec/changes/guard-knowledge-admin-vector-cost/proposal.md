# 提案：管理端真向量检索的最小费用入口保护

> 变更 ID：`guard-knowledge-admin-vector-cost`｜能力域：knowledge admin｜方案日期：2026-09-24｜依据：`master` 合并提交 `e2a023d` 上的只读费用链核查。**本提案仅授权设计与后续 ¥0 实现，不授权任何真实模型调用、reingest、生产开启或 push。**

## Why

`POST /knowledge/retrieval/test` 默认 `useVector=false` 走稀疏路；显式 `true` 已接入生产检索，授权 KB 非空时先尝试查询改写 chat，再做 embedding 和按 KB 的向量搜索。`V27__knowledge_admin_permission_seed.sql` 将入口权限 900 授给所有未删除的业务角色（不含特殊角色），且 900 同时覆盖其他六个知识库管理端点。当前端点没有接入 `RequestQuotaService`；请求的 `topK` 只校验正数，显式值不受动态配置目录里 `rag.retrieval.topK` 的 1–100 约束；省略 `kbId` 会扫用户全部可见 KB。

这证明了**当前仓库没有给该付费路径提供可靠的请求频率与工作量边界**，但不证明已部署生产、实际发生费用，或不存在网关/Provider 侧限额。代码里每个请求的可选模型分支有条件上限，不能误写成“单次请求无限调用”。

## 决策与最小范围

**只保护这个端点的 `useVector=true` 分支**，不重构全站费用系统；`useVector=false`、其他 `/knowledge/*` 端点、AI Chat 与既有 900 权限语义保持不变。

1. **服务端默认关闭**：新增 `rag.retrieval.admin-vector.enabled`（`rag.retrieval` 现有命名空间，Boolean，默认 `false`），登记到 `DynamicConfigKeyRegistry` 并更新配置文档。未注入/未注册/读取失败时一律按关闭处理。关闭时显式拒绝 `useVector=true`，不得返回 `usedVector=true` 的空成功，也不得进入查询改写、embedding 或向量库。该开关只能通过既有超管动态配置管理通路修改；**操作开启仍需 owner 单独批准**。
2. **单次工作量边界**：真向量请求必须显式给出已授权 `kbId`（只查询一个 KB），`topK` 为 1–10；省略 `topK` 保留现有默认 5。大于 10 或非正数直接拒绝，不进入模型；10 是针对管理端试跑的保守起点，不修改普通 `/ai` 检索的 topK，也不声称是经真实语料优化得出的阈值。
3. **每实例用户频率边界**：复用现有 `RequestQuotaService` 固定窗口机制，新增仅该端点的用户维度及独立配置 `platform.quota.admin-vector-user-per-minute`（默认 **3**）。只在开关已开、身份与 KB/参数有效时、任何模型调用前占一次额度；超限复用现有 `ErrorCode.RATE_LIMIT_EXCEEDED`。不得复用全站 `USER` 的默认额度导致其他入口竞争或误以为“所有 AI 请求已统一限流”。这是**每 JVM 实例**的保护，不是跨实例全局或金额预算。
4. **界面诚实提示**：现有知识库管理页有真向量复选框与可空 KB 选择。仅在选真向量时提示“须选定 KB、服务端可能关闭且会产生模型调用”；提交前检查 KB 与 topK，后端禁用/限流错误依既有错误展示流程显示。不得把 UI 提示当作服务端闸门。

### 为什么不一次做全链路计量

TokenBudgetService/RequestQuotaService“存在”不等于本入口“已接入”。但把查询改写、多查询、HyDE、LLM 重排、压缩的 usage 全部补齐，或建立跨实例全局美元费用上限，涉及另一条跨模块治理车道。本案不新增依赖、迁移或冻结契约变更，不修改 900 授权矩阵、不全量登记其他未注册检索配置键、不翻现有查询增强默认值。若未来需要**硬金额上限**，须另行评估 Provider/账户侧限额和 usage/价格口径；本案不得宣称实现了它。

## Impact

- **后端**：`KnowledgeAdminService` 的真向量入口；`DynamicConfigKeyRegistry` 新键；`QuotaDimension`、`QuotaProperties`、`RequestQuotaService` 增加独立用户额度；相应单测；`docs/dynamic-config-keys.md` 写清默认关和操作授权。
- **前端**：`frontend/src/pages/(dashboard)/knowledge/index.page.vue` 的提示和真向量条件校验；遵守 `frontend/AGENTS.md` 的类型检查与测试检查点要求。不新增 API/依赖。
- **兼容性**：当前 `useVector=true` 的调用会在开关关闭时得到明确业务错误；启用后必须传单个 `kbId` 且 `topK<=10`。这是有意的管理端行为收紧；调用方不得把拒绝当成“检索零命中”。
- **操作边界**：原 `add-knowledge-admin-api` 任务组 6 仍待 owner 明确授权；实现和 mock 测试不自动满足 6.1/6.2，也不自动开启生产。未授权不得实际调用模型、执行真实 embedding、reingest、基准集或对外推送。

## 反例、失败场景与回退

- 如果该接口始终只在隔离环境由可信管理员人工使用、且 Provider 已设账户支出上限，维持现状可能更省工；但 V27 的默认角色种子并不证明“仅可信管理员可调用”，外部网关与部署状态目前未知。因此以默认关闭作为可逆保险。
- 即使每用户 3 次/分钟，多个用户或多个实例仍可累计费用；Provider 超时/重试也可能继续计费。拒绝把这个保护描述为金额或全局上限。
- 关闭/超限/参数异常必须在任何模型调用前可观测地拒绝；已授权但没有可见 KB 时仍保持不进入模型的既有语义。
- 发布后若发现误伤，只回退管理端开关为 `false` 并停止真向量入口；不自动回滚迁移或改冻结接口。生产开启及真实试点需新的 owner 授权。

## 验收边界

实现 agent 用 mock 证明默认关、无 KB、超 topK、超频均不触发 `KnowledgeRetrievalServiceImpl`/模型/向量库；开关开且合法时沿用现有授权 scope 与检索结果。只跑 ¥0 测试和仓库门禁；需要更新测试基线时只能从真实运行用仓库脚本 `--update`。实现结束只提交可逆代码，**不自动执行付费任务组 6、归档旧案或 push**。
