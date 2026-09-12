package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.knowledge.retrieval.HydeQueryExpander;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HyDE 假设答案扩展器单测（enhance-query-transformation 任务 2.1/2.3）：
 * 关闭/失败/空输出/超时全路径降级为 null（调用方跳过 HyDE 路，不向调用方抛错）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HydeQueryExpanderTest {
    @Mock
    private ModelProvider modelProvider;
    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    @Mock
    private DynamicConfigService dynamicConfigService;

    private HydeQueryExpander service(Boolean enabled) {
        lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
        lenient().when(dynamicConfigService.get(eq("rag.query.hyde.enabled"), eq(Boolean.class), eq(false)))
                .thenReturn(enabled);
        return new HydeQueryExpander(modelProvider, dynamicConfigProvider);
    }

    /** 任务 2.1：启用且模型返回正文 → 返回净化后的假设答案。 */
    @Test
    void shouldReturnSanitizedHypothesisWhenEnabled() {
        when(modelProvider.chat(any(), any())).thenReturn(ModelCallResult.ofText(
                "第一行\n第二行续写", "test-model", 10L, 20L, 30L));

        assertEquals("第一行 第二行续写", service(true).hypotheticalAnswer("XR-500 售后找谁"));
    }

    /** 任务 2.3：关闭态不调用模型。 */
    @Test
    void shouldSkipLlmWhenDisabled() {
        assertNull(service(false).hypotheticalAnswer("查询"));
        verify(modelProvider, never()).chat(any(), any());
    }

    /** 任务 2.3：模型失败 / 空输出 / 占位词 → null。（doThrow/doReturn 重打桩不触发上一条桩） */
    @Test
    void shouldReturnNullOnFailureOrEmpty() {
        org.mockito.Mockito.doThrow(new IllegalStateException("限流")).when(modelProvider).chat(any(), any());
        assertNull(service(true).hypotheticalAnswer("查询"));

        org.mockito.Mockito.doReturn(ModelCallResult.ofText("", "m", null, null, null))
                .when(modelProvider).chat(any(), any());
        assertNull(service(true).hypotheticalAnswer("查询"));

        org.mockito.Mockito.doReturn(ModelCallResult.ofText("无", "m", null, null, null))
                .when(modelProvider).chat(any(), any());
        assertNull(service(true).hypotheticalAnswer("查询"));
    }

    /** 任务 2.3：等待超时 → null（覆写超时为 50ms，模型睡 300ms）。 */
    @Test
    void shouldReturnNullOnTimeout() {
        HydeQueryExpander shortTimeout = new HydeQueryExpander(modelProvider, dynamicConfigProvider) {
            @Override
            protected long resolveTimeoutMs() {
                return 50L;
            }
        };
        when(modelProvider.chat(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return ModelCallResult.ofText("慢答案", "m", null, null, null);
        });

        assertNull(shortTimeout.hypotheticalAnswer("查询"));
    }

    /** 配置中心缺席 = 关闭。 */
    @Test
    void shouldDegradeWhenConfigUnavailable() {
        when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
        HydeQueryExpander noConfig = new HydeQueryExpander(modelProvider, dynamicConfigProvider);

        assertNull(noConfig.hypotheticalAnswer("查询"));
        verify(modelProvider, never()).chat(any(), any());
    }
}
