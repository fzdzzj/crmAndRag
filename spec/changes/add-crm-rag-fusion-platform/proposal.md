# 提案：CRM × RAG 融合平台（crmAndRag）

> 变更 ID：`add-crm-rag-fusion-platform`
> 目标仓库：`d:\code\crmAndRag`（当前为空仓，`master` 分支，无提交）
> 方法：以 CRM 项目为基础，扩展 RAG 能力，完善部门/上下级数据权限，并中和（整合）两个源项目的未完成提案。
> 状态：待用户确认关键取舍后实施（本提案不自动改代码、不自动提交、不自动推送）。

## Why

新项目 `crmAndRag` 需要把两套已成熟但技术栈分叉的后端融合为一个统一平台：

- **CRM**（`com.slz.crm`，Spring Boot 3.5.5 / Java 21 / MyBatis-Plus / JWT 拦截器 / spring-ai-alibaba）：完整的客户关系管理业务域（客户、联系人、商机、合同、订单、回款、发票、审批、协助、报表、数据统计），一套基于角色的数据权限（`DataScopeLevel` = SELF/TAGE/ALL），以及一个生产级 AI 助手（SSE 流式、工具调用、草稿→确认两级执行的 Human-in-the-Loop 状态机、心跳/超时/指标、生成中接管、token 回写、评测集）。
- **RAG**（`com.mark.knowledge`，Spring Boot 4.0.2 / Java 21 / Spring Data JPA / Spring Security / LangChain4j / Qdrant / MinIO）：完整的知识库能力（多格式文档解析、OCR/视觉抽取、分块、向量化、混合检索 BM25+向量、图片检索、流式问答、会话记忆、知识库成员授权与检索授权过滤），以及一套后端治理基础设施（traceId/MDC 传播、分任务类型线程池、依赖韧性执行器、生产配置保护、可观测性）。

**背景**：
- 两个源项目各自都有**未完成提案**，需要在融合时一并中和，避免重复建设与语义漂移：
  - CRM：`add-ai-assistant`（安全/配额/预算/语义缓存等后续迭代项）、`enhance-ai-assistant`（限流落地、引用跳转、主动洞察、评测机制）。
  - RAG：`add-backend-governance-hardening`（请求配额、依赖韧性修复、AI Token 预算、版本化迁移、生命周期事件、跨存储对账、内容安全、审计门禁）、`update-backend-optimization-roadmap`（RAG 质量基准、检索性能优化、核心服务拆分、验证门禁）。
- CRM 现有数据权限**缺少部门/上下级维度**：`DataScopeLevel` 只有 `NONE/SELF/TAGE/ALL`，`DataScopeResolverImpl` 只按“本人+共享 / 标签 / 全部”解析；`SysDeptEntity` 已有 `parentId` 部门树、`UserEntity` 已有 `deptId`，但**没有“本部门 / 本部门及以下（上司查看下属）”**的数据范围，也没有部门负责人字段。
- 两套认证（CRM 的 JWT 拦截器 + `@RequirePermission` vs RAG 的 Spring Security 过滤链 + 静态账号/teacher 账号）、两套持久化（MyBatis-Plus vs JPA）、两套 AI 栈（dashscope vs LangChain4j）、两套 `Result`、两个 Spring Boot 大版本（3.5.5 vs 4.0.2）必须在融合时统一或明确共存边界。

**当前状态**：`crmAndRag` 为空仓；CRM 与 RAG 分别独立可用、各自回归通过（CRM 306 测试、RAG 456 测试），但无法协同——AI 助手只能查结构化业务数据，知识库只能独立问答，两者不共享身份、权限、治理与成本口径。

**期望状态**：一个以 CRM 为基座的统一后端平台——
- 单一身份与权限主体：CRM 的用户/角色/数据权限作为唯一认证源，RAG 的知识库授权绑定 CRM 稳定 userId。
- AI 助手可同时利用**结构化工具调用**（CRM 业务数据）与**非结构化知识检索**（RAG 知识库），二者受同一数据权限与知识库授权约束。
- 部门上下级数据权限（上司查看下属）贯通业务查询、AI 工具查询与知识库可见范围。
- 平台级治理（traceId/MDC、线程池、依赖韧性、请求配额、Token 预算、版本化迁移、可观测性、内容安全、审计）统一沉淀，两个源项目的未完成提案在此收敛为一条路线图。

