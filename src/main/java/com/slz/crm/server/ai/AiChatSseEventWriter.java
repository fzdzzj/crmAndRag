package com.slz.crm.server.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.pojo.entity.AiMessageEntity;
import java.io.IOException;
import java.util.ArrayList;
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
 */
@Slf4j
@Component
public class AiChatSseEventWriter {

  private final ObjectMapper objectMapper = new ObjectMapper();

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
    return sendEvent(emitter, "error", writeJson(data));
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
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sessionId", sessionId);
    data.put("assistantMessageId", assistantMessageId);
    data.put("generationId", generationId);
    return writeJson(data);
  }

  public String toMetaJson(
      String provider, String model, boolean useKnowledgeBase, boolean thinking) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("provider", provider);
    data.put("model", model);
    data.put("useKnowledgeBase", useKnowledgeBase);
    data.put("thinking", thinking);
    return writeJson(data);
  }

  public String toThinkingJson(String text, boolean finished) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("text", text == null ? "" : text);
    data.put("finished", finished);
    return writeJson(data);
  }

  public String toDeltaJson(String content) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("content", content == null ? "" : content);
    return writeJson(data);
  }

  public String toTitleJson(String text) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("text", text);
    return writeJson(data);
  }

  public String toDoneJson(String sessionId, Usage usage) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sessionId", sessionId);
    data.put("cancelled", false);
    data.put("usage", usageMap(usage));
    return writeJson(data);
  }

  public String toCancelledJson(String reason) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reason", reason);
    return writeJson(data);
  }

  public String toStoppedJson(String reason) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reason", reason);
    return writeJson(data);
  }

  public String toStoppedJson(Long sessionId, AiMessageEntity message) {
    // 兼容旧取消路径：保存结果只用于日志/指标，冻结 payload 仍只暴露 reason。
    return toStoppedJson(message == null ? "CANCELLED" : "SUPERSEDED_BY_NEW_REQUEST");
  }

  public String toAuditJson(
      String model,
      String promptVersion,
      long costMs,
      Usage usage,
      List<AiReferenceCollector.Reference> references) {
    return toAuditJson(model, promptVersion, costMs, usage, references, List.of(), List.of());
  }

  public String toAuditJson(
      String model,
      String promptVersion,
      long costMs,
      Usage usage,
      List<AiReferenceCollector.Reference> references,
      List<SourceReference> sources,
      List<Integer> citations) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("model", model);
    data.put("promptVersion", promptVersion);
    data.put("costMs", costMs);
    if (usage != null) {
      data.put("tokensIn", usage.getPromptTokens());
      data.put("tokensOut", usage.getCompletionTokens());
    }
    if (references != null && !references.isEmpty()) {
      data.put("references", toReferenceItems(references));
    }
    if (sources != null && !sources.isEmpty()) {
      data.put("sources", toSourceItems(sources));
    }
    if (citations != null && !citations.isEmpty()) {
      data.put("citations", citations);
    }
    return writeJson(data);
  }

  public String toReferencesJson(List<AiReferenceCollector.Reference> references) {
    return toReferencesJson(references, List.of());
  }

  public String toReferencesJson(
      List<AiReferenceCollector.Reference> references, List<Integer> citations) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("citations", citations == null ? List.of() : citations);
    data.put("items", toReferenceItems(references));
    return writeJson(data);
  }

  public String toSourcesJson(List<SourceReference> sources) {
    return writeJson(toSourceItems(sources));
  }

  private List<Map<String, Object>> toSourceItems(List<SourceReference> sources) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (SourceReference source : sources == null ? List.<SourceReference>of() : sources) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("sourceType", source.sourceType());
      item.put("route", source.route());
      item.put("filename", source.filename());
      item.put("documentId", source.documentId());
      item.put("chunkId", source.chunkId());
      item.put("chunkIndex", source.chunkIndex());
      item.put("pageNo", source.pageNo());
      item.put("rowIndex", source.rowIndex());
      item.put("excerpt", source.excerpt());
      item.put("relevanceScore", source.relevanceScore());
      payload.add(item);
    }
    return payload;
  }

  private List<Map<String, Object>> toReferenceItems(
      List<AiReferenceCollector.Reference> references) {
    List<Map<String, Object>> refs = new ArrayList<>();
    for (AiReferenceCollector.Reference reference : references) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("type", reference.type());
      item.put("id", reference.id());
      item.put("name", reference.name());
      refs.add(item);
    }
    return refs;
  }

  private Map<String, Long> usageMap(Usage usage) {
    Map<String, Long> result = new LinkedHashMap<>();
    result.put(
        "input", usage == null || usage.getPromptTokens() == null ? 0L : usage.getPromptTokens());
    result.put(
        "output",
        usage == null || usage.getCompletionTokens() == null ? 0L : usage.getCompletionTokens());
    result.put(
        "total", usage == null || usage.getTotalTokens() == null ? 0L : usage.getTotalTokens());
    return result;
  }

  private String writeJson(Object value) {
    String result;
    try {
      result = objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      log.error("JSON serialization failed", e);
      result = "{}";
    }
    return result;
  }
}
