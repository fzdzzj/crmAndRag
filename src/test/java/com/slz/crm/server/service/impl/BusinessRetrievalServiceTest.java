package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.server.ai.port.BusinessRetrievalPort;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataScopeService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 业务数据检索权限闸矩阵（add-customer-semantic-retrieval 任务 3.1 红测先行）。
 *
 * <p>场景全覆盖：归属人可检 / SELF 同事检索不到（且不补 TAGE）/ DEPT 上司可检下属（且补 TAGE 标签资源）/ 管理员 ALL 全可检 / NONE 级零外发（零
 * embed 零 search）/ TAGE 与共享 union 不漏 / DEPT_AND_CHILD 子树 union 且补 TAGE；另含编号接续、去重截断与 降级链。向量库以 mock
 * 承接：权限闸的正确性体现在 VectorSearchRequest.filter 的档位组合上（Qdrant metadata 等值过滤，B4 冻结约定）。
 */
class BusinessRetrievalServiceTest {

  private static final String RESOURCE_TYPE = "customer_company";

  private CrmEntityVectorStore vectorStore;

  private EmbeddingService embeddingService;

  private DataScopeService dataScopeService;

  private SysDeptMapper sysDeptMapper;

  private UserMapper userMapper;

  private BusinessRetrievalService service;

  @BeforeEach
  void setUp() {
    vectorStore = mock(CrmEntityVectorStore.class);
    embeddingService = mock(EmbeddingService.class);
    dataScopeService = mock(DataScopeService.class);
    sysDeptMapper = mock(SysDeptMapper.class);
    userMapper = mock(UserMapper.class);
    service =
        new BusinessRetrievalService(
            vectorStore, embeddingService, dataScopeService, sysDeptMapper, userMapper);
    when(embeddingService.embed(any())).thenReturn(new float[] {0.1F, 0.2F});
  }

  private UserContext userContext(Long userId, Long roleId, Long deptId) {
    return new UserContext(userId, roleId, deptId, DataScopeLevel.NONE, null);
  }

  private void stubLevel(DataScopeLevel level) {
    when(dataScopeService.getHighestDataScopeLevel(any(), any())).thenReturn(level.getCode());
  }

  private VectorSearchHit hit(String chunkId, long resourceId, double score, String companyName) {
    return new VectorSearchHit(
        chunkId,
        "CUSTOMER_COMPANY:" + resourceId,
        score,
        "公司名称： " + companyName,
        Map.of(
            "companyName",
            companyName,
            "entityType",
            "CUSTOMER_COMPANY",
            "resourceId",
            resourceId));
  }

  private SysDeptEntity dept(Long id, Long parentId) {
    SysDeptEntity dept = new SysDeptEntity();
    dept.setId(id);
    dept.setParentId(parentId);
    return dept;
  }

