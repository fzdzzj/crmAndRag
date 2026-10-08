# 变更提案：知识库管理端点写权限判定批量化消解 N+1（optimize-knowledge-base-write-auth-batching）

## 1. 背景与问题定义
在知识库管理端点 `GET /knowledge/bases`（`KnowledgeAdminController.listBases` -> `KnowledgeAdminService.listBases`）中：
1. 接口首先调用 `authorizationService.visibleKnowledgeBaseIds(user)` 获取当前用户所有可见知识库 ID 集合；
2. 随后通过 `knowledgeBaseMapper.selectList` 批量加载对应的 `KnowledgeBaseEntity` 列表；
3. 但在组装 `KnowledgeBaseVO` 列表时，对列表中的每一个知识库实体逐行调用 `authorizationService.canWrite(b, user)`：
   ```java
   vo.setCanWrite(authorizationService.canWrite(b, user));
   ```
4. 在 `KnowledgeBaseAuthorizationService.canWrite` 内部，若当前用户非超管且非该库所有者（例如 PUBLIC 库或作为成员加入的知识库），将逐行执行独立 SQL 查询：
   ```java
   memberMapper.selectCount(
       new QueryWrapper<KnowledgeBaseMemberEntity>()
           .eq("knowledge_base_id", knowledgeBase.getId())
           .eq("user_id", user.userIdRef())
           .in("member_role", "OWNER", "EDITOR")) > 0;
   ```
5. 若用户可见列表中包含 N 个非本人所有的公共库或协作库，便会在单次请求中触发 **N 次独立的 `selectCount` 数据库往返**，构成典型且严峻的 N+1 查询性能热点。

## 2. 改进方案与架构设计
1. **服务层新增批量写权限判定能力**：
   在 `KnowledgeBaseAuthorizationService` 中新增批量判定方法：
   `public Set<Long> resolveWritableKnowledgeBaseIds(UserContext user, List<KnowledgeBaseEntity> bases)`：
   - 超管（`user.isSuperAdmin()`）：直接内存返回所有未软删库 ID，**零查询**；
   - 负责人匹配（`Objects.equals(b.getOwnerUserId(), user.userIdRef())`）：内存直接识别为有权，**零查询**；
   - 剩余待查库列表：使用单一 `IN` 批量查询收敛：
     ```java
     memberMapper.selectList(
         new QueryWrapper<KnowledgeBaseMemberEntity>()
             .in("knowledge_base_id", needCheckIds)
             .eq("user_id", user.userIdRef())
             .in("member_role", "OWNER", "EDITOR")
             .select("knowledge_base_id"))
     ```
   - 将所有符合条件的 `knowledge_base_id` 汇聚为 `Set<Long>` 返回。
2. **编排层无缝接入**：
   在 `KnowledgeAdminService.listBases()` 中，先调用 `resolveWritableKnowledgeBaseIds(user, bases)` 获得可写集合，随后流式映射中以 `vo.setCanWrite(writableIds.contains(b.getId()))` 代替原逐行 SQL 探测。
3. **收益对比**：
   - 原实现：1（查可见库）+ 1（查实体）+ N（逐行成员查权）= 2 + N 次 SQL；
   - 新实现：1（查可见库）+ 1（查实体）+ 1（批量成员查权，全为自有库或超管时为 0）= 最多 3 次 SQL；
   - 彻底将随知识库数量线性递增的 O(N) 数据库往返降为 O(1) 常数级。

## 3. 约束边界与非目标
- **零 DDL 变更**：不修改 `src/main/resources/db/migration/` 下的任何迁移脚本；
- **零实体改动**：不修改 `KnowledgeBaseEntity`、`KnowledgeBaseMemberEntity` 或任何 POJO；
- **零新依赖**：不引入任何第三方依赖，纯 JVM + MyBatis-Plus 原生组合；
- **行为 100% 保持等价**：超管、owner、EDITOR、VIEWER、非成员、显式软删（`isDeleted=true`）实体的判定结论与原 `canWrite` 完全一致；原单实体 `canWrite` 方法保留不变以兼容上传等单点校验。

## 4. 验证与验收标准
1. `KnowledgeBaseAuthorizationServiceTest` 新增专属单测覆盖超管、自有库、成员编辑、普通只读、软删库等批量场景，断言调用 `selectList` 恰好 1 次且无 `selectCount` 产生；
2. `KnowledgeAdminServiceTest` 验证 `listBases` 在多库场景下的 `canWrite` 批量赋值与正确性；
3. 本地全套单测与静态分析四门禁（PMD / SpotBugs / Checkstyle / Spotless）0 违规通过；
4. 基线台账增量更新并验证通过，换行与写集守卫通过。
