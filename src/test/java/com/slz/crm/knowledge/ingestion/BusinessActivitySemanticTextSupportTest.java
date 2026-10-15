package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 商业活动语义文本拼装测试（add-business-activity-semantic-retrieval 任务 2.1）。
 *
 * <p>与 {@link BusinessActivitySemanticTextSupport} 同包以覆盖 package-private 成员。
 */
@DisplayName("商业活动语义文本拼装")
class BusinessActivitySemanticTextSupportTest {

  private BusinessActivityEntity fullEntity() {
    BusinessActivityEntity e = new BusinessActivityEntity();
    e.setId(901L);
    e.setActivityTitle("甲公司产品演示会");
    e.setActivityType("线下拜访");
    e.setActivityContent("客户关注国产化替代与实施周期，要求提供 POC 时间表");
    e.setRemark("两周内回访，附方案报价");
    e.setCompanyId(301L);
    e.setOpportunityId(401L);
    e.setCreatorId(42L);
    return e;
  }

  private BusinessActivityUserEntity participant(long id, long userId, String role) {
    BusinessActivityUserEntity p = new BusinessActivityUserEntity();
    p.setId(id);
    p.setActivityId(901L);
    p.setUserId(userId);
    p.setUserRole(role);
    return p;
  }

  @Test
  @DisplayName("全字段按固定顺序「字段名: 值」以「；」拼接（含参与人与联系人段）")
  void buildJoinsNonEmptyFieldsInFixedOrder() {
    String text =
        BusinessActivitySemanticTextSupport.buildSemanticText(
            fullEntity(),
            "甲公司",
            "甲公司 CRM 采购",
            BusinessActivitySemanticTextSupport.buildParticipantSegment(
                List.of(participant(1L, 42L, "主讲人"))),
            BusinessActivitySemanticTextSupport.buildContactSegment(
                List.of("张伟", "李娜"), List.of("决策者", "技术对接人")));

    assertEquals(
        "活动标题: 甲公司产品演示会；活动类型: 线下拜访；关联客户: 甲公司；关联商机: 甲公司 CRM 采购；"
            + "沟通内容: 客户关注国产化替代与实施周期，要求提供 POC 时间表；备注: 两周内回访，附方案报价；"
            + "参与人: 42（主讲人）；联系人: 张伟（决策者）；李娜（技术对接人）",
        text);
  }

  @Test
  @DisplayName("空白字段跳过且分隔符不错位；文本 trim；缺关联客户/商机不占位")
  void buildSkipsBlankFields() {
    BusinessActivityEntity e = new BusinessActivityEntity();
    e.setId(902L);
    e.setActivityTitle("  电话沟通记录  ");
    e.setActivityType("   ");
    e.setActivityContent(null);

    String text = BusinessActivitySemanticTextSupport.buildSemanticText(e, null, null, "", "");

    assertEquals("活动标题: 电话沟通记录", text);
  }

  @Test
  @DisplayName("全空字段返回空串（调用方跳过嵌入）")
  void buildReturnsBlankWhenAllFieldsEmpty() {
    assertEquals(
        "",
        BusinessActivitySemanticTextSupport.buildSemanticText(
            new BusinessActivityEntity(), null, null, null, null));
  }

  @Test
  @DisplayName("参与人段：按关联 id 升序、上限 5 条、userId 为空项跳过、角色为空只输出 ID")
  void participantSegmentSortedLimitedAndFiltered() {
    BusinessActivityUserEntity noUser = participant(9L, 0L, "记录人");
    noUser.setUserId(null);
    List<BusinessActivityUserEntity> participants =
        List.of(
            participant(6L, 46L, null),
            participant(2L, 42L, "主讲人"),
            noUser,
            participant(3L, 43L, "陪同人"),
            participant(4L, 44L, "记录人"),
            participant(5L, 45L, "主持人"),
            participant(7L, 47L, "观察员"));

    String segment = BusinessActivitySemanticTextSupport.buildParticipantSegment(participants);

    assertEquals("42（主讲人）；43（陪同人）；44（记录人）；45（主持人）；46", segment);
  }

  @Test
  @DisplayName("联系人段：空白姓名串跳过、上限 5 条、「；」连接")
  void contactSegmentFiltersBlankAndLimits() {
    String segment =
        BusinessActivitySemanticTextSupport.buildContactSegment(
            java.util.Arrays.asList("张伟", "  ", "李娜", "王强", "赵敏", "钱进", "孙悦"),
            java.util.Arrays.asList("决策者", null, "技术对接人", "参会人", "参会人", "参会人", "参会人"));

    assertEquals("张伟（决策者）；李娜（技术对接人）；王强（参会人）；赵敏（参会人）；钱进（参会人）", segment);
  }

  @Test
  @DisplayName("空参与人/联系人列表返回空串（语义文本不留空段）")
  void emptySegmentsReturnBlank() {
    assertEquals("", BusinessActivitySemanticTextSupport.buildParticipantSegment(null));
    assertEquals("", BusinessActivitySemanticTextSupport.buildParticipantSegment(List.of()));
    assertEquals("", BusinessActivitySemanticTextSupport.buildContactSegment(null, null));
    assertEquals(
        "", BusinessActivitySemanticTextSupport.buildContactSegment(List.of(" "), List.of("角色")));
  }

  @Test
  @DisplayName("单条展示项：值+角色、仅值、空值三态")
  void namedEntryFormatsValueAndRole() {
    assertEquals("42（主讲人）", BusinessActivitySemanticTextSupport.namedEntry(42L, "主讲人"));
    assertEquals("42", BusinessActivitySemanticTextSupport.namedEntry(42L, "  "));
    assertEquals("", BusinessActivitySemanticTextSupport.namedEntry(null, "主讲人"));
  }

  @Test
  @DisplayName("documentId 口径固定为 BUSINESS_ACTIVITY:{主键}；pointId 确定性派生且互不相同")
  void documentIdAndPointIdAreDeterministic() {
    assertEquals("BUSINESS_ACTIVITY:901", BusinessActivitySemanticTextSupport.documentId(901L));
    String expected =
        UUID.nameUUIDFromBytes("BUSINESS_ACTIVITY:901".getBytes(StandardCharsets.UTF_8)).toString();
    assertEquals(expected, BusinessActivitySemanticTextSupport.pointId(901L));
    assertEquals(
        BusinessActivitySemanticTextSupport.pointId(901L),
        BusinessActivitySemanticTextSupport.pointId(901L));
    assertNotEquals(
        BusinessActivitySemanticTextSupport.pointId(901L),
        BusinessActivitySemanticTextSupport.pointId(902L));
  }

  @Test
  @DisplayName("实体为 null 抛异常（与客户侧拼装口径一致）")
  void nullEntityThrows() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BusinessActivitySemanticTextSupport.buildSemanticText(null, null, null, null, null));
  }
}
