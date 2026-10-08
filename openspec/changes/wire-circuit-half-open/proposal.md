# 提案：wire-circuit-half-open（卡 P-v）

## 为什么
P-u 上线的熔断器在 OPEN 到期后直接全量放行（CircuitState 亲读实证：`openUntilNanos` 到期即 `isOpen()=false`，无半开）——依赖持续宕机时每个 30s 窗口放行 5 个请求逐个实打实等待超时（模型路单请求最长 60s），达阈值再开闸，循环往复。半开探测把每窗口受害面从 5 个请求收紧为 1 个探测：到期后仅 1 个调用真实试水，成功才恢复全量、失败立即回 OPEN。

## 改什么
`DependencyResilienceExecutor` 的 CircuitState 补三态状态机（CLOSED → OPEN → HALF_OPEN）：到期后单探测 CAS 抢占（其余调用按 `CIRCUIT_OPEN` 拒绝、不执行外呼）；探测成功回 CLOSED 清零计数；探测失败立即回 OPEN 重置全窗口（不等 failureThreshold 次数）；`execute` 与 `executeNoRetry` 双入口统一过状态机；新增 `dependency.circuit.probe{result}` counter；时钟注入供测试控时。

## 不改什么
P-u 三拍板全部保持：零自动重试；HALF_OPEN 拒绝的 failureType 仍为 `CIRCUIT_OPEN`、`CircuitOpenException` 类型不变——**三门面与 ModelCallGuard、两装配类零改动（写集外）**；既有 8 条 executor 测试零修改；public `@Autowired` 构造器签名不变；不新增配置键；任何 OpenAPI 契约。

## 费用红线
半开只减少受害请求的物理外呼（HALF_OPEN 期间拒绝不执行），不放大任何路径调用次数；测试零真实模型调用。

## 后续卡方向（非本卡）
熔断参数动态配置化（P-w，参数集含半开语义后更稳）、DependencyRecoveryPolicy 摄取路 PENDING 恢复重放（P-x）。
