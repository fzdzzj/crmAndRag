package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 商业活动语义文本拼装支持类（add-business-activity-semantic-retrieval 任务 2.1）。纯静态、无状态。
 *
 * <p>拼文本口径：非空字段按「活动标题、活动类型、关联客户、关联商机、沟通内容、备注、参与人、联系人」固定顺序输出 「字段名: 值」，以「；」连接；全空字段返回空串（调用方跳过嵌入）。文本值
 * trim 后写入。
 *
 * <p>参与人段取 business_activity_user 关联（按 id 升序、上限 5 人），展示为「参与人: 用户ID（角色）」； 联系人段由摄取服务按客户联系人姓名 +
 * 活动角色（business_activity_contact.contact_role）解析后传入，展示为 「联系人: 姓名（角色）」，两段共用 {@link
 * #buildNamedSegment} 分隔口径（「；」连接、「（角色）」后缀）。
 *
 * <p>文档/切片标识口径：documentId = chunkId = {@code BUSINESS_ACTIVITY:{主键}}；Qdrant point id 由该口径经 UUID v3
 * 确定性派生（nameUUIDFromBytes），重摄取幂等。
 */
final class BusinessActivitySemanticTextSupport {

  private BusinessActivitySemanticTextSupport() {}

  /** 商业活动向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "BUSINESS_ACTIVITY:";

  /** 参与人/联系人附段上限（防语义膨胀保险丝）。 */
  static final int SEGMENT_MAX = 5;

  /** 参与人段标签。 */
  static final String PARTICIPANT_LABEL = "参与人: ";

  /** 联系人段标签。 */
  static final String CONTACT_LABEL = "联系人: ";

  /**
   * 向量 documentId 口径：BUSINESS_ACTIVITY:{主键}（SourceReference.documentId 同口径，按文档删除依赖）。
   *
   * @param id 商业活动主键
   * @return documentId
   */
  static String documentId(Long id) {
    return ID_PREFIX + id;
  }

  /**
   * Qdrant point id：业务主键经 UUID v3 确定性派生（nameUUIDFromBytes），重摄取幂等。
   *
   * @param id 商业活动主键
   * @return UUID 字符串
   */
  static String pointId(Long id) {
    return UUID.nameUUIDFromBytes((ID_PREFIX + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * 拼装商业活动语义文本（非空字段「字段名: 值」标签化，「；」连接）。
   *
   * @param entity 商业活动实体
   * @param companyName 关联客户公司名称（可空）
   * @param opportunityName 关联商机名称（可空）
   * @param participantSegment 活动参与人段（可空；由 {@link #buildParticipantSegment} 产出）
   * @param contactSegment 活动联系人段（可空；由 {@link #buildContactSegment} 产出）
   * @return 语义文本；全空字段返回空串
   */
  static String buildSemanticText(
      BusinessActivityEntity entity,
      String companyName,
      String opportunityName,
      String participantSegment,
      String contactSegment) {
    if (entity == null) {
      throw new IllegalArgumentException("商业活动实体不能为空");
    }
    StringBuilder sb = new StringBuilder();
    appendField(sb, "活动标题", entity.getActivityTitle());
    appendField(sb, "活动类型", entity.getActivityType());
    appendField(sb, "关联客户", companyName);
    appendField(sb, "关联商机", opportunityName);
    appendField(sb, "沟通内容", entity.getActivityContent());
    appendField(sb, "备注", entity.getRemark());
    appendField(sb, "参与人", participantSegment);
    appendField(sb, "联系人", contactSegment);
    return sb.toString();
  }

  /**
   * 参与人段拼装：保留 userId 非空项、按关联记录 id 升序、上限 5 条；段形如 {@code 参与人: 用户ID（角色）}， 多人以「；」连接；角色为空只输出用户 ID。
   *
   * @param participants 活动参与人关联列表（可为 null/空）
   * @return 参与人段文本；无可用参与人返回空串
   */
  static String buildParticipantSegment(List<BusinessActivityUserEntity> participants) {
    StringBuilder sb = new StringBuilder();
    if (participants != null) {
      List<BusinessActivityUserEntity> filtered =
          participants.stream()
              .filter(Objects::nonNull)
              .filter(participant -> participant.getUserId() != null)
              .sorted(Comparator.comparing(BusinessActivityUserEntity::getId))
              .limit(SEGMENT_MAX)
              .toList();
      appendNamedSegment(
          sb,
          PARTICIPANT_LABEL,
          filtered.stream().map(BusinessActivityUserEntity::getUserId).toList(),
          filtered.stream().map(BusinessActivityUserEntity::getUserRole).toList());
    }
    return sb.toString();
  }

  /**
   * 联系人段拼装：段形如 {@code 联系人: 姓名（角色）}，多人以「；」连接、上限 5 条；空白项跳过。
   *
   * @param names 联系人姓名有序列表（与 roles 同序，可为 null/空）
   * @param roles 联系人在活动中的角色有序列表（可为 null；缺失位按空角色处理）
   * @return 联系人段文本；无可用联系人返回空串
   */
  static String buildContactSegment(List<String> names, List<String> roles) {
    StringBuilder sb = new StringBuilder();
    appendNamedSegment(sb, CONTACT_LABEL, names, roles);
    return sb.toString();
  }

  /** 单条展示项：{@code 值（角色）}；角色为空只输出值、值为空返回空串。 */
  static String namedEntry(Object value, String role) {
    String text = value == null ? "" : String.valueOf(value).trim();
    String result = text;
    if (!text.isEmpty() && role != null && !role.isBlank()) {
      result = text + "（" + role.trim() + "）";
    }
    return result;
  }

  /** 参与人/联系人共用段拼装：值非空项按原序取前 {@link #SEGMENT_MAX} 条，「；」连接。 */
  private static void appendNamedSegment(
      StringBuilder sb, String label, List<?> values, List<String> roles) {
    if (values != null && !values.isEmpty()) {
      StringBuilder segment = new StringBuilder();
      int index = 0;
      int appended = 0;
      while (index < values.size() && appended < SEGMENT_MAX) {
        String role = roles == null || index >= roles.size() ? null : roles.get(index);
        String entry = namedEntry(values.get(index), role);
        if (!entry.isEmpty()) {
          if (segment.length() > 0) {
            segment.append('；');
          }
          segment.append(entry);
          appended++;
        }
        index++;
      }
      if (segment.length() > 0) {
        sb.append(label).append(segment);
      }
    }
  }

  /** 非空白即追加「label: value」（分隔符「；」仅在本非首位时前置）。 */
  private static void appendField(StringBuilder sb, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("；");
    }
    sb.append(label).append(": ").append(value.trim());
  }
}
