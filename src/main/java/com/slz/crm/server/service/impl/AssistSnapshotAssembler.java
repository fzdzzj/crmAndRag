package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import com.slz.crm.server.service.DataConvertService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 终态业务快照装配与序列化（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口 {@code handleAssist} / {@code cancelPendingByRecord} 的终态冻结路径。只读装配 + 一次序列化，
 * 不写库、不带事务注解、不新开事务。
 *
 * <p>按来源模型构建终态快照：
 *
 * <p>审批推进是商机级协助，保留整条商机的活动范围；业务活动和联络任务只冻结 当前来源记录及明确关联的活动，不能因为同一商机而扩大到其他活动。
 *
 * <p>附件只保存 ID、来源模型、名称等元数据，不把下载 URL 写入数据库。协助交付物 属于 assist_request 本身，始终通过实时交付物接口查询，因此不会进入该快照。
 */
@Slf4j
class AssistSnapshotAssembler {

  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final ApprovalAttachmentService approvalAttachmentService;
  private final AssistRelatedRecordResolver assistRelatedRecordResolver;
  private final DataConvertService dataConvertService;
  private final ObjectMapper objectMapper;
  private final AssistOpportunitySnapshotSupport opportunitySnapshotSupport;

  AssistSnapshotAssembler(
      SalesStageApprovalMapper salesStageApprovalMapper,
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      ApprovalAttachmentService approvalAttachmentService,
      AssistRelatedRecordResolver assistRelatedRecordResolver,
      DataConvertService dataConvertService,
      ObjectMapper objectMapper,
      AssistOpportunitySnapshotSupport opportunitySnapshotSupport) {
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.approvalAttachmentService = approvalAttachmentService;
    this.assistRelatedRecordResolver = assistRelatedRecordResolver;
    this.dataConvertService = dataConvertService;
    this.objectMapper = objectMapper;
    this.opportunitySnapshotSupport = opportunitySnapshotSupport;
  }

