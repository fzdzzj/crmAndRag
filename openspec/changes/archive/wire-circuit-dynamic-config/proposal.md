# 提案：wire-circuit-dynamic-config（卡 P-w）

## 为什么
P-u/P-v 上线的熔断器参数（failureThreshold=5 / openDuration=30s）是构造器 final 字段，调整需改代码重启。依赖故障特征各异（DashScope 抖动 vs Qdrant 重启窗口），运维需要运行期调参能力。同时 F-3（P-u 遗留语义异味，P-v 复核挂 P-w 前置）：execute 循环 markFailure 先于 retryable 判定——凑满阈值/探测那次的不可重试异常被报 CALL_FAILED 而非 NON_RETRYABLE 且消耗熔断预算，参数错误（调用方 bug）不该消耗熔断预算。

## 改什么
1. **全局 2 键**（owner 拍板）：新增 `platform.resilience` 命名空间（Registry 白名单扩 1），登记 `failure-threshold`（默认 5）与 `open-duration-ms`（默认 30000）；新增 ResilienceConfigResolver 每调用实时读、越界/缺失回落默认（fail-safe）；executor 参数经 resolver；按依赖名级联留后续。maxAttempts/backoff 生产零消费不暴露（防误导启用重试）。
2. **F-3 修复**（owner 拍板）：execute 路径 retryable 判定提前于熔断计数——不可重试异常不消耗熔断预算、报 NON_RETRYABLE（指标 counter 照记 failure，熔断预算不动）；executeNoRetry 无谓词恒 CALL_FAILED 维持。
3. **F-6 措辞修正**：P-v 契约 6"OPEN 期间为 1"改为"未到期 OPEN"（惰性 gauge，一行）。

## 不改什么
P-u/P-v 全部拍板保持：零自动重试、HALF_OPEN 拒绝 failureType 仍 CIRCUIT_OPEN、门面层异常契约零变更（三门面+ModelCallGuard+两装配写集外）；在飞 OPEN 窗口不被追溯调整（新调用即时生效）；熔断键走既有通用动态配置权限（非超管特例）；既有 executor 用例断言零修改；任何 OpenAPI 契约。

## 费用红线
纯配置与次序修复，零外呼路径变化、零调用放大；测试零真实模型调用。

## 后续卡方向（非本卡）
按依赖名覆盖级联、P-x 摄取路恢复重放（DependencyRecoveryPolicy，F-3 修复后 NON_RETRYABLE 分类可被恢复映射正确消费为永久失败）。
