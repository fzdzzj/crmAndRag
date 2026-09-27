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
 *
 * <p>有效库边界（fix-knowledge-base-member-visibility-boundary）：成员行存活不代表其指向的知识库仍有效——库可能已逻辑删除，
 * 成员行也可能是孤儿引用。可见集与单库读写判定因此都要求目标库存在且未软删；校验由一次 JOIN 查询完成， 不逐成员查库、不生成无界 IN，查询失败直接向上抛出（不放宽授权）。
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

  /** 判断当前登录用户是否可读；显式传入已软删实体一律拒绝。 */
  public boolean canRead(KnowledgeBaseEntity knowledgeBase, UserContext user) {
    boolean result = false;
    if (knowledgeBase != null
        && !Boolean.TRUE.equals(knowledgeBase.getIsDeleted())
        && user != null) {
      result =
          user.isSuperAdmin()
              || Objects.equals(knowledgeBase.getOwnerUserId(), user.userIdRef())
              || "PUBLIC".equals(String.valueOf(knowledgeBase.getVisibility()))
              || memberMapper.selectCount(
                      new QueryWrapper<KnowledgeBaseMemberEntity>()
                          .eq("knowledge_base_id", knowledgeBase.getId())
                          .eq("user_id", user.userIdRef()))
                  > 0;
    }
    return result;
  }

  /** 判断当前登录用户是否可写；PUBLIC 只表示可读，不等于可写；显式传入已软删实体一律拒绝。 */
  public boolean canWrite(KnowledgeBaseEntity knowledgeBase, UserContext user) {
    boolean result = false;
    if (knowledgeBase != null
        && !Boolean.TRUE.equals(knowledgeBase.getIsDeleted())
        && user != null) {
      result =
          user.isSuperAdmin()
              || Objects.equals(knowledgeBase.getOwnerUserId(), user.userIdRef())
              || memberMapper.selectCount(
                      new QueryWrapper<KnowledgeBaseMemberEntity>()
                          .eq("knowledge_base_id", knowledgeBase.getId())
                          .eq("user_id", user.userIdRef())
                          .in("member_role", "OWNER", "EDITOR"))
                  > 0;
    }
    return result;
  }

  /** 计算当前用户可见知识库 ID；所有查询只取 id，避免加载实体和文档全表扫描。 */
  public List<Long> visibleKnowledgeBaseIds(UserContext user) {
    List<Long> result = List.of();
    if (user != null) {
      if (user.isSuperAdmin()) {
        result =
            knowledgeBaseMapper
                .selectList(new QueryWrapper<KnowledgeBaseEntity>().select("id"))
                .stream()
                .map(KnowledgeBaseEntity::getId)
                .toList();
      } else {
        Set<Long> ids = new LinkedHashSet<>();
        knowledgeBaseMapper
            .selectList(
                new QueryWrapper<KnowledgeBaseEntity>()
                    .select("id")
                    .eq("owner_user_id", user.userIdRef()))
            .forEach(item -> ids.add(item.getId()));
        knowledgeBaseMapper
            .selectList(
                new QueryWrapper<KnowledgeBaseEntity>().select("id").eq("visibility", "PUBLIC"))
            .forEach(item -> ids.add(item.getId()));
        memberMapper.selectActiveKnowledgeBaseIdsByUserId(user.userIdRef()).forEach(ids::add);
        result = new ArrayList<>(ids);
      }
    }
    return result;
  }

  /** 将请求范围收敛到授权集合内；空 scope 表示用户可见全部知识库。 */
  public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
    List<Long> visible = visibleKnowledgeBaseIds(user);
    List<Long> result = visible;
    if (requestedScopes != null && !requestedScopes.isEmpty()) {
      Set<Long> requested = new LinkedHashSet<>();
      for (String scope : requestedScopes) {
        try {
          requested.add(Long.valueOf(scope));
        } catch (NumberFormatException ignored) {
          // 非数字 scope 不放大授权；由调用方决定是否记录日志。
        }
      }
      result = visible.stream().filter(requested::contains).toList();
    }
    return result;
  }
}
