# 规范差异：邻居上下文对无效 chunkIndex 降级

本案只修复 `NeighborContextSupport` 的无效索引拼装分支；正常数字索引、父块优先、查询批次与源引用语义保持不变。

## ADDED Requirements

### Requirement: 无效 chunkIndex 的邻居组装不中断上下文
WHEN 邻居拼装处理命中 metadata 中缺失、非数字或负数的 `chunkIndex`,
系统 SHALL 跳过该命中的前后邻居，仍按原顺序及编号拼入该命中正文；MUST NOT 对空索引拆箱、为负索引取邻居，或因同批其他命中的预取行而混入错误邻居。

#### Scenario: 字段缺失或非数字
GIVEN 父块文本不存在、邻居模式开启，命中的 metadata 无 `chunkIndex` 或其值不是 `Number`
WHEN 经 `ContextBuilder` 生产组装路径拼接上下文
THEN 返回带原 `[n]` 编号和原命中文本的上下文，不抛 `NullPointerException`
AND 该命中不产生邻居查询目标、不拼任何前后邻居，其他有效命中的组装不受影响

#### Scenario: 负索引与同批有效命中
GIVEN 同文档的一条负索引命中和一条有效索引命中，后者预取的邻居行包含索引 0
WHEN 两条命中在同一批次拼装
THEN 负索引命中只有自身文本，不得借用索引 0 作为后置邻居
AND 有效命中仍按原前后邻居、同页优先及候选顺序拼装

#### Scenario: 既有有效路径
GIVEN 命中索引为 0 或正整数，或父块文本已可用，或邻居开关关闭
WHEN 同一输入在修复前后组装
THEN 正常邻居的查询目标/输出、父块优先、纯文本降级及 `[n]` 编号保持不变
AND 不改变批查失败时的有界回查、跨文档隔离和邻居异常降级
