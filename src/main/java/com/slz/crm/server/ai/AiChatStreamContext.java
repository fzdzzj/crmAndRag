package com.slz.crm.server.ai;

import com.slz.crm.pojo.ao.RoleAO;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public record AiChatStreamContext(
        Long sessionId,
        SseEmitter emitter,
        List<Message> messages,
        List<ToolCallback> toolCallbacks,
        String modelOverride,
        boolean fallback,
        long roundStart,
        AtomicInteger repairCounter,
        AiReferenceCollector referenceCollector,
        RoleAO currentUser
) {

    public static AiChatStreamContext initial(Long sessionId, SseEmitter emitter, List<Message> messages,
                                              List<ToolCallback> toolCallbacks, long roundStart,
                                              RoleAO currentUser) {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, null, false,
                roundStart, new AtomicInteger(0), new AiReferenceCollector(), currentUser);
    }

    public AiChatStreamContext forFallback(String fallbackModel) {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, fallbackModel, true,
                roundStart, repairCounter, referenceCollector, currentUser);
    }

    public String effectiveModel(String defaultModel) {
        return modelOverride != null ? modelOverride : defaultModel;
    }
}
