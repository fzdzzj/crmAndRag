package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协助申请项校验与实体装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>纯静态、零查询、零落库：申请/重申请两条链的入参校验、去重、实体装配全在这里，DB 与事务交给调用方 （{@link AssistApplyCoordinator} / {@link
 * AssistReapplyCoordinator}）。错误码与提示语一字未改。
 */
final class AssistApplyRules {

  private AssistApplyRules() {}

  /** 申请项校验：协助人必选、不能是申请人自己、不重复，返回按协助人去重后的申请项映射。 */
  static Map<Long, AssistApplyItem> validateAndDedupApplyItems(
      List<AssistApplyItem> applyList, Long applicantId) {
    Map<Long, AssistApplyItem> itemMap = new LinkedHashMap<>();
    for (AssistApplyItem item : applyList) {
      if (item == null || item.getAssistUserId() == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
      }
      if (item.getAssistUserId().equals(applicantId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
      }
      if (itemMap.putIfAbsent(item.getAssistUserId(), item) != null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "同一次协助申请不能重复选择同一协助人");
      }
    }
    if (itemMap.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
    }
    return itemMap;
  }

  /** 更新场景的申请项校验：协助人必选、不能是申请人自己、目的/要求必填、不重复。 */
  static Map<Long, AssistApplyItem> validateApplyItemsForUpdate(
      List<AssistApplyItem> applyList, Long applicantId) {
    Map<Long, AssistApplyItem> itemMap = new LinkedHashMap<>();
    for (AssistApplyItem item : applyList) {
      if (item == null || item.getAssistUserId() == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
      }
      if (Objects.equals(item.getAssistUserId(), applicantId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
      }
      if (trimToNull(item.getApplyPurpose()) == null
          || trimToNull(item.getApplyRequirement()) == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "每位协助人都必须填写协作目的与协作要求");
      }
      if (itemMap.putIfAbsent(item.getAssistUserId(), item) != null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "同一次协助申请不能重复选择同一协助人");
      }
    }
    return itemMap;
  }

  /** 创建场景补充校验：每位协助人都必须填写协作目的与协作要求。 */
  static void requirePurposeAndRequirement(Map<Long, AssistApplyItem> itemMap) {
    for (AssistApplyItem item : itemMap.values()) {
      if (trimToNull(item.getApplyPurpose()) == null
          || trimToNull(item.getApplyRequirement()) == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "每位协助人都必须填写协作目的与协作要求");
      }
    }
  }

  /** 由申请项构建待保存的协助申请实体列表（状态置待协助、pendingKey=PENDING）。 */
  static List<AssistRequestEntity> buildAssistEntities(
      String modelName,
      Long recordId,
      Long applicantId,
      Map<Long, AssistApplyItem> itemMap,
      Set<Long> distinctIds) {
    return distinctIds.stream()
        .map(
            assistUserId ->
                assembleAssist(
                    modelName, recordId, applicantId, assistUserId, itemMap.get(assistUserId)))
        .collect(Collectors.toList());
  }

  /** 按协助人去重后的申请项映射还原为协助人集合（保持插入顺序）。 */
  static Set<Long> distinctAssistUserIds(Map<Long, AssistApplyItem> itemMap) {
    return new LinkedHashSet<>(itemMap.keySet());
  }

  /** 现有待协助记录按协助人建索引（用于判断哪些是新增、哪些要保留）。 */
  static Map<Long, AssistRequestEntity> indexByUser(List<AssistRequestEntity> pendingAssists) {
    return pendingAssists.stream()
        .collect(Collectors.toMap(AssistRequestEntity::getAssistUserId, entity -> entity));
  }

  /** 本轮申请中已不再出现的待协助记录 id。 */
  static List<Long> vanishedIds(
      List<AssistRequestEntity> pendingAssists, Map<Long, AssistApplyItem> itemMap) {
    return pendingAssists.stream()
        .filter(entity -> !itemMap.containsKey(entity.getAssistUserId()))
        .map(AssistRequestEntity::getId)
        .toList();
  }

  /** 仍保留的待协助记录就地改写目的/要求字段。 */
  static List<AssistRequestEntity> updateKeptItems(
      List<AssistRequestEntity> pendingAssists, Map<Long, AssistApplyItem> itemMap) {
    return pendingAssists.stream()
        .filter(entity -> itemMap.containsKey(entity.getAssistUserId()))
        .peek(
            entity -> {
              AssistApplyItem item = itemMap.get(entity.getAssistUserId());
              entity.setApplyPurpose(trimToNull(item.getApplyPurpose()));
              entity.setApplyRequirement(trimToNull(item.getApplyRequirement()));
            })
        .collect(Collectors.toList());
  }

  /** 本轮新增的申请项（现有没有的协助人）。 */
  static List<AssistApplyItem> additions(
      Map<Long, AssistApplyItem> itemMap, Map<Long, AssistRequestEntity> existingByUser) {
    return itemMap.entrySet().stream()
        .filter(entry -> !existingByUser.containsKey(entry.getKey()))
        .map(Map.Entry::getValue)
        .collect(Collectors.toList());
  }

  /** 重新申请的条目校验：仅一条、协助人必选且必须是原协助人、不能是自己、目的/要求必填。 */
  static AssistApplyItem validateReapplyItem(
      List<AssistApplyItem> applyList, AssistRequestEntity original, Long currentId) {
    if (applyList == null || applyList.size() != 1) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "重新申请只能针对原协助人逐条发起");
    }
    AssistApplyItem item = applyList.get(0);
    if (item == null || item.getAssistUserId() == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
    }
    if (!Objects.equals(item.getAssistUserId(), original.getAssistUserId())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "重新申请只能发送给原协助人");
    }
    if (Objects.equals(item.getAssistUserId(), currentId)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
    }
    if (trimToNull(item.getApplyPurpose()) == null
        || trimToNull(item.getApplyRequirement()) == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "协作目的与协作要求不能为空");
    }
    return item;
  }

  /** 由原记录与新条目构建重申请实体（挂 parentId 形成重申请链）。 */
  static AssistRequestEntity buildReapplyEntity(
      AssistRequestEntity original, Long currentId, AssistApplyItem item, Long originalAssistId) {
    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setModelName(original.getModelName());
    entity.setRecordId(original.getRecordId());
    entity.setApplicantId(currentId);
    entity.setApplyPurpose(trimToNull(item.getApplyPurpose()));
    entity.setApplyRequirement(trimToNull(item.getApplyRequirement()));
    entity.setAssistUserId(original.getAssistUserId());
    entity.setAssistStatus(0);
    entity.setPendingKey("PENDING");
    entity.setParentId(originalAssistId);
    entity.setCreateTime(LocalDateTime.now());
    return entity;
  }

  /** 去空白后为空一律归 null（拆分前主类私有 trimToNull 的逐行等价实现）。 */
  static String trimToNull(String value) {
    String result = null;
    if (value != null) {
      String trimmed = value.trim();
      if (!trimmed.isEmpty()) {
        result = trimmed;
      }
    }
    return result;
  }

  private static AssistRequestEntity assembleAssist(
      String modelName, Long recordId, Long applicantId, Long assistUserId, AssistApplyItem item) {
    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setModelName(modelName);
    entity.setRecordId(recordId);
    entity.setApplicantId(applicantId);
    entity.setApplyPurpose(trimToNull(item.getApplyPurpose()));
    entity.setApplyRequirement(trimToNull(item.getApplyRequirement()));
    entity.setAssistUserId(assistUserId);
    entity.setAssistStatus(0);
    entity.setPendingKey("PENDING");
    entity.setCreateTime(LocalDateTime.now());
    return entity;
  }
}
