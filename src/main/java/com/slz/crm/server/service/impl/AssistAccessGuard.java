package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistVO;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 协助记录可见性与参与人守卫（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属能力：协助详情可见性、来源附件统一闸门（{@code requirePendingAssistSource}）、联络任务参与人闸门、
 * 协助列表隐私过滤。全部为只读判定，不写库、不带事务注解、不新开事务。
 *
 * <p>守卫式早返回保留原语义：每个方法各自只有一处返回，为「单出口」把整段判定塞进分支只会更晦涩； 除 {@code visibleAssists}
 * 外全部以异常收口，与拆分前的抛出点、错误码、提示语逐处一致。
 */
class AssistAccessGuard {

  private final AssistRequestStore store;
  private final AssistResponsibilityResolver responsibilityResolver;

  AssistAccessGuard(AssistRequestStore store, AssistResponsibilityResolver responsibilityResolver) {
    this.store = store;
    this.responsibilityResolver = responsibilityResolver;
  }

  /** 校验协助记录访问权。实时关联详情额外要求记录仍处于待协助状态。 */
  AssistRequestEntity requireVisibleAssist(Long id, boolean pendingRequired) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity entity = store.getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    boolean visible =
        responsibilityResolver.isSuperAdmin(responsibilityResolver.loadUser(currentId))
            || Objects.equals(entity.getApplicantId(), currentId)
            || Objects.equals(entity.getAssistUserId(), currentId)
            || responsibilityResolver.isCurrentBusinessResponsible(entity, currentId);
    if (!visible) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (pendingRequired && !Objects.equals(entity.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时关联详情");
    }
    return entity;
  }

  /**
   * 专用来源附件的统一闸门。 普通活动/任务页面不调用这里；它们使用普通记录权限。这里额外要求 pending 状态， 让下载令牌、上传和删除都绑定同一个
   * assistId，不会因“同一商机可见”扩大到其他附件。
   */
  AssistRequestEntity requirePendingAssistSource(
      Long assistId, String expectedModelName, Long userId) {
    if (assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist = store.getById(assistId);
    if (assist == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    requireAssistParticipant(assist, userId, "当前用户不是本次协助参与人");
    if (!Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时来源附件");
    }
    if (expectedModelName != null && !Objects.equals(expectedModelName, assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助来源与请求详情类型不匹配");
    }
    return assist;
  }

  /**
   * 联络任务的协助页面允许任务实际参与人共同补充来源材料。
   *
   * <p>仅覆盖当前待协助记录，且只用于读取、上传任务来源附件；删除仍走 {@link #requirePendingAssistSource(Long, String,
   * Long)}，不会因任务参与关系扩大删除权。
   */
  AssistRequestEntity requirePendingTaskAssistParticipant(Long assistId, Long userId) {
    if (assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist = store.getById(assistId);
    if (assist == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    if (!ModelName.CONTACT_TASK.equals(assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不是联络任务");
    }
    requireTaskParticipant(assist, userId);
    if (!Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时来源附件");
    }
    return assist;
  }

  /** 协助列表隐私过滤：超管/申请人/业务负责人可见全部，协助人只可见指派给自己的，其他人返回空列表。 */
  List<AssistVO> visibleAssists(
      List<AssistVO> all, String modelName, Long recordId, Long currentUserId) {
    List<AssistVO> visible = Collections.emptyList();
    if (!all.isEmpty() && currentUserId != null) {
      visible = resolveVisible(all, modelName, recordId, currentUserId);
    }
    return visible;
  }

  /** 校验用户存在且在职，并要求是本次协助参与人（管理员或申请人/协助人）。 */
  private void requireAssistParticipant(
      AssistRequestEntity assist, Long userId, String participantMessage) {
    UserEntity user = responsibilityResolver.loadUser(userId);
    if (!responsibilityResolver.isActiveUser(user)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不可访问协助来源附件");
    }
    boolean participant =
        responsibilityResolver.isSuperAdmin(user)
            || Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId);
    if (!participant) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, participantMessage);
    }
  }

  /** 校验用户存在且在职，并要求是本次协助的任务参与人（管理员/申请人/协助人/业务负责人）。 */
  private void requireTaskParticipant(AssistRequestEntity assist, Long userId) {
    UserEntity user = responsibilityResolver.loadUser(userId);
    if (!responsibilityResolver.isActiveUser(user)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不可访问协助来源附件");
    }
    boolean participant =
        responsibilityResolver.isSuperAdmin(user)
            || Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId)
            || responsibilityResolver.isCurrentBusinessResponsible(assist, userId);
    if (!participant) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不是本次协助的任务参与人");
    }
  }

  private List<AssistVO> resolveVisible(
      List<AssistVO> all, String modelName, Long recordId, Long currentUserId) {
    // 超管豁免：与列表可见性保持一致，超管可见全部协助记录
    boolean allVisible =
        responsibilityResolver.isSuperAdmin(responsibilityResolver.loadUser(currentUserId))
            || all.stream().anyMatch(vo -> Objects.equals(vo.getApplicantId(), currentUserId));
    if (!allVisible) {
      AssistRequestEntity context = new AssistRequestEntity();
      context.setModelName(modelName);
      context.setRecordId(recordId);
      allVisible = responsibilityResolver.isCurrentBusinessResponsible(context, currentUserId);
    }
    return allVisible
        ? all
        : all.stream().filter(vo -> Objects.equals(vo.getAssistUserId(), currentUserId)).toList();
  }
}
