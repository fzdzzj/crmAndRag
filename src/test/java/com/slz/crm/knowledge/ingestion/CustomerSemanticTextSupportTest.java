package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 客户公司语义文本拼装测试（add-customer-semantic-retrieval 任务 2.1 红测先行）。
 *
 * <p>与 {@link CustomerSemanticTextSupport} 同包以覆盖 package-private 成员。
 */
@DisplayName("客户语义文本拼装")
class CustomerSemanticTextSupportTest {

  private CustomerCompanyEntity fullEntity() {
    CustomerCompanyEntity e = new CustomerCompanyEntity();
    e.setId(101L);
    e.setCompanyName("示例科技有限公司");
    e.setIndustry("软件和信息技术服务业");
    e.setCustomerType("直销");
    e.setBelongGroup("示例集团");
    e.setDept("华东事业部");
    e.setAddress("上海市浦东新区世纪大道 100 号");
    e.setPhone("13800138000");
    e.setWebsite("https://example.com");
    e.setDescription("主营 CRM 与 RAG 解决方案");
    e.setGrade(5);
    return e;
  }

  @Test
  @DisplayName("全字段按固定顺序「字段名: 值」以「；」拼接")
  void buildJoinsNonEmptyFieldsInFixedOrder() {
    String text = CustomerSemanticTextSupport.buildSemanticText(fullEntity());

    assertEquals(
        "公司名称: 示例科技有限公司；所属行业: 软件和信息技术服务业；客户属性: 直销；"
            + "归属集团: 示例集团；部门: 华东事业部；公司地址: 上海市浦东新区世纪大道 100 号；"
            + "联系电话: 13800138000；公司网站: https://example.com；"
            + "公司描述: 主营 CRM 与 RAG 解决方案；客户等级: 5",
        text);
  }

  @Test
  @DisplayName("空白字段跳过且分隔符不错位；文本 trim；grade=0 不入文本")
  void buildSkipsBlankFieldsAndZeroGrade() {
    CustomerCompanyEntity e = new CustomerCompanyEntity();
    e.setId(102L);
    e.setCompanyName("  空白字段测试公司  ");
    e.setIndustry("   ");
    e.setGrade(0);

    String text = CustomerSemanticTextSupport.buildSemanticText(e);

    assertEquals("公司名称: 空白字段测试公司", text);
  }

  @Test
  @DisplayName("全空字段返回空串（调用方跳过嵌入）")
  void buildReturnsEmptyStringWhenAllFieldsBlank() {
    CustomerCompanyEntity e = new CustomerCompanyEntity();
    e.setId(103L);

    assertEquals("", CustomerSemanticTextSupport.buildSemanticText(e));
  }

