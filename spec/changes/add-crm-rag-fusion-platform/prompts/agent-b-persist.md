# Agent-B-persist 提示词 · RAG 持久化与授权迁移（Wave 1，B 线，先于 B-ai）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `migration-subtasks.md`（A0–A7）、`specs/knowledge-rag/spec-delta.md`、`specs/platform-fusion/spec-delta.md`、`tasks.json`（任务7持久化部分、任务8）、`design-decisions.md`（D2/D3/D8）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`rag/entity/*`、`rag/repository/*`、`auth/**`、`rag/service/KnowledgeBaseAuthorizationService`、`rag/service/rag/RagRetrievalAccessFilter`）。

## 角色与目标
你是 **RAG 持久化+授权 agent**。目标：把 RAG 从 JPA 迁到 MyBatis-Plus，并把身份/授权缝合到 CRM，移除匿名态。

## 负责范围
tasks.json 任务 7（重打包+持久化部分）、任务 8（身份缝合/授权/移除匿名）；migration-subtasks.md A0–A7。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-b-rag`
- 分支：`feature/lane-b-rag`（在 B-spike 之后接续）
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`UserContext`/`Result` 契约已冻结；B-spike 已给出向量维度统一值。

## 独占可改
`com.slz.crm.knowledge.**` 的 entity/mapper/persistence/授权服务、Flyway `V3x__*`、`docker-compose`。
## 禁改（需申请）
`pom.xml`（移除 JPA 依赖走申请）、`application.yml` 核心、冻结契约、CRM 业务包、**AI/流式服务（留给 B-ai）**。

## 要做
1. `com.mark.knowledge.*` 重打包为 `com.slz.crm.knowledge.*`；Jackson3(`tools.jackson`)→Jackson2；starter `webmvc`→`web`；控制器返回 CRM `Result`。
2. 9 个实体 JPA 注解→MyBatis-Plus（`@TableName`/`@TableId(AUTO)`/`@TableField`/`@EnumValue`/`@TableLogic`）；**表名统一单数（chat_messages→chat_message 等 9 张）、审计列 created_time/updated_time→create_time/update_time、软删除 deleted→is_deleted（Boolean）**；实体全扁平无关联。
3. 9 个 Repository→Mapper：派生查询→`LambdaQueryWrapper`；`@Query` JPQL→XML/`@Select`；`@Modifying`(`updateStatusIfMatch`/`markFailed`)→`@Update` 保留状态条件；`deleteByX` 返回 `long`→`int` 适配调用方。
4. 服务层：`save()`→按 id 分流 `insert`/`updateById`；移除脏检查隐式依赖；`@Transactional` 保留。
5. 身份缝合：RAG 授权入参消费 CRM `UserContext` 稳定 userId(`user:<id>`)；停用 RAG 独立登录/`SecurityConfig`；`UserIdentityVerifier`/`KnowledgeBaseAuthorizationService`/`ResourceAccessPolicy` 保留判定、换身份来源。
6. **移除匿名态**：删除 `AnonymousRagChatService` 及匿名分流；公开知识库=所有已登录用户可见。
7. 检索授权过滤：消除 `findAllByDeletedFalseOrderByUploadTimeDesc` 全表扫描，改按 documentId 集合/知识库范围查询。
8. Flyway `V3x__*`：知识库成员 owner/member 从 `teacher:<id>`/`config:<username>` 映射到 CRM userId；`uploaded_file.knowledge_base` 归属回填；`chat_conversations`/`chat_messages` 的 `username`→`user_id`。

## 关键坑
- `KnowledgeBaseMemberEntity.userId` 是 String；批量任务状态机（`updateStatusIfMatch`/`markFailed`/`findAllByStatusInAndUpdatedTimeBefore` 恢复扫描）语义必须保持。
- 与 B-ai 都碰 `knowledge` 服务层：**你先把持久化调用改完并合入**，B-ai 再在其上换模型层（持久化先、AI 后）。

## 注释重点（本 lane）
- **JPA→MyBatis-Plus 的语义差异处**（save 分流、deleteByX 计数、@Modifying→@Update 的状态条件）必须行内注释说明"为何这样改、保持什么语义"。
- 每个 Mapper 方法注释对应原 JPA 派生查询的语义；XML SQL 注释说明用途。
- 实体字段/枚举迁移保留原中文注释；授权服务替换身份来源处注释说明"从 SecurityContext 改为 UserContext userId"。
- Flyway `V3x` 头部注释写明数据映射/回填策略与回滚注意（源项目硬规则：库结构变更必须写清影响）。

## 出口条件
持久化+授权单测通过；匿名/成员/负责人/管理员/越权访问矩阵通过；userId 映射正确。合入后通知 B-ai 接续。

## 产出
变更摘要 + 测试结果 + 依赖变更申请（移除 `spring-boot-starter-data-jpa`）。
