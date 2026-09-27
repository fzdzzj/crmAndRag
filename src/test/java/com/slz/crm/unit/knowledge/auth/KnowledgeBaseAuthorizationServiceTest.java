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

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseVisibility;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
