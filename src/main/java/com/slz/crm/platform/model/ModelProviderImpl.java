package com.slz.crm.platform.model;

import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * {@link ModelProvider} 的 Spring AI DashScope 实现（contracts-frozen.md §1，base 归属）。
 *
 * <p>路由策略：</p>
 * <ul>
 *   <li><b>非流式对话 {@link #chat(Prompt)}</b>：优先 DashScope 原生协议
 *       （{@code DashScopeChatModel}，usage 在非流式响应里稳定）；未装配时回退 compatible-mode；</li>
 *   <li><b>流式 {@link #streamChat(Prompt)}</b>：<b>恒走 compatible-mode SSE 适配器</b>
 *       （{@code OpenAiChatModel} + {@code streamUsage(true)}）。原因：DashScope 原生流式协议
 *       的 usage 在 chunk 里不可靠，而 compatible-mode 是 OpenAI SSE 语义，
 *       可用 {@code stream_usage} 让最后一个 chunk 携带 usage——这正是 §1 要求
 *       “勿只依赖 {@code DashScopeChatModel.stream()}”的原因；</li>
 *   <li><b>向量 {@link #embed(EmbeddingRequest)}</b>：DashScope EmbeddingModel（必填，缺失即失败）；</li>
 *   <li><b>视觉 {@link #vision(Prompt)}</b>：compatible-mode + 强制 vision 模型
 *       （qwen-vl 系列在 compatible-mode 走 OpenAI 多模态 content，最稳定）。</li>
 * </ul>
 *
 * <p>usage 硬约束：三个非流式入口都必须返回 {@link ModelCallResult}；
 * 流式入口由实现强制 {@code streamUsage(true)}，调用方仍须在流结束时上报
 * {@code TokenUsageRecorder}（Lane D 聚合）。</p>
 *
 * <p>线程安全：本类无可变状态（compatible 客户端在构造期建好且线程安全），可单例并发使用。</p>
 */
@Component
public class ModelProviderImpl implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderImpl.class);

    /**
     * OpenAI 兼容端点的 completions 路径。
     * DashScope compatible-mode 的 base-url 已含 {@code /compatible-mode/v1}，
     * 因此这里必须去掉 Spring AI 默认的 {@code /v1} 前缀，否则会拼出错误 URL。
     */
    private static final String COMPATIBLE_COMPLETIONS_PATH = "/chat/completions";

    private final ObjectProvider<ChatModel> dashScopeChatModel;
    private final ObjectProvider<EmbeddingModel> dashScopeEmbeddingModel;
    private final ModelProviderProperties properties;
    private final Environment environment;
    private final OpenAiChatModel compatibleChatModel;

    /**
     * 构造并预建 compatible-mode 客户端（SSE 适配器）。
     *
     * @param dashScopeChatModel      DashScope 原生对话模型（starter 自动装配，允许缺失）
     * @param dashScopeEmbeddingModel DashScope 嵌入模型（缺失时 embed 调用期失败）
     * @param properties              provider/模型名/端点配置
     * @param environment             用于读取 {@code spring.ai.dashscope.*} 兜底 key/base-url
     */
    public ModelProviderImpl(ObjectProvider<ChatModel> dashScopeChatModel,
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
        if (log.isDebugEnabled()) {
            log.debug("chat() 使用 {} 协议", nativeModel != null ? "dashscope" : "compatible-mode");
        }
        return toTextResult(model.call(prompt), properties.getChatModel());
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
        // 强制 streamUsage=true：调用方自带 options 时也不能丢 usage（§1 的核心诉求）
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
        float[] vector = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput() : new float[0];
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        return ModelCallResult.ofVector(vector, properties.getEmbeddingModel(),
                toLong(usage == null ? null : usage.getPromptTokens()),
                toLong(usage == null ? null : usage.getTotalTokens()));
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
        // vision 恒走 compatible-mode：qwen-vl 在 OpenAI 多模态 content 下行为最稳定，
        // 且不需要复制 DashScope 原生 options（避免丢温度/长度等参数时语义含糊）
        Prompt forced = withModel(prompt, properties.getVisionModel());
        return toTextResult(compatibleChatModel.call(forced), properties.getVisionModel());
    }

    /**
     * 构建 compatible-mode（OpenAI 协议）SSE 客户端。
     *
     * @return 预配置好的 OpenAI 兼容对话模型
     */
    private OpenAiChatModel buildCompatibleChatModel() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(resolveBaseUrl())
                .apiKey(resolveApiKey())
                // base-url 已含 /compatible-mode/v1，故必须去掉 Spring AI 默认的 /v1 前缀
                .completionsPath(COMPATIBLE_COMPLETIONS_PATH)
                .build();
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(properties.getChatModel())
                .streamUsage(true)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                .build();
    }

    /**
     * 复制 Prompt 并强制 {@code streamUsage=true}。
     *
     * @param prompt 调用方原始 Prompt
     * @return 带流式 usage 开关的 Prompt
     */
    private Prompt withStreamUsage(Prompt prompt) {
        OpenAiChatOptions options = prompt.getOptions() instanceof OpenAiChatOptions existing
                ? OpenAiChatOptions.fromOptions(existing)
                : OpenAiChatOptions.builder().model(properties.getChatModel()).build();
        options.setStreamUsage(true);
        return new Prompt(prompt.getInstructions(), options);
    }

    /**
     * 复制 Prompt 并强制使用指定模型（vision 场景防误用纯文本模型）。
     *
     * @param prompt 调用方原始 Prompt
     * @param model  必须使用的模型名
     * @return 指定模型后的 Prompt
     */
    private Prompt withModel(Prompt prompt, String model) {
        OpenAiChatOptions options = prompt.getOptions() instanceof OpenAiChatOptions existing
                ? OpenAiChatOptions.fromOptions(existing)
                : OpenAiChatOptions.builder().build();
        options.setModel(model);
        return new Prompt(prompt.getInstructions(), options);
    }

    /**
     * 统一抽取文本与 usage（不允许任何入口漏掉计量）。
     *
     * @param response       模型响应
     * @param fallbackModel  响应里没带模型名时使用的兜底模型名
     * @return 携带 usage 的结果
     */
    private ModelCallResult<String> toTextResult(ChatResponse response, String fallbackModel) {
        String text = extractText(response);
        ChatResponseMetadata metadata = response.getMetadata();
        String model = (metadata != null && StringUtils.hasText(metadata.getModel()))
                ? metadata.getModel() : fallbackModel;
        Usage usage = metadata == null ? null : metadata.getUsage();
        return ModelCallResult.ofText(text, model,
                toLong(usage == null ? null : usage.getPromptTokens()),
                toLong(usage == null ? null : usage.getCompletionTokens()),
                toLong(usage == null ? null : usage.getTotalTokens()));
    }

    /**
     * @param response 模型响应
     * @return 首个候选输出文本；空响应返回空串（不返回 null，方便上层拼接）
     */
    private String extractText(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        AssistantMessage output = response.getResult().getOutput();
        return output.getText() == null ? "" : output.getText();
    }

    /**
     * Integer → Long 的安全转换（Spring AI Usage 用 Integer，计量记录用 Long）。
     *
     * @param value 可能为 null 的 token 数
     * @return Long 表示；null 透传
     */
    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /**
     * @return compatible-mode 端点 base-url；未配置时回退 spring.ai.dashscope.base-url
     */
    private String resolveBaseUrl() {
        String configured = properties.getBaseUrl();
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        String dashscope = environment.getProperty("spring.ai.dashscope.base-url", "");
        if (!StringUtils.hasText(dashscope)) {
            throw new IllegalStateException(
                    "compatible-mode base-url 未配置（platform.ai.model.base-url / spring.ai.dashscope.base-url）");
        }
        return dashscope;
    }

    /**
     * @return compatible-mode 端点 api-key；未配置时回退 spring.ai.dashscope.api-key
     */
    private String resolveApiKey() {
        String configured = properties.getApiKey();
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        String dashscope = environment.getProperty("spring.ai.dashscope.api-key", "");
        if (!StringUtils.hasText(dashscope)) {
            // 启动不失败（保持“无 key 也能起服务”的现状），调用期才失败并给出明确指引
            log.warn("compatible-mode api-key 未配置：chat/stream/vision 调用将在运行期失败");
            return "missing-api-key";
        }
        return dashscope;
    }
}
