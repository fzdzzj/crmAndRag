package com.slz.crm.server.service;

import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.server.ai.AiChatResume;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AiChatService {
    /**
     * 按冻结助手契约启动流式对话。
     *
     * @param request 助手请求
     * @param emitter SSE 连接
     * @param resume 断线续传参数；可为空
     */
    void streamChat(AssistantChatRequest request, SseEmitter emitter, AiChatResume resume);

    void streamChat(Long sessionId, String message, SseEmitter emitter);
    boolean cancelStream(Long sessionId, Long userId);
}
