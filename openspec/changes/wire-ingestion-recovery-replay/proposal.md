# 提案：摄取路恢复重放（wire-ingestion-recovery-replay）

## 为什么

P-u/P-v/P-w 落地了依赖熔断体系（五依赖接线、三态半开探测、参数动态配置化），但业务侧未闭环：文档摄取因模型/向量库/存储故障失败后，`markFailed` 无差别标 FAILED，只能靠启动期运维 runner（环境变量）或手动逐篇 reingest 恢复。依赖故障恢复后，失败文档无人自动重放。`DependencyRecoveryPolicy` 接口自 P-u 立项即为"把上传文件映射为 PENDING/FAILED"预留，至今生产零消费。

## 做什么

1. **失败分类**：摄取失败按"cause 链是否深扫到 `CircuitOpenException`"分类——熔断开闸拒绝 = 确凿依赖故障 → 标 PENDING（复用 markFailed 清理语义：清向量+物理清切片+留原始文件）；其余维持 FAILED 现状。经 `DependencyRecoveryPolicy` knowledge 侧实现接线。
2. **定时重放**：`@Scheduled` 固定 tick 实时读 `rag.ingest.replay-enabled`；开启时扫 PENDING（批量上限键控），按 `uploaded_file.userId` 构造原上传者 `UserContext` 跑既有 `reingest`（canWrite 授权 + 平台审计全链路复用）。成功 → COMPLETED；熔断仍开 → 保持 PENDING（快速拒绝零外呼费用）；其他失败（含授权拒绝/身份失效）→ FAILED 终态。
3. **配置面**：`rag.ingest` 新命名空间 2 键（replay-enabled 默认 false / replay-batch-size 默认 5），fail-safe 回落，每调用实时读。

## 取舍

- **定时扫描而非熔断恢复事件回调**：OPEN 期重放调用被熔断快速拒绝（零物理外呼、零费用），扫描与熔断天然互补；事件回调需改 platform/resilience 状态机写集，收益不成比例。
- **只认熔断开闸而非放宽到网络异常**：三门面 CALL_FAILED 路径原样透传底层异常（P-u 冻结语义，不可为甄别改写）；SDK 异常类型甄别脆弱、误分类风险高。开闸 = 连续 5 次失败的确凿依赖故障信号，误分类面最小。
- **原上传者身份而非运维 operatorId**：走既有 canWrite + 审计链路，零授权旁路；离职/冻结上传者的文档由授权拒绝自然落 FAILED 终态（DENIED 审计已落）。
- **开关默认关**：重放 = 真实嵌入 API 调用（chunk 量 × 单价），费用红线要求显式开启。

## 影响

- 写集：`DocumentIngestionService`（失败分类接线）、knowledge 侧新 policy 实现与调度器、`DynamicConfigKeyRegistry`（第 10 命名空间 rag.ingest）、测试与文档，≤13 tracked。
- 零改动：KnowledgeReingestRunner、reingest 手动入口、授权/审计链路、resilience 全模块、DependencyRecoveryPolicy 接口签名。
- 状态机新增 PENDING 值：管理端透传展示（无硬过滤），查询/契约零破坏。
