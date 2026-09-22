package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.PermissionService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 统一普通附件记录级读写授权。
 *
 * <p>普通活动/任务附件只按业务记录相关人和预留的部门上司范围授权， 不读取协助关系；实时协助来源和历史快照则走下方独立的 assist 校验路径。
 */
@Service
@RequiredArgsConstructor
public class AttachmentAccessServiceImpl implements AttachmentAccessService {

  private final AssistRequestMapper assistRequestMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final BusinessActivityUserMapper businessActivityUserMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final ContractMapper contractMapper;
  private final ContractOrderItemMapper contractOrderItemMapper;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final UserMapper userMapper;
  private final PermissionService permissionService;
  private final ObjectMapper objectMapper;

  @Override
  public void assertCanRead(ApprovalAttachmentEntity attachment, Long userId) {
    if (attachment == null
        || attachment.getId() == null
        || attachment.getAndId() == null
        || userId == null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "附件关联记录不完整");
    }
    if (!canReadAttachments(attachment.getModelName(), attachment.getAndId(), userId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权读取该附件");
    }
  }

  @Override
  public boolean canReadAttachments(String modelName, Long recordId, Long userId) {
    boolean result = false;
    if (modelName != null && recordId != null && userId != null) {
      UserEntity user = userMapper.selectById(userId);
      if (user != null && Objects.equals(user.getStatus(), 1)) {
        if (Objects.equals(user.getRoleId(), 1L)) {
          result = true;
        } else {
          result =
              switch (modelName) {
                case ModelName.BUSINESS_ACTIVITY ->
                    canReadBusinessActivityAttachments(recordId, userId, modelName);
                case ModelName.CONTACT_TASK -> {
                  if (!permissionService.hasPermission(userId, PermissionOperates.TASK_VIEW_TASK)) {
                    yield false;
                  }
                  ContactTaskEntity task = contactTaskMapper.selectById(recordId);
                  yield (task != null
                          && (Objects.equals(task.getCreatorId(), userId)
                              || Objects.equals(task.getAssignerId(), userId)
                              || Objects.equals(task.getAssigneeId(), userId)))
                      || departmentManagerReadReserved(modelName, recordId, userId);
                }
                case ModelName.SALES_STAGE_APPROVAL, ModelName.APPROVAL_ATTACHMENT -> {
                  if (!permissionService.hasPermission(
                      userId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE)) {
                    yield false;
                  }
                  SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
                  yield (approval != null
                          && (Objects.equals(approval.getApplicantId(), userId)
                              || Objects.equals(approval.getApproverId(), userId)))
                      || departmentManagerReadReserved(modelName, recordId, userId);
                }
                case ModelName.ASSIST_REQUEST -> hasAssistParticipantById(recordId, userId);
                default -> false;
              };
        }
      }
    }
    return result;
  }

