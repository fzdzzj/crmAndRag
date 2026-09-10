package com.slz.crm.server.ai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 助手 SSE 生命周期。
 *
 * <p>负责模型流订阅、事件缓冲、协作式中止与助手消息终态落库。</p>
 */
@Slf4j
@Component
public class AiChatStreamLifecycle {

    private final ChatClient.Builder chatClientBuilder;
    private final AiProperties aiProperties;
    private final AiMessageService aiMessageService;
    private final AiStreamRegistry aiStreamRegistry;
    private final AiChatPromptService promptService;
    private final AiChatSseEventWriter eventWriter;
    private final AiChatStreamHeartbeat heartbeat;
    private final AiChatMetrics metrics;
    private final AiAssistantMessageStore assistantMessageStore;
    private final String modelName;

    /** ModelProvider 实现归 base；缺失时保留 CRM 原有 ChatClient 兼容路径。 */
    @Autowired(required = false)
    private ObjectProvider<ModelProvider> modelProviderProvider;

    /** 记忆加工是可选增强；测试或旁路组件缺失时不得影响主答。 */
    @Autowired(required = false)
    private AiMemoryOrchestrator memoryOrchestrator;

    public AiChatStreamLifecycle(ChatClient.Builder chatClientBuilder,
                                 AiProperties aiProperties,
                                 AiMessageService aiMessageService,
                                 AiStreamRegistry aiStreamRegistry,
                                 AiChatPromptService promptService,
                                 AiChatSseEventWriter eventWriter,
                                 AiChatStreamHeartbeat heartbeat,
                                 AiChatMetrics metrics,
                                 AiAssistantMessageStore assistantMessageStore,
                                 @Value("${spring.ai.chat.options.model:qwen-plus}") String modelName) {
        this.chatClientBuilder = chatClientBuilder;
        this.aiProperties = aiProperties;
        this.aiMessageService = aiMessageService;
        this.aiStreamRegistry = aiStreamRegistry;
        this.promptService = promptService;
        this.eventWriter = eventWriter;
        this.heartbeat = heartbeat;
        this.metrics = metrics;
        this.assistantMessageStore = assistantMessageStore;
        this.modelName = modelName;
    }

    /**
     * 订阅模型流。
     *
     * @param activeStream 当前活跃流
     * @param context 请求上下文
     */
    public void subscribe(AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context) {
        if (activeStream.isFinished()) {
            return;
        }
        activeStream.setContext(context);
        Long sessionId = context.sessionId();
        String effectiveModel = context.effectiveModel(modelName);
        DashScopeChatOptions options = buildOptions(context, sessionId, effectiveModel);
        AiThinkTagStripper.StreamingStripper thinkStripper = AiThinkTagStripper.streaming();
        AtomicBoolean thinkingFinished = new AtomicBoolean(false);
        Usage[] usageHolder = new Usage[1];
        ModelProvider modelProvider = modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
        Flux<ChatResponse> responseFlux;
        if (modelProvider != null) {
            responseFlux = modelProvider.streamChat(new Prompt(context.messages(), options));
        } else {
            ChatClient.ChatClientRequestSpec requestSpec = chatClientBuilder.build()
                    .prompt()
                    .messages(context.messages());
            // 兼容路径仅在思考或模型覆盖时注入 DashScope 参数，保持旧调用桩的链式行为。
            if (context.thinking() || context.modelOverride() != null) {
                requestSpec = requestSpec.options(options);
            }
            if (!context.toolCallbacks().isEmpty()) {
                requestSpec = requestSpec
                        .toolCallbacks(context.toolCallbacks())
                        .toolContext(buildToolContext(context, sessionId));
            }
            responseFlux = requestSpec.stream().chatResponse();
        }
        Disposable subscription = responseFlux
                .timeout(Duration.ofSeconds(
                        aiProperties.getLlmTimeoutSeconds() == null ? 60 : aiProperties.getLlmTimeoutSeconds()))
                .doOnNext(response -> handleChatChunk(activeStream, response, usageHolder, context.thinking(),
                        thinkStripper, thinkingFinished))
                .doOnComplete(() -> {
                    if (shouldAbort(activeStream)) {
                        finishSuperseded(activeStream);
                        return;
                    }
                    if (activeStream.tryMarkFinished()) {
                        if (context.thinking() && thinkingFinished.compareAndSet(false, true)
                                && !sendBufferedEvent(activeStream, "thinking",
                                        eventWriter.toThinkingJson("", true))) {
                            return;
                        }
                        List<AiReferenceCollector.Reference> references = context.referenceCollector().getReferences();
                        if (!references.isEmpty() && !sendBufferedEvent(activeStream, "references",
                                eventWriter.toReferencesJson(references))) {
                            return;
                        }
                        Usage usage = usageHolder[0];
                        String auditPayload = eventWriter.toAuditJson(effectiveModel, promptService.getPromptVersion(),
                                System.currentTimeMillis() - context.roundStart(), usage, references);
                        String content = activeStream.getPartialAnswer().toString();
                        persistAssistantMessage(activeStream, content, auditPayload, usage, false);
                        if (memoryOrchestrator != null && !content.isBlank()) {
                            memoryOrchestrator.onRoundCompleted(sessionId,
                                    context.currentUser() == null ? null : context.currentUser().getId(),
                                    lastUserMessage(context.messages()), content);
                        }
                        if (sendBufferedEvent(activeStream, "done", eventWriter.toDoneJson(
                                String.valueOf(sessionId), usage))) {
                            metrics.recordCompleted(activeStream, effectiveModel, context.fallback());
                            completeEmitter(activeStream);
                        }
                    }
                    aiStreamRegistry.remove(sessionId, activeStream);
                })
                .doOnError(error -> handleStreamError(activeStream, effectiveModel, error))
                .doOnCancel(() -> {
                    if (activeStream.tryMarkFinished()) {
                        metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
                        persistAssistantMessage(activeStream, activeStream.getPartialAnswer().toString(),
                                "{\"interrupted\":true}", null, true);
                    }
                    aiStreamRegistry.remove(sessionId, activeStream);
                })
                .subscribe();
        activeStream.setSubscription(subscription);
        heartbeat.start(activeStream, () -> sendHeartbeat(activeStream));
    }

