# 规范增量：platform-perf

## ADDED Requirements

### Requirement: 性能优化前必须有可复现基线文档
在调整线程池、切分默认策略或 SSE 超时之前，仓库 SHALL 具备 `docs/perf-baseline.md`，盘点已有 Micrometer 指标与线程池拒绝策略，并包含一次 ¥0 微基准记录。该文档 MUST NOT 把跨机抖动数字当作 CI 门禁。本变更 MUST NOT 修改生产热路径代码。

#### Scenario: 基线文档存在
- **WHEN** measure-perf-baseline 合入
- **THEN** `docs/perf-baseline.md` 含指标资产表、本机微基准表、未测项清单

#### Scenario: 热路径不变
- **WHEN** 本变更合入
- **THEN** `FixedChunkingStrategy` / `AiThreadPoolConfig` 池大小 / SSE 超时配置与合入前一致
