package com.slz.crm.unit.service;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.server.ai.AiChatPromptService;
import com.slz.crm.server.ai.AiChatStreamHeartbeat;
import com.slz.crm.server.ai.AiChatStreamContext;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import com.slz.crm.server.ai.AiChatStreamLifecycle;
import com.slz.crm.server.ai.AiChatMetrics;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.AiShortQuestionRewriter;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiRateLimiter;
import com.slz.crm.server.ai.AiStreamRegistry;
import com.slz.crm.server.ai.AiAssistantMessageStore;
import com.slz.crm.server.ai.AiToolRegistry;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.service.impl.AiChatServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.env.Environment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 对话服务上下文与生命周期")
class AiChatServiceImplTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec promptSpec;

    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    @Mock
    private AiSessionService aiSessionService;

    @Mock
    private AiMessageService aiMessageService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private AiToolRegistry aiToolRegistry;

    @Mock
    private AiRateLimiter aiRateLimiter;

    @Mock
    private AiAssistantMessageStore assistantMessageStore;

    private Environment environment;

    private AiChatPromptService promptService;

    private AiChatSseEventWriter eventWriter;

    private AiChatStreamLifecycle streamLifecycle;

    /** 测试专用调度器；生命周期测试中默认关闭心跳，避免异步触发 */
    private final ScheduledExecutorService heartbeatScheduler = mock(ScheduledExecutorService.class);

    @Mock
    private SseEmitter emitter;

    @InjectMocks
    private AiChatServiceImpl service;

    @BeforeEach
    void setUp() {
        java.util.concurrent.Executor directExecutor = Runnable::run;
        ReflectionTestUtils.setField(service, "aiChatExecutor", directExecutor);
        ReflectionTestUtils.setField(service, "aiTitleExecutor", directExecutor);
        AiMessageEntity assistantPlaceholder = new AiMessageEntity();
        assistantPlaceholder.setId(9999L);
        lenient().when(assistantMessageStore.createPlaceholder(anyLong())).thenReturn(assistantPlaceholder);
        environment = mock(Environment.class);
        lenient().when(environment.getProperty("spring.ai.chat.provider", "dashscope")).thenReturn("dashscope");
        lenient().when(environment.getProperty("spring.ai.chat.options.model", "qwen-plus")).thenReturn("qwen-plus");
        ReflectionTestUtils.setField(service, "environment", environment);
        promptService = new AiChatPromptService();
        eventWriter = spy(new AiChatSseEventWriter());
        // 测试中关闭心跳，避免定时任务影响生命周期断言
        AiProperties heartbeatProperties = new AiProperties();
        heartbeatProperties.setHeartbeatEnabled(false);
        AiChatStreamHeartbeat heartbeat = new AiChatStreamHeartbeat(heartbeatProperties, heartbeatScheduler);
        streamLifecycle = new AiChatStreamLifecycle(chatClientBuilder, aiProperties, aiMessageService,
                new AiStreamRegistry(), promptService, eventWriter, heartbeat,
                // 使用独立内存 MeterRegistry，避免测试间共享指标状态
                new AiChatMetrics(new SimpleMeterRegistry(), new AiStreamRegistry()), assistantMessageStore,
                "qwen-plus");
        ReflectionTestUtils.setField(promptService, "aiProperties", aiProperties);
        ReflectionTestUtils.setField(promptService, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(promptService, "shortQuestionRewriter", new AiShortQuestionRewriter());
        ReflectionTestUtils.setField(streamLifecycle, "chatClientBuilder", chatClientBuilder);
        ReflectionTestUtils.setField(streamLifecycle, "aiProperties", aiProperties);
        ReflectionTestUtils.setField(streamLifecycle, "aiMessageService", aiMessageService);
        ReflectionTestUtils.setField(streamLifecycle, "promptService", promptService);
        ReflectionTestUtils.setField(streamLifecycle, "eventWriter", eventWriter);
        useRegistry(new AiStreamRegistry());
    }

    private void useRegistry(AiStreamRegistry registry) {
        ReflectionTestUtils.setField(service, "aiStreamRegistry", registry);
        // 测试中关闭心跳，保持替换/取消路径可同步验证
        AiProperties heartbeatProperties = new AiProperties();
        heartbeatProperties.setHeartbeatEnabled(false);
        AiChatStreamHeartbeat heartbeat = new AiChatStreamHeartbeat(heartbeatProperties, heartbeatScheduler);
        streamLifecycle = new AiChatStreamLifecycle(chatClientBuilder, aiProperties, aiMessageService,
                registry, promptService, eventWriter, heartbeat,
                // 指标注册表与当前 AiStreamRegistry 配对，便于读取活跃流 Gauge
                new AiChatMetrics(new SimpleMeterRegistry(), registry), assistantMessageStore, "qwen-plus");
        ReflectionTestUtils.setField(service, "promptService", promptService);
        ReflectionTestUtils.setField(service, "eventWriter", eventWriter);
        ReflectionTestUtils.setField(service, "streamLifecycle", streamLifecycle);
    }

    private void invokeDoStreamChat(RoleAO currentUser, Long sessionId, String message, SseEmitter emitter) {
        AssistantChatRequest request = new AssistantChatRequest(
                sessionId == null ? null : String.valueOf(sessionId), message, false, false, null, List.of());
        ReflectionTestUtils.invokeMethod(service, "doStreamChat", currentUser, request, emitter, null);
    }

    @AfterEach
    void tearDown() {
        BaseUnit.removeCurrentId();
    }

    @ParameterizedTest
    @ValueSource(longs = {42L, 77L})
    void streamChat_propagatesAndCleansInitiatorContext(long userId) {
        RoleAO currentUser = buildUser(userId);
        BaseUnit.setCurrentRole(currentUser);
        AtomicReference<RoleAO> seenRole = new AtomicReference<>();
        AtomicReference<Long> seenId = new AtomicReference<>();
        when(aiRateLimiter.tryAcquire(anyLong())).thenAnswer(invocation -> {
            seenRole.set(BaseUnit.getCurrentRole());
            seenId.set(BaseUnit.getCurrentId());
            return false;
        });

        service.streamChat(1L, "你好", emitter);

        assertThat(seenId.get()).isEqualTo(userId);
        assertThat(seenRole.get()).isSameAs(currentUser);
        assertThat(BaseUnit.getCurrentRole()).isNull();
        verify(aiRateLimiter).tryAcquire(userId);
    }

    @Test
    void rateLimitedRequest_shortCircuitsBeforeSessionOrModel() {
        RoleAO currentUser = buildUser(42L);
        BaseUnit.setCurrentRole(currentUser);
        when(aiRateLimiter.tryAcquire(42L)).thenReturn(false);

        service.streamChat(1L, "你好", emitter);

        verify(aiSessionService, org.mockito.Mockito.never()).getOwnedSession(anyLong(), anyLong());
        verify(aiSessionService, org.mockito.Mockito.never()).createSession(anyLong(), any());
    }

    @Test
    void blankMessage_shortCircuitsBeforeRateLimitSessionOrModel() {
        RoleAO currentUser = buildUser(42L);
        BaseUnit.setCurrentRole(currentUser);

        AssistantChatRequest blankRequest = mock(AssistantChatRequest.class);
        when(blankRequest.message()).thenReturn(" ");
        ReflectionTestUtils.invokeMethod(service, "doStreamChat", currentUser, blankRequest, emitter, null);

        verify(aiRateLimiter, never()).tryAcquire(anyLong());
        verify(aiSessionService, never()).getOwnedSession(anyLong(), anyLong());
        verify(emitter).complete();
    }

    @Test
    void doStreamChat_buildsContextBeforeSavingCurrentUserMessage() {
        RoleAO currentUser = buildUser(42L);
        AiSessionEntity session = new AiSessionEntity();
        session.setId(9L);
        session.setTitle("已有会话");
        when(aiRateLimiter.tryAcquire(42L)).thenReturn(true);
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(session);
        when(permissionService.getPermissionList(3L)).thenReturn(List.of());
        when(aiToolRegistry.getPermittedToolCallbacks(currentUser)).thenReturn(List.of());

        AiChatPromptService promptSpy = org.mockito.Mockito.spy(promptService);
        ReflectionTestUtils.setField(service, "promptService", promptSpy);
        doReturn(List.of()).when(promptSpy).buildMessages(9L, "你好");
        AiMessageEntity saved = new AiMessageEntity();
        saved.setId(88L);
        when(aiMessageService.saveMessage(9L, "user", "text", "你好", null)).thenReturn(saved);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());

        invokeDoStreamChat(currentUser, 9L, "你好", emitter);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(promptSpy, aiMessageService);
        inOrder.verify(promptSpy).buildMessages(9L, "你好");
        inOrder.verify(aiMessageService).saveMessage(9L, "user", "text", "你好", null);
    }

    @Test
    void doStreamChat_withActiveStream_replacesOldWithoutImmediateDispose() {
        RoleAO currentUser = buildUser(42L);
        BaseUnit.setCurrentRole(currentUser);
        AiSessionEntity session = new AiSessionEntity();
        session.setId(9L);
        session.setTitle("已有会话");
        AiStreamRegistry registry = new AiStreamRegistry();
        AiStreamRegistry.ActiveStream oldStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        registry.register(9L, oldStream);
        useRegistry(registry);

        when(aiRateLimiter.tryAcquire(42L)).thenReturn(true);
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(session);
        when(permissionService.getPermissionList(3L)).thenReturn(List.of());
        when(aiToolRegistry.getPermittedToolCallbacks(currentUser)).thenReturn(List.of());
        AiChatPromptService promptSpy = org.mockito.Mockito.spy(promptService);
        ReflectionTestUtils.setField(service, "promptService", promptSpy);
        doReturn(List.of()).when(promptSpy).buildMessages(9L, "补充说明");
        AiMessageEntity savedUser = new AiMessageEntity();
        savedUser.setId(89L);
        when(aiMessageService.saveMessage(9L, "user", "text", "补充说明", null)).thenReturn(savedUser);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());

        invokeDoStreamChat(currentUser, 9L, "补充说明", emitter);

        assertThat(oldStream.isFinished()).isFalse();
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(promptSpy);
        inOrder.verify(promptSpy).buildMessages(9L, "补充说明");
    }

    @Test
    void doStreamChat_withActiveStreamWithoutPartial_keepsOldForCooperativeAbort() {
        RoleAO currentUser = buildUser(42L);
        BaseUnit.setCurrentRole(currentUser);
        AiSessionEntity session = new AiSessionEntity();
        session.setId(9L);
        session.setTitle("已有会话");
        AiStreamRegistry registry = new AiStreamRegistry();
        AiStreamRegistry.ActiveStream oldStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        registry.register(9L, oldStream);
        useRegistry(registry);

        when(aiRateLimiter.tryAcquire(42L)).thenReturn(true);
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(session);
        when(permissionService.getPermissionList(3L)).thenReturn(List.of());
        when(aiToolRegistry.getPermittedToolCallbacks(currentUser)).thenReturn(List.of());
        AiChatPromptService promptSpy = org.mockito.Mockito.spy(promptService);
        ReflectionTestUtils.setField(service, "promptService", promptSpy);
        doReturn(List.of()).when(promptSpy).buildMessages(9L, "补充说明");
        AiMessageEntity savedUser = new AiMessageEntity();
        savedUser.setId(89L);
        when(aiMessageService.saveMessage(9L, "user", "text", "补充说明", null)).thenReturn(savedUser);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());

        invokeDoStreamChat(currentUser, 9L, "补充说明", emitter);

        assertThat(oldStream.isFinished()).isFalse();
    }

    @Test
    void cancelStream_savesPartialAnswerOnce() {
        AiSessionEntity session = new AiSessionEntity();
        session.setId(9L);
        when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(session);
        AiStreamRegistry registry = new AiStreamRegistry();
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        registry.register(9L, activeStream);
        activeStream.getPartialAnswer().append("部分内容");
        registry.register(9L, activeStream);
        ReflectionTestUtils.setField(service, "aiStreamRegistry", registry);
        useRegistry(registry);
        when(assistantMessageStore.complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0)))
                .thenReturn(true);

        boolean cancelled = service.cancelStream(9L, 42L);

        assertThat(cancelled).isTrue();
        assertThat(activeStream.isFinished()).isTrue();
        verify(assistantMessageStore).complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0));
        verify(emitter).complete();
    }

    @Test
    void subscribe_afterStreamFinished_doesNotCallModel() {
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.tryMarkFinished();

        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        verify(chatClientBuilder, never()).build();
    }

    @Test
    void streamError_usesFallbackOnlyOnceThenSavesStaticFallback() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new IllegalStateException("主模型失败")));
        when(aiProperties.getFallbackModel()).thenReturn("qwen-turbo");
        when(aiProperties.getLlmTimeoutSeconds()).thenReturn(1);
        when(aiProperties.getStaticFallbackMessage()).thenReturn("AI 服务暂时不可用");
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        registry.register(9L, activeStream);

        invokeHandleStreamError(activeStream);

        assertThat(activeStream.isFinished()).isTrue();
        verify(chatClientBuilder).build();
        verify(assistantMessageStore).complete(eq(88L), eq("AI 服务暂时不可用"), eq("{\"fallback\":true}"), eq(0));
        verify(emitter).complete();
    }

    @Test
    void streamError_afterFinished_doesNotFallbackOrWriteTerminal() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.tryMarkFinished();
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(aiProperties.getFallbackModel()).thenReturn("qwen-turbo");

        streamLifecycle.handleStreamError(activeStream, "qwen-plus", new IllegalStateException("迟到错误"));

        assertThat(activeStream.isFinished()).isTrue();
        assertThat(registry.get(9L)).isNull();
        verify(chatClientBuilder, never()).build();
        verify(aiMessageService, never()).saveMessage(anyLong(), any(), any(), any(), any());
        verify(emitter, never()).complete();
    }

    @Test
    void streamError_withPartialAnswer_terminatesWithoutFallback() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.getPartialAnswer().append("部分内容");
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(aiProperties.getFallbackModel()).thenReturn("qwen-turbo");

        streamLifecycle.handleStreamError(activeStream, "qwen-plus", new IllegalStateException("部分回答后失败"));

        assertThat(activeStream.isFinished()).isTrue();
        assertThat(registry.get(9L)).isNull();
        verify(chatClientBuilder, never()).build();
        verify(assistantMessageStore).complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0));
        verify(emitter).complete();
    }

    @Test
    void chatChunk_sendFailure_disposesActiveStreamAndStopsTerminal() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        Disposable subscription = mock(Disposable.class);
        activeStream.setSubscription(subscription);
        ChatResponse response = mock(ChatResponse.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(response));
        when(response.getResult().getOutput().getText()).thenReturn("chunk");
        doReturn(false).when(eventWriter).sendBufferedEvent(activeStream, "delta", "{\"content\":\"chunk\"}");

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        assertThat(activeStream.getPartialAnswer().toString()).isEqualTo("chunk");
        assertThat(activeStream.isFinished()).isTrue();
        assertThat(registry.get(9L)).isNull();
        verify(subscription).dispose();
        verify(aiMessageService, never()).saveMessage(anyLong(), any(), any(), any(), any());
        verify(emitter, never()).complete();
    }

    private void invokeHandleStreamError(AiStreamRegistry.ActiveStream activeStream) {
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));

        streamLifecycle.handleStreamError(activeStream, "qwen-plus", new IllegalStateException("主模型失败"));
    }

    @Test
    void streamContext_fallbackInheritsSubscriptionState() {
        RoleAO currentUser = buildUser(42L);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                123L, currentUser));

        AiChatStreamContext fallback = activeStream.getContext().forFallback("qwen-turbo");

        assertThat(fallback.sessionId()).isEqualTo(9L);
        assertThat(fallback.emitter()).isSameAs(emitter);
        assertThat(fallback.currentUser()).isSameAs(currentUser);
        assertThat(fallback.modelOverride()).isEqualTo("qwen-turbo");
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.roundStart()).isEqualTo(123L);
        assertThat(fallback.referenceCollector()).isSameAs(activeStream.getContext().referenceCollector());
    }

    @Test
    void subscribe_withAnonymousUserBuildsNullSafeToolContext() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        ToolCallback toolCallback = mock(ToolCallback.class);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(toolCallback),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.toolCallbacks(anyList())).thenReturn(promptSpec);
        org.mockito.ArgumentCaptor<Map<String, Object>> toolContextCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        when(promptSpec.toolContext(toolContextCaptor.capture())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.never());

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        Map<String, Object> toolContext = toolContextCaptor.getValue();
        assertThat(toolContext.get("sessionId")).isEqualTo(9L);
        assertThat(toolContext.get("user")).isNull();
        assertThat(toolContext.get("repairCounter")).isNotNull();
        assertThat(toolContext.get("references")).isNotNull();
        activeStream.getSubscription().dispose();
    }

    @Test
    void titleGeneration_skipsEventAfterStreamFinished() {
        AiChatPromptService promptSpy = org.mockito.Mockito.spy(promptService);
        ReflectionTestUtils.setField(service, "promptService", promptSpy);
        doReturn("新标题").when(promptSpy).generateTitle(chatClientBuilder, "你好");
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        activeStream.tryMarkFinished();

        ReflectionTestUtils.invokeMethod(service, "generateTitleAsync", 9L, 42L, "你好", true, activeStream);

        verify(aiSessionService).updateTitle(9L, 42L, "新标题");
        verify(eventWriter, never()).sendEvent(org.mockito.ArgumentMatchers.any(SseEmitter.class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void streamComplete_saveFailureStillSendsDoneAndCompletes() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.empty());
        when(assistantMessageStore.complete(eq(88L), eq(""), any(), eq(0))).thenReturn(false);

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        assertThat(activeStream.isFinished()).isTrue();
        assertThat(registry.get(9L)).isNull();
        verify(eventWriter).sendBufferedEvent(org.mockito.ArgumentMatchers.eq(activeStream),
                org.mockito.ArgumentMatchers.eq("done"), org.mockito.ArgumentMatchers.anyString());
        verify(emitter).complete();
    }

    @Test
    void cancel_saveFailureStillStopsAndCompletes() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.getPartialAnswer().append("部分内容");
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(assistantMessageStore.complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0)))
                .thenReturn(false);

        boolean cancelled = streamLifecycle.cancel(activeStream, 9L);

        assertThat(cancelled).isTrue();
        assertThat(activeStream.isFinished()).isTrue();
        assertThat(registry.get(9L)).isNull();
        verify(eventWriter).sendBufferedEvent(activeStream, "stopped", "{\"reason\":\"CANCELLED\"}");
        verify(emitter).complete();
    }

    @Test
    void streamComplete_persistsUsageTokenCount() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        when(aiProperties.getLlmTimeoutSeconds()).thenReturn(1);
        ChatResponse chatResponse = buildChatResponseWithUsage("回答", 128);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));
        when(assistantMessageStore.complete(eq(88L), eq("回答"), any(), eq(128))).thenReturn(true);

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        verify(assistantMessageStore).complete(eq(88L), eq("回答"), any(), eq(128));
    }

    @Test
    void streamThinking_sendsReasoningAndPersistsVisibleContentOnly() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null, true));
        registry.register(9L, activeStream);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        ChatResponse thinkingResponse = buildChatResponseWithUsage(
                "回答", Map.of("reasoningContent", "推理"), null);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(
                thinkingResponse));
        when(assistantMessageStore.complete(eq(88L), eq("回答"), any(), eq(0))).thenReturn(true);

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        verify(eventWriter).sendBufferedEvent(activeStream, "thinking", "{\"text\":\"推理\",\"finished\":false}");
        verify(eventWriter).sendBufferedEvent(activeStream, "thinking", "{\"text\":\"\",\"finished\":true}");
        verify(eventWriter).sendBufferedEvent(activeStream, "delta", "{\"content\":\"回答\"}");
        verify(assistantMessageStore).complete(eq(88L), eq("回答"), any(), eq(0));
    }

    @Test
    void streamContent_stripsThinkTagsBeforePersistAndSse() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), buildUser(42L)));
        registry.register(9L, activeStream);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.messages(anyList())).thenReturn(promptSpec);
        when(promptSpec.stream()).thenReturn(streamSpec);
        ChatResponse firstTagChunk = buildChatResponseWithUsage("<thi", null, null);
        ChatResponse secondTagChunk = buildChatResponseWithUsage("nk>推理</think>答案", null, null);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(
                firstTagChunk, secondTagChunk));
        when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(123))).thenReturn(true);

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        verify(eventWriter, never()).sendBufferedEvent(org.mockito.ArgumentMatchers.eq(activeStream),
                org.mockito.ArgumentMatchers.eq("thinking"), org.mockito.ArgumentMatchers.anyString());
        verify(eventWriter).sendBufferedEvent(activeStream, "delta", "{\"content\":\"答案\"}");
        verify(assistantMessageStore).complete(eq(88L), eq("答案"), any(), eq(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_prefersInjectedModelProviderAndAvoidsChatClient() {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        ModelProvider modelProvider = mock(ModelProvider.class);
        TokenUsageRecorder tokenUsageRecorder = mock(TokenUsageRecorder.class);
        ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider = mock(ObjectProvider.class);
        when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
        ObjectProvider<ModelProvider> modelProviderProvider = mock(ObjectProvider.class);
        when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
        ChatResponse providerResponse = buildChatResponseWithUsage("答案", 123);
        when(modelProvider.streamChat(any(Prompt.class), any(ModelCallOptions.class)))
                .thenReturn(Flux.just(providerResponse));
        ReflectionTestUtils.setField(streamLifecycle, "modelProviderProvider", modelProviderProvider);
        ReflectionTestUtils.setField(streamLifecycle, "tokenUsageRecorderProvider", tokenUsageRecorderProvider);
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), buildUser(42L)));
        registry.register(9L, activeStream);
        when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(123))).thenReturn(true);

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        verify(modelProvider).streamChat(any(Prompt.class), any(ModelCallOptions.class));
        verify(chatClientBuilder, never()).build();
        org.mockito.ArgumentCaptor<TokenUsageRecord> usageCaptor =
                org.mockito.ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRecorder).record(usageCaptor.capture());
        assertThat(usageCaptor.getValue().sessionId()).isEqualTo("9");
        assertThat(usageCaptor.getValue().userIdRef()).isEqualTo("user:42");
        verify(assistantMessageStore).complete(eq(88L), eq("答案"), any(), eq(123));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroOutputConnectionError_retriesSameModelThenCompletes() throws Exception {
        AiStreamRegistry registry = new AiStreamRegistry();
        useRegistry(registry);
        ModelProvider modelProvider = mock(ModelProvider.class);
        ObjectProvider<ModelProvider> modelProviderProvider = mock(ObjectProvider.class);
        when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
        ReflectionTestUtils.setField(streamLifecycle, "modelProviderProvider", modelProviderProvider);
        when(aiProperties.getLlmTimeoutSeconds()).thenReturn(1);
        when(aiProperties.getConnectionRetryBaseDelayMillis()).thenReturn(1L);
        ChatResponse providerResponse = buildChatResponseWithUsage("答案", null, null);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch retried = new CountDownLatch(1);
        CountDownLatch saved = new CountDownLatch(1);
        when(modelProvider.streamChat(any(Prompt.class), any(ModelCallOptions.class))).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new IOException("connection reset"));
            }
            retried.countDown();
            return Flux.just(providerResponse);
        });
        AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
        activeStream.setAssistantMessageId(88L);
        activeStream.setContext(AiChatStreamContext.initial(9L, emitter, List.of(), List.of(),
                System.currentTimeMillis(), null));
        registry.register(9L, activeStream);
        when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(0))).thenAnswer(invocation -> {
            saved.countDown();
            return true;
        });

        streamLifecycle.subscribe(activeStream, activeStream.getContext());

        assertThat(retried.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(saved.await(1, TimeUnit.SECONDS)).isTrue();
        verify(modelProvider, org.mockito.Mockito.times(2)).streamChat(any(Prompt.class), any(ModelCallOptions.class));
        assertThat(activeStream.getContext().connectionRetry()).isEqualTo(1);
        verify(assistantMessageStore).complete(eq(88L), eq("答案"), any(), eq(0));
    }

    private RoleAO buildUser(Long id) {
        RoleAO user = new RoleAO();
        user.setId(id);
        user.setRoleId(3L);
        return user;
    }

    private ChatResponse buildChatResponseWithUsage(String text, Integer totalTokens) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text));
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        return response;
    }

    private ChatResponse buildChatResponseWithUsage(String text, Map<String, Object> metadata, Integer totalTokens) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text, metadata == null ? Map.of() : metadata));
        if (totalTokens != null) {
            ChatResponseMetadata responseMetadata = mock(ChatResponseMetadata.class);
            Usage usage = mock(Usage.class);
            when(response.getMetadata()).thenReturn(responseMetadata);
            when(responseMetadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(totalTokens);
        }
        return response;
    }
}
