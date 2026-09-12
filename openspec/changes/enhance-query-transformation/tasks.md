# Tasks — enhance-query-transformation

> 前置确认：本 tasks 全部条目仅在"立项触发条件"满足后执行（见 proposal.md）。

## 1. 多查询生成与融合（方案 07 增强）

- [ ] 1.1 实现：`rag.query.multi-query.enabled`（默认 false）+ `rag.query.multi-query.variants`（默认 3）；改写器产出原始 + N 变体
- [ ] 1.2 N 路并行召回 → RRF 融合（复用提案 2 `RrfFusion`）→ 单一重排；单测断言融合排序与手算一致
- [ ] 1.3 降级链单测：任一路失败 → 已完成路融合；全部失败 → 回退单查询（现行为，既有改写测试不改全绿）
- [ ] 1.4 关闭态单测：`multi-query.enabled=false` 时检索管线与提案 4 完成态行为一致

## 2. HyDE 可选模式（方案 15）

- [ ] 2.1 实现：`rag.query.hyde.enabled`（默认 false）：LLM 假设答案 → 嵌入 → 检索 → 与原查询路 RRF 融合
- [ ] 2.2 隔离断言：假设答案文本不出现在生成上下文与 SourceReference（只用于检索向量）
- [ ] 2.3 失败回退单测：HyDE 生成/嵌入失败 → 回退原查询路，不向调用方抛错

## 3. 衍生问题文档增强（方案 06）

- [ ] 3.1 入库旁路实现（platform async 执行器）：每块生成 2–3 反向问题（`ModelProvider`，上限可配）→ 嵌入 → 向量记录关联原块 chunkId；命中衍生问题 → 回原块，`SourceReference` 指原块
- [ ] 3.2 不阻塞主链单测：衍生问题生成失败/超时 → 入库正常完成，该块退化为普通块（无衍生向量）
- [ ] 3.3 token 计量断言：衍生问题 LLM+嵌入消耗进 `TokenUsageRecorder`
- [ ] 3.4 随 reingest 重跑：重建后衍生问题重新生成（幂等单测：两次重建衍生向量集合一致）
- [ ] 3.5 开关：`rag.query.derived-questions.enabled`（默认 false）；关闭时入库无衍生向量（回退现行为）

## 4. 基线验收（对照最新基线）

- [ ] 4.1 启用多查询后重跑真检索基准：词汇失配类用例 recall@k 提升；全量不回退；TTFT/token 记录增幅并写入验证记录（增幅可接受才默认启用讨论）
- [ ] 4.2 产出 `baseline-after-query.json` 落盘，差异摘要写入本 change 验证记录
- [ ] 4.3 `mvn -B -ntp test` 绿（surefire 计数只增）；`mvn -B -ntp verify` failsafe 不减

## 5. 待定项台账（不实现，仅记录）

- [ ] 5.1 在本 change 目录 `deferred.md` 记录 11/14 的触发条件与届时落点（内容照 proposal.md §4 表）
