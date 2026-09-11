# 发布手册 · CRM+RAG 融合平台（task18d）

> 面向发布/运维/超管。涵盖：发布前检查、环境变量与基础设施、生产强约束、灰度开关与默认策略、回退步骤、部署运维、权限矩阵。
> 架构基线见 `proposal.md` / `design-decisions.md`；契约见 `contracts-frozen.md`；集成接线见 `prompts/agent-integrator.md`。

---

## 1. 发布前检查清单（逐项打勾方可上线）

**代码与门禁**
- [ ] CI 全绿：surefire 单测（基线 **467**，只增不减）+ failsafe 集成测试（Docker）。
- [ ] `FlywayMigrationIT` 在 CI（有 Docker）真跑通过：7 脚本 `V1/V3/V4/V4_1/V5/V6/V21` 按序无撞号迁移 + 关键表建出。
- [ ] `ApplicationContextSmokeTest`（H2 全上下文加载）通过：bean 图无冲突。
- [ ] `SseContractTest` 通过：SSE 12 事件名 + payload 字段集未漂移（前端已对齐）。
- [ ] RAG 质量基准（task17 harness）跑分 ≥ 上一版：召回/命中/引用精度/一致性无回退。

**安全与配置**
- [ ] 仓库无明文密钥：`git grep -iE "sk-|api[_-]?key|password" ` 仅命中 `.env.example` 占位与注释；`.env` 已被 `.gitignore` 排除。
- [ ] `SPRING_PROFILES_ACTIVE=prod`（非 dev）。
- [ ] 生产强约束满足（见 §3），`ProductionConfigurationGuard` 启动不报错。
- [ ] 所有必填环境变量已注入（见 §2），密钥经环境/密管而非镜像。
- [ ] 动态配置默认值已核对（strict-KB 兜底、图片缓存上限、意图类目、检索 topK/权重）；仅超管可写已验。

**数据与依赖**
- [ ] MySQL 8 可达、库/账号就绪；**上线前全量备份**（Flyway 前向迁移，回退依赖备份）。
- [ ] Qdrant 可达、collection 与 `QDRANT_DIMS`（默认 1024）与嵌入模型维度一致。
- [ ] MinIO 可达、bucket（默认 `knowledge-files`）已建、凭据有效。
- [ ] DashScope（或兼容端点）key 有效、配额充足；token 预算/请求配额阈值已设。

---

## 2. 环境变量与基础设施（权威清单见 `.env.example`）

| 分组 | 变量 | 说明 |
|---|---|---|
| profile | `SPRING_PROFILES_ACTIVE` | 生产必须 `prod` |
| MySQL | `SLZ_DATASOURCE_HOST/PORT/DATABASE/USERNAME/PASSWORD` | 主库连接，全部外置 |
| JWT | `SLZ_JWT_SECRET_KEY`(≥32B)、`SLZ_JWT_TTL` | 认证统一到 CRM JWT 拦截器 |
| 附件令牌 | `SLZ_ATTACH_TOKEN_KEY`(AES-256,32B) | 附件下载令牌 |
| 管理员 | `SLZ_ADMIN_EMAIL`、`SLZ_ADMIN_PASSWORD` | 仅首启初始化 |
| 模型 | `DASHSCOPE_API_KEY`；`SLZ_AI_PROVIDER/BASE_URL/API_KEY/CHAT_MODEL/VISION_MODEL/EMBEDDING_MODEL` | Provider 抽象，默认 dashscope |
| Qdrant | `QDRANT_HOST/PORT/TLS/API_KEY/COLLECTION/DIMS`、`RAG_VECTOR_PROVIDER` | 向量库，默认 qdrant |
| MinIO | `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET` | 知识库文件对象存储 |
| 基座开关 | `SLZ_FLYWAY_ENABLED`(prod=true)、`SLZ_AUTO_TABLE_MODE`(prod=none)、`SLZ_ACTUATOR_PROTECTED`(prod=true)、`SLZ_FILE_PATH` | 见 §3 |