  @Override
  public boolean canWriteAttachments(String modelName, Long recordId, Long userId) {
    boolean result = false;
    if (modelName != null && recordId != null && userId != null) {
      UserEntity user = userMapper.selectById(userId);
      if (user != null && Objects.equals(user.getStatus(), 1)) {
        if (Objects.equals(user.getRoleId(), 1L)) {
          result = true;
        } else {
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
                // 协助交付物的写权限还需要生命周期判断，不能从普通接口放行。
                default -> false;
              };
          result = owned || departmentManagerWriteReserved(modelName, recordId, userId);
        }
      }
    }
    return result;
  }

  @Override
  public void assertCanReadAssistSource(
      ApprovalAttachmentEntity attachment, Long assistId, Long userId) {
    if (attachment == null || assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助附件关联信息不完整");
    }
    UserEntity user = requireActiveUser(userId, "当前用户不可访问协助附件");
    AssistRequestEntity assist = assistRequestMapper.selectById(assistId);
    if (!canReadLiveAssist(assist, user, userId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束或当前用户不是协助参与人");
    }
    if (!attachmentBelongsToAssist(assist, attachment)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "附件不属于当前协助来源");
    }
  }

  /** 校验用户存在且在职（status=1），否则抛指定文案的权限异常；返回用户实体供后续判定复用。 */
  private UserEntity requireActiveUser(Long userId, String message) {
    UserEntity user = userMapper.selectById(userId);
    if (user == null || !Objects.equals(user.getStatus(), 1)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, message);
    }
    return user;
  }

  /** 实时来源附件的参与人判定：协助存在、当前用户是参与人（申请人/协助人/超管/联络任务实际参与人）且协助未结束。 */
  private boolean canReadLiveAssist(AssistRequestEntity assist, UserEntity user, Long userId) {
    boolean participant =
        assist != null
            && (Objects.equals(assist.getApplicantId(), userId)
                || Objects.equals(assist.getAssistUserId(), userId)
                || Objects.equals(user.getRoleId(), 1L)
                // 仅联络任务协助允许任务实际参与人读取本条实时来源附件。
                || isContactTaskAssistParticipant(assist, userId));
    return participant && Objects.equals(assist.getAssistStatus(), 0);
  }

  /** 附件必须确实挂在该协助的来源模型与业务记录上，且来源限定为业务活动/联络任务。 */
  private boolean attachmentBelongsToAssist(
      AssistRequestEntity assist, ApprovalAttachmentEntity attachment) {
    return Objects.equals(assist.getModelName(), attachment.getModelName())
        && Objects.equals(assist.getRecordId(), attachment.getAndId())
        && (ModelName.BUSINESS_ACTIVITY.equals(attachment.getModelName())
            || ModelName.CONTACT_TASK.equals(attachment.getModelName()));
  }

  private boolean isContactTaskAssistParticipant(AssistRequestEntity assist, Long userId) {
    boolean result = false;
    if (assist != null
        && ModelName.CONTACT_TASK.equals(assist.getModelName())
        && assist.getRecordId() != null) {
      ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
      result =
          task != null
              && (Objects.equals(task.getCreatorId(), userId)
                  || Objects.equals(task.getAssignerId(), userId)
                  || Objects.equals(task.getAssigneeId(), userId));
    }
    return result;
  }

  @Override
  public void assertCanReadHistorical(
      ApprovalAttachmentEntity attachment, Long assistId, Long userId) {
    if (attachment == null || attachment.getId() == null || assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "历史附件关联信息不完整");
    }
    if (ModelName.ASSIST_REQUEST.equals(attachment.getModelName())) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助交付物不属于历史业务快照");
    }

    // 第一步：令牌中的用户必须仍是有效在职用户。
    UserEntity user = requireActiveUser(userId, "当前用户不可访问历史附件");

    // 第二步：历史令牌只服务于终态协助；待协助附件必须走实时授权入口。
    AssistRequestEntity assist = assistRequestMapper.selectById(assistId);
    if (assist == null || Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "该附件仍属于实时协助范围");
    }

    // 第三步：只有超管、该申请的申请人或协助人可以查看这份历史。
    // 第四步：参与人也不能凭任意附件 ID 下载，附件必须真实出现在该协助冻结的快照中。
    assertHistoricalSnapshotAccess(assist, user, userId, attachment);
  }

  /** 历史附件的参与人与快照归属复核：超管/申请人/协助人之一，且附件真实出现在冻结快照中。 */
  private void assertHistoricalSnapshotAccess(
      AssistRequestEntity assist,
      UserEntity user,
      Long userId,
      ApprovalAttachmentEntity attachment) {
    boolean participant =
        Objects.equals(user.getRoleId(), 1L)
            || Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId);
    if (!participant || !snapshotContainsAttachment(assist.getRecordSnapshot(), attachment)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "附件不属于该协助历史快照");
    }
  }

  /** 将快照 JSON 解析后检查附件归属。解析失败或没有快照时按无权限处理，避免异常放宽授权。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 快照解析+递归匹配多源，解析失败按无权限处理防例外放权
  private boolean snapshotContainsAttachment(String snapshot, ApprovalAttachmentEntity attachment) {
    boolean result = false;
    if (snapshot != null && !snapshot.isBlank()) {
      try {
        result = containsAttachment(objectMapper.readTree(snapshot), attachment);
      } catch (Exception exception) {
        result = false;
      }
    }
    return result;
  }

  /** 递归遍历快照全部层级，匹配附件 ID；新快照再匹配 modelName 和 recordId。 旧快照可能缺少后两项，因此缺失时保持兼容，但只要字段存在就必须与数据库真实记录一致。 */
  private boolean containsAttachment(JsonNode node, ApprovalAttachmentEntity attachment) {
    if (node == null) {
      return false;
    }
    if (node.isObject()
        && node.has("attachmentId")
        && node.get("attachmentId").canConvertToLong()
        && Objects.equals(node.get("attachmentId").longValue(), attachment.getId())) {
      String snapshotModelName =
          node.hasNonNull("modelName") ? node.get("modelName").asText() : null;
      String snapshotRecordId = node.hasNonNull("recordId") ? node.get("recordId").asText() : null;
      return (snapshotModelName == null
              || Objects.equals(snapshotModelName, attachment.getModelName()))
          && (snapshotRecordId == null
              || Objects.equals(snapshotRecordId, String.valueOf(attachment.getAndId())));
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        if (containsAttachment(child, attachment)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasAssistParticipantById(Long assistId, Long userId) {
    return assistRequestMapper.selectCount(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getId, assistId)
                .and(
                    w ->
                        w.eq(AssistRequestEntity::getApplicantId, userId)
                            .or()
                            .eq(AssistRequestEntity::getAssistUserId, userId)))
        > 0;
  }

  @Override
  public boolean canReadProjectFile(ProjectFileEntity file, Long userId) {
    boolean result;
    if (file == null || file.getId() == null || userId == null) {
      result = false;
    } else {
      UserEntity user = userMapper.selectById(userId);
      if (user == null || !Objects.equals(user.getStatus(), 1)) {
        result = false;
      } else if (Objects.equals(user.getRoleId(), 1L)) {
        result = true;
      } else {
        result = canReadProjectFileByDimension(file, userId);
      }
    }
    return result;
  }

  /** 非超管的记录级判定：项目文件可能同时挂多个归属维度，任一维度可读即放行； 无任何归属维度的独立上传文件仅上传人本人可见；其余走部门主管保留通道。 */
  private boolean canReadProjectFileByDimension(ProjectFileEntity file, Long userId) {
    boolean result = false;
    // 项目文件可能同时挂多个归属维度，任一维度可读即放行
    boolean hasDimension = file.getActivityId() != null || file.getOpportunityId() != null;
    if (!result && file.getActivityId() != null) {
      result =
          canReadBusinessActivityAttachments(
              file.getActivityId(), userId, ModelName.BUSINESS_ACTIVITY);
    }
    if (!result && file.getOpportunityId() != null) {
      result = canReadSalesOpportunity(file.getOpportunityId(), userId);
    }
    if (!result) {
      Long contractId = resolveContractId(file);
      if (contractId != null && canReadContract(contractId, userId)) {
        result = true;
      } else if (!hasDimension && contractId == null) {
        // 无任何归属维度的独立上传文件：仅上传人本人可见（超管已在上方放行）
        result = Objects.equals(file.getUploaderId(), userId);
      } else {
        result = departmentManagerReadReserved(ModelName.PROJECT_FILE, file.getId(), userId);
      }
    }
    return result;
  }

  private boolean canReadBusinessActivityAttachments(Long recordId, Long userId, String modelName) {
    if (!permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY)) {
      return false;
    }
    BusinessActivityEntity activity = businessActivityMapper.selectById(recordId);
    return (activity != null
            && (Objects.equals(activity.getCreatorId(), userId)
                || businessActivityUserMapper.existsByActivityIdAndUserId(recordId, userId) > 0))
        || departmentManagerReadReserved(modelName, recordId, userId);
  }

  private boolean canReadSalesOpportunity(Long opportunityId, Long userId) {
    if (!permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY)) {
      return false;
    }
    SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
    return opportunity != null
        && (Objects.equals(opportunity.getOwnerId(), userId)
            || Objects.equals(opportunity.getCreatorId(), userId)
            || Objects.equals(opportunity.getApproverId(), userId));
  }

  private boolean canReadContract(Long contractId, Long userId) {
    if (!permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_CONTRACT)) {
      return false;
    }
    ContractEntity contract = contractMapper.selectById(contractId);
    return contract != null
        && (Objects.equals(contract.getOwnerId(), userId)
            || Objects.equals(contract.getCreatorId(), userId));
  }

  /** 订单维度文件经订单项反查合同，与合同归属共用同一授权判断。 */
  private Long resolveContractId(ProjectFileEntity file) {
    if (file.getOrderId() != null) {
      ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(file.getOrderId());
      if (orderItem != null && orderItem.getContractId() != null) {
        return orderItem.getContractId();
      }
    }
    return file.getContractId();
  }

  /**
   * 部门上司读取权限预留点，待组织架构的上下属规则确定后集中实现。
   *
   * <p>tighten-pmd-residual-325 任务 4.3：三个参数是待实现逻辑（上下属规则）的签名契约，当前方法恒返回 false
   * 故参数未被读取；按「签名契约只豁免、不删参」登记，不删参数以保留未来实现所需的输入意图。
   */
  @SuppressWarnings("PMD.UnusedFormalParameter") // 预留扩展点：参数即未来实现的签名契约，删参等于删掉接口意图
  private boolean departmentManagerReadReserved(String modelName, Long recordId, Long userId) {
    return false;
  }

  /**
   * 部门上司写权限预留点，与读取权限分开，避免查看权限自动变成上传/删除权限。
   *
   * <p>tighten-pmd-residual-325 任务 4.3：同 {@link #departmentManagerReadReserved}，参数为签名契约保留， 当前恒返回
   * false 故未读取。
   */
  @SuppressWarnings("PMD.UnusedFormalParameter") // 同上：读写两处预留点的参数是对称的签名契约，不删
  private boolean departmentManagerWriteReserved(String modelName, Long recordId, Long userId) {
    return false;
  }
}
