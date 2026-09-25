# 规范增量：dynamic-config

## ADDED Requirements

### Requirement: rag.retrieval 文档已有键必须可被注册表识别
系统 SHALL 将下列 11 个键登记到既有 `rag.retrieval` 命名空间，MUST NOT 扩展 `DynamicConfigKeyRegistry.NAMESPACES`，MUST NOT 改变各消费点在键缺失时的代码默认值：

- `rag.retrieval.query-rewrite.enabled`（Boolean，默认 true）
- `rag.retrieval.fusion.mode`（String，默认 `rrf`，允许 `rrf`/`weighted`）
- `rag.retrieval.fusion.rrf-k`（Integer，默认 60，最小 1）
- `rag.retrieval.rerank.mode`（String，默认 `default`，允许 `default`/`llm`）
- `rag.retrieval.rerank.vector-weight`（Double，默认 0.60，范围 0~1）
- `rag.retrieval.rerank.bm25-weight`（Double，默认 0.40，范围 0~1）
- `rag.retrieval.rerank.candidate-multiplier`（Integer，默认 4，最小 1）
- `rag.retrieval.rerank.llm.timeout-ms`（Long，默认 3000，最小 1）
- `rag.retrieval.rerank.llm.max-candidates`（Integer，默认 20，最小 1）
- `rag.retrieval.image-text-route-weight`（Double，默认 0.70，范围 0~1）
- `rag.retrieval.image-vector-route-weight`（Double，默认 0.30，范围 0~1）

未列出的 `rag.context.*` / `rag.chunking.*` / `rag.query.*` MUST 保持未注册。仅超管可通过既有动态配置管理入口写入已登记键。

#### Scenario: 11 键可校验
- **GIVEN** 注册表已加载
- **WHEN** 对上表每一键调用 `definitionOf` 与合法值 `validate`
- **THEN** 定义存在且校验通过，默认值与上表一致

#### Scenario: 非法值仍拒绝
- **GIVEN** 注册表已加载
- **WHEN** `fusion.mode` 写入非 `rrf`/`weighted`，或权重写入 1.1，或 `rrf-k` 写入 0
- **THEN** `validate` 失败，不得当作合法动态值

#### Scenario: 未扩命名空间的键仍未知
- **GIVEN** 注册表已加载
- **WHEN** 校验 `rag.query.multi-query.enabled` 或 `rag.context.token-budget`
- **THEN** 仍为未知配置键

#### Scenario: 缺省行为不变
- **GIVEN** 数据库没有覆盖这 11 键
- **WHEN** 检索代码按现有 `get(key, type, default)` 读取
- **THEN** 查询改写仍默认开、融合仍默认 rrf、重排仍默认 default，与登记前一致
