package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * wire-dependency-circuit-breaker 任务 3：模型门面接入依赖熔断执行器的行为与契约锁。
 *
 * <p>锁住四条红线：① 连续失败只累计计数，达阈值后 OPEN 快速拒绝——物理外呼次数不多于失败请求数，零自动重试放大；② 依赖名按调用面隔离（chat / embed / vision
 * 各一路），一路开闸不影响其他路；③ 门面抛出类型仍是既有 {@link IllegalStateException}， {@link
 * DependencyUnavailableException} 既不出现在签名上也不出现在异常链里；④ 流式 {@code streamChat} 属不接线面，不受熔断影响。
 *
 * <p>纯 Mockito 桩模型，零真实模型调用（{@code DASHSCOPE_API_KEY} 置空）。红测试先行：本文件的计数用例在未接线的 af99e41
 * 主代码基线上实跑为红（桩被调用 6 次而非 5 次）。
 */
@Timeout(20)
class ModelProviderCircuitBreakerTest {

  /** 执行器骨架默认连续失败阈值（本变更不新增配置键，骨架默认值即契约）。 */
  private static final int FAILURE_THRESHOLD = 5;

  private static final String CHAT_DEPENDENCY = "model-chat";
  private static final String EMBED_DEPENDENCY = "model-embed";
  private static final String VISION_DEPENDENCY = "model-vision";

  @Test
  void chatFailuresOpenCircuitAndFastRejectWithoutExtraPhysicalCalls() {
    AtomicInteger physicalCalls = new AtomicInteger();
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenAnswer(throwAfterCount(physicalCalls, "model-down"));
    ModelProviderImpl provider = providerWith(failing, null);

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(catchThrowable(() -> provider.chat(new Prompt(new UserMessage("hi")))));
    }

