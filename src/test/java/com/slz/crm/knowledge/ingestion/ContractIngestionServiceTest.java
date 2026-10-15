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
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
 * 合同语义摄取服务测试（add-unified-business-semantic-retrieval 任务 5.2，对齐 {@link
 * SalesOpportunityIngestionServiceTest} 先例）。
 *
 * <p>mock embedding 桩（EmbeddingService 返回固定向量，零外呼）；payload 键集、UUID 派生 point、订单明细/回款附属段、 缺失 mapper
 * 降级、游标回埋成功/失败计数全断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("合同语义摄取服务")
class ContractIngestionServiceTest {

  private static final float[] STUB_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, ContractEntity.class);
    TableInfoHelper.initTableInfo(assistant, ContractOrderItemEntity.class);
    TableInfoHelper.initTableInfo(assistant, PaymentRecordEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(assistant, SalesOpportunityEntity.class);
    TableInfoHelper.initTableInfo(assistant, UserEntity.class);
  }

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmEntityVectorStore vectorStore;

  @Mock private UserMapper userMapper;

  @Mock private ContractMapper contractMapper;

  @Mock private ContractOrderItemMapper contractOrderItemMapper;

  @Mock private PaymentRecordMapper paymentRecordMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  private ContractIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new ContractIngestionService(
            embeddingService,
            vectorStore,
            userMapper,
            contractMapper,
            contractOrderItemMapper,
            paymentRecordMapper,
            customerCompanyMapper,
            salesOpportunityMapper);
  }

  private ContractEntity entity(long id, Long companyId, Long opportunityId, Long ownerId) {
    ContractEntity e = new ContractEntity();
    e.setId(id);
    e.setContractNo("HT-2026-" + id);
    e.setContractName("甲公司年度软件服务合同");
    e.setTotalAmount(new BigDecimal("150000.00"));
    e.setContractStatus(1);
    e.setSignDate(LocalDateTime.of(2026, 1, 15, 10, 0));
    e.setCompanyId(companyId);
    e.setOpportunityId(opportunityId);
    e.setOwnerId(ownerId);
    return e;
  }

  private String expectedPointId(long id) {
    return UUID.nameUUIDFromBytes(("CONTRACT:" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  @Test
  @DisplayName("ingest：payload 键集 + UUID 派生 point + 订单明细/回款附属段拼入文本")
  void ingestUpsertsRecordWithPayloadAndDerivedPoint() {
    UserEntity owner = new UserEntity();
    owner.setId(42L);
    owner.setDeptId(7L);
    owner.setRealName("李强");
    when(userMapper.selectById(42L)).thenReturn(owner);
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setCompanyName("甲公司");
    when(customerCompanyMapper.selectById(301L)).thenReturn(company);
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setOpportunityName("年度服务采购");
    when(salesOpportunityMapper.selectById(2001L)).thenReturn(opportunity);
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(1L);
    item.setProductName("数据库服务");
    item.setRemark("标准版");
    item.setUnitPrice(new BigDecimal("10000"));
    item.setQuantity(new BigDecimal("2"));
    when(contractOrderItemMapper.selectList(any())).thenReturn(List.of(item));
    PaymentRecordEntity payment = new PaymentRecordEntity();
    payment.setId(1L);
    payment.setPaymentNo("RK-001");
    payment.setPaymentAmount(new BigDecimal("50000"));
    payment.setPaymentDate(LocalDateTime.of(2026, 1, 15, 0, 0));
    payment.setPaymentMethod("银行转账");
    when(paymentRecordMapper.selectList(any())).thenReturn(List.of(payment));
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1001L, 301L, 2001L, 42L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertEquals(expectedPointId(1001L), record.id());
    assertEquals("CONTRACT:1001", record.documentId());
    assertEquals("CONTRACT:1001", record.chunkId());
    assertEquals(0, record.chunkIndex());
    assertEquals(STUB_VECTOR, record.embedding());
    assertTrue(record.text().contains("合同名称: 甲公司年度软件服务合同"));
    assertTrue(record.text().contains("关联客户: 甲公司"));
    assertTrue(record.text().contains("关联商机: 年度服务采购"));
    assertTrue(record.text().contains("负责人: 李强"));
    assertTrue(record.text().contains("订单明细: 数据库服务（规格: 标准版，单价: 10000，数量: 2）"));
    assertTrue(record.text().contains("回款记录: 2026-01-15 银行转账 50000（单号 RK-001）"));

    assertEquals("CONTRACT", record.metadata().get("entityType"));
    assertEquals(1001L, record.metadata().get("resourceId"));
    assertEquals(42L, record.metadata().get("ownerUserId"));
    assertEquals(7L, record.metadata().get("ownerDeptId"));
    assertEquals(301L, record.metadata().get("companyId"));
    assertEquals("甲公司年度软件服务合同", record.metadata().get("title"));
  }

  @Test
  @DisplayName("ingest：owner/关联实体为空不反查库、owner 键缺省；明细/回款 mapper 缺失跳附属段")
  void ingestWithoutOwnerSkipsLookupsAndSegments() {
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1002L, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    verify(userMapper, never()).selectById(any());
    assertFalse(record.metadata().containsKey("ownerUserId"));
    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertFalse(record.metadata().containsKey("companyId"));
    // 无 owner 时不反查用户，但明细/回款段仍会查询（mock 返回空 → 段跳过）
    assertFalse(record.text().contains("订单明细"));
    assertFalse(record.text().contains("回款记录"));
    assertEquals("CONTRACT", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：owner 查无此人 ownerDeptId 键缺省；关联客户/商机查无记录时文本不含对应字段")
  void ingestWithMissingOwnerAndRelatedEntitiesOmitsKeys() {
    when(userMapper.selectById(404L)).thenReturn(null);
    when(customerCompanyMapper.selectById(301L)).thenReturn(null);
    when(salesOpportunityMapper.selectById(2001L)).thenReturn(null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1003L, 301L, 2001L, 404L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertTrue(record.metadata().containsKey("companyId"));
    assertFalse(record.text().contains("关联客户"));
    assertFalse(record.text().contains("关联商机"));
    assertFalse(record.text().contains("负责人"));
  }

  @Test
  @DisplayName("ingest：全部语义字段为空抛异常（调用方按失败计数处理）")
  void ingestWithBlankSemanticTextThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(new ContractEntity()));
  }

  @Test
  @DisplayName("ingest：实体或 id 为空抛异常且零外呼")
  void ingestWithNullEntityThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(null));
    assertThrows(IllegalArgumentException.class, () -> service.ingest(new ContractEntity()));
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }

  @Test
  @DisplayName("deleteByContractId：按 documentId=CONTRACT:{id} 删除向量")
  void deleteByContractIdDeletesByDocumentId() {
    service.deleteByContractId(1001L);

    verify(vectorStore).deleteByDocumentId("CONTRACT:1001");
  }

  @Test
  @DisplayName("deleteByContractId：id 为空抛异常且零外呼")
  void deleteWithNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.deleteByContractId(null));
    verify(vectorStore, never()).deleteByDocumentId(anyString());
  }

  @Test
  @DisplayName("reingestAll：游标分页逐条摄取，单条失败计数不中断")
  void reingestAllCountsSuccessAndFailureAcrossBatches() {
    ContractEntity first = entity(1001L, null, null, null);
    ContractEntity broken = entity(1002L, null, null, null);
    ContractEntity third = entity(1003L, null, null, null);
    when(contractMapper.selectList(any()))
        .thenReturn(List.of(first, broken), List.of(third), List.of());
    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("embed down"));

    ContractIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(3, result.total());
    assertEquals(0, result.success());
    assertEquals(3, result.failure());
  }

  @Test
  @DisplayName("reingestAll：全部成功计数如实返回")
  void reingestAllCountsSuccesses() {
    when(contractMapper.selectList(any()))
        .thenReturn(
            List.of(entity(1001L, null, null, null), entity(1002L, null, null, null)), List.of());
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    ContractIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(2, result.total());
    assertEquals(2, result.success());
    assertEquals(0, result.failure());
  }

  @Test
  @DisplayName("reingestAll：空库零扫描零外呼")
  void reingestAllOnEmptyTableReturnsZeroes() {
    when(contractMapper.selectList(any())).thenReturn(List.of());

    ContractIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(0, result.total());
    assertEquals(0, result.success());
    assertEquals(0, result.failure());
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }
}
