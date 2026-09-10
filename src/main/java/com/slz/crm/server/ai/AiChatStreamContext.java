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
        boolean thinking,
        int connectionRetry,
        long roundStart,
        AtomicInteger repairCounter,
        AiReferenceCollector referenceCollector,
        RoleAO currentUser
) {

    public static AiChatStreamContext initial(Long sessionId, SseEmitter emitter, List<Message> messages,
                                              List<ToolCallback> toolCallbacks, long roundStart,
                                              RoleAO currentUser) {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, null, false,
                false, 0, roundStart, new AtomicInteger(0), new AiReferenceCollector(), currentUser);
    }

    /**
     * 创建带思考开关的初始上下文；fallback 会保留原始开关。
     */
    public static AiChatStreamContext initial(Long sessionId, SseEmitter emitter, List<Message> messages,
                                              List<ToolCallback> toolCallbacks, long roundStart,
                                              RoleAO currentUser, boolean thinking) {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, null, false,
                thinking, 0, roundStart, new AtomicInteger(0), new AiReferenceCollector(), currentUser);
    }

    /**
     * 零输出连接型错误重试；保持同一模型与思考开关，仅递增重试次数。
     */
    public AiChatStreamContext forConnectionRetry() {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, modelOverride, fallback,
                thinking, connectionRetry + 1, roundStart, repairCounter, referenceCollector, currentUser);
    }

    public AiChatStreamContext forFallback(String fallbackModel) {
        return new AiChatStreamContext(sessionId, emitter, messages, toolCallbacks, fallbackModel, true,
                thinking, 0, roundStart, repairCounter, referenceCollector, currentUser);
    }

    public String effectiveModel(String defaultModel) {
        return modelOverride != null ? modelOverride : defaultModel;
    }
}
