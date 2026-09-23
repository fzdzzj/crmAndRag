package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.pojo.entity.AiMessageEntity;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 助手 SSE 事件写出器。
 *
 * <p>负责冻结契约的 payload 序列化、事件 id 分配与断线续传缓冲。
 *
 * <p>tighten-pmd-residual-325 任务 6.4 批A：payload 字段拼装与 JSON 序列化拆至 {@link
 * AiSsePayloadWriter}，本类保留连接写出 / 续传 / 重放，toXxxJson 保留为委托门面（公共 API 不变），行为等价。
 */
@Slf4j
@Component
public class AiChatSseEventWriter {

  private final AiSsePayloadWriter payloads = new AiSsePayloadWriter();

  public boolean sendEvent(SseEmitter emitter, String event, String data) {
    return sendEvent(emitter, event, data, null);
  }

  /**
   * 发送带事件 id 的业务事件，并写入 generation 续传缓冲。
   *
   * <p>同一 ActiveStream 的发送串行化，避免新连接替换 emitter 后新旧连接并发写。
   */
  public boolean sendBufferedEvent(
      AiStreamRegistry.ActiveStream activeStream, String event, String data) {
    synchronized (activeStream) {
      String eventId = activeStream.getEventBuffer().nextEventId();
      boolean sent = sendEvent(activeStream.getEmitter(), event, data, eventId);
      if (sent) {
        activeStream.getEventBuffer().append(eventId, event, data);
      }
      return sent;
    }
  }

  /** 向重连连接重放缓冲事件；只重放，不触发任何业务执行。 */
  public void replay(
      AiStreamRegistry.ActiveStream activeStream, SseEmitter emitter, String lastEventId) {
    for (AiSseEventBuffer.BufferedEvent event :
        activeStream.getEventBuffer().eventsAfter(lastEventId)) {
      sendEvent(emitter, event.eventName(), event.data(), event.eventId());
    }
  }

  /**
   * 先重放缓冲，再把仍活跃的生成切到新连接；终态流只重放并关闭。
   *
   * @return true 表示续传成功
   */
  public boolean resume(
      AiStreamRegistry.ActiveStream activeStream, SseEmitter emitter, String lastEventId) {
    synchronized (activeStream) {
      boolean result = true;
      for (AiSseEventBuffer.BufferedEvent event :
          activeStream.getEventBuffer().eventsAfter(lastEventId)) {
        if (!sendEvent(emitter, event.eventName(), event.data(), event.eventId())) {
          result = false;
          break;
        }
      }
      if (result) {
        if (activeStream.isFinished()) {
          completeQuietly(emitter);
        } else {
          SseEmitter previous = activeStream.attachResumeEmitter(emitter);
          completeQuietly(previous);
        }
      }
      return result;
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // SSE连接关闭边界：旧连接断开按已断处理不抛
  private void completeQuietly(SseEmitter emitter) {
    if (emitter == null) {
      return;
    }
    try {
      emitter.complete();
    } catch (RuntimeException ignored) {
      // 旧连接通常已断开；续传连接才是后续唯一写出目标。
    }
  }

  private boolean sendEvent(SseEmitter emitter, String event, String data, String eventId) {
    boolean result;
    try {
      SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
      if (eventId != null) {
        builder.id(eventId);
      }
      emitter.send(builder);
      result = true;
    } catch (IOException | IllegalStateException e) {
      log.warn("SSE send failed, event={}, eventId={}", event, eventId, e);
      result = false;
    }
    return result;
  }

  public boolean sendError(SseEmitter emitter, String code, String message) {
    return sendError(emitter, code, message, null);
  }

  public boolean sendError(
      SseEmitter emitter, String code, String message, Integer retryAfterSeconds) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", code);
    data.put("msg", message);
    if (retryAfterSeconds != null) {
      data.put("retryAfterSeconds", retryAfterSeconds);
    }
    return sendEvent(emitter, "error", payloads.writeJson(data));
  }

  /** 发送 SSE comment 心跳；失败返回 false，由调用方清理活跃流 */
  public boolean sendHeartbeat(SseEmitter emitter) {
    boolean result;
    try {
      emitter.send(SseEmitter.event().comment("ping"));
      result = true;
    } catch (IOException | IllegalStateException e) {
      log.warn("SSE heartbeat send failed", e);
      result = false;
    }
    return result;
  }

  public String toStartJson(String sessionId, Long assistantMessageId, String generationId) {
    return payloads.toStartJson(sessionId, assistantMessageId, generationId);
  }

  public String toMetaJson(
      String provider, String model, boolean useKnowledgeBase, boolean thinking) {
    return payloads.toMetaJson(provider, model, useKnowledgeBase, thinking);
  }

  public String toThinkingJson(String text, boolean finished) {
    return payloads.toThinkingJson(text, finished);
  }

  public String toDeltaJson(String content) {
    return payloads.toDeltaJson(content);
  }

  public String toTitleJson(String text) {
    return payloads.toTitleJson(text);
  }

  public String toDoneJson(String sessionId, Usage usage) {
    return payloads.toDoneJson(sessionId, usage);
  }

  public String toCancelledJson(String reason) {
    return payloads.toCancelledJson(reason);
  }

  public String toStoppedJson(String reason) {
    return payloads.toStoppedJson(reason);
  }

  public String toStoppedJson(Long sessionId, AiMessageEntity message) {
    return payloads.toStoppedJson(sessionId, message);
  }

  public String toAuditJson(
      String model,
      String promptVersion,
      long costMs,
      Usage usage,
      List<AiReferenceCollector.Reference> references) {
    return payloads.toAuditJson(model, promptVersion, costMs, usage, references);
  }

  public String toAuditJson(
      String model,
      String promptVersion,
      long costMs,
      Usage usage,
      List<AiReferenceCollector.Reference> references,
      List<SourceReference> sources,
      List<Integer> citations) {
    return payloads.toAuditJson(
        model, promptVersion, costMs, usage, references, sources, citations);
  }

  public String toReferencesJson(List<AiReferenceCollector.Reference> references) {
    return payloads.toReferencesJson(references);
  }

  public String toReferencesJson(
      List<AiReferenceCollector.Reference> references, List<Integer> citations) {
    return payloads.toReferencesJson(references, citations);
  }

  public String toSourcesJson(List<SourceReference> sources) {
    return payloads.toSourcesJson(sources);
  }
}
