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
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
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
 * 销售商机语义摄取服务测试（add-sales-opportunity-semantic-retrieval 任务 4.2，对齐 {@link
 * BusinessActivityIngestionServiceTest} 先例）。
 *
 * <p>mock embedding 桩（EmbeddingService 返回固定向量，零外呼）；payload 键集、UUID 派生 point、关联语义段、 缺失 mapper
 * 降级、游标回埋成功/失败计数全断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("销售商机语义摄取服务")
class SalesOpportunityIngestionServiceTest {

  private static final float[] STUB_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, SalesOpportunityEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerContactEntity.class);
    TableInfoHelper.initTableInfo(assistant, UserEntity.class);
  }

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmEntityVectorStore vectorStore;

  @Mock private UserMapper userMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  private SalesOpportunityIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new SalesOpportunityIngestionService(
            embeddingService,
            vectorStore,
            userMapper,
            salesOpportunityMapper,
            customerCompanyMapper,
            customerContactMapper);
  }

  private SalesOpportunityEntity entity(long id, Long companyId, Long contactId, Long ownerId) {
    SalesOpportunityEntity e = new SalesOpportunityEntity();
    e.setId(id);
    e.setOpportunityName("甲公司年度服务采购");
    e.setStage(2);
    e.setSource("老客户转介绍");
    e.setDescription("客户关注国产化替代与实施周期，要求提供 POC 时间表");
    e.setCompanyId(companyId);
    e.setContactId(contactId);
    e.setOwnerId(ownerId);
    return e;
  }

  private String expectedPointId(long id) {
    return UUID.nameUUIDFromBytes(("SALES_OPPORTUNITY:" + id).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  @Test
  @DisplayName(
      "ingest：payload 键集（entityType/resourceId/ownerUserId/ownerDeptId/companyId/title）"
          + " + UUID 派生 point + documentId/chunkId 口径")
  void ingestUpsertsRecordWithPayloadAndDerivedPoint() {
    UserEntity owner = new UserEntity();
    owner.setId(42L);
    owner.setDeptId(7L);
    owner.setRealName("李强");
    when(userMapper.selectById(42L)).thenReturn(owner);
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setCompanyName("甲公司");
    when(customerCompanyMapper.selectById(301L)).thenReturn(company);
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setId(2001L);
    contact.setName("张伟");
    when(customerContactMapper.selectById(2001L)).thenReturn(contact);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1001L, 301L, 2001L, 42L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertEquals(expectedPointId(1001L), record.id());
    assertEquals("SALES_OPPORTUNITY:1001", record.documentId());
    assertEquals("SALES_OPPORTUNITY:1001", record.chunkId());
    assertEquals(0, record.chunkIndex());
    assertEquals(STUB_VECTOR, record.embedding());
    assertTrue(record.text().contains("商机名称: 甲公司年度服务采购"));
    assertTrue(record.text().contains("关联客户: 甲公司"));
    assertTrue(record.text().contains("主要联系人: 张伟"));
    assertTrue(record.text().contains("商机阶段: 确认商机"));
    assertTrue(record.text().contains("机会来源: 老客户转介绍"));
    assertTrue(record.text().contains("需求描述: 客户关注国产化替代与实施周期，要求提供 POC 时间表"));
    assertTrue(record.text().contains("跟进负责人: 李强"));

    assertEquals("SALES_OPPORTUNITY", record.metadata().get("entityType"));
    assertEquals(1001L, record.metadata().get("resourceId"));
    assertEquals(42L, record.metadata().get("ownerUserId"));
    assertEquals(7L, record.metadata().get("ownerDeptId"));
    assertEquals(301L, record.metadata().get("companyId"));
    assertEquals("甲公司年度服务采购", record.metadata().get("title"));
  }

  @Test
  @DisplayName("ingest：owner 为空不反查库、ownerUserId/ownerDeptId 键缺省")
  void ingestWithoutOwnerSkipsUserLookupAndOmitsOwnerKeys() {
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1002L, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    verify(userMapper, never()).selectById(any());
    assertFalse(record.metadata().containsKey("ownerUserId"));
    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertFalse(record.metadata().containsKey("companyId"));
    assertEquals("SALES_OPPORTUNITY", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：owner 查无此人 ownerDeptId 键缺省；关联客户/联系人查无记录时语义文本不含对应字段")
  void ingestWithMissingOwnerAndRelatedEntitiesOmitsKeys() {
    when(userMapper.selectById(404L)).thenReturn(null);
    when(customerCompanyMapper.selectById(301L)).thenReturn(null);
    when(customerContactMapper.selectById(2001L)).thenReturn(null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1003L, 301L, 2001L, 404L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertTrue(record.metadata().containsKey("companyId"));
    assertFalse(record.text().contains("关联客户"));
    assertFalse(record.text().contains("主要联系人"));
    assertFalse(record.text().contains("跟进负责人"));
  }

  @Test
  @DisplayName("ingest：全部语义字段为空抛异常（调用方按失败计数处理）")
  void ingestWithBlankSemanticTextThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> service.ingest(new SalesOpportunityEntity()));
  }

  @Test
  @DisplayName("ingest：实体或 id 为空抛异常且零外呼")
  void ingestWithNullEntityThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(null));
    assertThrows(
        IllegalArgumentException.class, () -> service.ingest(new SalesOpportunityEntity()));
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }

  @Test
  @DisplayName("deleteByOpportunityId：按 documentId=SALES_OPPORTUNITY:{id} 删除向量")
  void deleteByOpportunityIdDeletesByDocumentId() {
    service.deleteByOpportunityId(1001L);

    verify(vectorStore).deleteByDocumentId("SALES_OPPORTUNITY:1001");
  }

  @Test
  @DisplayName("deleteByOpportunityId：id 为空抛异常且零外呼")
  void deleteWithNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.deleteByOpportunityId(null));
    verify(vectorStore, never()).deleteByDocumentId(anyString());
  }

  @Test
  @DisplayName("reingestAll：游标分页逐条摄取，单条失败计数不中断")
  void reingestAllCountsSuccessAndFailureAcrossBatches() {
    SalesOpportunityEntity first = entity(1001L, null, null, null);
    SalesOpportunityEntity broken = entity(1002L, null, null, null);
    SalesOpportunityEntity third = entity(1003L, null, null, null);
    when(salesOpportunityMapper.selectList(any()))
        .thenReturn(List.of(first, broken), List.of(third), List.of());
    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("embed down"));

    SalesOpportunityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(3, result.total());
    assertEquals(0, result.success());
    assertEquals(3, result.failure());
  }

  @Test
  @DisplayName("reingestAll：全部成功计数如实返回")
  void reingestAllCountsSuccesses() {
    when(salesOpportunityMapper.selectList(any()))
        .thenReturn(
            List.of(entity(1001L, null, null, null), entity(1002L, null, null, null)), List.of());
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    SalesOpportunityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(2, result.total());
    assertEquals(2, result.success());
    assertEquals(0, result.failure());
  }

  @Test
  @DisplayName("reingestAll：空库零扫描零外呼")
  void reingestAllOnEmptyTableReturnsZeroes() {
    when(salesOpportunityMapper.selectList(any())).thenReturn(List.of());

    SalesOpportunityIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(0, result.total());
    assertEquals(0, result.success());
    assertEquals(0, result.failure());
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }
}
