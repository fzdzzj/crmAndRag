package com.slz.crm.server.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.pojo.entity.AiMessageEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 助手 SSE 冻结契约 payload 序列化协作类（tighten-pmd-residual-325 任务 6.4 批A： 拆自 {@link
 * AiChatSseEventWriter}，行为等价）。
 *
 * <p>只负责各事件 payload 的字段拼装与 JSON 序列化；事件 id 分配、续传缓冲与连接写出仍归 {@link
 * AiChatSseEventWriter}。字段顺序即冻结契约对外结构，禁止调整。
 */
@Slf4j
class AiSsePayloadWriter {

  private final ObjectMapper objectMapper = new ObjectMapper();

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

  /** 序列化入口；error 事件（连接层）也复用，包内可见。 */
  String writeJson(Object value) {
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
