package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * 业务数据检索权限闸矩阵（add-customer-semantic-retrieval 任务 3.1 红测先行；add-business-activity-semantic-retrieval
 * 任务 4.1 扩展为双业务实体；add-sales-opportunity-semantic-retrieval 任务 4.1 扩展为三业务实体）。
 *
 * <p>场景全覆盖（客户公司 + 商业活动 + 销售商机各自独立过闸）：归属人可检 / SELF 跨部门同事检索不到（且不补 TAGE）/ DEPT 上司 可检下属（且补 TAGE 标签资源）/
 * 管理员 ALL 全可检 / 三实体皆 NONE 零外发（零 embed 零 search）/ 单实体 NONE 其他实体 照常召回 / TAGE 与共享 union 不漏 /
 * DEPT_AND_CHILD 子树 union 且补 TAGE；另含编号接续、去重截断、来源判源与降级链。
 *
 * <p>向量库以 mock 按 {@code entityType} 路由承接：权限闸的正确性体现在 VectorSearchRequest.filter 的「实体 + 档位」
 * 组合上（Qdrant metadata 等值过滤，B4 冻结约定）。客户 owner 锚定 owner_id、商业活动 owner 锚定 creator_id、销售商机 owner 锚定
 * owner_id（页面列表 SELF/DEPT 档判权字段同源）。
 */
class BusinessRetrievalServiceTest {

  private static final String CUSTOMER = "customer_company";

  private static final String ACTIVITY = "business_activity";

  private static final String OPPORTUNITY = "sales_opportunity";

  private static final String ENTITY_TYPE = "entityType";

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

  /** 三实体档位桩（客户面 + 商业活动面 + 销售商机面各自独立）。 */
  private void stubLevels(
      DataScopeLevel customerLevel, DataScopeLevel activityLevel, DataScopeLevel opportunityLevel) {
    when(dataScopeService.getHighestDataScopeLevel(any(), any()))
        .thenAnswer(
            invocation -> {
              String resourceType = invocation.getArgument(1);
              if (ACTIVITY.equals(resourceType)) {
                return activityLevel.getCode();
              }
              if (OPPORTUNITY.equals(resourceType)) {
                return opportunityLevel.getCode();
              }
              return customerLevel.getCode();
            });
  }

  /** 三实体同档桩（多数既有场景：三面档位一致）。 */
  private void stubLevel(DataScopeLevel level) {
    stubLevels(level, level, level);
  }

