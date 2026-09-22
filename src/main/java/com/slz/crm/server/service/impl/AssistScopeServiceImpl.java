package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import com.slz.crm.server.service.AssistScopeService;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 协助关联可见性解析实现： 通过 assist_request 反查业务表，收集公司/联系人/商机 ID */
@Service
public class AssistScopeServiceImpl implements AssistScopeService {

  @Autowired private AssistRequestMapper assistRequestMapper;

  @Autowired private AssistRelatedRecordResolver assistRelatedRecordResolver;

  @Override
  public Set<Long> visibleCompanyIds(Long userId) {
    return resolveCompanyIds(relatedRecordIds(userId));
  }

  @Override
  public Set<Long> visibleContactIds(Long userId) {
    return resolveContactIds(relatedRecordIds(userId));
  }

  @Override
  public Set<Long> visibleOpportunityIds(Long userId) {
    return resolveOpportunityIds(relatedRecordIds(userId));
  }

  @Override
  public List<AssistRequestEntity> visibleAssistsForOpportunity(Long userId, Long opportunityId) {
    List<AssistRequestEntity> result;
    if (opportunityId == null) {
      result = Collections.emptyList();
    } else {
      result =
          relatedRecordIds(userId).stream()
              .filter(
                  assist ->
                      opportunityId.equals(
                          assistRelatedRecordResolver.resolve(assist).getOpportunityId()))
              .toList();
    }
    return result;
  }

  /** 查询当前用户相关的协助记录（作为申请人或协助人） */
  private List<AssistRequestEntity> relatedRecordIds(Long userId) {
    List<AssistRequestEntity> result;
    if (userId == null) {
      result = Collections.emptyList();
    } else {
      result =
          assistRequestMapper.selectList(
              new LambdaQueryWrapper<AssistRequestEntity>()
                  .eq(AssistRequestEntity::getAssistStatus, 0)
                  .and(
                      w ->
                          w.eq(AssistRequestEntity::getApplicantId, userId)
                              .or()
                              .eq(AssistRequestEntity::getAssistUserId, userId)));
    }
    return result;
  }

  private Set<Long> resolveCompanyIds(List<AssistRequestEntity> assists) {
    return resolveIds(assists, AssistRelatedRecordVO::getCompanyId);
  }

  private Set<Long> resolveContactIds(List<AssistRequestEntity> assists) {
    return resolveIds(assists, AssistRelatedRecordVO::getContactId);
  }

  private Set<Long> resolveOpportunityIds(List<AssistRequestEntity> assists) {
    return resolveIds(assists, AssistRelatedRecordVO::getOpportunityId);
  }

  private Set<Long> resolveIds(
      List<AssistRequestEntity> assists,
      java.util.function.Function<AssistRelatedRecordVO, Long> idGetter) {
    Set<Long> ids = new HashSet<>();
    for (AssistRequestEntity assist : assists) {
      AssistRelatedRecordVO related = assistRelatedRecordResolver.resolve(assist);
      Long id = idGetter.apply(related);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }
}
