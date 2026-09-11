package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** SSE 心跳发送结果与活跃流清理联动验证。 */
@ExtendWith(MockitoExtension.class)
class AiChatStreamLifecycleHeartbeatTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private com.slz.crm.server.service.AiMessageService aiMessageService;

    @Mock
    private AiChatPromptService promptService;

    private AiChatSseEventWriter eventWriter;

    private AiStreamRegistry registry;

    /** 独立内存指标注册表，用于验证生命周期触发的埋点 */
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private AiChatStreamLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        eventWriter = mock(AiChatSseEventWriter.class);
        registry = new AiStreamRegistry();
        // 使用独立 SimpleMeterRegistry，避免测试间共享指标状态
        meterRegistry = new SimpleMeterRegistry();
        AiChatMetrics metrics = new AiChatMetrics(meterRegistry, registry);
        AiProperties heartbeatProperties = new AiProperties();
        heartbeatProperties.setHeartbeatEnabled(false);
        AiChatStreamHeartbeat heartbeat = new AiChatStreamHeartbeat(
                heartbeatProperties, mock(ScheduledExecutorService.class));
        AiAssistantMessageStore assistantMessageStore = mock(AiAssistantMessageStore.class);
        lifecycle = new AiChatStreamLifecycle(chatClientBuilder, new AiProperties(), aiMessageService,
                registry, promptService, eventWriter, heartbeat, metrics, assistantMessageStore, "qwen-plus");
    }

    @Test
    void successfulHeartbeat_shouldKeepStreamActive() {
        SseEmitter emitter = new SseEmitter();
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, emitter);
        registry.register(1L, stream);
        doReturn(true).when(eventWriter).sendHeartbeat(emitter);

        lifecycle.sendHeartbeat(stream);

        verify(eventWriter).sendHeartbeat(emitter);
        // 心跳成功只增加心跳计数，不改变活跃流终态
        assertThat(meterRegistry.get("ai.chat.stream.heartbeats").counter().count()).isEqualTo(1);
        assertThat(stream.isFinished()).isFalse();
        assertThat(registry.get(1L)).isSameAs(stream);
    }

    @Test
    void failedHeartbeat_shouldStopSubscriptionAndRemoveStream() {
        SseEmitter emitter = new SseEmitter();
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, emitter);
        registry.register(1L, stream);
        doReturn(false).when(eventWriter).sendHeartbeat(emitter);

        lifecycle.sendHeartbeat(stream);

        // 心跳失败仍记录一次尝试，并进入发送失败清理
        assertThat(meterRegistry.get("ai.chat.stream.heartbeats").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("ai.chat.stream.failed").counter().count()).isEqualTo(1);
        assertThat(stream.isFinished()).isTrue();
        assertThat(registry.get(1L)).isNull();
    }
}
