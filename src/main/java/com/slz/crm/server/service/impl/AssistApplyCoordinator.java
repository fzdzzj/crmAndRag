package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistMessageService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;

/**
 * 申请创建与待协助生命周期协作类（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口方法：{@code createAssists} / {@code updatePendingAssists} / {@code applyAssists} / {@code
 * appendAssists} / {@code cancelPendingByRecord}。事务由这些主类入口统一开启（{@code @Transactional} 注解只留在
 * 主类），本类不带事务注解、不持有 Spring 自代理、不新开事务：落库经 {@link AssistRequestStore}（由 {@link
 * AssistRequestStoreAdapter} 转交主类实例）进行，与拆分前主类内部的自我调用逐处等价。
 *
 * <p>{@code applyAssists} / {@code appendAssists} / {@code syncPendingAssists} 收口处的 {@code
 * createAssists} 走 {@code store.createAssists} 回调主类入口，而不是本类内部直调：这与拆分前「主类自我调用
 * createAssists」在调用图上一一对应（既有单测对该入口的 stub / verify 仍成立），且同为主类目标对象上的直调，不会新增或提前提交事务。
 *
 * <p>守卫式早返回（入参为空即静默跳过、无待协助记录即不落库）保留原语义：这些方法各自只有一处返回， 合并成「单出口」需要把整段主体塞进一个分支里，可读性更差。
 */
class AssistApplyCoordinator {

  private final AssistRequestStore store;
  private final UserMapper userMapper;
  private final ApprovalAttachmentService approvalAttachmentService;
  private final AssistMessageService assistMessageService;
  private final AssistResponsibilityResolver responsibilityResolver;
  private final AssistSnapshotAssembler snapshotAssembler;

  AssistApplyCoordinator(
      AssistRequestStore store,
      UserMapper userMapper,
      ApprovalAttachmentService approvalAttachmentService,
      AssistMessageService assistMessageService,
      AssistResponsibilityResolver responsibilityResolver,
      AssistSnapshotAssembler snapshotAssembler) {
    this.store = store;
    this.userMapper = userMapper;
    this.approvalAttachmentService = approvalAttachmentService;
    this.assistMessageService = assistMessageService;
    this.responsibilityResolver = responsibilityResolver;
    this.snapshotAssembler = snapshotAssembler;
  }

