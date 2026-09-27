# 知识库成员可见集有效库边界：安全修复评估（fix-knowledge-base-member-visibility-boundary）

- 日期：2026-09-27
- 工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（权威）｜起点 HEAD：`fba022b801b2cd729b4773c8907e744d50e5c5ab`
- 修复提案：`spec/changes/fix-knowledge-base-member-visibility-boundary/`（proposal.md / tasks.json / specs/knowledge-base-authorization/spec-delta.md）
- 修复入口：[KnowledgeBaseMemberVisibilityBoundaryIT](../../src/test/java/com/slz/crm/integration/knowledge/KnowledgeBaseMemberVisibilityBoundaryIT.java)（默认随 failsafe 运行，7 用例）
- 关联历史报告：[docs/knowledge-base-scope-auth-baseline.md](knowledge-base-scope-auth-baseline.md)（其 §9 单列本缺口；§12 为本案追加的修复索引，历史数字原样保留、不予追认）

## 0. 结论

**安全裁决：修前红灯成立且已定级；中央根因修复后绿灯成立；合法路径无回归。** 修复前，非超管可见集把「库已软删但成员行存活」
与「成员行指向不存在的库」的 ID 一并纳入，且该集合被下游真取用：`KnowledgeAdminService.listFiles` 真的**返回了失效库的残留文件**，
稀疏召回真的**返回了失效库切片的文本内容**，向量路真的**向失效库发起了 search**。修复把成员来源限定为「真实存在且未软删的库」，
并对**显式传入** `isDeleted=true` 实体拒绝读写；相同种子重跑，失效/孤儿 ID 不再授权，残留文件与内容不再从该路径返回，合法路径未退化。

**限定**：本案只保证**稳定状态**下成员来源为有效库。**授权之后、下游读之前**的并发软删竞赛**未被本案自动解决**，
因此本案**不宣称即时撤权、不宣称生产已发生泄露**，也不新增任何性能结论。

## 1. 证据分级（严格三档，不得混称）

本案全部红/绿断言按下列三档分别取证，禁止把弱档证据写成强档：

| 档位 | 含义 | 本案对应断言 |
| --- | --- | --- |
| A｜仅集合多出 ID | 授权集合里出现不该有的 ID，未证明被下游使用 | `memberSourcesMustExcludeSoftDeletedAndOrphanKnowledgeBases` |
| B｜只搜索了失效 KB | 下游确实用该 ID 构造了查询/过滤器，但未证明返回内容 | `vectorRouteMustNotSearchInvalidKnowledgeBases`（记录 search filter 的 KB 集合） |
| C｜实际返回文件/内容 | 下游真的把失效库数据吐回给调用方 | `listFiles*`（返回文件名）、`sparseRecallMustNotReturnChunksOfInvalidKbs`（返回切片文本） |

历史基线（`docs/knowledge-base-scope-auth-baseline.md`）只有 A 档证据（`orphan_included=true` / `member_of_softdeleted_included=true`）；
本案补齐 B、C 档，所以「返回内容」这句话是有实测支撑的，而不是从集合宽窄推断出来。

## 2. 环境与前提

```
evidence_class = local-real-mysql + production-flyway + production-mapper + production-service
docker         = available (Server Version 29.6.2)
mysql image    = mysql:8.0.36（IT）/ mysql:8.0（benchmark），均为本机已有，仅 inspect 只读预检，不 pull
flyway         = 生产迁移链 classpath:db/migration，14 个迁移，落点 v28
credentials    = 无（一次性容器内帐号 crm/crm_kb_pwd）；未读取/未打印任何真实密钥；不连业务库
models         = 未调用任何真实模型；DASHSCOPE_API_KEY 置空
```

- Docker 与本地镜像**实测在线**（见上）；缺前提即 `assumeTrue` 跳过并记「未测」，不 pull。
- 向量路使用**确定性假 `CrmVectorStore`**（`RecordingVectorStore`：记录 `request.filter().knowledgeBaseId`，固定返回空命中），
  `EmbeddingService`/`RetrievalQueryRewriteService`/`Bm25Scorer` 为 Mockito mock，`DynamicConfigService` 返回 null（走默认），
  知识库授权、`KnowledgeAdminService`、`SparseRecallService`、`DocumentVectorChunkMapper` 全为**生产实现**。

