package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 销售商机语义文本拼装测试（add-sales-opportunity-semantic-retrieval 任务 2.1）。
 *
 * <p>与 {@link SalesOpportunitySemanticTextSupport} 同包以覆盖 package-private 成员。
 */
@DisplayName("销售商机语义文本拼装")
class SalesOpportunitySemanticTextSupportTest {

  private SalesOpportunityEntity fullEntity() {
    SalesOpportunityEntity e = new SalesOpportunityEntity();
    e.setId(1001L);
    e.setOpportunityName("甲公司年度服务采购");
    e.setCompanyId(301L);
    e.setContactId(2001L);
    e.setStage(2);
    e.setAmount(new BigDecimal("800000.00"));
    e.setSource("老客户转介绍");
    e.setExpectedCloseDate(LocalDateTime.of(2026, 12, 31, 18, 0));
    e.setDescription("客户关注国产化替代，核心痛点是现有系统实施周期过长，要求提供 POC 时间表");
    e.setOwnerId(42L);
    return e;
  }

  @Test
  @DisplayName("全字段按固定顺序「字段名: 值」以「；」拼接")
  void buildJoinsNonEmptyFieldsInFixedOrder() {
    String text =
        SalesOpportunitySemanticTextSupport.buildSemanticText(fullEntity(), "甲公司", "张伟", "李强");

    assertEquals(
        "商机名称: 甲公司年度服务采购；关联客户: 甲公司；主要联系人: 张伟；商机阶段: 确认商机；"
            + "预估金额: 800000；机会来源: 老客户转介绍；预计结单日期: 2026-12-31；"
            + "需求描述: 客户关注国产化替代，核心痛点是现有系统实施周期过长，要求提供 POC 时间表；跟进负责人: 李强",
        text);
  }

  @Test
  @DisplayName("空白字段跳过且分隔符不错位；文本 trim；缺关联客户/联系人/负责人不占位")
  void buildSkipsBlankFields() {
    SalesOpportunityEntity e = new SalesOpportunityEntity();
    e.setId(1002L);
    e.setOpportunityName("  电话沟通记录  ");
    e.setStage(null);
    e.setAmount(null);
    e.setDescription("   ");

    String text = SalesOpportunitySemanticTextSupport.buildSemanticText(e, null, null, null);

    assertEquals("商机名称: 电话沟通记录", text);
  }

  @Test
  @DisplayName("全空字段返回空串（调用方跳过嵌入）")
  void buildReturnsBlankWhenAllFieldsEmpty() {
    assertEquals(
        "",
        SalesOpportunitySemanticTextSupport.buildSemanticText(
            new SalesOpportunityEntity(), null, null, null));
  }

  @Test
  @DisplayName("阶段码转文字描述：0~5 全映射，未知码值与 null 返回 null")
  void stageTextMapsAllCodes() {
    assertEquals("种子", SalesOpportunitySemanticTextSupport.stageText(0));
    assertEquals("潜在商机", SalesOpportunitySemanticTextSupport.stageText(1));
    assertEquals("确认商机", SalesOpportunitySemanticTextSupport.stageText(2));
    assertEquals("储备项目", SalesOpportunitySemanticTextSupport.stageText(3));
    assertEquals("立项签约", SalesOpportunitySemanticTextSupport.stageText(4));
    assertEquals("关闭", SalesOpportunitySemanticTextSupport.stageText(5));
    assertNull(SalesOpportunitySemanticTextSupport.stageText(9));
    assertNull(SalesOpportunitySemanticTextSupport.stageText(null));
  }

  @Test
  @DisplayName("金额展示：toPlainString 防科学计数法、stripTrailingZeros 去尾零、null 返回 null")
  void amountTextUsesPlainString() {
    assertEquals(
        "800000", SalesOpportunitySemanticTextSupport.amountText(new BigDecimal("800000.00")));
    assertEquals("1.5", SalesOpportunitySemanticTextSupport.amountText(new BigDecimal("1.500")));
    assertEquals("0", SalesOpportunitySemanticTextSupport.amountText(BigDecimal.ZERO));
    assertNull(SalesOpportunitySemanticTextSupport.amountText(null));
  }

  @Test
  @DisplayName("预计结单日期：yyyy-MM-dd 输出、null 返回 null")
  void dateTextFormatsDatePart() {
    assertEquals(
        "2026-12-31",
        SalesOpportunitySemanticTextSupport.dateText(LocalDateTime.of(2026, 12, 31, 18, 0)));
    assertNull(SalesOpportunitySemanticTextSupport.dateText(null));
  }

  @Test
  @DisplayName("documentId 口径固定为 SALES_OPPORTUNITY:{主键}；pointId 确定性派生且互不相同")
  void documentIdAndPointIdAreDeterministic() {
    assertEquals("SALES_OPPORTUNITY:1001", SalesOpportunitySemanticTextSupport.documentId(1001L));
    String expected =
        UUID.nameUUIDFromBytes("SALES_OPPORTUNITY:1001".getBytes(StandardCharsets.UTF_8))
            .toString();
    assertEquals(expected, SalesOpportunitySemanticTextSupport.pointId(1001L));
    assertEquals(
        SalesOpportunitySemanticTextSupport.pointId(1001L),
        SalesOpportunitySemanticTextSupport.pointId(1001L));
    assertNotEquals(
        SalesOpportunitySemanticTextSupport.pointId(1001L),
        SalesOpportunitySemanticTextSupport.pointId(1002L));
  }

  @Test
  @DisplayName("实体为 null 抛异常（与既有业务实体拼装口径一致）")
  void nullEntityThrows() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> SalesOpportunitySemanticTextSupport.buildSemanticText(null, null, null, null));
  }
}
