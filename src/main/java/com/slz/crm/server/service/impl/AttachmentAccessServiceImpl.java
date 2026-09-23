package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
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
import org.springframework.stereotype.Service;

/**
 * 统一普通附件记录级读写授权。
 *
 * <p>普通活动/任务附件只按业务记录相关人和预留的部门上司范围授权， 不读取协助关系；实时协助来源和历史快照则走下方独立的 assist 校验路径。
 *
 * <p>tighten-pmd-residual-325 任务 6.4 批B：快照递归匹配拆至 {@link AttachmentSnapshotMatcher}、 项目文件多维度判定拆至
 * {@link ProjectFileAttachmentReader}，判定矩阵行为等价（配套反向用例见
 * AttachmentAccessServiceTest），部门上司预留点签名契约与豁免原位保留。
 */
@Service
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
  private final AttachmentSnapshotMatcher snapshotMatcher;
  private final ProjectFileAttachmentReader projectFileReader;
  private final AttachmentModelScopeChecker modelScopeChecker;

  public AttachmentAccessServiceImpl(
      AssistRequestMapper assistRequestMapper,
      BusinessActivityMapper businessActivityMapper,
      BusinessActivityUserMapper businessActivityUserMapper,
      ContactTaskMapper contactTaskMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      ContractMapper contractMapper,
      ContractOrderItemMapper contractOrderItemMapper,
      SalesStageApprovalMapper salesStageApprovalMapper,
      UserMapper userMapper,
      PermissionService permissionService,
      ObjectMapper objectMapper) {
    this.assistRequestMapper = assistRequestMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.businessActivityUserMapper = businessActivityUserMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.contractMapper = contractMapper;
    this.contractOrderItemMapper = contractOrderItemMapper;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.userMapper = userMapper;
    this.permissionService = permissionService;
    this.objectMapper = objectMapper;
    this.snapshotMatcher = new AttachmentSnapshotMatcher(objectMapper);
    this.projectFileReader =
        new ProjectFileAttachmentReader(
            businessActivityMapper,
            businessActivityUserMapper,
            salesOpportunityMapper,
            contractMapper,
            contractOrderItemMapper,
            permissionService,
            this::departmentManagerReadReserved);
    this.modelScopeChecker =
        new AttachmentModelScopeChecker(
            businessActivityMapper,
            contactTaskMapper,
            salesStageApprovalMapper,
            assistRequestMapper,
            permissionService,
            projectFileReader,
            this::departmentManagerReadReserved,
            this::departmentManagerWriteReserved);
  }

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
        // 超管直通；其余按 modelName 路由到业务记录级判定（拆至 AttachmentModelScopeChecker）
        result =
            Objects.equals(user.getRoleId(), 1L)
                || modelScopeChecker.canRead(modelName, recordId, userId);
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
        result =
            Objects.equals(user.getRoleId(), 1L)
                || modelScopeChecker.canWrite(modelName, recordId, userId);
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
    if (!participant || !snapshotMatcher.contains(assist.getRecordSnapshot(), attachment)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "附件不属于该协助历史快照");
    }
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
        result = projectFileReader.canReadByDimension(file, userId);
      }
    }
    return result;
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
