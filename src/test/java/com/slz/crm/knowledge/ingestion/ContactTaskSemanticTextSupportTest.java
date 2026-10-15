package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.pojo.entity.ContactTaskEntity;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跟进任务语义文本拼装测试（add-unified-business-semantic-retrieval 任务 5.2 红测先行）。
 *
 * <p>与 {@link ContactTaskSemanticTextSupport} 同包以覆盖 package-private 成员。
 */
@DisplayName("跟进任务语义文本拼装")
class ContactTaskSemanticTextSupportTest {

  private ContactTaskEntity fullEntity() {
    ContactTaskEntity e = new ContactTaskEntity();
    e.setId(301L);
    e.setTaskTitle("跟进甲公司续约需求");
    e.setTaskType("电话");
    e.setPriority(6);
    e.setStatus(1);
    e.setStartTime(date(2026, 1, 1));
    e.setEndTime(date(2026, 1, 2));
    e.setTaskContent("客户反馈希望升级为年度订阅，并要求 2 月前提供商务方案");
    return e;
  }

  /** 按系统时区本地日期构造（dateText 以系统时区解析，保证断言与运行环境时区无关）。 */
  private Date date(int year, int month, int day) {
    return Date.from(
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  @Test
  @DisplayName("全字段按固定顺序「字段名: 值」以「；」拼接（含任务内容富文本）")
  void buildJoinsNonEmptyFieldsInFixedOrder() {
    String text =
        ContactTaskSemanticTextSupport.buildSemanticText(fullEntity(), "甲公司", "年度服务采购", "张伟", "李强");

    assertEquals(
        "任务主题: 跟进甲公司续约需求；任务类型: 电话；优先级: 6；任务状态: 进行中；"
            + "开始时间: 2026-01-01；结束时间: 2026-01-02；关联客户: 甲公司；关联商机: 年度服务采购；"
            + "关联联系人: 张伟；执行人: 李强；任务内容: 客户反馈希望升级为年度订阅，并要求 2 月前提供商务方案",
        text);
  }

  @Test
  @DisplayName("空白字段跳过且分隔符不错位；文本 trim；状态码未知跳过")
  void buildSkipsBlankFields() {
    ContactTaskEntity e = new ContactTaskEntity();
    e.setId(302L);
    e.setTaskTitle("  空白字段测试任务  ");
    e.setStatus(9); // 未知状态码 → 跳过
    e.setPriority(0);

    String text = ContactTaskSemanticTextSupport.buildSemanticText(e, null, null, null, null);

    assertEquals("任务主题: 空白字段测试任务；优先级: 0", text);
  }

  @Test
  @DisplayName("全空字段返回空串（调用方跳过嵌入）")
  void buildReturnsEmptyStringWhenAllFieldsBlank() {
    ContactTaskEntity e = new ContactTaskEntity();
    e.setId(303L);

    assertEquals("", ContactTaskSemanticTextSupport.buildSemanticText(e, null, null, null, null));
  }

  @Test
  @DisplayName("pointId 为 UUID v3 确定性派生：同 id 幂等、异 id 不同、可被 UUID 解析")
  void pointIdIsDeterministicUuid() {
    String first = ContactTaskSemanticTextSupport.pointId(301L);
    String second = ContactTaskSemanticTextSupport.pointId(301L);
    String other = ContactTaskSemanticTextSupport.pointId(302L);

    assertEquals(first, second, "同 id 派生幂等（重摄取覆盖同 point）");
    assertNotEquals(first, other, "不同 id 派生不同 point");
    assertEquals(
        UUID.nameUUIDFromBytes(("CONTACT_TASK:" + 301L).getBytes(StandardCharsets.UTF_8))
            .toString(),
        first,
        "派生算法 = nameUUIDFromBytes(ID_PREFIX + id)");
  }

  @Test
  @DisplayName("documentId 口径 = CONTACT_TASK:{主键}")
  void documentIdPrefixMatchesContract() {
    assertEquals("CONTACT_TASK:301", ContactTaskSemanticTextSupport.documentId(301L));
  }
}
