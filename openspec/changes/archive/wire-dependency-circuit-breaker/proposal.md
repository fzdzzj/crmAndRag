# 提案：wire-dependency-circuit-breaker（卡 P-u）

## 为什么
`DependencyResilienceExecutor`（platform/resilience，按依赖名隔离的连续失败计数 + 熔断开闸 + 指数退避重试 + `DependencyFailureType` 四态分类）已合入骨架但**零生产消费方**——设计意图覆盖的四类外呼（模型 chat/embed/vision、Qdrant 向量库、MinIO 对象存储）全部裸奔：DashScope 连环故障时检索/摄取请求逐个排队等超时，无快速失败；Qdrant/MinIO 宕机时每请求仍打满各自重试与超时上限。骨架的 `DependencyUnavailableException`/`DependencyFailureClassifier`/`DependencyRecoveryPolicy` 与 `PlatformErrorCode.DEPENDENCY_UNAVAILABLE(96008)` 均为已建零用的预留物。

## 改什么
把熔断器接入四类外呼的收敛门面，owner 三项拍板（2026-10-07）定形：
1. **四类全零自动重试**——新增 `executeNoRetry` 入口（OPEN 检查 → 单次执行 → 失败计数 → `CALL_FAILED` 上抛），熔断只做计数+开闸快速拒绝；Qdrant 内建 3 次重试保留不动（防 3×3 双重放大）；每请求物理外呼次数与接线前逐路等价，费用与时延零风险。
2. **OPEN 拒绝转回现有异常**——门面内把 `DependencyUnavailableException` 转回 `IllegalStateException`/`StorageException`（既有消息格式、cause 透传），下游与全局异常处理器零改动，零契约变更；熔断状态仅经 Micrometer 指标暴露（`dependency.call{result}` / `dependency.circuit.{rejected,opened,open}`，按依赖名打标）。
3. **四类一次全接**——模型三路（`model-chat`/`model-embed`/`model-vision`，P-s 超时护栏保留在内层）、Qdrant 三操作（`vector-qdrant`）、MinIO 三方法（`storage-minio`）。

## 不改什么
流式路（`streamChat`，与 P-s 口径一致）、`exists()` 探针（失败按不存在语义）、`probe()`（健康探针须真实探测）、`initialize()`（启动失败不阻断）、InMemory 两实现（本地无外呼）、`EmbeddingService`（薄委托，防双重包装）、Qdrant 内建重试、P-s 超时语义（快速失败/真中断/零值不限时）、`execute` 既有重试能力（保留，本卡不启用）、任何 OpenAPI 契约与错误响应、96008 错误码（继续预留）、配置面（不新增配置键，用骨架默认阈值 5/开 30s）。

## 费用红线
零自动重试 = 零调用放大：熔断不改变任何路径的物理外呼次数。测试零真实模型调用（`DASHSCOPE_API_KEY` 置空），零 API 费用增量。

## 后续卡方向（非本卡）
按依赖逐类启用重试（`execute` 既有能力）、熔断参数动态配置化、半开探测、`DependencyRecoveryPolicy` 摄取路 PENDING/FAILED 恢复重放、96008 错误码启用。
