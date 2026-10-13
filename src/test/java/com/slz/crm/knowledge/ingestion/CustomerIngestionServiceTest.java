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
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
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
 * 客户语义摄取服务测试（add-customer-semantic-retrieval 任务 2.1 红测先行）。
 *
 * <p>mock embedding 桩（EmbeddingService 返回固定向量，零外呼）；payload 五键、UUID 派生 point、 游标回埋成功/失败计数全断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户语义摄取服务")
class CustomerIngestionServiceTest {

  private static final float[] STUB_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerContactEntity.class);
  }

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmEntityVectorStore vectorStore;

  @Mock private UserMapper userMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  private CustomerIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new CustomerIngestionService(
            embeddingService, vectorStore, userMapper, customerCompanyMapper, customerContactMapper);
  }

  private CustomerCompanyEntity entity(long id, String companyName, Long ownerId) {
    CustomerCompanyEntity e = new CustomerCompanyEntity();
    e.setId(id);
    e.setCompanyName(companyName);
    e.setIndustry("软件和信息技术服务业");
    e.setOwnerId(ownerId);
    return e;
  }

  private String expectedPointId(long id) {
    return UUID.nameUUIDFromBytes(("CUSTOMER_COMPANY:" + id).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  @Test
  @DisplayName("ingest：payload 五键 + UUID 派生 point + documentId/chunkId 口径")
  void ingestUpsertsRecordWithPayloadAndDerivedPoint() {
    UserEntity owner = new UserEntity();
    owner.setId(9L);
    owner.setDeptId(7L);
    when(userMapper.selectById(9L)).thenReturn(owner);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(101L, "示例科技有限公司", 9L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertEquals(expectedPointId(101L), record.id());
    assertEquals("CUSTOMER_COMPANY:101", record.documentId());
    assertEquals("CUSTOMER_COMPANY:101", record.chunkId());
    assertEquals(0, record.chunkIndex());
    assertTrue(record.text().contains("公司名称: 示例科技有限公司"));
    assertTrue(record.text().contains("所属行业: 软件和信息技术服务业"));
    assertEquals(STUB_VECTOR, record.embedding());

    assertEquals("CUSTOMER_COMPANY", record.metadata().get("entityType"));
    assertEquals(101L, record.metadata().get("resourceId"));
    assertEquals(9L, record.metadata().get("ownerUserId"));
    assertEquals(7L, record.metadata().get("ownerDeptId"));
    assertEquals("示例科技有限公司", record.metadata().get("companyName"));
  }

  @Test
  @DisplayName("ingest：owner 为空不反查库、ownerUserId/ownerDeptId 键缺省")
  void ingestWithoutOwnerSkipsUserLookupAndOmitsOwnerKeys() {
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(102L, "无归属人公司", null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    verify(userMapper, never()).selectById(any());
    assertFalse(record.metadata().containsKey("ownerUserId"));
    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertEquals("CUSTOMER_COMPANY", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：owner 查无此人（含已删用户）ownerDeptId 键缺省")
  void ingestWithMissingOwnerOmitsDeptKey() {
    when(userMapper.selectById(404L)).thenReturn(null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(103L, "归属人缺失公司", 404L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    assertFalse(captor.getValue().metadata().containsKey("ownerDeptId"));
  }

  @Test
  @DisplayName("ingest：所属联系人列表拼入语义文本（形如 联系人: 姓名（职位，手机，email））")
  void ingestAppendsContactSegmentToSemanticText() {
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setId(11L);
    contact.setCompanyId(101L);
    contact.setName("张伟");
    contact.setPosition("销售总监");
    contact.setMobile("13800138011");
    contact.setEmail("zhangwei@slz.com");
    contact.setIsDeleted(false);
    when(customerContactMapper.selectList(any())).thenReturn(List.of(contact));
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(101L, "示例科技有限公司", null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();
    assertTrue(record.text().contains("公司名称: 示例科技有限公司"));
    assertTrue(record.text().contains("；联系人: 张伟（销售总监，13800138011，zhangwei@slz.com）"));
    // pointId 派生与 payload 规则零改动
    assertEquals(expectedPointId(101L), record.id());
    assertEquals("CUSTOMER_COMPANY", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：customerContactMapper 缺失（评测注入 null）→ 跳过联系人段、仅拼公司文本")
  void ingestWithoutContactMapperOmitsContactSegment() {
    CustomerIngestionService noContactMapper =
        new CustomerIngestionService(
            embeddingService, vectorStore, userMapper, customerCompanyMapper, null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    noContactMapper.ingest(entity(101L, "示例科技有限公司", null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    String text = captor.getValue().text();
    assertTrue(text.contains("公司名称: 示例科技有限公司"));
    org.junit.jupiter.api.Assertions.assertFalse(text.contains("联系人"));
  }

  @Test
  @DisplayName("ingest：全部联系人被软删 → 联系人段为空、文本不含软删联系人")
  void ingestSkipsSoftDeletedContacts() {
    CustomerContactEntity deleted = new CustomerContactEntity();
    deleted.setId(11L);
    deleted.setCompanyId(101L);
    deleted.setName("已删联系人");
    deleted.setIsDeleted(true);
    when(customerContactMapper.selectList(any())).thenReturn(List.of(deleted));
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(101L, "示例科技有限公司", null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    org.junit.jupiter.api.Assertions.assertFalse(captor.getValue().text().contains("已删联系人"));
    assertTrue(captor.getValue().text().contains("公司名称: 示例科技有限公司"));
  }

  @Test
  @DisplayName("ingest：全空字段语义文本为空 → 抛错且零嵌入零写入")
  void ingestRejectsBlankSemanticText() {
    CustomerCompanyEntity e = new CustomerCompanyEntity();
    e.setId(104L);

    assertThrows(IllegalArgumentException.class, () -> service.ingest(e));

    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any(VectorRecord.class));
  }

  @Test
  @DisplayName("deleteByCompanyId：按 documentId 口径删向量")
  void deleteByCompanyIdDeletesByDocumentId() {
    service.deleteByCompanyId(105L);

    verify(vectorStore).deleteByDocumentId("CUSTOMER_COMPANY:105");
  }

  @Test
  @DisplayName("reingestAll：游标分页逐条摄取，单条失败计数不中断")
  void reingestAllCountsSuccessAndFailureAcrossBatches() {
    CustomerCompanyEntity ok = entity(1L, "成功公司", null);
    CustomerCompanyEntity bad = new CustomerCompanyEntity(); // 全空语义字段 → 文本空 → 失败计数
    bad.setId(2L);
    when(customerCompanyMapper.selectList(any()))
        .thenReturn(List.of(ok, bad))
        .thenReturn(List.of());
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    CustomerIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(2, result.total());
    assertEquals(1, result.success());
    assertEquals(1, result.failure());
    verify(vectorStore).upsert(any(VectorRecord.class));
  }

  @Test
  @DisplayName("reingestAll：空库零扫描零外呼")
  void reingestAllOnEmptyTableReturnsZeroes() {
    when(customerCompanyMapper.selectList(any())).thenReturn(List.of());

    CustomerIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(0, result.total());
    assertEquals(0, result.success());
    assertEquals(0, result.failure());
    verify(embeddingService, never()).embed(anyString());
  }
}