  @Test
  @DisplayName("pointId 为 UUID v3 确定性派生：同 id 幂等、异 id 不同、可被 UUID 解析")
  void pointIdIsDeterministicUuid() {
    String first = CustomerSemanticTextSupport.pointId(101L);
    String second = CustomerSemanticTextSupport.pointId(101L);
    String other = CustomerSemanticTextSupport.pointId(102L);

    assertEquals(first, second, "同 id 派生幂等（重摄取覆盖同 point）");
    assertNotEquals(first, other, "不同 id 派生不同 point");
    assertEquals(
        UUID.nameUUIDFromBytes(
                ("CUSTOMER_COMPANY:" + 101L).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString(),
        first,
        "派生算法 = nameUUIDFromBytes(ID_PREFIX + id)");
  }

  @Test
  @DisplayName("documentId 口径 = CUSTOMER_COMPANY:{主键}")
  void documentIdPrefixMatchesContract() {
    assertEquals("CUSTOMER_COMPANY:101", CustomerSemanticTextSupport.documentId(101L));
  }

  private CustomerContactEntity contact(
      long id, String name, String position, String mobile, String email, boolean deleted) {
    CustomerContactEntity c = new CustomerContactEntity();
    c.setId(id);
    c.setName(name);
    c.setPosition(position);
    c.setMobile(mobile);
    c.setEmail(email);
    c.setIsDeleted(deleted);
    return c;
  }

  @Test
  @DisplayName("联系人段：仅 is_deleted=0、按 id 升序、格式「姓名（职位，手机，email）」以「；」连接")
  void contactSegmentFiltersSortsAndFormats() {
    List<CustomerContactEntity> contacts =
        List.of(
            contact(3L, "李娜", "销售总监", "13900000333", "lina@slz.com", false),
            contact(1L, "张伟", "技术负责人", "13900000111", "zhangwei@slz.com", false),
            contact(2L, "王强", null, "13900000222", null, true)); // 软删应被过滤

    String segment = CustomerSemanticTextSupport.buildContactSegment(contacts);

    assertEquals(
        "联系人: 张伟（技术负责人，13900000111，zhangwei@slz.com）；李娜（销售总监，13900000333，lina@slz.com）",
        segment);
  }

  @Test
  @DisplayName("联系人段：职位/手机/email 为空只输出存在项（姓名必填）")
  void contactSegmentOmitsBlankInnerFields() {
    CustomerContactEntity c = contact(1L, "张伟", null, null, null, false);

    assertEquals("联系人: 张伟（）", CustomerSemanticTextSupport.buildContactSegment(List.of(c)));
  }

  @Test
  @DisplayName("联系人段：上限 5 人（防语义膨胀保险丝），超出截断且保序")
  void contactSegmentLimitsToFive() {
    List<CustomerContactEntity> contacts =
        java.util.stream.LongStream.rangeClosed(1, 8)
            .mapToObj(i -> contact(i, "联系人" + i, "职位" + i, "1390000" + i, "p" + i + "@slz.com", false))
            .toList();

    String segment = CustomerSemanticTextSupport.buildContactSegment(contacts);

    // 只含前 5 人（id 升序取前 5），且不含第 6 人
    assertEquals(
        "联系人: 联系人1（职位1，13900001，p1@slz.com）；联系人2（职位2，13900002，p2@slz.com）；"
            + "联系人3（职位3，13900003，p3@slz.com）；联系人4（职位4，13900004，p4@slz.com）；"
            + "联系人5（职位5，13900005，p5@slz.com）",
        segment);
    org.junit.jupiter.api.Assertions.assertFalse(segment.contains("联系人6"));
  }

  @Test
  @DisplayName("两参拼装：联系人段以「；」追加到公司文本之后；空联系人列表不追加原样返回")
  void buildSemanticTextAppendsContactSegmentAfterCompanyText() {
    CustomerCompanyEntity company = fullEntity();
    List<CustomerContactEntity> contacts =
        List.of(contact(1L, "张伟", "销售总监", "13800138001", "zhangwei@slz.com", false));

    String withContact = CustomerSemanticTextSupport.buildSemanticText(company, contacts);
    String base = CustomerSemanticTextSupport.buildSemanticText(company);

    assertEquals(
        base + "；联系人: 张伟（销售总监，13800138001，zhangwei@slz.com）", withContact);
    assertEquals(base, CustomerSemanticTextSupport.buildSemanticText(company, null));
    assertEquals(base, CustomerSemanticTextSupport.buildSemanticText(company, List.of()));
  }

  @Test
  @DisplayName("两参拼装：全部门联系人被软删或无联系人 → 联系人段为空、文本不变；null 联系人安全")
  void buildSemanticTextIgnoresAllDeletedContacts() {
    CustomerCompanyEntity company = fullEntity();
    CustomerContactEntity deleted = contact(1L, "张伟", "销售总监", "13800138001", null, true);

    assertEquals(
        CustomerSemanticTextSupport.buildSemanticText(company),
        CustomerSemanticTextSupport.buildSemanticText(company, List.of(deleted)));
  }
}
