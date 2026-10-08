# 增量契约规范：外部依赖熔断接线（wire-dependency-circuit-breaker）

## 1. 行为契约增量规范

### 契约 1：连续失败计数与开闸快速拒绝（Counting and Fast-Fail on OPEN）
- **GIVEN** 某依赖名（`model-chat` / `model-embed` / `model-vision` / `vector-qdrant` / `storage-minio`）下连续 5 次逻辑调用失败；
- **WHEN** 第 6 次对该依赖的调用发生（开闸 30s 窗口内）；
- **THEN** 调用不执行底层外呼，经门面转换后以该门面既有异常类型快速失败（模型/向量库 = `IllegalStateException`，对象存储 = `StorageException`），`dependency.circuit.rejected` 计数 +1；窗口到期后熔断自动关闭，下一调用真实执行。

### 契约 2：零自动重试（Zero Auto-Retry）
- **GIVEN** 熔断接线生效；
- **WHEN** 任意接线面外呼失败；
- **THEN** 每请求物理外呼次数与接线前逐路等价：模型路单次（超时护栏保留在内层，`timeout-seconds=0` 不限时语义不变）、Qdrant 路保留内建 3 次退避重试（本卡零改动）、MinIO 路单次；不引入任何退避等待或重试放大（费用与时延红线）。

### 契约 3：异常类型契约不变（Exception Contract Preserved）
- **GIVEN** 熔断开闸或计数失败上抛；
- **WHEN** 异常穿过接线门面（`ModelProviderImpl` / `QdrantVectorStore` / `MinioFileStorageService`）；
- **THEN** 门面外可见类型与消息格式与接线前一致，cause 链透传；`DependencyUnavailableException` 不出现在任何门面的公开抛出面上（契约锁定测试保证）；下游消费方与全局异常处理器零改动。

### 契约 4：不接线面显式排除（Out-of-Scope Faces）
- **GIVEN** 以下调用面；**WHEN** 本卡合入后调用；
- **THEN** 行为与合入前逐字等价：流式 `streamChat`、`MinioFileStorageService.exists`（失败按不存在）、`QdrantVectorStore.probe`（健康探针真实探测）、`QdrantVectorStore.initialize`（启动失败不阻断）、InMemory 向量库/存储实现、`EmbeddingService` 薄委托层。

### 契约 5：指标暴露（Metrics Contract）
- **GIVEN** 熔断接线生效；
- **WHEN** 接线面外呼发生或熔断状态变化；
- **THEN** Micrometer 暴露按依赖名打标的 `dependency.call{result=success|failure}`、`dependency.circuit.rejected`、`dependency.circuit.opened`、`dependency.circuit.open`（gauge），既有 `dependency.retry` 在零重试口径下不增长。

### 契约 6：骨架既有语义保持（Executor Backward Compatibility）
- **GIVEN** `DependencyResilienceExecutor.execute`（带重试入口）与既有 4 条单测；
- **WHEN** 新增 `executeNoRetry` 入口；
- **THEN** `execute` 行为零变化（重试能力保留，本卡生产接线路不启用），`DependencyFailureType` 四态与 `DependencyFailureClassifier` 链上查找语义不变。
