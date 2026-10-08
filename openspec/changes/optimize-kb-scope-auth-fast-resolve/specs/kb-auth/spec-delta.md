# 增量契约规范：知识库指定 scope 授权快速收敛（optimize-kb-scope-auth-fast-resolve）

## 1. 行为契约增量规范

### 契约 1：指定 scope 定向收敛（Directed Resolution）
- **GIVEN** `requestedScopes` 非空且含至少一个可解析为数字的 kbId；
- **WHEN** 调用 `authorizedKnowledgeBaseIds(user, requestedScopes)`；
- **THEN** 不触发 `visibleKnowledgeBaseIds` 的全量枚举（普通用户 ≤2 条带 `id IN` 的定向 SQL、超管 1 条），返回集合语义恒为 `requested ∩ visible`。

### 契约 2：空 scope 全量路径不变（Null Scope Fallback）
- **GIVEN** `requestedScopes` 为 null 或空；
- **WHEN** 调用 `authorizedKnowledgeBaseIds(user, requestedScopes)`；
- **THEN** 行为与 master@b8c2116 完全一致（走 `visibleKnowledgeBaseIds` 全量路径，含超管直通与普通用户三组 SQL）。

### 契约 3：授权不放大（No Privilege Amplification）
- **GIVEN** 任一 requested kbId 不在用户可见集合（非 owner、非 PUBLIC、非 active 成员、库不存在）；
- **WHEN** 定向分支计算结果；
- **THEN** 该 id 必不在返回集合中；非数字 scope 沿用容错丢弃，不放大授权；成员定向查询的 active 过滤语义与 `selectActiveKnowledgeBaseIdsByUserId` 逐条等价。
