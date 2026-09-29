package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * SSE 流终态收尾与活跃流状态协作类（tighten-pmd-residual-325 任务 6.4 批D：拆自 {@link AiChatStreamLifecycle}，行为等价）。
 *
 * <p>职责：流正常完成收尾（落库 / 计量 / 记忆加工 / done 事件 / 注销）、活跃流状态助手 （发送失败清理、完成 emitter、shouldAbort、超管口径的
 * model/fallback 读取）、用户取消与 断连兜底清理。重试/降级决策见 {@link AiChatStreamErrorRecovery}；记忆加工与 usage 上报 是可选增强，经
 * Supplier 惰性读取（字段注入发生在生命周期构造之后）。
 */
@Slf4j
class AiChatStreamFinalizer {

  private final AiChatSseEventWriter eventWriter;
  private final AiAssistantMessageStore assistantMessageStore;
  private final AiStreamRegistry aiStreamRegistry;
  private final AiChatMetrics metrics;
  private final AiChatPromptService promptService;
  private final Supplier<AiMemoryOrchestrator> memoryOrchestratorSupplier;
  private final Supplier<TokenUsageRecorder> tokenUsageRecorderSupplier;
  private final String modelName;

  AiChatStreamFinalizer(
      AiChatSseEventWriter eventWriter,
      AiAssistantMessageStore assistantMessageStore,
      AiStreamRegistry aiStreamRegistry,
      AiChatMetrics metrics,
      AiChatPromptService promptService,
      Supplier<AiMemoryOrchestrator> memoryOrchestratorSupplier,
      Supplier<TokenUsageRecorder> tokenUsageRecorderSupplier,
      String modelName) {
    this.eventWriter = eventWriter;
    this.assistantMessageStore = assistantMessageStore;
    this.aiStreamRegistry = aiStreamRegistry;
    this.metrics = metrics;
    this.promptService = promptService;
    this.memoryOrchestratorSupplier = memoryOrchestratorSupplier;
    this.tokenUsageRecorderSupplier = tokenUsageRecorderSupplier;
    this.modelName = modelName;
  }

  /** 事件写出器包内访问器（块处理协作类拼 payload 时使用）。 */
  AiChatSseEventWriter eventWriter() {
    return eventWriter;
  }

  boolean sendBufferedEvent(AiStreamRegistry.ActiveStream activeStream, String event, String data) {
    boolean sent = eventWriter.sendBufferedEvent(activeStream, event, data);
    if (!sent) {
      cleanupSendFailure(activeStream);
    }
    return sent;
  }

  /** 发送 SSE comment 心跳；失败清理活跃流。 */
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
    disposeSubscription(activeStream);
    aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // SSE关闭边界：旧连接断开按已断处理不抛
  void completeEmitter(AiStreamRegistry.ActiveStream activeStream) {
    try {
      activeStream.getEmitter().complete();
    } catch (RuntimeException exception) {
      log.warn("SSE complete failed, sessionId={}", activeStream.getSessionId(), exception);
    }
  }

  boolean shouldAbort(AiStreamRegistry.ActiveStream activeStream) {
    return activeStream.isCancelled()
        || activeStream.isFinished()
        || aiStreamRegistry.get(activeStream.getSessionId()) != activeStream;
  }

  void finishSuperseded(AiStreamRegistry.ActiveStream activeStream) {
    try {
      if (activeStream.tryMarkFinished()) {
        metrics.recordCancelled(
            activeStream, streamModel(activeStream), streamFallback(activeStream));
        String partial = activeStream.getPartialAnswer().toString();
        persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
        disposeSubscription(activeStream);
        try {
          sendBufferedEvent(
              activeStream, "stopped", eventWriter.toStoppedJson("SUPERSEDED_BY_NEW_REQUEST"));
        } finally {
          completeEmitter(activeStream);
        }
      }
    } finally {
      aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
    }
  }

