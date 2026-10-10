# 增量契约规范：摄取路恢复重放（wire-ingestion-recovery-replay）

## 1. 行为契约增量规范

### 契约 1：熔断开闸失败进 PENDING（Circuit-Open Failure Maps To PENDING）
- **GIVEN** 摄取（ingest/reingest）过程中依赖调用被熔断快速拒绝（异常 cause 链任意层深扫到 `CircuitOpenException`）；
- **WHEN** 失败落地；
- **THEN** uploaded_file status=PENDING，清理语义与 markFailed 逐字一致（清向量+物理清切片+保留原始文件与 DB 记录），errorMessage 记录失败摘要，等待自动重放。

### 契约 2：非熔断失败零回归（Non-Circuit Failure Unchanged）
- **GIVEN** 摄取失败但 cause 链不含 `CircuitOpenException`（业务异常、散点外呼失败等）；
- **WHEN** 失败落地；
- **THEN** status=FAILED 且全部行为与接线前逐字等价（清理语义、errorMessage、既有失败用例断言零修改）。

### 契约 3：定时重放流转（Scheduled Replay Lifecycle）
- **GIVEN** `rag.ingest.replay-enabled=true`；
- **WHEN** 调度 tick 到来；
- **THEN** 扫 status=PENDING（软删除外）按 `rag.ingest.replay-batch-size` 上限逐篇以原上传者身份（userId 加载真实用户构造 UserContext）调用既有 reingest：成功 → COMPLETED；异常且 cause 链含 CircuitOpenException → 保持 PENDING 下轮再试（零物理外呼）；其他异常（含 canWrite 授权拒绝、上传者不存在/身份不完整）→ 标 FAILED 终态。单篇失败不中断本轮，每篇重放沿用 reingest 既有平台审计。

### 契约 4：开关默认关（Disabled By Default）
- **GIVEN** `rag.ingest.replay-enabled` 缺失、为 false 或读取异常（fail-safe 回落 false）；
- **WHEN** 任意调度 tick；
- **THEN** 空转返回，零重放、零外呼（费用红线：重放=真实嵌入调用，必须显式开启）。

### 契约 5：既有入口零改动（Existing Entrypoints Untouched）
- **GIVEN** 本变更合入后；
- **WHEN** 使用 KnowledgeReingestRunner（环境变量触发）、reingest 手动入口、canWrite 授权与平台审计链路；
- **THEN** 行为与合入前逐字等价；`DependencyRecoveryPolicy` 接口签名、三门面与 ModelCallGuard 异常透传语义、platform/resilience 全模块零改动。
