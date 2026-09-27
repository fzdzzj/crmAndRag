# 规范差异：knowledge-base-authorization 成员可见集有效库边界

相对现行授权行为，本案只收窄失效成员引用所产生的可见 ID；有效 KB 的 owner/PUBLIC/member 与超管语义、scope 解析及返回顺序不改。

## MODIFIED Requirements

### Requirement: 成员授权只接受有效知识库实体
WHEN 计算用户可见或 scope 内授权的知识库 ID,
系统 SHALL 只把仍存在且未逻辑删除的 `knowledge_base` 纳入结果；成员行本身也必须未删除。owner、PUBLIC、成员的有效 ID 按原相对顺序加入并去重，空 scope 仍表示全部可见，有效 scope 只做交集；超管仍只看到未删库。有效库数增长时成员有效性校验 MUST NOT 退化为每成员一次查库或无界 IN；校验失败 MUST NOT 回退成包含失效 ID 的结果。

#### Scenario: 活库成员与重叠来源
GIVEN 用户为有效私有库的成员，同时也是另一公共库的 owner，且某库同时来自 owner/PUBLIC/member
WHEN 计算可见集、单库 scope 和空 scope
THEN 这些有效库依既有来源顺序返回且每个 ID 最多一次
AND 有效文件列表和检索结果不因修复失效引用而被误删

#### Scenario: 软删库仍有有效成员和残留数据
GIVEN 库已逻辑删除而成员行未删，上传文件、切片或向量可能仍存在
WHEN 该用户请求可见集、该库单库 scope、文件列表或检索
THEN 失效库 ID 不得被授权；文件与检索路径不得以该 ID 返回残留内容或向量命中
AND 真库红绿测试区分“返回内容”与“仅发生失效库查询”两种证据级别

#### Scenario: 孤儿成员引用与授权查询故障
GIVEN 成员行引用不存在的库，或验证成员引用所需查询失败
WHEN 计算可见集
THEN 孤儿 ID 不得返回；查询故障不得放宽授权范围
AND 不将暂时故障伪装为可见所有库

### Requirement: 单库读写判定不接受显式软删实体
WHEN 调用 `canRead` 或 `canWrite` 且传入实体已显式标记 `isDeleted=true`,
系统 SHALL 拒绝授权，即便调用者提供了 owner、PUBLIC 或 member 身份；传入 `null` 或正常活动实体时保留既有判定口径。

#### Scenario: 已删实体被直接传入
GIVEN 传入实体标记软删且用户为 owner 或超管
WHEN 调用 `canRead` 或 `canWrite`
THEN 两个判定均为拒绝

#### Scenario: 正常实体与既有角色矩阵
GIVEN 传入未删或未显式标记删除的活动实体
WHEN owner、PUBLIC 读者、READER、EDITOR、非成员或超管分别请求读写
THEN 结果与原读写矩阵一致，PUBLIC 不会自动获得写权限

## ADDED Requirements

### Requirement: 安全修复必须先取得请求路径红灯
WHEN 处理成员引用失效库的安全缺口,
测试 SHALL 使用生产迁移/mapper 与本地假数据，先在修复前记录中央集合和文件/稀疏/向量下游的真实结果，再用同一数据验收修后；MUST NOT 将静态推断当作已经实证的泄露，也不得改写旧授权性能报告的原始数字。并发删除发生在授权后读前的情形须独立标记为未验证或另案，不宣称本修复提供事务级即时撤权。

#### Scenario: 稳定状态红绿
GIVEN 活库与失效库分别留有可区分的文件、切片或向量
WHEN 先运行修前再运行修后的相同路径
THEN 保存失败/成功断言和下游调用证据，并确认活库结果未退化
AND 旧基准行为断言更新到修后语义，历史测量数字原样保留

#### Scenario: 环境或证据不完整
GIVEN Docker/本地镜像、真库反例或必要门禁不具备
WHEN 尝试裁决修复
THEN 明确记录未测或阻断，不下载镜像、不调用真实模型、不强行提交合并
AND 不以合成授权集合结果替代请求级证据
