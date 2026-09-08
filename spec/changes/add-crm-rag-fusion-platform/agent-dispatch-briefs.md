# 子 Agent 分派简报（已被 prompts/ 取代）

> ⚠️ **本文件已被 `prompts/` 目录下的独立提示词取代**（后者更完整、含强制注释规范与 worktree/分支）。请改用 `prompts/README.md` 与 `prompts/agent-*.md`；本文件仅留作历史参考，勿据此派发。
> 配套 `agent-execution-plan.md`。每个简报自包含，复制对应整段作为该子 agent 的初始指令即可。
> **分派顺序**：先 `Agent-0 基座`（串行，必须最先完成并广播契约）→ 再并行 `A / B-spike / C(任务11) / D(骨架)` → B-spike 出结论后 `B-persist → B-ai` → 收敛 `E` → 最后 `integrator`。
> 所有 agent 通用铁律已写入每份简报，勿删。

---

## Agent-0 · 基座与契约（Wave 0，串行，最先）

````text
角色：融合平台基座 agent。目标：在空仓 d:\code\crmAndRag 建立可编译可跑的统一基座，并冻结所有 lane 共用的接口契约。
负责：tasks.json 任务 1、2、3、4 + agent-execution-plan.md §2 契约冻结 + Flyway V1 基线。
入口：空仓（master，无提交）。你是第一个 agent，无前置依赖。

要做：
1. Maven 骨架：spring-boot-starter-parent 3.5.x / Java 21；包边界 com.slz.crm.*（业务+助手）、com.slz.crm.knowledge.*（知识库）、com.slz.crm.platform.*（治理+契约）。
2. pom 依赖（唯一真源，你独占）：MyBatis-Plus（唯一持久化，不引 JPA/Hibernate）、Spring AI(spring-ai-alibaba/dashscope)、Qdrant VectorStore、MinIO、jjwt 0.12.5、springdoc、actuator、testcontainers。
3. 并入 CRM 全量业务域 + AI 助手运行时 + 认证权限(JWTInterceptor/PermissionsInterceptor/@RequirePermission/DataScope*) + 通用件(Result/异常/工具/枚举/GlobalExceptionHandler)。
4. 统一配置：合并 application.yml；敏感项环境变量外置；dev/test/prod profile；生产 auto-table.mode=none；接入 ProductionConfigurationGuard。
5. Flyway V1__baseline.sql：CRM 现有表 + RAG 9 张表（RAG 表 DDL 从 Hibernate 生成物固化）。
6. 冻结并广播契约（放 com.slz.crm.platform.contract）：UserContext(身份/异步快照传播)、Result+错误码(含 RATE_LIMITED/QUOTA/UNAUTHORIZED/CONTENT_RISK)、ModelProvider(chat/stream/embedding/vision, provider=dashscope默认/openai/vllm)、VectorStore 抽象、DataScope 接口、SSE 事件契约(message/stopped/references/error/thinking)、Token 计量 record(...)、DynamicConfigService.get(...)。

关键坑：
- Boot 3.5.x（不是 4）；持久化只留 MyBatis-Plus；AI 用 Spring AI 不用 LangChain4j。
- 契约是后续所有 lane 的编程基线，签名要一次想清楚；冻结后改动需你或 integrator 批准。

独占可改：项目骨架、pom.xml、application.yml、com.slz.crm.common.*、com.slz.crm.platform.contract.*、Flyway V1__*。
铁律：只在分配的独立分支工作，禁止提交 master、禁止 git push（推送需用户显式授权）；每步保持可编译；中文 Javadoc 风格；不顺手重构范围外代码；每阶段先输出可审查变更摘要（位置/内容/目的/影响/验证）再继续。
出口：骨架可编译启动 + CRM 回归基线（原 306 测试）在新仓重建通过 + 契约冻结并广播给所有 lane。
产出：变更摘要 + 测试结果 + 契约清单（接口签名）。
````

---

## Agent-A · 组织与上下级数据权限（Wave 1，可最先并行）

````text
角色：数据权限 agent。目标：为 CRM 增加"本部门/本部门及以下（上司查看下属）"数据范围。
负责：tasks.json 任务 5、6。
入口：Agent-0 完成，UserContext 与 DataScope 契约已冻结。

