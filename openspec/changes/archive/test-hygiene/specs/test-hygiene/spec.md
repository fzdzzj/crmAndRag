# 规范增量：test-hygiene

## ADDED Requirements

### Requirement: Caffeine 缓存统计开启
`CacheConfig`（8 个）与 `RequestQuotaService`（2 处）的全部 Caffeine builder MUST 调用 `.recordStats()`，使 Micrometer 可采集命中率/驱逐/负载指标；缓存 TTL、容量、驱逐策略 MUST 保持不变。启动日志 MUST 不再出现 "does not support statistics" 警告。

#### Scenario: 启动无统计警告
- **WHEN** 应用启动（含测试上下文）
- **THEN** Micrometer 不再为任何注册 cache 打 "does not support statistics" 警告

### Requirement: AI 定时任务测试环境隔离
`AiPendingActionExpireTask` 与 `AiMemoryOrchestrator` 的 `@Scheduled` Bean MUST 以 `@ConditionalOnProperty(name = "crm.ai.scheduled-enabled", havingValue = "true", matchIfMissing = true)` 门控——生产与未配置环境默认启用（零行为变化），测试 profile（`application-test.yml`）显式关闭。H2 冒烟测试日志 MUST 不再出现 `ai_pending_action` Table not found 异常栈。

#### Scenario: H2 冒烟无定时任务报错
- **WHEN** ApplicationContextSmokeTest 在 H2 auto-table 环境运行
- **THEN** 日志无 ai_pending_action 相关 Table not found 异常

#### Scenario: 生产默认启用
- **WHEN** 未配置 crm.ai.scheduled-enabled 启动生产
- **THEN** 两个定时任务照常注册与调度（matchIfMissing=true）

### Requirement: surefire 挂 Mockito agent
surefire 的 `<argLine>` MUST 同时包含 JaCoCo 的 `@{argLine}` 与 byte-buddy-agent 的 `-javaagent:` 挂载（版本以依赖树实测为准）；测试日志 MUST 不再出现 "A Java agent has been loaded dynamically" 警告，JaCoCo 覆盖率报告 MUST 照常生成。

#### Scenario: 无动态 agent 警告且覆盖率不失效
- **WHEN** mvn -B -ntp test
- **THEN** 无动态加载警告，target/site/jacoco/index.html 正常产出

### Requirement: BeanPostProcessor 工厂方法静态化
`AsyncContextDecoratorConfig#asyncContextDecoratorPostProcessor` MUST 为 static @Bean 方法（匿名类体与 MDC 装饰逻辑不变），启动日志不再出现 BPP 非静态警告。

#### Scenario: BPP 警告消失
- **WHEN** 上下文启动
- **THEN** 日志无该 BeanPostProcessor 相关警告，异步线程池仍获得 MDC 装饰器（615 回归兜底）
