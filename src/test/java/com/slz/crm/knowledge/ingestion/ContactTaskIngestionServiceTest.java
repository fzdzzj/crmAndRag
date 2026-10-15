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
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
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
 * 跟进任务语义摄取服务测试（add-unified-business-semantic-retrieval 任务 5.2，对齐 {@link
 * ContractIngestionServiceTest} 先例）。
 *
 * <p>mock embedding 桩（EmbeddingService 返回固定向量，零外呼）；payload 键集、UUID 派生 point、task_content 富文本段、缺失
 * mapper 降级、游标回埋成功/失败计数全断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("跟进任务语义摄取服务")
class ContactTaskIngestionServiceTest {

  private static final float[] STUB_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, ContactTaskEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(assistant, SalesOpportunityEntity.class);
    TableInfoHelper.initTableInfo(assistant, CustomerContactEntity.class);
    TableInfoHelper.initTableInfo(assistant, UserEntity.class);
  }

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmEntityVectorStore vectorStore;

  @Mock private UserMapper userMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  private ContactTaskIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new ContactTaskIngestionService(
            embeddingService,
            vectorStore,
            userMapper,
            contactTaskMapper,
            customerCompanyMapper,
            salesOpportunityMapper,
            customerContactMapper);
  }

  private ContactTaskEntity entity(
      long id, Long companyId, Long opportunityId, Long contactId, Long assigneeId) {
    ContactTaskEntity e = new ContactTaskEntity();
    e.setId(id);
    e.setTaskTitle("跟进甲公司续约需求");
    e.setTaskType("电话");
    e.setPriority(6);
    e.setStatus(1);
    e.setStartTime(
        Date.from(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    e.setTaskContent("客户反馈希望升级为年度订阅，并要求 2 月前提供商务方案");
    e.setCompanyId(companyId);
    e.setOpportunityId(opportunityId);
    e.setContactId(contactId);
    e.setAssigneeId(assigneeId);
    return e;
  }

  private String expectedPointId(long id) {
    return UUID.nameUUIDFromBytes(("CONTACT_TASK:" + id).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  @Test
  @DisplayName("ingest：payload 键集 + UUID 派生 point + task_content 富文本拼入文本")
  void ingestUpsertsRecordWithPayloadAndDerivedPoint() {
    UserEntity assignee = new UserEntity();
    assignee.setId(42L);
    assignee.setDeptId(7L);
    assignee.setRealName("李强");
    when(userMapper.selectById(42L)).thenReturn(assignee);
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setCompanyName("甲公司");
    when(customerCompanyMapper.selectById(301L)).thenReturn(company);
    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setOpportunityName("年度服务采购");
    when(salesOpportunityMapper.selectById(2001L)).thenReturn(opportunity);
    CustomerContactEntity contact = new CustomerContactEntity();
    contact.setId(5001L);
    contact.setName("张伟");
    when(customerContactMapper.selectById(5001L)).thenReturn(contact);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1001L, 301L, 2001L, 5001L, 42L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertEquals(expectedPointId(1001L), record.id());
    assertEquals("CONTACT_TASK:1001", record.documentId());
    assertEquals("CONTACT_TASK:1001", record.chunkId());
    assertEquals(0, record.chunkIndex());
    assertEquals(STUB_VECTOR, record.embedding());
    assertTrue(record.text().contains("任务主题: 跟进甲公司续约需求"));
    assertTrue(record.text().contains("任务类型: 电话"));
    assertTrue(record.text().contains("优先级: 6"));
    assertTrue(record.text().contains("任务状态: 进行中"));
    assertTrue(record.text().contains("关联客户: 甲公司"));
    assertTrue(record.text().contains("关联商机: 年度服务采购"));
    assertTrue(record.text().contains("关联联系人: 张伟"));
    assertTrue(record.text().contains("执行人: 李强"));
    assertTrue(record.text().contains("任务内容: 客户反馈希望升级为年度订阅，并要求 2 月前提供商务方案"));

    assertEquals("CONTACT_TASK", record.metadata().get("entityType"));
    assertEquals(1001L, record.metadata().get("resourceId"));
    assertEquals(42L, record.metadata().get("ownerUserId"));
    assertEquals(7L, record.metadata().get("ownerDeptId"));
    assertEquals(301L, record.metadata().get("companyId"));
    assertEquals(2001L, record.metadata().get("opportunityId"));
    assertEquals("跟进甲公司续约需求", record.metadata().get("title"));
  }

  @Test
  @DisplayName("ingest：assignee/关联实体为空不反查库、owner 键缺省；关联段跳过")
  void ingestWithoutAssigneeSkipsLookups() {
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1002L, null, null, null, null));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    verify(userMapper, never()).selectById(any());
    assertFalse(record.metadata().containsKey("ownerUserId"));
    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertFalse(record.metadata().containsKey("companyId"));
    assertFalse(record.metadata().containsKey("opportunityId"));
    assertFalse(record.text().contains("关联客户"));
    assertFalse(record.text().contains("关联商机"));
    assertFalse(record.text().contains("执行人"));
    assertEquals("CONTACT_TASK", record.metadata().get("entityType"));
  }

  @Test
  @DisplayName("ingest：assignee 查无此人 ownerDeptId 键缺省")
  void ingestWithMissingAssigneeOmitsOwnerDept() {
    when(userMapper.selectById(404L)).thenReturn(null);
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    service.ingest(entity(1003L, 301L, null, null, 404L));

    ArgumentCaptor<VectorRecord> captor = ArgumentCaptor.forClass(VectorRecord.class);
    verify(vectorStore).upsert(captor.capture());
    VectorRecord record = captor.getValue();

    assertFalse(record.metadata().containsKey("ownerDeptId"));
    assertTrue(record.metadata().containsKey("companyId"));
    assertFalse(record.text().contains("执行人"));
  }

  @Test
  @DisplayName("ingest：全部语义字段为空抛异常（调用方按失败计数处理）")
  void ingestWithBlankSemanticTextThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(new ContactTaskEntity()));
  }

  @Test
  @DisplayName("ingest：实体或 id 为空抛异常且零外呼")
  void ingestWithNullEntityThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.ingest(null));
    assertThrows(IllegalArgumentException.class, () -> service.ingest(new ContactTaskEntity()));
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }

  @Test
  @DisplayName("deleteByTaskId：按 documentId=CONTACT_TASK:{id} 删除向量")
  void deleteByTaskIdDeletesByDocumentId() {
    service.deleteByTaskId(1001L);

    verify(vectorStore).deleteByDocumentId("CONTACT_TASK:1001");
  }

  @Test
  @DisplayName("deleteByTaskId：id 为空抛异常且零外呼")
  void deleteWithNullIdThrows() {
    assertThrows(IllegalArgumentException.class, () -> service.deleteByTaskId(null));
    verify(vectorStore, never()).deleteByDocumentId(anyString());
  }

  @Test
  @DisplayName("reingestAll：游标分页逐条摄取，单条失败计数不中断")
  void reingestAllCountsSuccessAndFailureAcrossBatches() {
    ContactTaskEntity first = entity(1001L, null, null, null, null);
    ContactTaskEntity broken = entity(1002L, null, null, null, null);
    ContactTaskEntity third = entity(1003L, null, null, null, null);
    when(contactTaskMapper.selectList(any()))
        .thenReturn(List.of(first, broken), List.of(third), List.of());
    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("embed down"));

    ContactTaskIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(3, result.total());
    assertEquals(0, result.success());
    assertEquals(3, result.failure());
  }

  @Test
  @DisplayName("reingestAll：全部成功计数如实返回")
  void reingestAllCountsSuccesses() {
    when(contactTaskMapper.selectList(any()))
        .thenReturn(
            List.of(entity(1001L, null, null, null, null), entity(1002L, null, null, null, null)),
            List.of());
    when(embeddingService.embed(anyString())).thenReturn(STUB_VECTOR);

    ContactTaskIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(2, result.total());
    assertEquals(2, result.success());
    assertEquals(0, result.failure());
  }

  @Test
  @DisplayName("reingestAll：空库零扫描零外呼")
  void reingestAllOnEmptyTableReturnsZeroes() {
    when(contactTaskMapper.selectList(any())).thenReturn(List.of());

    ContactTaskIngestionService.ReingestResult result = service.reingestAll();

    assertEquals(0, result.total());
    assertEquals(0, result.success());
    assertEquals(0, result.failure());
    verify(embeddingService, never()).embed(anyString());
    verify(vectorStore, never()).upsert(any());
  }
}
