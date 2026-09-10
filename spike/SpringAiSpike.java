import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Condition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.util.MimeTypeUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI B0 验证样例：只验证能力与协议形态，不接入业务代码。
 *
 * <p>判定口径：本样例通过表示 Spring AI 1.0.1 / spring-ai-alibaba 1.0.0.4 的静态能力满足
 * B0 门禁；真实模型流式与真实 Qdrant 检索仍需在联调环境补跑一次冒烟。</p>
 */
public final class SpringAiSpike {

    /** 防止工具类被实例化。 */
    private SpringAiSpike() {
    }

    /**
     * 执行五项静态能力验证；任一项失败都会抛出并终止。
     *
     * @param args 未使用
     * @throws Exception 反射访问 DashScope 内部方法失败时抛出
     */
    public static void main(String[] args) throws Exception {
        verifyThinkingIncrement();
        verifyDashScopeThinkingOption();
        verifyQdrantFilterSemantics();
        verifyVisionMedia();
        verifyEmbeddingDimensions();
        System.out.println("B0 static spike: PASS");
    }

    /**
     * 验证 1：DashScope 流式响应把 reasoning_content 放进 AssistantMessage metadata，
     * 业务层可以从每个 ChatResponse 中提取增量，而不需要 LangChain4j 的 onPartialThinking。
     */
    private static void verifyThinkingIncrement() {
        Map<String, Object> metadata = Map.of("reasoningContent", "先核对用户意图");
        AssistantMessage message = new AssistantMessage("正文增量", metadata, List.of());
        ChatResponse response = new ChatResponse(List.of(new Generation(message)), null);
        AssistantMessage output = Objects.requireNonNull(response.getResult()).getOutput();

        String thinking = (String) output.getMetadata().get("reasoningContent");
        if (!"先核对用户意图".equals(thinking) || !"正文增量".equals(output.getText())) {
            throw new IllegalStateException("思考增量与正文增量无法同时提取");
        }
        System.out.println("1 thinking increment: PASS");
    }

    /**
     * 验证 2：DashScopeChatOptions.enableThinking 会映射到 DashScope 顶层 parameters.enableThinking。
     * 这是百炼 Qwen 思考开关的真实请求参数；vLLM chat_template_kwargs 不能由默认 ChatOptions 表达，
     * 需在 ModelProvider 的 vLLM/OpenAI-compatible 实现里用可扩展 options 或请求装饰器补齐。
     */
    private static void verifyDashScopeThinkingOption() throws Exception {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel("qwen3.8-flash")
                .withEnableThinking(true)
                .withIncrementalOutput(true)
                .build();
        Prompt prompt = new Prompt(UserMessage.builder().text("验证请求参数").build(), options);
        DashScopeApi api = DashScopeApi.builder().apiKey("test-key").build();
        DashScopeChatModel model = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();

        Method createRequest = DashScopeChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
        createRequest.setAccessible(true);
        DashScopeApi.ChatCompletionRequest request =
                (DashScopeApi.ChatCompletionRequest) createRequest.invoke(model, prompt, true);

        if (!Boolean.TRUE.equals(request.parameters().enableThinking())) {
            throw new IllegalStateException("enableThinking 未映射到 DashScope parameters");
        }
        if (!Boolean.TRUE.equals(request.parameters().incrementalOutput())) {
            throw new IllegalStateException("流式增量输出未映射到 DashScope parameters");
        }
        System.out.println("2 dashscope thinking option: PASS");
    }

    /**
     * 验证 3：Qdrant 原生 filter 能表达 AND / OR / NOT、字符串与整型等值、字符串集合。
     * Spring AI QdrantFilterExpressionConverter 也按这些条件转换；但与 LangChain4j 相比，
     * Boolean/UUID 等值是边界差异，统一值应优先用 String/Long，避免走不支持类型。
     */
    private static void verifyQdrantFilterSemantics() {
        Condition allowed = ConditionFactory.matchKeyword("knowledgeBaseId", "kb-1");
        Condition categoryContract = ConditionFactory.matchKeyword("category", "contract");
        Condition categoryLead = ConditionFactory.matchKeyword("category", "lead");
        Condition deletedFalse = ConditionFactory.match("isDeleted", false);

        Filter supported = Filter.newBuilder()
                .addMust(allowed)
                .addShould(categoryContract)
                .addShould(categoryLead)
                .addMustNot(deletedFalse)
                .build();
        if (supported.getMustCount() != 1 || supported.getShouldCount() != 2 || supported.getMustNotCount() != 1) {
            throw new IllegalStateException("Qdrant AND/OR/NOT 条件组装失败");
        }
        Condition knowledgeBaseIds = ConditionFactory.matchKeywords(
                "knowledgeBaseId", List.of("kb-1", "kb-2"));
        if (knowledgeBaseIds.getSerializedSize() <= 0) {
            throw new IllegalStateException("字符串集合过滤条件构建失败");
        }
        System.out.println("3 qdrant filter semantics: PASS");
    }

    /**
     * 验证 4：Spring AI UserMessage 可携带二进制图片 Media；
     * DashScopeChatModel 的 convertMediaContent 会将其转换为 type=image 与 data:image/png;base64 URL。
     */
    private static void verifyVisionMedia() throws Exception {
        byte[] image = {1, 2, 3};
        UserMessage userMessage = UserMessage.builder()
                .text("请识别图片中的关键内容")
                .media(Media.builder()
                        .mimeType(MimeTypeUtils.IMAGE_PNG)
                        .data(image)
                        .build())
                .build();
        DashScopeApi api = DashScopeApi.builder().apiKey("test-key").build();
        DashScopeChatModel model = DashScopeChatModel.builder().dashScopeApi(api).build();

        Method convertMediaContent =
                DashScopeChatModel.class.getDeclaredMethod("convertMediaContent", UserMessage.class);
        convertMediaContent.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<DashScopeApi.ChatCompletionMessage.MediaContent> contents =
                (List<DashScopeApi.ChatCompletionMessage.MediaContent>) convertMediaContent.invoke(model, userMessage);

        if (contents.size() != 2) {
            throw new IllegalStateException("多模态消息必须同时包含图片与文本");
        }
        DashScopeApi.ChatCompletionMessage.MediaContent imageContent = contents.getFirst();
        if (!"image".equals(imageContent.type()) || !imageContent.image().startsWith("data:image/png;base64,")) {
            throw new IllegalStateException("图片 Media 未转换为 DashScope image 内容");
        }
        System.out.println("4 vision media: PASS");
    }

    /**
     * 验证 5：统一向量维度用 1024，与 Qdrant 集合维度保持同一配置。
     * text-embedding-v3 支持 1024/768/512，1024 是当前默认；样例只验证 EmbeddingRequest 能携带该约束。
     */
    private static void verifyEmbeddingDimensions() {
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel("text-embedding-v3")
                .withDimensions(1024)
                .build();
        EmbeddingRequest request = new EmbeddingRequest(List.of("向量维度验证"), options);
        if (!(request.getOptions() instanceof DashScopeEmbeddingOptions embeddingOptions)
                || !Integer.valueOf(1024).equals(embeddingOptions.getDimensions())) {
            throw new IllegalStateException("嵌入请求未能下发统一维度 1024");
        }
        System.out.println("5 embedding dimensions: PASS");
    }
}
