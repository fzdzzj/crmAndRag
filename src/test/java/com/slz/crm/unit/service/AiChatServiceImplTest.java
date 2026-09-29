package com.slz.crm.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiAssistantMessageStore;
import com.slz.crm.server.ai.AiChatImageService;
import com.slz.crm.server.ai.AiChatImageUnderstandingService;
import com.slz.crm.server.ai.AiChatMetrics;
import com.slz.crm.server.ai.AiChatPromptService;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import com.slz.crm.server.ai.AiChatStreamContext;
import com.slz.crm.server.ai.AiChatStreamHeartbeat;
import com.slz.crm.server.ai.AiChatStreamLifecycle;
import com.slz.crm.server.ai.AiRateLimiter;
import com.slz.crm.server.ai.AiShortQuestionRewriter;
import com.slz.crm.server.ai.AiStreamRegistry;
import com.slz.crm.server.ai.AiToolRegistry;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AiChatServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 对话服务上下文与生命周期")
class AiChatServiceImplTest {

  @Mock private ChatClient.Builder chatClientBuilder;

  @Mock private ChatClient chatClient;

  @Mock private ChatClient.ChatClientRequestSpec promptSpec;

  @Mock private ChatClient.StreamResponseSpec streamSpec;

  @Mock private AiSessionService aiSessionService;

  @Mock private AiMessageService aiMessageService;

  @Mock private PermissionService permissionService;

  @Mock private AiProperties aiProperties;

  @Mock private AiToolRegistry aiToolRegistry;

  @Mock private AiChatImageService aiChatImageService;

  @Mock private AiChatImageUnderstandingService aiChatImageUnderstandingService;

  @Mock private AiRateLimiter aiRateLimiter;

  @Mock private AiAssistantMessageStore assistantMessageStore;

  private Environment environment;

  private AiChatPromptService promptService;

  private AiChatSseEventWriter eventWriter;

  private AiChatStreamLifecycle streamLifecycle;

  /** 测试专用调度器；生命周期测试中默认关闭心跳，避免异步触发 */
  private final ScheduledExecutorService heartbeatScheduler = mock(ScheduledExecutorService.class);

  @Mock private SseEmitter emitter;

  @InjectMocks private AiChatServiceImpl service;