  /** 关联商机/公司/联系人的索引，附上公司与联系人名称（供实时关联详情接口使用）。 */
  AssistRelatedRecordVO resolveRelatedRecord(AssistRequestEntity assist) {
    AssistRelatedRecordVO vo = assistRelatedRecordResolver.resolve(assist);
    vo.setCompanyName(
        vo.getCompanyId() == null ? null : dataConvertService.getCompanyName(vo.getCompanyId()));
    vo.setContactName(
        vo.getContactId() == null ? null : dataConvertService.getContactName(vo.getContactId()));
    return vo;
  }

  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // 快照构建混调 resolver+objectMapper 多源，非业务异常统一包装上抛
  String buildRecordSnapshot(AssistRequestEntity assist) {
    try {
      AssistRelatedRecordVO related = resolveRelatedRecord(assist);
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("version", 2);
      snapshot.put("capturedAt", LocalDateTime.now());
      snapshot.put("modelName", assist.getModelName());
      snapshot.put("recordId", assist.getRecordId());

      Map<String, Object> relatedSnapshot = new LinkedHashMap<>();
      relatedSnapshot.put("opportunityId", related.getOpportunityId());
      relatedSnapshot.put("opportunityName", related.getOpportunityName());
      relatedSnapshot.put("companyId", related.getCompanyId());
      relatedSnapshot.put("companyName", related.getCompanyName());
      relatedSnapshot.put("contactId", related.getContactId());
      relatedSnapshot.put("contactName", related.getContactName());
      snapshot.put("related", relatedSnapshot);
      snapshot.put("opportunity", null);

      snapshot.put("record", buildRecordSection(assist, related, snapshot));
      String serialized = objectMapper.writeValueAsString(snapshot);
      if (serialized == null || serialized.isBlank()) {
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "协助历史快照写入失败");
      }
      return serialized;
    } catch (Exception e) {
      log.warn("协助终态快照构建失败，assistId={}", assist.getId(), e);
      if (e instanceof BaseException baseException) {
        throw baseException;
      }
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "协助历史快照写入失败");
    }
  }

  /** 按来源模型装配 record 段：审批/业务活动/联络任务各有专属字段与附件，并按需填充商机快照。 */
  private Map<String, Object> buildRecordSection(
      AssistRequestEntity assist, AssistRelatedRecordVO related, Map<String, Object> snapshot) {
    return switch (assist.getModelName()) {
      case ModelName.SALES_STAGE_APPROVAL ->
          buildSalesStageApprovalRecord(assist, related, snapshot);
      case ModelName.BUSINESS_ACTIVITY -> buildBusinessActivityRecord(assist, related, snapshot);
      case ModelName.CONTACT_TASK -> buildContactTaskRecord(assist, related, snapshot);
      default -> throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "暂不支持该协助来源的快照");
    };
  }

  /** 销售阶段推进审批快照段：审批推进是商机级协助，只有这里才冻结商机下全部活动及活动附件。 */
  private Map<String, Object> buildSalesStageApprovalRecord(
      AssistRequestEntity assist, AssistRelatedRecordVO related, Map<String, Object> snapshot) {
    Map<String, Object> record = new LinkedHashMap<>();
    SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(assist.getRecordId());
    snapshot.put(
        "opportunity",
        opportunitySnapshotSupport.buildOpportunitySnapshotData(related.getOpportunityId()));
    record.put("type", "salesStageApproval");
    record.put("approvalId", assist.getRecordId());
    record.put("opportunityId", approval == null ? null : approval.getOpportunityId());
    record.put("currentStage", approval == null ? null : approval.getCurrentStage());
    record.put("targetStage", approval == null ? null : approval.getTargetStage());
    record.put("approvalStatus", approval == null ? null : approval.getApprovalStatus());
    record.put("message", approval == null ? null : approval.getMessage());
    record.put("approvalOpinion", approval == null ? null : approval.getApprovalOpinion());
    record.put("applyTime", approval == null ? null : approval.getApplyTime());
    record.put("approvalTime", approval == null ? null : approval.getApprovalTime());
    record.put(
        "attachments",
        attachmentMetadataForAssist(
            assist.getRecordId(), ModelName.APPROVAL_ATTACHMENT, assist.getId()));
    return record;
  }

  /** 业务活动快照段：只冻结本活动字段与附件，商机侧仅取摘要。 */
  private Map<String, Object> buildBusinessActivityRecord(
      AssistRequestEntity assist, AssistRelatedRecordVO related, Map<String, Object> snapshot) {
    Map<String, Object> record = new LinkedHashMap<>();
    BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
    snapshot.put(
        "opportunity",
        opportunitySnapshotSupport.buildOpportunitySummaryData(related.getOpportunityId()));
    record.put("type", "businessActivity");
    record.put("activityId", assist.getRecordId());
    record.put("title", activity == null ? null : activity.getActivityTitle());
    record.put("activityType", activity == null ? null : activity.getActivityType());
    record.put("content", activity == null ? null : activity.getActivityContent());
    record.put("time", activity == null ? null : activity.getActivityTime());
    record.put("activityDuration", activity == null ? null : activity.getActivityDuration());
    record.put("companyId", activity == null ? null : activity.getCompanyId());
    record.put("opportunityId", activity == null ? null : activity.getOpportunityId());
    record.put("creatorId", activity == null ? null : activity.getCreatorId());
    record.put("remark", activity == null ? null : activity.getRemark());
    record.put("taskId", activity == null ? null : activity.getTaskId());
    record.put(
        "attachments",
        attachmentMetadataForAssist(
            assist.getRecordId(), ModelName.BUSINESS_ACTIVITY, assist.getId()));
    return record;
  }

  /** 联络任务快照段：任务字段 + 按 task_id 明确关联的活动列表（不按商机反查全部活动）。 */
  private Map<String, Object> buildContactTaskRecord(
      AssistRequestEntity assist, AssistRelatedRecordVO related, Map<String, Object> snapshot) {
    Map<String, Object> record = new LinkedHashMap<>();
    ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
    snapshot.put(
        "opportunity",
        opportunitySnapshotSupport.buildOpportunitySummaryData(related.getOpportunityId()));
    record.put("type", "contactTask");
    record.put("taskId", assist.getRecordId());
    record.put("title", task == null ? null : task.getTaskTitle());
    record.put("taskType", task == null ? null : task.getTaskType());
    record.put("content", task == null ? null : task.getTaskContent());
    record.put("startTime", task == null ? null : toLocalDateTime(task.getStartTime()));
    record.put("endTime", task == null ? null : toLocalDateTime(task.getEndTime()));
    record.put("companyId", task == null ? null : task.getCompanyId());
    record.put("contactId", task == null ? null : task.getContactId());
    record.put("opportunityId", task == null ? null : task.getOpportunityId());
    record.put("priority", task == null ? null : task.getPriority());
    record.put("status", task == null ? null : task.getStatus());
    record.put("assigneeId", task == null ? null : task.getAssigneeId());
    record.put("assignerId", task == null ? null : task.getAssignerId());
    record.put("creatorId", task == null ? null : task.getCreatorId());
    record.put(
        "attachments",
        attachmentMetadataForAssist(assist.getRecordId(), ModelName.CONTACT_TASK, assist.getId()));
    List<BusinessActivityEntity> linkedActivities =
        businessActivityMapper.selectByTaskId(assist.getRecordId());
    record.put(
        "relatedActivities", opportunitySnapshotSupport.activitySnapshotList(linkedActivities));
    return record;
  }

  private List<Map<String, Object>> attachmentMetadataForAssist(
      Long recordId, String modelName, Long assistId) {
    List<ApprovalAttachmentVO> attachments =
        approvalAttachmentService.getByAndIdsForAssist(
            Collections.singletonList(recordId), modelName, assistId);
    return AssistSnapshotMetadata.attachmentMetadata(attachments);
  }

  private LocalDateTime toLocalDateTime(java.util.Date date) {
    return date == null
        ? null
        : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
  }
}