要做：
1. DataScopeLevel 新增 DEPT(本部门)、DEPT_AND_CHILD(本部门及以下)，保留 NONE/SELF/TAGE/ALL 与 fromCode 兼容。
2. SysDeptEntity 新增 leaderId(部门负责人)；基于 parentId 构建部门树。
3. PermissionOperates 为受控表补 _DEPT/_DEPT_AND_SUB 权限项 + 权限种子。
4. DataScopeResolver：按 parentId 递归解析子树部门集合、按 user.deptId 解析下属用户集合；getHighestDataScopeLevel 纳入新级别（负责人默认 DEPT_AND_CHILD，超管 roleId=1 走 ALL）。
5. MyDataPermissionHandler：受控表按归属人(owner_id/creator_id) ∈ 下属集合注入 SQL 过滤。
6. 数据权限贯通 AI 工具查询（面向 DataScope 契约，不改 AI 助手实现）。
7. Flyway V2x__*：sys_dept.leader_id 列 + DEPT/DEPT_AND_SUB 权限种子（默认不授予任何角色）。

关键坑（来自源码盘点）：
- sys_dept 现无 leader_id；parent_id 注释写"支持两级"，你要扩成 N 级递归并做成环防御。
- 现状无种子部门树，DataInitializer 只建一个"默认部门"；测试需自建多级部门+用户。
- 受控表清单见 DataScopeResolverImpl.TABLE_PERMISSIONS；归属人列名按表确认。
- 新增权限默认不授予，避免改变既有可见范围。

独占可改：common.enumeration.DataScopeLevel/PermissionOperates、server.service.impl.DataScopeResolverImpl、MyDataPermissionHandler、pojo.entity.SysDeptEntity、Flyway V2x__*。
禁改（需申请）：pom.xml、application.yml 核心、已冻结契约、其他 lane 的包。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：本人/本部门/本部门及以下/全部 四级可见性 + 负责人跨子部门 + 越权拦截 + 部门树成环防御 测试全绿。
产出：变更摘要 + 测试结果 + 对契约/依赖的变更申请（如有）。
````

---

## Agent-B-spike · Spring AI 可行性验证（Wave 1，B 线前置闸）

````text
角色：技术验证 agent。目标：判定 Spring AI 能否等价承接 RAG 现有 LangChain4j 关键能力，给出 go/no-go 与降级策略。
负责：migration-subtasks.md 的 B0（仅此，不做迁移）。
入口：Agent-0 完成，ModelProvider/VectorStore 契约已冻结。

要验证（用最小样例，不改业务代码）：
1. 思考块流式透传：LangChain4j 现用 .returnThinking(true)+PartialThinking 下发 reasoning_content；Spring AI DashScope 流式是否有等价思考增量？
2. 自定义思考参数：vLLM 的 chat_template_kwargs.enable_thinking、openai/百炼顶层 enable_thinking，能否经 Spring AI ChatOptions 按 provider 下发？
3. Qdrant 过滤语义：Spring AI QdrantVectorStore 的 metadata filter 是否等价 LangChain4j EmbeddingStore filter（影响检索授权一致性）？
4. 向量维度：现状默认值三处不一致（ChatConfig 2056 / QdrantInitializer 2560 / application.yaml 1024），确认实际嵌入模型维度并给出统一值。

独占可改：新建验证样例目录（如 spike/）+ 输出文档；不改 knowledge 业务代码、不改 pom（如需临时依赖走申请）。
铁律：独立分支，禁止提交 master、禁止 git push；不顺手重构。
出口：产出 B0-spike-结论.md（每项 可行/不可行 + 证据 + 若不可行的降级方案，如思考流式不可等价则降级为仅快速模式或自定义解析）。
产出：B0-spike-结论.md，并广播给 Agent-B-ai 与 Agent-C（因思考流式结论会影响 SSE 事件契约的 thinking 字段）。
````

---

## Agent-B-persist · RAG 持久化与授权迁移（Wave 1，B 线，先于 B-ai）

````text
角色：RAG 持久化+授权 agent。目标：把 RAG 从 JPA 迁到 MyBatis-Plus，并把身份/授权缝合到 CRM。
负责：tasks.json 任务 7（重打包+持久化部分）、任务 8（身份缝合/授权/移除匿名）；migration-subtasks.md A0–A7。
入口：Agent-0 完成；UserContext/Result 契约已冻结；B-spike 已给出向量维度统一值。