  @BeforeEach
  void setUp() {
    java.util.concurrent.Executor directExecutor = Runnable::run;
    ReflectionTestUtils.setField(service, "aiChatExecutor", directExecutor);
    ReflectionTestUtils.setField(service, "aiTitleExecutor", directExecutor);
    AiMessageEntity assistantPlaceholder = new AiMessageEntity();
    assistantPlaceholder.setId(9999L);
    lenient()
        .when(assistantMessageStore.createPlaceholder(anyLong()))
        .thenReturn(assistantPlaceholder);
    environment = mock(Environment.class);
    lenient()
        .when(environment.getProperty("spring.ai.chat.provider", "dashscope"))
        .thenReturn("dashscope");
    lenient()
        .when(environment.getProperty("spring.ai.chat.options.model", "qwen-plus"))
        .thenReturn("qwen-plus");
    ReflectionTestUtils.setField(service, "environment", environment);
    // CI-2 竞态教训（2026-09-26/2026-09-29 两次 CI 红，本地难复现）：aiProperties 是 mock，
    // Mockito 对未打桩的包装类型返回默认值 0 而非 null，getLlmTimeoutSeconds() 得 0 →
    // 链上 .timeout(0ms) 的定时器挂在 Schedulers.parallel() 上，与 main 线程的同步发射竞速；
    // CI 慢机器上定时器先触发 TimeoutException，错误处置链抢走 tryMarkFinished 终态 CAS，
    // interrupted 口径落库替代正常完成，严格桩与 verify 全部落空。桩到测试窗口之外，
    // 使"subscribe() 返回即终态已在本线程交付"成为不变量（配套断言见 subscribeAwaitTerminal）。
    // 用 lenient 是共享 setUp 桩的既有形态（同上述 createPlaceholder/environment 两处）：26 个用例
    // 仅 14 个走到订阅会消耗该桩，严格桩会在其余用例报 UnnecessaryStubbingException（实测 12 例）。
    lenient().when(aiProperties.getLlmTimeoutSeconds()).thenReturn(60);
    promptService = new AiChatPromptService();
    eventWriter = spy(new AiChatSseEventWriter());
    // 测试中关闭心跳，避免定时任务影响生命周期断言
    AiProperties heartbeatProperties = new AiProperties();
    heartbeatProperties.setHeartbeatEnabled(false);
    AiChatStreamHeartbeat heartbeat =
        new AiChatStreamHeartbeat(heartbeatProperties, heartbeatScheduler);
    streamLifecycle =
        new AiChatStreamLifecycle(
            chatClientBuilder,
            aiProperties,
            aiMessageService,
            new AiStreamRegistry(),
            promptService,
            eventWriter,
            heartbeat,
            // 使用独立内存 MeterRegistry，避免测试间共享指标状态
            new AiChatMetrics(new SimpleMeterRegistry(), new AiStreamRegistry()),
            assistantMessageStore,
            "qwen-plus");
    ReflectionTestUtils.setField(promptService, "aiProperties", aiProperties);
    ReflectionTestUtils.setField(promptService, "aiMessageService", aiMessageService);
    ReflectionTestUtils.setField(
        promptService, "shortQuestionRewriter", new AiShortQuestionRewriter());
    ReflectionTestUtils.setField(streamLifecycle, "chatClientBuilder", chatClientBuilder);
    ReflectionTestUtils.setField(streamLifecycle, "aiProperties", aiProperties);
    // 批D拆分后 aiMessageService/promptService/eventWriter 已随职责迁入 AiChatStreamFinalizer，
    // 且构造器已注入同一实例，无需（也不能）再反射注入生命周期字段。
    useRegistry(new AiStreamRegistry());
  }

  private void useRegistry(AiStreamRegistry registry) {
    ReflectionTestUtils.setField(service, "aiStreamRegistry", registry);
    // 测试中关闭心跳，保持替换/取消路径可同步验证
    AiProperties heartbeatProperties = new AiProperties();
    heartbeatProperties.setHeartbeatEnabled(false);
    AiChatStreamHeartbeat heartbeat =
        new AiChatStreamHeartbeat(heartbeatProperties, heartbeatScheduler);
    streamLifecycle =
        new AiChatStreamLifecycle(
            chatClientBuilder,
            aiProperties,
            aiMessageService,
            registry,
            promptService,
            eventWriter,
            heartbeat,
            // 指标注册表与当前 AiStreamRegistry 配对，便于读取活跃流 Gauge
            new AiChatMetrics(new SimpleMeterRegistry(), registry),
            assistantMessageStore,
            "qwen-plus");
    ReflectionTestUtils.setField(service, "promptService", promptService);
    ReflectionTestUtils.setField(service, "eventWriter", eventWriter);
    ReflectionTestUtils.setField(service, "streamLifecycle", streamLifecycle);
  }

  private void invokeDoStreamChat(
      RoleAO currentUser, Long sessionId, String message, SseEmitter emitter) {
    AssistantChatRequest request =
        new AssistantChatRequest(
            sessionId == null ? null : String.valueOf(sessionId),
            message,
            false,
            false,
            null,
            List.of());
    ReflectionTestUtils.invokeMethod(service, "doStreamChat", currentUser, request, emitter, null);
  }

