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
}
