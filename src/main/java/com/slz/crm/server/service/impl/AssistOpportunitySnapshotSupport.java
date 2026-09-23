package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 商机侧快照装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属：审批推进的完整商机快照、活动/任务快照的商机摘要、以及按 task_id 明确关联的活动列表。 只读装配，不写库、不带事务注解、不新开事务。
 *
 * <p>审批推进是商机级协助，保留整条商机的活动范围；业务活动和联络任务只冻结当前来源记录及明确关联的活动， 不能因为同一商机而扩大到其他活动。
 */
class AssistOpportunitySnapshotSupport {

  private final SalesOpportunityMapper salesOpportunityMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final ApprovalAttachmentService approvalAttachmentService;
  private final DataConvertService dataConvertService;

  AssistOpportunitySnapshotSupport(
      SalesOpportunityMapper salesOpportunityMapper,
      BusinessActivityMapper businessActivityMapper,
      ApprovalAttachmentService approvalAttachmentService,
      DataConvertService dataConvertService) {
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.approvalAttachmentService = approvalAttachmentService;
    this.dataConvertService = dataConvertService;
  }

  /** 构建审批推进协助需要的完整商机详情。该方法只在协助结束时调用， 不能用于业务活动/联络任务协助，也不能用于待协助详情。 */
  Map<String, Object> buildOpportunitySnapshotData(Long opportunityId) {
    Map<String, Object> snapshot = null;
    SalesOpportunityEntity opportunity = loadOpportunity(opportunityId);
    if (opportunity != null) {
      snapshot = new LinkedHashMap<>();
      putOpportunityFields(snapshot, opportunity);
      List<BusinessActivityEntity> activities =
          businessActivityMapper.selectByOpportunityId(opportunity.getId());
      List<Map<String, Object>> activityList = new ArrayList<>();
      if (activities != null && !activities.isEmpty()) {
        Map<Long, List<ApprovalAttachmentVO>> attachmentMap =
            loadAttachmentMap(activities.stream().map(BusinessActivityEntity::getId).toList());
        for (BusinessActivityEntity activity : activities) {
          activityList.add(opportunityActivityEntry(activity, attachmentMap));
        }
      }
      snapshot.put("activities", activityList);
    }
    return snapshot;
  }

  /** 业务活动/联络任务只需要商机摘要，不应把同商机其他活动带入快照。 */
  Map<String, Object> buildOpportunitySummaryData(Long opportunityId) {
    Map<String, Object> summary = null;
    SalesOpportunityEntity opportunity = loadOpportunity(opportunityId);
    if (opportunity != null) {
      summary = new LinkedHashMap<>();
      putOpportunityFields(summary, opportunity);
    }
    return summary;
  }

  /** 联络任务快照中的活动必须来自 task_id 的明确关联，而不是按商机反查全部活动。 */
  List<Map<String, Object>> activitySnapshotList(List<BusinessActivityEntity> activities) {
    List<Map<String, Object>> result = Collections.emptyList();
    if (activities != null && !activities.isEmpty()) {
      List<Long> activityIds =
          activities.stream().map(BusinessActivityEntity::getId).filter(Objects::nonNull).toList();
      Map<Long, List<ApprovalAttachmentVO>> attachmentMap = loadAttachmentMap(activityIds);
      result =
          activities.stream()
              .map(activity -> activityEntry(activity, attachmentMap))
              .collect(Collectors.toList());
    }
    return result;
  }

  private SalesOpportunityEntity loadOpportunity(Long opportunityId) {
    return opportunityId == null ? null : salesOpportunityMapper.selectById(opportunityId);
  }

  private void putOpportunityFields(
      Map<String, Object> target, SalesOpportunityEntity opportunity) {
    target.put("opportunityId", opportunity.getId());
    target.put("opportunityName", opportunity.getOpportunityName());
    target.put("stage", opportunity.getStage());
    target.put("stageName", AssistSnapshotMetadata.stageName(opportunity.getStage()));
    target.put("amount", opportunity.getAmount());
    target.put("expectedCloseDate", opportunity.getExpectedCloseDate());
    target.put("source", opportunity.getSource());
    target.put("description", opportunity.getDescription());
    target.put("ownerId", opportunity.getOwnerId());
    target.put("companyId", opportunity.getCompanyId());
    target.put(
        "companyName",
        opportunity.getCompanyId() == null
            ? null
            : dataConvertService.getCompanyName(opportunity.getCompanyId()));
    target.put("contactId", opportunity.getContactId());
    target.put(
        "contactName",
        opportunity.getContactId() == null
            ? null
            : dataConvertService.getContactName(opportunity.getContactId()));
  }

  private Map<Long, List<ApprovalAttachmentVO>> loadAttachmentMap(List<Long> activityIds) {
    Map<Long, List<ApprovalAttachmentVO>> attachmentMap = new HashMap<>();
    if (!activityIds.isEmpty()) {
      List<ApprovalAttachmentVO> attachments =
          Optional.ofNullable(
                  approvalAttachmentService.getByAndIds(activityIds, ModelName.BUSINESS_ACTIVITY))
              .orElseGet(Collections::emptyList);
      attachmentMap =
          attachments.stream().collect(Collectors.groupingBy(ApprovalAttachmentVO::getAndId));
    }
    return attachmentMap;
  }

  /**
   * 商机快照内的活动条目：只保留活动自身的展示字段（拆分前 {@code buildOpportunitySnapshotData} 的内联字段集， 与联络任务「明确关联活动」条目刻意不同 ——
   * 后者额外带时长/归属/任务链字段，两者不可互相复用）。
   */
  private Map<String, Object> opportunityActivityEntry(
      BusinessActivityEntity activity, Map<Long, List<ApprovalAttachmentVO>> attachmentMap) {
    Map<String, Object> act = new LinkedHashMap<>();
    act.put("activityId", activity.getId());
    act.put("activityTitle", activity.getActivityTitle());
    act.put("activityType", activity.getActivityType());
    act.put("activityTime", activity.getActivityTime());
    act.put("activityContent", activity.getActivityContent());
    act.put(
        "attachments",
        AssistSnapshotMetadata.attachmentMetadata(
            attachmentMap.getOrDefault(activity.getId(), Collections.emptyList())));
    return act;
  }

  private Map<String, Object> activityEntry(
      BusinessActivityEntity activity, Map<Long, List<ApprovalAttachmentVO>> attachmentMap) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("activityId", activity.getId());
    item.put("activityTitle", activity.getActivityTitle());
    item.put("activityType", activity.getActivityType());
    item.put("activityContent", activity.getActivityContent());
    item.put("activityTime", activity.getActivityTime());
    item.put("activityDuration", activity.getActivityDuration());
    item.put("companyId", activity.getCompanyId());
    item.put("opportunityId", activity.getOpportunityId());
    item.put("creatorId", activity.getCreatorId());
    item.put("remark", activity.getRemark());
    item.put("taskId", activity.getTaskId());
    item.put(
        "attachments",
        AssistSnapshotMetadata.attachmentMetadata(
            attachmentMap.getOrDefault(activity.getId(), Collections.emptyList())));
    return item;
  }
}