## What Changes

### 组 1：融合基座（platform-fusion）
- 确立 CRM 为基座：统一到 **Spring Boot 3.5.x / Java 21**，RAG 代码回填（back-port）到该基线（Jackson 2、Spring Security 6）。
- 统一包结构与模块边界：CRM 业务保留 `com.slz.crm.*`；RAG 能力重打包为 `com.slz.crm.knowledge.*`；平台共享治理沉淀为 `com.slz.crm.platform.*`。
- 统一认证：以 CRM 的 JWT + `UserContext` 为唯一身份源，RAG 授权服务消费 CRM 稳定 userId，停用/改造 RAG 独立 Spring Security 过滤链。
- 持久化统一到 MyBatis-Plus：CRM 域沿用 MyBatis-Plus；RAG 域由 JPA 迁移为 MyBatis-Plus（实体/仓储改写），共用同一 MySQL，表结构统一由 Flyway 管理（生产禁用 `auto-table`/`ddl-auto` 改表）。
- 统一 `Result`/异常/错误码：以 CRM `com.slz.crm.common.result.Result` + `GlobalExceptionHandler` 为准，RAG 响应适配。
- 统一配置：合并 `application.yml`，外置敏感项，区分 dev/test/prod profile，引入版本化迁移与生产配置保护；运行期可调参数下沉到动态配置（见组 6）。

### 组 2：部门架构与上下级数据权限（org-data-scope）
- 扩展 `DataScopeLevel`：新增 `DEPT`（本部门）与 `DEPT_AND_CHILD`（本部门及以下/含下属）。
- 基于 `sys_dept.parentId` 构建部门树，结合 `user.deptId` 解析“下属用户集合”，为负责人（上司）提供跨下级部门的数据可见性。
- 为受控业务表补充 `_DEPT` / `_DEPT_AND_SUB` 权限项（`PermissionOperates`）并接入 `DataScopeResolver` 与 `MyDataPermissionHandler`。
- 数据权限贯通 AI 工具查询与知识库可见范围（同一 userId + 数据范围口径）。

### 组 3：知识库/RAG 能力扩展（knowledge-rag）
- 迁移知识库与成员授权、文档管理（上传/解析/OCR/视觉/分块）、向量化与 Qdrant、混合检索、图片检索、流式问答与会话记忆、MinIO 存储。
- 知识库授权改绑 CRM 稳定 userId；检索授权过滤改为按需查询（消除全表扫描）。
- 新增 CRM AI 助手可调用的“知识库检索工具”，使助手回答可引用知识文档。
- **移除 RAG 未登录/匿名态**：所有知识库接口需登录；“公开”知识库语义收敛为“所有已登录用户可见”，删除 `AnonymousRagChatService` 匿名链路（D8）。
- **向量库经 VectorStore 抽象接入**：默认 Qdrant，另提供内存回退实现（可选文件持久化）用于本地/测试；`docker-compose` 提供真 Qdrant（D9，回应 #9）。

### 组 4：AI 助手统一与精进（ai-assistant）
- 保留 CRM AI 助手既有能力（SSE 流式、17+ 工具、草稿状态机、心跳/超时/接管、token 回写、评测集）。
- **AI 模型层统一到 Spring AI**（spring-ai-alibaba，默认 dashscope/qwen），引入可插拔模型 Provider 抽象（dashscope / openai 兼容 / vllm 可切换）；RAG 原 LangChain4j 的 chat/embedding/vision 调用迁移到该抽象（D4，回应 #5）。
- 中和 `enhance-ai-assistant` 未完成项：限流真正落地、references 结构化引用事件、主动洞察（`ai_insight`）、评测执行机制。
- 中和 `add-ai-assistant` 后续项：提示注入/越权防护、并发配额与预算、语义缓存占位。
- 统一 Token 计量口径（CRM `tokenCount` + RAG `AiUsageInfo`）。