  @Test
  @DisplayName("归属人可检：SELF 档主 filter ownerUserId=当前用户，来源引用字段逐项对齐")
  void retrieve_selfOwnerRetrievesOwnData() {
    stubLevel(DataScopeLevel.SELF);
    when(dataScopeService.getSharedResourceIds(42L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any())).thenReturn(List.of(hit("chunk-1", 301L, 0.92D, "甲公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "甲公司怎么样", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(1);
    assertThat(result.context()).contains("【业务数据检索】").contains("[1] 公司名称： 甲公司");
    assertThat(result.sources()).hasSize(1);
    SourceReference source = result.sources().get(0);
    assertThat(source.sourceType()).isEqualTo("CUSTOMER");
    assertThat(source.route()).isEqualTo("vector");
    assertThat(source.filename()).isEqualTo("甲公司");
    assertThat(source.documentId()).isEqualTo("301");
    assertThat(source.chunkId()).isEqualTo("chunk-1");
    assertThat(source.chunkIndex()).isEqualTo(0);
    assertThat(source.pageNo()).isNull();
    assertThat(source.rowIndex()).isNull();
    assertThat(source.excerpt()).isEqualTo("公司名称： 甲公司");
    assertThat(source.relevanceScore()).isEqualTo(0.92D);

    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore).search(captor.capture());
    assertThat(captor.getValue().filter())
        .containsEntry("entityType", "CUSTOMER_COMPANY")
        .containsEntry("ownerUserId", 42L);
  }

  @Test
  @DisplayName("SELF 同事检索不到：主 filter 锁定 ownerUserId=自己，同事数据物理不可达")
  void retrieve_selfColleagueDataNotRetrievable() {
    stubLevel(DataScopeLevel.SELF);
    when(dataScopeService.getSharedResourceIds(43L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any())).thenReturn(List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "乙公司", userContext(43L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    assertThat(result.sources()).isEmpty();
    assertThat(result.context()).isEmpty();
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore).search(captor.capture());
    assertThat(captor.getValue().filter()).containsEntry("ownerUserId", 43L);
    // SELF 档不补 TAGE：SQL 层 SELF 无 tage，补召回会越权扩大（页面一致性锚定）
    verify(dataScopeService, never()).getTageResourceIds(any(), any());
  }

  @Test
  @DisplayName("DEPT 上司可检下属：ownerDeptId=本部门 filter")
  void retrieve_deptSupervisorRetrievesSubordinates() {
    stubLevel(DataScopeLevel.DEPT);
    when(dataScopeService.getSharedResourceIds(10L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any())).thenReturn(List.of(hit("chunk-d", 305L, 0.8D, "丙公司")));

    service.retrieve(
        new BusinessRetrievalPort.BusinessRetrievalQuery("丙公司", userContext(10L, 3L, 11L), 5, 1));

    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore).search(captor.capture());
    assertThat(captor.getValue().filter()).containsEntry("ownerDeptId", 11L);
  }

  @Test
  @DisplayName("管理员 ALL 全可检：仅 entityType filter，无 owner 维度且不查 TAGE/共享")
  void retrieve_adminAllRetrievesEverything() {
    stubLevel(DataScopeLevel.ALL);
    when(vectorStore.search(any())).thenReturn(List.of(hit("chunk-a", 301L, 0.9D, "甲公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery("客户", userContext(1L, 1L, 1L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(1);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore).search(captor.capture());
    assertThat(captor.getValue().filter()).containsOnlyKeys("entityType");
    verify(dataScopeService, never()).getTageResourceIds(any(), any());
    verify(dataScopeService, never()).getSharedResourceIds(any(), any(), any());
  }

  @Test
  @DisplayName("NONE 级零外发：不 embed 不 search，空返回")
  void retrieve_noneLevelZeroExternalCalls() {
    stubLevel(DataScopeLevel.NONE);

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    assertThat(result.context()).isEmpty();
    assertThat(result.sources()).isEmpty();
    verifyNoInteractions(embeddingService);
    verifyNoInteractions(vectorStore);
    verifyNoInteractions(sysDeptMapper);
  }

  @Test
  @DisplayName("TAGE 绑定不漏：TAGE 档在自有数据外补查标签绑定资源（逐 id filter）")
  void retrieve_tageBindingUnionNotMissed() {
    stubLevel(DataScopeLevel.TAGE);
    when(dataScopeService.getTageResourceIds(3L, RESOURCE_TYPE)).thenReturn(List.of(302L));
    when(dataScopeService.getSharedResourceIds(42L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-own", 301L, 0.9D, "甲公司")),
            List.of(hit("chunk-tage", 302L, 0.7D, "乙公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("301", "302");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, org.mockito.Mockito.times(2)).search(captor.capture());
    assertThat(captor.getAllValues().get(1).filter()).containsEntry("resourceId", 302L);
  }

  @Test
  @DisplayName("共享不漏：SELF 档补查共享资源（逐 id filter）并按相似度降序")
  void retrieve_sharedUnionNotMissed() {
    stubLevel(DataScopeLevel.SELF);
    when(dataScopeService.getSharedResourceIds(42L, 3L, RESOURCE_TYPE)).thenReturn(List.of(303L));
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-own", 301L, 0.6D, "甲公司")),
            List.of(hit("chunk-shared", 303L, 0.95D, "丙公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("303", "301");
  }

  @Test
  @DisplayName("DEPT_AND_CHILD：按部门子树逐 deptId 检索并 union")
  void retrieve_deptAndChildSubtreeUnion() {
    stubLevel(DataScopeLevel.DEPT_AND_CHILD);
    when(dataScopeService.getSharedResourceIds(10L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    // resolver 的 LambdaQueryWrapper 参数绑定是惰性注册（getSqlSegment 前 paramNameValuePairs 为空 map，实测
    // PARAMS={}），
    // 故按调用序返回子节点：第 1 查（根 11）→[12]、第 2 查（12）→[13]、第 3 查（13）→无子节点；
    // 子树递归展开（含环防御）语义由 DataScopeDepartmentResolver 自身测试保障。
    java.util.concurrent.atomic.AtomicInteger deptQuery =
        new java.util.concurrent.atomic.AtomicInteger();
    when(sysDeptMapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              int call = deptQuery.incrementAndGet();
              if (call == 1) {
                return List.of(dept(12L, 11L));
              }
              if (call == 2) {
                return List.of(dept(13L, 12L));
              }
              return List.of();
            });
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-11", 301L, 0.9D, "甲公司")),
            List.of(hit("chunk-12", 302L, 0.8D, "乙公司")),
            List.of(hit("chunk-13", 303L, 0.7D, "丙公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(3);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, org.mockito.Mockito.times(3)).search(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(request -> request.filter().get("ownerDeptId"))
        .containsExactly(11L, 12L, 13L);
  }

  @Test
  @DisplayName("DEPT 档补查 TAGE 绑定：与页面列表 SQL 超集不变量一致，不漏标签资源客户")
  void retrieve_deptSupplementsTageBindings() {
    stubLevel(DataScopeLevel.DEPT);
    when(dataScopeService.getTageResourceIds(3L, RESOURCE_TYPE)).thenReturn(List.of(304L));
    when(dataScopeService.getSharedResourceIds(10L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-dept", 305L, 0.8D, "丙公司")),
            List.of(hit("chunk-tage", 304L, 0.85D, "丁公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("304", "305");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, org.mockito.Mockito.times(2)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter()).containsEntry("ownerDeptId", 11L);
    assertThat(captor.getAllValues().get(1).filter()).containsEntry("resourceId", 304L);
  }

  @Test
  @DisplayName("DEPT_AND_CHILD 档补查 TAGE 绑定：子树 union 之外仍 union 标签资源")
  void retrieve_deptAndChildSupplementsTageBindings() {
    stubLevel(DataScopeLevel.DEPT_AND_CHILD);
    when(sysDeptMapper.selectList(any())).thenReturn(List.of());
    when(dataScopeService.getTageResourceIds(3L, RESOURCE_TYPE)).thenReturn(List.of(306L));
    when(dataScopeService.getSharedResourceIds(10L, 3L, RESOURCE_TYPE)).thenReturn(List.of());
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-dept", 301L, 0.9D, "甲公司")),
            List.of(hit("chunk-tage", 306L, 0.6D, "戊公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, org.mockito.Mockito.times(2)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter()).containsEntry("ownerDeptId", 11L);
    assertThat(captor.getAllValues().get(1).filter()).containsEntry("resourceId", 306L);
  }

  @Test
  @DisplayName("编号接续：numberStart=3 时业务片段编号自 [3] 起")
  void retrieve_numberingContinuesFromNumberStart() {
    stubLevel(DataScopeLevel.ALL);
    when(vectorStore.search(any()))
        .thenReturn(List.of(hit("c1", 301L, 0.9D, "甲公司"), hit("c2", 302L, 0.8D, "乙公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery("客户", userContext(1L, 1L, 1L), 5, 3));

    assertThat(result.context()).contains("[3] 公司名称： 甲公司").contains("[4] 公司名称： 乙公司");
    assertThat(result.context()).doesNotContain("[1]").doesNotContain("[2]");
  }

  @Test
  @DisplayName("多 filter 档命中按 chunkId 去重（取高分）、相似度降序并截断 topK")
  void retrieve_dedupSortTruncate() {
    stubLevel(DataScopeLevel.TAGE);
    when(dataScopeService.getTageResourceIds(3L, RESOURCE_TYPE)).thenReturn(List.of(301L, 302L));
    when(dataScopeService.getSharedResourceIds(42L, 3L, RESOURCE_TYPE)).thenReturn(List.of(301L));
    when(vectorStore.search(any()))
        .thenReturn(
            List.of(hit("chunk-x", 301L, 0.5D, "甲公司")),
            List.of(hit("chunk-x", 301L, 0.9D, "甲公司"), hit("chunk-y", 302L, 0.7D, "乙公司")),
            List.of(hit("chunk-x", 301L, 0.8D, "甲公司")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 2, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources())
        .extracting(SourceReference::chunkId)
        .containsExactly("chunk-x", "chunk-y");
    assertThat(result.sources().get(0).relevanceScore()).isEqualTo(0.9D);
  }

  @Test
  @DisplayName("无身份快照：空返回零外发")
  void retrieve_nullUserContextReturnsEmpty() {
    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(new BusinessRetrievalPort.BusinessRetrievalQuery("客户", null, 5, 1));

    assertThat(result.hitCount()).isZero();
    verifyNoInteractions(embeddingService, vectorStore, dataScopeService);
  }

  @Test
  @DisplayName("空查询：空返回零外发")
  void retrieve_blankQueryReturnsEmpty() {
    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "  ", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    verifyNoInteractions(embeddingService, vectorStore, dataScopeService);
  }

  @Test
  @DisplayName("embed 失败：WARN 空返回降级，不阻断主链路")
  void retrieve_embedFailureDegradesToEmpty() {
    stubLevel(DataScopeLevel.SELF);
    when(embeddingService.embed(any())).thenThrow(new IllegalStateException("embed down"));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    verify(vectorStore, never()).search(any());
  }
}
