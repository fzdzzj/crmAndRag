package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 协助列表的业务记录摘要采集（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属：待我协助/我发起的协助列表的 {@code fillRecordContent}。按来源模型批量查业务记录并压缩成摘要， 批量查询避免
 * N+1。只读，不写库、不带事务注解、不新开事务。
 */
class AssistRecordBriefCollector {

  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;

  AssistRecordBriefCollector(
      SalesStageApprovalMapper salesStageApprovalMapper,
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      SalesOpportunityMapper salesOpportunityMapper) {
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
  }

  /** 销售阶段推进审批摘要：商机名称 + 审批备注 + 申请时间（批量查商机避免 N+1）。 */
  Map<Long, AssistRecordBrief> collectApprovalBriefs(List<AssistVO> voList) {
    Set<Long> approvalIds = collectRecordIds(voList, ModelName.SALES_STAGE_APPROVAL);
    Map<Long, AssistRecordBrief> map = new HashMap<>();
    if (!approvalIds.isEmpty()) {
      Set<Long> opportunityIds = new HashSet<>();
      List<SalesStageApprovalEntity> approvals =
          salesStageApprovalMapper.selectBatchIds(approvalIds);
      for (SalesStageApprovalEntity approval : approvals) {
        if (approval.getOpportunityId() != null) {
          opportunityIds.add(approval.getOpportunityId());
        }
      }
      Map<Long, SalesOpportunityEntity> opportunityMap = loadOpportunityMap(opportunityIds);
      for (SalesStageApprovalEntity approval : approvals) {
        SalesOpportunityEntity opportunity =
            approval.getOpportunityId() == null
                ? null
                : opportunityMap.get(approval.getOpportunityId());
        map.put(
            approval.getId(),
            new AssistRecordBrief(
                "销售阶段推进审批",
                approvalContent(approval, opportunity),
                approval.getApplyTime(),
                approval.getOpportunityId(),
                opportunity == null ? null : opportunity.getOpportunityName(),
                opportunity == null ? null : opportunity.getCompanyId(),
                opportunity == null ? null : opportunity.getContactId()));
      }
    }
    return map;
  }

  /** 业务活动摘要：活动标题 + 活动内容 + 活动时间。 */
  Map<Long, AssistRecordBrief> collectActivityBriefs(List<AssistVO> voList) {
    Set<Long> activityIds = collectRecordIds(voList, ModelName.BUSINESS_ACTIVITY);
    Map<Long, AssistRecordBrief> map = new HashMap<>();
    if (!activityIds.isEmpty()) {
      List<BusinessActivityEntity> activities = businessActivityMapper.selectBatchIds(activityIds);
      Map<Long, SalesOpportunityEntity> opportunityMap =
          loadOpportunityMap(opportunityIdsOfActivities(activities));
      for (BusinessActivityEntity activity : activities) {
        SalesOpportunityEntity opportunity =
            activity.getOpportunityId() == null
                ? null
                : opportunityMap.get(activity.getOpportunityId());
        map.put(
            activity.getId(),
            new AssistRecordBrief(
                "业务活动：" + (activity.getActivityTitle() == null ? "" : activity.getActivityTitle()),
                activity.getActivityContent(),
                activity.getActivityTime(),
                activity.getOpportunityId(),
                opportunity == null ? null : opportunity.getOpportunityName(),
                activity.getCompanyId() != null
                    ? activity.getCompanyId()
                    : opportunity == null ? null : opportunity.getCompanyId(),
                opportunity == null ? null : opportunity.getContactId()));
      }
    }
    return map;
  }

  /** 联络任务摘要：任务标题 + 任务内容 + 结束时间。 */
  Map<Long, AssistRecordBrief> collectTaskBriefs(List<AssistVO> voList) {
    return taskBriefs(collectRecordIds(voList, ModelName.CONTACT_TASK));
  }

  /** 按任务 ID 批量装配摘要；空集合直接返回空映射（不查询）。 */
  private Map<Long, AssistRecordBrief> taskBriefs(Set<Long> taskIds) {
    Map<Long, AssistRecordBrief> map = new HashMap<>();
    if (!taskIds.isEmpty()) {
      List<ContactTaskEntity> tasks = contactTaskMapper.selectBatchIds(taskIds);
      Map<Long, SalesOpportunityEntity> opportunityMap =
          loadOpportunityMap(opportunityIdsOfTasks(tasks));
      for (ContactTaskEntity task : tasks) {
        map.put(task.getId(), taskBrief(task, opportunityMap));
      }
    }
    return map;
  }

  /** 单条联络任务的摘要装配：字段取值与商机回填优先级与拆分前逐处一致。 */
  private AssistRecordBrief taskBrief(
      ContactTaskEntity task, Map<Long, SalesOpportunityEntity> opportunityMap) {
    SalesOpportunityEntity opportunity =
        task.getOpportunityId() == null ? null : opportunityMap.get(task.getOpportunityId());
    return new AssistRecordBrief(
        "联络任务：" + (task.getTaskTitle() == null ? "" : task.getTaskTitle()),
        task.getTaskContent(),
        toLocalDateTime(task.getEndTime()),
        task.getOpportunityId(),
        opportunity == null ? null : opportunity.getOpportunityName(),
        task.getCompanyId() != null
            ? task.getCompanyId()
            : opportunity == null ? null : opportunity.getCompanyId(),
        task.getContactId() != null
            ? task.getContactId()
            : opportunity == null ? null : opportunity.getContactId());
  }

  /** 按商机 ID 集合批量加载商机实体映射，空集合直接返回空映射。 */
  private Map<Long, SalesOpportunityEntity> loadOpportunityMap(Set<Long> opportunityIds) {
    return opportunityIds.isEmpty()
        ? Collections.emptyMap()
        : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
            .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));
  }

  /** 审批摘要正文：商机名与备注按存在性拼接，分隔符与拼接顺序与拆分前一致。 */
  private String approvalContent(
      SalesStageApprovalEntity approval, SalesOpportunityEntity opportunity) {
    StringBuilder content = new StringBuilder();
    String opportunityName = opportunity == null ? null : opportunity.getOpportunityName();
    if (opportunityName != null && !opportunityName.isEmpty()) {
      content.append("商机：").append(opportunityName);
    }
    if (approval.getMessage() != null && !approval.getMessage().isEmpty()) {
      if (content.length() > 0) {
        content.append("；");
      }
      content.append("备注：").append(approval.getMessage());
    }
    return content.toString();
  }

  private Set<Long> opportunityIdsOfActivities(List<BusinessActivityEntity> activities) {
    return activities.stream()
        .map(BusinessActivityEntity::getOpportunityId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private Set<Long> opportunityIdsOfTasks(List<ContactTaskEntity> tasks) {
    return tasks.stream()
        .map(ContactTaskEntity::getOpportunityId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private Set<Long> collectRecordIds(List<AssistVO> voList, String modelName) {
    Set<Long> ids = new HashSet<>();
    for (AssistVO vo : voList) {
      if (modelName.equals(vo.getModelName()) && vo.getRecordId() != null) {
        ids.add(vo.getRecordId());
      }
    }
    return ids;
  }

  private LocalDateTime toLocalDateTime(java.util.Date date) {
    return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
  }
}