### 组 5：平台治理与硬化（platform-governance）
- 中和 RAG `add-backend-governance-hardening` + `update-backend-optimization-roadmap` 未完成项，提升为平台级：traceId/MDC 跨线程传播、分任务类型线程池、依赖韧性执行器（修复熔断开路无 cause、依赖不可用回 PENDING）、四层请求配额、AI Token 预算、Flyway 版本化迁移（生产禁 `ddl-auto`/`auto-table` 改表）、文档生命周期事件与跨存储对账、内容安全分级、审计与治理指标、RAG 质量基准与检索性能优化、验证门禁。

### 组 6：超级管理员动态配置（dynamic-config）
- AI 与业务侧配置（提示词、模型选择与参数、限流/配额阈值、检索/分块参数、功能开关）支持超级管理员运行期动态配置：DB 存储 + 热生效 + 类型/范围校验护栏 + 版本/回滚 + 审计（D10，回应 #11）。

## 关键取舍决策（评审已确认 2026-09-08）

以下决策已确认，详细对比见 `design-decisions.md`：

| # | 决策点 | 确认结果 |
|---|--------|----------|
| D1 | Spring Boot 基线 | ✅ **A** 统一 3.5.x，回填 RAG |
| D2 | 持久化 | ✅ **B** 归一到 MyBatis-Plus + Flyway（RAG 由 JPA 迁移为 MyBatis-Plus） |
| D3 | 认证统一 | ✅ **A** 以 CRM JWT 为唯一源，RAG 授权绑 userId |
| D4 | AI 栈 | ✅ **A** dashscope 为默认，统一到 Spring AI + 可插拔模型 Provider（可适配不同模型） |
| D5 | 模块结构 | ✅ **A** 单模块 + 包边界（后续可演进多模块） |
| D6 | 上司下属建模 | ✅ **A+B** 部门树 + `sys_dept.leaderId` 负责人字段 |
| D7 | 向量库/对象存储 | ✅ **A** 引入 Qdrant + MinIO |
| D8 | RAG 未登录态 | ✅ **B** 移除匿名态，知识库接口全部需登录 |
| D9 | 向量库本地回退 | ✅ **A+B** docker-compose 提供真 Qdrant；VectorStore 抽象 + 内存回退用于 dev/test |
| D10 | 动态配置 | ✅ **B** 超级管理员可运行期动态配置 AI/业务参数（含提示词） |

## Impact

### 受影响的规范
- 新增能力域（本变更的 `specs/` 下）：
  - `platform-fusion` — 融合基座：构建/技术栈、包与模块边界、认证统一、持久化共存、配置与错误统一。
  - `org-data-scope` — 部门树与上下级数据权限。
  - `knowledge-rag` — 知识库、文档、向量、检索、流式问答、知识库授权、存储。
  - `ai-assistant` — CRM AI 助手统一与精进（含知识库检索工具、限流、引用、洞察、Token 计量）。
  - `platform-governance` — traceId/MDC、线程池、依赖韧性、配额、Token 预算、版本化迁移、可观测性、内容安全、审计、质量基准。
  - `dynamic-config` — 超级管理员动态配置（AI/业务参数、提示词、热生效、校验、版本回滚、审计）。

### 受影响的代码（融合后目标布局）
- `com.slz.crm.common` / `com.slz.crm.server.*` — CRM 业务与 AI 助手基座（沿用）。
- `com.slz.crm.pojo.entity`（`SysDeptEntity`/`UserEntity` 等） — 部门树与上下级字段扩展。
- `com.slz.crm.common.enumeration.DataScopeLevel` / `PermissionOperates` — 新增 DEPT/DEPT_AND_CHILD 及对应权限项。
- `com.slz.crm.server.service.impl.DataScopeResolverImpl` + `MyDataPermissionHandler` — 下属集合解析与 SQL 注入。
- `com.slz.crm.knowledge.*`（由 `com.mark.knowledge.*` 重打包，JPA→MyBatis-Plus、LangChain4j→Spring AI） — 知识库/RAG 能力。
- `com.slz.crm.platform.*` — 治理基础设施（trace/async/resilience/quota/token/migration/observability/audit）与动态配置（dynamic-config）、模型 Provider 抽象、VectorStore 抽象。

