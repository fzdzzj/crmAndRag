package com.slz.crm.server.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AiChatService {
    void streamChat(Long sessionId, String message, SseEmitter emitter);
    boolean cancelStream(Long sessionId, Long userId);
}