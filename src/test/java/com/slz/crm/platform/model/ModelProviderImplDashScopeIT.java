package com.slz.crm.platform.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link ModelProviderImpl} 真机验证（DashScope，IT，本地手动触发）。
 *
 * <p>为什么放 IT：单测只能验证“字段有没有抄对”，验证不了“协议真的通、usage 真的回来”。
 * 本类直接构造真实 DashScope 客户端，验证 §1 的三条硬要求：</p>
 * <ol>
 *   <li>chat() 返回 {@link ModelCallResult} 且带 usage；</li>
 *   <li>streamChat() 走 compatible-mode SSE 且流里有 usage chunk；</li>
 *   <li>embed() 返回向量且带 usage。</li>
 * </ol>
 *
 * <p>触发条件：环境变量 {@code DASHSCOPE_API_KEY}，或仓库根目录 {@code .env} 里有同名键；
 * 否则测试自动跳过（Assumptions），保证无 key 的 CI 不会误报。</p>
 *
 * <p>边界：vision() 未在真机覆盖（需要真实图片与外部图床），它与 chat() 共用同一 compatible-mode
 * 客户端代码路径，仅模型名被强制为 qwen-vl 系列；该强制逻辑用纯单测覆盖即可。</p>
 */
class ModelProviderImplDashScopeIT {

    /** compatible-mode 端点（Spring AI OpenAI 协议要求 base-url 已含版本前缀） */
    private static final String COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Test
    void chatShouldReturnUsageOverNativeDashScope() {
        ModelProvider provider = buildProvider();
        ModelCallResult<String> result =
                provider.chat(new Prompt("只回复两个字：好的"));
        assertFalse(result.content().isBlank(), "chat() 必须返回非空文本");
        assertTrue(result.hasUsage(), "chat() 必须带 usage（修 RAG chat(String) 漏计盲点），实际=" + result);
    }

    @Test
    void streamChatShouldCarryUsageInCompatibleMode() {
        ModelProvider provider = buildProvider();
        List<ChatResponse> chunks = provider.streamChat(new Prompt("从1数到3"))
                .collectList()
                .block(Duration.ofSeconds(90));
        assertNotNull(chunks, "流式调用必须返回 chunk 列表");
        assertFalse(chunks.isEmpty(), "流式调用不能返回空流");
        boolean anyUsage = chunks.stream()
                .map(ChatResponse::getMetadata)
                .anyMatch(metadata -> metadata != null && metadata.getUsage() != null
                        && metadata.getUsage().getTotalTokens() != null);
        assertTrue(anyUsage, "compatible-mode 流式必须能拿到 usage chunk（streamUsage=true）");
    }

    @Test
    void embedShouldReturnVectorAndUsage() {
        ModelProvider provider = buildProvider();
        ModelCallResult<float[]> result = provider.embed(new EmbeddingRequest(
                List.of("crm-rag-fusion-smoke"), EmbeddingOptionsBuilder.builder().build()));
        assertTrue(result.vector() != null && result.vector().length > 0, "embed() 必须返回非空向量");
        assertTrue(result.hasUsage(), "embed() 必须带 usage，实际=" + result);
    }

    /**
     * 构造真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream/vision）。
     *
     * @return 可直接调用的实现
     */
    private ModelProvider buildProvider() {
        String apiKey = resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY（env 或 .env），跳过真机验证");

        // 1) DashScope 原生协议客户端（默认 baseUrl 即原生端点，不与 compatible-mode 混用）
        DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
        ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);

        // 2) 用 MockEnvironment 提供 compatible-mode 端点与 key（与 application.yml 的回退逻辑一致）
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", apiKey);
        environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);

        ModelProviderProperties properties = new ModelProviderProperties();
        return new ModelProviderImpl(provider(nativeChat), provider(embeddingModel), properties, environment);
    }

    /**
     * 读取 key：优先环境变量，其次仓库根目录 .env（本地冒烟常用）。
     *
     * @return api key；取不到返回 null
     */
    private String resolveApiKey() {
        String fromEnv = System.getenv("DASHSCOPE_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path dotEnv = Path.of(".env");
        if (Files.exists(dotEnv)) {
            try {
                return Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
                        .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
                        .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
                        .findFirst()
                        .orElse(null);
            } catch (Exception ignored) {
                // 读不到就走“跳过”分支
            }
        }
        return null;
    }

    /**
     * 最小 ObjectProvider 包装（测试里没有容器，只有固定实例）。
     *
     * @param value 固定返回值
     * @param <T>   bean 类型
     * @return 只返回该实例的 provider
     */
    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
