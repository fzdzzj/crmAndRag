package com.slz.crm.knowledge.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 商业活动语义摄取服务测试（add-business-activity-semantic-retrieval 任务 4.2，对齐 {@link
 * CustomerIngestionServiceTest} 先例）。
 *
 * <p>mock embedding 桩（EmbeddingService 返回固定向量，零外呼）；payload 键集、UUID 派生 point、附属语义段、 缺失 mapper
 * 降级、游标回埋成功/失败计数全断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("商业活动语义摄取服务")
class BusinessActivityIngestionServiceTest {

  private static final float[] STUB_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BusinessActivityEntity.class);
    TableInfoHelper.initTableInfo(assistant, BusinessActivityUserEntity.class);
    TableInfoHelper.initTableInfo(assistant, BusinessActivityContactEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerContactEntity.class);
    TableInfoHelper.initTableInfo(assistant, SalesOpportunityEntity.class);
  }

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmEntityVectorStore vectorStore;

  @Mock private UserMapper userMapper;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  @Mock private BusinessActivityUserMapper businessActivityUserMapper;

  @Mock private BusinessActivityContactMapper businessActivityContactMapper;

  private BusinessActivityIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new BusinessActivityIngestionService(
            embeddingService,
            vectorStore,
            userMapper,
            businessActivityMapper,
            customerCompanyMapper,
            salesOpportunityMapper,
            customerContactMapper,
            businessActivityUserMapper,
            businessActivityContactMapper);
  }

  private BusinessActivityEntity entity(
      long id, Long companyId, Long opportunityId, Long creatorId) {
    BusinessActivityEntity e = new BusinessActivityEntity();
    e.setId(id);
    e.setActivityTitle("甲公司产品演示会");
    e.setActivityType("线下拜访");
    e.setActivityContent("客户关注国产化替代与实施周期");
    e.setRemark("两周内回访");
    e.setCompanyId(companyId);
    e.setOpportunityId(opportunityId);
    e.setCreatorId(creatorId);
    return e;
  }

  private String expectedPointId(long id) {
    return UUID.nameUUIDFromBytes(("BUSINESS_ACTIVITY:" + id).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  @Test
  @DisplayName(
      "ingest：payload 键集（entityType/resourceId/ownerUserId/ownerDeptId/companyId/opportunityId/title）"
          + " + UUID 派生 point + documentId/chunkId 口径")
  void ingestUpsertsRecordWithPayloadAndDerivedPoint() {
    UserEntity creator = new UserEntity();
    creator.setId(42L);
    creator.setDeptId(7L);
    when(userMapper.selectById(42L)).thenReturn(creator);
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setCompanyName("甲公司");
    when(customerCompanyMapper.selectById(301L)).thenReturn(company);
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setOpportunityName("甲公司 CRM 采购");
    when(salesOpportunityMapper.selectById(401L)).thenReturn(opportunity);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(901L, 301L, 401L, 42L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertEquals(expectedPointId(901L), record.id());
    assertEquals("BUSINESS_ACTIVITY:901", record.documentId());
    assertEquals("BUSINESS_ACTIVITY:901", record.chunkId());
    assertEquals(0, record.chunkIndex());
    assertEquals(STUB_VECTOR, record.embedding());
    assertTrue(record.text().contains("活动标题: 甲公司产品演示会"));
    assertTrue(record.text().contains("活动类型: 线下拜访"));
    assertTrue(record.text().contains("关联客户: 甲公司"));
    assertTrue(record.text().contains("关联商机: 甲公司 CRM 采购"));
    assertTrue(record.text().contains("沟通内容: 客户关注国产化替代与实施周期"));
    assertTrue(record.text().contains("备注: 两周内回访"));

    assertEquals("BUSINESS_ACTIVITY", record.metadata().get("entityType"));
    assertEquals(901L, record.metadata().get("resourceId"));
    assertEquals(42L, record.metadata().get("ownerUserId"));
    assertEquals(7L, record.metadata().get("ownerDeptId"));
    assertEquals(301L, record.metadata().get("companyId"));
    assertEquals(401L, record.metadata().get("opportunityId"));
    assertEquals("甲公司产品演示会", record.metadata().get("title"));
  }

  @Test
  @DisplayName("ingest：creator 为空不反查库、ownerUserId/ownerDeptId 键缺省")
  void ingestWithoutCreatorSkipsUserLookupAndOmitsOwnerKeys() {
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(902L, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    verify(userMapper, never()).selectById(any());
    assertFalse(record.metadata().containsKey("ownerUserId"));
    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertFalse(record.metadata().containsKey("companyId"));
    assertFalse(record.metadata().containsKey("opportunityId"));
    assertEquals("BUSINESS_ACTIVITY", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：creator 查无此人 ownerDeptId 键缺省；关联客户/商机查无记录时语义文本不含对应字段")
  void ingestWithMissingOwnerAndRelatedEntitiesOmitsKeys() {
    when(userMapper.selectById(404L)).thenReturn(null);
    when(customerCompanyMapper.selectById(301L)).thenReturn(null);
    when(salesOpportunityMapper.selectById(401L)).thenReturn(null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(903L, 301L, 401L, 404L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertTrue(record.metadata().containsKey("companyId"));
    assertFalse(record.text().contains("关联客户"));
    assertFalse(record.text().contains("关联商机"));
  }

  @Test
  @DisplayName("ingest：参与人段与联系人段拼入语义文本（形如 参与人: 42（主讲人）；联系人: 张伟（决策者））")
  void ingestAppendsParticipantAndContactSegments() {
    BusinessActivityUserEntity participant = new BusinessActivityUserEntity();
    participant.setId(1L);
    participant.setActivityId(901L);
    participant.setUserId(42L);
    participant.setUserRole("主讲人");
    when(businessActivityUserMapper.selectList(any())).thenReturn(List.of(participant));

    BusinessActivityContactEntity relation = new BusinessActivityContactEntity();
    relation.setId(1L);
    relation.setActivityId(901L);
    relation.setContactId(2001L);
    relation.setContactRole("决策者");
    when(businessActivityContactMapper.selectList(any())).thenReturn(List.of(relation));
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setId(2001L);
    contact.setName("张伟");
    when(customerContactMapper.selectById(2001L)).thenReturn(contact);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(901L, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertTrue(record.text().contains("；参与人: 42（主讲人）"));
    assertTrue(record.text().contains("；联系人: 张伟（决策者）"));
    // pointId 派生与 payload 规则零改动
    assertEquals(expectedPointId(901L), record.id());
    assertEquals("BUSINESS_ACTIVITY", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：人员 mapper 缺失（评测注入 null）→ 跳过参与人/联系人段，仅拼活动主体文本")
  void ingestWithoutPersonMappersOmitsSegments() {
    BusinessActivityIngestionService noPersonMappers =
        new BusinessActivityIngestionService(
            embeddingService,
            vectorStore,
            userMapper,
            businessActivityMapper,
            customerCompanyMapper,
            salesOpportunityMapper,
            null,
            null,
            null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    noPersonMappers.ingest(entity(901L, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    String text = captor.getValue().text();
    assertTrue(text.contains("活动标题: 甲公司产品演示会"));
    assertFalse(text.contains("参与人"));
    assertFalse(text.contains("联系人"));
  }

  @Test
  @DisplayName("ingest：全部语义字段为空抛异常（调用方按失败计数处理）")
  void ingestWithBlankSemanticTextThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> service.ingest(new BusinessActivityEntity()));
  }

  @Test
  @DisplayName("ingest：实体或 id 为空抛异常且零外呼")
  void ingestWithNullEntityThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(null));
    assertThrows(
        IllegalArgumentException.class, () -> service.ingest(new BusinessActivityEntity()));
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }

  @Test
  @DisplayName("deleteByActivityId：按 documentId=BUSINESS_ACTIVITY:{id} 删除向量")
  void deleteByActivityIdDeletesByDocumentId() {
    service.deleteByActivityId(901L);

    verify(vectorStore).deleteByDocumentId("BUSINESS_ACTIVITY:901");
  }

  @Test
  @DisplayName("deleteByActivityId：id 为空抛异常且零外呼")
  void deleteWithNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.deleteByActivityId(null));
    verify(vectorStore, never()).deleteByDocumentId(anyString());
  }

  @Test
  @DisplayName("reingestAll：游标分页逐条摄取，单条失败计数不中断")
  void reingestAllCountsSuccessAndFailureAcrossBatches() {
    BusinessActivityEntity first = entity(901L, null, null, null);
    BusinessActivityEntity broken = entity(902L, null, null, null);
    BusinessActivityEntity third = entity(903L, null, null, null);
    when(businessActivityMapper.selectList(any()))
        .thenReturn(List.of(first, broken), List.of(third), List.of());
    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("embed down"));

    BusinessActivityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(3, result.total());
    assertEquals(0, result.success());
    assertEquals(3, result.failure());
  }

  @Test
  @DisplayName("reingestAll：全部成功计数如实返回")
  void reingestAllCountsSuccesses() {
    when(businessActivityMapper.selectList(any()))
        .thenReturn(
            List.of(entity(901L, null, null, null), entity(902L, null, null, null)), List.of());
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    BusinessActivityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(2, result.total());
    assertEquals(2, result.success());
    assertEquals(0, result.failure());
  }

  @Test
  @DisplayName("reingestAll：空库零扫描零外呼")
  void reingestAllOnEmptyTableReturnsZeroes() {
    when(businessActivityMapper.selectList(any())).thenReturn(List.of());

    BusinessActivityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(0, result.total());
    assertEquals(0, result.success());
    assertEquals(0, result.failure());
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }
}