要做：
1. com.mark.knowledge.* 重打包为 com.slz.crm.knowledge.*；Jackson3(tools.jackson)→Jackson2；starter webmvc→web；控制器返回 CRM Result。
2. 9 个实体 JPA 注解→MyBatis-Plus（@TableName/@TableId(AUTO)/@TableField/@EnumValue）；实体全扁平无关联。
3. 9 个 Repository→Mapper：派生查询→LambdaQueryWrapper；@Query JPQL→XML/@Select；@Modifying(updateStatusIfMatch/markFailed)→@Update 保留状态条件；deleteByX 返回 long→int 适配调用方。
4. 服务层：save()→按 id 分流 insert/updateById；移除脏检查隐式依赖；@Transactional 保留。
5. 身份缝合：RAG 授权入参消费 CRM UserContext 稳定 userId(user:<id>)；停用 RAG 独立登录/SecurityConfig；UserIdentityVerifier/KnowledgeBaseAuthorizationService/ResourceAccessPolicy 保留判定、换身份来源。
6. 移除匿名态：删除 AnonymousRagChatService 及匿名分流；公开知识库=所有已登录用户可见。
7. 检索授权过滤：消除 findAllByDeletedFalseOrderByUploadTimeDesc 全表扫描，改按 documentId 集合/知识库范围查询。
8. Flyway V3x__*：知识库成员 owner/member 从 teacher:<id>/config:<username> 映射到 CRM userId；uploaded_file.knowledge_base 归属回填；chat_conversations/chat_messages 的 username→user_id。

关键坑：KnowledgeBaseMemberEntity.userId 是 String；批量任务状态机(updateStatusIfMatch/markFailed/findAllByStatusInAndUpdatedTimeBefore 恢复扫描)语义必须保持。
独占可改：com.slz.crm.knowledge.** 的 entity/mapper/persistence/授权服务、Flyway V3x__*、docker-compose。
禁改（需申请）：pom.xml（移除 JPA 依赖走申请）、application.yml 核心、契约、CRM 业务包、AI/流式服务（留给 B-ai）。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：持久化+授权单测通过；匿名/成员/负责人/管理员/越权访问矩阵通过；userId 映射正确。合入后通知 B-ai 接续。
产出：变更摘要 + 测试结果 + 依赖变更申请（移除 spring-boot-starter-data-jpa）。
````

---

## Agent-B-ai · RAG 模型/检索/流式迁移（Wave 1，B 线，后于 B-persist）

````text
角色：RAG AI 栈 agent。目标：把 RAG 从 LangChain4j 迁到 Spring AI，接通检索与流式问答。
负责：tasks.json 任务 7（AI 栈+VectorStore 部分）、任务 9（文档/检索/流式冒烟）；migration-subtasks.md B1–B10。
入口：Agent-0 完成；B-spike 结论已出；B-persist 已合入（持久化先、AI 后，避免同文件并发改）。

要做：
1. 按 B-spike 结论重写 ChatConfig 的 5 个 bean 为 Spring AI（chatModel/embeddingModel/streamingChatModel/vectorStore/memorySummaryModel），接入 ModelProvider 契约（dashscope 默认/openai/vllm）。
2. 同步 chat：RagMemoryOrchestrator 摘要/意图 → ChatModel.call(Prompt)，内部恒禁思考。
3. 流式 chat（最重）：RagChatPipeline/RagStreamSessionManager 从 StreamingChatResponseHandler/PartialResponse 改为 Flux<ChatResponse>；wrapWithConnectionRetry→retryWhen；同会话取消/接管对齐 CRM AiStreamRegistry 接管锁与 stopped 事件；统一 SSE 事件契约。
4. 思考模式：按 B-spike 结论透传或降级；ThinkTagStripper(写记忆/历史前剥离)保留；prompt-max-chars 预算闸门保留。
5. Embedding：EmbeddingService/ImageEmbeddingService → Spring AI EmbeddingModel；TextSegment→Document；维度按 spike 统一值。
6. VectorStore：EmbeddingStore/QdrantEmbeddingStore→VectorStore/QdrantVectorStore；实现 InMemoryVectorStore 回退(dev/test)；RagRetrievalService/RagRetrievalAccessFilter 过滤语义迁移；Bm25Scorer 保留。
7. Vision/OCR：ImageUnderstandingService 多模态→Spring AI DashScope Media。
8. TokenUsage→Usage，对接 Token 计量契约。移除 LangChain4j 依赖（走 pom 申请）。

关键坑：Flux 背压 vs 回调 handler 的取消/接管/重试语义差异最大；MockWebServer 思考参数序列化测试需改为 Spring AI 等价断言。
独占可改：com.slz.crm.knowledge.** 的 ai/chat/embedding/vector/retrieval/stream 服务、ChatConfig 重写。
禁改（需申请）：pom.xml、application.yml 核心、契约（若 spike 要求改 SSE thinking 字段，走契约变更申请）、B-persist 已定的 entity/mapper。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：登录态下 上传→解析→嵌入→检索→流式问答(快速/思考两模式) 冒烟通过；RAG 回归基线（原 491 测试：485 通过+6 Docker 集成跳过）重建通过。
产出：变更摘要 + 测试结果 + 依赖/契约变更申请。
````

