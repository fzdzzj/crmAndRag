package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 合同语义文本拼装测试（add-unified-business-semantic-retrieval 任务 5.2 红测先行）。
 *
 * <p>与 {@link ContractSemanticTextSupport} 同包以覆盖 package-private 成员。
 */
@DisplayName("合同语义文本拼装")
class ContractSemanticTextSupportTest {

  private ContractEntity fullEntity() {
    ContractEntity e = new ContractEntity();
    e.setId(201L);
    e.setContractNo("HT-2026-0001");
    e.setContractName("甲公司年度软件服务合同");
    e.setTotalAmount(new BigDecimal("150000.00"));
    e.setContractStatus(1);
    e.setSignDate(LocalDateTime.of(2026, 1, 15, 10, 0));
    e.setStartDate(LocalDateTime.of(2026, 1, 15, 0, 0));
    e.setEndDate(LocalDateTime.of(2026, 12, 31, 0, 0));
    return e;
  }

  @Test
  @DisplayName("全字段按固定顺序「字段名: 值」以「；」拼接（基础信息 + 关联 + 附属段）")
  void buildJoinsNonEmptyFieldsInFixedOrder() {
    String text =
        ContractSemanticTextSupport.buildSemanticText(
            fullEntity(),
            "甲公司",
            "年度服务采购",
            "李强",
            "数据库服务（规格: 标准版，单价: 10000，数量: 2）",
            "2026-01-15 银行转账 50000（单号 RK-001）");

    assertEquals(
        "合同编号: HT-2026-0001；合同名称: 甲公司年度软件服务合同；合同总金额: 150000；"
            + "合同状态: 已生效；签约日期: 2026-01-15；生效日期: 2026-01-15；失效日期: 2026-12-31；"
            + "关联客户: 甲公司；关联商机: 年度服务采购；负责人: 李强；"
            + "订单明细: 数据库服务（规格: 标准版，单价: 10000，数量: 2）；"
            + "回款记录: 2026-01-15 银行转账 50000（单号 RK-001）",
        text);
  }

  @Test
  @DisplayName("空白字段跳过且分隔符不错位；文本 trim")
  void buildSkipsBlankFields() {
    ContractEntity e = new ContractEntity();
    e.setId(202L);
    e.setContractName("  空白字段测试合同  ");
    e.setContractStatus(null);
    e.setTotalAmount(null);

    String text =
        ContractSemanticTextSupport.buildSemanticText(
            e,
            null,
            null,
            null,
            ContractSemanticTextSupport.buildOrderItemSegment(null),
            ContractSemanticTextSupport.buildPaymentSegment(null));

    assertEquals("合同名称: 空白字段测试合同", text);
  }

  @Test
  @DisplayName("全空字段返回空串（调用方跳过嵌入）")
  void buildReturnsEmptyStringWhenAllFieldsBlank() {
    ContractEntity e = new ContractEntity();
    e.setId(203L);

    assertEquals(
        "", ContractSemanticTextSupport.buildSemanticText(e, null, null, null, null, null));
  }

  @Test
  @DisplayName("订单明细段：仅保留产品名非空项、明细字段以「，」连接、条目以「；」连接、上限 5 条")
  void orderItemSegmentFormatsAndCaps() {
    ContractOrderItemEntity a = orderItem(1L, "数据库服务", "标准版", "10000", "2");
    ContractOrderItemEntity b = orderItem(2L, "实施服务", "现场交付", "50000", "1");
    ContractOrderItemEntity c = new ContractOrderItemEntity(); // 产品名空 → 跳过
    ContractOrderItemEntity d = orderItem(4L, "培训服务", null, null, null); // 明细字段全空 → 仅产品名

    String segment = ContractSemanticTextSupport.buildOrderItemSegment(List.of(a, b, c, d));

    assertEquals("数据库服务（规格: 标准版，单价: 10000，数量: 2）；实施服务（规格: 现场交付，单价: 50000，数量: 1）；培训服务", segment);
  }

  @Test
  @DisplayName("回款段：按原序条目「日期 方式 金额（单号 xxx）」以「；」连接，字段空时跳过")
  void paymentSegmentFormats() {
    PaymentRecordEntity a = payment(1L, "RK-001", "2026-01-15", "银行转账", "50000");
    PaymentRecordEntity b = payment(2L, "RK-002", "2026-02-20", null, null); // 方式/金额空
    PaymentRecordEntity c = new PaymentRecordEntity(); // 单号空 → 跳过

    String segment = ContractSemanticTextSupport.buildPaymentSegment(List.of(a, b, c));

    assertEquals("2026-01-15 银行转账 50000（单号 RK-001）；2026-02-20（单号 RK-002）", segment);
  }

  @Test
  @DisplayName("pointId 为 UUID v3 确定性派生：同 id 幂等、异 id 不同、可被 UUID 解析")
  void pointIdIsDeterministicUuid() {
    String first = ContractSemanticTextSupport.pointId(201L);
    String second = ContractSemanticTextSupport.pointId(201L);
    String other = ContractSemanticTextSupport.pointId(202L);

    assertEquals(first, second, "同 id 派生幂等（重摄取覆盖同 point）");
    assertNotEquals(first, other, "不同 id 派生不同 point");
    assertEquals(
        UUID.nameUUIDFromBytes(("CONTRACT:" + 201L).getBytes(StandardCharsets.UTF_8)).toString(),
        first,
        "派生算法 = nameUUIDFromBytes(ID_PREFIX + id)");
  }

  @Test
  @DisplayName("documentId 口径 = CONTRACT:{主键}")
  void documentIdPrefixMatchesContract() {
    assertEquals("CONTRACT:201", ContractSemanticTextSupport.documentId(201L));
  }

  private ContractOrderItemEntity orderItem(
      long id, String productName, String remark, String unitPrice, String quantity) {
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(id);
    item.setContractId(201L);
    item.setProductName(productName);
    item.setRemark(remark);
    item.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
    item.setQuantity(quantity == null ? null : new BigDecimal(quantity));
    return item;
  }

  private PaymentRecordEntity payment(
      long id, String paymentNo, String date, String method, String amount) {
    PaymentRecordEntity p = new PaymentRecordEntity();
    p.setId(id);
    p.setContractId(201L);
    p.setPaymentNo(paymentNo);
    p.setPaymentDate(date == null ? null : LocalDateTime.parse(date + "T00:00:00"));
    p.setPaymentMethod(method);
    p.setPaymentAmount(amount == null ? null : new BigDecimal(amount));
    return p;
  }
}
