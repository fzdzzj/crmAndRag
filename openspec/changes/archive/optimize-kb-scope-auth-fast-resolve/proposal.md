# 变更提案：知识库指定 scope 授权快速收敛（optimize-kb-scope-auth-fast-resolve）

## 1. 背景与问题定义
`KnowledgeBaseAuthorizationService.authorizedKnowledgeBaseIds(user, requestedScopes)`（master@b8c2116 实测 L172-187）：
1. 恒先调用 `visibleKnowledgeBaseIds(user)` 全量枚举——普通用户 3 组 SQL（自有库 id / PUBLIC 库 id / 成员库 id），超管 1 组全库 id；
2. 再对 requestedScopes 内存过滤求交集（L184）；
3. 指定单 kbId 的场景（`KnowledgeAdminService.listFiles` L126 / `resolveAuthorizedKnowledgeBaseIds` L248）与检索多值 kbScope（`KnowledgeRetrievalServiceImpl` L214）都为「过滤到 ≤1 个或少数几个 id」付出全量枚举成本；
4. 随库数量增长，每次定向请求的授权计算成本线性上涨，属可收敛的固定浪费。

## 2. 改进方案
`authorizedKnowledgeBaseIds` 内部对「requestedScopes 非空且解析出有效 id」走定向分支，空/null scope 保留原全量路径：
1. 解析 requested ids（保留非数字容错，空解析集返回空表）；
2. 超管：一条 `select id where id IN (requested)` 存在性收敛；
3. 普通用户：一条 `id IN (requested) AND (owner_user_id = ? OR visibility = 'PUBLIC')` + 一条成员定向查询（active 语义与 `selectActiveKnowledgeBaseIdsByUserId` 逐条等价）；
4. 返回集合语义恒为 `requested ∩ visible`，顺序改为 requested 序（三处消费方亲核零顺序依赖：单值场景仅判空+eq、多值场景 IN 查询）；
5. SQL 次数：普通用户 3 组全表枚举 → 2 条 IN 定向；超管 1 条。

## 3. 约束边界与非目标
- 零 DDL、零实体/POJO/VO 改动、零 OpenAPI 契约变更、零新依赖；
- `visibleKnowledgeBaseIds` 方法本体与语义零改动（空 scope 全量路径原样）；
- 不放大授权：任何分支返回集合 ⊆ 原 `requested ∩ visible`；
- 非目标：`visibleKnowledgeBaseIds` 本体优化、检索链路其他瓶颈。

## 4. 验证与验收标准
1. 既有 `KnowledgeBaseAuthorizationServiceTest` 扩展定向单测，红绿协议：指定 scope 时 `knowledgeBaseMapper.selectList` ≤2 次断言在旧形状 3 次上必红，实现后转绿；
2. 覆盖：owner/PUBLIC/成员三来源命中与皆无、非数字容错、超管存在性收敛、多值交集、空/null scope 全量回归；
3. 全量单测只增不减（928→928+k）、四静态门禁 0 违规、基线真实写回、三守卫与 136 条自测全绿；
4. ArgumentCaptor 断言定向查询 SQL 片段含 `id IN`。
