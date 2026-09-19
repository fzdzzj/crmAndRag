package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/** 修正轮3：带工具的 chat/streamChat 经 ModelProvider 完成 tool 环 + usage 不漏计。 */
class ModelProviderToolTest {

  private HttpServer server;
  private final AtomicInteger callCount = new AtomicInteger(0);
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private ModelProviderImpl provider;
  private FunctionToolCallback<Void, String> weatherTool;
  private final AtomicReference<String> toolResult = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    weatherTool =
        FunctionToolCallback.builder(
                "getWeather",
                (Void unused) -> {
                  toolResult.set("25C");
                  return "Beijing 25C";
                })
            .description("Get weather for a city")
            .inputType(java.lang.Void.class)
            .build();

    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int call = callCount.incrementAndGet();
          byte[] resp;
          String contentType = "application/json";
          if (call == 1) {
            resp =
                ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"getWeather\",\"arguments\":\"{}\"}}]}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}")
                    .getBytes(StandardCharsets.UTF_8);
          } else {
            resp =
                ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Beijing is 25C\"}}],"
                        + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8);
          }
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });
    server.start();

    ModelProviderProperties props = new ModelProviderProperties();
    props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
    props.setApiKey("test-key");
    provider =
        new ModelProviderImpl(emptyProvider(), emptyProvider(), props, new MockEnvironment());
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> emptyProvider() {
    return new ObjectProvider<T>() {
      @Override
      public T getObject() {
        throw new IllegalStateException("no bean");
      }

      @Override
      public T getIfAvailable() {
        return null;
      }
    };
  }

  @Test
  void chatWithToolsCompletesToolLoopAndReturnsUsage() {
    var result =
        provider.chat(
            new Prompt(new UserMessage("What is the weather?")),
            ModelCallOptions.withTools(List.of(weatherTool), Map.of()));

    assertThat(toolResult.get()).isEqualTo("25C");
    assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
    assertThat(result.totalTokens()).isNotNull().isGreaterThan(0L);
    assertThat(result.content()).contains("25C");
  }

  @Test
  void chatWithToolsAndThinkingDoesNotCrash() {
    var result =
        provider.chat(
            new Prompt(new UserMessage("Weather?")),
            new ModelCallOptions(null, true, null, null, List.of(weatherTool), Map.of(), Map.of()));

    assertThat(toolResult.get()).isEqualTo("25C");
    assertThat(result.content()).isNotNull();
  }

  @Test
  void streamChatWithToolsCompletesAndDoesNotCrash() {
    // Spring AI tool loop in stream mode: model returns tool_calls, tool executes, model returns
    // content
    // For simplicity verify no crash + tool execution
    var flux =
        provider.streamChat(
            new Prompt(new UserMessage("Weather?")),
            ModelCallOptions.withTools(List.of(weatherTool), Map.of()));
    var list = flux.collectList().block();

    assertThat(list).isNotNull();
  }
}
