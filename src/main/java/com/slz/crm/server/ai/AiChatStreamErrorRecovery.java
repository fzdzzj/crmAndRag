package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * SSE 流错误处置协作类（tighten-pmd-residual-325 任务 6.4 批D：拆自 {@link AiChatStreamLifecycle}， 行为等价）。
 *
 * <p>职责：模型流错误后的处置链——被接管判定 → 同模型连接重试（零输出且连接型异常，最多 2 次）→ 备用模型切换（零输出）→
 * 静态兜底文案与终态落库；以及外部请求触发的终止路径——用户取消、 客户端断开兜底清理、断开回调收尾。重新订阅经 {@link StreamResubscriber} 回调
 * 回到生命周期入口，终态处置经 {@link AiChatStreamFinalizer} 委托。
 */
@Slf4j
class AiChatStreamErrorRecovery {

  /** 重新订阅回调：重试/降级都回到 AiChatStreamLifecycle.subscribe 入口。 */
  @FunctionalInterface
  interface StreamResubscriber {
    void resubscribe(AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context);
  }

  private final AiChatStreamFinalizer finalizer;
  private final AiProperties aiProperties;
  private final AiStreamRegistry aiStreamRegistry;
  private final AiChatMetrics metrics;
  private final StreamResubscriber resubscriber;

  AiChatStreamErrorRecovery(
      AiChatStreamFinalizer finalizer,
      AiProperties aiProperties,
      AiStreamRegistry aiStreamRegistry,
      AiChatMetrics metrics,
      StreamResubscriber resubscriber) {
    this.finalizer = finalizer;
    this.aiProperties = aiProperties;
    this.aiStreamRegistry = aiStreamRegistry;
    this.metrics = metrics;
    this.resubscriber = resubscriber;
  }

  /** 处理模型流错误，必要时降级备用模型。 */
  public void handleStreamError(
      AiStreamRegistry.ActiveStream activeStream, String effectiveModel, Throwable error) {
    Long sessionId = activeStream.getSessionId();
    log.error("AI chat stream error, sessionId={}, model={}", sessionId, effectiveModel, error);
    String fallbackModel = aiProperties.getFallbackModel();
    boolean handled = finalizer.shouldAbort(activeStream);
    if (handled) {
      finalizer.finishSuperseded(activeStream);
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
            finalizer.persistInterrupted(activeStream, answer);
            finalizer.sendBufferedEvent(
                activeStream, "done", eventWriter().toDoneJson(String.valueOf(sessionId), null));
          } else {
            String staticMessage =
                aiProperties.getStaticFallbackMessage() == null
                    ? "AI 服务暂时不可用，请稍后再试"
                    : aiProperties.getStaticFallbackMessage();
            finalizer.persistFallbackMessage(activeStream, staticMessage);
            if (finalizer.sendBufferedEvent(
                activeStream, "delta", eventWriter().toDeltaJson(staticMessage))) {
              finalizer.sendBufferedEvent(
                  activeStream, "done", eventWriter().toDoneJson(String.valueOf(sessionId), null));
            }
          }
          finalizer.completeEmitter(activeStream);
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
      resubscriber.resubscribe(activeStream, activeStream.getContext().forFallback(fallbackModel));
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
                if (finalizer.shouldAbort(activeStream)) {
                  finalizer.finishSuperseded(activeStream);
                  return;
                }
                resubscriber.resubscribe(
                    activeStream, activeStream.getContext().forConnectionRetry());
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

  private AiChatSseEventWriter eventWriter() {
    return finalizer.eventWriter();
  }

  /** 流被取消（客户端断开）时的收尾：标记结束后按中断口径落库并清理注册表。 */
  void handleStreamCancelled(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
    if (activeStream.tryMarkFinished()) {
      metrics.recordCancelled(
          activeStream,
          finalizer.streamModel(activeStream),
          finalizer.streamFallback(activeStream));
      finalizer.persistInterrupted(activeStream, activeStream.getPartialAnswer().toString());
    }
    aiStreamRegistry.remove(sessionId, activeStream);
  }

  /**
   * 用户取消或接管旧流。
   *
   * @return 是否执行了取消
   */
  boolean cancel(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
    boolean result;
    if (activeStream == null || activeStream.isFinished()) {
      result = false;
    } else {
      if (activeStream.tryMarkFinished()) {
        metrics.recordCancelled(
            activeStream,
            finalizer.streamModel(activeStream),
            finalizer.streamFallback(activeStream));
        String partial = activeStream.getPartialAnswer().toString();
        finalizer.persistInterrupted(activeStream, partial);
      }
      finalizer.disposeSubscription(activeStream);
      // 终态 CAS 已占位，dispose 触发的 doOnCancel 不会重复保存。
      finalizer.sendBufferedEvent(
          activeStream, "stopped", finalizer.eventWriter().toStoppedJson("CANCELLED"));
      finalizer.completeEmitter(activeStream);
      aiStreamRegistry.remove(sessionId, activeStream);
      result = true;
    }
    return result;
  }

  /** 客户端断开或超时兜底清理。 */
  void cleanup(AiStreamRegistry.ActiveStream activeStream, String reason) {
    finalizer.disposeSubscription(activeStream);
    if (activeStream.tryMarkFinished()) {
      metrics.recordCancelled(
          activeStream,
          finalizer.streamModel(activeStream),
          finalizer.streamFallback(activeStream));
      String partial = activeStream.getPartialAnswer().toString();
      finalizer.persistInterrupted(activeStream, partial);
      log.info("SSE 连接断开兜底清理, sessionId={}, reason={}", activeStream.getSessionId(), reason);
    }
    aiStreamRegistry.remove(activeStream.getSessionId(), activeStream);
  }
}
