package com.slz.crm.server.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.entity.AiMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AiChatSseEventWriter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean sendEvent(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE send failed, event={}", event, e);
            return false;
        }
    }

    public boolean sendError(SseEmitter emitter, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        return sendEvent(emitter, "error", writeJson(data));
    }

    /** 发送 SSE comment 心跳；失败返回 false，由调用方清理活跃流 */
    public boolean sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE heartbeat send failed", e);
            return false;
        }
    }

    public String toMetaJson(Long sessionId, Long messageId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messageId", messageId);
        return writeJson(data);
    }

    public String toTextJson(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        return writeJson(data);
    }

    public String toTitleJson(Long sessionId, String title) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("title", title);
        return writeJson(data);
    }

    public String toDoneJson() {
        return "{}";
    }

    public String toStoppedJson(Long sessionId, AiMessageEntity message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messageId", message == null ? null : message.getId());
        data.put("interrupted", true);
        return writeJson(data);
    }

    public String toAuditJson(String model, String promptVersion, long costMs, Usage usage,
                              List<AiReferenceCollector.Reference> references) {
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
        return writeJson(data);
    }

    public String toReferencesJson(List<AiReferenceCollector.Reference> references) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("references", toReferenceItems(references));
        return writeJson(data);
    }

    private List<Map<String, Object>> toReferenceItems(List<AiReferenceCollector.Reference> references) {
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("JSON serialization failed", e);
            return "{}";
        }
    }
}