**外部依赖**：MySQL 8、Qdrant、MinIO、DashScope（或 OpenAI 兼容/vLLM 端点）。

---

## 3. 生产 profile 强约束（`application-prod.yml` + `ProductionConfigurationGuard`，fail-fast）

任一不满足则**启动期直接失败**，避免带病上线：
- `spring.flyway.enabled=true` + `auto-table.mode=none`：**库结构唯一真相源 = Flyway**，禁用自动建表（D2/任务4）。
- `platform.actuator.protected-enabled=true`：`/actuator/**` 仅探针放行，其余需超管（移除 Spring Security 后由 `ActuatorProtectionFilter` 保护）。
- `springdoc.api-docs.enabled=false`：生产不匿名暴露 OpenAPI。
- 必填密钥/连接缺失 → Guard 拒绝启动。

---

## 4. 灰度开关与默认策略

**开关分两层**：静态（环境变量/properties，重启生效）+ 动态（E 的 `DynamicConfigService`，超管热生效、带版本回滚）。

| 开关 | 层 | 生产默认 | 灰度用途 |
|---|---|---|---|
| `crm.ai.knowledge-retrieval.mock-enabled` | 静态 | **false**（用 B 真检索） | 紧急降级：检索链路故障时可临时 true 回空结果 mock（诚实生成，不阻断主答） |
| `rag.vector-store.provider` / `RAG_VECTOR_PROVIDER` | 静态 | qdrant | 本地/降级可切 in-memory（禁生产） |
| `knowledge.storage.provider` | 静态 | minio | 同上 |
| `SLZ_AI_PROVIDER` | 静态 | dashscope | 切 openai-compatible/vllm |
| `rag.retrieval.*`（topK/minScore/rerank 权重/图文 0.7-0.3） | 动态 | 见默认常量 | 检索调参灰度，热生效 + 可回滚 |
| `rag.intent.*`（意图类目/关键词，D17） | 动态 | 内置类目 | 意图过滤热更新 |
| strict-KB 空匹配兜底开关（D16） | 动态 | 关（不强制"未检索到") | KB ON 零命中行为 |
| 图片缓存上限（D13） | 动态 | 每会话 8 条 LRU | 图文解耦内存控制 |
| `ai.prompt.*` / `ai.model.*`（提示词/温度/maxToken） | 动态 | 内置 | 提示词/模型灰度 |

**灰度节奏**：先小范围（按角色/部门）开知识库检索 → 观测 token 预算/失败率/质量基准跑分 → 无回退再放量。任何动态开关改动**自动进 D 治理审计流**（`GovernanceDynamicConfigAuditRecorder`，敏感值掩码）。

---

## 5. 回退步骤（按层次，从快到慢）

1. **动态开关回退（秒级，首选）**：超管在配置中心把可疑参数**回滚到上一版本**（E 的版本历史 + rollback，回滚值再过校验护栏）。例：检索质量下降 → 回滚 `rag.retrieval.*`；提示词异常 → 回滚 `ai.prompt.*`。
2. **功能降级（秒级）**：检索链路故障 → `knowledge-retrieval.mock-enabled=true`（诚实空结果，主答不阻断）；模型异常 → 切 `fallback-model` 或备用 Provider。
3. **制品回退（分钟级）**：重新部署上一版镜像/JAR。**注意 Flyway 是前向迁移**——新版若加了迁移脚本，回退制品后旧代码面对新表结构通常兼容（新增表/列不破坏旧逻辑）；若涉及破坏性变更，需配合 DB 备份恢复。
4. **数据库回退（最后手段）**：Flyway **无自动 down**。破坏性回退 = 恢复上线前全量备份。`V21`（org 数据权限）脚本头有专门回滚注意（先确认无角色绑定 1174/1175 等权限再删）。

