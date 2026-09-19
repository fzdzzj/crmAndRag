package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.AiChatKnowledgeRetrievalService;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** KB 开关解耦、零命中诚实标记、图文双路端口参数与 topK 动态配置解析验证。 */
class AiChatKnowledgeRetrievalServiceTest {

  private KnowledgeRetrievalPort retrievalPort;
  private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private AiChatKnowledgeRetrievalService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    retrievalPort = mock(KnowledgeRetrievalPort.class);
    ObjectProvider<KnowledgeRetrievalPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(retrievalPort);
    dynamicConfigProvider = mock(ObjectProvider.class);
    // 缺省：动态配置未装配 → topK 回退默认 4（升级前硬编码行为）
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    service = new AiChatKnowledgeRetrievalService(provider, dynamicConfigProvider);
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
    // 动态配置未装配：topK 回退默认 4（升级前硬编码）
    assertThat(captor.getValue().topK()).isEqualTo(4);
    assertThat(outcome.context()).contains("片段内容").contains("[编号]");
    assertThat(outcome.sources()).containsExactly(source);
  }

  /** 任务 4.1 配置生效：rag.retrieval.topK=6 时检索按 6 取数。 */
  @Test
  @DisplayName("topK 配置生效")
  void retrieve_topKShouldFollowDynamicConfig() {
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get("rag.retrieval.topK", Integer.class, 4)).thenReturn(6);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(config);
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    service.retrieve("问题", 42L, null, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(retrievalPort).retrieve(captor.capture());
    assertThat(captor.getValue().topK()).isEqualTo(6);
  }

  /** 任务 4.1 非法回退：配置值 <1 时回退默认 4。 */
  @Test
  @DisplayName("topK 非法配置回退默认")
  void retrieve_invalidTopKShouldFallBackToDefault() {
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get("rag.retrieval.topK", Integer.class, 4)).thenReturn(0);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(config);
    when(retrievalPort.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    service.retrieve("问题", 42L, null, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(retrievalPort).retrieve(captor.capture());
    assertThat(captor.getValue().topK()).isEqualTo(4);
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

  private SourceReference source() {
    return new SourceReference(
        "pdf", "hybrid", "合同.pdf", "doc-1", "chunk-1", 3, 2, null, "承重片段", 0.87D);
  }
}