  /**
   * 终止型源的统一订阅入口（永久锁，卡 H CI-2）：订阅后断言终态已在本线程交付。
   *
   * <p>背景：2026-09-26 / 2026-09-29 两次 CI 红、本地 7 连绿——mock 的 {@code getLlmTimeoutSeconds()} 未打桩时
   * Mockito 对包装类型返回 0，{@code .timeout(0ms)} 的定时器挂在 {@code Schedulers.parallel()} 上与 main
   * 线程的同步发射竞速，慢机器上 TimeoutException 抢走终态 CAS，流走 interrupted 落库路径，正常完成路径的桩与 verify 全部落空（09-29 失败栈：
   * MonoDelay → FluxTimeout → doOnError → handleStreamError:64 → persistInterrupted）。
   *
   * <p>本类约定：setUp 已把超时桩到测试窗口之外（60s），终止型源（Flux.just/empty/error）的 终态必然在 {@code subscribe()}
   * 返回前于调用线程交付完毕，因此返回后直接断言 {@code isFinished}。新增用例<b>禁止"裸 verify 紧跟 subscribe"</b>：终止型源一律走本方法；
   * 不终止的源（如 Flux.never）必须显式 dispose 并在用例内注明理由（参照 {@code
   * subscribe_withAnonymousUserBuildsNullSafeToolContext}）。能逮住：入口检查拦"删掉超时桩/把桩改回 0ms"（0ms
   * 定时器复活的第一道闸，确定性红）；订阅后检查拦"不等终态就断言"在慢机器上的退化（终态未交付即抛错）。逮不住：其他类 新引入的
   * 异步源，以及不走本方法的订阅点（那需要各测试类自己的终态等待）。
   */
  private void subscribeAwaitTerminal(
      AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context) {
    Integer timeoutSeconds = aiProperties.getLlmTimeoutSeconds();
    if (timeoutSeconds != null && timeoutSeconds <= 0) {
      throw new IllegalStateException(
          "超时桩失效（getLlmTimeoutSeconds()="
              + timeoutSeconds
              + "）：0ms 定时器会与同步发射竞速，终态可能不在本线程交付（CI-2 教训见方法注释），禁止在此状态下断言");
    }
    streamLifecycle.subscribe(activeStream, context);
    if (!activeStream.isFinished()) {
      throw new IllegalStateException(
          "subscribe() 返回后流仍未到达终态：同步交付不变量被破坏（CI-2 教训见方法注释），" + "禁止在此状态下直接断言");
    }
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
    when(aiRateLimiter.tryAcquire(anyLong()))
        .thenAnswer(
            invocation -> {
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
    ReflectionTestUtils.invokeMethod(
        service, "doStreamChat", currentUser, blankRequest, emitter, null);

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
    verify(assistantMessageStore)
        .complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0));
    verify(emitter).complete();
  }

  @Test
  void subscribe_afterStreamFinished_doesNotCallModel() {
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.tryMarkFinished();

    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

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
    verify(assistantMessageStore)
        .complete(eq(88L), eq("AI 服务暂时不可用"), eq("{\"fallback\":true}"), eq(0));
    verify(emitter).complete();
  }

  @Test
  void streamError_afterFinished_doesNotFallbackOrWriteTerminal() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.tryMarkFinished();
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
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
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
    registry.register(9L, activeStream);
    when(aiProperties.getFallbackModel()).thenReturn("qwen-turbo");

