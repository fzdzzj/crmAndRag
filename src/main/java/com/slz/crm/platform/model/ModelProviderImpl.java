package com.slz.crm.platform.model;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
@Component
public class ModelProviderImpl implements ModelProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ModelProviderImpl.class);

  private final ObjectProvider<ChatModel> dashScopeChatModel;
  private final ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel;
  private final ModelProviderProperties properties;
  private final OpenAiChatModel compatibleChatModel;
  private final CompatibleModeSupport compatibleMode;

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
    return toTextResult(model.call(prompt), properties.getChatModel());
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
    EmbeddingResponse response = model.call(request);
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
    return toTextResult(compatibleChatModel.call(forced), properties.getVisionModel());
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
      result = toTextResult(compatibleChatModel.call(withModel(translated, model)), model);
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
}
