package com.slz.crm.server.ai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        StringBuilder answer = activeStream.getPartialAnswer();
        ChatClient.ChatClientRequestSpec promptSpec = chatClientBuilder.build()
                .prompt()
                .messages(context.messages());
        if (context.modelOverride() != null) {
            promptSpec = promptSpec.options(
                    DashScopeChatOptions.builder().withModel(context.modelOverride()).build());
        }
        if (!context.toolCallbacks().isEmpty()) {
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put("sessionId", sessionId);
            toolContext.put("repairCounter", context.repairCounter());
            toolContext.put("references", context.referenceCollector());
            toolContext.put("user", context.currentUser());
            promptSpec = promptSpec.toolCallbacks(context.toolCallbacks())
                    .toolContext(toolContext);
        }

        String effectiveModel = context.effectiveModel(modelName);
        Usage[] usageHolder = new Usage[1];
        Disposable subscription = promptSpec.stream()
                .chatResponse()
                .timeout(Duration.ofSeconds(
                        aiProperties.getLlmTimeoutSeconds() == null ? 60 : aiProperties.getLlmTimeoutSeconds()))
                .doOnNext(response -> handleChatChunk(activeStream, response, usageHolder))
                .doOnComplete(() -> {
                    if (shouldAbort(activeStream)) {
                        finishSuperseded(activeStream);
                        return;
                    }
                    if (activeStream.tryMarkFinished()) {
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
                                 Usage[] usageHolder) {
        if (shouldAbort(activeStream)) {
            finishSuperseded(activeStream);
            return;
        }
        if (chatResponse == null || chatResponse.getResult() == null) {
            return;
        }
        if (chatResponse.getResult().getOutput() != null) {
            String text = chatResponse.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                if (activeStream.markFirstToken()) {
                    AiChatStreamContext streamContext = activeStream.getContext();
                    metrics.recordFirstToken(activeStream, streamContext.effectiveModel(modelName),
                            streamContext.fallback());
                }
                activeStream.getPartialAnswer().append(text);
                if (!sendBufferedEvent(activeStream, "delta", eventWriter.toDeltaJson(text))) {
                    return;
                }
            }
        }
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
                && chatResponse.getMetadata().getUsage().getTotalTokens() > 0) {
            usageHolder[0] = chatResponse.getMetadata().getUsage();
        }
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
