# 规范差异：platform-governance（平台治理与硬化）

本文件包含对 `spec/specs/platform-governance/spec.md` 的规范变更。
本能力域中和 RAG 源提案 `add-backend-governance-hardening` 与 `update-backend-optimization-roadmap` 的未完成项，并将其提升为平台级共享治理，同时惠及 CRM 业务与 AI 助手。

## ADDED 需求

### Requirement: traceId 与 MDC 跨线程传播
系统 SHALL 为每个请求分配 traceId 并写入 MDC，且 MUST 在自定义线程池、异步编排、延迟任务与流式任务中传播同一 traceId。

#### Scenario: 异步线程保留 traceId
GIVEN 请求线程已将 traceId 写入 MDC
WHEN 任务提交到异步线程池执行
THEN 系统通过任务装饰器传播 traceId 与用户/安全上下文
AND 任务结束后清理 MDC，避免线程复用污染

#### Scenario: 流式与延迟任务追踪连续
GIVEN 流式问答或延迟重试任务在独立线程运行
WHEN 输出日志
THEN 日志携带与原始请求一致的 traceId
AND 治理与审计日志可跨线程闭环定位

### Requirement: 分任务类型线程池与拒绝降级
系统 SHALL 为不同任务类型（批量上传、聊天记录、流式问答、记忆旁路、嵌入、解析）配置独立线程池，并 MUST 定义有界队列、拒绝策略与超时。

#### Scenario: 线程池隔离
GIVEN 高负载下批量上传任务激增
WHEN 批量上传线程池饱和
THEN 系统按拒绝策略降级（如丢弃并记录/调用者运行）
AND 不拖垮流式问答等其他任务线程池

#### Scenario: 线程池指标
GIVEN 各线程池运行中
WHEN 采集指标
THEN 系统暴露入队、执行、拒绝、完成与活跃线程数指标
AND 拒绝事件可被告警发现

### Requirement: 依赖韧性执行器
系统 SHALL 通过统一的依赖韧性执行器包裹外部依赖调用（嵌入、向量库、对象存储、LLM 等），提供重试、退避与熔断，并 MUST 正确分类可恢复与不可恢复失败。

#### Scenario: 熔断开路拒绝可重试
GIVEN 熔断器处于 OPEN 状态直接拒绝调用
WHEN 执行器抛出拒绝异常
THEN 异常携带可识别的 cause 与失败语义
AND 批量处理将其判定为可重试而非永久失败

#### Scenario: 依赖不可用回 PENDING
GIVEN 向量化依赖暂时不可用
WHEN 文档处理失败
THEN 系统将受影响文件恢复为 PENDING 状态
AND 依赖恢复后可重放处理

### Requirement: 请求配额
系统 SHALL 实施身份、IP、知识库与全局四层请求配额，覆盖问答、流式生成、上传容量与活跃流式会话上限，超限 MUST 返回机器可读原因。

#### Scenario: 超出配额
GIVEN 某身份/IP/知识库/全局维度达到配额上限
WHEN 新请求到达
THEN 系统快速拒绝并返回机器可读的超限原因与重试间隔
AND 记录配额拒绝审计指标

#### Scenario: 并发流上限
GIVEN 活跃流式会话数达到上限
WHEN 用户发起新的流式问答
THEN 系统拒绝或排队（按策略），避免资源耗尽

### Requirement: AI Token 预算
系统 SHALL 在请求前进行 token 预算检查、请求后计量落库，覆盖 chat、embedding、OCR、vision、摘要与意图提取，并 MAY 按模型/用户/会话/知识库累计成本与告警。

#### Scenario: 预算检查
GIVEN 某维度 token 预算已耗尽
WHEN 新的模型调用请求到达
THEN 系统在调用前拒绝并返回可解释的超限原因
AND 不产生新的 LLM 消耗

#### Scenario: 计量与汇总
GIVEN 各类模型调用完成
WHEN 系统获得用量
THEN 系统按维度持久化 token 消耗
AND 输出成本指标与日/月汇总，超阈值告警

### Requirement: 版本化数据库迁移与生产保护
系统 SHALL 通过版本化迁移（Flyway 或等价）管理生产数据库模式，生产环境 MUST 禁止应用启动隐式建表改表。

#### Scenario: 生产禁用自动改表
GIVEN 应用以生产 profile 启动
WHEN 检测到 `ddl-auto=update` 或 `auto-table` 自动改表开启
THEN 系统拒绝启动或强制切换为版本化迁移
AND 表结构变更仅通过受审计的迁移脚本执行

#### Scenario: 迁移演练与回退
GIVEN 一次模式迁移
WHEN 在预发布数据库执行
THEN 系统提供迁移、备份、验证与回退步骤
AND 迁移失败可回滚到前一版本

### Requirement: 文档生命周期事件与跨存储对账
系统 SHALL 为文档入库、处理、删除、归档、重建与恢复定义幂等生命周期事件，并 SHALL 对数据库、对象存储与向量库进行跨存储对账与孤儿清理。

