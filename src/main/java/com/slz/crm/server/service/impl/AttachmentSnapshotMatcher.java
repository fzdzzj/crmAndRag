package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import java.util.Objects;

/**
 * 协助历史快照 JSON 匹配协作类（tighten-pmd-residual-325 任务 6.4 批B：拆自 {@link
 * AttachmentAccessServiceImpl}，行为等价）。
 *
 * <p>只负责「附件是否出现在冻结快照中」的递归判定；参与人身份与协助状态判定仍归主服务。 解析失败或没有快照时一律按无权限处理，避免异常放宽授权。
 */
class AttachmentSnapshotMatcher {

  private final ObjectMapper objectMapper;

  AttachmentSnapshotMatcher(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 解析快照 JSON 并检查附件归属；解析失败或无快照返回 false（fail-closed）。
   *
   * <p>拆自 snapshotContainsAttachment（tighten-pmd-residual-325 任务 6.4 批B，行为等价）。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 快照解析+递归匹配多源，解析失败按无权限处理防例外放权
  boolean contains(String snapshot, ApprovalAttachmentEntity attachment) {
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

  /**
   * 递归遍历快照全部层级，匹配附件 ID；新快照再匹配 modelName 和 recordId。
   *
   * <p>旧快照可能缺少后两项，因此缺失时保持兼容，但只要字段存在就必须与数据库真实记录一致。 单出口改写（tighten-pmd-residual-325 任务 6.4
   * 批B）：守卫与递归命中统一收敛到 result 标志，匹配语义不变。
   */
  private boolean containsAttachment(JsonNode node, ApprovalAttachmentEntity attachment) {
    boolean matched = false;
    if (node != null
        && node.isObject()
        && node.has("attachmentId")
        && node.get("attachmentId").canConvertToLong()
        && Objects.equals(node.get("attachmentId").longValue(), attachment.getId())) {
      String snapshotModelName =
          node.hasNonNull("modelName") ? node.get("modelName").asText() : null;
      String snapshotRecordId = node.hasNonNull("recordId") ? node.get("recordId").asText() : null;
      matched =
          (snapshotModelName == null
                  || Objects.equals(snapshotModelName, attachment.getModelName()))
              && (snapshotRecordId == null
                  || Objects.equals(snapshotRecordId, String.valueOf(attachment.getAndId())));
    }
    if (!matched && node != null && node.isContainerNode()) {
      for (JsonNode child : node) {
        if (containsAttachment(child, attachment)) {
          matched = true;
          break;
        }
      }
    }
    return matched;
  }
}
