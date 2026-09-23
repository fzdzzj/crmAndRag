package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 协助详情与受控来源详情装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口：{@code getDetail} / {@code getRelatedApproval} / {@code getRelatedActivity} / {@code
 * getRelatedTask}。只读装配，不写库、不带事务注解、不新开事务。
 *
 * <p>终态详情绝不通过实时业务回填：待协助回填实时摘要，终态只在冻结 JSON 上补短期下载链接。
 */
class AssistDetailAssembler {

  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final DataConvertService dataConvertService;
  private final AssistRecordBriefFiller briefFiller;
  private final AssistSnapshotHistorySupport historySupport;

  AssistDetailAssembler(
      SalesStageApprovalMapper salesStageApprovalMapper,
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      DataConvertService dataConvertService,
      AssistRecordBriefFiller briefFiller,
      AssistSnapshotHistorySupport historySupport) {
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.dataConvertService = dataConvertService;
    this.briefFiller = briefFiller;
    this.historySupport = historySupport;
  }

  /** 组装协助详情：待协助可见最新进展（回填实时摘要），终态只读冻结快照。 */
  AssistVO detail(AssistRequestEntity entity, Long id, List<AssistVO> vos) {
    AssistVO vo =
        vos.stream()
            .filter(v -> Objects.equals(v.getId(), id))
            .findFirst()
            .orElseThrow(() -> new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在"));
    if (Objects.equals(entity.getAssistStatus(), 0)) {
      // 待协助可看到最新进展，因此回填实时摘要；完整商机快照只在进入终态时构建并持久化。
      briefFiller.fillRecordContent(Collections.singletonList(vo));
    } else {
      // 历史终态绝不通过实时业务回填。只在冻结 JSON 上补短期下载链接，旧历史缺快照时交给客户端明确提示。
      vo.setRecordSnapshot(
          historySupport.hydrateHistoricalAttachmentLinks(
              entity.getRecordSnapshot(), entity.getId()));
      vo.setSnapshotMissing(
          entity.getRecordSnapshot() == null || entity.getRecordSnapshot().isBlank());
    }
    return vo;
  }

  /** 通过协助记录受控读取审批详情（仅审批来源协助可用）。 */
  SalesStageApprovalVO relatedApproval(AssistRequestEntity assist) {
    requireModel(assist, ModelName.SALES_STAGE_APPROVAL);
    SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(assist.getRecordId());
    if (approval == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "审批记录不存在或已删除");
    }
    SalesOpportunityEntity opportunity =
        approval.getOpportunityId() == null
            ? null
            : salesOpportunityMapper.selectById(approval.getOpportunityId());
    return SalesStageApprovalVO.fromEntity(
        approval,
        opportunity == null ? null : opportunity.getOpportunityName(),
        AssistSnapshotMetadata.stageName(approval.getCurrentStage()),
        AssistSnapshotMetadata.stageName(approval.getTargetStage()),
        dataConvertService.getUserName(approval.getApproverId()));
  }

  /** 通过协助记录受控读取业务活动详情（仅活动来源协助可用）。 */
  BusinessActivityVO relatedActivity(AssistRequestEntity assist) {
    requireModel(assist, ModelName.BUSINESS_ACTIVITY);
    BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
    if (activity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS, "业务活动不存在或已删除");
    }
    return BusinessActivityVO.fromEntity(
        activity,
        dataConvertService.getUserName(activity.getCreatorId()),
        dataConvertService.getOpportunityName(activity.getOpportunityId()));
  }

  /** 通过协助记录受控读取联络任务详情（任务来源直接取 recordId，活动来源按 taskId 上溯）。 */
  ContactTaskVO relatedTask(AssistRequestEntity assist) {
    requireModelForTask(assist);
    Long taskId = resolveTaskId(assist);
    if (taskId == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "该业务活动未关联联络任务");
    }
    ContactTaskEntity task = contactTaskMapper.selectById(taskId);
    if (task == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "联络任务不存在或已删除");
    }
    return ContactTaskVO.fromEntity(
        task,
        dataConvertService.getCompanyName(task.getCompanyId()),
        dataConvertService.getContactName(task.getContactId()),
        dataConvertService.getOpportunityName(task.getOpportunityId()),
        dataConvertService.getUserName(task.getAssigneeId()),
        dataConvertService.getUserName(task.getAssignerId()),
        dataConvertService.getUserName(task.getCreatorId()));
  }

  private void requireModel(AssistRequestEntity assist, String expectedModelName) {
    if (!Objects.equals(expectedModelName, assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助来源与请求详情类型不匹配");
    }
  }

  private void requireModelForTask(AssistRequestEntity assist) {
    if (!Objects.equals(assist.getModelName(), ModelName.CONTACT_TASK)
        && !Objects.equals(assist.getModelName(), ModelName.BUSINESS_ACTIVITY)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源没有关联联络任务");
    }
  }

  private Long resolveTaskId(AssistRequestEntity assist) {
    Long taskId = null;
    if (Objects.equals(assist.getModelName(), ModelName.CONTACT_TASK)) {
      taskId = assist.getRecordId();
    } else {
      BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
      taskId = activity == null ? null : activity.getTaskId();
    }
    return taskId;
  }
}