    streamLifecycle.handleStreamError(
        activeStream, "qwen-plus", new IllegalStateException("部分回答后失败"));

    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(9L)).isNull();
    verify(chatClientBuilder, never()).build();
    verify(assistantMessageStore)
        .complete(eq(88L), eq("部分内容"), eq("{\"interrupted\":true}"), eq(0));
    verify(emitter).complete();
  }

  @Test
  void chatChunk_sendFailure_disposesActiveStreamAndStopsTerminal() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
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
    doReturn(false)
        .when(eventWriter)
        .sendBufferedEvent(activeStream, "delta", "{\"content\":\"chunk\"}");

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    assertThat(activeStream.getPartialAnswer().toString()).isEqualTo("chunk");
    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(9L)).isNull();
    verify(subscription).dispose();
    verify(aiMessageService, never()).saveMessage(anyLong(), any(), any(), any(), any());
    verify(emitter, never()).complete();
  }

  private void invokeHandleStreamError(AiStreamRegistry.ActiveStream activeStream) {
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));

    streamLifecycle.handleStreamError(
        activeStream, "qwen-plus", new IllegalStateException("主模型失败"));
  }

  @Test
  void streamContext_fallbackInheritsSubscriptionState() {
    RoleAO currentUser = buildUser(42L);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(9L, emitter, List.of(), List.of(), 123L, currentUser));

    AiChatStreamContext fallback = activeStream.getContext().forFallback("qwen-turbo");

    assertThat(fallback.sessionId()).isEqualTo(9L);
    assertThat(fallback.emitter()).isSameAs(emitter);
    assertThat(fallback.currentUser()).isSameAs(currentUser);
    assertThat(fallback.modelOverride()).isEqualTo("qwen-turbo");
    assertThat(fallback.fallback()).isTrue();
    assertThat(fallback.roundStart()).isEqualTo(123L);
    assertThat(fallback.referenceCollector())
        .isSameAs(activeStream.getContext().referenceCollector());
  }

  @Test
  void subscribe_withAnonymousUserBuildsNullSafeToolContext() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    ToolCallback toolCallback = mock(ToolCallback.class);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(toolCallback), System.currentTimeMillis(), null));
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

    // Flux.never 不终止：订阅只为捕获 toolContext，断言后显式 dispose，不走 subscribeAwaitTerminal
    // （CI-2 教训见该方法的注释；超时桩 60s 保证定时器在测试窗口内不触发）。
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
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
    activeStream.tryMarkFinished();

    ReflectionTestUtils.invokeMethod(
        service, "generateTitleAsync", 9L, 42L, "你好", true, activeStream);

    verify(aiSessionService).updateTitle(9L, 42L, "新标题");
    verify(eventWriter, never())
        .sendEvent(
            org.mockito.ArgumentMatchers.any(SseEmitter.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void streamComplete_saveFailureStillSendsDoneAndCompletes() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
    registry.register(9L, activeStream);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(promptSpec);
    when(promptSpec.messages(anyList())).thenReturn(promptSpec);
    when(promptSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.empty());
    when(assistantMessageStore.complete(eq(88L), eq(""), any(), eq(0))).thenReturn(false);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(9L)).isNull();
    verify(eventWriter)
        .sendBufferedEvent(
            org.mockito.ArgumentMatchers.eq(activeStream),
            org.mockito.ArgumentMatchers.eq("done"),
            org.mockito.ArgumentMatchers.anyString());
    verify(emitter).complete();
  }

  @Test
  void cancel_saveFailureStillStopsAndCompletes() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.getPartialAnswer().append("部分内容");
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
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
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
    registry.register(9L, activeStream);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(promptSpec);
    when(promptSpec.messages(anyList())).thenReturn(promptSpec);
    when(promptSpec.stream()).thenReturn(streamSpec);
    when(aiProperties.getLlmTimeoutSeconds()).thenReturn(1);
    ChatResponse chatResponse = buildChatResponseWithUsage("回答", 128);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));
    when(assistantMessageStore.complete(eq(88L), eq("回答"), any(), eq(128))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    verify(assistantMessageStore).complete(eq(88L), eq("回答"), any(), eq(128));
  }

  @Test
  void streamThinking_sendsReasoningAndPersistsVisibleContentOnly() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null, true));
    registry.register(9L, activeStream);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(promptSpec);
    when(promptSpec.messages(anyList())).thenReturn(promptSpec);
    when(promptSpec.options(any())).thenReturn(promptSpec);
    when(promptSpec.stream()).thenReturn(streamSpec);
    ChatResponse thinkingResponse =
        buildChatResponseWithUsage("回答", Map.of("reasoningContent", "推理"), null);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(thinkingResponse));
    when(assistantMessageStore.complete(eq(88L), eq("回答"), any(), eq(0))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    verify(eventWriter)
        .sendBufferedEvent(activeStream, "thinking", "{\"text\":\"推理\",\"finished\":false}");
    verify(eventWriter)
        .sendBufferedEvent(activeStream, "thinking", "{\"text\":\"\",\"finished\":true}");
    verify(eventWriter).sendBufferedEvent(activeStream, "delta", "{\"content\":\"回答\"}");
    verify(assistantMessageStore).complete(eq(88L), eq("回答"), any(), eq(0));
  }

  @Test
  void streamContent_stripsThinkTagsBeforePersistAndSse() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), buildUser(42L)));
    registry.register(9L, activeStream);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(promptSpec);
    when(promptSpec.messages(anyList())).thenReturn(promptSpec);
    when(promptSpec.stream()).thenReturn(streamSpec);
    ChatResponse firstTagChunk = buildChatResponseWithUsage("<thi", null, null);
    ChatResponse secondTagChunk = buildChatResponseWithUsage("nk>推理</think>答案", null, null);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(firstTagChunk, secondTagChunk));
    // 思考标签响应无 usage（getMetadata() 未桩 → null）→ 落库 tokens=0；旧值 eq(123) 是永不匹配
    // 的死桩，会在正常完成路径触发 PotentialStubbingProblem（卡 H CI-2 顺带修）。
    when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(0))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    verify(eventWriter, never())
        .sendBufferedEvent(
            org.mockito.ArgumentMatchers.eq(activeStream),
            org.mockito.ArgumentMatchers.eq("thinking"),
            org.mockito.ArgumentMatchers.anyString());
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
    ReflectionTestUtils.setField(
        streamLifecycle, "tokenUsageRecorderProvider", tokenUsageRecorderProvider);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), buildUser(42L)));
    registry.register(9L, activeStream);
    when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(123))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

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
  void streamChat_persistsSourceCitationsFromAnswer() {
    AiStreamRegistry registry = new AiStreamRegistry();
    useRegistry(registry);
    ModelProvider modelProvider = mock(ModelProvider.class);
    TokenUsageRecorder tokenUsageRecorder = mock(TokenUsageRecorder.class);
    ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider = mock(ObjectProvider.class);
    when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
    ObjectProvider<ModelProvider> modelProviderProvider = mock(ObjectProvider.class);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    ChatResponse providerResponse = buildChatResponseWithUsage("依据[2]结论", 123);
    when(aiProperties.getLlmTimeoutSeconds()).thenReturn(1);
    when(modelProvider.streamChat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(Flux.just(providerResponse));
    ReflectionTestUtils.setField(streamLifecycle, "modelProviderProvider", modelProviderProvider);
    ReflectionTestUtils.setField(
        streamLifecycle, "tokenUsageRecorderProvider", tokenUsageRecorderProvider);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), buildUser(42L)));
    activeStream.setSources(
        List.of(
            new SourceReference(
                "pdf", "hybrid", "A.pdf", "doc-a", "chunk-a", 1, 1, null, "无关片段甲", 0.9D),
            // excerpt 需能支撑子句「依据结论」，否则 CitationAligner 会 DROP 编号
            new SourceReference(
                "pdf", "vector", "B.pdf", "doc-b", "chunk-b", 4, 2, null, "依据结论：B 方案已验证", 0.8D)));
    registry.register(9L, activeStream);
    when(assistantMessageStore.complete(eq(88L), eq("依据[2]结论"), any(), eq(123))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    org.mockito.ArgumentCaptor<String> payloadCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(assistantMessageStore)
        .complete(eq(88L), eq("依据[2]结论"), payloadCaptor.capture(), eq(123));
    assertThat(payloadCaptor.getValue())
        .contains("\"citations\":[2]")
        .contains("\"sourceType\":\"pdf\"")
        .contains("\"pageNo\":2")
        .contains("\"chunkIndex\":4");
  }

  @Test
  @SuppressWarnings("unchecked")
  void streamChat_sendsToolCallbacksThroughModelProvider() {
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
    ReflectionTestUtils.setField(
        streamLifecycle, "tokenUsageRecorderProvider", tokenUsageRecorderProvider);
    ToolCallback toolCallback = mock(ToolCallback.class);
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L,
            emitter,
            List.of(),
            List.of(toolCallback),
            System.currentTimeMillis(),
            buildUser(42L)));
    registry.register(9L, activeStream);
    when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(123))).thenReturn(true);

    subscribeAwaitTerminal(activeStream, activeStream.getContext());

    org.mockito.ArgumentCaptor<ModelCallOptions> optionsCaptor =
        org.mockito.ArgumentCaptor.forClass(ModelCallOptions.class);
    verify(modelProvider).streamChat(any(Prompt.class), optionsCaptor.capture());
    assertThat(optionsCaptor.getValue().hasTools()).isTrue();
    assertThat(optionsCaptor.getValue().toolCallbacks()).containsExactly(toolCallback);
    assertThat(optionsCaptor.getValue().toolContext()).containsEntry("sessionId", 9L);
    verify(chatClientBuilder, never()).build();
  }

  @Test
  void imageRef_invalidReferenceDoesNotStartGeneration() {
    RoleAO currentUser = buildUser(42L);
    AiSessionEntity session = new AiSessionEntity();
    session.setId(9L);
    session.setTitle("已有会话");
    when(aiSessionService.getOwnedSession(9L, 42L)).thenReturn(session);
    when(aiRateLimiter.tryAcquire(42L)).thenReturn(true);
    when(aiChatImageService.findByRef(9L, "999")).thenReturn(java.util.Optional.empty());
    AssistantChatRequest request =
        new AssistantChatRequest("9", "看图说明", false, false, "999", List.of());

    ReflectionTestUtils.invokeMethod(service, "doStreamChat", currentUser, request, emitter, null);

    verify(eventWriter).sendError(emitter, "PARAM_INVALID", "图片引用无效或无权访问");
    verify(assistantMessageStore, never())
        .createPlaceholder(org.mockito.ArgumentMatchers.anyLong());
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
    when(modelProvider.streamChat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenAnswer(
            invocation -> {
              if (attempts.incrementAndGet() == 1) {
                return Flux.error(new IOException("connection reset"));
              }
              retried.countDown();
              return Flux.just(providerResponse);
            });
    AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(9L, emitter);
    activeStream.setAssistantMessageId(88L);
    activeStream.setContext(
        AiChatStreamContext.initial(
            9L, emitter, List.of(), List.of(), System.currentTimeMillis(), null));
    registry.register(9L, activeStream);
    when(assistantMessageStore.complete(eq(88L), eq("答案"), any(), eq(0)))
        .thenAnswer(
            invocation -> {
              saved.countDown();
              return true;
            });

    // 首次订阅以连接错误同步结束、终态由 1ms 延迟的重试任务异步交付：不能用
    // subscribeAwaitTerminal（此时 isFinished 仍为 false），终态等待 = 下方 retried/saved 两个
    // CountDownLatch（CI-2 教训见该方法的注释）。
    streamLifecycle.subscribe(activeStream, activeStream.getContext());

    assertThat(retried.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(saved.await(1, TimeUnit.SECONDS)).isTrue();
    verify(modelProvider, org.mockito.Mockito.times(2))
        .streamChat(any(Prompt.class), any(ModelCallOptions.class));
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

  private ChatResponse buildChatResponseWithUsage(
      String text, Map<String, Object> metadata, Integer totalTokens) {
    ChatResponse response = mock(ChatResponse.class);
    Generation generation = mock(Generation.class);
    when(response.getResult()).thenReturn(generation);
    when(generation.getOutput())
        .thenReturn(new AssistantMessage(text, metadata == null ? Map.of() : metadata));
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
