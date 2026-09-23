package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.Objects;

/**
 * 业务责任人 / 可操作性判定（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属能力：{@code isCurrentBusinessResponsible}、{@code isOperable}、{@code canWriteAssistDelivery} 及
 * {@code applyAssists} 的发起前置校验。全部为只读判定 + {@link AssistRequestStore} 上的计数查询， 不写库、不带事务注解、不新开事务。
 *
 * <p>多分支判定按「结果标志 + 短路」单出口化：与拆分前的早返回逐场景等价（同一顺序、同一短路点， 不新增也不减少任何一次查询）。
 */
class AssistResponsibilityResolver {

  private final UserMapper userMapper;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final BusinessActivityMapper businessActivityMapper;
  private final BusinessActivityUserMapper businessActivityUserMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final AssistRequestStore store;

  AssistResponsibilityResolver(
      UserMapper userMapper,
      SalesStageApprovalMapper salesStageApprovalMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      BusinessActivityMapper businessActivityMapper,
      BusinessActivityUserMapper businessActivityUserMapper,
      ContactTaskMapper contactTaskMapper,
      AssistRequestStore store) {
    this.userMapper = userMapper;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.businessActivityMapper = businessActivityMapper;
    this.businessActivityUserMapper = businessActivityUserMapper;
    this.contactTaskMapper = contactTaskMapper;
    this.store = store;
  }

  /** 按用户 ID 读取用户快照（拆自主类的 userMapper.selectById 调用点，语义与调用时机不变）。 */
  UserEntity loadUser(Long userId) {
    return userMapper.selectById(userId);
  }

  /** 超管判定：只认 roleId=1，不附加在职状态（在职闸由各调用点自行把握，与拆分前一致）。 */
  boolean isSuperAdmin(UserEntity user) {
    return user != null && Objects.equals(user.getRoleId(), 1L);
  }

  /** 在职判定：用户存在且 status=1。 */
  boolean isActiveUser(UserEntity user) {
    return user != null && Objects.equals(user.getStatus(), 1);
  }

  /** 发起协助前置校验：只有业务负责人/执行人或超管可以发起。 */
  void requireCanApply(String modelName, Long recordId, Long userId) {
    AssistRequestEntity context = new AssistRequestEntity();
    context.setModelName(modelName);
    context.setRecordId(recordId);
    if (!isSuperAdmin(loadUser(userId)) && !isCurrentBusinessResponsible(context, userId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "只有业务负责人或执行人可以发起协助申请");
    }
  }

  /** 历史申请人不可改写；人员交接后由当前业务负责人接管查看和重申请。 */
  boolean isCurrentBusinessResponsible(AssistRequestEntity assist, Long userId) {
    boolean responsible = false;
    if (assist != null && userId != null && assist.getRecordId() != null) {
      responsible = resolveResponsibleByModel(assist, userId);
    }
    return responsible;
  }

  /** 当前用户是否可操作某条业务记录（申请人/协助人/审批人/创建人/超管）。 */
  boolean canOperate(String modelName, Long recordId, Long userId) {
    boolean operable = modelName != null && recordId != null && userId != null;
    if (operable) {
      operable = resolveOperable(modelName, recordId, userId);
    }
    return operable;
  }

  /** 判断当前用户能否写入协助交付物附件（交付物不属于业务活动/联络任务的普通附件）。 */
  boolean canWriteDelivery(Long assistId, Long userId) {
    boolean writable = false;
    if (assistId != null && userId != null) {
      UserEntity currentUser = loadUser(userId);
      writable =
          isActiveUser(currentUser)
              && (isSuperAdmin(currentUser) || isPendingParticipant(assistId, userId));
    }
    return writable;
  }

  private boolean resolveResponsibleByModel(AssistRequestEntity assist, Long userId) {
    return switch (assist.getModelName()) {
      case ModelName.SALES_STAGE_APPROVAL -> isApprovalOwner(assist.getRecordId(), userId);
      case ModelName.BUSINESS_ACTIVITY -> isActivityResponsible(assist.getRecordId(), userId);
      case ModelName.CONTACT_TASK -> isTaskResponsible(assist.getRecordId(), userId);
      default -> false;
    };
  }

  private boolean isApprovalOwner(Long recordId, Long userId) {
    SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
    SalesOpportunityEntity opportunity =
        approval == null || approval.getOpportunityId() == null
            ? null
            : salesOpportunityMapper.selectById(approval.getOpportunityId());
    return opportunity != null && Objects.equals(opportunity.getOwnerId(), userId);
  }

  private boolean isActivityResponsible(Long recordId, Long userId) {
    BusinessActivityEntity activity = businessActivityMapper.selectById(recordId);
    return activity != null
        && (Objects.equals(activity.getCreatorId(), userId)
            || businessActivityUserMapper.existsByActivityIdAndUserId(recordId, userId) > 0);
  }

  private boolean isTaskResponsible(Long recordId, Long userId) {
    ContactTaskEntity task = contactTaskMapper.selectById(recordId);
    return task != null
        && (Objects.equals(task.getCreatorId(), userId)
            || Objects.equals(task.getAssignerId(), userId)
            || Objects.equals(task.getAssigneeId(), userId));
  }

  private boolean resolveOperable(String modelName, Long recordId, Long userId) {
    return isSuperAdmin(loadUser(userId)) || isOperableByAssistOrModel(modelName, recordId, userId);
  }

  private boolean isOperableByAssistOrModel(String modelName, Long recordId, Long userId) {
    boolean operable;
    if (ModelName.ASSIST_REQUEST.equals(modelName)) {
      operable = isAssistParticipant(recordId, userId);
    } else {
      operable =
          store.count(AssistRequestQueries.operablePending(modelName, recordId, userId)) > 0
              || isModelParticipant(modelName, recordId, userId);
    }
    return operable;
  }

  private boolean isAssistParticipant(Long recordId, Long userId) {
    AssistRequestEntity assist = store.getById(recordId);
    return assist != null
        && (Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId));
  }

  private boolean isModelParticipant(String modelName, Long recordId, Long userId) {
    return switch (modelName) {
      case ModelName.SALES_STAGE_APPROVAL -> {
        SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
        yield approval != null
            && (Objects.equals(approval.getApplicantId(), userId)
                || Objects.equals(approval.getApproverId(), userId));
      }
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
      default -> false;
    };
  }

  private boolean isPendingParticipant(Long assistId, Long userId) {
    AssistRequestEntity assist = store.getById(assistId);
    return assist != null
        && Objects.equals(assist.getAssistStatus(), 0)
        && (Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId));
  }
}
