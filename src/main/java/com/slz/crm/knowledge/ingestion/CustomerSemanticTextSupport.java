package com.slz.crm.knowledge.ingestion;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 客户公司语义文本拼装支持类（add-customer-semantic-retrieval 任务 2.1 / add-customer-contact-retrieval 任务
 * 1.1）。纯静态、无状态。
 *
 * <p>拼文本口径（§0 登记）：非空字段按「公司名称、所属行业、客户属性、归属集团、部门、公司地址、 联系电话、公司网站、公司描述、客户等级」固定顺序输出「字段名:
 * 值」，以「；」连接；grade 仅在 &gt;0 时输出； 全空字段返回空串（调用方跳过嵌入）。文本值 trim 后写入。
 *
 * <p>联系人附属语义段（add-customer-contact-retrieval 任务 1.1）：仅 is_deleted=0、按 id 升序、上限 5 人，段格式「联系人:
 * 姓名（职位，手机，email）」，多人以「；」连接，与公司面字段分隔符口径一致；职位/手机/email 为空的情况只输出存在项 （姓名必填）。联系人段通过 {@link
 * #buildSemanticText(CustomerCompanyEntity, List)} 追加到公司文本之后。
 */
final class CustomerSemanticTextSupport {

  private CustomerSemanticTextSupport() {}

  /** 客户公司向量 documentId 前缀（同时是 chunkId 口径）。 */
  private static final String ID_PREFIX = "CUSTOMER_COMPANY:";

  /** 联系人附段上限（防未来语义膨胀保险丝；V39/V40 每家 2 人实际不触上限）。 */
  static final int CONTACT_SEGMENT_MAX = 5;

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

  /**
   * 拼装客户公司语义文本并追加联系人附属语义段（add-customer-contact-retrieval 任务 1.1）。公司面口径不 变（委托单参重载），随后在同一
   * StringBuilder 上以「；」追加联系人段；空联系人列表不追加、返回原公司文本。
   *
   * @param entity 客户公司实体
   * @param contacts 该公司的联系人列表（可为 null/空）；拼接前按 is_deleted=0、id 升序、上限 5 人规约重排
   * @return 语义文本；公司字段全空且无联系人时返回空串
   */
  static String buildSemanticText(
      CustomerCompanyEntity entity, List<CustomerContactEntity> contacts) {
    String base = buildSemanticText(entity);
    String contactSegment =
        (contacts == null || contacts.isEmpty()) ? "" : buildContactSegment(contacts);
    String result = base;
    if (!contactSegment.isEmpty()) {
      StringBuilder sb = new StringBuilder(base);
      if (sb.length() > 0) {
        sb.append("；");
      }
      sb.append(contactSegment);
      result = sb.toString();
    }
    return result;
  }

  /**
   * 联系人段拼接：仅保留 is_deleted=0、按 id 升序、上限 5 人；段形如 {@code 联系人: 姓名（职位，手机，email）}，多人以「；」连接；职位/手机/email
   * 为空只输出存在项（姓名必填）。
   *
   * @param contacts 源联系人列表（不做原地修改，内部过滤/排序重排为确定性顺序）
   * @return 联系人段文本；无可展示联系人返回空串
   */
  static String buildContactSegment(List<CustomerContactEntity> contacts) {
    List<CustomerContactEntity> filtered =
        contacts == null
            ? List.of()
            : contacts.stream()
                .filter(Objects::nonNull)
                .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
                .sorted(Comparator.comparing(CustomerContactEntity::getId))
                .limit(CONTACT_SEGMENT_MAX)
                .toList();
    StringBuilder sb = new StringBuilder();
    if (!filtered.isEmpty()) {
      sb.append("联系人: ");
      boolean first = true;
      for (CustomerContactEntity c : filtered) {
        if (!first) {
          sb.append("；");
        }
        first = false;
        sb.append(c.getName());
        sb.append("（");
        appendSegmentParts(sb, c);
        sb.append('）');
      }
    }
    return sb.toString();
  }

  /** 联系人段内非空字段以「，」连接并追加（职位/手机/email 三缺一或齐）。 */
  private static void appendSegmentParts(StringBuilder sb, CustomerContactEntity c) {
    boolean hasInner = false;
    hasInner = appendSegmentPart(sb, hasInner, c.getPosition());
    hasInner = appendSegmentPart(sb, hasInner, c.getMobile());
    appendSegmentPart(sb, hasInner, c.getEmail());
  }

  /** 追加单个非空字段（首个不前置「，」），返回当前是否已有内容。 */
  private static boolean appendSegmentPart(StringBuilder sb, boolean hasInner, String value) {
    boolean result = hasInner;
    if (value != null && !value.isBlank()) {
      if (hasInner) {
        sb.append('，');
      }
      sb.append(value.trim());
      result = true;
    }
    return result;
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
