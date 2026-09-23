package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 协助来源附件的受控读写（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口：{@code getRelatedActivityAttachments} / {@code getRelatedTaskAttachments} / {@code
 * deleteRelatedAttachments} / {@code uploadRelatedAttachments}。授权依据永远是「本条待协助记录」， 由 {@link
 * AssistAccessGuard} 统一裁决，不会因同一商机可见而扩大到其他附件。 写操作在主类入口 {@code @Transactional} 内进行，本类不带事务注解、不新开事务。
 *
 * <p>闸门调用分两条路，与拆分前的调用图一一对应：{@code requirePendingAssistSource} 在拆分前是主类的<b>公共</b>方法， 故经 {@code store}
 * 回调主类入口（既有单测对它的 stub / verify 仍成立）；{@code requirePendingTaskAssistParticipant}
 * 在拆分前是主类<b>私有</b>方法、不可被测试打桩， 故仍直接走 {@link AssistAccessGuard}。两者的实现是同一份，行为不变。
 */
class AssistSourceAttachmentSupport {

  private final AssistRequestStore store;
  private final AssistAccessGuard accessGuard;
  private final ApprovalAttachmentService approvalAttachmentService;
  private final BusinessActivityMapper businessActivityMapper;

  AssistSourceAttachmentSupport(
      AssistRequestStore store,
      AssistAccessGuard accessGuard,
      ApprovalAttachmentService approvalAttachmentService,
      BusinessActivityMapper businessActivityMapper) {
    this.store = store;
    this.accessGuard = accessGuard;
    this.approvalAttachmentService = approvalAttachmentService;
    this.businessActivityMapper = businessActivityMapper;
  }

  /** 查询本次业务活动协助的来源活动附件。入参校验先于授权判定，与拆分前一致。 */
  List<ApprovalAttachmentVO> relatedActivityAttachments(Long id, Long activityId) {
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist =
        store.requirePendingAssistSource(id, ModelName.BUSINESS_ACTIVITY, BaseUnit.getCurrentId());
    BusinessActivityEntity activity = businessActivityMapper.selectById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "业务活动不存在");
    }
    if (!Objects.equals(assist.getRecordId(), activityId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "该业务活动不属于本次协助关联范围");
    }
    return approvalAttachmentService.getByAndIdsForAssist(
        Collections.singletonList(activityId), ModelName.BUSINESS_ACTIVITY, id);
  }

  /** 查询当前协助来源联络任务的附件（仅待协助期间）。 */
  List<ApprovalAttachmentVO> relatedTaskAttachments(Long id) {
    AssistRequestEntity assist =
        accessGuard.requirePendingTaskAssistParticipant(id, BaseUnit.getCurrentId());
    return approvalAttachmentService.getByAndIdsForAssist(
        Collections.singletonList(assist.getRecordId()), ModelName.CONTACT_TASK, id);
  }

  /** 协助专用删除不复用普通删除授权：前者的授权依据是本条待协助记录， 但仍把 modelName 和 recordId 一并传给附件服务，防止把其他业务记录的附件 ID 混入删除请求。 */
  AttachmentDeleteResultVO deleteRelatedAttachments(Long id, List<Long> attachmentIds) {
    AssistRequestEntity assist =
        store.requirePendingAssistSource(id, null, BaseUnit.getCurrentId());
    String sourceModel = assist.getModelName();
    if (!ModelName.BUSINESS_ACTIVITY.equals(sourceModel)
        && !ModelName.CONTACT_TASK.equals(sourceModel)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不支持删除业务附件");
    }
    return approvalAttachmentService.removeSourceAttachments(
        attachmentIds, sourceModel, assist.getRecordId());
  }

  /** 以协助上下文上传来源业务的活动/任务附件，绕过普通模块权限但不绕过记录和生命周期授权。 */
  void uploadRelatedAttachments(Long id, List<ApprovalAttachmentDTO> attachments) {
    AssistRequestEntity loaded = store.getById(id);
    AssistRequestEntity assist =
        loaded != null && ModelName.CONTACT_TASK.equals(loaded.getModelName())
            ? accessGuard.requirePendingTaskAssistParticipant(id, BaseUnit.getCurrentId())
            : store.requirePendingAssistSource(id, null, BaseUnit.getCurrentId());
    if (attachments == null || attachments.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择至少一个附件");
    }
    String sourceModel =
        switch (assist.getModelName()) {
          case ModelName.BUSINESS_ACTIVITY, ModelName.CONTACT_TASK -> assist.getModelName();
          default -> throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不支持上传业务附件");
        };
    approvalAttachmentService.saveAttachments(assist.getRecordId(), sourceModel, attachments);
  }
}