---

## Agent-C · AI 助手统一与精进（Wave 1：任务 11 先行；任务 10 待 B）

````text
角色：AI 助手 agent。目标：精进 CRM AI 助手并接通知识库检索工具。
负责：tasks.json 任务 11（可先行）、任务 10（依赖 B 检索契约）。
入口：Agent-0 完成；UserContext/ModelProvider/SSE/DataScope/Token 契约已冻结。

要做（任务 11，纯 CRM 内，先行）：
1. POST /ai/chat/stream 落地按用户滑动窗口限流（内存实现），超限返回 error 事件 code=RATE_LIMITED 且不消耗 LLM；确认/取消不限流。
2. 只读工具结果实体汇总为 references，经 SSE references 事件下发。
3. ai_insight 表 + 定时任务（基于统计/待办生成个人周报洞察，同周期幂等）+ GET /ai/insights；Flyway V4x__*。
4. 确定评测执行机制（真实调用/离线回放），接入黄金用例集回归。
5. 补齐确定性层单测（Validator/EntityResolver/PendingAction 状态机/限流器）。
要做（任务 10，待 B-ai 提供检索契约后）：
6. 新增只读工具 queryKnowledgeBase，接入 AiToolRegistry 与描述；面向 B 的检索契约编程，B 未合入前用接口/mock。
7. 工具查询同时受 CRM 数据权限(DataScope 契约)+知识库授权约束。
8. 统一 Token 计量：AiMessage.tokenCount 接入 Token 计量契约。

关键坑：SSE 事件契约与 B 共用，thinking/references 字段不自改（要改走契约申请）；任务 10 强依赖 B，别在 B 未就绪时硬集成。
独占可改：server.ai.**、AiChatServiceImpl、AiToolRegistry、ai_insight 相关、Flyway V4x__*。
禁改（需申请）：pom.xml、application.yml 核心、契约、com.slz.crm.knowledge.**（B 的领地）。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：限流/references/洞察/评测通过；queryKnowledgeBase 越权知识库不可检索、Token 计量落库。
产出：变更摘要 + 测试结果 + 契约/依赖变更申请。
````

---

## Agent-D · 平台治理与硬化（Wave 1：任务 12 骨架先行；13/14 随后）

````text
角色：平台治理 agent。目标：沉淀跨 CRM/AI/知识库共享的治理基础设施。
负责：tasks.json 任务 12（骨架先行，供 B/C 使用）、13、14。
入口：Agent-0 完成；UserContext/SSE/Token/VectorStore 契约已冻结。

要做（任务 12 骨架先行）：
1. RequestTraceFilter 写 traceId；异步/流式/延迟任务经 MdcTaskDecorator.wrap 传播并清理 MDC。
2. 分任务类型线程池（批量上传/聊天记录/流式问答/记忆旁路/嵌入/解析）独立队列/拒绝/超时 + 指标。
3. 修复 DependencyResilienceExecutor 熔断开路直接拒绝缺 cause（判为可重试）；依赖不可用批量文件回 PENDING 而非 FAILED。
要做（13/14）：
4. 身份/IP/知识库/全局四层配额 + 上传容量 + 活跃流式会话上限；超限返回机器可读原因/重试间隔 + 审计指标。
5. Token 预算：请求前预算检查 + 请求后计量落库（chat/embedding/ocr/vision 分策略），消费 Token 计量契约聚合。
6. 文档生命周期事件（幂等）+ 跨存储对账（MinIO/MySQL/Qdrant/快照，dry-run/保留窗口/补偿）+ 审计事件 + 治理指标看板；Flyway V5x__*。

关键坑：SSE 只加 traceId 装饰，不改事件结构（结构归 B/C）；Token 由 B/C 产数、你只聚合，别自建计量；线程池/韧性骨架要早于 B/C 大规模使用就绪。
独占可改：com.slz.crm.platform.**（trace/async/resilience/quota/token/lifecycle/reconcile/audit，contract 除外）、Flyway V5x__*。
禁改（需申请）：pom.xml、application.yml 核心、契约、B/C 的业务实现。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：跨线程 traceId、线程池拒绝降级、熔断开路/重试/批量恢复、配额拒绝、预算边界、对账识别/清理、审计可追溯 测试通过。
产出：变更摘要 + 测试结果 + 契约/依赖变更申请。
````

---

## Agent-E · 超级管理员动态配置（Wave 2，收敛期）

