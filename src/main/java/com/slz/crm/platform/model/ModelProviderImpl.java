package com.slz.crm.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
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
 */
@Component
public class ModelProviderImpl implements ModelProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ModelProviderImpl.class);
  private static final String COMPATIBLE_COMPLETIONS_PATH = "/chat/completions";
  private static final String THINKING_HEADER = "X-Enable-Thinking";

  private final ObjectProvider<ChatModel> dashScopeChatModel;
  private final ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel;
  private final ModelProviderProperties properties;
  private final Environment environment;
  private final OpenAiChatModel compatibleChatModel;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebClient streamWebClient = WebClient.builder().build();

  public ModelProviderImpl(
      ObjectProvider<ChatModel> dashScopeChatModel,
      ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel,
      ModelProviderProperties properties,
      Environment environment) {
    this.dashScopeChatModel = dashScopeChatModel;
    this.dashScopeEmbeddingModel = dashScopeEmbeddingModel;
    this.properties = properties;
    this.environment = environment;
    this.compatibleChatModel = buildCompatibleChatModel();
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
        && "true".equals(opts.getHttpHeaders().get(THINKING_HEADER))) {
      result = streamWithThinking(translated, options);
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
        headers.put(THINKING_HEADER, "true");
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

  /** 将 enable_thinking 双写注入 JSON body（sync 经 RestClient 拦截器调用）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // JSON构建容错：objectMapper+节点操作，失败仅告警不抛
  private byte[] injectThinkingIntoBody(byte[] body) {
    byte[] result = body;
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root instanceof ObjectNode obj) {
        obj.put("enable_thinking", true);
        ObjectNode kwargs;
        if (obj.has("chat_template_kwargs") && obj.get("chat_template_kwargs").isObject()) {
          kwargs = (ObjectNode) obj.get("chat_template_kwargs");
        } else {
          kwargs = obj.putObject("chat_template_kwargs");
        }
        kwargs.put("enable_thinking", true);
        result = objectMapper.writeValueAsBytes(obj);
      }
    } catch (Exception e) {
      LOG.warn("enable_thinking body 注入失败", e);
    }
    return result;
  }

  /** thinking=true 流式：手工 JSON + WebClient SSE。 */
  private Flux<ChatResponse> streamWithThinking(Prompt prompt, ModelCallOptions options) {
    String jsonBody = buildChatJsonBody(prompt, options, true);
    String url = resolveBaseUrl() + COMPATIBLE_COMPLETIONS_PATH;
    return streamWebClient
        .post()
        .uri(url)
        .header("Authorization", "Bearer " + resolveApiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(jsonBody)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .filter(sse -> sse.data() != null && !"[DONE]".equals(sse.data().trim()))
        .map(sse -> chunkToChatResponse(sse.data()));
  }

  /** 构建 OpenAI chat completion JSON（thinking 路径）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // JSON构建容错：objectMapper+节点操作多源，统一包装上抛
  private String buildChatJsonBody(Prompt prompt, ModelCallOptions options, boolean stream) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      String model =
          StringUtils.hasText(options.model()) ? options.model() : properties.getChatModel();
      root.put("model", model);
      if (stream) {
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
      }
      if (options.temperature() != null) root.put("temperature", options.temperature());
      if (options.maxTokens() != null) root.put("max_tokens", options.maxTokens());

      ArrayNode messages = root.putArray("messages");
      for (Message msg : prompt.getInstructions()) {
        ObjectNode m = messages.addObject();
        m.put("role", msg.getMessageType().getValue());
        m.put("content", msg.getText() != null ? msg.getText() : "");
      }

      root.put("enable_thinking", true);
      root.putObject("chat_template_kwargs").put("enable_thinking", true);
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("构建 thinking JSON 失败", e);
    }
  }

  /** SSE data JSON 转 ChatResponse。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // JSON解析容错：objectMapper+节点访问，失败回退空响应
  private ChatResponse chunkToChatResponse(String json) {
    ChatResponse result;
    try {
      JsonNode root = objectMapper.readTree(json);
      List<Generation> generations = new ArrayList<>();
      JsonNode choices = root.get("choices");
      if (choices != null && choices.isArray()) {
        for (JsonNode choice : choices) {
          JsonNode delta = choice.get("delta");
          if (delta != null && delta.has("content")) {
            String text = delta.get("content").asText("");
            if (!text.isEmpty()) {
              generations.add(new Generation(new AssistantMessage(text)));
            }
          }
        }
      }
      result = new ChatResponse(generations);
    } catch (Exception e) {
      LOG.warn("SSE chunk 解析失败: {}", json, e);
      result = new ChatResponse(List.of());
    }
    return result;
  }

  private OpenAiChatModel buildCompatibleChatModel() {
    ClientHttpRequestInterceptor thinkingInterceptor =
        (request, body, execution) -> {
          String thinking = request.getHeaders().getFirst(THINKING_HEADER);
          if ("true".equals(thinking)) {
            request.getHeaders().remove(THINKING_HEADER);
            return execution.execute(request, injectThinkingIntoBody(body));
          }
          return execution.execute(request, body);
        };

    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(resolveBaseUrl())
            .apiKey(resolveApiKey())
            .completionsPath(COMPATIBLE_COMPLETIONS_PATH)
            .restClientBuilder(
                org.springframework.web.client.RestClient.builder()
                    .requestInterceptor(thinkingInterceptor))
            .build();
    OpenAiChatOptions defaultOptions =
        OpenAiChatOptions.builder().model(properties.getChatModel()).streamUsage(true).build();
    return OpenAiChatModel.builder().openAiApi(api).defaultOptions(defaultOptions).build();
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

  private String resolveBaseUrl() {
    String result = properties.getBaseUrl();
    if (!StringUtils.hasText(result)) {
      result = environment.getProperty("spring.ai.dashscope.base-url", "");
      if (!StringUtils.hasText(result)) {
        throw new IllegalStateException(
            "compatible-mode base-url 未配置（platform.ai.model.base-url / spring.ai.dashscope.base-url）");
      }
    }
    return result;
  }

  private String resolveApiKey() {
    String result = properties.getApiKey();
    if (!StringUtils.hasText(result)) {
      result = environment.getProperty("spring.ai.dashscope.api-key", "");
      if (!StringUtils.hasText(result)) {
        LOG.warn("compatible-mode api-key 未配置：调用将在运行期失败");
        result = "missing-api-key";
      }
    }
    return result;
  }
}
