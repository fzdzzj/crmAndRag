# 契约差分：知识库批量写权限判定（spec-delta）

## 1. 领域模型与接口契约扩充

### 1.1 `KnowledgeBaseAuthorizationService` 接口增强
```java
/**
 * 批量解析指定知识库集合中当前登录用户具备写权限（OWNER / EDITOR / 超管）的知识库 ID 集合。
 *
 * @param user 当前登录用户上下文，为空时返回空集合
 * @param bases 待检查的知识库实体列表，为空或 null 时返回空集合
 * @return 当前用户具备写权限且未软删的知识库 ID 集合（保持 LinkedHashSet 去重）
 */
public Set<Long> resolveWritableKnowledgeBaseIds(UserContext user, List<KnowledgeBaseEntity> bases);
```

### 1.2 契约规则与时序行为

#### 规则 1：超管全局放行
- **WHEN**: `user.isSuperAdmin() == true`
- **THEN**: 返回 `bases` 中所有 `isDeleted != true` 且 `id != null` 的库 ID 集合，**完全不触发任何成员表 SQL 查询**。

#### 规则 2：所有者直接识别（内存短路）
- **WHEN**: `b.getOwnerUserId()` 等于 `user.userIdRef()` 且 `b.getIsDeleted() != true`
- **THEN**: 直接计入可写 ID 集合，**不为该库执行任何成员表 SQL 查询**。

#### 规则 3：协作库批量查权（消解 N+1）
- **WHEN**: 存在非超管且非当前用户所有的候选库 ID 列表 `needCheckIds`
- **THEN**: 仅发起**一次**批量 `IN` 查询：
  ```sql
  SELECT knowledge_base_id FROM knowledge_base_member
  WHERE is_deleted = 0
    AND user_id = #{user.userIdRef}
    AND member_role IN ('OWNER', 'EDITOR')
    AND knowledge_base_id IN (needCheckIds...)
  ```
  返回记录对应的 `knowledge_base_id` 全部追加到可写 ID 集合。

#### 规则 4：显式软删排除
- **WHEN**: `b.getIsDeleted() == true`
- **THEN**: 无论用户是超管、负责人还是 EDITOR 成员，该库 ID **严禁**进入可写 ID 集合。

#### 规则 5：端点消费一致性
- **WHEN**: 客户端调用 `GET /knowledge/bases`
- **THEN**: `KnowledgeAdminService.listBases()` 必须且仅允许调用一次 `resolveWritableKnowledgeBaseIds`，通过 `writableIds.contains(b.getId())` 设置返回视图的 `canWrite` 属性。
