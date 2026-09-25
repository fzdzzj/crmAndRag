# 规范差异：image-context-cache

本文件约束图片 L2 上下文缓存的生命周期。不改变单条命中的摘要和向量内容。

## ADDED Requirements

### Requirement: 归档会话释放图片缓存
WHEN 会话归档成功,
系统 SHALL 删除该会话的图片上下文缓存。

#### Scenario: 归档成功
GIVEN 会话里有未过期的图片上下文
WHEN 归档把会话状态更新为已归档
THEN 再用同一会话查询缓存
AND 结果为空

#### Scenario: 归档没有发生
GIVEN 会话不存在或已经归档
WHEN 归档方法直接返回
THEN 不清空其他会话的缓存

### Requirement: 缓存不能按会话无限增长
WHEN 写入图片上下文,
系统 SHALL 移除已全部过期的会话，并且外层会话数不超过固定上限。

#### Scenario: 过期会话被移除
GIVEN 一个会话的条目都已超过 TTL
WHEN 向另一个会话写入缓存
THEN 过期会话不再留在外层 Map

#### Scenario: 超过会话数上限
GIVEN 外层会话数已经达到上限
WHEN 写入一个新会话
THEN 只淘汰最久未访问的会话
AND 未过期且最近访问过的会话仍能命中

### Requirement: 单会话命中语义不变
WHEN 会话未归档且条目未过期,
系统 SHALL 继续按会话、图片哈希和问题返回原来的摘要与向量副本。

#### Scenario: 未过期命中
GIVEN 同一会话的条目数量不超过 8 且未过期
WHEN 用相同问题读取
THEN 返回写入时的摘要
AND 返回的向量是副本而不是缓存内部数组
