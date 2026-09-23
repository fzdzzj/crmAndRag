package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import java.util.Collection;

/**
 * 协助记录查询条件拼装（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>纯静态、零查询：这里只产出 {@link LambdaQueryWrapper}，执行一律由调用方（支持类经 {@link
 * AssistRequestStore}）负责。搬运期间条件字段与顺序一字未改。
 */
final class AssistRequestQueries {

  private AssistRequestQueries() {}

  /** 按业务记录批量查询协助记录的完整条件（按 id 升序，与列表展示顺序一致）。 */
  static LambdaQueryWrapper<AssistRequestEntity> byRecords(
      String modelName, Collection<Long> recordIds) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .in(AssistRequestEntity::getRecordId, recordIds)
        .orderByAsc(AssistRequestEntity::getId);
  }

  /** 只取 id 列的批量查询条件（级联删除先查 assistId）。 */
  static LambdaQueryWrapper<AssistRequestEntity> idsByRecords(
      String modelName, Collection<Long> recordIds) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .select(AssistRequestEntity::getId)
        .eq(AssistRequestEntity::getModelName, modelName)
        .in(AssistRequestEntity::getRecordId, recordIds);
  }

  /** 级联删除用条件：只按模块与业务记录收敛范围（不带排序，供 delete 使用）。 */
  static LambdaQueryWrapper<AssistRequestEntity> recordScope(
      String modelName, Collection<Long> recordIds) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .in(AssistRequestEntity::getRecordId, recordIds);
  }

  /** 某用户作为协助人的待协助记录条件。 */
  static LambdaQueryWrapper<AssistRequestEntity> pendingByUser(String modelName, Long userId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getAssistUserId, userId)
        .eq(AssistRequestEntity::getAssistStatus, 0);
  }

  /** 某业务记录上某申请人的全部待协助记录条件。 */
  static LambdaQueryWrapper<AssistRequestEntity> pendingByApplicant(
      String modelName, Long recordId, Long applicantId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getRecordId, recordId)
        .eq(AssistRequestEntity::getApplicantId, applicantId)
        .eq(AssistRequestEntity::getAssistStatus, 0);
  }

  /** 某业务记录上全部待协助记录条件（取消待协助用）。 */
  static LambdaQueryWrapper<AssistRequestEntity> pendingByRecord(String modelName, Long recordId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getRecordId, recordId)
        .eq(AssistRequestEntity::getAssistStatus, 0);
  }

  /** 幂等校验：同一业务记录上这批协助人已有的待协助记录（只取协助人列）。 */
  static LambdaQueryWrapper<AssistRequestEntity> pendingFor(
      String modelName, Long recordId, Collection<Long> assistUserIds) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getRecordId, recordId)
        .in(AssistRequestEntity::getAssistUserId, assistUserIds)
        .eq(AssistRequestEntity::getAssistStatus, 0)
        .select(AssistRequestEntity::getAssistUserId);
  }

  /** 重申请幂等校验：同一业务同一协助人是否已有待协助记录。 */
  static LambdaQueryWrapper<AssistRequestEntity> pendingCountOf(
      String modelName, Long recordId, Long assistUserId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getRecordId, recordId)
        .eq(AssistRequestEntity::getAssistUserId, assistUserId)
        .eq(AssistRequestEntity::getAssistStatus, 0);
  }

  /** 重申请链校验：以原记录为父节点的子记录。 */
  static LambdaQueryWrapper<AssistRequestEntity> childrenOf(Long parentId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getParentId, parentId);
  }

  /** 待我协助分页条件（可选状态过滤，按创建时间倒序）。 */
  static LambdaQueryWrapper<AssistRequestEntity> byAssistUser(
      Long assistUserId, Integer assistStatus) {
    LambdaQueryWrapper<AssistRequestEntity> wrapper =
        new LambdaQueryWrapper<AssistRequestEntity>()
            .eq(AssistRequestEntity::getAssistUserId, assistUserId)
            .orderByDesc(AssistRequestEntity::getCreateTime);
    if (assistStatus != null) {
      wrapper.eq(AssistRequestEntity::getAssistStatus, assistStatus);
    }
    return wrapper;
  }

  /** 我发起的协助分页条件（可选状态过滤，按创建时间倒序）。 */
  static LambdaQueryWrapper<AssistRequestEntity> byApplicant(
      Long applicantId, Integer assistStatus) {
    LambdaQueryWrapper<AssistRequestEntity> wrapper =
        new LambdaQueryWrapper<AssistRequestEntity>()
            .eq(AssistRequestEntity::getApplicantId, applicantId)
            .orderByDesc(AssistRequestEntity::getCreateTime);
    if (assistStatus != null) {
      wrapper.eq(AssistRequestEntity::getAssistStatus, assistStatus);
    }
    return wrapper;
  }

  /** 可操作性判定：该业务记录上是否存在以该用户为申请人或协助人的待协助记录。 */
  static LambdaQueryWrapper<AssistRequestEntity> operablePending(
      String modelName, Long recordId, Long userId) {
    return new LambdaQueryWrapper<AssistRequestEntity>()
        .eq(AssistRequestEntity::getModelName, modelName)
        .eq(AssistRequestEntity::getRecordId, recordId)
        .eq(AssistRequestEntity::getAssistStatus, 0)
        .and(
            w ->
                w.eq(AssistRequestEntity::getApplicantId, userId)
                    .or()
                    .eq(AssistRequestEntity::getAssistUserId, userId));
  }
}
