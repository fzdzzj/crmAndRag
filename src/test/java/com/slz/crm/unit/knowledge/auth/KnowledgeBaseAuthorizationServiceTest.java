package com.slz.crm.unit.knowledge.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseVisibility;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 知识库授权矩阵：负责人、公共库、成员编辑与普通成员只读。
 *
 * <p>有效库边界（fix-knowledge-base-member-visibility-boundary 阶段 3）：成员来源只接受有效库（一次查询、无 N+1）、
 * 显式软删实体一律拒绝、成员校验失败不放权。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseAuthorizationServiceTest {
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  @Mock private KnowledgeBaseMemberMapper memberMapper;

  private KnowledgeBaseAuthorizationService authorizationService;

  @BeforeEach
  void setUp() {
    authorizationService = new KnowledgeBaseAuthorizationService(knowledgeBaseMapper, memberMapper);
  }

  @Test
  void publicKnowledgeBaseShouldBeReadableButNotWritable() {
    KnowledgeBaseEntity knowledgeBase = knowledgeBase(100L, "user:2", "PUBLIC");
    UserContext user = user(1L);

    assertTrue(authorizationService.canRead(knowledgeBase, user));
    assertFalse(authorizationService.canWrite(knowledgeBase, user));
  }

  @Test
  void ownerShouldReadAndWrite() {
    KnowledgeBaseEntity knowledgeBase = knowledgeBase(101L, "user:1", "PRIVATE");

    assertTrue(authorizationService.canRead(knowledgeBase, user(1L)));
    assertTrue(authorizationService.canWrite(knowledgeBase, user(1L)));
  }

  @Test
  void memberShouldBeAuthorizedForEditorRole() {
    KnowledgeBaseEntity knowledgeBase = knowledgeBase(102L, "user:2", "PRIVATE");
    lenient().when(memberMapper.selectCount(any())).thenReturn(1L);

    assertTrue(authorizationService.canRead(knowledgeBase, user(1L)));
    assertTrue(authorizationService.canWrite(knowledgeBase, user(1L)));
  }

  @Test
  void unrelatedUserShouldBeDeniedForPrivateKnowledgeBase() {
    KnowledgeBaseEntity knowledgeBase = knowledgeBase(103L, "user:2", "PRIVATE");
    lenient().when(memberMapper.selectCount(any())).thenReturn(0L);

    assertFalse(authorizationService.canRead(knowledgeBase, user(1L)));
    assertFalse(authorizationService.canWrite(knowledgeBase, user(1L)));
  }

  /** 显式 isDeleted=true 的实体：owner/PUBLIC/超管一律拒绝，且不触达任何成员查询。 */
  @Test
  void explicitlyDeletedEntityShouldBeDeniedForReadAndWrite() {
    KnowledgeBaseEntity deletedOwned = knowledgeBase(104L, "user:1", "PRIVATE");
    deletedOwned.setIsDeleted(true);
    assertFalse(authorizationService.canRead(deletedOwned, user(1L)), "软删自有库读判定必须拒绝");
    assertFalse(authorizationService.canWrite(deletedOwned, user(1L)), "软删自有库写判定必须拒绝");

    KnowledgeBaseEntity deletedPublic = knowledgeBase(105L, "user:2", "PUBLIC");
    deletedPublic.setIsDeleted(true);
    assertFalse(authorizationService.canRead(deletedPublic, user(1L)), "软删 PUBLIC 库读判定必须拒绝");

    UserContext admin = new UserContext(1L, 1L, 10L, DataScopeLevel.SELF, "admin");
    assertFalse(authorizationService.canRead(deletedPublic, admin), "软删实体对超管也必须拒绝");
    assertFalse(authorizationService.canWrite(deletedPublic, admin), "软删实体对超管也必须拒绝");
  }

  /** 成员来源改为一次有效库查询：不逐成员查库（selectList 不再被调用），顺序按查询返回值保持并去重。 */
  @Test
  void visibleIdsShouldUseSingleActiveMemberQueryAndKeepSourceOrder() {
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectActiveKnowledgeBaseIdsByUserId("user:1")).thenReturn(List.of(30L, 40L));

    assertEquals(List.of(30L, 40L), authorizationService.visibleKnowledgeBaseIds(user(1L)));

    verify(memberMapper, times(1)).selectActiveKnowledgeBaseIdsByUserId("user:1");
    verify(memberMapper, never()).selectList(any());
  }

  /** 成员有效性校验查询失败必须失败关闭：抛错而不是回退成包含失效 ID（或全部库）的宽结果。 */
  @Test
  void memberLookupFailureMustNotWidenAuthorization() {
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectActiveKnowledgeBaseIdsByUserId("user:1"))
        .thenThrow(new IllegalStateException("member active lookup failed"));

    assertThrows(
        IllegalStateException.class, () -> authorizationService.visibleKnowledgeBaseIds(user(1L)));
  }

  /** 超管批量写判定：所有未软删库直接放行，零成员表查询（超管内存短路）。 */
  @Test
  void resolveWritableIds_superAdminReturnsAllActiveWithoutMemberQuery() {
    KnowledgeBaseEntity ownless = knowledgeBase(201L, "user:9", "PRIVATE");
    KnowledgeBaseEntity publicBase = knowledgeBase(202L, "user:9", "PUBLIC");
    KnowledgeBaseEntity deleted = knowledgeBase(203L, "user:9", "PRIVATE");
    deleted.setIsDeleted(true);
    KnowledgeBaseEntity nullId = knowledgeBase(null, "user:9", "PRIVATE");

    Set<Long> writable =
        authorizationService.resolveWritableKnowledgeBaseIds(
            admin(1L), List.of(ownless, publicBase, deleted, nullId));

    assertEquals(Set.of(201L, 202L), writable);
    verify(memberMapper, never()).selectList(any());
    verify(memberMapper, never()).selectCount(any());
  }

  /** 自有库批量写判定：负责人内存短路，不为自有库发起成员表查询。 */
  @Test
  void resolveWritableIds_ownedBasesShortCircuitWithoutMemberQuery() {
    KnowledgeBaseEntity first = knowledgeBase(211L, "user:1", "PRIVATE");
    KnowledgeBaseEntity second = knowledgeBase(212L, "user:1", "PUBLIC");

    Set<Long> writable =
        authorizationService.resolveWritableKnowledgeBaseIds(user(1L), List.of(first, second));

    assertEquals(Set.of(211L, 212L), writable);
    verify(memberMapper, never()).selectList(any());
  }

  /** 协作库批量写判定：非自有候选库仅一次 IN 批量查询，仅收 OWNER/EDITOR 命中项。 */
  @Test
  void resolveWritableIds_collaborationBasesUseSingleBatchQuery() {
    KnowledgeBaseEntity owned = knowledgeBase(220L, "user:1", "PRIVATE");
    KnowledgeBaseEntity editor = knowledgeBase(221L, "user:9", "PRIVATE");
    KnowledgeBaseEntity viewer = knowledgeBase(222L, "user:9", "PRIVATE");
    KnowledgeBaseEntity outsider = knowledgeBase(223L, "user:9", "PRIVATE");
    KnowledgeBaseMemberEntity editorMember = new KnowledgeBaseMemberEntity();
    editorMember.setKnowledgeBaseId(221L);
    when(memberMapper.selectList(any())).thenReturn(List.of(editorMember));

    Set<Long> writable =
        authorizationService.resolveWritableKnowledgeBaseIds(
            user(1L), List.of(owned, editor, viewer, outsider));

    assertEquals(Set.of(220L, 221L), writable);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<KnowledgeBaseMemberEntity>> captor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(memberMapper, times(1)).selectList(captor.capture());
    String sql = captor.getValue().getSqlSegment();
    assertTrue(sql.contains("knowledge_base_id"), "批量查询必须按候选库批量 IN");
    assertTrue(sql.contains("user_id"), "批量查询必须限定当前用户");
    assertTrue(sql.contains("member_role"), "批量查询必须限定 OWNER/EDITOR 角色");
  }

  /** 显式软删实体严格排除：超管与负责人均不放行，且不触达成员表。 */
  @Test
  void resolveWritableIds_deletedEntityExcludedForAdminAndOwner() {
    KnowledgeBaseEntity deletedOwned = knowledgeBase(231L, "user:1", "PRIVATE");
    deletedOwned.setIsDeleted(true);
    KnowledgeBaseEntity deletedOther = knowledgeBase(232L, "user:9", "PRIVATE");
    deletedOther.setIsDeleted(true);

    Set<Long> adminWritable =
        authorizationService.resolveWritableKnowledgeBaseIds(
            admin(1L), List.of(deletedOwned, deletedOther));
    Set<Long> ownerWritable =
        authorizationService.resolveWritableKnowledgeBaseIds(
            user(1L), List.of(deletedOwned, deletedOther));

    assertTrue(adminWritable.isEmpty(), "软删库对超管也必须排除");
    assertTrue(ownerWritable.isEmpty(), "软删自有库也必须排除");
    verify(memberMapper, never()).selectList(any());
  }

  /** 空值容错：null 用户 / null 列表 / 空列表 / 含 null 元素均安全返回，不放宽授权。 */
  @Test
  void resolveWritableIds_nullAndEmptyInputsAreTolerated() {
    KnowledgeBaseEntity active = knowledgeBase(241L, "user:1", "PRIVATE");

    assertTrue(
        authorizationService.resolveWritableKnowledgeBaseIds(null, List.of(active)).isEmpty(),
        "null 用户必须返回空集合");
    assertTrue(
        authorizationService.resolveWritableKnowledgeBaseIds(user(1L), null).isEmpty(),
        "null 列表必须返回空集合");
    assertTrue(
        authorizationService.resolveWritableKnowledgeBaseIds(user(1L), List.of()).isEmpty(),
        "空列表必须返回空集合");
    assertEquals(
        Set.of(241L),
        authorizationService.resolveWritableKnowledgeBaseIds(
            user(1L), java.util.Arrays.asList(null, active)),
        "含 null 元素时仅返回有效子集");
    verify(memberMapper, never()).selectList(any());
  }

  // ==== optimize-kb-scope-auth-fast-resolve：指定 scope 定向收敛（任务 2，红绿协议） ====

  /**
   * 指定单 scope（owner 命中）：定向分支必须收敛到 ≤2 条定向 SQL（知识库 1 条 + 成员 1 条）且不触达成员全量方法； 旧形状普通用户恒 3 组 SQL（知识库全量枚举
   * ×2 + 成员有效库 ×1），次数断言在其上必红。
   */
  @Test
  void authorizedIds_withScopeResolvesDirectedInsteadOfFullEnumeration() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(List.of(knowledgeBase(11L, "user:1", "PRIVATE")));

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("11"));

    assertEquals(List.of(11L), authorized);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<KnowledgeBaseEntity>> kbCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(knowledgeBaseMapper, times(1)).selectList(kbCaptor.capture());
    String kbSql = kbCaptor.getValue().getSqlSegment();
    assertTrue(kbSql.contains("id IN"), "知识库定向查询必须带 id IN");
    assertTrue(kbSql.contains("owner_user_id"), "知识库定向查询必须带 owner 条件");
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /** 指定单 scope（PUBLIC 命中）：知识库定向查询以 OR 复刻 owner/PUBLIC 双条件，一次覆盖。 */
  @Test
  void authorizedIds_publicHitResolvesThroughDirectedQuery() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(List.of(knowledgeBase(12L, "user:9", "PUBLIC")));

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("12"));

    assertEquals(List.of(12L), authorized);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<KnowledgeBaseEntity>> kbCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(knowledgeBaseMapper, times(1)).selectList(kbCaptor.capture());
    String kbSql = kbCaptor.getValue().getSqlSegment();
    assertTrue(kbSql.contains("id IN"), "知识库定向查询必须带 id IN");
    assertTrue(kbSql.contains("visibility"), "知识库定向查询必须带 PUBLIC 条件");
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /**
   * 指定 scope 成员命中：成员定向查询解析成员库，其命中 id 并入知识库定向查询的 OR 分支， 由 @TableLogic 自动追加的 kb.is_deleted=0 承接 JOIN
   * 的库存活语义（SQL 片段断言 OR id IN）。
   */
  @Test
  void authorizedIds_memberHitResolvesThroughDirectedMemberQuery() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(List.of(knowledgeBase(13L, "user:9", "PRIVATE")));
    lenient().when(memberMapper.selectList(any())).thenReturn(List.of(member(13L)));

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("13"));

    assertEquals(List.of(13L), authorized);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<KnowledgeBaseEntity>> kbCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(knowledgeBaseMapper, times(1)).selectList(kbCaptor.capture());
    String kbSql = kbCaptor.getValue().getSqlSegment();
    assertTrue(kbSql.contains("OR id IN"), "成员命中必须并入知识库定向查询 OR 分支");
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /** 指定 scope 三来源皆无：返回空集合不放大授权，仍守定向预算（知识库 1 条 + 成员 1 条）。 */
  @Test
  void authorizedIds_noHitReturnsEmptyWithinDirectedBudget() {
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("31"));

    assertTrue(authorized.isEmpty());
    verify(knowledgeBaseMapper, times(1)).selectList(any());
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /** 非数字 scope 容错：混入垃圾项仅丢弃垃圾项；纯垃圾项解析为空直接返回空表且零 SQL （旧形状仍全量枚举 3 组，次数断言在其上必红）。 */
  @Test
  void authorizedIds_nonNumericScopesAreToleratedAndGarbageOnlySkipsQueries() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(List.of(knowledgeBase(14L, "user:1", "PRIVATE")));

    assertEquals(
        List.of(14L),
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("abc", "14")));
    assertTrue(authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("abc")).isEmpty());

    verify(knowledgeBaseMapper, times(1)).selectList(any());
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /** 超管存在性收敛：一条 id IN 定向（旧形状为无 IN 的全库枚举，SQL 片段断言在其上必红），零成员表触达。 */
  @Test
  void authorizedIds_superAdminUsesExistenceQueryWithIdIn() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(List.of(knowledgeBase(21L, "user:9", "PRIVATE")));

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(admin(1L), List.of("21", "22"));

    assertEquals(List.of(21L), authorized);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<QueryWrapper<KnowledgeBaseEntity>> kbCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(knowledgeBaseMapper, times(1)).selectList(kbCaptor.capture());
    String kbSql = kbCaptor.getValue().getSqlSegment();
    assertTrue(kbSql.contains("id IN"), "超管定向必须是 id IN 存在性查询");
    assertFalse(kbSql.contains("owner_user_id"), "超管定向不应带 owner 条件");
    verify(memberMapper, never()).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /**
   * 多值 scope 交集：返回 requested ∩ visible 且顺序改 requested 序 （mock 返回序故意倒置为 33→31，断言仍按 requested 序输出
   * 31→33）。
   */
  @Test
  void authorizedIds_multiValueIntersectsAndKeepsRequestedOrder() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(
            List.of(
                knowledgeBase(33L, "user:9", "PRIVATE"), knowledgeBase(31L, "user:1", "PRIVATE")));
    lenient().when(memberMapper.selectList(any())).thenReturn(List.of(member(33L)));

    List<Long> authorized =
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of("31", "32", "33"));

    assertEquals(List.of(31L, 33L), authorized);
    verify(knowledgeBaseMapper, times(1)).selectList(any());
    verify(memberMapper, times(1)).selectList(any());
    verify(memberMapper, never()).selectActiveKnowledgeBaseIdsByUserId(any());
  }

  /** 空 scope 回归：仍走 visibleKnowledgeBaseIds 全量路径（知识库 ×2 + 成员有效库 ×1），形状零改动。 */
  @Test
  void authorizedIds_emptyScopeKeepsFullEnumerationPath() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(
            List.of(
                knowledgeBase(41L, "user:1", "PRIVATE"), knowledgeBase(42L, "user:9", "PUBLIC")));
    when(memberMapper.selectActiveKnowledgeBaseIdsByUserId("user:1")).thenReturn(List.of(43L));

    assertEquals(
        List.of(41L, 42L, 43L),
        authorizationService.authorizedKnowledgeBaseIds(user(1L), List.of()));

    verify(knowledgeBaseMapper, times(2)).selectList(any());
    verify(memberMapper, times(1)).selectActiveKnowledgeBaseIdsByUserId("user:1");
    verify(memberMapper, never()).selectList(any());
  }

  /** null scope 回归：同空 scope，全量路径原样。 */
  @Test
  void authorizedIds_nullScopeKeepsFullEnumerationPath() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(
            List.of(
                knowledgeBase(41L, "user:1", "PRIVATE"), knowledgeBase(42L, "user:9", "PUBLIC")));
    when(memberMapper.selectActiveKnowledgeBaseIdsByUserId("user:1")).thenReturn(List.of(43L));

    assertEquals(
        List.of(41L, 42L, 43L), authorizationService.authorizedKnowledgeBaseIds(user(1L), null));

    verify(knowledgeBaseMapper, times(2)).selectList(any());
    verify(memberMapper, times(1)).selectActiveKnowledgeBaseIdsByUserId("user:1");
    verify(memberMapper, never()).selectList(any());
  }

  /** null 用户 + 指定 scope：等价旧语义（可见集为空 → 空结果），且不发起任何查询。 */
  @Test
  void authorizedIds_withScopeAndNullUserReturnsEmptyWithoutQueries() {
    assertTrue(authorizationService.authorizedKnowledgeBaseIds(null, List.of("11")).isEmpty());
    verifyNoInteractions(knowledgeBaseMapper, memberMapper);
  }

  /** 超管上下文（roleId=1）。 */
  private UserContext admin(Long userId) {
    return new UserContext(userId, 1L, 10L, DataScopeLevel.SELF, "admin");
  }

  private KnowledgeBaseEntity knowledgeBase(Long id, String ownerUserId, String visibility) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(id);
    entity.setOwnerUserId(ownerUserId);
    entity.setVisibility(KnowledgeBaseVisibility.valueOf(visibility));
    return entity;
  }

  private UserContext user(Long userId) {
    return new UserContext(userId, 2L, 10L, DataScopeLevel.SELF, "user");
  }

  /** 成员行桩：定向成员查询只消费 knowledge_base_id。 */
  private KnowledgeBaseMemberEntity member(Long knowledgeBaseId) {
    KnowledgeBaseMemberEntity entity = new KnowledgeBaseMemberEntity();
    entity.setKnowledgeBaseId(knowledgeBaseId);
    return entity;
  }
}
