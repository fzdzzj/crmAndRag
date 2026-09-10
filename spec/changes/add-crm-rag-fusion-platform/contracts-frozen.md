# 冻结契约登记册（contracts-frozen.md）

> **唯一权威来源**。契约代码在 `com.slz.crm.platform.contract`（base=`e3f8230` 冻结 17 个，已核验）。本册登记全部契约 + 后续修正 + **谁实现/谁消费** + SSE payload 字段级 + 断线续传 + 变更日志。
> 改契约流程：变更申请 → 架构/integrator 批准 → **更新本册 + 契约代码 + 广播相关 lane**。`agent-execution-plan.md §2` 与 `_common-rules.md §3` 仅为摘要，**冲突以本册 + 契约代码为准**。

---

## 0. 契约包现状（base e3f8230 冻结 17 个 + 修正轮 +3 = 20 个）
`AssistantChatRequest` `BypassTaskExecutor` `CrmVectorStore` `CrmVectorStoreHealth` `DataScope` `DynamicConfigService` `ModelCallResult` `ModelProvider` `PlatformErrorCode` `SourceReference` `SseEventName` `TokenUsageRecord` `TokenUsageRecorder` `TokenUsageType` `UserContext` `UserContextHolder` `VectorRecord` `VectorSearchHit` `VectorSearchRequest` `ModelProviderImpl`（归 base/platform，非 contract 包）

**已核验为很好、无需改的**：
- `UserContext(Long userId, Long roleId, Long deptId, DataScopeLevel dataScope, String displayName)` + `userIdRef()="user:<id>"` + `isSuperAdmin()` → **C1/C6 已解决**：CRM 域内引用用 `userId`(Long/BIGINT)，跨域（知识库表）用 `userIdRef()`(String100)。
- `SourceReference(...chunkId, chunkIndex, pageNo, rowIndex, excerpt, relevanceScore)` → **D15 档 B 锚点已在契约**，B 只需按页分块填 `pageNo`。
- `AssistantChatRequest(String sessionId, ...)` → **C1 确认**：sessionId 为 String（可空、后端生成经 `start` 回传），内部解析为 Long(ai_session.id)，非数字明确报错（String 上送避免 JS 大整数精度丢失）。

---

## 1. 模型 ModelProvider（✅ impl 已落地；🔴 修正轮2 待补 ModelCallOptions）
- 接口已冻；`ModelProviderImpl`（`com.slz.crm.platform.model`，`@Component`）**已由 base 实现并验证**（chat→DashScope 原生 / streamChat→恒 compatible-mode SSE + streamUsage(true) / embed→DashScope 原生 / vision→强制 qwen-vl compatible-mode；真机 IT usage 正常；Bean 发现测试 `905f11d`；308 测试绿）。
- **🔴 修正轮2（解 C 报的 thinking 丢失 bug，base 待做）**：`ModelProviderImpl.withStreamUsage/withModel` 的 **else 分支对非 `OpenAiChatOptions` 入参只保留 model、丢弃其余**（thinking/temperature/自定义参数），且 compatible-mode 的 `enable_thinking` 无承载位 → C 的 DashScope thinking 参数经 Provider 失效；违背“provider 差异封在实现里、业务不传 provider 专有 options”初衷。**需补**：
  1. 契约包新增中立载体 **`ModelCallOptions`(record)**：`{ String model; boolean thinking; Double temperature; Integer maxTokens; Map<String,Object> extra }`。
  2. `ModelProvider` 加重载：`streamChat(Prompt, ModelCallOptions)`、`chat(Prompt, ModelCallOptions)`、`vision(Prompt, ModelCallOptions)`（保留无 options 版走默认）。
  3. `ModelProviderImpl` 把 `ModelCallOptions` 翻译到当前 provider：DashScope compatible-mode `thinking=true`→`enable_thinking`（B1：`chat_template_kwargs.enable_thinking` + 顶层 `enable_thinking` 双写）；model/temperature/maxTokens 映射进 `OpenAiChatOptions`；**修 `withStreamUsage/withModel` 改为在既有 options 上叠加、不再 else 全新建（不丢调用方参数）**。
  4. 测试：`thinking=true` 经 streamChat 请求体确带 `enable_thinking`（MockWebServer 断言）；非 OpenAi 入参不被丢。
