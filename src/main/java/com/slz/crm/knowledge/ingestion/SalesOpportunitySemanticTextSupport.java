package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 销售商机语义文本拼装支持类（add-sales-opportunity-semantic-retrieval 任务 2.1）。纯静态、无状态。
 *
 * <p>拼文本口径：非空字段按「商机名称、关联客户、主要联系人、商机阶段、预估金额、机会来源、预计结单日期、需求描述、跟进负责人」固定顺序输出 「字段名:
 * 值」，以「；」连接；全空字段返回空串（调用方跳过嵌入）。文本值 trim 后写入。
 *
 * <p>阶段码转文字描述（对齐 {@code sales_opportunity.stage} 注释口径：0种子/1潜在商机/2确认商机/3储备项目/4立项签约/5关闭）；金额以 {@link
 * BigDecimal#toPlainString} 输出（避免科学计数法）；日期以 {@code yyyy-MM-dd} 输出（对齐商机列表页展示口径）。
 *
 * <p>文档/切片标识口径：documentId = chunkId = {@code SALES_OPPORTUNITY:{主键}}；Qdrant point id 由该口径经 UUID v3
 * 确定性派生（nameUUIDFromBytes），重摄取幂等。
 */
final class SalesOpportunitySemanticTextSupport {

  private SalesOpportunitySemanticTextSupport() {}

  /** 销售商机向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "SALES_OPPORTUNITY:";

  /** 预计结单日期展示口径（对齐商机列表页日期展示）。 */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** 商机阶段码→文字描述（sales_opportunity.stage 注释口径）。 */
  private static final Map<Integer, String> STAGE_TEXT =
      Map.of(
          0, "种子",
          1, "潜在商机",
          2, "确认商机",
          3, "储备项目",
          4, "立项签约",
          5, "关闭");

  /**
   * 向量 documentId 口径：SALES_OPPORTUNITY:{主键}（SourceReference.documentId 同口径，按文档删除依赖）。
   *
   * @param id 商机主键
   * @return documentId
   */
  static String documentId(Long id) {
    return ID_PREFIX + id;
  }

  /**
   * Qdrant point id：业务主键经 UUID v3 确定性派生（nameUUIDFromBytes），重摄取幂等。
   *
   * @param id 商机主键
   * @return UUID 字符串
   */
  static String pointId(Long id) {
    return UUID.nameUUIDFromBytes((ID_PREFIX + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * 拼装销售商机语义文本（非空字段「字段名: 值」标签化，「；」连接）。
   *
   * @param entity 销售商机实体
   * @param companyName 关联客户公司名称（可空；摄取服务解析）
   * @param contactName 主要联系人姓名（可空；摄取服务解析）
   * @param ownerName 跟进负责人姓名（可空；摄取服务解析）
   * @return 语义文本；全空字段返回空串
   */
  static String buildSemanticText(
      SalesOpportunityEntity entity, String companyName, String contactName, String ownerName) {
    if (entity == null) {
      throw new IllegalArgumentException("销售商机实体不能为空");
    }
    StringBuilder sb = new StringBuilder();
    appendField(sb, "商机名称", entity.getOpportunityName());
    appendField(sb, "关联客户", companyName);
    appendField(sb, "主要联系人", contactName);
    appendField(sb, "商机阶段", stageText(entity.getStage()));
    appendField(sb, "预估金额", amountText(entity.getAmount()));
    appendField(sb, "机会来源", entity.getSource());
    appendField(sb, "预计结单日期", dateText(entity.getExpectedCloseDate()));
    appendField(sb, "需求描述", entity.getDescription());
    appendField(sb, "跟进负责人", ownerName);
    return sb.toString();
  }

  /** 阶段码转文字描述；码值未知（含 null）返回 null（字段层跳过）。 */
  static String stageText(Integer stage) {
    return stage == null ? null : STAGE_TEXT.get(stage);
  }

  /** 金额转展示文本（{@link BigDecimal#toPlainString} 防科学计数法）；null 返回 null。 */
  static String amountText(BigDecimal amount) {
    return amount == null ? null : amount.stripTrailingZeros().toPlainString();
  }

  /** 预计结单日期转 {@code yyyy-MM-dd}；null 返回 null。 */
  static String dateText(LocalDateTime date) {
    return date == null ? null : date.format(DATE_FORMAT);
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