#### Scenario: 生命周期事件幂等
GIVEN 文档状态发生变迁
WHEN 系统记录生命周期事件
THEN 事件具备幂等键与状态版本
AND 重复消费或崩溃恢复不产生重复副作用

#### Scenario: 对账与清理
GIVEN 存在缺失向量、孤儿向量、孤儿对象或失效快照
WHEN 对账扫描运行
THEN 系统识别不一致并提供 dry-run、管理员确认与保留窗口清理
AND 输出对账报告与清理审计日志

### Requirement: 内容安全分级
系统 SHALL 对不可信文档、检索片段与用户输入进行安全分级，并 SHALL 提供提示注入检测、上下文包装与高风险内容降级/拦截路径。

#### Scenario: 高风险内容处置
GIVEN 内容命中高风险策略
WHEN 系统处理该内容
THEN 系统按允许/降级/复核/拦截四级策略处置
AND 限制高风险内容进入系统提示与持久记忆
AND 记录策略版本、命中原因与处置结果

### Requirement: 审计与治理可观测性
系统 SHALL 记录授权变更、配额调整、清理与重建等管理动作审计事件，并 SHALL 提供治理指标、告警阈值与管理端查询接口。

#### Scenario: 管理动作可追溯
GIVEN 管理员调整授权或配额、触发清理/重建
WHEN 动作执行
THEN 系统记录审计事件（谁、何时、对什么、结果）
AND 可通过管理端接口查询

### Requirement: RAG 质量基准与检索性能
系统 SHALL 建立固定的 RAG 质量基准集与自动评估，覆盖召回、命中、引用正确性与答案一致性，并 SHALL 记录首字延迟、总延迟、token 消耗与失败率。

#### Scenario: 质量评估报告
GIVEN 基准集包含文本/表格/图片/边界问题
WHEN 运行评估
THEN 系统输出可比较的 JSON/HTML 报告
AND 检索参数调整前后可对比效果

#### Scenario: 检索优化可回退
GIVEN 调整分块、混合检索、重排或缓存参数
WHEN 优化上线
THEN 系统保留实验开关并支持回退到旧参数
AND 以固定基准集验证不产生质量回退

### Requirement: Actuator 健康与指标
系统 SHALL 移植 Qdrant/MinIO 健康 indicator 并挂到 VectorStore 抽象与 MinIO，提供 liveness/readiness 健康分组与 Micrometer/Prometheus 指标；`show-details` MUST 为 when_authorized，健康端点 MUST 收敛统一。

#### Scenario: 依赖健康分级
GIVEN Qdrant 或 MinIO 不可用
WHEN 健康检查探测
THEN readiness 判为不健康、liveness 仍为 UP（停发新流量但不杀进程）
AND 向量库切内存回退时健康为降级(WARN)而非 DOWN

#### Scenario: 指标合并与暴露
GIVEN 助手 SSE 指标、线程池指标、检索耗时与治理指标
WHEN 采集
THEN 系统统一到 Micrometer 并经 `/actuator/prometheus` 暴露
AND 健康端点由 CRM `/health` 与 Actuator health 收敛为统一分组

### Requirement: 验证门禁与发布检查
系统 SHALL 为关键 API 建立契约测试，并将安全、数据权限、状态机、依赖韧性与 RAG 评估纳入 CI，定义性能预算与发布前检查、灰度与回退策略。

#### Scenario: CI 门禁
GIVEN 一次代码变更提交
WHEN CI 运行
THEN 系统执行契约、安全、数据权限、韧性与评估测试
AND 未达门禁阈值时阻止合入

---

## 备注

- 本能力域中和 RAG 源提案：
  - `add-backend-governance-hardening`：请求配额、依赖韧性修复、Token 预算、版本化迁移、生命周期事件、跨存储对账、内容安全、审计门禁。
  - `update-backend-optimization-roadmap`：RAG 质量基准、检索性能优化、核心服务拆分、验证门禁。
- 已完成项（生产配置基线、权限矩阵、异步治理、状态机、依赖健康、可观测性、MDC 传播、userId 授权切换）在基座/知识库迁移阶段承接，不重复建设。
- 动态配置（`dynamic-config` 能力域）的变更审计复用本域“审计与治理可观测性”，配置变更事件纳入统一审计流。
- 治理基础设施沉淀在 `com.slz.crm.platform.*`，为 CRM 业务、AI 助手与知识库共享。
- **记忆旁路执行器**（意图/摘要，pool=2/有界队列/AbortPolicy + CAS 单飞 + 拒绝降级）为本域提供的共享基础设施，ai-assistant 复用而非自建。
- **Token 计量补盲点**：RAG 意图/摘要原用 `chat(String)` 重载漏计 token，本域统一计量口径须覆盖（chat/embedding/OCR/vision/摘要/意图）。
- **Actuator**：移除 Spring Security 后由 CRM 拦截器保护（见 platform-fusion），本域负责健康 indicator 移植、健康分组与指标合并。