    private String lastUserMessage(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof UserMessage userMessage && userMessage.getText() != null) {
                return userMessage.getText();
            }
        }
        return "";
    }

    /**
     * 装配 DashScope 请求参数；思考开关是真实请求参数而非 prompt 约束。
     */
    private DashScopeChatOptions buildOptions(AiChatStreamContext context, Long sessionId, String effectiveModel) {
        DashScopeChatOptions.DashscopeChatOptionsBuilder builder = DashScopeChatOptions.builder()
                .withModel(effectiveModel)
                .withEnableThinking(context.thinking());
        if (modelProviderProvider != null && modelProviderProvider.getIfAvailable() != null
                && !context.toolCallbacks().isEmpty()) {
            builder.withToolCallbacks(context.toolCallbacks()).withToolContext(buildToolContext(context, sessionId));
        }
        return builder.build();
    }

    private Map<String, Object> buildToolContext(AiChatStreamContext context, Long sessionId) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put("sessionId", sessionId);
        toolContext.put("repairCounter", context.repairCounter());
        toolContext.put("references", context.referenceCollector());
        toolContext.put("user", context.currentUser());
        return toolContext;
    }

    /**
     * 处理模型流错误，必要时降级备用模型。
     */
    public void handleStreamError(AiStreamRegistry.ActiveStream activeStream, String effectiveModel,
                                  Throwable error) {
        Long sessionId = activeStream.getSessionId();
        log.error("AI chat stream error, sessionId={}, model={}", sessionId, effectiveModel, error);
        String fallbackModel = aiProperties.getFallbackModel();
        if (shouldAbort(activeStream)) {
            finishSuperseded(activeStream);
            return;
        }
        if (scheduleConnectionRetry(activeStream, error)) {
            return;
        }
        if (!activeStream.isFinished()
                && !activeStream.getContext().fallback()
                && activeStream.getContext().modelOverride() == null
                && activeStream.getPartialAnswer().length() == 0
                && fallbackModel != null
                && !fallbackModel.isBlank()) {
            log.warn("主模型失败，切换备用模型: {}, sessionId={}", fallbackModel, sessionId);
            subscribe(activeStream, activeStream.getContext().forFallback(fallbackModel));
            return;
        }

        if (activeStream.tryMarkFinished()) {
            metrics.recordFailed(activeStream, effectiveModel, activeStream.getContext().fallback());
            String answer = activeStream.getPartialAnswer().toString();
            if (!answer.isBlank()) {
                persistAssistantMessage(activeStream, answer, "{\"interrupted\":true}", null, true);
                sendBufferedEvent(activeStream, "done", eventWriter.toDoneJson(String.valueOf(sessionId), null));
            } else {
                String staticMessage = aiProperties.getStaticFallbackMessage() == null
                        ? "AI 服务暂时不可用，请稍后再试" : aiProperties.getStaticFallbackMessage();
                persistAssistantMessage(activeStream, staticMessage, "{\"fallback\":true}", null, false);
                if (sendBufferedEvent(activeStream, "delta", eventWriter.toDeltaJson(staticMessage))) {
                    sendBufferedEvent(activeStream, "done", eventWriter.toDoneJson(String.valueOf(sessionId), null));
                }
            }
            completeEmitter(activeStream);
        }
        aiStreamRegistry.remove(sessionId, activeStream);
    }

    /**
     * 零输出且连接型异常时同模型重试，最多 2 次；有部分内容后不再重试，避免重复回答。
     */
    private boolean scheduleConnectionRetry(AiStreamRegistry.ActiveStream activeStream, Throwable error) {
        AiChatStreamContext context = activeStream.getContext();
        if (activeStream.isFinished()
                || activeStream.getPartialAnswer().length() > 0
                || context == null
                || context.connectionRetry() >= 2
                || !isRetryableConnectionError(error)) {
            return false;
        }
        long baseDelay = aiProperties.getConnectionRetryBaseDelayMillis() == null
                ? 500L : aiProperties.getConnectionRetryBaseDelayMillis();
        // 第一次重试 500ms，第二次 1000ms；使用共享调度器，不新建业务线程池。
        long delayMillis = baseDelay * (1L << context.connectionRetry());
        log.warn("模型零输出且连接型失败，安排同模型重试: sessionId={}, retry={}, delayMs={}",
                activeStream.getSessionId(), context.connectionRetry() + 1, delayMillis);
        Schedulers.parallel().schedule(() -> {
            if (shouldAbort(activeStream)) {
                finishSuperseded(activeStream);
                return;
            }
            subscribe(activeStream, activeStream.getContext().forConnectionRetry());
        }, delayMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean isRetryableConnectionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.contains("WebClientRequestException") || className.contains("ConnectException")) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("connection reset") || message.contains("connection refused")
                    || message.contains("read timed out") || message.contains("connection prematurely closed")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * 用户取消或接管旧流。
     *
     * @return 是否执行了取消
     */
    public boolean cancel(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
        if (activeStream == null || activeStream.isFinished()) {
            return false;
        }
        if (activeStream.tryMarkFinished()) {
            metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
            String partial = activeStream.getPartialAnswer().toString();
            persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
        }
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            // 终态 CAS 已占位，dispose 触发的 doOnCancel 不会重复保存。
            subscription.dispose();
        }
        sendBufferedEvent(activeStream, "stopped", eventWriter.toStoppedJson("CANCELLED"));
        completeEmitter(activeStream);
        aiStreamRegistry.remove(sessionId, activeStream);
        return true;
    }

    /**
     * 客户端断开或超时兜底清理。
     */
    public void cleanup(AiStreamRegistry.ActiveStream activeStream, String reason) {
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (activeStream.tryMarkFinished()) {
            metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
            String partial = activeStream.getPartialAnswer().toString();
            persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
            log.info("SSE 连接断开兜底清理, sessionId={}, reason={}", activeStream.getSessionId(), reason);
        }
        aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
    }

    /**
     * 处理一次模型响应块。
     */
    private void handleChatChunk(AiStreamRegistry.ActiveStream activeStream, ChatResponse chatResponse,
                                 Usage[] usageHolder, boolean thinking,
                                 AiThinkTagStripper.StreamingStripper thinkStripper,
                                 AtomicBoolean thinkingFinished) {
        if (shouldAbort(activeStream)) {
            finishSuperseded(activeStream);
            return;
        }
        if (chatResponse == null || chatResponse.getResult() == null) {
            return;
        }
        if (chatResponse.getResult().getOutput() != null) {
            if (thinking) {
                String reasoningContent = extractThinking(chatResponse.getResult().getOutput().getMetadata());
                if (reasoningContent != null && !reasoningContent.isBlank()
                        && !sendBufferedEvent(activeStream, "thinking",
                                eventWriter.toThinkingJson(reasoningContent, false))) {
                    return;
                }
            }
            String rawText = chatResponse.getResult().getOutput().getText();
            String visibleText = rawText == null ? "" : thinkStripper.filter(rawText, thinking
                    ? piece -> sendBufferedEvent(activeStream, "thinking",
                            eventWriter.toThinkingJson(piece, false))
                    : null);
            if (!visibleText.isEmpty() && thinking && thinkingFinished.compareAndSet(false, true)
                    && !sendBufferedEvent(activeStream, "thinking",
                            eventWriter.toThinkingJson("", true))) {
                return;
            }
            if (!visibleText.isEmpty()) {
                if (activeStream.markFirstToken()) {
                    AiChatStreamContext streamContext = activeStream.getContext();
                    metrics.recordFirstToken(activeStream, streamContext.effectiveModel(modelName),
                            streamContext.fallback());
                }
                activeStream.getPartialAnswer().append(visibleText);
                if (!sendBufferedEvent(activeStream, "delta", eventWriter.toDeltaJson(visibleText))) {
                    return;
                }
            }
        }
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
                && chatResponse.getMetadata().getUsage().getTotalTokens() > 0) {
            usageHolder[0] = chatResponse.getMetadata().getUsage();
        }
    }

    /**
     * DashScope 会把 reasoning_content 放入 AssistantMessage metadata；键名做兼容读取。
     */
    private String extractThinking(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        for (String key : List.of("reasoningContent", "reasoning_content", "thinking")) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private void finishSuperseded(AiStreamRegistry.ActiveStream activeStream) {
        if (activeStream.tryMarkFinished()) {
            metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
            String partial = activeStream.getPartialAnswer().toString();
            persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
            Disposable subscription = activeStream.getSubscription();
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
            sendBufferedEvent(activeStream, "stopped",
                    eventWriter.toStoppedJson("SUPERSEDED_BY_NEW_REQUEST"));
            completeEmitter(activeStream);
        }
        aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
    }

    private void persistAssistantMessage(AiStreamRegistry.ActiveStream activeStream, String content,
                                         String payload, Usage usage, boolean deleteIfEmpty) {
        Long messageId = activeStream.getAssistantMessageId();
        boolean updated = assistantMessageStore.complete(messageId, content, payload,
                usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
        if (!updated) {
            log.error("AI assistant message save failed, sessionId={}, messageId={}",
                    activeStream.getSessionId(), messageId);
            return;
        }
        if (deleteIfEmpty && (content == null || content.isBlank())) {
            assistantMessageStore.deleteIfEmpty(messageId);
        }
    }

    private boolean shouldAbort(AiStreamRegistry.ActiveStream activeStream) {
        return activeStream.isCancelled()
                || activeStream.isFinished()
                || aiStreamRegistry.get(activeStream.getSessionId()) != activeStream;
    }

    private boolean sendBufferedEvent(AiStreamRegistry.ActiveStream activeStream, String event, String data) {
        boolean sent = eventWriter.sendBufferedEvent(activeStream, event, data);
        if (!sent) {
            cleanupSendFailure(activeStream);
        }
        return sent;
    }

    void sendHeartbeat(AiStreamRegistry.ActiveStream activeStream) {
        if (activeStream.isFinished()) {
            return;
        }
        metrics.recordHeartbeat();
        if (!eventWriter.sendHeartbeat(activeStream.getEmitter())) {
            cleanupSendFailure(activeStream);
        }
    }

    private void cleanupSendFailure(AiStreamRegistry.ActiveStream activeStream) {
        if (activeStream.tryMarkFinished()) {
            metrics.recordFailed(activeStream, streamModel(activeStream), streamFallback(activeStream));
            log.info("SSE 发送失败，停止模型流, sessionId={}", activeStream.getSessionId());
        }
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
    }

    private void completeEmitter(AiStreamRegistry.ActiveStream activeStream) {
        try {
            activeStream.getEmitter().complete();
        } catch (RuntimeException exception) {
            log.warn("SSE complete failed, sessionId={}", activeStream.getSessionId(), exception);
        }
    }

    private String streamModel(AiStreamRegistry.ActiveStream activeStream) {
        AiChatStreamContext context = activeStream.getContext();
        return context == null ? modelName : context.effectiveModel(modelName);
    }

    private boolean streamFallback(AiStreamRegistry.ActiveStream activeStream) {
        AiChatStreamContext context = activeStream.getContext();
        return context != null && context.fallback();
    }
}