### 用户影响
- 上司（部门负责人/上级部门用户）可查看下属及下级部门数据；普通用户仍限本人+共享。
- AI 助手可基于知识库文档作答并给出来源引用；超配额/越权/高风险内容会被明确拒绝。
- 匿名与登录能力按统一权限矩阵区分，部分原公开接口可能收紧。

### API 变更
- 不主动破坏 CRM/RAG 既有响应结构；统一 `Result` 封装时保持字段兼容。
- 新增：部门/下属数据范围相关查询、知识库检索工具、配额/审计/对账管理端接口。
- 可能为配额、授权、内容安全返回标准错误码与机器可读原因。

### 需要迁移
- [ ] 数据库迁移：CRM `sys_dept`/`user` 上下级字段、DEPT 权限项；RAG 知识库授权 userId 映射、配额、Token 计量、生命周期事件、审计表；统一到 Flyway 版本化脚本。
- [ ] 依赖迁移：合并 `pom.xml`（Boot 3.5.x BOM、Spring AI(spring-ai-alibaba/dashscope)、Qdrant、MinIO、统一 MyBatis-Plus（移除 JPA/Hibernate）、jjwt 版本归一）。
- [ ] 配置迁移：合并 `application.yml`/`application.yaml`，敏感项外置，profile 分离。
- [ ] 用户沟通：数据范围、知识库授权、配额与内容安全策略。
- [ ] 文档更新：部署（Qdrant/MinIO）、运维手册、告警阈值、回退步骤。

## 时间线评估

大型；建议分 6 个阶段（见 `tasks.json`）：
1. **融合基座**（中到大）：技术栈统一、包/模块、认证统一、配置与错误统一、双持久化打通、健康启动。
2. **部门与上下级数据权限**（中）：DataScopeLevel 扩展、部门树解析、权限项、SQL 注入、贯通测试。
3. **知识库/RAG 迁移**（大）：实体/仓储/服务/控制器回填、Qdrant+MinIO 接入、检索授权过滤优化、userId 绑定。
4. **AI 助手统一与精进**（中）：知识库检索工具、限流落地、references、洞察、Token 计量统一。
5. **平台治理与硬化**（中到大）：MDC、线程池、韧性、配额、Token 预算、版本化迁移、生命周期/对账、内容安全、审计、可观测性。
6. **质量基准与验证门禁**（中）：RAG 质量基准、检索性能优化、契约/性能测试、CI 门禁、灰度与回退。

## 风险

- **Spring Boot 版本回填风险**：RAG 依赖 Boot 4（Jackson 3 `tools.jackson`、starter 命名、Security 7）；回填 3.5.x 需替换为 Jackson 2、`spring-boot-starter-web`、Security 6，并验证 LangChain4j/Qdrant/MinIO 兼容。缓解：先做最小可编译骨架 + 关键链路冒烟。
- **双持久化复杂度**：MyBatis-Plus 与 JPA 共存需明确事务边界与建表策略（禁用 `auto-table`/`ddl-auto` 生产改表，统一 Flyway）。缓解：模块隔离 + 迁移基线。
- **认证统一回归面大**：RAG 授权从 Spring Security 上下文改为 CRM `UserContext`，涉及知识库成员、检索过滤、匿名/登录分流。缓解：保留授权判定点，仅替换身份来源，补越权测试。
- **数据权限扩展影响既有查询**：新增 DEPT/DEPT_AND_CHILD 可能改变列表可见范围。缓解：默认不授予新权限，按角色灰度，补数据范围测试。
- **新增基础设施**：Qdrant + MinIO 引入部署与运维成本。缓解：提供 compose 与健康检查，本地可用内存/文件系统回退。
- **未完成提案语义漂移**：两源项目提案条目需去重合并，避免重复实现或相互矛盾。缓解：`migration-inventory.md` 建立条目→新能力域映射。
- **范围过大**：一次性融合风险高。缓解：严格按阶段推进，每阶段可审查、可回归、可回退（遵循源项目“阶段审核后提交”的硬性规则）。