- 消费：C（chat/stream/vision，**改用 `ModelCallOptions`、勿传 provider 专有 options**）、B（embedding/vision/vector）。实现就绪前用 `ObjectProvider` 可选依赖 + 测试桩，**不自建实现**。

## 2. 向量库 VectorStore + 健康（base 加接口 / B 实现 / D 消费）
- `CrmVectorStore`(upsert/search/delete) 已冻，**不动**。
- **新增 `CrmVectorStoreHealth`（additive，base 加接口）**：`String componentName(); boolean inMemoryFallback(); String collectionName(); boolean probe();`
  - 实现 = **B**（`QdrantVectorStore`/`InMemoryVectorStore` 各自实现；Qdrant 探测走现有 gRPC 6334 原生客户端，**不加 http-port**）。
  - 消费 = **D**（health indicator：`inMemoryFallback`→WARN 降级；否则 `probe()?UP:DOWN(readiness)`）。
- **Qdrant metadata 类型约定（B4，B 入库遵守）**：ID/类目=`String`(keyword match)、布尔状态=`Long 0/1`(integer match)、**禁 `Boolean/UUID` 值过滤**。约束 D17 意图类目过滤（category=String）。

## 3. 数据权限 DataScope（A 实现 / C 消费）
- 方法：`getHighestDataScopeLevel(userId, deptId, roleId, resourceType)`、`getSubordinateUserIds(deptId)`（部门子树）、`addDataScopeCondition(wrapper, ...)`（两个重载）。落点 `DataScopeServiceImpl`+`QueryWrapperAspect`（**非 MyDataPermissionHandler，不存在**）。
- 优先级：`ALL > DEPT_AND_CHILD > DEPT > TAGE > SELF`。
- **超集不变量（A3）**：解析 DEPT/DEPT_AND_CHILD 时 **UNION 进显式 `data_share` 共享 + `tage` 绑定**资源 → 新增权限**绝不收窄**既有可见范围。两个 `switch` 重载都要加 case（否则 default→SELF 静默降级）。

## 4. SSE 事件契约（名 + 顺序 + payload 字段级冻结）
**顺序**：`start → meta → sources? → thinking* → delta* → references? → title? → done | cancelled | stopped | error`；`ping` 为注释帧穿插。
**事件名修正**：`SseEventName` **+= `TITLE("title")`**（base 加；Agent-0 统一时漏了 CRM 既有的异步标题事件，C7）。

| 事件 | payload（字段级冻结） |
|---|---|
| `start` | `{ sessionId, assistantMessageId, generationId }` |
| `meta` | `{ provider, model, useKnowledgeBase, thinking }` |
| `sources` | `[SourceReference]`（答案前先发） |
| `thinking` | `{ text, finished }`（B2；仅 thinking=true） |
| `delta` | `{ content }` |
| `references` | `{ citations:[n], items:[{type,id,name}] }`（业务实体 + 实际引用编号） |
| `title` | `{ text }`（异步会话标题，C7 新增） |
| `done` | `{ sessionId, cancelled:false, usage:{input,output,total} }` |
| `cancelled` | `{ reason }`（客户端主动取消） |
| `stopped` | `{ reason }`（被同会话新请求接管） |
| `error` | `{ code, msg, retryAfterSeconds? }`（脱敏，不透堆栈/SQL） |
| `ping` | 注释帧 `:ping`，**无 id、不缓冲、不重放**，前端忽略 |

## 5. 断线续传 resume（★新增，C 实现）
- **事件 id**：每个**业务事件**带 SSE `id:` = 每 generation 内单调递增 seq（建议 `<generationId>:<seq>`）；`generationId` 由 `start` 事件下发。
- **服务端有界缓冲**：按 generation 缓冲已发事件（名+id+data，上限如 ≤1000 条 / ≤256KB），存于 `AiStreamRegistry` 的 generation 状态；**终态事件（done/cancelled/stopped/error）+ 最终/部分答案必留**。
- **重连入口**：客户端带 SSE 标准头 `Last-Event-ID`，或 `POST /ai/chat/stream` body 带 `resume:{generationId,lastEventId}`：
  - generation 仍在跑 → 重放 `id > lastEventId` 的缓冲事件 → 接续实时流；
  - 已完成/取消/停止 → 重放剩余（含终态 + 答案）后关闭；
  - generation 已消失（缓冲淘汰 / 服务重启 / 换实例）→ `error code=RESUME_UNAVAILABLE` + 回传已持久化 `ai_message`（部分或最终）供前端渲染。
