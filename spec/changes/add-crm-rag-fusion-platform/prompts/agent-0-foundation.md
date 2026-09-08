# Agent-0 提示词 · 基座与契约（Wave 0，串行，最先）

> 第一步（必做）：完整阅读 `spec/changes/add-crm-rag-fusion-platform/prompts/_common-rules.md`（注释规范+工作纪律），再读 `proposal.md`、`design-decisions.md`、`tasks.json`（任务1–4）、`agent-execution-plan.md`（§2 契约、§3 归属、§5 Flyway）、`migration-inventory.md`。
> 源项目只读参考：CRM `D:\code\crm\back\crm-back\.worktrees\ai-createid`、RAG `D:\code\rag\back\RAG`。

## 角色与目标
你是融合平台**基座 agent**。目标：在 `d:\code\crmAndRag` 建立可编译可跑的统一基座，并入 CRM 全量，**冻结所有 lane 共用的接口契约**。你是第一个 agent，是所有并行的前提。

## 负责范围
tasks.json 任务 1、2、3、4 + agent-execution-plan.md §2 契约冻结 + Flyway V1 基线。

## 入口条件（当前仓库状态）
- 仓库 `d:\code\crmAndRag` 当前在分支 `spec/add-crm-rag-fusion-platform`，已有多个 docs 提交（仅 `spec/` 提案文档），**尚无代码**。你从该分支继续（或按调度者建 `develop`），产出首个代码基线提交。
- 开工前先 `git log --oneline` / `git status` 确认实际状态，勿假设“空仓 master 无提交”。

## worktree / 分支
- 在主树 `d:\code\crmAndRag` 工作；base 分支 `spec/add-crm-rag-fusion-platform`（或按调度者指定的 `develop`）。
- 禁止提交 master、禁止 push。

## 独占可改
项目骨架、`pom.xml`、`application.yml`、`com.slz.crm.common.*`、`com.slz.crm.platform.contract.*`、Flyway `V1__*`。（你是唯一可改 pom/yml 的 agent。）

## 要做
1. Maven 骨架：spring-boot-starter-parent **3.5.x** / Java 21；包边界 `com.slz.crm.*`（业务+助手）、`com.slz.crm.knowledge.*`（知识库，暂空）、`com.slz.crm.platform.*`（治理+契约）。
2. `pom.xml`：MyBatis-Plus（唯一持久化，**不引 JPA/Hibernate**）、Spring AI（spring-ai-alibaba/dashscope）、Qdrant VectorStore、MinIO、jjwt 0.12.5、springdoc、actuator、testcontainers。
3. 并入 CRM 全量业务域 + AI 助手运行时 + 认证权限（JWTInterceptor/PermissionsInterceptor/@RequirePermission/DataScope*）+ 通用件（Result/异常/工具/枚举/GlobalExceptionHandler）。
4. 统一配置：合并 `application.yml`；敏感项环境变量外置；dev/test/prod profile；生产 `auto-table.mode=none`；接入 `ProductionConfigurationGuard`。
5. Flyway `V1__baseline.sql`：CRM 现有表 + RAG 9 张表（RAG 表 DDL 从 Hibernate 生成物固化）。
6. **冻结并广播契约**（放 `com.slz.crm.platform.contract`）：UserContext、Result+错误码、ModelProvider、VectorStore 抽象、DataScope 接口、SSE 事件契约、Token 计量、DynamicConfigService（清单见 _common-rules §3）。

## 关键坑
- Boot 3.5.x（不是 4）；持久化只留 MyBatis-Plus；AI 用 Spring AI 不用 LangChain4j。
- 契约是后续所有 lane 的编程基线，**签名要一次想清楚**；冻结后改动需你或 integrator 批准。

## 注释重点（本 lane）
- **契约接口必须写详尽中文 Javadoc**（职责、参数、返回、线程安全、异步传播语义）——所有 lane 靠它编程。
- `application.yml` 每个键加中文注释（对齐 RAG 风格）；`pom.xml` 关键依赖注明用途。
- Flyway `V1` 头部注释写明"基线来源：CRM 现有表 + RAG Hibernate 固化 DDL"。

## 出口条件
骨架可编译启动 + CRM 回归基线（原 306 测试）在新仓重建通过 + 契约冻结并广播。

## 产出
变更摘要 + 测试结果 + **契约清单（接口签名）** + base 提交（供各 lane worktree 派生）。