    assertThat(physicalCalls.get())
        .as("chat 路连续失败达阈值后必须 OPEN 快速拒绝，不得继续物理外呼")
        .isEqualTo(FAILURE_THRESHOLD);
    assertFacadeExceptionsKeepContract(thrown);
  }

  @Test
  void embedFailuresOpenOwnCircuitWithoutBlockingChatDependency() {
    AtomicInteger physicalCalls = new AtomicInteger();
    EmbeddingModel failingEmbed = mock(EmbeddingModel.class);
    when(failingEmbed.call(any(EmbeddingRequest.class)))
        .thenAnswer(throwAfterCount(physicalCalls, "embed-down"));
    ChatModel healthyChat = mock(ChatModel.class);
    when(healthyChat.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
    ModelProviderImpl provider = providerWith(healthyChat, failingEmbed);

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(catchThrowable(() -> provider.embed(embedRequest())));
    }

    assertThat(physicalCalls.get()).as("embed 路连续失败达阈值后必须 OPEN 快速拒绝").isEqualTo(FAILURE_THRESHOLD);
    assertFacadeExceptionsKeepContract(thrown);

    assertThat(provider.chat(new Prompt(new UserMessage("hi"))))
        .as("model-embed 开闸不得影响 model-chat 依赖")
        .isNotNull();
  }

  @Test
  void visionFailuresOpenCircuitAndFastRejectWithoutExtraPhysicalCalls() {
    AtomicInteger physicalCalls = new AtomicInteger();
    OpenAiChatModel failing = mock(OpenAiChatModel.class);
    when(failing.call(any(Prompt.class))).thenAnswer(throwAfterCount(physicalCalls, "vision-down"));
    ModelProviderImpl provider = providerWith(null, null);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", failing);

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(catchThrowable(() -> provider.vision(new Prompt(new UserMessage("img")))));
    }

    assertThat(physicalCalls.get()).as("vision 路连续失败达阈值后必须 OPEN 快速拒绝").isEqualTo(FAILURE_THRESHOLD);
    assertFacadeExceptionsKeepContract(thrown);
  }

  @Test
  void visionWithOptionsSharesVisionDependencyAndFastRejects() {
    AtomicInteger physicalCalls = new AtomicInteger();
    OpenAiChatModel failing = mock(OpenAiChatModel.class);
    when(failing.call(any(Prompt.class))).thenAnswer(throwAfterCount(physicalCalls, "vision-down"));
    ModelProviderImpl provider = providerWith(null, null);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", failing);

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(
          catchThrowable(
              () ->
                  provider.vision(
                      new Prompt(new UserMessage("img")), ModelCallOptions.defaults())));
    }

    assertThat(physicalCalls.get())
        .as("vision(options) 重载的独立 call 点同样计入 model-vision，达阈值后快速拒绝")
        .isEqualTo(FAILURE_THRESHOLD);
    assertFacadeExceptionsKeepContract(thrown);
  }

  @Test
  void streamChatStaysOutsideCircuitBreakerAfterChatDependencyOpens() {
    AtomicInteger physicalCalls = new AtomicInteger();
    ChatModel failingNative = mock(ChatModel.class);
    when(failingNative.call(any(Prompt.class)))
        .thenAnswer(throwAfterCount(physicalCalls, "model-down"));
    OpenAiChatModel compatible = mock(OpenAiChatModel.class);
    when(compatible.stream(any(Prompt.class))).thenReturn(Flux.just(mock(ChatResponse.class)));
    ModelProviderImpl provider = providerWith(failingNative, null);
    ReflectionTestUtils.setField(provider, "compatibleChatModel", compatible);

    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      catchThrowable(() -> provider.chat(new Prompt(new UserMessage("hi"))));
    }

    List<ChatResponse> emitted =
        provider
            .streamChat(new Prompt(new UserMessage("hi")))
            .collectList()
            .block(Duration.ofSeconds(5));

    assertThat(emitted).as("流式路属不接线面，chat 依赖开闸后仍须照常出流").hasSize(1);
  }

  @Test
  void failingChatThroughInjectedGuardIncrementsDependencyFailureCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicInteger physicalCalls = new AtomicInteger();
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenAnswer(throwAfterCount(physicalCalls, "model-down"));
    ModelProviderImpl provider =
        providerWith(new ModelCallGuard(new DependencyResilienceExecutor(registry)), failing, null);

    assertThat(catchThrowable(() -> provider.chat(new Prompt(new UserMessage("hi")))))
        .as("门面抛出类型保持既有 IllegalStateException")
        .isInstanceOf(IllegalStateException.class);

    assertThat(physicalCalls.get()).as("一次请求一次物理外呼").isEqualTo(1);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", CHAT_DEPENDENCY)
                .tag("result", "failure")
                .counter()
                .count())
        .as("失败调用必须计入 model-chat 的 dependency.call{{result=failure}}")
        .isEqualTo(1.0);
    assertThat(registry.find("dependency.retry").tag("dependency", CHAT_DEPENDENCY).counter())
        .as("熔断层不得引入任何自动重试")
        .isNull();
  }

  @Test
  void openCircuitRejectionCountsRejectedAndKeepsFacadeExceptionType() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicInteger physicalCalls = new AtomicInteger();
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenAnswer(throwAfterCount(physicalCalls, "model-down"));
    ModelProviderImpl provider =
        providerWith(new ModelCallGuard(new DependencyResilienceExecutor(registry)), failing, null);

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(catchThrowable(() -> provider.chat(new Prompt(new UserMessage("hi")))));
    }

    assertThat(physicalCalls.get()).isEqualTo(FAILURE_THRESHOLD);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", CHAT_DEPENDENCY)
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(FAILURE_THRESHOLD);
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", CHAT_DEPENDENCY)
                .counter()
                .count())
        .as("达阈值开闸一次")
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("dependency.circuit.rejected")
                .tag("dependency", CHAT_DEPENDENCY)
                .counter()
                .count())
        .as("OPEN 后的第 6 次请求被快速拒绝并计数")
        .isEqualTo(1.0);
    assertFacadeExceptionsKeepContract(thrown);
    assertThat(thrown.get(FAILURE_THRESHOLD))
        .as("熔断拒绝的 cause 必须是 CircuitOpenException 而非 DependencyUnavailableException")
        .hasCauseInstanceOf(DependencyUnavailableException.CircuitOpenException.class);
  }

  @Test
  void modelCallSitesStayWiredToDependencyCircuitBreaker() throws IOException {
    String code =
        codeOnly("com/slz/crm/platform/model/ModelProviderImpl.java")
            + codeOnly("com/slz/crm/platform/model/ModelCallGuard.java");

    assertThat(count(code, "\"" + CHAT_DEPENDENCY + "\""))
        .as("chat 接线点必须以依赖名 %s 计入熔断器", CHAT_DEPENDENCY)
        .isGreaterThanOrEqualTo(1);
    assertThat(count(code, "\"" + EMBED_DEPENDENCY + "\""))
        .as("embed 接线点必须以依赖名 %s 计入熔断器", EMBED_DEPENDENCY)
        .isGreaterThanOrEqualTo(1);
    assertThat(count(code, "\"" + VISION_DEPENDENCY + "\""))
        .as("vision 两处 call 点（含 options 重载）必须以依赖名 %s 计入熔断器", VISION_DEPENDENCY)
        .isGreaterThanOrEqualTo(2);
    assertThat(count(code, "executeNoRetry"))
        .as("模型门面必须经 executeNoRetry 单接熔断器（零自动重试）")
        .isGreaterThanOrEqualTo(1);
  }

  // ---------- 断言与桩 ----------

  /** 门面异常契约：类型仍是 IllegalStateException，且异常链里不得出现 DependencyUnavailableException。 */
  private static void assertFacadeExceptionsKeepContract(List<Throwable> thrown) {
    for (Throwable throwable : thrown) {
      assertThat(throwable)
          .as("门面抛出类型保持既有 IllegalStateException")
          .isInstanceOf(IllegalStateException.class);
      Throwable cursor = throwable;
      while (cursor != null) {
        assertThat(cursor)
            .as("DependencyUnavailableException 不得泄漏到门面异常链")
            .isNotInstanceOf(DependencyUnavailableException.class);
        cursor = cursor.getCause();
      }
    }
  }

  /** 计数并抛出的模型桩：物理外呼次数即“零自动重试”的直接观测量。 */
  private static Answer<Object> throwAfterCount(AtomicInteger counter, String message) {
    return invocation -> {
      counter.incrementAndGet();
      throw new IllegalStateException(message);
    };
  }

  private static EmbeddingRequest embedRequest() {
    return new EmbeddingRequest(List.of("hello"), null);
  }

  /** 剥掉块注释与行注释后的主源码文本；文件不存在返回空串（协作者类尚未拆分时）。 */
  private static String codeOnly(String relativePathFromMainJava) throws IOException {
    Path path = Paths.get("src/main/java", relativePathFromMainJava);
    if (!Files.exists(path)) {
      return "";
    }
    String source = Files.readString(path, StandardCharsets.UTF_8);
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
  }

  private static int count(String haystack, String needle) {
    int result = 0;
    for (int index = haystack.indexOf(needle);
        index >= 0;
        index = haystack.indexOf(needle, index + 1)) {
      result++;
    }
    return result;
  }

  private ModelProviderImpl providerWith(ChatModel chatModel, EmbeddingModel embeddingModel) {
    return providerWith(ModelCallGuard.standalone(), chatModel, embeddingModel);
  }

  private ModelProviderImpl providerWith(
      ModelCallGuard guard, ChatModel chatModel, EmbeddingModel embeddingModel) {
    ModelProviderProperties props = new ModelProviderProperties();
    props.setBaseUrl("http://localhost:1");
    props.setApiKey("test-key");
    props.setTimeoutSeconds(0);
    return new ModelProviderImpl(
        providerOf(chatModel), providerOf(embeddingModel), props, new MockEnvironment(), guard);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
