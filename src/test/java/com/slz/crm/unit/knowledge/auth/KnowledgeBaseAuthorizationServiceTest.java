package com.slz.crm.unit.knowledge.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseVisibility;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 知识库授权矩阵：负责人、公共库、成员编辑与普通成员只读。 */
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
