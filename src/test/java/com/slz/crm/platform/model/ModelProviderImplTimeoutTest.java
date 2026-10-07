package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * wire-llm-call-timeout 任务 3：非流式调用路（chat/vision/embed）的超时护栏行为锁。
 *
 * <p>锁定四条语义：调用方侧背锅护栏（预算 = 配置值 + 5s 宽限，刻意落在卡 G 的 HTTP 读超时之后不抢断口）到点 快速失败（异常类型明确、底层调用线程被中断、不等待模型返回）；
 * {@code timeout-seconds=0} 保持"不限时" （慢而有限的调用照常成功）；阈值内正常调用逐字段等价； 非超时异常原样穿透（不吞不改、零重试放大）。
 *
 * <p>纯 Mockito（假 ChatModel/EmbeddingModel 挂住不返回），零真实模型调用；类级 {@code @Timeout} 兜底， 未实现超时护栏的基线上挂死用例会被
 * JUnit 击杀判红，而不是拖死整个 surefire。
 */
@Timeout(10)
class ModelProviderImplTimeoutTest {

  // ---------- chat：原生路（生产首选，卡 G 的 HTTP 层超时盖不到 Mockito 桩内的挂死） ----------

  @Test
  void chatTimeoutFailsFastAndInterruptsUnderlyingCall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    ChatModel hanging = mock(ChatModel.class);
    when(hanging.call(any(Prompt.class))).thenAnswer(hangUntilInterrupted(started, interrupted));

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(1);
    ModelProviderImpl provider = providerWith(hanging, null, props);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> provider.chat(new Prompt(new UserMessage("hi"))));
    assertThat(exception).hasMessageContaining("超时").hasCauseInstanceOf(TimeoutException.class);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertInterruptedSoon(interrupted);
  }

  @Test
  void chatTimeoutZeroMeansUnlimited() {
    ChatResponse expected = chatResponse("慢而有限", null, null, null, null);
    ChatModel slowButFinite = mock(ChatModel.class);
    when(slowButFinite.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(400L);
              return expected;
            });

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(0);
    ModelProviderImpl provider = providerWith(slowButFinite, null, props);

    ModelCallResult<String> result = provider.chat(new Prompt(new UserMessage("hi")));
    assertThat(result.content()).isEqualTo("慢而有限");
  }

  @Test
  void chatNormalCallWithinThresholdReturnsFieldEquivalentResult() {
    ChatResponse expected = chatResponse("你好，结果", "mock-chat-model", 11L, 7L, 18L);
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(expected);

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(60);
    ModelProviderImpl provider = providerWith(model, null, props);

    ModelCallResult<String> result = provider.chat(new Prompt(new UserMessage("hi")));
    assertThat(result.content()).isEqualTo("你好，结果");
    assertThat(result.vector()).isNull();
    assertThat(result.model()).isEqualTo("mock-chat-model");
    assertThat(result.promptTokens()).isEqualTo(11L);
    assertThat(result.completionTokens()).isEqualTo(7L);
    assertThat(result.totalTokens()).isEqualTo(18L);
  }

  @Test
  void chatNonTimeoutExceptionPropagatesUnwrappedWithoutRetry() {
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom-direct"));

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(60);
    ModelProviderImpl provider = providerWith(failing, null, props);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> provider.chat(new Prompt(new UserMessage("hi"))));
    assertThat(exception).hasMessage("boom-direct");
  }

  // ---------- vision：compatible 路（含带 options 的重载，其独立 call 点也必须被护栏覆盖） ----------

  @Test
  void visionTimeoutFailsFastAndInterruptsUnderlyingCall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    OpenAiChatModel hanging = mock(OpenAiChatModel.class);
    when(hanging.call(any(Prompt.class))).thenAnswer(hangUntilInterrupted(started, interrupted));

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(1);
    ModelProviderImpl provider = providerWith(null, null, props);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", hanging);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> provider.vision(new Prompt(new UserMessage("img"))));
    assertThat(exception).hasMessageContaining("超时").hasCauseInstanceOf(TimeoutException.class);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertInterruptedSoon(interrupted);
  }

  @Test
  void visionWithOptionsTimeoutFailsFast() {
    OpenAiChatModel hanging = mock(OpenAiChatModel.class);
    when(hanging.call(any(Prompt.class)))
        .thenAnswer(hangUntilInterrupted(new CountDownLatch(1), new AtomicBoolean(false)));

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(1);
    ModelProviderImpl provider = providerWith(null, null, props);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", hanging);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> provider.vision(new Prompt(new UserMessage("img")), ModelCallOptions.defaults()));
    assertThat(exception).hasMessageContaining("超时").hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  void visionNormalCallWithinThresholdReturnsFieldEquivalentResult() {
    ChatResponse expected = chatResponse("图里是猫", "vl-real", 5L, 3L, 8L);
    OpenAiChatModel compatible = mock(OpenAiChatModel.class);
    when(compatible.call(any(Prompt.class))).thenReturn(expected);

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(60);
    ModelProviderImpl provider = providerWith(null, null, props);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", compatible);

    ModelCallResult<String> result = provider.vision(new Prompt(new UserMessage("img")));
    assertThat(result.content()).isEqualTo("图里是猫");
    assertThat(result.model()).isEqualTo("vl-real");
    assertThat(result.promptTokens()).isEqualTo(5L);
    assertThat(result.completionTokens()).isEqualTo(3L);
    assertThat(result.totalTokens()).isEqualTo(8L);
  }

  // ---------- embed：DashScope 嵌入路 ----------

  @Test
  void embedTimeoutFailsFastAndInterruptsUnderlyingCall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);
    EmbeddingModel hanging = mock(EmbeddingModel.class);
    when(hanging.call(any(EmbeddingRequest.class)))
        .thenAnswer(hangUntilInterrupted(started, interrupted));

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(1);
    ModelProviderImpl provider = providerWith(null, hanging, props);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> provider.embed(embedRequest()));
    assertThat(exception).hasMessageContaining("超时").hasCauseInstanceOf(TimeoutException.class);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertInterruptedSoon(interrupted);
  }

  @Test
  void embedTimeoutZeroMeansUnlimitedAndReturnsEquivalentVector() {
    EmbeddingModel slowButFinite = mock(EmbeddingModel.class);
    when(slowButFinite.call(any(EmbeddingRequest.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(400L);
              return embeddingResponse(new float[] {0.25f, -0.5f});
            });

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(0);
    ModelProviderImpl provider = providerWith(null, slowButFinite, props);

    ModelCallResult<float[]> result = provider.embed(embedRequest());
    assertThat(result.vector()).containsExactly(0.25f, -0.5f);
    assertThat(result.model()).isEqualTo("text-embedding-v3");
    assertThat(result.promptTokens()).isNull();
    assertThat(result.completionTokens()).isNull();
    assertThat(result.totalTokens()).isNull();
  }

  @Test
  void embedNormalCallWithinThresholdReturnsFieldEquivalentResult() {
    EmbeddingResponse response = embeddingResponse(new float[] {1.0f, -2.0f, 3.0f});
    EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.call(any(EmbeddingRequest.class))).thenReturn(response);

    ModelProviderProperties props = baseProps();
    props.setTimeoutSeconds(60);
    ModelProviderImpl provider = providerWith(null, model, props);

    ModelCallResult<float[]> result = provider.embed(embedRequest());
    assertThat(result.vector()).containsExactly(1.0f, -2.0f, 3.0f);
    assertThat(result.model()).isEqualTo("text-embedding-v3");
    assertThat(result.promptTokens()).isNull();
    assertThat(result.completionTokens()).isNull();
    assertThat(result.totalTokens()).isNull();
  }

  // ---------- 桩与装配 ----------

  /** 挂住的模型桩：先标记已启动，再长睡不返回；被中断时记录标志并原样抛出。 */
  private static Answer<Object> hangUntilInterrupted(
      CountDownLatch started, AtomicBoolean interrupted) {
    return invocation -> {
      started.countDown();
      try {
        Thread.sleep(30_000L);
        return null;
      } catch (InterruptedException exception) {
        interrupted.set(true);
        throw exception;
      }
    };
  }

  /** 超时取消发生在另一线程，中断标志落位允许短暂延迟；5 秒内未见中断即失败。 */
  private static void assertInterruptedSoon(AtomicBoolean interrupted) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!interrupted.get() && System.nanoTime() < deadlineNanos) {
      Thread.sleep(10L);
    }
    assertThat(interrupted.get()).as("超时取消应中断底层调用线程").isTrue();
  }

  private static ChatResponse chatResponse(
      String content, String model, Long promptTokens, Long completionTokens, Long totalTokens) {
    ChatResponse response = mock(ChatResponse.class);
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(promptTokens == null ? null : promptTokens.intValue());
    when(usage.getCompletionTokens())
        .thenReturn(completionTokens == null ? null : completionTokens.intValue());
    when(usage.getTotalTokens()).thenReturn(totalTokens == null ? null : totalTokens.intValue());
    when(metadata.getModel()).thenReturn(model);
    when(metadata.getUsage()).thenReturn(usage);
    when(response.getResult()).thenReturn(new Generation(new AssistantMessage(content)));
    when(response.getMetadata()).thenReturn(metadata);
    return response;
  }

  private static EmbeddingResponse embeddingResponse(float[] vector) {
    EmbeddingResponse response = mock(EmbeddingResponse.class);
    Embedding embedding = mock(Embedding.class);
    when(embedding.getOutput()).thenReturn(vector);
    when(response.getResult()).thenReturn(embedding);
    when(response.getMetadata()).thenReturn(null);
    return response;
  }

  private static EmbeddingRequest embedRequest() {
    return new EmbeddingRequest(List.of("hello"), null);
  }

  private static ModelProviderProperties baseProps() {
    ModelProviderProperties props = new ModelProviderProperties();
    props.setBaseUrl("http://localhost:1");
    props.setApiKey("test-key");
    return props;
  }

  private ModelProviderImpl providerWith(
      ChatModel chatModel, EmbeddingModel embeddingModel, ModelProviderProperties props) {
    return new ModelProviderImpl(
        providerOf(chatModel), providerOf(embeddingModel), props, new MockEnvironment());
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
