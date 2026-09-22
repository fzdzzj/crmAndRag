package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.IOUtils;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审批附件 VO 组装与删除权判定支持类（tighten-pmd-residual-325 任务 6.3 自 ApprovalAttachmentServiceImpl 拆出，行为等价）。
 * 聚合上传人姓名/下载令牌 URL 填充与「申请人或业务创建人」跨模块判定。
 */
class ApprovalAttachmentVoSupport {
  private final ApprovalAttachmentMapper attachmentMapper;
  private final UserMapper userMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final AssistRequestMapper assistRequestMapper;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final AttachmentDownloadTokenUtil downloadTokenUtil;
  private final com.slz.crm.server.service.AttachmentAccessService attachmentAccessService;

  ApprovalAttachmentVoSupport(
      ApprovalAttachmentMapper attachmentMapper,
      UserMapper userMapper,
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      AssistRequestMapper assistRequestMapper,
      SalesStageApprovalMapper salesStageApprovalMapper,
      AttachmentDownloadTokenUtil downloadTokenUtil,
      com.slz.crm.server.service.AttachmentAccessService attachmentAccessService) {
    this.attachmentMapper = attachmentMapper;
    this.userMapper = userMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.assistRequestMapper = assistRequestMapper;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.downloadTokenUtil = downloadTokenUtil;
    this.attachmentAccessService = attachmentAccessService;
  }

  /**
   * 实体列表 → VO 列表并批量填充上传人姓名与带令牌的下载 URL（清空文件数据，避免返回列表时传输大量二进制数据）。
   *
   * @param entities 附件实体（调用方已完成查询）
   * @param modelName 附件所属模块名
   * @param activeAssistId 协助来源 ID；非空时生成协助来源下载令牌
   * @param baseUrl 下载 URL 基础路径（调用方从请求上下文解析）
   */
  List<ApprovalAttachmentVO> readFileWithEnrichment(
      List<ApprovalAttachmentEntity> entities,
      String modelName,
      Long activeAssistId,
      String baseUrl) {
    List<ApprovalAttachmentVO> vos = IOUtils.readFile(entities);

    // 批量填充上传人姓名
    Set<Long> uploaderIds =
        entities.stream()
            .map(ApprovalAttachmentEntity::getUploaderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> uploaderNameMap =
        uploaderIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(uploaderIds).stream()
                .collect(
                    Collectors.toMap(
                        u -> u.getId(), u -> u.getRealName() == null ? "" : u.getRealName()));
    for (ApprovalAttachmentVO vo : vos) {
      vo.setUploaderName(uploaderNameMap.get(vo.getUploaderId()));
    }

    // 为每个附件生成带令牌的下载URL（包含用户ID）
    Long currentUserId = BaseUnit.getCurrentId();
    for (ApprovalAttachmentVO vo : vos) {
      String token =
          activeAssistId == null
              ? downloadTokenUtil.generateDownloadToken(
                  vo.getId(), currentUserId, "approval_attachment", modelName)
              : downloadTokenUtil.generateAssistSourceDownloadToken(
                  vo.getId(), currentUserId, "approval_attachment", modelName, activeAssistId);
      vo.setDownloadUrl(baseUrl + "/public/attachment/download?token=" + token);
      // 清空文件数据，避免返回列表时传输大量二进制数据
      vo.setFileData(null);
    }

    return vos;
  }

  /** 逐条判定删除权限并执行删除：缺失进 notFound，越权进 denied，放行的删除文件与记录 */
  void deleteAuthorizedAttachments(
      AttachmentDeleteResultVO result,
      List<Long> distinctIds,
      String modelName,
      Long expectedAndId) {
    Map<Long, ApprovalAttachmentEntity> entityById =
        attachmentMapper.selectBatchIds(distinctIds).stream()
            .collect(Collectors.toMap(ApprovalAttachmentEntity::getId, Function.identity()));
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    List<ApprovalAttachmentEntity> allowed = new java.util.ArrayList<>();

    for (Long attachmentId : distinctIds) {
      ApprovalAttachmentEntity entity = entityById.get(attachmentId);
      if (entity == null) {
        result.getNotFoundIds().add(attachmentId);
      } else if (!Objects.equals(modelName, entity.getModelName())) {
        result.getDenied().put(attachmentId, "附件不属于当前模块");
      } else if (expectedAndId != null && !Objects.equals(expectedAndId, entity.getAndId())) {
        result.getDenied().put(attachmentId, "附件不属于当前业务记录");
      } else if (canDeleteAttachment(entity, isAdmin, currentId)) {
        allowed.add(entity);
      } else {
        result.getDenied().put(attachmentId, "仅上传人本人、申请人或业务创建人可删除该附件");
      }
    }
    if (!allowed.isEmpty()) {
      IOUtils.deleteFile(allowed);
      List<Long> allowedIds = allowed.stream().map(ApprovalAttachmentEntity::getId).toList();
      attachmentMapper.deleteBatchIds(allowedIds);
      result.setDeletedIds(allowedIds);
    }
  }

  /** 删除权判定：管理员/上传人本人/申请人或业务创建人，或非协助交付物且对业务记录有写权限。 */
  private boolean canDeleteAttachment(
      ApprovalAttachmentEntity entity, boolean isAdmin, Long currentId) {
    return isAdmin
        || Objects.equals(entity.getUploaderId(), currentId)
        || isApplicantOrCreator(entity, currentId)
        // 普通业务附件的记录级权限统一由 AttachmentAccessService 管理；
        // 协助交付物仍由 AssistController 的生命周期校验负责。
        || (!ModelName.ASSIST_REQUEST.equals(entity.getModelName())
            && attachmentAccessService.canWriteAttachments(
                entity.getModelName(), entity.getAndId(), currentId));
  }

  /** 判断当前用户是否为附件所属业务的申请人/创建人 */
  boolean isApplicantOrCreator(ApprovalAttachmentEntity entity, Long userId) {
    boolean result = false;
    if (entity.getAndId() != null) {
      result =
          switch (entity.getModelName()) {
            case ModelName.BUSINESS_ACTIVITY -> {
              BusinessActivityEntity activity =
                  businessActivityMapper.selectById(entity.getAndId());
              yield activity != null && Objects.equals(activity.getCreatorId(), userId);
            }
            case ModelName.CONTACT_TASK -> {
              ContactTaskEntity task = contactTaskMapper.selectById(entity.getAndId());
              yield task != null
                  && (Objects.equals(task.getCreatorId(), userId)
                      || Objects.equals(task.getAssignerId(), userId)
                      || Objects.equals(task.getAssigneeId(), userId));
            }
            case ModelName.ASSIST_REQUEST -> {
              AssistRequestEntity assist = assistRequestMapper.selectById(entity.getAndId());
              yield assist != null && Objects.equals(assist.getApplicantId(), userId);
            }
            case ModelName.APPROVAL_ATTACHMENT, ModelName.SALES_STAGE_APPROVAL -> {
              SalesStageApprovalEntity approval =
                  salesStageApprovalMapper.selectById(entity.getAndId());
              yield approval != null
                  && (Objects.equals(approval.getApplicantId(), userId)
                      || Objects.equals(approval.getApproverId(), userId));
            }
            default -> false;
          };
    }
    return result;
  }
}
