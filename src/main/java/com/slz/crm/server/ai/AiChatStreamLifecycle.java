package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AI 助手 SSE 生命周期。
 *
 * <p>负责模型流订阅、事件缓冲、协作式中止与助手消息终态落库。
 */
@Slf4j
@Component
public class AiChatStreamLifecycle {

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

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

  /** Spring AI 兼容路径（含工具循环）也必须上报 usage，避免模型计量盲区。 */
  @Autowired(required = false)
  private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

  public AiChatStreamLifecycle(
      ChatClient.Builder chatClientBuilder,
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
    ModelCallOptions callOptions = buildModelCallOptions(context, sessionId, effectiveModel);
    AiThinkTagStripper.StreamingStripper thinkStripper = AiThinkTagStripper.streaming();
    AtomicBoolean thinkingFinished = new AtomicBoolean(false);
    Usage[] usageHolder = new Usage[1];
    ModelProvider modelProvider =
        modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
    Flux<ChatResponse> responseFlux;
    // 修正轮3后工具环由 ModelProvider 承载；Spring AI 仅保留 Provider 缺失时的兼容路径。
    if (modelProvider != null) {
      responseFlux = modelProvider.streamChat(new Prompt(context.messages()), callOptions);
    } else {
      ChatClient.ChatClientRequestSpec requestSpec =
          chatClientBuilder.build().prompt().messages(context.messages());
      // 兼容路径只供测试/Provider 未装配时兜底；不承载生产工具调用。
      if (context.thinking() || context.modelOverride() != null) {
        // 兼容路径使用 Spring AI 通用 options；Provider 差异不再由业务层承载。
        requestSpec = requestSpec.options(buildFallbackOptions(context, sessionId, effectiveModel));
      }
      if (!context.toolCallbacks().isEmpty()) {
        requestSpec =
            requestSpec
                .toolCallbacks(context.toolCallbacks())
                .toolContext(buildToolContext(context, sessionId));
      }
      responseFlux = requestSpec.stream().chatResponse();
    }
    Disposable subscription =
        responseFlux
            .timeout(
                Duration.ofSeconds(
                    aiProperties.getLlmTimeoutSeconds() == null
                        ? 60
                        : aiProperties.getLlmTimeoutSeconds()))
            .doOnNext(
                response ->
                    handleChatChunk(
                        activeStream,
                        response,
                        usageHolder,
                        context.thinking(),
                        thinkStripper,
                        thinkingFinished))
            .doOnComplete(
                () -> {
                  if (shouldAbort(activeStream)) {
                    finishSuperseded(activeStream);
                    return;
                  }
                  if (activeStream.tryMarkFinished()) {
                    if (context.thinking()
                        && thinkingFinished.compareAndSet(false, true)
                        && !sendBufferedEvent(
                            activeStream, "thinking", eventWriter.toThinkingJson("", true))) {
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
                            activeStream,
                            "references",
                            eventWriter.toReferencesJson(references, citations))) {
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
                    if (memoryOrchestrator != null && !content.isBlank()) {
                      memoryOrchestrator.onRoundCompleted(
                          sessionId,
                          context.currentUser() == null ? null : context.currentUser().getId(),
                          lastUserMessage(context.messages()),
                          content);
                    }
                    if (sendBufferedEvent(
                        activeStream,
                        "done",
                        eventWriter.toDoneJson(String.valueOf(sessionId), usage))) {
                      metrics.recordCompleted(activeStream, effectiveModel, context.fallback());
                      completeEmitter(activeStream);
                    }
                  }
                  aiStreamRegistry.remove(sessionId, activeStream);
                })
            .doOnError(error -> handleStreamError(activeStream, effectiveModel, error))
            .doOnCancel(
                () -> {
                  if (activeStream.tryMarkFinished()) {
                    metrics.recordCancelled(
                        activeStream, streamModel(activeStream), streamFallback(activeStream));
                    persistAssistantMessage(
                        activeStream,
                        activeStream.getPartialAnswer().toString(),
                        "{\"interrupted\":true}",
                        null,
                        true);
                  }
                  aiStreamRegistry.remove(sessionId, activeStream);
                })
            .subscribe();
    activeStream.setSubscription(subscription);
    heartbeat.start(activeStream, () -> sendHeartbeat(activeStream));
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

  /** 装配中立模型调用参数；思考开关是真实请求参数而非 prompt 约束。 */
  private ModelCallOptions buildModelCallOptions(
      AiChatStreamContext context, Long sessionId, String effectiveModel) {
    return new ModelCallOptions(
        effectiveModel,
        context.thinking(),
        null,
        null,
        List.copyOf(context.toolCallbacks()),
        buildToolContext(context, sessionId),
        Map.of());
  }

  private Map<String, Object> buildToolContext(AiChatStreamContext context, Long sessionId) {
    Map<String, Object> toolContext = new HashMap<>();
    toolContext.put("sessionId", sessionId);
    toolContext.put("repairCounter", context.repairCounter());
    toolContext.put("references", context.referenceCollector());
    toolContext.put("user", context.currentUser());
    return toolContext;
  }

  private DefaultToolCallingChatOptions buildFallbackOptions(
      AiChatStreamContext context, Long sessionId, String effectiveModel) {
    DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
    options.setModel(effectiveModel);
    options.setToolCallbacks(context.toolCallbacks());
    options.setToolContext(buildToolContext(context, sessionId));
    return options;
  }

  /** 上报流式对话 usage；工具环与普通对话共用同一口径。 */
  private void recordTokenUsage(
      AiStreamRegistry.ActiveStream activeStream, String model, Usage usage) {
    TokenUsageRecorder recorder =
        tokenUsageRecorderProvider == null ? null : tokenUsageRecorderProvider.getIfAvailable();
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

  /** 处理模型流错误，必要时降级备用模型。 */
  public void handleStreamError(
      AiStreamRegistry.ActiveStream activeStream, String effectiveModel, Throwable error) {
    Long sessionId = activeStream.getSessionId();
    log.error("AI chat stream error, sessionId={}, model={}", sessionId, effectiveModel, error);
    String fallbackModel = aiProperties.getFallbackModel();
    boolean handled = shouldAbort(activeStream);
    if (handled) {
      finishSuperseded(activeStream);
    } else {
      handled = scheduleConnectionRetry(activeStream, error);
      if (!handled) {
        handled = tryFallbackModel(activeStream, fallbackModel);
      }
      if (!handled) {
        if (activeStream.tryMarkFinished()) {
          metrics.recordFailed(activeStream, effectiveModel, activeStream.getContext().fallback());
          String answer = activeStream.getPartialAnswer().toString();
          if (!answer.isBlank()) {
            persistAssistantMessage(activeStream, answer, "{\"interrupted\":true}", null, true);
            sendBufferedEvent(
                activeStream, "done", eventWriter.toDoneJson(String.valueOf(sessionId), null));
          } else {
            String staticMessage =
                aiProperties.getStaticFallbackMessage() == null
                    ? "AI 服务暂时不可用，请稍后再试"
                    : aiProperties.getStaticFallbackMessage();
            persistAssistantMessage(
                activeStream, staticMessage, "{\"fallback\":true}", null, false);
            if (sendBufferedEvent(activeStream, "delta", eventWriter.toDeltaJson(staticMessage))) {
              sendBufferedEvent(
                  activeStream, "done", eventWriter.toDoneJson(String.valueOf(sessionId), null));
            }
          }
          completeEmitter(activeStream);
        }
        aiStreamRegistry.remove(sessionId, activeStream);
      }
    }
  }

  /** 主模型零输出且未进入兼容路径时切换备用模型；返回是否已接管处理。 */
  private boolean tryFallbackModel(
      AiStreamRegistry.ActiveStream activeStream, String fallbackModel) {
    boolean result;
    if (activeStream.isFinished()
        || activeStream.getContext().fallback()
        || activeStream.getContext().modelOverride() != null
        || activeStream.getPartialAnswer().length() != 0
        || fallbackModel == null
        || fallbackModel.isBlank()) {
      result = false;
    } else {
      log.warn("主模型失败，切换备用模型: {}, sessionId={}", fallbackModel, activeStream.getSessionId());
      subscribe(activeStream, activeStream.getContext().forFallback(fallbackModel));
      result = true;
    }
    return result;
  }

  /** 零输出且连接型异常时同模型重试，最多 2 次；有部分内容后不再重试，避免重复回答。 */
  private boolean scheduleConnectionRetry(
      AiStreamRegistry.ActiveStream activeStream, Throwable error) {
    AiChatStreamContext context = activeStream.getContext();
    boolean result;
    if (activeStream.isFinished()
        || activeStream.getPartialAnswer().length() > 0
        || context == null
        || context.connectionRetry() >= 2
        || !isRetryableConnectionError(error)) {
      result = false;
    } else {
      long baseDelay =
          aiProperties.getConnectionRetryBaseDelayMillis() == null
              ? 500L
              : aiProperties.getConnectionRetryBaseDelayMillis();
      // 第一次重试 500ms，第二次 1000ms；使用共享调度器，不新建业务线程池。
      long delayMillis = baseDelay * (1L << context.connectionRetry());
      log.warn(
          "模型零输出且连接型失败，安排同模型重试: sessionId={}, retry={}, delayMs={}",
          activeStream.getSessionId(),
          context.connectionRetry() + 1,
          delayMillis);
      Schedulers.parallel()
          .schedule(
              () -> {
                if (shouldAbort(activeStream)) {
                  finishSuperseded(activeStream);
                  return;
                }
                subscribe(activeStream, activeStream.getContext().forConnectionRetry());
              },
              delayMillis,
              TimeUnit.MILLISECONDS);
      result = true;
    }
    return result;
  }

  private boolean isRetryableConnectionError(Throwable error) {
    Throwable current = error;
    boolean result = false;
    while (current != null) {
      if (current instanceof IOException || current instanceof TimeoutException) {
        result = true;
        break;
      }
      String className = current.getClass().getName();
      if (className.contains("WebClientRequestException")
          || className.contains("ConnectException")) {
        result = true;
        break;
      }
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains("connection reset")
          || message.contains("connection refused")
          || message.contains("read timed out")
          || message.contains("connection prematurely closed")) {
        result = true;
        break;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return result;
  }

  /**
   * 用户取消或接管旧流。
   *
   * @return 是否执行了取消
   */
  public boolean cancel(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
    boolean result;
    if (activeStream == null || activeStream.isFinished()) {
      result = false;
    } else {
      if (activeStream.tryMarkFinished()) {
        metrics.recordCancelled(
            activeStream, streamModel(activeStream), streamFallback(activeStream));
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
      result = true;
    }
    return result;
  }

  /** 客户端断开或超时兜底清理。 */
  public void cleanup(AiStreamRegistry.ActiveStream activeStream, String reason) {
    Disposable subscription = activeStream.getSubscription();
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
    }
    if (activeStream.tryMarkFinished()) {
      metrics.recordCancelled(
          activeStream, streamModel(activeStream), streamFallback(activeStream));
      String partial = activeStream.getPartialAnswer().toString();
      persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
      log.info("SSE 连接断开兜底清理, sessionId={}, reason={}", activeStream.getSessionId(), reason);
    }
    aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
  }

  /** 处理一次模型响应块。 */
  private void handleChatChunk(
      AiStreamRegistry.ActiveStream activeStream,
      ChatResponse chatResponse,
      Usage[] usageHolder,
      boolean thinking,
      AiThinkTagStripper.StreamingStripper thinkStripper,
      AtomicBoolean thinkingFinished) {
    boolean interrupted = false;
    if (shouldAbort(activeStream)) {
      interrupted = true;
      finishSuperseded(activeStream);
    } else if (chatResponse != null && chatResponse.getResult() != null) {
      if (chatResponse.getResult().getOutput() != null) {
        if (thinking) {
          String reasoningContent =
              extractThinking(chatResponse.getResult().getOutput().getMetadata());
          if (reasoningContent != null
              && !reasoningContent.isBlank()
              && !sendBufferedEvent(
                  activeStream, "thinking", eventWriter.toThinkingJson(reasoningContent, false))) {
            interrupted = true;
          }
        }
        if (!interrupted) {
          String rawText = chatResponse.getResult().getOutput().getText();
          String visibleText =
              rawText == null
                  ? ""
                  : thinkStripper.filter(
                      rawText,
                      thinking
                          ? piece ->
                              sendBufferedEvent(
                                  activeStream,
                                  "thinking",
                                  eventWriter.toThinkingJson(piece, false))
                          : null);
          if (!visibleText.isEmpty()
              && thinking
              && thinkingFinished.compareAndSet(false, true)
              && !sendBufferedEvent(
                  activeStream, "thinking", eventWriter.toThinkingJson("", true))) {
            interrupted = true;
          }
          if (!interrupted && !visibleText.isEmpty()) {
            if (activeStream.markFirstToken()) {
              AiChatStreamContext streamContext = activeStream.getContext();
              metrics.recordFirstToken(
                  activeStream, streamContext.effectiveModel(modelName), streamContext.fallback());
            }
            activeStream.getPartialAnswer().append(visibleText);
            if (!sendBufferedEvent(activeStream, "delta", eventWriter.toDeltaJson(visibleText))) {
              interrupted = true;
            }
          }
        }
      }
    }
    if (!interrupted
        && chatResponse.getMetadata() != null
        && chatResponse.getMetadata().getUsage() != null
        && chatResponse.getMetadata().getUsage().getTotalTokens() > 0) {
      usageHolder[0] = chatResponse.getMetadata().getUsage();
    }
  }

  /** DashScope 会把 reasoning_content 放入 AssistantMessage metadata；键名做兼容读取。 */
  private String extractThinking(Map<String, Object> metadata) {
    String result = "";
    if (metadata != null && !metadata.isEmpty()) {
      for (String key : List.of("reasoningContent", "reasoning_content", "thinking")) {
        Object value = metadata.get(key);
        if (value instanceof String text && !text.isBlank()) {
          result = text;
          break;
        }
      }
    }
    return result;
  }

  private void finishSuperseded(AiStreamRegistry.ActiveStream activeStream) {
    if (activeStream.tryMarkFinished()) {
      metrics.recordCancelled(
          activeStream, streamModel(activeStream), streamFallback(activeStream));
      String partial = activeStream.getPartialAnswer().toString();
      persistAssistantMessage(activeStream, partial, "{\"interrupted\":true}", null, true);
      Disposable subscription = activeStream.getSubscription();
      if (subscription != null && !subscription.isDisposed()) {
        subscription.dispose();
      }
      sendBufferedEvent(
          activeStream, "stopped", eventWriter.toStoppedJson("SUPERSEDED_BY_NEW_REQUEST"));
      completeEmitter(activeStream);
    }
    aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
  }

  private void persistAssistantMessage(
      AiStreamRegistry.ActiveStream activeStream,
      String content,
      String payload,
      Usage usage,
      boolean deleteIfEmpty) {
    Long messageId = activeStream.getAssistantMessageId();
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

  private List<Integer> extractCitations(String content, List<SourceReference> sources) {
    List<Integer> result;
    if (content == null || content.isBlank() || sources == null || sources.isEmpty()) {
      result = List.of();
    } else {
      java.util.Set<Integer> citations = new java.util.LinkedHashSet<>();
      Matcher matcher = CITATION_PATTERN.matcher(content);
      while (matcher.find()) {
        int citation = Integer.parseInt(matcher.group(1));
        if (citation >= 1 && citation <= sources.size()) {
          citations.add(citation);
        }
      }
      result = List.copyOf(citations);
    }
    return result;
  }

  private boolean sendBufferedEvent(
      AiStreamRegistry.ActiveStream activeStream, String event, String data) {
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

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // SSE关闭边界：旧连接断开按已断处理不抛
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
