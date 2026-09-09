package com.slz.crm.server.ai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.slz.crm.pojo.entity.AiMessageEntity;
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

    /** AI 流式与工具运行指标 */
    private final AiChatMetrics metrics;
    private final String modelName;

    public AiChatStreamLifecycle(ChatClient.Builder chatClientBuilder,
                                 AiProperties aiProperties,
                                 AiMessageService aiMessageService,
                                 AiStreamRegistry aiStreamRegistry,
                                 AiChatPromptService promptService,
                                 AiChatSseEventWriter eventWriter,
                                 AiChatStreamHeartbeat heartbeat,
                                 AiChatMetrics metrics,
                                 @Value("${spring.ai.chat.options.model:qwen-plus}") String modelName) {
        this.chatClientBuilder = chatClientBuilder;
        this.aiProperties = aiProperties;
        this.aiMessageService = aiMessageService;
        this.aiStreamRegistry = aiStreamRegistry;
        this.promptService = promptService;
        this.eventWriter = eventWriter;
        this.heartbeat = heartbeat;
        this.metrics = metrics;
        this.modelName = modelName;
    }

    public void subscribe(AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context) {
        if (activeStream.isFinished()) {
            return;
        }
        activeStream.setContext(context);
        Long sessionId = context.sessionId();
        SseEmitter emitter = context.emitter();
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
                    if (activeStream.tryMarkFinished()) {
                        List<AiReferenceCollector.Reference> references = context.referenceCollector().getReferences();
                        if (!references.isEmpty()) {
                            if (!sendEvent(activeStream, "references", eventWriter.toReferencesJson(references))) {
                                return;
                            }
                        }
                        String auditPayload = eventWriter.toAuditJson(effectiveModel, promptService.getPromptVersion(),
                                System.currentTimeMillis() - context.roundStart(), usageHolder[0], references);
                        saveAssistantMessage(sessionId, answer.toString(), auditPayload, usageHolder[0]);
                        if (sendEvent(activeStream, "done", eventWriter.toDoneJson())) {
                            // done 事件写入成功才计为完成；写入失败由 cleanupSendFailure 计 failed
                            metrics.recordCompleted(activeStream, effectiveModel, context.fallback());
                            try {
                                emitter.complete();
                            } catch (RuntimeException exception) {
                                log.warn("SSE complete failed, sessionId={}", sessionId, exception);
                            }
                        }
                    }
                    aiStreamRegistry.remove(sessionId, activeStream);
                })
                .doOnError(e -> handleStreamError(activeStream, effectiveModel, e))
                .doOnCancel(() -> {
                    if (activeStream.tryMarkFinished()) {
                        // 停止或生成中发新消息会 dispose 订阅；已有部分回答需要保留到会话
                        metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
                        String partial = answer.toString();
                        if (!partial.isEmpty()) {
                            saveAssistantMessage(sessionId, partial, "{\"interrupted\":true}");
                        }
                    }
                    aiStreamRegistry.remove(sessionId, activeStream);
                })
                .subscribe();
        activeStream.setSubscription(subscription);
        heartbeat.start(activeStream, () -> sendHeartbeat(activeStream));
    }

    public void handleStreamError(AiStreamRegistry.ActiveStream activeStream, String effectiveModel,
                                  Throwable error) {
        Long sessionId = activeStream.getSessionId();
        log.error("AI chat stream error, sessionId={}, model={}", sessionId, effectiveModel, error);
        String fallbackModel = aiProperties.getFallbackModel();
        if (activeStream.isFinished()) {
            aiStreamRegistry.remove(sessionId, activeStream);
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
            // 主备模型最终失败时进入这里；备用模型切换路径不计 failed
            metrics.recordFailed(activeStream, effectiveModel, activeStream.getContext().fallback());
            StringBuilder answer = activeStream.getPartialAnswer();
            if (answer.length() > 0) {
                saveAssistantMessage(sessionId, answer.toString(), "{\"interrupted\":true}");
                sendEvent(activeStream, "done", eventWriter.toDoneJson());
            } else {
                String staticMessage = aiProperties.getStaticFallbackMessage() == null
                        ? "AI 服务暂时不可用，请稍后再试" : aiProperties.getStaticFallbackMessage();
                saveAssistantMessage(sessionId, staticMessage, "{\"fallback\":true}");
                if (sendEvent(activeStream, "text", eventWriter.toTextJson(staticMessage))) {
                    sendEvent(activeStream, "done", eventWriter.toDoneJson());
                }
            }
            try {
                activeStream.getEmitter().complete();
            } catch (RuntimeException exception) {
                log.warn("SSE complete failed, sessionId={}", sessionId, exception);
            }
        }
        aiStreamRegistry.remove(sessionId, activeStream);
    }

    public boolean cancel(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
        if (activeStream == null || activeStream.isFinished()) {
            return false;
        }
        AiMessageEntity saved = null;
        if (activeStream.tryMarkFinished()) {
            // 用户停止时已看到部分回答，保存避免上下文丢失
            metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
            String partial = activeStream.getPartialAnswer().toString();
            if (!partial.isEmpty()) {
                saved = saveAssistantMessage(sessionId, partial, "{\"interrupted\":true}");
            }
        }
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            // 终态 CAS 已占位，dispose 触发的 doOnCancel 不会重复保存或计数
            subscription.dispose();
        }
        sendEvent(activeStream, "stopped", eventWriter.toStoppedJson(sessionId, saved));
        try {
            activeStream.getEmitter().complete();
        } catch (RuntimeException exception) {
            log.warn("SSE complete failed, sessionId={}", sessionId, exception);
        }
        aiStreamRegistry.remove(sessionId, activeStream);
        return true;
    }

    public void cleanup(AiStreamRegistry.ActiveStream activeStream, String reason) {
        Long sessionId = activeStream.getSessionId();
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (activeStream.tryMarkFinished()) {
            // 客户端断开兜底：先保存可见的部分回答，再退出注册表
            metrics.recordCancelled(activeStream, streamModel(activeStream), streamFallback(activeStream));
            String partial = activeStream.getPartialAnswer().toString();
            if (!partial.isEmpty()) {
                saveAssistantMessage(sessionId, partial, "{\"interrupted\":true}");
            }
            log.info("SSE 连接断开兜底清理, sessionId={}, reason={}", sessionId, reason);
        }
        aiStreamRegistry.remove(sessionId, activeStream);
    }

    private void handleChatChunk(AiStreamRegistry.ActiveStream activeStream, ChatResponse chatResponse,
                                 Usage[] usageHolder) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return;
        }
        if (chatResponse.getResult().getOutput() != null) {
            String text = chatResponse.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                // CAS 保证降级或多 chunk 并发下只记录首个有效 token
                if (activeStream.markFirstToken()) {
                    AiChatStreamContext streamContext = activeStream.getContext();
                    metrics.recordFirstToken(activeStream, streamContext.effectiveModel(modelName),
                            streamContext.fallback());
                }
                activeStream.getPartialAnswer().append(text);
                if (!sendEvent(activeStream, "text", eventWriter.toTextJson(text))) {
                    return;
                }
            }
        }
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
                && chatResponse.getMetadata().getUsage().getTotalTokens() > 0) {
            usageHolder[0] = chatResponse.getMetadata().getUsage();
        }
    }

    private AiMessageEntity saveAssistantMessage(Long sessionId, String content, String payload) {
        try {
            return aiMessageService.saveMessage(sessionId, "assistant", "text", content, payload);
        } catch (RuntimeException exception) {
            log.error("AI assistant message save failed, sessionId={}", sessionId, exception);
            return null;
        }
    }

    private AiMessageEntity saveAssistantMessage(Long sessionId, String content, String payload, Usage usage) {
        int tokenCount = usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
        try {
            return aiMessageService.saveMessage(sessionId, "assistant", "text", content, payload, tokenCount);
        } catch (RuntimeException exception) {
            log.error("AI assistant message save failed, sessionId={}", sessionId, exception);
            return null;
        }
    }

    private boolean sendEvent(AiStreamRegistry.ActiveStream activeStream, String event, String data) {
        boolean sent = eventWriter.sendEvent(activeStream.getEmitter(), event, data);
        if (!sent) {
            cleanupSendFailure(activeStream);
        }
        return sent;
    }

    void sendHeartbeat(AiStreamRegistry.ActiveStream activeStream) {
        if (activeStream.isFinished()) {
            return;
        }
        // 先计数再发送；发送失败会由 cleanupSendFailure 单独计为 failed
        metrics.recordHeartbeat();
        if (!eventWriter.sendHeartbeat(activeStream.getEmitter())) {
            cleanupSendFailure(activeStream);
        }
    }

    private void cleanupSendFailure(AiStreamRegistry.ActiveStream activeStream) {
        if (activeStream.tryMarkFinished()) {
            // 客户端未看到内容，不保存 partialAnswer；先标记终态避免 dispose 回调重复保存
            metrics.recordFailed(activeStream, streamModel(activeStream), streamFallback(activeStream));
            log.info("SSE 发送失败，停止模型流, sessionId={}", activeStream.getSessionId());
        }
        Disposable subscription = activeStream.getSubscription();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
    }

    /** 读取当前流的有效模型名，用于指标低基数标签 */
    private String streamModel(AiStreamRegistry.ActiveStream activeStream) {
        AiChatStreamContext context = activeStream.getContext();
        return context == null ? modelName : context.effectiveModel(modelName);
    }

    /** 读取当前流是否处于备用模型降级路径 */
    private boolean streamFallback(AiStreamRegistry.ActiveStream activeStream) {
        AiChatStreamContext context = activeStream.getContext();
        return context != null && context.fallback();
    }
}