  /**
   * 按调用序喂入检索结果（客户面/商业活动面/销售商机面检索按实体顺序交替，故传参需按完整调用序列给出）。
   *
   * <p>注意：必须用 {@code thenReturn(第一个)} + {@code thenReturn(其余)} 的链式两步写法——「新起 {@code when(...)} 再连续
   * {@code thenReturn}」在 Mockito 5 下只生效首个值（实测踩坑）。
   */
  private void stubSearchSequence(List<VectorSearchHit>... results) {
    org.mockito.stubbing.OngoingStubbing<List<VectorSearchHit>> stub =
        when(vectorStore.search(any())).thenReturn(results[0]);
    for (int index = 1; index < results.length; index++) {
      stub = stub.thenReturn(results[index]);
    }
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
            ENTITY_TYPE,
            "CUSTOMER_COMPANY",
            "resourceId",
            resourceId,
            "ownerUserId",
            42L));
  }

  private VectorSearchHit activityHit(String chunkId, long activityId, double score, String title) {
    return new VectorSearchHit(
        chunkId,
        "BUSINESS_ACTIVITY:" + activityId,
        score,
        "活动标题: " + title,
        Map.of(
            "title",
            title,
            ENTITY_TYPE,
            "BUSINESS_ACTIVITY",
            "resourceId",
            activityId,
            "ownerUserId",
            42L));
  }

  private VectorSearchHit opportunityHit(
      String chunkId, long opportunityId, double score, String title) {
    return new VectorSearchHit(
        chunkId,
        "SALES_OPPORTUNITY:" + opportunityId,
        score,
        "商机名称: " + title,
        Map.of(
            "title",
            title,
            ENTITY_TYPE,
            "SALES_OPPORTUNITY",
            "resourceId",
            opportunityId,
            "ownerUserId",
            42L));
  }

  private SysDeptEntity dept(Long id, Long parentId) {
    SysDeptEntity dept = new SysDeptEntity();
    dept.setId(id);
    dept.setParentId(parentId);
    return dept;
  }

  /** 无 TAGE / 无共享授权的空召回桩（避免 Mockito 对未打桩返回 null 造成 NPE）。 */
  private void stubEmptyGrants(Long userId, Long roleId) {
    when(dataScopeService.getSharedResourceIds(userId, roleId, CUSTOMER)).thenReturn(List.of());
    when(dataScopeService.getSharedResourceIds(userId, roleId, ACTIVITY)).thenReturn(List.of());
    when(dataScopeService.getSharedResourceIds(userId, roleId, OPPORTUNITY)).thenReturn(List.of());
    when(dataScopeService.getTageResourceIds(roleId, CUSTOMER)).thenReturn(List.of());
    when(dataScopeService.getTageResourceIds(roleId, ACTIVITY)).thenReturn(List.of());
    when(dataScopeService.getTageResourceIds(roleId, OPPORTUNITY)).thenReturn(List.of());
  }

  @Test
  @DisplayName("归属人可检：SELF 档三实体主 filter ownerUserId=当前用户，来源引用字段逐项对齐")
  void retrieve_selfOwnerRetrievesOwnData() {
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(42L, 3L);
    stubSearchSequence(List.of(hit("chunk-1", 301L, 0.92D, "甲公司")), List.of(), List.of());

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
    verify(vectorStore, times(3)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter())
        .containsEntry(ENTITY_TYPE, "CUSTOMER_COMPANY")
        .containsEntry("ownerUserId", 42L);
    assertThat(captor.getAllValues().get(1).filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY")
        .containsEntry("ownerUserId", 42L);
    assertThat(captor.getAllValues().get(2).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY")
        .containsEntry("ownerUserId", 42L);
  }

  @Test
  @DisplayName("SELF 跨部门同事数据检索不到：主 filter 锁定 ownerUserId=自己，他人商业活动物理不可达")
  void retrieve_selfColleagueDataNotRetrievable() {
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(43L, 3L);
    // 同事 42 的商业活动（活动 901）在同部门内且被向量库整轮返回，但权限闸主 filter 只认 ownerUserId=43，
    // 且服务侧档位复核（纵深防御）同样按 payload ownerUserId 复判 → 同事数据物理不可达
    stubSearchSequence(
        List.of(), List.of(activityHit("chunk-colleague", 901L, 0.99D, "同事的拜访")), List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "同事的拜访要点", userContext(43L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    assertThat(result.sources()).isEmpty();
    assertThat(result.context()).isEmpty();
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(3)).search(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(request -> assertThat(request.filter()).containsEntry("ownerUserId", 43L));
    // SELF 档不补 TAGE：SQL 层 SELF 无 tage，补召回会越权扩大（页面一致性锚定）
    verify(dataScopeService, never()).getTageResourceIds(any(), any());
  }

  @Test
  @DisplayName("SELF 商业活动归属人可检：命中以 BUSINESS_ACTIVITY 来源下发（documentId=活动主键、filename=活动标题）")
  void retrieve_selfOwnerRetrievesOwnActivity() {
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(42L, 3L);
    stubSearchSequence(List.of(), List.of(activityHit("act-1", 901L, 0.88D, "甲公司产品演示")), List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "甲公司演示重点", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(1);
    assertThat(result.context()).contains("【业务数据检索】").contains("[1] 活动标题: 甲公司产品演示");
    SourceReference source = result.sources().get(0);
    assertThat(source.sourceType()).isEqualTo("BUSINESS_ACTIVITY");
    assertThat(source.filename()).isEqualTo("甲公司产品演示");
    assertThat(source.documentId()).isEqualTo("901");
    assertThat(source.chunkId()).isEqualTo("act-1");
    assertThat(source.relevanceScore()).isEqualTo(0.88D);
  }

  @Test
  @DisplayName("SELF 销售商机归属人可检：命中以 SALES_OPPORTUNITY 来源下发（documentId=商机主键、filename=商机名称）")
  void retrieve_selfOwnerRetrievesOwnOpportunity() {
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(42L, 3L);
    stubSearchSequence(
        List.of(), List.of(), List.of(opportunityHit("opp-1", 1001L, 0.9D, "甲公司年度服务采购")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "甲公司年度采购", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(1);
    assertThat(result.context()).contains("【业务数据检索】").contains("[1] 商机名称: 甲公司年度服务采购");
    SourceReference source = result.sources().get(0);
    assertThat(source.sourceType()).isEqualTo("SALES_OPPORTUNITY");
    assertThat(source.filename()).isEqualTo("甲公司年度服务采购");
    assertThat(source.documentId()).isEqualTo("1001");
    assertThat(source.chunkId()).isEqualTo("opp-1");
    assertThat(source.relevanceScore()).isEqualTo(0.9D);
  }

  @Test
  @DisplayName("DEPT 上司可检下属：三实体均 ownerDeptId=本部门 filter")
  void retrieve_deptSupervisorRetrievesSubordinates() {
    stubLevel(DataScopeLevel.DEPT);
    stubEmptyGrants(10L, 3L);
    stubSearchSequence(
        List.of(hit("chunk-d", 305L, 0.8D, "丙公司")),
        List.of(activityHit("chunk-da", 905L, 0.7D, "丙公司电话沟通")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "丙公司", userContext(10L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(3)).search(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(request -> assertThat(request.filter()).containsEntry("ownerDeptId", 11L));
  }

  @Test
  @DisplayName("管理员 ALL 全可检：三实体仅 entityType filter，无 owner 维度且不查 TAGE/共享")
  void retrieve_adminAllRetrievesEverything() {
    stubLevel(DataScopeLevel.ALL);
    stubSearchSequence(
        List.of(hit("chunk-a", 301L, 0.9D, "甲公司")),
        List.of(activityHit("chunk-aa", 901L, 0.85D, "甲公司需求沟通")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery("客户", userContext(1L, 1L, 1L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources())
        .extracting(SourceReference::sourceType)
        .containsExactly("CUSTOMER", "BUSINESS_ACTIVITY");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(3)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter()).containsOnlyKeys(ENTITY_TYPE);
    assertThat(captor.getAllValues().get(1).filter())
        .containsOnlyKeys(ENTITY_TYPE)
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY");
    assertThat(captor.getAllValues().get(2).filter())
        .containsOnlyKeys(ENTITY_TYPE)
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY");
    verify(dataScopeService, never()).getTageResourceIds(any(), any());
    verify(dataScopeService, never()).getSharedResourceIds(any(), any(), any());
  }

  @Test
  @DisplayName("四角色矩阵（客户 + 商业活动 + 销售商机同轮）：归属人/本部门上级/跨部门同事/管理员逐角色断言")
  void retrieve_roleMatrixCoversFourRoles() {
    // ① 归属人（SELF）：本人条目可检，他人同部门条目物理不可达
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(42L, 3L);
    stubSearchSequence(
        List.of(hit("own", 301L, 0.9D, "甲公司")),
        List.of(activityHit("own-a", 901L, 0.8D, "本人拜访")),
        List.of(opportunityHit("own-o", 1001L, 0.75D, "本人商机")));
    BusinessRetrievalPort.BusinessRetrievalResult owner =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "我的记录", userContext(42L, 3L, 11L), 5, 1));
    assertThat(owner.hitCount()).isEqualTo(3);
    assertThat(owner.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("301", "901", "1001");

    // ② 本部门上级（DEPT）：本部门条目（含下属商机/活动）可检
    stubLevel(DataScopeLevel.DEPT);
    stubEmptyGrants(10L, 3L);
    stubSearchSequence(
        List.of(hit("sub", 302L, 0.7D, "乙公司")),
        List.of(activityHit("sub-a", 902L, 0.6D, "下属拜访")),
        List.of(opportunityHit("sub-o", 1002L, 0.55D, "下属商机")));
    BusinessRetrievalPort.BusinessRetrievalResult supervisor =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "部门记录", userContext(10L, 3L, 11L), 5, 1));
    assertThat(supervisor.hitCount()).isEqualTo(3);
    assertThat(supervisor.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("302", "902", "1002");

    // ③ 跨部门同事（SELF，deptId=99 另一部门）：filter 锁定自己 → 零命中
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(77L, 3L);
    stubSearchSequence(List.of(), List.of(), List.of());
    BusinessRetrievalPort.BusinessRetrievalResult crossDept =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "别人部门的记录", userContext(77L, 3L, 99L), 5, 1));
    assertThat(crossDept.hitCount()).isZero();
    assertThat(crossDept.sources()).isEmpty();

    // ④ 管理员（ALL）：全量可检，无 owner 维度
    stubLevel(DataScopeLevel.ALL);
    stubSearchSequence(
        List.of(hit("all", 301L, 0.5D, "甲公司")),
        List.of(activityHit("all-a", 901L, 0.4D, "全员可见拜访")),
        List.of(opportunityHit("all-o", 1001L, 0.35D, "全员可见商机")));
    BusinessRetrievalPort.BusinessRetrievalResult admin =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "全量记录", userContext(1L, 1L, 1L), 5, 1));
    assertThat(admin.hitCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("SELF 跨部门同事的销售商机绝不可查：整轮返回他人商机，owner 复核物理丢弃")
  void retrieve_selfColleagueOpportunityNotRetrievable() {
    stubLevel(DataScopeLevel.SELF);
    stubEmptyGrants(43L, 3L);
    // 同事 42 的商机（商机 1002）被向量库整轮返回，但权限闸主 filter 只认 ownerUserId=43，
    // 档位复核同样按 payload ownerUserId 复判 → 跨部门同事的商机物理不可达
    stubSearchSequence(
        List.of(), List.of(), List.of(opportunityHit("opp-colleague", 1002L, 0.99D, "同事的商机")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "同事的商机要点", userContext(43L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isZero();
    assertThat(result.sources()).isEmpty();
    assertThat(result.context()).isEmpty();
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(3)).search(captor.capture());
    assertThat(captor.getAllValues().get(2).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY")
        .containsEntry("ownerUserId", 43L);
  }

  @Test
  @DisplayName("三实体皆 NONE 级零外发：不 embed 不 search，空返回")
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
  @DisplayName("单实体 NONE：客户面/商机面无权但商业活动面有权时仍召回活动，且不发无权面检索")
  void retrieve_singleEntityNoneStillRetrievesOtherEntity() {
    stubLevels(DataScopeLevel.NONE, DataScopeLevel.SELF, DataScopeLevel.NONE);
    when(dataScopeService.getSharedResourceIds(42L, 3L, ACTIVITY)).thenReturn(List.of());
    stubSearchSequence(List.of(activityHit("act-only", 901L, 0.77D, "活动专有命中")));

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "只有活动权限", userContext(42L, 3L, 11L), 5, 1));

    assertThat(result.hitCount()).isEqualTo(1);
    assertThat(result.sources().get(0).sourceType()).isEqualTo("BUSINESS_ACTIVITY");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(1)).search(captor.capture());
    assertThat(captor.getValue().filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY")
        .containsEntry("ownerUserId", 42L);
  }

  @Test
  @DisplayName("TAGE 绑定不漏：TAGE 档在自有数据外补查标签绑定资源（逐实体逐 id filter）")
  void retrieve_tageBindingUnionNotMissed() {
    stubLevel(DataScopeLevel.TAGE);
    stubEmptyGrants(42L, 3L);
    when(dataScopeService.getTageResourceIds(3L, CUSTOMER)).thenReturn(List.of(302L));
    when(dataScopeService.getTageResourceIds(3L, ACTIVITY)).thenReturn(List.of(902L));
    stubSearchSequence(
        List.of(hit("chunk-own", 301L, 0.9D, "甲公司")),
        List.of(hit("chunk-tage", 302L, 0.7D, "乙公司")),
        List.of(activityHit("chunk-own-a", 901L, 0.6D, "自有活动")),
        List.of(activityHit("chunk-tage-a", 902L, 0.5D, "标签活动")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 10, 1));

    assertThat(result.hitCount()).isEqualTo(4);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("301", "302", "901", "902");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(5)).search(captor.capture());
    assertThat(captor.getAllValues().get(1).filter())
        .containsEntry(ENTITY_TYPE, "CUSTOMER_COMPANY")
        .containsEntry("resourceId", 302L);
    assertThat(captor.getAllValues().get(3).filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY")
        .containsEntry("resourceId", 902L);
    assertThat(captor.getAllValues().get(4).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY")
        .containsEntry("ownerUserId", 42L);
  }

  @Test
  @DisplayName("共享不漏：SELF 档补查共享资源（逐实体逐 id filter）并按相似度降序")
  void retrieve_sharedUnionNotMissed() {
    stubLevel(DataScopeLevel.SELF);
    when(dataScopeService.getSharedResourceIds(42L, 3L, CUSTOMER)).thenReturn(List.of(303L));
    when(dataScopeService.getSharedResourceIds(42L, 3L, ACTIVITY)).thenReturn(List.of(903L));
    when(dataScopeService.getSharedResourceIds(42L, 3L, OPPORTUNITY)).thenReturn(List.of());
    stubSearchSequence(
        List.of(hit("chunk-own", 301L, 0.6D, "甲公司")),
        List.of(hit("chunk-shared", 303L, 0.95D, "丙公司")),
        List.of(),
        List.of(activityHit("chunk-shared-a", 903L, 0.91D, "被共享的活动")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(42L, 3L, 11L), 10, 1));

    assertThat(result.hitCount()).isEqualTo(3);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("303", "903", "301");
  }

  @Test
  @DisplayName("DEPT_AND_CHILD：三实体按部门子树逐 deptId 检索并 union")
  void retrieve_deptAndChildSubtreeUnion() {
    stubLevel(DataScopeLevel.DEPT_AND_CHILD);
    stubEmptyGrants(10L, 3L);
    // resolver 的 LambdaQueryWrapper 参数绑定是惰性注册（getSqlSegment 前 paramNameValuePairs 为空 map，实测
    // PARAMS={}），故本桩按「子树展开轮次」作答：每轮第 1 查（根 11）→[12]、第 2 查（12）→[13]、第 3 查（13）→
    // 无子节点；三实体各展开一轮，用 1..9 次调用映射到三轮同一序列（子树递归展开含环防御语义由
    // DataScopeDepartmentResolver 自身测试保障）。
    java.util.concurrent.atomic.AtomicInteger deptQuery =
        new java.util.concurrent.atomic.AtomicInteger();
    when(sysDeptMapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              int roundCall = (deptQuery.incrementAndGet() - 1) % 3 + 1;
              if (roundCall == 1) {
                return List.of(dept(12L, 11L));
              }
              if (roundCall == 2) {
                return List.of(dept(13L, 12L));
              }
              return List.of();
            });
    stubSearchSequence(
        List.of(hit("chunk-11", 301L, 0.9D, "甲公司")),
        List.of(hit("chunk-12", 302L, 0.8D, "乙公司")),
        List.of(hit("chunk-13", 303L, 0.7D, "丙公司")),
        List.of(activityHit("chunk-11a", 901L, 0.65D, "根的拜访")),
        List.of(activityHit("chunk-12a", 902L, 0.6D, "子部门拜访")),
        List.of(activityHit("chunk-13a", 903L, 0.55D, "孙部门拜访")),
        List.of(),
        List.of(),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 10, 1));

    assertThat(result.hitCount()).isEqualTo(6);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(9)).search(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(request -> request.filter().get("ownerDeptId"))
        .containsExactly(11L, 12L, 13L, 11L, 12L, 13L, 11L, 12L, 13L);
    assertThat(captor.getAllValues().get(3).filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY");
    assertThat(captor.getAllValues().get(6).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY");
  }

  @Test
  @DisplayName("DEPT 档补查 TAGE 绑定：与页面列表 SQL 超集不变量一致，不漏标签资源")
  void retrieve_deptSupplementsTageBindings() {
    stubLevel(DataScopeLevel.DEPT);
    stubEmptyGrants(10L, 3L);
    when(dataScopeService.getTageResourceIds(3L, CUSTOMER)).thenReturn(List.of(304L));
    when(dataScopeService.getTageResourceIds(3L, ACTIVITY)).thenReturn(List.of(904L));
    stubSearchSequence(
        List.of(hit("chunk-dept", 305L, 0.8D, "丙公司")),
        List.of(hit("chunk-tage", 304L, 0.85D, "丁公司")),
        List.of(activityHit("chunk-dept-a", 905L, 0.5D, "部门活动")),
        List.of(activityHit("chunk-tage-a", 904L, 0.45D, "标签活动")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 10, 1));

    assertThat(result.hitCount()).isEqualTo(4);
    assertThat(result.sources())
        .extracting(SourceReference::documentId)
        .containsExactly("304", "305", "905", "904");
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(5)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter()).containsEntry("ownerDeptId", 11L);
    assertThat(captor.getAllValues().get(1).filter()).containsEntry("resourceId", 304L);
    assertThat(captor.getAllValues().get(3).filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY")
        .containsEntry("resourceId", 904L);
    assertThat(captor.getAllValues().get(4).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY")
        .containsEntry("ownerDeptId", 11L);
  }

  @Test
  @DisplayName("DEPT_AND_CHILD 档补查 TAGE 绑定：子树 union 之外仍 union 标签资源")
  void retrieve_deptAndChildSupplementsTageBindings() {
    stubLevel(DataScopeLevel.DEPT_AND_CHILD);
    when(sysDeptMapper.selectList(any())).thenReturn(List.of());
    stubEmptyGrants(10L, 3L);
    // TAGE 绑定按资源面路由（客户面 306；商业活动面/商机面无绑定）。注意顺序：先打通用桩、后打具体覆盖桩——Mockito
    // 同参数匹配下**最后注册的桩生效**，若先注册具体桩会被随后的通用桩覆盖而恒返空。
    when(dataScopeService.getTageResourceIds(any(), any()))
        .thenAnswer(
            invocation -> {
              String type = invocation.getArgument(1);
              return ACTIVITY.equals(type) || OPPORTUNITY.equals(type) ? List.of() : List.of(306L);
            });
    // 调用序：① 客户面部门子树（根 11）② 客户面 TAGE 补充召回（306）③ 商业活动面部门子树（根 11）④ 商机面部门子树（根 11）
    stubSearchSequence(
        List.of(hit("chunk-dept", 301L, 0.9D, "甲公司")),
        List.of(hit("chunk-tage", 306L, 0.6D, "戊公司")),
        List.of(),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(
                "客户", userContext(10L, 3L, 11L), 10, 1));

    assertThat(result.hitCount()).isEqualTo(2);
    ArgumentCaptor<VectorSearchRequest> captor = ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore, times(4)).search(captor.capture());
    assertThat(captor.getAllValues().get(0).filter())
        .containsEntry(ENTITY_TYPE, "CUSTOMER_COMPANY")
        .containsEntry("ownerDeptId", 11L);
    assertThat(captor.getAllValues().get(1).filter())
        .containsEntry(ENTITY_TYPE, "CUSTOMER_COMPANY")
        .containsEntry("resourceId", 306L);
    assertThat(captor.getAllValues().get(2).filter())
        .containsEntry(ENTITY_TYPE, "BUSINESS_ACTIVITY")
        .containsEntry("ownerDeptId", 11L);
    assertThat(captor.getAllValues().get(3).filter())
        .containsEntry(ENTITY_TYPE, "SALES_OPPORTUNITY")
        .containsEntry("ownerDeptId", 11L);
  }

  @Test
  @DisplayName("三实体混合命中按相似度统一降序，编号自 numberStart 起接续知识库片段")
  void retrieve_numberingContinuesFromNumberStart() {
    stubLevel(DataScopeLevel.ALL);
    stubSearchSequence(
        List.of(hit("c1", 301L, 0.9D, "甲公司"), hit("c2", 302L, 0.8D, "乙公司")),
        List.of(activityHit("a1", 901L, 0.85D, "甲公司拜访")),
        List.of());

    BusinessRetrievalPort.BusinessRetrievalResult result =
        service.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery("客户", userContext(1L, 1L, 1L), 5, 3));

    assertThat(result.context())
        .contains("[3] 公司名称： 甲公司")
        .contains("[4] 活动标题: 甲公司拜访")
        .contains("[5] 公司名称： 乙公司");
    assertThat(result.context()).doesNotContain("[1]").doesNotContain("[2]");
    assertThat(result.sources())
        .extracting(SourceReference::sourceType)
        .containsExactly("CUSTOMER", "BUSINESS_ACTIVITY", "CUSTOMER");
  }

  @Test
  @DisplayName("多 filter 档命中按 chunkId 去重（取高分）、相似度降序并截断 topK")
  void retrieve_dedupSortTruncate() {
    stubLevel(DataScopeLevel.TAGE);
    when(dataScopeService.getTageResourceIds(3L, CUSTOMER)).thenReturn(List.of(301L, 302L));
    when(dataScopeService.getSharedResourceIds(42L, 3L, CUSTOMER)).thenReturn(List.of(301L));
    when(dataScopeService.getTageResourceIds(3L, ACTIVITY)).thenReturn(List.of());
    when(dataScopeService.getSharedResourceIds(42L, 3L, ACTIVITY)).thenReturn(List.of());
    when(dataScopeService.getTageResourceIds(3L, OPPORTUNITY)).thenReturn(List.of());
    when(dataScopeService.getSharedResourceIds(42L, 3L, OPPORTUNITY)).thenReturn(List.of());
    stubSearchSequence(
        List.of(hit("chunk-x", 301L, 0.5D, "甲公司")),
        List.of(hit("chunk-x", 301L, 0.9D, "甲公司"), hit("chunk-y", 302L, 0.7D, "乙公司")),
        List.of(hit("chunk-x", 301L, 0.8D, "甲公司")),
        List.of(),
        List.of(),
        List.of());

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