## 3. 种子与「数据残留」前提

同一一次性库内固定构造（修复前、修复后**同一份种子**）：

| ID | 知识库 | 可见来源 | 残留数据 |
| --- | --- | --- | --- |
| 1 | KB1 活库 PRIVATE | owner=user:50 | `doc-a` + CHILD 切片（文本含 XR-900） |
| 2 | KB2 活库 | member=user:50（READER） | `doc-b` + 切片 |
| 3 | KB3 **is_deleted=1** PRIVATE | 成员行**存活**（user:50 READER） | `doc-c` + 切片（**未删**） |
| 4 | KB4 活库 PUBLIC | PUBLIC | `doc-d` + 切片 |
| 5 | KB5 活库 PRIVATE（他人） | 无 | `doc-e` + 切片 |
| 900000000 | **不存在**（孤儿引用） | 成员行指向它（user:50 READER） | `doc-o` + 切片（**未删**） |

- 成员行：`(kb=2,'user:50','READER',0)`、`(kb=3,'user:50','READER',0)`、`(kb=900000000,'user:50','READER',0)`。
- `uploaded_file` 6 条、`document_vector_chunk` 6 条（均为 CHILD），切片文本全部含 `XR-900`，便于稀疏 FULLTEXT(ngram) 命中。
- **「数据残留」是本红的必要条件**：只有失效库仍留有未删文件/切片时，下游才可能真的返回内容；本案显式构造了这一前提。

## 4. 修前红灯原文（逐路径）

命令（修前，`git stash` 不需要——直接对未修复代码跑；输出存 `%TEMP%\kbmb-red-boundary-it.log`）：

```
mvn -o -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=KnowledgeBaseMemberVisibilityBoundaryIT
```

汇总行：

```
[ERROR] Tests run: 7, Failures: 6, Errors: 0, Skipped: 0, Time elapsed: 41.01 s <<< FAILURE! -- in com.slz.crm.integration.knowledge.KnowledgeBaseMemberVisibilityBoundaryIT
```

逐路径原文（A/B/C 分档标注）：

```
# A｜仅集合多出 ID
memberSourcesMustExcludeSoftDeletedAndOrphanKnowledgeBases:150
  非超管可见集：owner(KB1) → PUBLIC(KB4) → member(KB2)，且不含软删库 KB3 与孤儿 900000000
  ==> expected: <[1, 4, 2]> but was: <[1, 4, 2, 3, 900000000]>

# C｜实际返回文件（单库 scope：listFiles(3L)）
listFilesOfInvalidKbMustNotReturnResidualFile:178
  软删库 KB3 的残留文件不得被列出 ==> expected: <[]> but was: <[doc-c]>

# C｜实际返回文件（空/全部 scope：listFiles(null)）
listFilesAllMustExcludeResidualFilesOfInvalidKbs:187
  仅活库 doc-a(KB1)/doc-b(KB2)/doc-d(KB4)；doc-c(KB3)/doc-o(孤儿) 不得出现
  ==> expected: <[doc-a, doc-b, doc-d]> but was: <[doc-a, doc-b, doc-c, doc-d, doc-o]>

# C｜实际返回内容（稀疏路：切片文本）
sparseRecallMustNotReturnChunksOfInvalidKbs:205
  稀疏路只允许返回活库切片 ==> expected: <[doc-a, doc-b, doc-d]> but was: <[doc-a, doc-b, doc-c, doc-d, doc-o]>

# B｜只搜索了失效 KB（向量路 search filter）
vectorRouteMustNotSearchInvalidKnowledgeBases:231
  软删库 KB3 不得进入向量路 search filter，实测=[1, 4, 2, 3, 900000000] ==> expected: <false> but was: <true>

# C｜显式软删实体的读写判定（canRead）
explicitlyDeletedEntityMustBeDeniedForReadAndWrite:277
  显式软删实体的 owner 读判定必须拒绝 ==> expected: <false> but was: <true>
```

