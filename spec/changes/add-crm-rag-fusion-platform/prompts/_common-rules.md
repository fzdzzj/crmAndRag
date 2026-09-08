# 子 Agent 通用规则（所有 agent 第一步必读）

> 本文件是所有子 agent 的公共约束。**开工前必须完整读本文件**，再读自己的专属提示词 `prompts/agent-*.md`。
> 通用约束以本文件为准；专属提示词只补充该 lane 的范围与验收。

## 0. 项目背景

- 目标仓库：`d:\code\crmAndRag`。**架构重定位（D11）**：以 CRM 为基座的**单体**，AI 助手**吸收 RAG 对话能力**（思考/记忆/图文/接管），知识库能力**移植**为 `com.slz.crm.knowledge`；RAG 独立对话层（`chat_*`/`RagChatPipeline`/匿名）**丢弃**。整合≈只动 AI/知识库模块 + 横切治理，业务模块原样搬入。
- 提案文档（按需阅读，均在 `spec/changes/add-crm-rag-fusion-platform/`）：
  - `proposal.md`（Why/What/Impact）、`design-decisions.md`（D1–D17 已确认决策）
  - `tasks.json`（18 任务/6 lane）、`agent-execution-plan.md`（波次/契约/归属/冲突/worktree）
  - `assistant-decision-tree.md`（助手行为树：请求/记忆/图片/高亮/payload）、`db-table-coordination.md`（表协调 C1–C10）
  - `migration-inventory.md`（迁移清单）、`migration-subtasks.md`（D2/D4 方法级子任务）
  - `specs/*/spec-delta.md`（6 能力域 EARS 规范）
- 源项目（**只读参考**，迁移来源，禁止修改）：
  - CRM：`D:\code\crm\back\crm-back\.worktrees\ai-createid`
  - RAG：`D:\code\rag\back\RAG`
- 技术基线（已锁定，勿再讨论）：Spring Boot **3.5.x** / Java 21 / **MyBatis-Plus（唯一持久化，不用 JPA）** / **Spring AI（spring-ai-alibaba，dashscope 默认，可切 openai 兼容/vllm）** / Qdrant + MinIO / jjwt 0.12.5 / Flyway 版本化迁移。

## 1. 注释规范（强制 · 用户特别要求"适当做注释"）

### 1.1 总则
- **一律中文注释**，风格与 `com.slz.crm` 现有代码一致（类/方法用 Javadoc `/** */`，逻辑用行内 `//`）。
- 注释解释**"为什么 / 约束 / 边界"**，不复述显而易见的"做什么"。
- **迁移既有代码时必须保留原注释语义**（尤其 RAG `ChatConfig`、CRM `DataScopeServiceImpl`、`application.yaml` 这类注释密集处），不得丢失信息。
- **修改已有注释时必须更新为清晰准确的中文，禁止删除或留空**（源项目硬性规则）。
- 改代码必须同步改注释，禁止留下与代码不符的过期注释。

### 1.2 必须注释的位置
1. 每个新增/修改的**类、接口、枚举**：类级 Javadoc 说明职责、协作关系；涉及并发的注明线程安全性。
2. 每个 **public/protected 方法**：Javadoc 含职责、`@param`、`@return`、`@throws`（如抛异常）。
3. **实体字段 / 枚举值 / DTO 字段**：中文注释说明含义、取值范围、单位（如超时是秒还是毫秒）。
4. **配置项（yml/properties）**：每个键加中文注释说明用途、默认值、生产注意（对齐 RAG `application.yaml` 风格）。
5. **复杂/非直觉逻辑**：算法（BM25、部门树递归、图文加权融合）、并发（接管锁、线程池拒绝、Flux 取消/重试）、事务边界、状态机流转、幂等键、降级/兜底分支——**必须行内注释解释原因与边界**。
6. **Flyway 脚本**：脚本头部注释写明目的、影响表、回滚注意；破坏性变更显式标注。
7. **契约接口（`com.slz.crm.platform.contract`）**：详尽 Javadoc（多 lane 依赖它编程）。
8. **临时待办**：`// TODO(上下文): 说明`，禁止无说明占位或空实现。

### 1.3 禁止
- 废话注释（如 `// 设置名称` 对应 `setName()`）。
- 把注释掉的死代码留在提交里（要么删，要么注明保留原因）。
- 中英混杂的半成品注释、机翻腔。
- 复杂逻辑"裸奔"无注释。

### 1.4 密度
- 与周边代码一致：CRM/RAG 均为注释较密风格；关键处宁可充分注释，也不省略。

## 2. 工作纪律（强制）

- **worktree / 分支**：在分配给你的 worktree 目录 + 独立分支工作（见专属提示词）。
- **禁止提交 master**；**禁止 `git push` / `git push -f`**（推送属高风险操作，必须有用户显式指令才可执行）。
- 每一步**保持可编译**；提交前自检编译 + 相关测试。
- **文件归属**：只改专属提示词中"独占可改"的文件；需要动 `pom.xml` / `application.yml` 核心 / 冻结契约 / 其他 lane 的包时，**停下手**，在产出里写"变更申请"，不擅自改。
- 面向 §3 **冻结契约**编程，不擅改契约签名。
- **Flyway**：只在分配号段内加脚本（见 §4）；库结构变更必须在变更摘要写明影响。
- 每阶段完成先输出**可审查变更摘要**（位置 / 内容 / 目的 / 影响 / 验证），等确认再继续。
- **不顺手重构**范围外代码；发现无关问题先记录不改。
- 敏感信息（密钥 / 本地路径 / 测试数据）**不入库**。
- 用户或交接文档的解释视为**待验证假设**，用代码/测试/日志验证后再当事实。

## 3. 冻结契约（只实现/消费，不改签名；详见 agent-execution-plan.md §2）

`UserContext`（身份 userId+deptId+异步快照传播）· `Result`+错误码（RATE_LIMITED/QUOTA_EXCEEDED/UNAUTHORIZED/CONTENT_RISK）· `ModelProvider`（chat/stream/embedding/vision；dashscope 默认；**须返回带 usage 的响应**）· `VectorStore` 抽象（Qdrant+内存回退）· `DataScope` 接口（落点 `DataScopeServiceImpl`+`QueryWrapperAspect`，非 MyDataPermissionHandler）· **助手请求契约**（`sessionId/message/useKnowledgeBase/thinking/imageRef`）· **SSE 事件契约**（`start/meta/sources/thinking/delta/references/done/cancelled/stopped/error` + `ping` 心跳）· **来源引用契约** `SourceReference`（含 `chunkIndex/pageNo/chunkId`，档 B 高亮）· Token 计量 `record(...)`（含 summary/intent）· `DynamicConfigService.get(...)`。

## 4. Flyway 号段（防撞号；详见 agent-execution-plan.md §5）

`V1__*` 基座 · `V2x__*` A 数据权限 · `V3x__*` B 知识库 · `V4x__*` C 助手 · `V5x__*` D 治理 · `V6x__*` E 动态配置。号段内递增；跨 lane 依赖的表由被依赖方建。

## 5. 出口与产出（通用）

- **出口**：专属提示词的验收测试全绿 + 可编译 + 相关基线测试重建。★基线要拆：CRM 原 306 全量重建；RAG 原 491 = **知识库能力测试子集**（Lane B 重建）+ **对话层测试**（随 `chat_*`/`RagChatPipeline` 丢弃，其能力在 Lane C 以助手测试重建）——**不是** 491 原样重建。
- **产出**：变更摘要 + 测试结果 + 依赖/契约变更申请（如有）+ 本 lane 出口验收证据。
