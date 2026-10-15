package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.ContactTaskEntity;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

/**
 * 跟进待办任务语义文本拼装支持类（add-unified-business-semantic-retrieval 任务 3.2）。纯静态、无状态。
 *
 * <p>拼文本口径：非空字段按「任务主题、任务类型、优先级、任务状态、开始时间、结束时间、关联客户、关联商机、关联联系人、执行人、任务内容」固定顺序 输出「字段名:
 * 值」，以「；」连接；全空字段返回空串（调用方跳过嵌入）。文本值 trim 后写入。
 *
 * <p>优先级为 0-9（低到高）数值码，直接输出数字；状态码转文字描述（对齐 {@code contact_task.status} 注释口径：
 * 0未开始/1进行中/2已完成/3已取消）；起止时间为 java.util.Date，以 {@code yyyy-MM-dd} 输出。
 *
 * <p>任务内容（task_content）为富文本详细描述，直接以纯文本值 trim 后入段（支撑「按任务内容找任务」的语义召回）。
 *
 * <p>文档/切片标识口径：documentId = chunkId = {@code CONTACT_TASK:{主键}}；Qdrant point id 由该口径经 UUID v3
 * 确定性派生（nameUUIDFromBytes），重摄取幂等。
 */
final class ContactTaskSemanticTextSupport {

  private ContactTaskSemanticTextSupport() {}

  /** 跟进任务向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "CONTACT_TASK:";

  /** 日期展示口径（对齐任务列表页日期展示）。 */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** 任务状态码→文字描述（contact_task.status 注释口径）。 */
  private static final String[] STATUS_TEXT = {"未开始", "进行中", "已完成", "已取消"};

  /**
   * 向量 documentId 口径：CONTACT_TASK:{主键}（SourceReference.documentId 同口径，按文档删除依赖）。
   *
   * @param id 任务主键
   * @return documentId
   */
  static String documentId(Long id) {
    return ID_PREFIX + id;
  }

  /**
   * Qdrant point id：业务主键经 UUID v3 确定性派生（nameUUIDFromBytes），重摄取幂等。
   *
   * @param id 任务主键
   * @return UUID 字符串
   */
  static String pointId(Long id) {
    return UUID.nameUUIDFromBytes((ID_PREFIX + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * 拼装跟进任务语义文本（非空字段「字段名: 值」标签化，「；」连接）。
   *
   * @param entity 跟进任务实体
   * @param companyName 关联客户公司名称（可空；摄取服务解析）
   * @param opportunityName 关联商机名称（可空；摄取服务解析）
   * @param contactName 关联联系人姓名（可空；摄取服务解析）
   * @param assigneeName 执行人姓名（可空；摄取服务解析）
   * @return 语义文本；全空字段返回空串
   */
  static String buildSemanticText(
      ContactTaskEntity entity,
      String companyName,
      String opportunityName,
      String contactName,
      String assigneeName) {
    if (entity == null) {
      throw new IllegalArgumentException("跟进任务实体不能为空");
    }
    StringBuilder sb = new StringBuilder();
    appendField(sb, "任务主题", entity.getTaskTitle());
    appendField(sb, "任务类型", entity.getTaskType());
    appendField(
        sb, "优先级", entity.getPriority() == null ? null : String.valueOf(entity.getPriority()));
    appendField(sb, "任务状态", statusText(entity.getStatus()));
    appendField(sb, "开始时间", dateText(entity.getStartTime()));
    appendField(sb, "结束时间", dateText(entity.getEndTime()));
    appendField(sb, "关联客户", companyName);
    appendField(sb, "关联商机", opportunityName);
    appendField(sb, "关联联系人", contactName);
    appendField(sb, "执行人", assigneeName);
    appendField(sb, "任务内容", entity.getTaskContent());
    return sb.toString();
  }

  /** 状态码转文字描述；码值未知（含 null）返回 null（字段层跳过）。 */
  static String statusText(Integer status) {
    String result = null;
    if (status != null && status >= 0 && status < STATUS_TEXT.length) {
      result = STATUS_TEXT[status];
    }
    return result;
  }

  /** 起止时间转 {@code yyyy-MM-dd}（java.util.Date 转系统时区）；null 返回 null。 */
  static String dateText(Date date) {
    return date == null
        ? null
        : DATE_FORMAT.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
  }

  /** 非空白即追加「label: value」（分隔符「；」仅在本非首位时前置）。 */
  private static void appendField(StringBuilder sb, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append('；');
    }
    sb.append(label).append(": ").append(value.trim());
  }
}
