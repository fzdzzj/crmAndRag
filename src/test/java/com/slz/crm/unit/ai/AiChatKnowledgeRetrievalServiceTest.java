package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.ai.AiChatKnowledgeRetrievalService;
import com.slz.crm.server.ai.port.BusinessRetrievalPort;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** KB 开关解耦、零命中诚实标记、图文双路端口参数与 topK 动态配置解析验证；任务 3.2 增补业务双源合并与降级链。 */
class AiChatKnowledgeRetrievalServiceTest {

  private KnowledgeRetrievalPort retrievalPort;
  private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private BusinessRetrievalPort businessPort;
  private ObjectProvider<BusinessRetrievalPort> businessProvider;
  private AiChatKnowledgeRetrievalService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    retrievalPort = mock(KnowledgeRetrievalPort.class);
    ObjectProvider<KnowledgeRetrievalPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(retrievalPort);
    dynamicConfigProvider = mock(ObjectProvider.class);
    // 缺省：动态配置未装配 → topK 回退真相源默认 RetrievalDefaults.TOP_K（TASK-18 收敛，曾孤例 4）
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    businessPort = mock(BusinessRetrievalPort.class);
    businessProvider = mock(ObjectProvider.class);
    when(businessProvider.getIfAvailable()).thenReturn(businessPort);
    service =
        new AiChatKnowledgeRetrievalService(provider, dynamicConfigProvider, businessProvider);
  }

  @Test
  void retrieve_kbOffNeverCallsPortOrInjectsMissMarker() {
    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, new float[] {0.1F}, false);

    assertThat(outcome.context()).isNull();
    assertThat(outcome.sources()).isEmpty();
    verifyNoInteractions(retrievalPort);
  }

  @Test
  void retrieve_kbOnPassesImageVectorAndReturnsSources() {
    SourceReference source = source();
    when(retrievalPort.retrieve(any()))
        .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(source), 1));

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("发票金额", 42L, new float[] {0.1F, 0.2F}, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(retrievalPort).retrieve(captor.capture());
    assertThat(captor.getValue().imageVector()).containsExactly(0.1F, 0.2F);
    // 动态配置未装配：topK 回退真相源默认（RetrievalDefaults.TOP_K，一致性由 RetrievalParamTruthSourceTest 门禁保证）
    assertThat(captor.getValue().topK()).isEqualTo(RetrievalDefaults.TOP_K);
    assertThat(outcome.context()).contains("片段内容").contains("[编号]");
    assertThat(outcome.sources()).containsExactly(source);
  }

  /** 任务 4.1 配置生效：rag.retrieval.topK=6 时检索按 6 取数。 */
  @Test
  @DisplayName("topK 配置生效")
  void retrieve_topKShouldFollowDynamicConfig() {
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get("rag.retrieval.topK", Integer.class, RetrievalDefaults.TOP_K)).thenReturn(6);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(config);
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    service.retrieve("问题", 42L, null, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(retrievalPort).retrieve(captor.capture());
    assertThat(captor.getValue().topK()).isEqualTo(6);
  }

  /** 任务 4.1 非法回退：配置值 <1 时回退真相源默认（RetrievalDefaults.TOP_K）。 */
  @Test
  @DisplayName("topK 非法配置回退默认")
  void retrieve_invalidTopKShouldFallBackToDefault() {
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get("rag.retrieval.topK", Integer.class, RetrievalDefaults.TOP_K)).thenReturn(0);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(config);
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    service.retrieve("问题", 42L, null, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(retrievalPort).retrieve(captor.capture());
    assertThat(captor.getValue().topK()).isEqualTo(RetrievalDefaults.TOP_K);
  }

  @Test
  void retrieve_kbOnZeroHitUsesHonestMissMarker() {
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true);

    assertThat(outcome.context()).contains("未命中知识库片段");
    assertThat(outcome.context()).doesNotContain("未检索到");
    assertThat(outcome.sources()).isEmpty();
  }

  @Test
  void retrieve_portFailureDoesNotBlockMainAnswer() {
    when(retrievalPort.retrieve(any())).thenThrow(new IllegalStateException("qdrant down"));

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true);

    assertThat(outcome.context()).contains("未命中知识库片段");
    assertThat(outcome.sources()).isEmpty();
  }

  // ===== 任务 3.2：业务检索双源合并与降级链（add-customer-semantic-retrieval） =====

  private UserContext userContext() {
    return new UserContext(42L, 3L, 11L, DataScopeLevel.NONE, null);
  }

  private SourceReference businessSource() {
    return new SourceReference(
        "CUSTOMER", "vector", "甲公司", "301", "chunk-b1", 0, null, null, "客户片段", 0.75D);
  }

  @Test
  @DisplayName("业务命中合并：context 拼接、sources 追加、编号接续知识库条数")
  void retrieve_businessHitsMergeAfterKnowledgeWithContinuedNumbering() {
    SourceReference knowledgeSource = source();
    when(retrievalPort.retrieve(any()))
        .thenReturn(
            new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(knowledgeSource), 1));
    SourceReference businessSource = businessSource();
    when(businessPort.retrieve(any()))
        .thenReturn(
            new BusinessRetrievalPort.BusinessRetrievalResult(
                "【业务数据检索】……\n\n[2] 客户片段", List.of(businessSource), 1));
    UserContext context = userContext();

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("发票金额", 42L, null, true, context);

    assertThat(outcome.context()).contains("片段内容\n\n【业务数据检索】").contains("[2] 客户片段");
    assertThat(outcome.sources()).containsExactly(knowledgeSource, businessSource);
    ArgumentCaptor<BusinessRetrievalPort.BusinessRetrievalQuery> captor =
        ArgumentCaptor.forClass(BusinessRetrievalPort.BusinessRetrievalQuery.class);
    verify(businessPort).retrieve(captor.capture());
    assertThat(captor.getValue().numberStart()).isEqualTo(2);
    assertThat(captor.getValue().userContext()).isSameAs(context);
    assertThat(captor.getValue().topK()).isEqualTo(RetrievalDefaults.TOP_K);
  }

  @Test
  @DisplayName("业务端口未就绪：WARN + 空业务段降级，主答与旧路径逐字节一致")
  void retrieve_businessPortMissing_degradesToKnowledgeOnly() {
    when(retrievalPort.retrieve(any()))
        .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(source()), 1));
    when(businessProvider.getIfAvailable()).thenReturn(null);

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true, userContext());

    assertThat(outcome.context()).contains("片段内容");
    assertThat(outcome.context()).doesNotContain("【业务数据检索】");
    assertThat(outcome.sources()).hasSize(1);
    verifyNoInteractions(businessPort);
  }

  @Test
  @DisplayName("业务检索异常：WARN + 空业务段降级，主链路不断流")
  void retrieve_businessPortFailure_degradesToKnowledgeOnly() {
    when(retrievalPort.retrieve(any()))
        .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(source()), 1));
    when(businessPort.retrieve(any())).thenThrow(new IllegalStateException("qdrant crm down"));

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true, userContext());

    assertThat(outcome.context()).contains("片段内容");
    assertThat(outcome.context()).doesNotContain("【业务数据检索】");
    assertThat(outcome.sources()).hasSize(1);
  }

  @Test
  @DisplayName("KB OFF：业务检索一并跳过（跟随 useKnowledgeBase 开关）")
  void retrieve_kbOffSkipsBusinessPortEntirely() {
    service.retrieve("问题", 42L, null, false, userContext());

    verifyNoInteractions(retrievalPort);
    verifyNoInteractions(businessPort);
  }

  @Test
  @DisplayName("旧 4 参签名：userContext=null 跳过业务检索（既有调用方行为不变）")
  void retrieve_legacyFourArgPathSkipsBusiness() {
    when(retrievalPort.retrieve(any()))
        .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(source()), 1));

    service.retrieve("问题", 42L, null, true);

    verifyNoInteractions(businessPort);
  }

  @Test
  @DisplayName("业务零命中：context 与 sources 与纯知识库路径逐字节一致（不追加空段分隔）")
  void retrieve_businessEmptyResult_keepsKnowledgeContextByteIdentical() {
    when(retrievalPort.retrieve(any()))
        .thenReturn(new KnowledgeRetrievalPort.RetrievalResult("片段内容", List.of(source()), 1));
    when(businessPort.retrieve(any()))
        .thenReturn(BusinessRetrievalPort.BusinessRetrievalResult.empty());

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true, userContext());

    assertThat(outcome.context()).endsWith("片段内容");
    assertThat(outcome.context()).doesNotContain("【业务数据检索】");
    assertThat(outcome.sources()).hasSize(1);
  }

  @Test
  @DisplayName("知识库未命中 + 业务命中：miss 标记后追加业务段，来源下发业务引用")
  void retrieve_businessHitsAfterKnowledgeMiss_appendBusinessSection() {
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());
    when(businessPort.retrieve(any()))
        .thenReturn(
            new BusinessRetrievalPort.BusinessRetrievalResult(
                "【业务数据检索】……\n\n[1] 客户片段", List.of(businessSource()), 1));

    AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
        service.retrieve("问题", 42L, null, true, userContext());

    assertThat(outcome.context()).contains("未命中知识库片段").contains("【业务数据检索】");
    assertThat(outcome.sources()).hasSize(1);
    assertThat(outcome.hasSources()).isTrue();
  }

  private SourceReference source() {
    return new SourceReference(
        "pdf", "hybrid", "合同.pdf", "doc-1", "chunk-1", 3, 2, null, "承重片段", 0.87D);
  }
}
