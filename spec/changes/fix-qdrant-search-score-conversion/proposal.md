# 提案：修复 Qdrant 非空搜索命中的分数类型转换

## Why

2026-09-25 本机隔离度量在真实 Qdrant 的非空搜索路径发现 `QdrantVectorStore#search` 将反射返回的 `ScoredPoint.getScore()` 强转为 `Double`。当前仓库使用的 Qdrant client 1.13.0 中 `getScore()` 返回原始 `float`；反射调用装箱为 `Float`，`(Double)` 抛 `ClassCastException`。生产搜索循环只要走到该表达式便无法构造该命中，异常被现有重试器重试三次后包装上抛。旧内存桩及现有 Qdrant 测试没有覆盖该路径。

**已独立验证**：权威树 `master@ac9cbfb` 的生产源码仍为 `(Double) ...getScore()`；本地 client jar 的 `javap` 返回 `public float getScore()`；最小 JVM 反例 `Object score=Float.valueOf(0.75f); (Double)score` 抛同类异常。真实 Qdrant 首轮栈与四轮测量由 `docs/representative-hotpath-measurement.md` 报告，本提案阶段未独立复跑 Docker。报告用测试侧 `RepresentativeHotpathQdrantSearchShim` 绕过了这个点，故报告中的成功搜索**不证明原生产实现可正常搜索**。

## What Changes

- 仅在 `QdrantVectorStore#search` 的分数映射处用数值转换得到冻结契约 `VectorSearchHit.score` 的 `double`（例如 `((Number) reflectScore).doubleValue()`），不直接把 `Float` 强转 `Double`。不改变请求向量、集合、limit、阈值、KB 过滤、payload 解析、分数顺序、超时或现有重试策略。空结果保持空列表。
- 先补能调用**生产 `QdrantVectorStore.search`** 的非空命中回归测试，证明修复前因 `Float` 装箱失败、修复后能返回原分数、chunkId/documentId/text/metadata；另覆盖空命中和正常过滤请求。测试不得调用付费模型；纯 JVM 的直接返回值/反射契约测试优先，同时在本地一次性 Qdrant 上补不经 shim 的端到端非空命中验证。回归测试应防止以后把反射值再写回 `(Double)` 强转。
- 将 `RepresentativeHotpathBenchmark` 改为直接使用修复后的生产 store 搜索，移除仅为这处缺陷存在的 `RepresentativeHotpathQdrantSearchShim`；保留原有 opt-in、模型桩、本地镜像预检和工作量/安全断言。针对生产 store 至少完整运行一轮本机隔离度量以验证同负载搜索、过滤、写入均通；若需性能对比，重新按同一环境/负载采样，不能把历史 shim 数字冒充修复后生产数字。
- 在 `docs/representative-hotpath-measurement.md` §5 追加带日期的修复验证记录：原始四轮是历史 shim 口径，原数字不改；另写独立实测的生产 store 非空搜索结果、命令、剩余限制。如未能运行本地 Qdrant，只写未验证，不声称修复已端到端验证。

## Impact

- **受影响**：`src/main/java/com/slz/crm/knowledge/vector/QdrantVectorStore.java`、测试侧 Qdrant 回归/度量入口、历史 shim 文件、度量报告及本案规格。冻结 `VectorSearchHit` record 和端口签名均不改。
- **不受影响**：知识库权限、SQL/Flyway、动态配置、模型 Provider、前端、依赖、JVM/线程池、费用/生产开关及旧合成基线数字。
- **风险**：强转修复只解决“命中映射的数值类型”根因，不预先保证其他 Qdrant payload、过滤、权限问题不存在；本地容器回归须实际跑过才能作端到端结论。旧度量 shim 的超时/重试与生产不同，不得将历史结果追认为生产基线。
- **边界**：不启用 `RAG_BENCHMARK_REAL` 或 `RAG_VISION_PDF_REAL`，不使用真实 embedding/模型，不下载 Docker 镜像、不 push、不归档。如 Docker 或镜像缺失，不以假端口绿替代生产搜索非空回归结论。

## 验收条件

1. 红绿证据：真实生产搜索非空命中测试在旧表达式下因类型转换失败、改后通过；空命中和过滤语义不回退。纯 JVM 测试可独立运行、默认 surefire 覆盖。
2. 本地预存在的 Qdrant 镜像可用时，明确运行**生产 `QdrantVectorStore.search`** 非空路径，实际返回 `VectorSearchHit` 且检查 KB 过滤、文本及分数。不得以 shim 结果或仅 `javap` 代替。
3. 度量入口仍默认不可发现，模型桩仍零外发，测试门禁/安全反例和本案的定向回归通过；报告历史数值保持不变，追加独立验证记录。
4. merge-gate 与 `git diff --check` 通过、仅本案文件进入提交与合并；不因数值修复扩大到重试策略、并行化或性能优化。