- 第 7 个用例 `activePathsAndScopeSemanticsMustBePreserved`（合法路径回归）**修前即绿**——它证明红灯不是种子整体坏掉造成的假阳性。
- 修前 `listFiles(null)` 与稀疏路都**返回了** `doc-c`（KB3 软删库残留）与 `doc-o`（孤儿库残留），故本红属 **C 档**：
  确实经这条授权链把失效库数据返回给了调用方。向量路是 **B 档**：`RecordingVectorStore` 记录了 filter 里含 `3` 与 `900000000`，
  即**向失效 KB 发起了检索**；因假 store 固定返回空命中，本案**不**声称向量路返回了向量内容，只声称「发生了失效库检索」。

## 5. 修后绿灯原文（同一份种子）

命令与修前同形，输出存 `%TEMP%\kbmb-green-boundary-it.log`：

```
mvn -o -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=KnowledgeBaseMemberVisibilityBoundaryIT
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 34.29 s -- in com.slz.crm.integration.knowledge.KnowledgeBaseMemberVisibilityBoundaryIT
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

修后各路径结果（断言口径）：

| 路径 | 修前 | 修后 |
| --- | --- | --- |
| 授权集合（A） | `[1, 4, 2, 3, 900000000]` | `[1, 4, 2]`（软删库 3、孤儿 900000000 被剔除；owner→PUBLIC→member 相对顺序与去重保留） |
| 单库 scope 3 / 孤儿 / 5 | 3 返回 `[doc-c]` | 均为空集 |
| `listFiles(null)`（C） | `[doc-a, doc-b, doc-c, doc-d, doc-o]` | `[doc-a, doc-b, doc-d]` |
| 稀疏召回内容（C） | `[doc-a, doc-b, doc-c, doc-d, doc-o]` | `[doc-a, doc-b, doc-d]` |
| 向量 search filter（B） | 含 `3` 与 `900000000` | 不含两者 |
| `canRead/canWrite(软删实体)`（C） | `true` | `false`（owner 与超管身份均拒绝） |
| 超管可见集 | `[1, 2, 4, 5]`（sorted） | 不变 |
| 合法 owner/PUBLIC/member、空/重复/无效 scope | 见下 §6 | 不变 |

## 6. 合法路径回归（绿）

- **IT 第 7 用例** `activePathsAndScopeSemanticsMustBePreserved`：有效库（KB1/KB2/KB4）文件列表与检索仍返回 `doc-a/doc-b/doc-d`；
  重叠成员（同一库既是 owner/PUBLIC/member）只出现一次；重复/无效/空 scope 语义保持；超管可见集不变。
- **单测** `KnowledgeBaseAuthorizationServiceTest`：7/7 绿。其中新增 3 条锁边界——
  - `explicitlyDeletedEntityShouldBeDeniedForReadAndWrite`：显式软删实体读写均拒绝；
  - `visibleIdsShouldUseSingleActiveMemberQueryAndKeepSourceOrder`：`verify(memberMapper, times(1)).selectActiveKnowledgeBaseIdsByUserId(...)`
    且 `verify(memberMapper, never()).selectList(any())` —— **证明是单条有界 JOIN 查询，没有 N+1、没有无界 IN**；
  - `memberLookupFailureMustNotWidenAuthorization`：成员校验查询抛异常时 `assertThrows(IllegalStateException.class)` —— **失败关闭，不回退宽授权**。
- **基线守卫** `KnowledgeBaseScopeAuthBaselineGuardTest`：7/7 绿（纯 JVM，随 surefire 默认运行）。
- **基线入口** `KnowledgeBaseScopeAuthBaselineBenchmark`（真库，`KB_SCOPE_AUTH_MEASURE=1`）修后重跑：

```
KBSCOPE scale: scale=4   subject_visible=4   admin_visible=6
KBSCOPE snapshot: scale=4 subject_visible_ids=[100000, 100005, 100001, 100002] orphan_included=false member_of_softdeleted_included=false softdel_excluded=true
KBSCOPE scale: scale=128 subject_visible=97  admin_visible=130
KBSCOPE scale: scale=512 subject_visible=385 admin_visible=514
KBSCOPE done: threadlocal_clean=true failures=0
```

  非超管可见数由 `3*(N/4)+3` 收敛为 `3*(N/4)+1`（剔除的正是软删库成员引用与孤儿引用两项）；超管口径、scope 语义、去重不变。
  旧报告与旧日志的原始数字**未改写**，仅在 `docs/knowledge-base-scope-auth-baseline.md` **追加** §12 修复索引并在 benchmark javadoc 标注**历史不可追认**。

## 7. 修复内容（根因处最小改动，无调用侧改动）

1. `KnowledgeBaseMemberMapper#selectActiveKnowledgeBaseIdsByUserId`（新增，一次有界 JOIN）：

