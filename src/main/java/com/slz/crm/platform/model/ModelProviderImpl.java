package com.slz.crm.platform.model;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * {@link ModelProvider} 的 Spring AI DashScope 实现（contracts-frozen.md SS1, base）。
 *
 * <p>路由策略：
 *
 * <ul>
 *   <li><b>非流式 {@link #chat(Prompt)}</b>：优先 DashScope 原生（usage 稳定）；缺失回退 compatible-mode；
 *   <li><b>流式 {@link #streamChat(Prompt)}</b>：恒走 compatible-mode SSE + streamUsage(true)；
 *   <li><b>向量 {@link #embed(EmbeddingRequest)}</b>：DashScope EmbeddingModel；
 *   <li><b>视觉 {@link #vision(Prompt)}</b>：compatible-mode + 强制 vision 模型。
 * </ul>
 *
 * <p>修正轮2：新增 {@link ModelCallOptions} 重载，thinking=true 时 sync 走 RestClient 拦截器注入 enable_thinking，
 * stream 走独立 JSON + WebClient（确保 body 带 enable_thinking 双写）。
 *
 * <p>tighten-pmd-residual-325 任务 6.4 批A：compatible-mode 协议细节（模型装配 / thinking JSON / SSE chunk 解析）拆至
 * {@link CompatibleModeSupport}，本类只保留路由与结果转换，行为等价。
 *
 * <p>wire-llm-call-timeout 任务 3：非流式 chat / vision / embed 四处同步 call 点经 {@link #callWithTimeout} 接线
 * {@code platform.ai.model.timeout-seconds}——超时快速失败并真中断底层调用，0 保持不限时；流式路不在此列。
 */
@Component
public class ModelProviderImpl implements ModelProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ModelProviderImpl.class);

  private final ObjectProvider<ChatModel> dashScopeChatModel;
  private final ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel;
  private final ModelProviderProperties properties;
  private final OpenAiChatModel compatibleChatModel;
  private final CompatibleModeSupport compatibleMode;

  /**
   * wire-llm-call-timeout 任务 3：非流式模型调用专用虚拟线程执行器。线程名前缀 {@code platform-model-call-}
   * 便于线程转储定位；每调用一线程，并发量天然受调用方约束，无队列饱和与拒绝放大， 阻塞 socket 读对 {@code Thread#interrupt()} 真实响应。刻意不复用
   * {@code llmAuxTaskExecutor}：检索三消费方已在该池外层提交 {@code chat()}，同池嵌套提交会自我争用。
   */
  private final ExecutorService modelCallExecutor =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("platform-model-call-", 0).factory());

  public ModelProviderImpl(
      ObjectProvider<ChatModel> dashScopeChatModel,
      ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel,
      ModelProviderProperties properties,
      Environment environment) {
    this.dashScopeChatModel = dashScopeChatModel;
    this.dashScopeEmbeddingModel = dashScopeEmbeddingModel;
    this.properties = properties;
    this.compatibleMode = new CompatibleModeSupport(properties, environment);
    this.compatibleChatModel = compatibleMode.buildCompatibleChatModel();
  }

  /** 关停时中断在途非流式模型调用，避免拖慢上下文关闭。 */
  @PreDestroy
  void shutdownModelCallExecutor() {
    modelCallExecutor.shutdownNow();
  }

  @Override
  public String provider() {
    return properties.getProvider();
  }

  @Override
  public ModelCallResult<String> chat(Prompt prompt) {
    ChatModel nativeModel = dashScopeChatModel.getIfAvailable();
    ChatModel model = nativeModel != null ? nativeModel : compatibleChatModel;
    if (LOG.isDebugEnabled()) {
      LOG.debug("chat() 使用 {} 协议", nativeModel != null ? "dashscope" : "compatible-mode");
    }
    ChatResponse response = callWithTimeout(() -> model.call(prompt), "chat");
    return toTextResult(response, properties.getChatModel());
  }

  @Override
  public Flux<ChatResponse> streamChat(Prompt prompt) {
    Prompt forced = withStreamUsage(prompt);
    return compatibleChatModel.stream(forced);
  }

  @Override
  public ModelCallResult<float[]> embed(EmbeddingRequest request) {
    EmbeddingModel model = dashScopeEmbeddingModel.getIfAvailable();
    if (model == null) {
      throw new IllegalStateException(
          "DashScope EmbeddingModel 未装配（请检查 spring.ai.dashscope.api-key 与 starter 依赖）");
    }
    EmbeddingResponse response = callWithTimeout(() -> model.call(request), "embed");
    float[] vector =
        response.getResult() != null && response.getResult().getOutput() != null
            ? response.getResult().getOutput()
            : new float[0];
    Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    return ModelCallResult.ofVector(
        vector,
        properties.getEmbeddingModel(),
        toLong(usage == null ? null : usage.getPromptTokens()),
        toLong(usage == null ? null : usage.getTotalTokens()));
  }

  @Override
  public ModelCallResult<String> vision(Prompt prompt) {
    Prompt forced = withModel(prompt, properties.getVisionModel());
    ChatResponse response = callWithTimeout(() -> compatibleChatModel.call(forced), "vision");
    return toTextResult(response, properties.getVisionModel());
  }

  // ==================== 修正轮2 ====================

  @Override
  public ModelCallResult<String> chat(Prompt prompt, ModelCallOptions options) {
    return chat(applyOptions(prompt, options));
  }

  @Override
  public Flux<ChatResponse> streamChat(Prompt prompt, ModelCallOptions options) {
    Prompt translated = applyOptions(prompt, options);
    Flux<ChatResponse> result;
    if (options != null && options.hasTools()) {
      // 修正轮3：工具优先——走 Spring AI tool loop（enable_thinking 尽力而为，
      // 手工 JSON 路径不支持工具，不能为 thinking 破坏 tool loop）
      result = streamChat(translated);
    } else if (translated.getOptions() instanceof OpenAiChatOptions opts
        && opts.getHttpHeaders() != null
        && "true".equals(opts.getHttpHeaders().get(CompatibleModeSupport.THINKING_HEADER))) {
      result = compatibleMode.streamWithThinking(translated, options);
    } else {
      result = streamChat(translated);
    }
    return result;
  }

  @Override
  public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
    ModelCallResult<String> result;
    if (options == null) {
      result = vision(prompt);
    } else {
      Prompt translated = applyOptions(prompt, options);
      String model =
          StringUtils.hasText(options.model()) ? options.model() : properties.getVisionModel();
      result =
          toTextResult(
              callWithTimeout(
                  () -> compatibleChatModel.call(withModel(translated, model)), "vision"),
              model);
    }
    return result;
  }

  /** 翻译 ModelCallOptions 到 OpenAiChatOptions（修正轮2：thinking 头标记）。 */
  private Prompt applyOptions(Prompt prompt, ModelCallOptions options) {
    Prompt result;
    if (options == null) {
      result = prompt;
    } else {
      OpenAiChatOptions opts = overlayFrom(prompt.getOptions());
      if (StringUtils.hasText(options.model())) opts.setModel(options.model());
      if (options.temperature() != null) opts.setTemperature(options.temperature());
      if (options.maxTokens() != null) opts.setMaxTokens(options.maxTokens());
      if (options.hasTools()) {
        opts.setToolCallbacks(options.toolCallbacks());
        if (options.toolContext() != null) opts.setToolContext(options.toolContext());
      }
      if (options.thinking()) {
        Map<String, String> headers =
            opts.getHttpHeaders() != null
                ? new LinkedHashMap<>(opts.getHttpHeaders())
                : new LinkedHashMap<>();
        headers.put(CompatibleModeSupport.THINKING_HEADER, "true");
        opts.setHttpHeaders(headers);
      }
      result = new Prompt(prompt.getInstructions(), opts);
    }
    return result;
  }

  /** 从调用方 options 构建 OpenAiChatOptions（修正轮2：非 OpenAi 入参不丢参）。 */
  private OpenAiChatOptions overlayFrom(ChatOptions source) {
    OpenAiChatOptions result;
    if (source instanceof OpenAiChatOptions openAi) {
      result = OpenAiChatOptions.fromOptions(openAi);
    } else {
      result = new OpenAiChatOptions();
      if (source != null) {
        if (source.getModel() != null) result.setModel(source.getModel());
        if (source.getTemperature() != null) result.setTemperature(source.getTemperature());
        if (source.getMaxTokens() != null) result.setMaxTokens(source.getMaxTokens());
      }
    }
    return result;
  }

  private Prompt withStreamUsage(Prompt prompt) {
    OpenAiChatOptions options = overlayFrom(prompt.getOptions());
    if (!StringUtils.hasText(options.getModel())) {
      options.setModel(properties.getChatModel());
    }
    options.setStreamUsage(true);
    return new Prompt(prompt.getInstructions(), options);
  }

  private Prompt withModel(Prompt prompt, String model) {
    OpenAiChatOptions options = overlayFrom(prompt.getOptions());
    options.setModel(model);
    return new Prompt(prompt.getInstructions(), options);
  }

  private ModelCallResult<String> toTextResult(ChatResponse response, String fallbackModel) {
    String text = extractText(response);
    ChatResponseMetadata metadata = response.getMetadata();
    String model =
        (metadata != null && StringUtils.hasText(metadata.getModel()))
            ? metadata.getModel()
            : fallbackModel;
    Usage usage = metadata == null ? null : metadata.getUsage();
    return ModelCallResult.ofText(
        text,
        model,
        toLong(usage == null ? null : usage.getPromptTokens()),
        toLong(usage == null ? null : usage.getCompletionTokens()),
        toLong(usage == null ? null : usage.getTotalTokens()));
  }

  private String extractText(ChatResponse response) {
    String result = "";
    if (response.getResult() != null && response.getResult().getOutput() != null) {
      AssistantMessage output = response.getResult().getOutput();
      result = output.getText() == null ? "" : output.getText();
    }
    return result;
  }

  private Long toLong(Integer value) {
    return value == null ? null : value.longValue();
  }

  // ==================== wire-llm-call-timeout 任务 3 ====================

  /**
   * 调用方侧护栏在配置超时值之后的宽限秒数。护栏是"背锅层"，必须让同旋钮值的 HTTP 读超时（卡 G 接线）先失效并 保留其精确断口（{@code
   * HttpTimeoutException}）；宽限只需盖过 HTTP 客户端自身的触发开销（实测锚点 ≤0.3s）。
   */
  private static final long CALLER_GRACE_SECONDS = 5L;

  /**
   * 非流式模型调用的调用方侧超时护栏（wire-llm-call-timeout 任务 3）。
   *
   * <p>卡 G 已把 {@code platform.ai.model.timeout-seconds} 接到 HTTP 层（{@code ClientHttpRequestFactory}
   * 读超时）， 但读超时可被"慢滴"响应逐字节 重置而失效，SDK 内非 HTTP 环节也不受其约束。本护栏作背锅层兜住这些盲区： 预算 = 配置值 + {@link
   * #CALLER_GRACE_SECONDS}（刻意落在 HTTP 层之后，不抢它的断口）；到点经 {@link Future#cancel(boolean)}
   * 真中断底层调用线程（虚拟线程的阻塞 socket 读对中断真实响应）， 以 {@link IllegalStateException}（cause={@link
   * TimeoutException}）快速失败上抛。零重试：失败即失败，绝不放大调用量； 正常调用与异常穿透路径逐字段等价于直调。
   *
   * <p>{@code timeoutSeconds <= 0} 按配置语义"0 = 不限时"直调，不包裹不换线程，行为与历史版本一致。
   *
   * <p>实现刻意保持最小形态：任务不经 MDC/UserContext 装饰器——虚拟线程每任务一线程、任务间零复用，不存在串号或
   * 泄漏；调用方线程上的治理池装饰器（MdcTaskDecorator）已保证外层日志带 trace 身份，仅模型客户端内部日志短暂缺 MDC， 该可观测性损耗换来护栏语句数不超 PMD 类
   * NCSS 预算（pmd-rules.xml 150）。
   */
  private <T> T callWithTimeout(Supplier<T> call, String scene) {
    long timeoutSeconds = properties.getTimeoutSeconds();
    T result;
    if (timeoutSeconds <= 0) {
      result = call.get();
    } else {
      long boundSeconds = timeoutSeconds + CALLER_GRACE_SECONDS;
      Future<T> future = modelCallExecutor.submit(call::get);
      try {
        result = future.get(boundSeconds, TimeUnit.SECONDS);
      } catch (TimeoutException exception) {
        // FutureTask 语义：cancel(true) 真中断执行线程；CompletableFuture.cancel 的中断参数是无效的，故不用它。
        future.cancel(true);
        LOG.warn("{} 模型调用等待 {}s 未返回，已中断底层调用", scene, boundSeconds);
        throw new IllegalStateException(
            scene + " 模型调用等待 " + boundSeconds + "s 超时（配置 " + timeoutSeconds + "s + 宽限），已中断底层调用",
            exception);
      } catch (ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IllegalStateException(scene + " 模型调用失败", cause);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(scene + " 模型调用等待被中断", exception);
      }
    }
    return result;
  }
}
