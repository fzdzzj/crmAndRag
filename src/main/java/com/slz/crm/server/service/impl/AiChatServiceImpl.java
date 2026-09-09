package com.slz.crm.server.service.impl;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiChatPromptService;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import com.slz.crm.server.ai.AiChatStreamLifecycle;
import com.slz.crm.server.ai.AiRateLimiter;
import com.slz.crm.server.ai.AiChatStreamContext;
import com.slz.crm.server.ai.AiStreamRegistry;
import com.slz.crm.server.ai.AiAssistantMessageStore;
import com.slz.crm.server.ai.AiToolRegistry;
import com.slz.crm.server.service.AiChatService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;

@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final String DEFAULT_TITLE = "新对话";

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AiMessageService aiMessageService;

    @Autowired
    private AiChatPromptService promptService;

    @Autowired
    private AiChatSseEventWriter eventWriter;

    @Autowired
    private AiChatStreamLifecycle streamLifecycle;

    @Autowired
    private AiToolRegistry aiToolRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AiStreamRegistry aiStreamRegistry;

    @Autowired
    private AiAssistantMessageStore assistantMessageStore;

    @Autowired
    private AiRateLimiter aiRateLimiter;

    @Autowired
    @Qualifier("aiChatExecutor")
    private Executor aiChatExecutor;

    @Autowired
    @Qualifier("aiTitleExecutor")
    private Executor aiTitleExecutor;

    @Override
    public void streamChat(Long sessionId, String message, SseEmitter emitter) {
        RoleAO currentUser = BaseUnit.getCurrentRole();
        CompletableFuture.runAsync(() -> doStreamChat(currentUser, sessionId, message, emitter), aiChatExecutor);
    }

    private void doStreamChat(RoleAO currentUser, Long sessionId, String message, SseEmitter emitter) {
        BaseUnit.setCurrentRole(currentUser);
        Long userId = currentUser == null ? null : currentUser.getId();

        try {
            if (message == null || message.isBlank()) {
                eventWriter.sendError(emitter, "PARAM_INVALID", "消息内容不能为空");
                emitter.complete();
                return;
            }

            if (!aiRateLimiter.tryAcquire(userId)) {
                eventWriter.sendError(emitter, "RATE_LIMITED", "操作过于频繁，请稍后再试");
                emitter.complete();
                return;
            }

            AiSessionEntity session = aiSessionService.getOwnedSession(sessionId, userId);
            boolean isNewSession = false;
            if (session == null) {
                session = aiSessionService.createSession(userId, null);
                isNewSession = true;
            } else if (session.getStatus() != null && session.getStatus() == 0) {
                eventWriter.sendError(emitter, "SESSION_ARCHIVED", "会话已归档，请新建会话");
                emitter.complete();
                return;
            }

            Long finalSessionId = session.getId();
            boolean needTitle = isNewSession || DEFAULT_TITLE.equals(session.getTitle());
            Lock takeoverLock = aiStreamRegistry.takeoverLock(finalSessionId);
            takeoverLock.lock();
            try {
                AiStreamRegistry.ActiveStream oldStream = aiStreamRegistry.get(finalSessionId);
                // 接管只顶替注册表；旧流在下一个 shouldAbort 检查点协作退出。

                List<Message> messages = promptService.buildMessages(finalSessionId, message);
                AiMessageEntity userMessage = aiMessageService.saveMessage(
                        finalSessionId, "user", "text", message, null);
                AiMessageEntity assistantMessage = assistantMessageStore.createPlaceholder(finalSessionId);
                AiStreamRegistry.ActiveStream activeStream = new AiStreamRegistry.ActiveStream(
                        finalSessionId, emitter, java.util.UUID.randomUUID().toString());
                activeStream.setAssistantMessageId(assistantMessage.getId());
                eventWriter.sendBufferedEvent(activeStream, "start", eventWriter.toStartJson(
                        String.valueOf(finalSessionId), assistantMessage.getId(), activeStream.getGenerationId()));
                eventWriter.sendBufferedEvent(activeStream, "meta", eventWriter.toMetaJson(
                        resolveProvider(), resolveModelName(), false, false));

                List<ToolCallback> toolCallbacks = permittedToolCallbacks(currentUser);
                activeStream.setContext(AiChatStreamContext.initial(finalSessionId, emitter, messages, toolCallbacks,
                        System.currentTimeMillis(), currentUser));
                aiStreamRegistry.register(finalSessionId, activeStream);
                emitter.onCompletion(() -> streamLifecycle.cleanup(activeStream, "onCompletion"));
                emitter.onTimeout(() -> streamLifecycle.cleanup(activeStream, "onTimeout"));
                generateTitleAsync(finalSessionId, userId, message, needTitle, activeStream);
                if (activeStream.isFinished()) {
                    assistantMessageStore.deleteIfEmpty(activeStream.getAssistantMessageId());
                    return;
                }

                streamLifecycle.subscribe(activeStream, activeStream.getContext());
            } finally {
                takeoverLock.unlock();
            }
        } catch (Exception exception) {
            log.error("AI chat failed, sessionId={}", sessionId, exception);
            eventWriter.sendError(emitter, "LLM_ERROR", "模型调用失败，请稍后重试");
            emitter.complete();
        } finally {
            BaseUnit.removeCurrentId();
        }
    }

    private void generateTitleAsync(Long sessionId, Long userId, String message, boolean needTitle,
                                    AiStreamRegistry.ActiveStream activeStream) {
        if (!needTitle) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            String title = promptService.generateTitle(chatClientBuilder, message);
                if (title != null && !title.isBlank()) {
                    aiSessionService.updateTitle(sessionId, userId, title);
                    if (!activeStream.isFinished()) {
                        eventWriter.sendBufferedEvent(activeStream, "title", eventWriter.toTitleJson(title));
                    }
                }
        }, aiTitleExecutor);
    }

    private List<ToolCallback> permittedToolCallbacks(RoleAO currentUser) {
        if (currentUser != null
                && (currentUser.getPermissions() == null || currentUser.getPermissions().isEmpty())) {
            currentUser.setPermissions(permissionService.getPermissionList(currentUser.getRoleId()));
        }
        return aiToolRegistry.getPermittedToolCallbacks(currentUser);
    }

    private String resolveProvider() {
        // DashScope 是当前基线默认 Provider；ModelProvider 接线后由 provider() 统一回传。
        return environment.getProperty("spring.ai.chat.provider", "dashscope");
    }

    private String resolveModelName() {
        return environment.getProperty("spring.ai.chat.options.model", "qwen-plus");
    }

    @Override
    public boolean cancelStream(Long sessionId, Long userId) {
        if (aiSessionService.getOwnedSession(sessionId, userId) == null) {
            throw new IllegalStateException("会话不存在或无权访问");
        }
        AiStreamRegistry.ActiveStream activeStream = aiStreamRegistry.get(sessionId);
        if (activeStream == null || activeStream.isFinished()) {
            return false;
        }
        return streamLifecycle.cancel(activeStream, sessionId);
    }
}
