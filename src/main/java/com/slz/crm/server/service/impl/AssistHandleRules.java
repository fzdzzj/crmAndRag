package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 协助处理入参校验与终态改写（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>纯静态、零查询、零落库：状态机终态的合法性与实体字段改写在这里，记录加载与落库由调用方负责。 错误码、提示语、字段清空规则（通过填内容清理由，驳回/拒绝填理由清内容）一字未改。
 */
final class AssistHandleRules {

  private AssistHandleRules() {}

  /** 协助处理入参校验：记录必填、终态合法、按终态要求内容/理由必填。 */
  static void validate(AssistHandleDTO dto) {
    requireBase(dto);
    requireStatusPayload(dto);
  }

  /** 校验协助处理基础入参：记录 ID 必填且协助状态合法（1 已协助/2 已驳回/3 已拒绝）。 */
  static void requireBase(AssistHandleDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    if (dto.getAssistStatus() == null
        || (!dto.getAssistStatus().equals(1)
            && !dto.getAssistStatus().equals(2)
            && !dto.getAssistStatus().equals(3))) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助状态只能为：1已协助/2已驳回/3已拒绝");
    }
  }

  /** 按终态校验内容/理由必填。 */
  static void requireStatusPayload(AssistHandleDTO dto) {
    if (dto.getAssistStatus().equals(1)
        && (dto.getAssistContent() == null || dto.getAssistContent().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "协助内容不能为空");
    }
    if ((dto.getAssistStatus().equals(2) || dto.getAssistStatus().equals(3))
        && (dto.getRejectReason() == null || dto.getRejectReason().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "驳回/拒绝理由不能为空");
    }
  }

  /** 待处理记录校验：存在、当前用户是协助人、仍处于待协助状态。 */
  static AssistRequestEntity requirePendingAssist(AssistRequestEntity entity, Long currentUserId) {
    if (entity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    if (!Objects.equals(entity.getAssistUserId(), currentUserId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Objects.equals(entity.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已处理");
    }
    return entity;
  }

  /** 按处理决定改写实体状态字段并冻结业务快照（1 已协助填内容，2/3 驳回/拒绝填理由）。 */
  static void applyDecision(
      AssistRequestEntity entity, AssistHandleDTO dto, String recordSnapshot) {
    entity.setAssistStatus(dto.getAssistStatus());
    entity.setPendingKey(null);
    if (dto.getAssistStatus().equals(1)) {
      entity.setAssistContent(dto.getAssistContent().trim());
      entity.setRejectReason(null);
    } else {
      entity.setRejectReason(dto.getRejectReason().trim());
      entity.setAssistContent(null);
    }
    // 任一终态均冻结按来源模型收窄后的业务快照；交付物不写入快照，单独按 assistId 查询。
    entity.setRecordSnapshot(recordSnapshot);
    entity.setAssistTime(LocalDateTime.now());
  }

  /** 终态对应的系统消息文案（1 已完成 / 2 已驳回 / 其余按 3 已拒绝）。 */
  static String statusMessage(Integer assistStatus) {
    String message = "协助已拒绝";
    if (Objects.equals(assistStatus, 1)) {
      message = "协助已完成";
    } else if (Objects.equals(assistStatus, 2)) {
      message = "协助已驳回";
    }
    return message;
  }
}
