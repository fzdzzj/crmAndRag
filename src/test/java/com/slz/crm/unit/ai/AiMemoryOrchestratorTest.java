package com.slz.crm.unit.ai;

import com.slz.crm.platform.contract.BypassTaskExecutor;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.server.ai.AiMemoryOrchestrator;
import com.slz.crm.server.service.AiConversationMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 记忆旁路加工")
class AiMemoryOrchestratorTest {

    @Mock
    private AiConversationMemoryService memoryService;

    @Mock
    private BypassTaskExecutor bypassExecutor;

    @Mock
    private ModelProvider modelProvider;

    @Mock
    private TokenUsageRecorder tokenUsageRecorder;

    @Mock
    private ObjectProvider<BypassTaskExecutor> bypassExecutorProvider;

    @Mock
    private ObjectProvider<ModelProvider> modelProviderProvider;

    @Mock
    private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

    private AiMemoryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AiMemoryOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "memoryService", memoryService);
        ReflectionTestUtils.setField(orchestrator, "bypassExecutorProvider", bypassExecutorProvider);
        ReflectionTestUtils.setField(orchestrator, "modelProviderProvider", modelProviderProvider);
        ReflectionTestUtils.setField(orchestrator, "tokenUsageRecorderProvider", tokenUsageRecorderProvider);
        lenient().when(bypassExecutorProvider.getIfAvailable()).thenReturn(bypassExecutor);
        lenient().when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
        lenient().when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void roundCompleted_extractsIntentThroughBypassAndRecordsToken() {
        AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
        memory.setId(1L);
        memory.setVersion(2);
        when(memoryService.ensureMemory(9L, 42L)).thenReturn(memory);
        when(memoryService.findBySessionId(9L)).thenReturn(memory);
        when(memoryService.restoreRecentProjection(9L, 13)).thenReturn(List.of());
        when(bypassExecutor.tryExecute(any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        when(modelProvider.chat(any(Prompt.class)))
                .thenReturn(ModelCallResult.ofText("<think>推理</think>查询合同", "qwen-plus", 1L, 2L, 3L));
        when(memoryService.updateMemory(memory)).thenReturn(true);

        orchestrator.onRoundCompleted(9L, 42L, "帮我查合同", "已找到合同");

        assertThat(memory.getIntent()).isEqualTo("查询合同");
        verify(memoryService).updateMemory(memory);
        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRecorder).record(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(TokenUsageType.INTENT);
        assertThat(captor.getValue().totalTokens()).isEqualTo(3L);
    }

    @Test
    void roundCompleted_rejectedBypassDoesNotUpdateMemory() {
        when(memoryService.ensureMemory(9L, 42L)).thenReturn(new AiConversationMemoryEntity());
        when(memoryService.restoreRecentProjection(9L, 13)).thenReturn(List.of());
        when(bypassExecutor.tryExecute(any(Runnable.class))).thenReturn(false);

        orchestrator.onRoundCompleted(9L, 42L, "帮我查合同", "已找到合同");

        verify(memoryService, org.mockito.Mockito.never()).updateMemory(any());
    }

    @Test
    void topMatch_extractsDeduplicatedFactsUpToLimit() {
        AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
        memory.setId(1L);
        memory.setVersion(1);
        memory.setFacts("[\"客户A采购ERP\"]");
        when(memoryService.ensureMemory(9L, 42L)).thenReturn(memory);
        when(memoryService.findBySessionId(9L)).thenReturn(memory);
        when(memoryService.updateMemory(memory)).thenReturn(true);

        orchestrator.updateFactsFromTopMatch(9L, 42L,
                "客户A采购ERP\n合同金额120万\n交付时间为Q4\n第四行", 0.82);

        assertThat(memory.getFacts())
                .contains("客户A采购ERP")
                .contains("合同金额120万")
                .contains("交付时间为Q4");
        verify(memoryService).updateMemory(memory);
    }

    @Test
    void topMatch_belowScoreThresholdDoesNotTouchMemory() {
        orchestrator.updateFactsFromTopMatch(9L, 42L, "低相关片段", 0.10);

        verify(memoryService, org.mockito.Mockito.never()).ensureMemory(any(), any());
        verify(memoryService, org.mockito.Mockito.never()).updateMemory(any());
    }

    @Test
    void cleanupExpiredMemories_deletesMemoryOlderThanTtl() {
        orchestrator.cleanupExpiredMemories();

        verify(memoryService).deleteExpiredBefore(org.mockito.ArgumentMatchers.argThat(
                threshold -> threshold.isBefore(java.time.LocalDateTime.now())));
    }
}