> 原则：优先用 1/2（热回退、不丢数据），制品/DB 回退是兜底。每次回退后重跑质量基准 + 健康检查确认恢复。

---

## 6. 部署与运维手册

**启动**：注入 §2 环境变量 → `SPRING_PROFILES_ACTIVE=prod` → 启动。Flyway 自动迁移到最新版本；`ProductionConfigurationGuard` 校验强约束；健康分组就绪后接流量。

**健康端点**（Actuator，受保护）：
- liveness：`management.endpoint.health.group.liveness`
- readiness：`readinessState,db,vectorStore,minio`（生产含 minio；`VectorStoreHealthIndicator` 内存回退 WARN、probe 失败 DOWN；`MinioHealthIndicator` 仅 MinIO 装配时存在）。

**线程池**（D 的 `PlatformAsyncConfig`，均有界队列 + 拒绝降级 + MDC/UserContext 传播）：`batchUploadTaskExecutor`、`streamChatTaskExecutor`、`embeddingTaskExecutor`、`documentParsingTaskExecutor`、`memoryBypassExecutor`（记忆旁路，C 消费）。

**依赖韧性**（D 的 `DependencyResilienceExecutor`）：嵌入/向量库/对象存储/模型调用按依赖名隔离熔断 + 指数退避重试；拒绝携带 `DependencyFailureType`（区分"暂时回 PENDING"与"永久失败"）。

**可观测性**：MDC `traceId` 跨线程闭环；Micrometer 指标（token 计量、配额拒绝、审计、熔断）；token 预算请求前检查 + 请求后落库（chat/embedding/ocr/vision/summary/intent 全覆盖）。

**SSE 断线续传**：业务事件带 `id=<generationId>:<seq>` + 有界缓冲；重连按 `Last-Event-ID` 重放（重放≠重执行，不重跑 LLM/检索/不重复计 token）；缓冲失效发 `RESUME_UNAVAILABLE`；多实例需 sticky session 兜底。

---

## 7. 权限矩阵

| 能力 | 超管(roleId=1) | 部门负责人 | 普通销售/用户 | 说明 |
|---|---|---|---|---|
| 数据范围 | ALL | DEPT_AND_CHILD（本部门及以下） | SELF（本人）/ DEPT | A 的 `DataScopeServiceImpl` 两重载 + `QueryWrapperAspect` AOP 注入；`sys_dept.leader_id` 判定负责人 |
| 部门/及以下查看权限项 | ✓ | ✓（V21 授予） | 按角色绑定 | V21 登记 `*_VIEW_*_DEPT` / `*_DEPT_AND_SUB`（客户/销售机会/订单/合同/回款/任务等） |
| 客户联系人 | ✓ | 按范围 | **恒 SELF** | `customer_contact` 固定 SELF（不随部门放大） |
| 知识库访问 | ✓ | 按 KB 成员 | 按 KB 成员 | B 的 `KnowledgeBaseAuthorizationService`；检索按授权 KB 过滤，不能借助手绕过 |
| AI 助手对话 | ✓ | ✓ | ✓ | CRM JWT 认证；受数据权限 + KB 授权双约束 |
| 动态配置**写** | **✓ 仅超管** | ✗（96005） | ✗ | E：非超管拒绝；越权写零落库零审计 |
| 动态配置**读**（生效值） | ✓ | ✓ | ✓ | 各 lane 经 `DynamicConfigService` 消费 |
| Actuator 非探针端点 | ✓ | ✗ | ✗ | `ActuatorProtectionFilter` |
| 治理审计/对账/配额管理 | ✓ | ✗ | ✗ | D 治理域管理动作 |

> 授权不变量：数据范围"就大不就小"仅在同一维度内（超集不变量）；跨域引用统一 `UserContext.userIdRef()`（`user:<id>`），域内用 `userId`(BIGINT)。助手不得成为绕过 CRM 数据权限或知识库授权的旁路。
