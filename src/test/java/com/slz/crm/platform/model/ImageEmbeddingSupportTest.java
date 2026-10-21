package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/**
 * multimodal-shared-space 任务 5.1：DashScope 文本-图像联合 embedding 协议单测（本地 HTTP 桩，零真外呼）。
 *
 * <p>覆盖：请求体形态（model + image data URI）/ 向量与 usage 解析 / 业务错误码与空向量抛错 / 接口默认实现 （未实现视觉 embedding 的
 * Provider 抛 {@link UnsupportedOperationException}）。
 */
@DisplayName("视觉 embedding 协议（任务 1.1）")
class ImageEmbeddingSupportTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  @Test
  @DisplayName("成功：解析 output.embeddings[0].embedding 与 usage，请求体带 model + 图片 data URI")
  void call_parsesVectorAndUsage() throws IOException {
    String response =
        """
        {"output":{"embeddings":[{"index":0,"embedding":[0.5,0.25,-0.125]}]},
         "usage":{"input_tokens":12,"total_tokens":12},"request_id":"req-1"}""";
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authHeader = new AtomicReference<>();
    String endpoint = serve(response, requestBody, authHeader);
    ImageEmbeddingSupport support = support(endpoint, "sk-test");

    ImageEmbeddingSupport.ImageVectorCall call =
        support.call("png-bytes".getBytes(StandardCharsets.UTF_8), "image/png");

    assertThat(call.vector()).containsExactly(0.5F, 0.25F, -0.125F);
    assertThat(call.promptTokens()).isEqualTo(12L);
    assertThat(call.totalTokens()).isEqualTo(12L);
    assertThat(authHeader.get()).isEqualTo("Bearer sk-test");
    assertThat(requestBody.get())
        .contains("\"model\":\"multimodal-embedding-v1\"")
        .contains("data:image/png;base64,")
        .contains("\"contents\"");
  }

  @Test
  @DisplayName("业务错误码：抛 IllegalStateException（不返回伪造向量）")
  void call_businessErrorCode_throws() throws IOException {
    String endpoint =
        serve(
            "{\"code\":\"InvalidParameter\",\"message\":\"image 解码失败\"}",
            new AtomicReference<>(),
            new AtomicReference<>());
    ImageEmbeddingSupport support = support(endpoint, "sk-test");

    assertThatThrownBy(() -> support.call(new byte[] {1, 2, 3}, "image/png"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("InvalidParameter");
  }

  @Test
  @DisplayName("空向量：抛 IllegalStateException")
  void call_emptyVector_throws() throws IOException {
    String endpoint =
        serve("{\"output\":{\"embeddings\":[]}}", new AtomicReference<>(), new AtomicReference<>());
    ImageEmbeddingSupport support = support(endpoint, "sk-test");

    assertThatThrownBy(() -> support.call(new byte[] {1, 2, 3}, "image/png"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未返回向量");
  }

  @Test
  @DisplayName("空图片字节：抛 IllegalArgumentException（调用方缺陷，不外呼）")
  void call_emptyImage_throwsBeforeRequest() {
    ImageEmbeddingSupport support = support("http://127.0.0.1:1/emb", "sk-test");

    assertThatThrownBy(() -> support.call(new byte[0], "image/png"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("接口默认实现：未实现视觉 embedding 的 Provider 抛 UnsupportedOperationException（既有实现方零改动）")
  void modelProviderDefault_throwsUnsupported() {
    ModelProvider minimal =
        new ModelProvider() {
          @Override
          public String provider() {
            return "vllm";
          }

          @Override
          public ModelCallResult<String> chat(Prompt prompt) {
            throw new UnsupportedOperationException("not needed");
          }

          @Override
          public Flux<org.springframework.ai.chat.model.ChatResponse> streamChat(Prompt prompt) {
            return Flux.empty();
          }

          @Override
          public ModelCallResult<float[]> embed(EmbeddingRequest request) {
            return ModelCallResult.ofVector(new float[] {1F}, "text-embedding", 1L, 1L);
          }

          @Override
          public ModelCallResult<String> vision(Prompt prompt) {
            throw new UnsupportedOperationException("not needed");
          }
        };

    assertThatThrownBy(() -> minimal.embedImage(new byte[] {1}, "image/png"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("未实现视觉 embedding");
  }

  private static ImageEmbeddingSupport support(String endpoint, String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setImageEmbeddingEndpoint(endpoint);
    properties.setApiKey(apiKey);
    properties.setTimeoutSeconds(5);
    properties.setConnectTimeoutSeconds(5);
    return new ImageEmbeddingSupport(properties, new MockEnvironment());
  }

  private String serve(
      String responseBody, AtomicReference<String> requestBody, AtomicReference<String> authHeader)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/emb",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, payload.length);
          try (var output = exchange.getResponseBody()) {
            output.write(payload);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/emb";
  }
}
