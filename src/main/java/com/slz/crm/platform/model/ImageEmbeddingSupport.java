package com.slz.crm.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.platform.contract.ModelCallResult;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * DashScope 文本-图像联合 embedding 协议细节（multimodal-shared-space 任务 1.1，零新依赖）。
 *
 * <p>为什么单独成类：{@link ModelProviderImpl} 只保留路由与结果转换（PMD {@code NcssCount} 预算），协议细节（原生 {@code
 * /api/v1/services/embeddings/multimodal-embedding/multimodal-embedding} 的 JSON 装配、data URI 编码、
 * {@code output.embeddings[].embedding} 解析）落在这里，与 {@link CompatibleModeSupport} 同一分工。
 *
 * <p>为什么不用 Spring AI 的 {@code EmbeddingModel}：DashScope 原生联合 embedding 是独立服务端点，不在 OpenAI 兼容 {@code
 * /embeddings} 语义内，Spring AI 的 {@code EmbeddingRequest} 只承载文本；走 {@link RestClient}（已在
 * classpath，compatible-mode 同款）即可真视觉，不引入新依赖。
 *
 * <p>失败语义：不吞错——HTTP 非 2xx、业务错误码、空向量一律抛 {@link IllegalStateException}，由调用方（摄取旁路/查询侧）按
 * "失败即降级"决定是否继续，绝不返回伪造向量。
 */
class ImageEmbeddingSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ImageEmbeddingSupport.class);

  /** 缺省 MIME：调用方未给出类型时按 PNG 声明（内嵌图片统一转 PNG，与 PdfImageSliceIngestService 同口径）。 */
  static final String DEFAULT_MIME_TYPE = "image/png";

  private static final String DATA_URI_PREFIX = "data:";

  private final ModelProviderProperties properties;
  private final Environment environment;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestClient restClient;

  ImageEmbeddingSupport(ModelProviderProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
    this.restClient =
        RestClient.builder()
            .requestFactory(
                CompatibleModeSupport.buildRequestFactory(
                    properties.getConnectTimeoutSeconds(), properties.getTimeoutSeconds()))
            .build();
  }

  /** 一次视觉 embedding 的返回值（向量 + usage）；usage 缺失时为 null，由调用方按"无计量"处理。 */
  record ImageVectorCall(float[] vector, Long promptTokens, Long totalTokens) {}

  /** 调用结果 → 契约结果（模型名取配置的视觉 embedding 模型；usage 缺失按无计量，不伪造 token）。 */
  static ModelCallResult<float[]> toResult(ImageVectorCall call, String model) {
    return ModelCallResult.ofVector(call.vector(), model, call.promptTokens(), call.totalTokens());
  }

  /**
   * 图片 → 视觉向量（图→向量，不做任何文本降维）。
   *
   * @param imageBytes 图片原始字节
   * @param mimeType 图片 MIME（空则按 {@value #DEFAULT_MIME_TYPE}）
   * @return 视觉向量 + usage
   * @throws IllegalArgumentException 图片字节为空
   * @throws IllegalStateException 端点未配置 / HTTP 失败 / 业务错误 / 空向量
   */
  ImageVectorCall call(byte[] imageBytes, String mimeType) {
    if (imageBytes == null || imageBytes.length == 0) {
      throw new IllegalArgumentException("视觉 embedding 入参图片字节不能为空");
    }
    String body = buildRequestBody(imageBytes, mimeType);
    String response =
        restClient
            .post()
            .uri(resolveEndpoint())
            .header("Authorization", "Bearer " + resolveApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);
    return parseResponse(response);
  }

  /** DashScope 联合 embedding 请求体：{@code input.contents[].image} 传 data URI（自建网关亦可直接吃 base64）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // JSON 装配容错：ObjectMapper 多源异常统一包装上抛
  private String buildRequestBody(byte[] imageBytes, String mimeType) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("model", properties.getImageEmbeddingModel());
      ArrayNode contents = root.putObject("input").putArray("contents");
      contents.addObject().put("image", toDataUri(imageBytes, mimeType));
      return objectMapper.writeValueAsString(root);
    } catch (Exception exception) {
      throw new IllegalStateException("构建视觉 embedding 请求体失败", exception);
    }
  }

  /** base64 data URI：把 mime 一起带上，端点按声明解码，避免内容嗅探。 */
  static String toDataUri(byte[] imageBytes, String mimeType) {
    String mime = StringUtils.hasText(mimeType) ? mimeType.trim() : DEFAULT_MIME_TYPE;
    return DATA_URI_PREFIX + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
  }

  /** 解析 {@code output.embeddings[0].embedding}（兼容顶层 {@code output.embedding} 形态）与 usage。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // JSON 解析容错：结构漂移统一转业务异常
  private ImageVectorCall parseResponse(String response) {
    ImageVectorCall result;
    try {
      JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
      String errorCode = root.path("code").asText("");
      if (!errorCode.isBlank()) {
        throw new IllegalStateException(
            "视觉 embedding 端点返回错误: " + errorCode + " " + root.path("message").asText(""));
      }
      JsonNode embedding = firstEmbedding(root);
      if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
        throw new IllegalStateException("视觉 embedding 端点未返回向量");
      }
      float[] vector = new float[embedding.size()];
      for (int index = 0; index < embedding.size(); index++) {
        vector[index] = (float) embedding.get(index).asDouble();
      }
      JsonNode usage = root.path("usage");
      result =
          new ImageVectorCall(
              vector, positiveOrNull(usage, "input_tokens"), positiveOrNull(usage, "total_tokens"));
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException stateException) {
        throw stateException;
      }
      throw new IllegalStateException("视觉 embedding 响应解析失败", exception);
    }
    return result;
  }

  private static JsonNode firstEmbedding(JsonNode root) {
    JsonNode output = root.path("output");
    JsonNode embeddings = output.path("embeddings");
    JsonNode embedding =
        embeddings.isArray() && !embeddings.isEmpty()
            ? embeddings.get(0).path("embedding")
            : output.get("embedding");
    return embedding == null || embedding.isMissingNode() ? null : embedding;
  }

  private static Long positiveOrNull(JsonNode usage, String field) {
    long value = usage.path(field).asLong(0L);
    return value > 0L ? Long.valueOf(value) : null;
  }

  private String resolveEndpoint() {
    String endpoint = properties.getImageEmbeddingEndpoint();
    if (!StringUtils.hasText(endpoint)) {
      throw new IllegalStateException(
          "视觉 embedding 端点未配置（platform.ai.model.image-embedding-endpoint）");
    }
    return endpoint.trim();
  }

  /** api-key 解析顺序与 compatible-mode 一致：本节配置优先，其次 {@code spring.ai.dashscope.api-key}。 */
  private String resolveApiKey() {
    String apiKey = properties.getApiKey();
    if (!StringUtils.hasText(apiKey)) {
      apiKey = environment.getProperty("spring.ai.dashscope.api-key", "");
      if (!StringUtils.hasText(apiKey)) {
        LOG.warn("视觉 embedding api-key 未配置：调用将在运行期失败");
        apiKey = "missing-api-key";
      }
    }
    return apiKey;
  }
}
