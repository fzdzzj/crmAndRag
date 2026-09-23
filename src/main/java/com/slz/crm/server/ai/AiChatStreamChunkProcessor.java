package com.slz.crm.server.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 模型块处理协作类（tighten-pmd-residual-325 任务 6.4 批D：拆自 {@link AiChatStreamLifecycle}， 行为等价）。
 *
 * <p>职责：单次模型响应块的处理管线——思考增量提取与推送、可见文本过滤（含思考结束事件与 首 token 统计）、delta 推送。发送失败/被接管的终态处置经 {@link
 * AiChatStreamFinalizer} 委托。
 */
class AiChatStreamChunkProcessor {

  private final AiChatStreamFinalizer finalizer;
  private final AiChatMetrics metrics;
  private final String modelName;

  AiChatStreamChunkProcessor(
      AiChatStreamFinalizer finalizer, AiChatMetrics metrics, String modelName) {
    this.finalizer = finalizer;
    this.metrics = metrics;
    this.modelName = modelName;
  }

  /** 处理一次模型响应块。 */
  void handleChatChunk(
      AiStreamRegistry.ActiveStream activeStream,
      org.springframework.ai.chat.model.ChatResponse chatResponse,
      org.springframework.ai.chat.metadata.Usage[] usageHolder,
      boolean thinking,
      AiThinkTagStripper.StreamingStripper thinkStripper,
      AtomicBoolean thinkingFinished) {
    boolean interrupted = false;
    if (finalizer.shouldAbort(activeStream)) {
      interrupted = true;
      finalizer.finishSuperseded(activeStream);
    } else if (chatResponse != null
        && chatResponse.getResult() != null
        && chatResponse.getResult().getOutput() != null) {
      interrupted =
          processChunkOutput(
              activeStream,
              chatResponse.getResult().getOutput(),
              thinking,
              thinkStripper,
              thinkingFinished);
    }
    if (!interrupted
        && chatResponse.getMetadata() != null
        && chatResponse.getMetadata().getUsage() != null
        && chatResponse.getMetadata().getUsage().getTotalTokens() > 0) {
      usageHolder[0] = chatResponse.getMetadata().getUsage();
    }
  }

  /** 处理一次模型输出：思考增量 → 可见文本过滤（含思考结束事件与首 token 统计）→ delta 推送； 任一缓冲事件发送失败返回 true 表示本次输出处理中断。 */
  private boolean processChunkOutput(
      AiStreamRegistry.ActiveStream activeStream,
      org.springframework.ai.chat.messages.AssistantMessage output,
      boolean thinking,
      AiThinkTagStripper.StreamingStripper thinkStripper,
      AtomicBoolean thinkingFinished) {
    boolean interrupted = false;
    if (thinking) {
      interrupted = processThinkingChunk(activeStream, output);
    }
    if (!interrupted) {
      interrupted =
          processVisibleChunk(activeStream, output, thinking, thinkStripper, thinkingFinished);
    }
    return interrupted;
  }

  /** 思考增量处理：提取并发送思考缓冲事件，发送失败返回 true 表示中断（拆自 processChunkOutput，行为等价）。 */
  private boolean processThinkingChunk(
      AiStreamRegistry.ActiveStream activeStream,
      org.springframework.ai.chat.messages.AssistantMessage output) {
    boolean interrupted = false;
    String reasoningContent = extractThinking(output.getMetadata());
    if (reasoningContent != null
        && !reasoningContent.isBlank()
        && !finalizer.sendBufferedEvent(
            activeStream, "thinking", eventWriter().toThinkingJson(reasoningContent, false))) {
      interrupted = true;
    }
    return interrupted;
  }

  /** 可见文本处理：思考结束事件、首 token 统计、追加 partialAnswer 并推送 delta（拆自 processChunkOutput，行为等价）。 */
  private boolean processVisibleChunk(
      AiStreamRegistry.ActiveStream activeStream,
      org.springframework.ai.chat.messages.AssistantMessage output,
      boolean thinking,
      AiThinkTagStripper.StreamingStripper thinkStripper,
      AtomicBoolean thinkingFinished) {
    boolean interrupted = false;
    String rawText = output.getText();
    String visibleText =
        rawText == null
            ? ""
            : thinkStripper.filter(
                rawText,
                thinking
                    ? piece ->
                        finalizer.sendBufferedEvent(
                            activeStream, "thinking", eventWriter().toThinkingJson(piece, false))
                    : null);
    if (!visibleText.isEmpty()
        && thinking
        && thinkingFinished.compareAndSet(false, true)
        && !finalizer.sendBufferedEvent(
            activeStream, "thinking", eventWriter().toThinkingJson("", true))) {
      interrupted = true;
    }
    if (!interrupted && !visibleText.isEmpty()) {
      if (activeStream.markFirstToken()) {
        AiChatStreamContext streamContext = activeStream.getContext();
        metrics.recordFirstToken(
            activeStream, streamContext.effectiveModel(modelName), streamContext.fallback());
      }
      activeStream.getPartialAnswer().append(visibleText);
      if (!finalizer.sendBufferedEvent(
          activeStream, "delta", eventWriter().toDeltaJson(visibleText))) {
        interrupted = true;
      }
    }
    return interrupted;
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

  private AiChatSseEventWriter eventWriter() {
    return finalizer.eventWriter();
  }
}
