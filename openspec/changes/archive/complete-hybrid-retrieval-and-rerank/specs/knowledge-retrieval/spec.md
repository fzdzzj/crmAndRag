# 规范增量：knowledge-retrieval

## ADDED Requirements

### Requirement: 语料级稀疏召回
系统 SHALL 提供基于全量切片语料的词法召回路（BM25/全文检索语义），且 MUST 与授权知识库集合和类目过滤同语义；词法精确命中但向量漏召的块 MUST 能进入候选集。

#### Scenario: 词法精确召回
- **WHEN** 查询包含型号、编号或专有名词，且某切片文本精确包含该词而向量相似度低于召回阈值
- **THEN** 该切片经稀疏路进入候选集，并可进入最终 top-K

#### Scenario: 稀疏路授权不可绕过
- **WHEN** 用户仅被授权部分知识库
- **THEN** 稀疏路结果不包含未授权库的切片，伪造 metadata 不能放大授权集合

#### Scenario: 类目过滤
- **WHEN** 检索指定业务类目
- **THEN** 向量路与稀疏路均按类目过滤；类目为空时不过滤

### Requirement: 双路召回融合
系统 SHALL 将向量路与稀疏路召回融合后进入重排，默认采用 RRF，融合模式与参数 MUST 经动态配置可调，并 MUST 保留升级前的加权融合行为作为回退。

#### Scenario: RRF 融合
- **WHEN** 两路对同一切片均有命中
- **THEN** 该片段合得分为各路排名倒数之和（RRF），排序据此重排

#### Scenario: 回退开关
- **WHEN** 动态配置融合模式为加权（weighted）
- **THEN** 检索行为与升级前等价，既有基准用例不回退

### Requirement: 可插拔重排
系统 SHALL 提供重排器抽象，默认实现 MUST 与既有"向量/BM25 归一化加权"行为等价；LLM 重排 MUST 可选启用，且失败、超时或空输出时回退默认实现。

#### Scenario: 默认行为等价
- **WHEN** 重排模式为默认（default）
- **THEN** 同一候选集的重排结果与升级前实现一致

#### Scenario: LLM 重排回退
- **WHEN** LLM 重排启用且调用失败或返回空
- **THEN** 使用默认重排链完成本轮检索，不向调用方抛错

### Requirement: 检索质量不回退
混合检索与重排变更 MUST 以固定基准集验收：词法精确型用例召回提升，全量聚合指标不低于变更前基线。

#### Scenario: 基线对照
- **WHEN** 变更后重跑真检索基准
- **THEN** 词法精确型用例 recall@k 较基线提升，全量 recall@k / hitRate / citationPrecision 不低于基线
