# 提案 · 单副本部署契约硬化 (harden-single-replica-contract)

<!-- id: harden-single-replica-contract -->
<!-- status: proposal -->
<!-- reviewer: Owner 派发 / B 档独立复核 -->

## 1. 背景与动机

根据候选池 `work/_paa-candidates-pool-v2.md` 第 59 项（I-4 进程内状态四件套：横向扩容即失效）：
1. **进程内状态全景扩张**：系统内多个核心领域深度依赖单副本进程内状态，包括：
   - 配额治理：`RequestQuotaService`（Caffeine 四层固定窗口计数）；
   - 流式交互：`AiChatStreamLifecycle` / `AiStreamRegistry`（内存活跃流管理与取消）；
   - 动态配置：`DynamicConfigCache`（ConcurrentHashMap 读缓存快照）；
   - AI 限流：`AiRateLimiter`（ConcurrentHashMap 用户滑动窗口）；
   - 韧性熔断：`DependencyResilienceExecutor`（ConcurrentHashMap 熔断三态与故障计数）；
   - 业务单号：`NumberGenerator`（AtomicInteger 每日序号）；
   - 安全加固：`LoginAttemptService`（Caffeine 登录防爆破）与 `TokenBlacklistService`（Caffeine 登出黑名单）；
   - 定时调度：6 大 `@Scheduled` 任务（客户流失扫描、合同预警、公海回收等）均为无分布式锁单机触发。
2. **部署契约约束缺口**：
   - 历史卡 P-d 引入了 `ProductionConfigurationGuard` 启动期第 8 项校验，但静态守卫 `SingleReplicaArchitectureAssumptionGuardTest` 仅覆盖了 3 个组件，大量新状态组件未纳管；
   - 容器编排 `deploy/docker-compose.yml` 未显式锁定 `deploy.replicas: 1`，存在误水平扩容破坏业务一致性的风险；
   - `NumberGenerator` 缺乏单元测试覆盖，且时间戳哈希格式化存在潜在越界隐患。

## 2. 方案设计与架构边界

1. **编排与环境部署契约硬化**：
   - `deploy/docker-compose.yml` 中 `app` 服务显式声明 `deploy: replicas: 1` 与 `mode: replicated`，锁定单副本编排；
   - `deploy/docker-compose.yml` 环境变量显式配置 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA: "true"`；
   - `.env.example` 补充 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true` 及说明；
   - `deploy/README.md` 增加《单副本单体部署契约与进程内状态组件清单》权威指引。
2. **单副本静态架构守卫全量扩容 (`SingleReplicaArchitectureAssumptionGuardTest`)**：
   - 扩容单机组件登记表至 8 大组件，强制锁定源文件内的进程内状态标记与单机假设声明；
   - 增加静态扫描断言，校验 6 大 `@Scheduled` 定时任务类均已显式声明单副本/单机调度假设；
   - 增加 `deploy/docker-compose.yml` 包含 `replicas: 1` 部署约束的静态文本断言。
3. **`NumberGenerator` 健壮性加固与单测覆盖**：
   - 加固 `getTimestampHash()` 实现，采用 `String.format("%04X", (hash & 0xFFFF))` 确保恒定 4 字符十六进制，消除潜在越界缺陷；
   - 规范 Javadoc 并标注单副本序号递增契约；
   - 新建 `NumberGeneratorTest`，覆盖回款/发票单号格式、并发自增唯一性与跨日重置行为。

## 3. 红线与非目标

- **红线 1**：零数据库迁移（DDL/DML），下一可用号仍为 V43；
- **红线 2**：零新增 Maven 依赖，严禁引入 Redis、ShedLock 或外部分布式协调中间件；
- **红线 3**：零破坏性接口变更，前端与 OpenAPI 契约 100% 保持零改动；
- **红线 4**：严禁触碰 `frontend/typed-router.d.ts`。