````text
角色：动态配置 agent。目标：让 AI/业务参数（含提示词）可由超管运行期动态配置。
负责：tasks.json 任务 15。
入口：Agent-0 完成；DynamicConfigService 契约已冻结；D 的审计骨架就绪；B/C 的配置读取点已明确。

要做：
1. 动态配置项模型：DB 存储 + 命名空间（ai.prompt.*/ai.model.*/rag.retrieval.*/business.*），动态值覆盖静态默认；Flyway V6x__*（配置项表 + 版本历史表）。
2. 仅超级管理员(roleId=1)可写；非超管拒绝。
3. 热生效：缓存 + 有界刷新；提示词/模型 Provider/限流配额阈值/检索分块参数改动后无需重启生效。
4. 校验护栏：类型/范围/枚举校验，非法值拒绝并保持原值；敏感值掩码。
5. 版本历史与回滚；配置变更审计复用 D 的审计流。

关键坑：动态配置不替代启动期生产配置保护——密钥/凭据仍走环境变量（静态），你只管运行期可调的策略参数；读取接口要已被 B/C/D 消费，别改他们的调用点签名。
独占可改：com.slz.crm.platform.config.**（dynamic-config）、Flyway V6x__*。
禁改（需申请）：pom.xml、application.yml 核心、契约、B/C/D 的实现。
铁律：独立分支，禁止提交 master、禁止 git push；每步可编译；中文 Javadoc；不顺手重构；每阶段先出变更摘要。
出口：越权写拒绝、非法值拒绝、热生效、回滚、审计记录 测试通过。
产出：变更摘要 + 测试结果 + 契约/依赖变更申请。
````

---

## Agent-Integrator · 集成、质量基准与门禁（Wave 2/3）

````text
角色：集成 agent。目标：串行合并各 lane，建立质量基准与发布门禁。
负责：tasks.json 任务 16、17 + 各 lane 合并。
入口：各 lane 出口达标。

要做：
1. 串行合并，每次合并后编译+冒烟再合下一个，顺序：A(数据权限)→B(RAG)→C(AI 助手)→D(治理)→E(动态配置)。
2. 裁决合并冲突（集中在 pom.xml / application.yml / Flyway 版本号 / SSE 事件契约）。
3. 任务 16：RAG 质量基准集（文本/表格/图片/边界）+ 召回/命中/引用正确性/答案一致性评估 + 首字延迟/总延迟/token/失败率 + JSON/HTML 报告；检索参数优化（分块/混合/缓存/topK/重排，保留实验开关与回退）。
4. 任务 17：关键 API 契约测试；安全/数据权限/状态机/韧性/RAG 评估纳入 CI + 性能预算/回归阈值；核心服务拆分（先补契约测试再小步提取，接口不变）；发布前检查/灰度开关/回退步骤/部署运维手册/权限矩阵。

关键坑：并行开发、串行合并；任何 lane 的契约变更必须经你批准并广播；测试基线（CRM 306 / RAG 491：485+6skip）重建是硬门禁。
独占可改：集成分支、CI 配置、契约/性能测试、发布文档。
铁律：禁止提交 master、禁止 git push（推送需用户显式授权）；合并前确认各 lane 变更摘要已审核。
出口：全量回归通过 + CI 门禁绿 + 发布检查完成。
产出：集成报告 + 全量测试结果 + 发布检查清单。
````

---

## 分派清单速览

| Agent | 波次 | tasks | 依赖 | 关键产出 |
|-------|------|-------|------|----------|
| Agent-0 基座 | Wave0 串行 | 1,2,3,4 | 无 | 可编译基座 + 冻结契约 + Flyway V1 |
| Agent-A 数据权限 | Wave1 并行 | 5,6 | Wave0 | 上下级数据权限 |
| Agent-B-spike | Wave1 前置 | B0 | Wave0 | go/no-go 结论 |
| Agent-B-persist | Wave1 | 7(持久化),8 | Wave0+spike | JPA→MyBatis+授权缝合 |
| Agent-B-ai | Wave1 | 7(AI),9 | B-persist | LangChain4j→Spring AI+流式 |
| Agent-C AI 助手 | Wave1(11)/Wave2(10) | 11,10 | Wave0；10 待 B | 限流/引用/洞察/KB工具 |
| Agent-D 治理 | Wave1(12)/Wave2 | 12,13,14 | Wave0 | traceId/配额/韧性/对账/审计 |
| Agent-E 动态配置 | Wave2 | 15 | Wave0+D | 超管动态配置 |
| Integrator | Wave2/3 | 16,17 | 各 lane | 集成+质量基准+门禁 |
