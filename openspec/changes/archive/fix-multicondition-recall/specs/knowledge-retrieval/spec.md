# 规范增量：knowledge-retrieval

## ADDED Requirements

### Requirement: 多条件查询零 LLM 拆路召回
系统 SHALL 在查询改写之后、召回之前，用确定性规则把「A后B / A且B」类查询拆成原查询加左右子查询，对每一路做文本召回后融合。拆句 MUST NOT 调用聊天模型，MUST NOT 改变 `KnowledgeRetrievalServiceImpl` 任一构造器签名，MUST NOT 把 `rag.query.multi-query.enabled` 默认改为 true。

未命中启发式的查询 MUST 保持单路、单次 embedding。V1 回退装配（`this.rrfFusion == null`）MUST 仍能融合拆路结果。

#### Scenario: T-14 形拆出三路
- **GIVEN** 查询为「客户主体变更后重新签合同要满足什么条件」
- **WHEN** 拆句器处理
- **THEN** 结果含原句、左句「客户主体变更」、右句「重新签合同要满足什么条件」

#### Scenario: 过短或无分隔不拆
- **GIVEN** 查询无 `后/且/并且/同时`，或分隔一侧汉字不足 4 个
- **WHEN** 拆句器处理
- **THEN** 只返回原查询

#### Scenario: V1 回退态也拆路
- **GIVEN** 检索服务以 6 参兼容构造装配（无稀疏、无 this.rrfFusion）
- **AND** 查询命中拆句启发式
- **WHEN** 执行 retrieve
- **THEN** 左右子查询各自召回并被融合进候选
- **AND** 构造器签名与升级前一致

#### Scenario: 普通查询 embedding 次数不变
- **GIVEN** 查询不会被拆句
- **WHEN** 执行 retrieve
- **THEN** 对该查询只 embedding 一次

### Requirement: 评测复测不覆盖历史锚点
真检索复测 MUST 写入 `docs/rag-quality/baseline-after-multicondition.json`，MUST NOT 覆盖既有 baseline JSON。`SUITE_VERSION` MUST 保持 2.0。未授权 MUST NOT 设置 `RAG_BENCHMARK_REAL=1`。

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 4
- **THEN** 无真基准外呼，HANDOFF 标明待补跑

#### Scenario: 授权后对照 v2
- **WHEN** 纯默认矩阵跑完 54 条
- **THEN** T-14 recall@5 相对 v2 的 0.5 提升（目标 1.0）
- **AND** T-15 / T-16 / T-17 recall 不回退
- **AND** 若 T-14 仍为 0.5 或同组回退，实现方 MUST 停下，不得改黄金块或打开 LLM 多查询
