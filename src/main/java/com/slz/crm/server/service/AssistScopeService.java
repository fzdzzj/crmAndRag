package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.AssistRequestEntity;
import java.util.List;
import java.util.Set;

/** 协助关联可见性解析服务： 协助人（及申请人）可查看关联业务记录 + 关联客户公司/联系人/商机 */
public interface AssistScopeService {

  /** 当前用户可见的关联客户公司ID集合 */
  Set<Long> visibleCompanyIds(Long userId);

  /** 当前用户可见的关联联系人ID集合 */
  Set<Long> visibleContactIds(Long userId);

  /** 当前用户可见的关联商机ID集合 */
  Set<Long> visibleOpportunityIds(Long userId);

  /**
   * 查询当前用户对指定商机仍处于待协助状态的协助来源。
   *
   * <p>用于商机详情按来源收紧活动和审批记录，不得用于扩大列表可见范围。
   */
  List<AssistRequestEntity> visibleAssistsForOpportunity(Long userId, Long opportunityId);
}
