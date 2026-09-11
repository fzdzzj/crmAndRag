import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * DashScope compatible-mode 最小真实链路验证：一次思考流式 + 一次文本向量。
 *
 * <p>只读取环境变量中的 API Key，不打印密钥；输出只保留统计信息，避免把模型内容入库。</p>
 */
public final class SpringAiRuntimeSpike {

    /** JSON 解析器；SSE 行均应是 OpenAI Chat Completion chunk。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 防止工具类被实例化。 */
    private SpringAiRuntimeSpike() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 DASHSCOPE_API_KEY，未执行真实链路");
        }
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        verifyCompatibleThinking(apiKey);
        verifyRuntimeEmbeddingDimension(api);
        System.out.println("B0 runtime spike: PASS");
    }

    /**
     * 验证 qwen3.8-flash 在 compatible-mode 下返回 reasoning_content 增量。
     *
     * @param apiKey DashScope API Key
     */
    private static void verifyCompatibleThinking(String apiKey) {
        WebClient client = WebClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .defaultHeader("X-DashScope-SSE", "enable")
                .build();
        Map<String, Object> request = Map.of(
                "model", "qwen3.8-flash",
                "stream", true,
                "enable_thinking", true,
                "messages", List.of(
                        Map.of("role", "system", "content", "请用一句话回答。"),
                        Map.of("role", "user", "content", "9.11 和 9.8 哪个大？")));

        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger thinkingChunks = new AtomicInteger();
        Flux<ServerSentEvent<String>> flux = client.post()
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(60))
                .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(500))
                        .filter(SpringAiRuntimeSpike::isTransientStreamError))
                .doOnNext(event -> {
                    String text = event.data();
                    if (text == null || text.isBlank() || "[DONE]".equals(text.strip())) {
                        return;
                    }
                    chunks.incrementAndGet();
                    try {
                        JsonNode root = OBJECT_MAPPER.readTree(stripDataPrefix(text));
                        JsonNode choices = root.path("choices");
                        for (int index = 0; index < choices.size(); index++) {
                            String reasoning = choices.get(index)
                                    .path("delta")
                                    .path("reasoning_content")
                                    .asText("");
                            if (!reasoning.isBlank()) {
                                thinkingChunks.incrementAndGet();
                            }
                        }
                    }
                    catch (Exception exception) {
                        throw new IllegalStateException("解析 compatible-mode SSE 失败", exception);
                    }
                });
        flux.blockLast(Duration.ofSeconds(70));

        System.out.println("runtime thinking: chunks=" + chunks.get()
                + ", thinkingChunks=" + thinkingChunks.get());
        if (thinkingChunks.get() == 0) {
            throw new IllegalStateException("compatible-mode 未返回 reasoning_content 增量");
        }
    }

    /**
     * 兼容部分网关未剥离 data: 前缀的场景。
     *
     * @param text SSE 数据帧
     * @return JSON 文本
     */
    private static String stripDataPrefix(String text) {
        String trimmed = text.strip();
        return trimmed.startsWith("data:") ? trimmed.substring("data:".length()).strip() : trimmed;
    }

    /**
     * 只重试连接中断、响应体解码失败和等待超时；业务 JSON 解析错误不重试。
     *
     * @param throwable 流式异常
     * @return 是否可重试
     */
    private static boolean isTransientStreamError(Throwable throwable) {
        String message = String.valueOf(throwable.getMessage()).toLowerCase();
        return throwable instanceof IOException
                || throwable instanceof TimeoutException
                || message.contains("connection reset")
                || message.contains("connection prematurely closed")
                || message.contains("stream disconnected before completion")
                || message.contains("error decoding response body");
    }

    /**
     * 验证 text-embedding-v3 实际返回 1024 维向量。
     *
     * @param api DashScope API 客户端
     */
    private static void verifyRuntimeEmbeddingDimension(DashScopeApi api) {
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel("text-embedding-v3")
                .withDimensions(1024)
                .build();
        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel(api, MetadataMode.EMBED, options);
        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("向量维度验证"), options));
        float[] vector = response.getResults().getFirst().getOutput();
        if (vector.length != 1024) {
            throw new IllegalStateException("实际向量维度不是 1024: " + vector.length);
        }
        System.out.println("runtime embedding: PASS, dimensions=1024");
    }
}
