# 规范增量：rag-quality

## ADDED Requirements

### Requirement: 检索质量基准集
系统 SHALL 提供版本化固定的 RAG 基准集，MUST 覆盖文本、表格、图片、词法精确、边界五类用例；任何检索参数调整 MUST 跑同一套基准以指标涨跌判定质量回退。

#### Scenario: 五类用例齐备
- **WHEN** 载入标准基准集
- **THEN** TEXT、TABLE、IMAGE、LEXICAL、EDGE 五类用例均存在且非空
- **AND** 每份质量报告携带基准集版本，跨变更可比较

#### Scenario: 词法精确型召回考察
- **WHEN** 用例问题包含型号/编号/专有名词，且存在向量相似度低但文本精确包含该词的黄金片段
- **THEN** 该片段计入 recall/MRR 判定，作为混合检索补全前后的对照

#### Scenario: 边界用例诚实语义保持
- **WHEN** 边界用例（闲聊/零命中主题）的期望片段为空
- **THEN** "不召回、不伪造引用"记满分，过度检索或伪造引用记零分（D16）

### Requirement: 评测数据准备可复现
系统 SHALL 提供固定评测文档集与幂等入库的数据准备步骤，使黄金片段 id 与实际入库 chunkId 对齐且可重复。

#### Scenario: 幂等重跑
- **WHEN** 同一评测环境重复执行数据准备
- **THEN** 两次产出的 chunkId 集合一致，基准结果可比较

#### Scenario: 占位 id 拒绝
- **WHEN** 黄金集引用的 chunkId 未出现在实际入库结果中
- **THEN** 数据准备显式失败并报出未对齐的 id，不按空集静默继续

### Requirement: 真检索基线报告
系统 SHALL 支持以真实模型与向量库执行基准并落盘 JSON 报告；该执行 MUST 以环境变量门控，未启用时按跳过处理且构建不失败。

#### Scenario: 门控跳过
- **WHEN** 未设置启用变量或模型密钥
- **THEN** 真检索基准按假设跳过（记录 skip），`mvn verify` 不因此失败

#### Scenario: 基线落盘
- **WHEN** 启用门控并跑完基准
- **THEN** 报告含 recall@k、precision@k、MRR、hitRate、citationPrecision、答案要点覆盖、token、TTFT、总延迟、失败率
- **AND** 携带基准集版本与时间戳，落盘到约定路径供后续变更对照