- **硬不变量：重放 ≠ 重执行**——只重放缓冲输出，**绝不**重跑 LLM/检索、**不**重复计 token、**不**重复存 ai_message。
- **多实例**：内存缓冲 → 续传仅"重连命中同实例 + 缓冲仍在"时有效；部署用 **sticky session（按 sessionId 路由）** 兜底；跨实例/重启降级 `RESUME_UNAVAILABLE` + 持久化答案。**真·持久续传（每事件落库）本期不做**（过重），登记为后续可选增强。
- `PlatformErrorCode` **+= `RESUME_UNAVAILABLE`**（base 加）。

## 6. Token 计量（B/C 产数 / D 聚合）
- `TokenUsageRecorder.record(TokenUsageRecord)`；`TokenUsageRecord` **9 字段不改签名**（D 只实现 recorder + 预算/聚合）。
- `TokenUsageType` **MUST 含 `SUMMARY`/`INTENT`**（补 RAG 意图/摘要漏计盲点）+ chat/embedding/ocr/vision。
- C 的意图/摘要 LLM 调用（吸收自 RAG 记忆）**MUST 经 recorder 上报 type=SUMMARY/INTENT**。

## 7. 记忆旁路执行器 BypassTaskExecutor（★新增，base 加接口 / D 实现 / C 消费）
- 接口：`boolean tryExecute(Runnable task)`——内部 `MdcTaskDecorator.wrap` 传播 traceId + UserContext；**返回 false = 队列满被拒 → 调用方降级跳过**（C 的意图/摘要据此不阻塞主答）。
- bean 名：`memoryBypassExecutor`（pool=2 / 有界队列=64 / AbortPolicy）。
- **单飞去重由调用方（C）按会话 CAS**（沿用 RAG 语义）；D 只提供执行器 + 拒绝语义，不做业务单飞。

## 8. 知识库检索端口 KnowledgeRetrievalPort（lane-local，非冻结契约）
- **C 定义**在 `server.ai.port`，**B 提供实现**，integrator 接线。**不进契约包**（是 C↔B 集成缝）。
- 形状：`RetrievalResult retrieve(RetrievalQuery q)`；
  - `RetrievalQuery{ String query(改写后); Long userId; List<String> kbScope?; int topK; float[] imageVector?; String intentCategory? }`
  - `RetrievalResult{ String context; List<SourceReference> sources; int hitCount }`
- **MUST 用冻结的 `SourceReference`**（含 pageNo/chunkIndex），别自造来源类型。C 未合入 B 前用 mock 实现。

## 9. imageRef 语义澄清（C4，base 更新 Javadoc）
- `AssistantChatRequest.imageRef` = **助手会话内聊天图片的引用**（C 域：聊天图片存储键 / 已持久化 L1 理解的 hash），**不是** CRM 业务附件、**不是** B 的 `uploaded_file`。
- C 需自建聊天图片存储 + 按 hash 持久化 L1 理解（对齐 D13"imageRef 指向的图/理解需可恢复"）。引用 KB 文档图 / CRM 附件图是后续功能，本期不做。

## 10. DynamicConfigService（E 实现 / B/C/D 消费）
- `get(key, type, default)` + 命名空间 `ai.prompt.*`/`ai.model.*`/`rag.retrieval.*`/`rag.intent.*`/`business.*`；含意图类目/`strict-KB`/图片缓存上限/限流配额阈值。仅超管可写、热生效、校验护栏、版本回滚、审计。

## 11. PlatformErrorCode（全集，base 核对+补）
`UNAUTHORIZED`(现网 96003=请先登录) · `FORBIDDEN` · `RATE_LIMITED` · `QUOTA_EXCEEDED` · `TOKEN_BUDGET_EXCEEDED` · `CONTENT_RISK` · `VALIDATION` · `DEPENDENCY_UNAVAILABLE` · **`RESUME_UNAVAILABLE`(新)** · `INTERNAL`。

---