  /** 安全释放模型流订阅（终止处置协作类亦复用）。 */
  void disposeSubscription(AiStreamRegistry.ActiveStream activeStream) {
    reactor.core.Disposable subscription = activeStream.getSubscription();
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 存储异常降级：落库异常不阻断后续流终态处理
  private void persistAssistantMessage(
      AiStreamRegistry.ActiveStream activeStream,
      String content,
      String payload,
      Usage usage,
      boolean deleteIfEmpty) {
    Long messageId = activeStream.getAssistantMessageId();
    try {
      boolean updated =
          assistantMessageStore.complete(
              messageId,
              content,
              payload,
              usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
      if (!updated) {
        log.error(
            "AI assistant message save failed, sessionId={}, messageId={}",
            activeStream.getSessionId(),
            messageId);
      } else if (deleteIfEmpty && (content == null || content.isBlank())) {
        assistantMessageStore.deleteIfEmpty(messageId);
      }
    } catch (RuntimeException exception) {
      log.error(
          "AI assistant message save exception, sessionId={}, messageId={}",
          activeStream.getSessionId(),
          messageId,
          exception);
    }
  }

  /** 中断口径落库（错误处置链使用）：payload 标记 interrupted，空内容时删除占位消息。 */
  void persistInterrupted(AiStreamRegistry.ActiveStream activeStream, String content) {
    persistAssistantMessage(activeStream, content, "{\"interrupted\":true}", null, true);
  }

  /** 静态兜底口径落库（错误处置链使用）：payload 标记 fallback，不因空内容删除。 */
  void persistFallbackMessage(AiStreamRegistry.ActiveStream activeStream, String content) {
    persistAssistantMessage(activeStream, content, "{\"fallback\":true}", null, false);
  }

  /** 上报流式对话 usage；工具环与普通对话共用同一口径。 */
  private void recordTokenUsage(
      AiStreamRegistry.ActiveStream activeStream, String model, Usage usage) {
    TokenUsageRecorder recorder = tokenUsageRecorderSupplier.get();
    if (recorder != null && usage != null) {
      AiChatStreamContext context = activeStream.getContext();
      Long userId = context.currentUser() == null ? null : context.currentUser().getId();
      long promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
      long completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
      long totalTokens =
          usage.getTotalTokens() == null ? promptTokens + completionTokens : usage.getTotalTokens();
      if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
        recorder.record(
            new TokenUsageRecord(
                model,
                userId == null ? "user:system" : "user:" + userId,
                String.valueOf(activeStream.getSessionId()),
                null,
                TokenUsageType.CHAT,
                promptTokens,
                completionTokens,
                totalTokens,
                true));
      }
    }
  }

  List<Integer> extractCitations(String content, List<SourceReference> sources) {
    return CitationSupport.extractCitations(content, sources);
  }

  private String lastUserMessage(List<Message> messages) {
    String result = "";
    for (int index = messages.size() - 1; index >= 0; index--) {
      Message message = messages.get(index);
      if (message instanceof UserMessage userMessage && userMessage.getText() != null) {
        result = userMessage.getText();
        break;
      }
    }
    return result;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  // OnlyOneReturn 豁免理由（tighten-pmd-residual-325 任务 6.2）：流正常完成收尾里的三处守卫式早返回
  // （被接管 / 思考结束事件发送失败 / references 事件发送失败）均需跳过后续落库与注册表清理，
  // 拆分后无法在不引入贯穿全流程的完成标志的前提下等价合并为单出口（Q7 拍板口径）。
  void finishStreamNormally(
      AiStreamRegistry.ActiveStream activeStream,
      AiChatStreamContext context,
      String effectiveModel,
      Usage[] usageHolder,
      java.util.concurrent.atomic.AtomicBoolean thinkingFinished) {
    Long sessionId = context.sessionId();
    if (shouldAbort(activeStream)) {
      finishSuperseded(activeStream);
      return;
    }
    if (activeStream.tryMarkFinished()) {
      if (context.thinking()
          && thinkingFinished.compareAndSet(false, true)
          && !sendBufferedEvent(activeStream, "thinking", eventWriter.toThinkingJson("", true))) {
        return;
      }
      List<AiReferenceCollector.Reference> references =
          context.referenceCollector().getReferences();
      Usage usage = usageHolder[0];
      String content = activeStream.getPartialAnswer().toString();
      List<SourceReference> sources = activeStream.getSources();
      // fix-citation-alignment 任务 2.1：落库/citations 前对齐编号（不回放已流出 SSE token）
      CitationAligner.Alignment aligned = CitationAligner.align(content, sources);
      content = aligned.text();
      List<Integer> citations = extractCitations(content, sources);
      if (!references.isEmpty()
          && !sendBufferedEvent(
              activeStream, "references", eventWriter.toReferencesJson(references, citations))) {
        return;
      }
      String auditPayload =
          eventWriter.toAuditJson(
              effectiveModel,
              promptService.getPromptVersion(),
              System.currentTimeMillis() - context.roundStart(),
              usage,
              references,
              sources,
              citations);
      persistAssistantMessage(activeStream, content, auditPayload, usage, false);
      recordTokenUsage(activeStream, effectiveModel, usage);
      AiMemoryOrchestrator memoryOrchestrator = memoryOrchestratorSupplier.get();
      if (memoryOrchestrator != null && !content.isBlank()) {
        memoryOrchestrator.onRoundCompleted(
            sessionId,
            context.currentUser() == null ? null : context.currentUser().getId(),
            lastUserMessage(context.messages()),
            content);
      }
      if (sendBufferedEvent(
          activeStream, "done", eventWriter.toDoneJson(String.valueOf(sessionId), usage))) {
        metrics.recordCompleted(activeStream, effectiveModel, context.fallback());
        completeEmitter(activeStream);
      }
      aiStreamRegistry.remove(sessionId, activeStream);
    } else {
      aiStreamRegistry.remove(sessionId, activeStream);
    }
  }

  /** 当前流的实际生效模型（上下文覆盖优先，缺省回落生命周期配置）。 */
  String streamModel(AiStreamRegistry.ActiveStream activeStream) {
    AiChatStreamContext context = activeStream.getContext();
    return context == null ? modelName : context.effectiveModel(modelName);
  }

  boolean streamFallback(AiStreamRegistry.ActiveStream activeStream) {
    AiChatStreamContext context = activeStream.getContext();
    return context != null && context.fallback();
  }
}
