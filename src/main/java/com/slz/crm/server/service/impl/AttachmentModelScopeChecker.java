package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.service.PermissionService;
import java.util.Objects;

/**
 * 附件模型分支读写判定协作类（tighten-pmd-residual-325 任务 6.4 批C补拆：拆自 {@link AttachmentAccessServiceImpl}，行为等价）。
 *
 * <p>职责：按 modelName 路由的业务记录级读/写范围判定（活动/联络任务/审批/协助交付物）。 用户状态与超管闸门仍归主服务；部门上司读写预留点经回调注入，签名契约不迁移。
 */
class AttachmentModelScopeChecker {

  /** 部门上司写权限预留点回调：本体留在 AttachmentAccessServiceImpl（豁免与签名契约不迁移）。 */
  @FunctionalInterface
  interface ReservedWrite {
    boolean reserved(String modelName, Long recordId, Long userId);
  }

  private final BusinessActivityMapper businessActivityMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final AssistRequestMapper assistRequestMapper;
  private final PermissionService permissionService;
  private final ProjectFileAttachmentReader projectFileReader;
  private final ProjectFileAttachmentReader.ReservedRead reservedRead;
  private final ReservedWrite reservedWrite;

  AttachmentModelScopeChecker(
      BusinessActivityMapper businessActivityMapper,
      ContactTaskMapper contactTaskMapper,
      SalesStageApprovalMapper salesStageApprovalMapper,
      AssistRequestMapper assistRequestMapper,
      PermissionService permissionService,
      ProjectFileAttachmentReader projectFileReader,
      ProjectFileAttachmentReader.ReservedRead reservedRead,
      ReservedWrite reservedWrite) {
    this.businessActivityMapper = businessActivityMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.assistRequestMapper = assistRequestMapper;
    this.permissionService = permissionService;
    this.projectFileReader = projectFileReader;
    this.reservedRead = reservedRead;
    this.reservedWrite = reservedWrite;
  }

  /** 非超管的读范围判定：按 modelName 路由到对应业务记录的参与人判定。 */
  boolean canRead(String modelName, Long recordId, Long userId) {
    return switch (modelName) {
      case ModelName.BUSINESS_ACTIVITY ->
          projectFileReader.canReadBusinessActivityAttachments(recordId, userId, modelName);
      case ModelName.CONTACT_TASK -> canReadContactTaskAttachments(modelName, recordId, userId);
      case ModelName.SALES_STAGE_APPROVAL, ModelName.APPROVAL_ATTACHMENT ->
          canReadApprovalAttachments(modelName, recordId, userId);
      case ModelName.ASSIST_REQUEST -> hasAssistParticipantById(recordId, userId);
      default -> false;
    };
  }

  /** 非超管的写范围判定：按 modelName 路由；协助交付物的写权限还需生命周期判断，不能从普通接口放行。 */
  boolean canWrite(String modelName, Long recordId, Long userId) {
    boolean owned =
        switch (modelName) {
          case ModelName.BUSINESS_ACTIVITY -> {
            BusinessActivityEntity activity = businessActivityMapper.selectById(recordId);
            yield activity != null && Objects.equals(activity.getCreatorId(), userId);
          }
          case ModelName.CONTACT_TASK -> {
            ContactTaskEntity task = contactTaskMapper.selectById(recordId);
            yield task != null
                && (Objects.equals(task.getCreatorId(), userId)
                    || Objects.equals(task.getAssignerId(), userId)
                    || Objects.equals(task.getAssigneeId(), userId));
          }
          case ModelName.SALES_STAGE_APPROVAL, ModelName.APPROVAL_ATTACHMENT -> {
            SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
            yield approval != null
                && (Objects.equals(approval.getApplicantId(), userId)
                    || Objects.equals(approval.getApproverId(), userId));
          }
          default -> false;
        };
    return owned || reservedWrite.reserved(modelName, recordId, userId);
  }

  private boolean canReadContactTaskAttachments(String modelName, Long recordId, Long userId) {
    boolean result;
    if (!permissionService.hasPermission(userId, PermissionOperates.TASK_VIEW_TASK)) {
      result = false;
    } else {
      ContactTaskEntity task = contactTaskMapper.selectById(recordId);
      result =
          (task != null
                  && (Objects.equals(task.getCreatorId(), userId)
                      || Objects.equals(task.getAssignerId(), userId)
                      || Objects.equals(task.getAssigneeId(), userId)))
              || reservedRead.reserved(modelName, recordId, userId);
    }
    return result;
  }

  private boolean canReadApprovalAttachments(String modelName, Long recordId, Long userId) {
    boolean result;
    if (!permissionService.hasPermission(
        userId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE)) {
      result = false;
    } else {
      SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
      result =
          (approval != null
                  && (Objects.equals(approval.getApplicantId(), userId)
                      || Objects.equals(approval.getApproverId(), userId)))
              || reservedRead.reserved(modelName, recordId, userId);
    }
    return result;
  }

  private boolean hasAssistParticipantById(Long assistId, Long userId) {
    return assistRequestMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                    AssistRequestEntity>()
                .eq(AssistRequestEntity::getId, assistId)
                .and(
                    w ->
                        w.eq(AssistRequestEntity::getApplicantId, userId)
                            .or()
                            .eq(AssistRequestEntity::getAssistUserId, userId)))
        > 0;
  }
}
