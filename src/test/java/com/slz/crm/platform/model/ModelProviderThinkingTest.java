package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 修正轮2：thinking=true 经 streamChat/chat 请求体确带 enable_thinking。
 * JDK HttpServer 捕获请求 body，不引 MockWebServer 依赖。
 */
class ModelProviderThinkingTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseMode = new AtomicReference<>("sse");
    private ModelProviderImpl provider;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp;
            String contentType;
            if ("sse".equals(responseMode.get())) {
                resp = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"
                        .getBytes(StandardCharsets.UTF_8);
                contentType = "text/event-stream";
            } else {
                resp = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
                contentType = "application/json";
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
        provider = new ModelProviderImpl(emptyProvider(), emptyProvider(), props, new MockEnvironment());
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<T>() {
            @Override public T getObject() { throw new IllegalStateException("no bean"); }
            @Override public T getIfAvailable() { return null; }
        };
    }

    @Test
    void streamChatWithThinkingSendsEnableThinking() {
        new Prompt(new UserMessage("hello"));
        provider.streamChat(new Prompt(new UserMessage("hello")),
                new ModelCallOptions(null, true, null, null, Map.of())).blockLast();
        assertThat(capturedBody.get()).contains("enable_thinking").contains("chat_template_kwargs").contains("stream");
    }

    @Test
    void streamChatWithoutThinkingNoEnableThinking() {
        provider.streamChat(new Prompt(new UserMessage("hello")), ModelCallOptions.defaults()).blockLast();
        assertThat(capturedBody.get()).doesNotContain("enable_thinking");
    }

    @Test
    void chatWithThinkingSendsEnableThinking() {
        responseMode.set("json");
        provider.chat(new Prompt(new UserMessage("hello")),
                new ModelCallOptions(null, true, null, null, Map.of()));
        assertThat(capturedBody.get()).contains("enable_thinking").contains("chat_template_kwargs");
    }
}
