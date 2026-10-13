package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 客户公司语义文本拼装支持类（add-customer-semantic-retrieval 任务 2.1）。纯静态、无状态。
 *
 * <p>拼文本口径（§0 登记）：非空字段按「公司名称、所属行业、客户属性、归属集团、部门、公司地址、 联系电话、公司网站、公司描述、客户等级」固定顺序输出「字段名:
 * 值」，以「；」连接；grade 仅在 &gt;0 时输出； 全空字段返回空串（调用方跳过嵌入）。文本值 trim 后写入。
 */
final class CustomerSemanticTextSupport {

  private CustomerSemanticTextSupport() {}

  /** 客户公司向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "CUSTOMER_COMPANY:";

  /**
   * 向量 documentId 口径：CUSTOMER_COMPANY:{主键}（SourceReference.documentId 同口径，按文档删除依赖）。
   *
   * @param id 客户公司主键
   * @return documentId
   */
  static String documentId(Long id) {
    return ID_PREFIX + id;
  }

  /**
   * Qdrant point id：业务主键经 UUID v3 确定性派生（nameUUIDFromBytes），重摄取幂等。
   *
   * @param id 客户公司主键
   * @return UUID 字符串
   */
  static String pointId(Long id) {
    return UUID.nameUUIDFromBytes((ID_PREFIX + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * 拼装客户公司语义文本（任务 2.1：非空字段「字段名: 值」标签化，「；」连接）。
   *
   * @param entity 客户公司实体
   * @return 语义文本；全空字段返回空串
   */
  static String buildSemanticText(CustomerCompanyEntity entity) {
    if (entity == null) {
      throw new IllegalArgumentException("客户公司实体不能为空");
    }
    StringBuilder sb = new StringBuilder();
    appendField(sb, "公司名称", entity.getCompanyName());
    appendField(sb, "所属行业", entity.getIndustry());
    appendField(sb, "客户属性", entity.getCustomerType());
    appendField(sb, "归属集团", entity.getBelongGroup());
    appendField(sb, "部门", entity.getDept());
    appendField(sb, "公司地址", entity.getAddress());
    appendField(sb, "联系电话", entity.getPhone());
    appendField(sb, "公司网站", entity.getWebsite());
    appendField(sb, "公司描述", entity.getDescription());
    if (entity.getGrade() != null && entity.getGrade() > 0) {
      appendField(sb, "客户等级", String.valueOf(entity.getGrade()));
    }
    return sb.toString();
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
