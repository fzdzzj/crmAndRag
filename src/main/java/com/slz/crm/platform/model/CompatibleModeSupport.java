package com.slz.crm.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.platform.contract.ModelCallOptions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * compatible-mode 基础设施协作类（tighten-pmd-residual-325 任务 6.4 批A：拆自 {@link ModelProviderImpl}，行为等价）。
 *
 * <p>职责：装配 compatible-mode {@link OpenAiChatModel}（含 sync 路径 RestClient 拦截器注入 enable_thinking 双写）、
 * thinking=true 时的手工 JSON + WebClient SSE 流式通道、SSE chunk 到 {@link ChatResponse} 的解析。 路由决策仍归 {@link
 * ModelProviderImpl}，本类只承载 compatible-mode 协议细节。
 */
class CompatibleModeSupport {

  private static final Logger LOG = LoggerFactory.getLogger(CompatibleModeSupport.class);
  private static final String COMPATIBLE_COMPLETIONS_PATH = "/chat/completions";

  /** thinking 开关注入的请求头；路由层据此识别手工 SSE 通道，包内可见。 */
  static final String THINKING_HEADER = "X-Enable-Thinking";

  private final ModelProviderProperties properties;
  private final Environment environment;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebClient streamWebClient = WebClient.builder().build();

  CompatibleModeSupport(ModelProviderProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  /** 装配 compatible-mode ChatModel：sync 经 RestClient 拦截器把 enable_thinking 双写进 JSON body。 */
  OpenAiChatModel buildCompatibleChatModel() {
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

  /** thinking=true 流式：手工 JSON + WebClient SSE。 */
  Flux<ChatResponse> streamWithThinking(Prompt prompt, ModelCallOptions options) {
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