```
SELECT m.knowledge_base_id
FROM knowledge_base_member m
JOIN knowledge_base kb ON kb.id = m.knowledge_base_id AND kb.is_deleted = 0
WHERE m.is_deleted = 0
  AND m.user_id = #{userId}
ORDER BY m.id
```

  单条 SQL、单次 `userId` 参数（**无按成员循环、无无界 IN**），`ORDER BY m.id` 保留成员段原有相对顺序。
2. `KnowledgeBaseAuthorizationService#visibleKnowledgeBaseIds`：成员分支由「只查成员表」改为调用上述有界查询。owner/PUBLIC 段与
   `LinkedHashSet` 顺序去重、超管路径、`authorizedKnowledgeBaseIds` 的空 scope / `Long.valueOf` 解析语义**均未改**。
3. `KnowledgeBaseAuthorizationService#canRead`/`canWrite`：条件加上 `!Boolean.TRUE.equals(knowledgeBase.getIsDeleted())`，
   对**显式传入的软删实体**一律拒绝；正常实体角色矩阵不变。
4. **未改**：端点权限、冻结契约、迁移链/索引、动态配置、模型 Provider、重试/线程池/JVM、费用开关、`D:\code\crmAndRag`（只读）、
   受保护未跟踪 `docs/backend-optimization-candidates.md`。

## 8. 未解决的并发边界（不得扩大成一致性声明）

- **授权后并发软删的竞态**：本案的前置过滤发生在授权集合生成时。若库在**授权之后、下游读之前**被并发软删，可见集不会自动收回该 ID，
  下游仍可能按该 ID 取到当时未删的数据。本案**未**实现读时二次校验或事务边界，因此：
  - **不宣称「即时撤权」**；
  - **不宣称「生产已发生泄露」**（本机一次性真库的残留数据是构造出来的前提，生产是否存在未审计）；
  - 该竞态列为后续审计项，如需更强保证须另案定夺读时校验/事务边界。
- **其它未知项**：生产真实数据基数与残留分布 unknown；生产索引/统计信息 unknown；真实下游调用方是否另行复核 `canRead` unknown。

## 9. 未跑项

- 未做请求端到端/接口级性能度量 —— **本案不新增性能结论**（修复正确性验收，不把新 SQL 冒称优化）。
- 未调用真实模型；未连业务库；未审计生产访问日志；未做生产环境验证。
- 默认 `scripts/merge-gate.sh` 的 `[it]` 阶段按设计跳过（不含 IT）；IT 覆盖以本轮独立 `mvn -o -B -ntp clean verify` 为准。

## 10. 复现命令

```bash
# 定向真库红绿（需本机 Docker + 本地已有 mysql:8.0.36；缺前提 assumeTrue 跳过并记未测）
mvn -o -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=KnowledgeBaseMemberVisibilityBoundaryIT

# 基线入口（真库，显式 opt-in；缺开关/镜像 fail closed，不 pull）
KB_SCOPE_AUTH_MEASURE=1 mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark test

# 纯 JVM 单测与守卫（随默认 surefire 运行）
mvn -o -B -ntp -Dtest=KnowledgeBaseAuthorizationServiceTest test
mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineGuardTest test
```
