# 提案：测试与可观测性卫生修复（Caffeine 统计 + 定时任务隔离 + Mockito agent + BPP 静态化）

> 变更 ID：`test-hygiene` ｜ 能力域：`test-hygiene` ｜ 序列：独立小提案（权限线闭环后的卫生收尾）
> 来源：2026-09-13/14 两轮 `mvn -B -ntp test` 亲验日志中反复出现的四类警告/错误（非臆测，日志实录）。

## Why

1. **9 个 Caffeine cache 未开统计**：每次启动 Micrometer 打 9 行警告"cache ... does not support statistics"；`CacheConfig` 8 个 + `RequestQuotaService` 2 处 builder 均未调 `recordStats()`——生产命中率、驱逐、负载全盲，指标体系只有 size 没有命中面。
2. **AI 定时任务在 H2 冒烟环境报错**：`AiPendingActionExpireTask`（fixedRate 60s）与 `AiMemoryOrchestrator` 清理任务（5min）在 H2 auto-table 环境查 `ai_pending_action` 报 Table not found——冒烟测试每次都刷异常栈，污染日志、干扰诊断，且定时任务在测试环境本无意义。
3. **Mockito self-attaching 警告**：surefire 日志每次出现 "A Java agent has been loaded dynamically (byte-buddy-agent)"——JDK 新版本默认禁用动态加载 agent 后（warning 已明示）现有 Mockito 用法会直接失败，属潜伏兼容性地雷。
4. **`AsyncContextDecoratorConfig` 的 `@Bean` BeanPostProcessor 非静态**：Spring Framework 6.2+ 明确建议 BPP 工厂方法为 static，否则警告——一行修复。

## What Changes

### 1. Caffeine 统计开启（9 处）
- [CacheConfig.java](../../../../src/main/java/com/slz/crm/server/config/CacheConfig.java) 8 个 builder（userName/deptName/companyName/contactName/opportunityName/contractName/chartDataCache/chartCache）与 [RequestQuotaService.java](../../../../src/main/java/com/slz/crm/platform/quota/RequestQuotaService.java) L34/L77 两处 builder 链上补 `.recordStats()`；
- 中文注释标注「test-hygiene 任务 1.x」；不改任何 TTL/容量/驱逐策略（Non-Goal）。

### 2. AI 定时任务测试隔离
- `AiPendingActionExpireTask`、`AiMemoryOrchestrator` 的 `@Scheduled` 方法所在 Bean 加 `@ConditionalOnProperty(name = "crm.ai.scheduled-enabled", havingValue = "true", matchIfMissing = true)`（生产默认开，零行为变化）；
- `src/test/resources/application-test.yml` 增加 `crm.ai.scheduled-enabled: false`；
- 验收：H2 冒烟测试日志不再出现 `ai_pending_action` Table not found 异常栈。

### 3. surefire 挂 Mockito agent（消动态加载警告）
- `pom.xml` properties 解析 byte-buddy-agent 路径 + surefire `<argLine>` 与 JaCoCo `@{argLine}` 合并（`-javaagent:... @{argLine}`）；
- 验收：`mvn -B -ntp test` 日志不再出现 "Java agent has been loaded dynamically" 警告，JaCoCo 报告照常生成。

### 4. BeanPostProcessor 静态化
- `AsyncContextDecoratorConfig#asyncContextDecoratorPostProcessor` 加 `static`（匿名类体不变）；
- 验收：启动日志不再出现该 BPP 非静态警告。

## Impact

- **修改**：`CacheConfig`、`RequestQuotaService`、`AiPendingActionExpireTask`、`AiMemoryOrchestrator`（或其配置类）、`AsyncContextDecoratorConfig`、`pom.xml`（surefire argLine）、`application-test.yml`。
- **新增**：无新文件、无新依赖（byte-buddy-agent 已在依赖树，只改挂载方式）。
- **不改**：任何缓存参数、定时任务业务逻辑、`init_data.sql`、迁移链、CI 基线（surefire 615 不变——四项修复均不新增测试用例）。

## 风险

- **recordStats() 内存开销**：每 cache 一组 LongAdder，代价可忽略；
- **定时任务条件化**：`matchIfMissing = true` 保证生产与未配置环境默认开；Testcontainers IT 若用 test profile 也会关闭这两个任务——验收要求既有 Docker IT（WriteChainRegressionIT 等）回归绿，确认无 IT 依赖定时任务真实触发；
- **argLine 合并**：JaCoCo `prepare-agent` 会写 `argLine` 属性，surefire 必须引用 `@{argLine}` 再追加 javaagent，否则覆盖率静默失效——验收要求 target/site/jacoco 照常产出；
- **BPP 静态化**：方法加 static 不改变 Bean 语义（工厂方法静态化正是 Spring 建议），回归 615 全绿兜底。

## Non-Goals

- 不做缓存容量/TTL 调优，不引入新缓存；
- 不做指标告警/看板建设（仅开统计让指标可见）；
- 不重构定时任务为消息驱动/调度中心；
- 不动 `init_data.sql`、迁移链、WebMvcConfiguration。
