package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * AI 流错误与取消处置链的「终态保证」验证（卡 I，P2）。
 *
 * <p>契约与约定来源：
 *
 * <ol>
 *   <li>任务卡 I：store 抛异常时不许丢终态帧、不许留僵尸流、不许让被丢弃的错误对测试不可见；
 *   <li>卡 H / docs/main-agent-execution.md §19.3：终止型响应源（{@code Flux.just/empty/error}） 在 {@code
 *       subscribe()} 后必须等待终态交付（{@code activeStream.isFinished()}）再执行断言， 严禁裸 verify 紧跟
 *       subscribe；不终止的源（{@code Flux.never}）必须显式 dispose 并注明理由；
 *   <li>spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md §4：SSE 终态事件名称、顺序与 payload
 *       字段级冻结；
 *   <li>测试层永久锁：接入 {@link ReactorOnErrorDroppedExtension}，确保 Reactor 抛弃的错误对测试可见。
 * </ol>
 */
@ExtendWith({MockitoExtension.class, ReactorOnErrorDroppedExtension.class})
@DisplayName("AI 流错误与取消处置链终态保证")
class AiChatStreamTerminalGuaranteeTest {

  @Mock private ChatClient.Builder chatClientBuilder;

  @Mock private ChatClient chatClient;

  @Mock private ChatClient.ChatClientRequestSpec promptSpec;

  @Mock private ChatClient.StreamResponseSpec streamSpec;

  @Mock private AiMessageService aiMessageService;

  @Mock private AiAssistantMessageStore assistantMessageStore;

  @Mock private SseEmitter emitter;

  private final AiSsePayloadWriter payloads = new AiSsePayloadWriter();

  private AiChatPromptService promptService;

  private AiChatSseEventWriter eventWriter;

  private AiStreamRegistry registry;

  private AiProperties aiProperties;

  private AiChatStreamLifecycle lifecycle;

  private Logger finalizerLogger;

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    promptService = new AiChatPromptService();
    eventWriter = spy(new AiChatSseEventWriter());
    registry = new AiStreamRegistry();
    aiProperties = new AiProperties();
    aiProperties.setHeartbeatEnabled(false);
    aiProperties.setLlmTimeoutSeconds(60);
    aiProperties.setFallbackModel("");
    aiProperties.setStaticFallbackMessage("AI 服务暂时不可用，请稍后再试");

    AiChatMetrics metrics = new AiChatMetrics(new SimpleMeterRegistry(), registry);
    AiChatStreamHeartbeat heartbeat =
        new AiChatStreamHeartbeat(aiProperties, mock(ScheduledExecutorService.class));

    lifecycle =
        new AiChatStreamLifecycle(
            chatClientBuilder,
            aiProperties,
            aiMessageService,
            registry,
            promptService,
            eventWriter,
            heartbeat,
            metrics,
            assistantMessageStore,
            "qwen-plus");

    finalizerLogger = (Logger) LoggerFactory.getLogger(AiChatStreamFinalizer.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    finalizerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    if (finalizerLogger != null && logAppender != null) {
      finalizerLogger.detachAppender(logAppender);
    }
  }

  /**
   * 终止型源的统一订阅入口（卡 H / §19.3 约定）： 订阅后必须断言终态已在本线程交付（{@code activeStream.isFinished()}），禁止裸 verify 紧跟
   * subscribe。
   */
  private void subscribeAwaitTerminal(
      AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context) {
    Integer timeoutSeconds = aiProperties.getLlmTimeoutSeconds();
    if (timeoutSeconds != null && timeoutSeconds <= 0) {
      throw new IllegalStateException(
          "超时桩失效（getLlmTimeoutSeconds()=" + timeoutSeconds + "）：必须桩到测试窗口之外（如 60s）");
    }
    lifecycle.subscribe(activeStream, context);
    if (!activeStream.isFinished()) {
      throw new AssertionError("终止型响应源在 subscribe() 返回后终态尚未交付");
    }
  }

  private AiStreamRegistry.ActiveStream createRegisteredStream(
      Long sessionId, Long messageId, SseEmitter streamEmitter) {
    AiStreamRegistry.ActiveStream activeStream =
        new AiStreamRegistry.ActiveStream(sessionId, streamEmitter);
    activeStream.setAssistantMessageId(messageId);
    activeStream.setContext(
        AiChatStreamContext.initial(
            sessionId, streamEmitter, List.of(), List.of(), System.currentTimeMillis(), null));
    registry.register(sessionId, activeStream);
    return activeStream;
  }

