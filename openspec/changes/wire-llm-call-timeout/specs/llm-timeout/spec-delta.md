# 增量契约规范：非流式 LLM 调用超时（wire-llm-call-timeout）

## 1. 行为契约增量规范

### 契约 1：非流式调用受超时约束（Bounded Non-Streaming Calls）
- **GIVEN** `platform.ai.model.timeout-seconds = N`（N>0）；
- **WHEN** `ModelProviderImpl.chat/vision/embed` 的底层模型调用超过 N 秒未返回；
- **THEN** 调用方在 N 秒附近收到快速失败（异常类型在实现登记于 tasks.md §0），底层调用被取消/中断，不无限等待。

### 契约 2：零值不限时语义保留（Zero Means Unlimited）
- **GIVEN** `platform.ai.model.timeout-seconds = 0`；
- **WHEN** 任意非流式模型调用；
- **THEN** 行为与接线前等价（不限时），六消费方及其降级路径零改动。

### 契约 3：正常调用零扰动（No Disturbance On Happy Path）
- **GIVEN** 底层模型在阈值内正常返回；
- **WHEN** 超时接线生效后调用；
- **THEN** 返回值与接线前逐字段等价（`ModelCallResult` 形状不变），不引入额外重试或额外模型调用（费用红线）。
