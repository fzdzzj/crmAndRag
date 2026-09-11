package com.slz.crm.unit.ai;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.AiChatKnowledgeRetrievalService;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** KB 开关解耦、零命中诚实标记与图文双路端口参数验证。 */
class AiChatKnowledgeRetrievalServiceTest {

    private KnowledgeRetrievalPort retrievalPort;
    private AiChatKnowledgeRetrievalService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        retrievalPort = mock(KnowledgeRetrievalPort.class);
        ObjectProvider<KnowledgeRetrievalPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(retrievalPort);
        service = new AiChatKnowledgeRetrievalService(provider);
    }

    @Test
    void retrieve_kbOffNeverCallsPortOrInjectsMissMarker() {
        AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
                service.retrieve("问题", 42L, new float[]{0.1F}, false);

        assertThat(outcome.context()).isNull();
        assertThat(outcome.sources()).isEmpty();
        verifyNoInteractions(retrievalPort);
    }

    @Test
    void retrieve_kbOnPassesImageVectorAndReturnsSources() {
        SourceReference source = source();
        when(retrievalPort.retrieve(any())).thenReturn(new KnowledgeRetrievalPort.RetrievalResult(
                "片段内容", List.of(source), 1));

        AiChatKnowledgeRetrievalService.RetrievalOutcome outcome =
                service.retrieve("发票金额", 42L, new float[]{0.1F, 0.2F}, true);

        ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
                ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
        verify(retrievalPort).retrieve(captor.capture());
        assertThat(captor.getValue().imageVector()).containsExactly(0.1F, 0.2F);
        assertThat(outcome.context()).contains("片段内容").contains("[编号]");
        assertThat(outcome.sources()).containsExactly(source);
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
        return new SourceReference("pdf", "hybrid", "合同.pdf", "doc-1", "chunk-1",
                3, 2, null, "承重片段", 0.87D);
    }
}
