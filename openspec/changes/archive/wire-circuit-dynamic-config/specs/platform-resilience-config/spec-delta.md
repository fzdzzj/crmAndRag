# 增量契约规范：熔断参数动态配置化 + F-3 次序修复（wire-circuit-dynamic-config）

## 1. 行为契约增量规范

### 契约 1：全局 2 键实时生效（Global Keys, Immediate Effect）
- **GIVEN** `platform.resilience.failure-threshold`（默认 5）与 `platform.resilience.open-duration-ms`（默认 30000）两键已注册；
- **WHEN** 管理端写入合法新值；
- **THEN** 后续每次熔断调用实时按新值执行（阈值判定与开闸窗口）；已写入 openUntilNanos 的在飞 OPEN 窗口不被追溯缩短/延长；键被删除/写坏/越界时回落默认值（5 / 30000），绝不抛配置异常打断业务调用。

### 契约 2：不可重试异常不消耗熔断预算（F-3：Non-Retryable Excludes Budget）
- **GIVEN** execute 入口调用方提供 retryable 谓词且某异常被判不可重试（如 IllegalArgumentException）；
- **WHEN** 该异常发生在任意 attempt（含凑满阈值的那一次）；
- **THEN** 上抛 `DependencyFailureType.NON_RETRYABLE`（消息「依赖调用不可重试」），熔断连续失败计数不增加（不消耗预算、不因此开闸）；指标 `dependency.call{result=failure}` 照记该次失败。探测路径（executeNoRetry 无谓词）维持恒 CALL_FAILED 语义。

### 契约 3：既有熔断语义零回归（P-u/P-v Preserved）
- **GIVEN** 半开三态状态机与 F-1 硬化；
- **WHEN** 本卡合入后任意调用序列；
- **THEN** 零自动重试、HALF_OPEN 单探测 CAS、探测失败立即回 OPEN（reopenByProbeFailure 权威）、陈旧调用让位在飞探测（openByStaleCall）、门面层异常契约零变更（DependencyUnavailableException 不出门面）——全部与 P-v 硬化后逐字等价；既有 executor 用例断言零修改全绿。

### 契约 4：配置面边界（Configuration Surface）
- **THEN** 仅暴露 2 键（failure-threshold / open-duration-ms）；maxAttempts/initialBackoff/multiplier 不入动态配置（生产零消费防误导启用重试）；按依赖名覆盖级联为后续卡范围；熔断键走既有通用动态配置权限模型（读写路径与权限校验复用 DynamicConfigAdminService/AccessGuards，无新增特例）；键定义在 `docs/dynamic-config-keys.md` 强制登记。

### 契约 5：测试构造器兼容（Test Constructor Compatibility）
- **GIVEN** package-private 测试构造器（固定参数路径）；
- **THEN** 既有 15 条 executor 用例断言零修改（或最小改动且逐条留痕）；public @Autowired 装配路径注入 resolver 读动态键。