  private AiStreamRegistry.ActiveStream createRegisteredStream(Long sessionId, Long messageId) {
    return createRegisteredStream(sessionId, messageId, emitter);
  }

  /**
   * 档 1 / 用例 a：错误路径 persistInterrupted 抛 RuntimeException 断言：emitter 已 complete、registry.get 为
   * null、终态帧 done 仍发出、onErrorDropped 收集为空、降级 ERROR 日志已打。
   */
  @Test
  @DisplayName("用例a：错误路径 persistInterrupted 抛异常，仍发送 done 帧并清理活跃流与 emitter")
  void handleStreamError_persistInterruptedThrows_stillSendsDoneAndCleansUp() {
    Long sessionId = 9L;
    Long messageId = 88L;
    AiStreamRegistry.ActiveStream activeStream = createRegisteredStream(sessionId, messageId);
    activeStream.getPartialAnswer().append("已有部分回答");

    when(assistantMessageStore.complete(
            eq(messageId), eq("已有部分回答"), eq("{\"interrupted\":true}"), eq(0)))
        .thenThrow(new RuntimeException("数据库连接超时，落库失败"));

    lifecycle.handleStreamError(activeStream, "qwen-plus", new RuntimeException("模型连接异常断开"));

    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(sessionId)).isNull();
    String expectedDone = payloads.toDoneJson(String.valueOf(sessionId), null);
    verify(eventWriter).sendBufferedEvent(eq(activeStream), eq("done"), eq(expectedDone));
    verify(emitter).complete();
    assertThat(ReactorOnErrorDroppedExtension.getCapturedErrors()).isEmpty();

