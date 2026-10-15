package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 销售合同语义文本拼装支持类（add-unified-business-semantic-retrieval 任务 3.1）。纯静态、无状态。
 *
 * <p>拼文本口径：非空字段按「合同编号、合同名称、合同总金额、合同状态、签约日期、生效日期、失效日期、关联客户、关联商机、负责人、订单明细、回款记录」 固定顺序输出「字段名:
 * 值」，以「；」连接；全空字段返回空串（调用方跳过嵌入）。文本值 trim 后写入。
 *
 * <p>订单明细附属段（优雅嵌入防 Qdrant 碎片化，支撑「按产品找合同」语义召回）：摄取服务按合同 id 加载 contract_order_item（id 升序、上限 {@link
 * #SEGMENT_MAX} 条），段内容形如 {@code 数据库服务（规格: 标准版，单价: 10000，数量: 2）}；回款履约附属段按 payment_record （id 升序、上限
 * {@link #SEGMENT_MAX} 条），段内容形如 {@code 2026-01-15 银行转账 50000（单号 RK-001）}；两段共用「（…）」细节包裹 +
 * 「；」条目分隔口径，标签「订单明细: 」「回款记录: 」由 {@link #buildSemanticText} 字段层统一加。
 *
 * <p>状态码转文字描述（对齐 {@code contract.contract_status} 注释口径：0预签约/1已生效/2已终止/3已完成/4已弃用）；金额以 {@link
 * BigDecimal#toPlainString} 输出（避免科学计数法）；日期以 {@code yyyy-MM-dd} 输出。
 *
 * <p>文档/切片标识口径：documentId = chunkId = {@code CONTRACT:{主键}}；Qdrant point id 由该口径经 UUID v3
 * 确定性派生（nameUUIDFromBytes），重摄取幂等。
 */
final class ContractSemanticTextSupport {

  private ContractSemanticTextSupport() {}

  /** 合同向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "CONTRACT:";

  /** 附属段（订单明细/回款记录）条目上限（防语义膨胀保险丝）。 */
  static final int SEGMENT_MAX = 5;

  /** 日期展示口径（对齐合同列表页日期展示）。 */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** 合同状态码→文字描述（contract.contract_status 注释口径）。 */
  private static final String[] STATUS_TEXT = {"预签约", "已生效", "已终止", "已完成", "已弃用"};

  /**
   * 向量 documentId 口径：CONTRACT:{主键}（SourceReference.documentId 同口径，按文档删除依赖）。
   *
   * @param id 合同主键
   * @return documentId
   */
  static String documentId(Long id) {
    return ID_PREFIX + id;
  }

  /**
   * Qdrant point id：业务主键经 UUID v3 确定性派生（nameUUIDFromBytes），重摄取幂等。
   *
   * @param id 合同主键
   * @return UUID 字符串
   */
  static String pointId(Long id) {
    return UUID.nameUUIDFromBytes((ID_PREFIX + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * 拼装合同语义文本（基础信息 + 附属段，非空字段「字段名: 值」标签化，「；」连接）。
   *
   * @param entity 合同实体
   * @param companyName 关联客户公司名称（可空；摄取服务解析）
   * @param opportunityName 关联商机名称（可空；摄取服务解析）
   * @param ownerName 负责人姓名（可空；摄取服务解析）
   * @param orderSegment 订单产品明细附属段（可空；由 {@link #buildOrderItemSegment} 产出）
   * @param paymentSegment 回款履约附属段（可空；由 {@link #buildPaymentSegment} 产出）
   * @return 语义文本；全空字段返回空串
   */
  static String buildSemanticText(
      ContractEntity entity,
      String companyName,
      String opportunityName,
      String ownerName,
      String orderSegment,
      String paymentSegment) {
    if (entity == null) {
      throw new IllegalArgumentException("合同实体不能为空");
    }
    StringBuilder sb = new StringBuilder();
    appendField(sb, "合同编号", entity.getContractNo());
    appendField(sb, "合同名称", entity.getContractName());
    appendField(sb, "合同总金额", amountText(entity.getTotalAmount()));
    appendField(sb, "合同状态", statusText(entity.getContractStatus()));
    appendField(sb, "签约日期", dateText(entity.getSignDate()));
    appendField(sb, "生效日期", dateText(entity.getStartDate()));
    appendField(sb, "失效日期", dateText(entity.getEndDate()));
    appendField(sb, "关联客户", companyName);
    appendField(sb, "关联商机", opportunityName);
    appendField(sb, "负责人", ownerName);
    appendField(sb, "订单明细", orderSegment);
    appendField(sb, "回款记录", paymentSegment);
    return sb.toString();
  }

  /**
   * 订单产品明细附属段拼装：保留 productName 非空项、按原序（摄取服务按 id 升序加载）取前 {@link #SEGMENT_MAX} 条； 返回**段内容**（不含 「订单明细:
   * 」标签），形如 {@code 数据库服务（规格: 标准版，单价: 10000，数量: 2）}；明细内字段以「，」连接、条目间以「；」连接； 明细字段全空时仅输出产品名称。
   *
   * @param items 订单明细列表（可为 null/空）
   * @return 明细段内容；无可用明细返回空串
   */
  static String buildOrderItemSegment(List<ContractOrderItemEntity> items) {
    StringBuilder sb = new StringBuilder();
    int appended = 0;
    if (items != null) {
      for (ContractOrderItemEntity item : items) {
        if (appended >= SEGMENT_MAX) {
          break;
        }
        if (item == null || item.getProductName() == null || item.getProductName().isBlank()) {
          continue;
        }
        if (sb.length() > 0) {
          sb.append('；');
        }
        sb.append(item.getProductName().trim());
        StringBuilder detail = new StringBuilder();
        appendKeyValue(detail, "规格", item.getRemark());
        appendKeyValue(detail, "单价", amountText(item.getUnitPrice()));
        appendKeyValue(detail, "数量", amountText(item.getQuantity()));
        if (detail.length() > 0) {
          sb.append('（').append(detail).append('）');
        }
        appended++;
      }
    }
    return sb.toString();
  }

  /**
   * 回款履约附属段拼装：保留 paymentNo 非空项、按原序（摄取服务按 id 升序加载）取前 {@link #SEGMENT_MAX} 条； 返回**段内容**（不含 「回款记录:
   * 」标签），形如 {@code 2026-01-15 银行转账 50000（单号 RK-001）}；条目间以「；」连接。
   *
   * @param payments 回款记录列表（可为 null/空）
   * @return 回款段内容；无可用回款返回空串
   */
  static String buildPaymentSegment(List<PaymentRecordEntity> payments) {
    StringBuilder sb = new StringBuilder();
    int appended = 0;
    if (payments != null) {
      for (PaymentRecordEntity payment : payments) {
        if (appended >= SEGMENT_MAX) {
          break;
        }
        if (payment == null || payment.getPaymentNo() == null || payment.getPaymentNo().isBlank()) {
          continue;
        }
        if (sb.length() > 0) {
          sb.append('；');
        }
        appendValue(sb, dateText(payment.getPaymentDate()));
        appendValue(sb, payment.getPaymentMethod());
        appendValue(sb, amountText(payment.getPaymentAmount()));
        sb.append("（单号 ").append(payment.getPaymentNo().trim()).append('）');
        appended++;
      }
    }
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

  /** 金额转展示文本（{@link BigDecimal#toPlainString} 防科学计数法）；null 返回 null。 */
  static String amountText(BigDecimal amount) {
    return amount == null ? null : amount.stripTrailingZeros().toPlainString();
  }

  /** 日期转 {@code yyyy-MM-dd}；null 返回 null。 */
  static String dateText(LocalDateTime date) {
    return date == null ? null : date.format(DATE_FORMAT);
  }

  /** 非空白即追加「label: value」（明细内字段以「，」连接）。 */
  private static void appendKeyValue(StringBuilder sb, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append('，');
    }
    sb.append(label).append(": ").append(value.trim());
  }

  /** 非空白值追加到段内（回款「日期 方式 金额」以空格连接的展示口径；分隔符/括号后不重复加空格）。 */
  private static void appendValue(StringBuilder sb, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    int len = sb.length();
    if (len > 0) {
      char last = sb.charAt(len - 1);
      if (last != '；' && last != '（' && last != ' ') {
        sb.append(' ');
      }
    }
    sb.append(value.trim());
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
