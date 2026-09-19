package com.slz.crm.knowledge.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 知识库授权服务。
 *
 * <p>知识库使用 owner/member/Public 授权，不叠加部门数据范围；身份统一来自 UserContext。
 */
@Service
public class KnowledgeBaseAuthorizationService {
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final KnowledgeBaseMemberMapper memberMapper;

  public KnowledgeBaseAuthorizationService(
      KnowledgeBaseMapper knowledgeBaseMapper, KnowledgeBaseMemberMapper memberMapper) {
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.memberMapper = memberMapper;
  }

  /** 判断当前登录用户是否可读。 */
  public boolean canRead(KnowledgeBaseEntity knowledgeBase, UserContext user) {
    if (knowledgeBase == null || user == null) {
      return false;
    }
    if (user.isSuperAdmin() || Objects.equals(knowledgeBase.getOwnerUserId(), user.userIdRef())) {
      return true;
    }
    if ("PUBLIC".equals(String.valueOf(knowledgeBase.getVisibility()))) {
      return true;
    }
    return memberMapper.selectCount(
            new QueryWrapper<KnowledgeBaseMemberEntity>()
                .eq("knowledge_base_id", knowledgeBase.getId())
                .eq("user_id", user.userIdRef()))
        > 0;
  }

  /** 判断当前登录用户是否可写；PUBLIC 只表示可读，不等于可写。 */
  public boolean canWrite(KnowledgeBaseEntity knowledgeBase, UserContext user) {
    if (knowledgeBase == null || user == null) {
      return false;
    }
    if (user.isSuperAdmin() || Objects.equals(knowledgeBase.getOwnerUserId(), user.userIdRef())) {
      return true;
    }
    return memberMapper.selectCount(
            new QueryWrapper<KnowledgeBaseMemberEntity>()
                .eq("knowledge_base_id", knowledgeBase.getId())
                .eq("user_id", user.userIdRef())
                .in("member_role", "OWNER", "EDITOR"))
        > 0;
  }

  /** 计算当前用户可见知识库 ID；所有查询只取 id，避免加载实体和文档全表扫描。 */
  public List<Long> visibleKnowledgeBaseIds(UserContext user) {
    if (user == null) {
      return List.of();
    }
    if (user.isSuperAdmin()) {
      return knowledgeBaseMapper
          .selectList(new QueryWrapper<KnowledgeBaseEntity>().select("id"))
          .stream()
          .map(KnowledgeBaseEntity::getId)
          .toList();
    }
    Set<Long> ids = new LinkedHashSet<>();
    knowledgeBaseMapper
        .selectList(
            new QueryWrapper<KnowledgeBaseEntity>()
                .select("id")
                .eq("owner_user_id", user.userIdRef()))
        .forEach(item -> ids.add(item.getId()));
    knowledgeBaseMapper
        .selectList(new QueryWrapper<KnowledgeBaseEntity>().select("id").eq("visibility", "PUBLIC"))
        .forEach(item -> ids.add(item.getId()));
    memberMapper
        .selectList(
            new QueryWrapper<KnowledgeBaseMemberEntity>()
                .select("knowledge_base_id")
                .eq("user_id", user.userIdRef()))
        .forEach(item -> ids.add(item.getKnowledgeBaseId()));
    return new ArrayList<>(ids);
  }

  /** 将请求范围收敛到授权集合内；空 scope 表示用户可见全部知识库。 */
  public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
    List<Long> visible = visibleKnowledgeBaseIds(user);
    if (requestedScopes == null || requestedScopes.isEmpty()) {
      return visible;
    }
    Set<Long> requested = new LinkedHashSet<>();
    for (String scope : requestedScopes) {
      try {
        requested.add(Long.valueOf(scope));
      } catch (NumberFormatException ignored) {
        // 非数字 scope 不放大授权；由调用方决定是否记录日志。
      }
    }
    return visible.stream().filter(requested::contains).toList();
  }
}