    List<ILoggingEvent> errorEvents =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("AI assistant message save exception"))
            .toList();
    assertThat(errorEvents).hasSize(1);
    assertThat(errorEvents.get(0).getFormattedMessage())
        .contains("sessionId=" + sessionId, "messageId=" + messageId);
    assertThat(errorEvents.get(0).getThrowableProxy().getClassName())
        .isEqualTo(RuntimeException.class.getName());
  }

  /**
   * 档 1 / 用例 b：空答案路径 persistFallbackMessage 抛 RuntimeException 断言：emitter 已 complete、registry.get 为
   * null、终态帧 delta+done 仍发出、onErrorDropped 收集为空、降级 ERROR 日志已打。
   */
  @Test
  @DisplayName("用例b：空答案路径 persistFallbackMessage 抛异常，仍发送 delta+done 帧并清理活跃流与 emitter")
  void handleStreamError_emptyAnswer_persistFallbackThrows_stillSendsDeltaDoneAndCleansUp() {
    Long sessionId = 10L;
    Long messageId = 89L;
    AiStreamRegistry.ActiveStream activeStream = createRegisteredStream(sessionId, messageId);

    String staticFallback = "AI 服务暂时不可用，请稍后再试";
    when(assistantMessageStore.complete(
            eq(messageId), eq(staticFallback), eq("{\"fallback\":true}"), eq(0)))
        .thenThrow(new RuntimeException("数据库死锁，落库失败"));

    lifecycle.handleStreamError(activeStream, "qwen-plus", new RuntimeException("模型零输出立即失败"));

    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(sessionId)).isNull();

    String expectedDelta = payloads.toDeltaJson(staticFallback);
    String expectedDone = payloads.toDoneJson(String.valueOf(sessionId), null);

    InOrder order = inOrder(eventWriter, emitter);
    order.verify(eventWriter).sendBufferedEvent(eq(activeStream), eq("delta"), eq(expectedDelta));
    order.verify(eventWriter).sendBufferedEvent(eq(activeStream), eq("done"), eq(expectedDone));
    order.verify(emitter).complete();
    assertThat(ReactorOnErrorDroppedExtension.getCapturedErrors()).isEmpty();

    List<ILoggingEvent> errorEvents =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("AI assistant message save exception"))
            .toList();
    assertThat(errorEvents).hasSize(1);
    assertThat(errorEvents.get(0).getFormattedMessage())
        .contains("sessionId=" + sessionId, "messageId=" + messageId);
  }

  /**
   * 档 1 / 用例 c：cancel 路径 store 抛 RuntimeException 断言：emitter 已 complete、registry.get 为 null、stopped
   * 帧仍发出、onErrorDropped 收集为空、降级 ERROR 日志已打。
   */
  @Test
  @DisplayName("用例c：cancel 路径 store 抛异常，仍发送 stopped 帧并完成 emitter 与 registry 清理")
  void cancel_persistInterruptedThrows_stillStopsEmitterAndCleansUp() {
    Long sessionId = 11L;
    Long messageId = 90L;
    AiStreamRegistry.ActiveStream activeStream = createRegisteredStream(sessionId, messageId);
    activeStream.getPartialAnswer().append("取消前部分已生成内容");

    when(assistantMessageStore.complete(
            eq(messageId), eq("取消前部分已生成内容"), eq("{\"interrupted\":true}"), eq(0)))
        .thenThrow(new RuntimeException("存储抛出异常"));

    boolean cancelled = lifecycle.cancel(activeStream, sessionId);

    assertThat(cancelled).isTrue();
    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(sessionId)).isNull();

    String expectedStopped = payloads.toStoppedJson("CANCELLED");
    verify(eventWriter).sendBufferedEvent(eq(activeStream), eq("stopped"), eq(expectedStopped));
    verify(emitter).complete();
    assertThat(ReactorOnErrorDroppedExtension.getCapturedErrors()).isEmpty();

    List<ILoggingEvent> errorEvents =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("AI assistant message save exception"))
            .toList();
    assertThat(errorEvents).hasSize(1);
    assertThat(errorEvents.get(0).getFormattedMessage())
        .contains("sessionId=" + sessionId, "messageId=" + messageId);
  }

  /**
   * 档 1 / 补充：handleStreamCancelled 路径 store 抛 RuntimeException 断言：registry.get 为
   * null，onErrorDropped 收集为空，降级 ERROR 日志已打。
   */
  @Test
  @DisplayName("用例c补充：handleStreamCancelled 路径 store 抛异常，仍清理注册表")
  void handleStreamCancelled_persistInterruptedThrows_stillCleansRegistry() {
    Long sessionId = 12L;
    Long messageId = 91L;
    AiStreamRegistry.ActiveStream activeStream = createRegisteredStream(sessionId, messageId);
    activeStream.getPartialAnswer().append("客户端主动断开前内容");

    when(assistantMessageStore.complete(
            eq(messageId), eq("客户端主动断开前内容"), eq("{\"interrupted\":true}"), eq(0)))
        .thenThrow(new RuntimeException("存储抛出异常"));

    lifecycle.cleanup(activeStream, "CLIENT_DISCONNECT");

    assertThat(activeStream.isFinished()).isTrue();
    assertThat(registry.get(sessionId)).isNull();
    assertThat(ReactorOnErrorDroppedExtension.getCapturedErrors()).isEmpty();

    List<ILoggingEvent> errorEvents =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("AI assistant message save exception"))
            .toList();
    assertThat(errorEvents).hasSize(1);
    assertThat(errorEvents.get(0).getFormattedMessage())
        .contains("sessionId=" + sessionId, "messageId=" + messageId);
  }

  /** 档 1 / 用例 d：反向对照——store 正常时行为与现状逐字等价 严格核对终态帧名称、顺序、payload，确保冻结契约（contracts-frozen.md §4）零漂移。 */
  @Test
  @DisplayName("用例d：反向对照——store 正常时三条路径的终态帧名称、顺序与 payload 与既有契约逐字等价")
  void baselineParity_storeNormal_verbatimEventNameOrderAndPayload() {
    // 1. 错误且有内容路径：仅发 done 帧，且 payload 逐字等价
    Long sessionId1 = 21L;
    Long messageId1 = 101L;
    SseEmitter emitter1 = mock(SseEmitter.class);
    AiStreamRegistry.ActiveStream stream1 =
        createRegisteredStream(sessionId1, messageId1, emitter1);
    stream1.getPartialAnswer().append("正常回答内容");
    when(assistantMessageStore.complete(
            eq(messageId1), eq("正常回答内容"), eq("{\"interrupted\":true}"), eq(0)))
        .thenReturn(true);

    lifecycle.handleStreamError(stream1, "qwen-plus", new RuntimeException("普通流错误"));

    assertThat(stream1.isFinished()).isTrue();
    assertThat(registry.get(sessionId1)).isNull();
    String expectedDone1 = payloads.toDoneJson(String.valueOf(sessionId1), null);
    verify(eventWriter).sendBufferedEvent(eq(stream1), eq("done"), eq(expectedDone1));
    verify(eventWriter, never()).sendBufferedEvent(eq(stream1), eq("delta"), any());
    verify(emitter1).complete();

    // 2. 错误且零输出路径：先发 delta 兜底文本帧，再发 done 帧，顺序与 payload 逐字等价
    Long sessionId2 = 22L;
    Long messageId2 = 102L;
    SseEmitter emitter2 = mock(SseEmitter.class);
    AiStreamRegistry.ActiveStream stream2 =
        createRegisteredStream(sessionId2, messageId2, emitter2);
    when(assistantMessageStore.complete(
            eq(messageId2), eq("AI 服务暂时不可用，请稍后再试"), eq("{\"fallback\":true}"), eq(0)))
        .thenReturn(true);

    lifecycle.handleStreamError(stream2, "qwen-plus", new RuntimeException("零输出错误"));

    assertThat(stream2.isFinished()).isTrue();
    assertThat(registry.get(sessionId2)).isNull();

    String expectedDelta2 = payloads.toDeltaJson("AI 服务暂时不可用，请稍后再试");
    String expectedDone2 = payloads.toDoneJson(String.valueOf(sessionId2), null);

    InOrder order = inOrder(eventWriter);
    order.verify(eventWriter).sendBufferedEvent(eq(stream2), eq("delta"), eq(expectedDelta2));
    order.verify(eventWriter).sendBufferedEvent(eq(stream2), eq("done"), eq(expectedDone2));
    verify(emitter2).complete();

    // 3. 取消路径：发送 stopped 帧，payload 逐字等价
    Long sessionId3 = 23L;
    Long messageId3 = 103L;
    SseEmitter emitter3 = mock(SseEmitter.class);
    AiStreamRegistry.ActiveStream stream3 =
        createRegisteredStream(sessionId3, messageId3, emitter3);
    stream3.getPartialAnswer().append("待取消内容");
    when(assistantMessageStore.complete(
            eq(messageId3), eq("待取消内容"), eq("{\"interrupted\":true}"), eq(0)))
        .thenReturn(true);

    boolean cancelled = lifecycle.cancel(stream3, sessionId3);

    assertThat(cancelled).isTrue();
    assertThat(stream3.isFinished()).isTrue();
    assertThat(registry.get(sessionId3)).isNull();

    String expectedStopped3 = payloads.toStoppedJson("CANCELLED");
    verify(eventWriter).sendBufferedEvent(eq(stream3), eq("stopped"), eq(expectedStopped3));
    verify(emitter3).complete();

    // 4. 正常流完成路径（卡 H / §19.3 终态订阅入口验证）：验证 subscribeAwaitTerminal 正常完成
    Long sessionId4 = 24L;
    Long messageId4 = 104L;
    SseEmitter emitter4 = mock(SseEmitter.class);
    AiStreamRegistry.ActiveStream stream4 =
        createRegisteredStream(sessionId4, messageId4, emitter4);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(promptSpec);
    when(promptSpec.messages(anyList())).thenReturn(promptSpec);
    when(promptSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.empty());
    when(assistantMessageStore.complete(eq(messageId4), eq(""), any(), eq(0))).thenReturn(true);

    subscribeAwaitTerminal(stream4, stream4.getContext());

    assertThat(stream4.isFinished()).isTrue();
    assertThat(registry.get(sessionId4)).isNull();
    String expectedDone4 = payloads.toDoneJson(String.valueOf(sessionId4), null);
    verify(eventWriter).sendBufferedEvent(eq(stream4), eq("done"), eq(expectedDone4));
    verify(emitter4).complete();

    // 全程无降级 ERROR，Reactor 无丢弃
    List<ILoggingEvent> errorEvents =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("AI assistant message save exception"))
            .toList();
    assertThat(errorEvents).isEmpty();
    assertThat(ReactorOnErrorDroppedExtension.getCapturedErrors()).isEmpty();
  }
}