## 12. 谁实现 / 谁消费（归属矩阵）
| 契约 | 实现方 | 消费方 | 状态 |
|---|---|---|---|
| UserContext(+Holder) | base | A/B/C/D/E | ✅ 已冻 |
| ModelProvider / ModelCallResult / ModelProviderImpl | base | C/B | ✅ impl 已落地；🔴 修正轮2 加 `ModelCallOptions` |
| CrmVectorStore | B | B/C | ✅ 接口冻 |
| **CrmVectorStoreHealth** | **B** | D | 🆕 base 加接口 |
| DataScope | A | C | ✅ 接口冻（补超集不变量） |
| SseEventName(+TITLE) / payload | C（发送）| 前端/B/D | 🆕 +TITLE、payload 字段级冻结 |
| **断线续传（事件 id/缓冲/重连）** | **C** | 前端 | 🆕 |
| SourceReference | B（填充 pageNo）| C（下发）| ✅ 已冻 |
| TokenUsageRecorder/Record/Type(+SUMMARY/INTENT) | D | B/C | ✅ 接口冻（type 补枚举） |
| **BypassTaskExecutor** | **D** | C | 🆕 base 加接口 |
| **KnowledgeRetrievalPort** | **B** | C | 🆕 C-local，非契约包 |
| DynamicConfigService | E | B/C/D | ✅ 接口冻 |
| PlatformErrorCode(+RESUME_UNAVAILABLE) | base | 全部 | 🆕 补码 |

## 13. base 契约修正轮（重开 Agent-0 或 integrator 批量做，避免多 lane 并发改契约包）
### 修正轮 1（✅ 已落地，commit `9f6658e`/`905f11d`）
1. `SseEventName` += `TITLE` ✓　2. `PlatformErrorCode` += `RESUME_UNAVAILABLE` ✓　3. `CrmVectorStoreHealth` ✓　4. `BypassTaskExecutor` ✓　5. `ModelProviderImpl` ✓　6. `imageRef` Javadoc ✓
### 修正轮 2（🔴 待做，解 C 的 thinking 丢失）
7. 新增 `ModelCallOptions`(record){model,thinking,temperature,maxTokens,extra}。
8. `ModelProvider` 加 `streamChat/chat/vision(Prompt, ModelCallOptions)` 重载。
9. `ModelProviderImpl` 翻译 `ModelCallOptions`→provider（DashScope compatible-mode `enable_thinking` 双写）；修 `withStreamUsage/withModel` 不再丢非 OpenAi options。
10. 测试：thinking 经 streamChat 确带 `enable_thinking`；非 OpenAi 入参不丢。

---

## 变更日志
- **2026-09-09 · base e3f8230**：初始 17 契约冻结（Agent-0）。
- **2026-09-09 · 修正轮（架构批准）**：+`TITLE` 事件；SSE payload 字段级冻结；+断线续传（事件 id/有界缓冲/Last-Event-ID/重放≠重执行/sticky 兜底/RESUME_UNAVAILABLE）；+`CrmVectorStoreHealth`(B实现/D消费)；+`BypassTaskExecutor`(D实现/C消费)；+`KnowledgeRetrievalPort`(C定义/B实现,lane-local)；`ModelProviderImpl` 归 base（C2）；`imageRef` 语义澄清（=C聊天图域）；`TokenUsageType` 含 SUMMARY/INTENT；Qdrant metadata 类型约定（B4）；DataScope 超集不变量（A3）；确认 C1(sessionId String)/C6(user_id BIGINT，userIdRef 跨域)/D15(锚点已在契约)。
- **2026-09-09 · 修正轮落地（Agent-0 重开）**：20 契约全部已实现；`ModelProviderImpl` chat 走 DashScope 原生 / streamChat 恒走 compatible-mode SSE + streamUsage(true) / embed 走 DashScope 原生 / vision 强制 qwen-vl 走 compatible-mode；真机 IT chat/stream/embed 均验证 usage 正常；`PlatformErrorCode` 10 项全集（§11）；`AssistantChatRequest.imageRef` Javadoc 更新。
- **2026-09-10 · 修正轮2（架构批准，base 待实现）**：C 核实 `ModelProviderImpl.withStreamUsage/withModel` else 分支丢弃非 `OpenAiChatOptions`（thinking/temperature）+ compatible-mode `enable_thinking` 无承载位 → 新增中立 `ModelCallOptions` + `streamChat/chat/vision` 重载 + 翻译到 DashScope `enable_thinking` + 修 `with*` 不丢参；C 改用 `ModelCallOptions`、勿传 provider 专有 options。

