# 增量契约规范：熔断半开探测（wire-circuit-half-open）

## 1. 行为契约增量规范

### 契约 1：到期后半开单探测（Half-Open Single Probe）
- **GIVEN** 某依赖熔断 OPEN 且 `openDuration` 到期；
- **WHEN** 后续多个调用到达；
- **THEN** 恰 1 个调用 CAS 抢占探测资格真实执行外呼，其余调用不执行外呼、按既有 `CIRCUIT_OPEN` 语义快速拒绝（failureType 仍为 `CIRCUIT_OPEN`，`CircuitOpenException` 类型不变）。

### 契约 2：探测成功恢复全量（Probe Success Closes Circuit）
- **GIVEN** HALF_OPEN 探测调用成功；
- **WHEN** 后续调用到达；
- **THEN** 熔断回 CLOSED、失败计数清零，后续调用正常执行；`dependency.circuit.probe{result=success}` 计数 +1。

### 契约 3：探测失败立即回 OPEN（Probe Failure Reopens Immediately）
- **GIVEN** HALF_OPEN 探测调用失败；
- **WHEN** 探测失败发生；
- **THEN** 熔断立即回 OPEN 并重置完整 `openDuration` 窗口（不等 `failureThreshold` 次数）；`execute` 重试循环在该场景终止上抛；`dependency.circuit.probe{result=failure}` 计数 +1。

### 契约 4：双入口统一与既有语义保持（Both Entrypoints, Zero Regression）
- **GIVEN** `execute` 与 `executeNoRetry` 两入口；
- **WHEN** 半开状态机生效；
- **THEN** 两入口统一过状态机；P-u 全部既有行为保持——零自动重试（`execute` 的重试语义仅用于显式调用方且不受半开放大）、CLOSED 态计数/开闸阈值/指标时序、既有 8 条测试零修改全绿；public `@Autowired` 构造器签名不变。

### 契约 5：门面零改动（Facade Untouched）
- **GIVEN** HALF_OPEN 拒绝异常；
- **WHEN** 异常穿过 P-u 接线的三门面（ModelProviderImpl 经 ModelCallGuard / QdrantVectorStore / MinioFileStorageService）；
- **THEN** 转换行为与 P-u 合入后逐字等价（HALF_OPEN 拒绝不构成新异常类型或新 failureType）；三门面、ModelCallGuard、两装配类 diff 为空。

### 契约 6：指标与时钟（Metrics and Clock）
- **GIVEN** 半开状态机生效；
- **THEN** 新增 `dependency.circuit.probe{dependency, result=success|failure}` counter；`dependency.circuit.open` gauge 在 OPEN 与 HALF_OPEN 期间均为 1、CLOSED 为 0；时钟经 package-private 构造器链可注入（测试控时），默认 `System::nanoTime`，不新增配置键。
