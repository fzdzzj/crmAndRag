package com.slz.crm.server.service.impl;

import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiChatImageUnderstandingService;
import com.slz.crm.server.ai.AiChatResume;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import com.slz.crm.server.ai.AiRateLimiter;
import com.slz.crm.server.ai.AiStreamRegistry;
import com.slz.crm.server.service.AiSessionService;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 会话恢复与解析辅助支持类（tighten-pmd-residual-325 任务 6.3 自 AiChatServiceImpl 拆出，行为等价）。 聚合恢复中断流、sessionId
 * 解析、末条用户消息定位与图片资料块拼装；依赖经参数传入，保持零注入可测性。
 */
final class AiChatResumeSupport {

  private AiChatResumeSupport() {}

  /**
   * 恢复中断流检查：携带有效 generationId 且接管成功时完成流式续传并返回 true（拆自 doStreamChat，行为等价）。
   *
   * @param currentUser 当前用户
   * @param emitter SSE 发射器
   * @param resume 恢复上下文
   * @param requestedSessionId 解析出的会话 ID
   * @return true 表示恢复分支已完成流式输出，调用方应直接终止
   */
  static boolean resumeRequestedAndCompleted(
      RoleAO currentUser,
      SseEmitter emitter,
      AiChatResume resume,
      Long requestedSessionId,
      AiSessionService aiSessionService,
      AiStreamRegistry aiStreamRegistry,
      AiChatSseEventWriter eventWriter) {
    boolean resumeCompleted =
        resume != null
            && resume.generationId() != null
            && !resume.generationId().isBlank()
            && tryResume(
                currentUser,
                requestedSessionId,
                emitter,
                resume,
                aiSessionService,
                aiStreamRegistry,
                eventWriter);
    return resumeCompleted;
  }

  @SuppressWarnings(
      "PMD.OnlyOneReturn") // 3 处守卫早返回各带「发错误事件 + complete」副作用，单出口化需深嵌套（tighten-pmd-residual-325 任务
  // 6.3）
  private static boolean tryResume(
      RoleAO currentUser,
      Long sessionId,
      SseEmitter emitter,
      AiChatResume resume,
      AiSessionService aiSessionService,
      AiStreamRegistry aiStreamRegistry,
      AiChatSseEventWriter eventWriter) {
    Long userId = currentUser == null ? null : currentUser.getId();
    if (sessionId == null || aiSessionService.getOwnedSession(sessionId, userId) == null) {
      eventWriter.sendError(emitter, "UNAUTHORIZED", "会话不存在或无权访问");
      emitter.complete();
      return true;
    }
    AiStreamRegistry.ActiveStream activeStream =
        aiStreamRegistry.getByGenerationId(resume.generationId());
    if (activeStream == null || !sessionId.equals(activeStream.getSessionId())) {
      eventWriter.sendError(emitter, "RESUME_UNAVAILABLE", "会话输出已不在缓冲区，请查看已生成的回答");
      emitter.complete();
      return true;
    }
    if (!eventWriter.resume(activeStream, emitter, resume.lastEventId())) {
      eventWriter.sendError(emitter, "RESUME_UNAVAILABLE", "会话输出已不在缓冲区，请查看已生成的回答");
      emitter.complete();
    }
    return true;
  }

  static Long parseSessionId(
      String sessionId, SseEmitter emitter, AiChatSseEventWriter eventWriter) {
    Long result = null;
    if (sessionId != null && !sessionId.isBlank()) {
      try {
        result = Long.valueOf(sessionId);
      } catch (NumberFormatException ignored) {
        eventWriter.sendError(emitter, "PARAM_INVALID", "sessionId 必须是数字");
        emitter.complete();
        result = null;
      }
    }
    return result;
  }

  /** 定位消息列表中最后一条有文本的用户消息；不存在返回空串。 */
  static String lastUserMessage(List<Message> messages) {
    String result = null;
    for (int index = messages.size() - 1; index >= 0 && result == null; index--) {
      Message message = messages.get(index);
      if (message instanceof org.springframework.ai.chat.messages.UserMessage userMessage
          && userMessage.getText() != null) {
        result = userMessage.getText();
      }
    }
    return result == null ? "" : result;
  }

  /** 图片资料块恒定注入；KB 开关不影响图片理解。 */
  static String toImageContextPrompt(AiChatImageUnderstandingService.UnderstandingContext context) {
    StringBuilder prompt = new StringBuilder(128);
    prompt.append("【图片资料】");
    if (context.ocrText() != null && !context.ocrText().isBlank()) {
      prompt.append("\nOCR：").append(context.ocrText().trim());
    }
    if (context.imageSummary() != null && !context.imageSummary().isBlank()) {
      prompt.append("\n图片摘要：").append(context.imageSummary().trim());
    }
    if (context.keyEntities() != null && !context.keyEntities().isEmpty()) {
      prompt.append("\n关键实体：").append(String.join("、", context.keyEntities()));
    }
    if (context.focusedSummary() != null && !context.focusedSummary().isBlank()) {
      prompt.append("\n问题聚焦：").append(context.focusedSummary().trim());
    }
    return prompt.toString();
  }

  /* 消息内容空校验：为空发 PARAM_INVALID 错误事件并 complete 会话 */
  static boolean rejectIfBlankMessage(
      AssistantChatRequest request, SseEmitter emitter, AiChatSseEventWriter eventWriter) {
    boolean rejected = request.message() == null || request.message().isBlank();
    if (rejected) {
      eventWriter.sendError(emitter, "PARAM_INVALID", "消息内容不能为空");
      emitter.complete();
    }
    return rejected;
  }

  /** 限流检查：未取得令牌发 RATE_LIMITED 错误事件并 complete 会话 */
  static boolean rejectIfRateLimited(
      Long userId,
      SseEmitter emitter,
      AiChatSseEventWriter eventWriter,
      AiRateLimiter aiRateLimiter) {
    boolean rejected = !aiRateLimiter.tryAcquire(userId);
    if (rejected) {
      eventWriter.sendError(emitter, "RATE_LIMITED", "操作过于频繁，请稍后再试");
      emitter.complete();
    }
    return rejected;
  }

  /** 已归档会话检查：发 SESSION_ARCHIVED 错误事件并 complete 会话 */
  static boolean rejectIfArchived(
      AiSessionEntity session, SseEmitter emitter, AiChatSseEventWriter eventWriter) {
    boolean archived = session.getStatus() != null && session.getStatus() == 0;
    if (archived) {
      eventWriter.sendError(emitter, "SESSION_ARCHIVED", "会话已归档，请新建会话");
      emitter.complete();
    }
    return archived;
  }
}
