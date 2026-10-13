package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
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
}