  /** 批量创建协助申请记录：校验 → 幂等 → 在职 → 落库并追加系统消息。 */
  void createAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    if (recordId == null || applyList == null || applyList.isEmpty()) {
      return;
    }
    Map<Long, AssistApplyItem> itemMap =
        AssistApplyRules.validateAndDedupApplyItems(applyList, applicantId);
    AssistApplyRules.requirePurposeAndRequirement(itemMap);
    Set<Long> distinctIds = AssistApplyRules.distinctAssistUserIds(itemMap);
    assertNoPendingDuplicate(modelName, recordId, distinctIds);
    assertAssistUsersActive(distinctIds);
    persistNewAssists(
        AssistApplyRules.buildAssistEntities(
            modelName, recordId, applicantId, itemMap, distinctIds));
  }

  /** 协助人处理协助申请（仅本人可操作）：校验 → 冻结终态快照 → 改写终态 → 落库 → 系统消息。 */
  Boolean handleAssist(AssistHandleDTO dto) {
    AssistHandleRules.validate(dto);
    AssistRequestEntity entity =
        AssistHandleRules.requirePendingAssist(store.getById(dto.getId()), BaseUnit.getCurrentId());

    // 必须先捕获终态前一刻的实时范围，再改变状态；这样快照与待协助期间
    // 协助人实际能看到的记录边界保持一致，快照失败时事务不会留下半更新状态。
    String recordSnapshot = snapshotAssembler.buildRecordSnapshot(entity);

    AssistHandleRules.applyDecision(entity, dto, recordSnapshot);
    if (!store.updateById(entity)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "协助状态更新失败");
    }
    assistMessageService.appendSystemMessage(
        entity.getId(), AssistHandleRules.statusMessage(dto.getAssistStatus()));
    return true;
  }

  /** 同步一条待协助业务记录：删除消失的、就地更新保留的、创建新增的。 */
  void syncPendingAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    if (recordId == null || applyList == null) {
      return;
    }
    Map<Long, AssistApplyItem> itemMap =
        AssistApplyRules.validateApplyItemsForUpdate(applyList, applicantId);
    List<AssistRequestEntity> pendingAssists =
        store.list(AssistRequestQueries.pendingByApplicant(modelName, recordId, applicantId));
    Map<Long, AssistRequestEntity> existingByUser = AssistApplyRules.indexByUser(pendingAssists);

    removeVanishedPendingAssists(pendingAssists, itemMap);
    updateKeptPendingAssists(pendingAssists, itemMap);
    store.createAssists(
        modelName, recordId, applicantId, AssistApplyRules.additions(itemMap, existingByUser));
  }

  /** 由业务创建人、活动参与人或任务执行/指派人发起协助申请。 */
  void applyAssists(String modelName, Long recordId, List<AssistApplyItem> applyList) {
    if (modelName == null || recordId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    Long currentId = BaseUnit.getCurrentId();
    responsibilityResolver.requireCanApply(modelName, recordId, currentId);
    store.createAssists(modelName, recordId, currentId, applyList);
  }

  /** 在同一业务记录上追加新的协助人；不修改、不覆盖既有协助记录。 */
  void appendAssists(Long originalAssistId, List<AssistApplyItem> applyList) {
    if (originalAssistId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity original = store.getById(originalAssistId);
    requireAppendable(original, BaseUnit.getCurrentId(), applyList);
    store.createAssists(
        original.getModelName(), original.getRecordId(), original.getApplicantId(), applyList);
  }

  /** 将某业务记录下仍待协助的记录取消；每条记录先冻结快照，再写入取消原因。 */
  void cancelPendingByRecord(String modelName, Long recordId, String reason) {
    if (modelName == null || recordId == null) {
      return;
    }
    List<AssistRequestEntity> pending =
        store.list(AssistRequestQueries.pendingByRecord(modelName, recordId));
    for (AssistRequestEntity entity : pending) {
      // 快照失败会抛异常，整个审批状态更新也随事务回滚。
      entity.setRecordSnapshot(snapshotAssembler.buildRecordSnapshot(entity));
      entity.setAssistStatus(4);
      entity.setPendingKey(null);
      entity.setCancelReason(AssistApplyRules.trimToNull(reason));
      entity.setAssistTime(LocalDateTime.now());
    }
    if (!pending.isEmpty() && !store.updateBatchById(pending)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "取消待协助记录失败");
    }
    for (AssistRequestEntity entity : pending) {
      assistMessageService.appendSystemMessage(entity.getId(), "协助已取消：" + entity.getCancelReason());
    }
  }

  /** 幂等校验：同一业务记录上同一协助人不能同时存在两条待协助记录。 */
  private void assertNoPendingDuplicate(String modelName, Long recordId, Set<Long> distinctIds) {
    List<AssistRequestEntity> existing =
        store.list(AssistRequestQueries.pendingFor(modelName, recordId, distinctIds));
    if (!existing.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复发起");
    }
  }

  /** 校验协助人存在且在职（status=1）。 */
  private void assertAssistUsersActive(Set<Long> distinctIds) {
    List<UserEntity> users = userMapper.selectBatchIds(distinctIds);
    Set<Long> activeIds =
        users.stream()
            .filter(u -> Objects.equals(u.getStatus(), 1))
            .map(UserEntity::getId)
            .collect(Collectors.toSet());
    for (Long assistUserId : distinctIds) {
      if (!activeIds.contains(assistUserId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不存在或已冻结/离职：用户ID " + assistUserId);
      }
    }
  }

  /** 逐条落库并追加「已发起协助申请」系统消息；唯一索引冲突翻译为明确的重复提交提示。 */
  private void persistNewAssists(List<AssistRequestEntity> entities) {
    for (AssistRequestEntity entity : entities) {
      try {
        store.save(entity);
        assistMessageService.appendSystemMessage(entity.getId(), "已发起协助申请");
      } catch (DuplicateKeyException e) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复提交");
      }
    }
  }

  /** 删除本轮申请中已不再出现的待协助记录（连带清理附件），删除失败报错。 */
  private void removeVanishedPendingAssists(
      List<AssistRequestEntity> pendingAssists, Map<Long, AssistApplyItem> itemMap) {
    List<Long> removedIds = AssistApplyRules.vanishedIds(pendingAssists, itemMap);
    if (!removedIds.isEmpty()) {
      approvalAttachmentService.removeByAndIds(removedIds, ModelName.ASSIST_REQUEST);
      if (!store.removeByIds(removedIds)) {
        throw new BaseException(ErrorCode.UPDATE_FAILED, "删除协助人失败");
      }
    }
  }

  /** 就地更新仍保留的待协助记录的目的/要求字段。 */
  private void updateKeptPendingAssists(
      List<AssistRequestEntity> pendingAssists, Map<Long, AssistApplyItem> itemMap) {
    List<AssistRequestEntity> updates = AssistApplyRules.updateKeptItems(pendingAssists, itemMap);
    if (!updates.isEmpty() && !store.updateBatchById(updates)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "更新协助申请失败");
    }
  }

  /** 追加协助人的前置校验：原记录存在、只有原申请人可追加、已取消不可追加、至少一位协助人。 */
  private void requireAppendable(
      AssistRequestEntity original, Long currentId, List<AssistApplyItem> applyList) {
    if (original == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "原协助记录不存在");
    }
    if (!Objects.equals(original.getApplicantId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "只有原申请人可以追加协助人");
    }
    if (Objects.equals(original.getAssistStatus(), 4)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "已取消的协助申请不能追加协助人");
    }
    if (applyList == null || applyList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择至少一位协助人");
    }
  }
}
