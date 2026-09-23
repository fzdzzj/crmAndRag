package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.server.service.ApprovalAttachmentService;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协助列表与批量查询协作类（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口：{@code listAssistsByRecords} / {@code listAssistsByRecord} / {@code
 * getRelatedRecordIdsByUser} / {@code getVisibleAssists} / {@code pageMyAssists} / {@code
 * pageMyApplications} / {@code deleteByRecords}。只读查询 + 分页壳装配；{@code deleteByRecords} 的写在主类入口
 * {@code @Transactional} 内进行，本类不带事务注解、不新开事务。
 *
 * <p>{@code listAssistsByRecord} 与 {@code getVisibleAssists} 里的查询走 {@code store} 上的主类入口回调， 与拆分前
 * 「{@code listAssistsByRecord -> listAssistsByRecords}」「{@code getVisibleAssists ->
 * listAssistsByRecord}」两处自我调用 一一对应，既有单测对这两个入口的 stub / verify 仍然成立。
 */
class AssistListQueryCoordinator {

  private final AssistRequestStore store;
  private final AssistVoAssembler voAssembler;
  private final AssistRecordBriefFiller briefFiller;
  private final AssistAccessGuard accessGuard;
  private final ApprovalAttachmentService approvalAttachmentService;

  AssistListQueryCoordinator(
      AssistRequestStore store,
      AssistVoAssembler voAssembler,
      AssistRecordBriefFiller briefFiller,
      AssistAccessGuard accessGuard,
      ApprovalAttachmentService approvalAttachmentService) {
    this.store = store;
    this.voAssembler = voAssembler;
    this.briefFiller = briefFiller;
    this.accessGuard = accessGuard;
    this.approvalAttachmentService = approvalAttachmentService;
  }

  /** 按业务记录批量查询协助记录（含协助人姓名、部门）。 */
  List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds) {
    List<AssistVO> voList = Collections.emptyList();
    if (recordIds != null && !recordIds.isEmpty()) {
      List<AssistRequestEntity> entities =
          store.list(AssistRequestQueries.byRecords(modelName, recordIds));
      voList = voAssembler.toVoList(entities);
    }
    return voList;
  }

  /** 查询某条业务记录的协助记录。 */
  List<AssistVO> listAssistsByRecord(String modelName, Long recordId) {
    return recordId == null
        ? Collections.emptyList()
        : store.listAssistsByRecords(modelName, Collections.singletonList(recordId));
  }

  /** 查询指定用户作为协助人的待处理业务记录 ID 集合。 */
  Set<Long> getRelatedRecordIdsByUser(String modelName, Long userId) {
    Set<Long> recordIds = Collections.emptySet();
    if (modelName != null && userId != null) {
      List<AssistRequestEntity> entities =
          store.list(AssistRequestQueries.pendingByUser(modelName, userId));
      if (!entities.isEmpty()) {
        recordIds =
            entities.stream()
                .map(AssistRequestEntity::getRecordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
      }
    }
    return recordIds;
  }

  /** 按隐私可见性返回某条业务记录的协助列表。 */
  List<AssistVO> getVisibleAssists(String modelName, Long recordId, Long currentUserId) {
    return accessGuard.visibleAssists(
        store.listAssistsByRecord(modelName, recordId), modelName, recordId, currentUserId);
  }

  /** 待我协助分页装配（分页壳 + 内容摘要回填）。 */
  Page<AssistVO> pageMyAssists(Page<AssistRequestEntity> pageResult) {
    return toVoPage(pageResult);
  }

  /** 我发起的协助分页装配（分页壳 + 内容摘要回填）。 */
  Page<AssistVO> pageMyApplications(Page<AssistRequestEntity> pageResult) {
    return toVoPage(pageResult);
  }

  /** 删除某模块下多个业务记录的协助记录（级联删除）。 */
  void deleteByRecords(String modelName, List<Long> recordIds) {
    if (recordIds != null && !recordIds.isEmpty()) {
      List<Long> assistIds =
          store.list(AssistRequestQueries.idsByRecords(modelName, recordIds)).stream()
              .map(AssistRequestEntity::getId)
              .toList();
      // 协助交付物的路径归属的是协助记录。先删文件和元数据，再删协助本身，
      // 避免源业务删除后留下无法访问的附件孤儿记录。
      approvalAttachmentService.removeByAndIds(assistIds, ModelName.ASSIST_REQUEST);
      store.remove(AssistRequestQueries.recordScope(modelName, recordIds));
    }
  }

  private Page<AssistVO> toVoPage(Page<AssistRequestEntity> pageResult) {
    Page<AssistVO> ans =
        new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
    if (pageResult.getRecords().isEmpty()) {
      ans.setRecords(Collections.emptyList());
    } else {
      List<AssistVO> voList = voAssembler.toVoList(pageResult.getRecords());
      briefFiller.fillRecordContent(voList);
      ans.setRecords(voList);
    }
    return ans;
  }
}
