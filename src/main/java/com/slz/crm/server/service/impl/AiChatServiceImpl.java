package com.slz.crm.server.service.impl;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiAssistantMessageStore;
import com.slz.crm.server.ai.AiChatImageService;
import com.slz.crm.server.ai.AiChatImageUnderstandingService;
import com.slz.crm.server.ai.AiChatKnowledgeRetrievalService;
import com.slz.crm.server.ai.AiChatPromptService;
import com.slz.crm.server.ai.AiChatResume;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import com.slz.crm.server.ai.AiChatStreamContext;
import com.slz.crm.server.ai.AiChatStreamLifecycle;
import com.slz.crm.server.ai.AiRateLimiter;
import com.slz.crm.server.ai.AiStreamRegistry;
import com.slz.crm.server.ai.AiToolRegistry;
import com.slz.crm.server.service.AiChatService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.service.PermissionService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

  private static final String DEFAULT_TITLE = "新对话";

  @Autowired private ChatClient.Builder chatClientBuilder;

  /** ModelProvider 实现归 base；就绪后统一 provider 元信息与模型流。 */
  @Autowired(required = false)
  private ObjectProvider<ModelProvider> modelProviderProvider;

  @Autowired private AiSessionService aiSessionService;

  @Autowired private AiMessageService aiMessageService;

  @Autowired private AiChatPromptService promptService;

  @Autowired private AiChatSseEventWriter eventWriter;

  @Autowired private AiChatStreamLifecycle streamLifecycle;

  @Autowired private AiToolRegistry aiToolRegistry;

  @Autowired private Environment environment;

  @Autowired private PermissionService permissionService;

  @Autowired private AiStreamRegistry aiStreamRegistry;

  @Autowired private AiAssistantMessageStore assistantMessageStore;

  @Autowired private AiRateLimiter aiRateLimiter;

  @Autowired(required = false)
  private AiChatImageService aiChatImageService;

  @Autowired(required = false)
  private AiChatImageUnderstandingService aiChatImageUnderstandingService;

  /** 检索消费属 C；B 生产实现未合入时由 mock/端口缺失的零命中路径承接。 */
  @Autowired(required = false)
  private AiChatKnowledgeRetrievalService knowledgeRetrievalService;

  @Autowired
  @Qualifier("aiChatExecutor")
  private Executor aiChatExecutor;

  @Autowired
  @Qualifier("aiTitleExecutor")
  private Executor aiTitleExecutor;

  @Override
  public void streamChat(AssistantChatRequest request, SseEmitter emitter, AiChatResume resume) {
    RoleAO currentUser = BaseUnit.getCurrentRole();
    CompletableFuture.runAsync(
        () -> doStreamChat(currentUser, request, emitter, resume), aiChatExecutor);
  }

  @Override
  public void streamChat(Long sessionId, String message, SseEmitter emitter) {
    AssistantChatRequest request =
        new AssistantChatRequest(
            sessionId == null ? null : String.valueOf(sessionId),
            message,
            false,
            false,
            null,
            List.of());
    streamChat(request, emitter, null);
  }

  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
  // OnlyOneReturn 豁免理由（tighten-pmd-residual-325 任务 6.2）：前置校验各失败分支均需「发错误事件 + complete 后终止」，属守卫式早返回；
  // 拆分后仍无法等价合并为单出口，机械合并需引入完成标志贯穿全流程，伤害可读性（Q7 拍板口径）。
  private void doStreamChat(
      RoleAO currentUser, AssistantChatRequest request, SseEmitter emitter, AiChatResume resume) {
    BaseUnit.setCurrentRole(currentUser);
    Long userId = currentUser == null ? null : currentUser.getId();

    try {
      if (AiChatResumeSupport.rejectIfBlankMessage(request, emitter, eventWriter)) {
        return;
      }

      Long requestedSessionId =
          AiChatResumeSupport.parseSessionId(request.sessionId(), emitter, eventWriter);
      if (requestedSessionId == null
          && request.sessionId() != null
          && !request.sessionId().isBlank()) {
        return;
      }
      if (AiChatResumeSupport.resumeRequestedAndCompleted(
          currentUser,
          emitter,
          resume,
          requestedSessionId,
          aiSessionService,
          aiStreamRegistry,
          eventWriter)) {
        return;
      }

      if (AiChatResumeSupport.rejectIfRateLimited(userId, emitter, eventWriter, aiRateLimiter)) {
        return;
      }

      AiSessionEntity session =
          requestedSessionId == null
              ? null
              : aiSessionService.getOwnedSession(requestedSessionId, userId);
      boolean isNewSession = false;
      if (session == null) {
        session = aiSessionService.createSession(userId, null);
        isNewSession = true;
      } else if (AiChatResumeSupport.rejectIfArchived(session, emitter, eventWriter)) {
        return;
      }

      boolean needTitle = isNewSession || DEFAULT_TITLE.equals(session.getTitle());
      ImagePreparation imagePrep = prepareImageContext(session.getId(), request, emitter);
      if (imagePrep == null) {
        // 图片分支内部已发错误事件并 complete，直接终止
        return;
      }

      dispatchChatStream(currentUser, request, emitter, session, imagePrep, needTitle);
    } catch (Exception exception) {
      log.error("AI chat failed, sessionId={}", request.sessionId(), exception);
      eventWriter.sendError(emitter, "LLM_ERROR", "模型调用失败，请稍后重试");
      emitter.complete();
    } finally {
      BaseUnit.removeCurrentId();
    }
  }

  /** 图片上下文准备结果；null 表示图片分支已发错误并终止会话 */
  private record ImagePreparation(
      java.util.Optional<AiChatImageUnderstandingService.UnderstandingContext> context) {}

  /** 解析图片引用为理解上下文：未带图片引用返回空上下文；服务未就绪或引用无效时发错误事件并 complete 后返回 null。 */
  private ImagePreparation prepareImageContext(
      Long finalSessionId, AssistantChatRequest request, SseEmitter emitter) {
    ImagePreparation result;
    if (request.imageRef() == null || request.imageRef().isBlank()) {
      result = new ImagePreparation(java.util.Optional.empty());
    } else if (aiChatImageService == null || aiChatImageUnderstandingService == null) {
      eventWriter.sendError(emitter, "DEPENDENCY_UNAVAILABLE", "图片服务未就绪");
      emitter.complete();
      result = null;
    } else {
      java.util.Optional<AiChatImageEntity> image =
          aiChatImageService.findByRef(finalSessionId, request.imageRef());
      if (image.isEmpty()) {
        eventWriter.sendError(emitter, "PARAM_INVALID", "图片引用无效或无权访问");
        emitter.complete();
        result = null;
      } else {
        result =
            new ImagePreparation(
                aiChatImageUnderstandingService.understand(
                    image.get(), request.message(), request.useKnowledgeBase()));
      }
    }
    return result;
  }

  /** 持有接管锁后的主链路：构建消息与检索上下文 → 落库占位 → 注册活跃流 → 订阅模型流式输出 */
  private void dispatchChatStream(
      RoleAO currentUser,
      AssistantChatRequest request,
      SseEmitter emitter,
      AiSessionEntity session,
      ImagePreparation imagePrep,
      boolean needTitle) {
    Long finalSessionId = session.getId();
    Long userId = currentUser == null ? null : currentUser.getId();
    java.util.Optional<AiChatImageUnderstandingService.UnderstandingContext> imageContext =
        imagePrep.context();
    float[] imageVector =
        imageContext
            .map(AiChatImageUnderstandingService.UnderstandingContext::imageVector)
            .orElse(null);
    Lock takeoverLock = aiStreamRegistry.takeoverLock(finalSessionId);
    takeoverLock.lock();
    try {
      // 接管只顶替注册表；旧流在下一个 shouldAbort 检查点协作退出，此处不需要旧流句柄。

      String message = request.message();
      List<Message> messages = promptService.buildMessages(finalSessionId, message);
      imageContext.ifPresent(
          context ->
              messages.add(
                  1, new SystemMessage(AiChatResumeSupport.toImageContextPrompt(context))));
      AiChatKnowledgeRetrievalService.RetrievalOutcome retrieval =
          knowledgeRetrievalService == null
              ? AiChatKnowledgeRetrievalService.RetrievalOutcome.empty()
              : knowledgeRetrievalService.retrieve(
                  AiChatResumeSupport.lastUserMessage(messages),
                  userId,
                  imageVector,
                  request.useKnowledgeBase());
      if (retrieval.context() != null && !retrieval.context().isBlank()) {
        messages.add(imageContext.isPresent() ? 2 : 1, new SystemMessage(retrieval.context()));
      }
      aiMessageService.saveMessage(finalSessionId, "user", "text", message, null);
      AiMessageEntity assistantMessage = assistantMessageStore.createPlaceholder(finalSessionId);
      AiStreamRegistry.ActiveStream activeStream =
          new AiStreamRegistry.ActiveStream(
              finalSessionId, emitter, java.util.UUID.randomUUID().toString());
      activeStream.setAssistantMessageId(assistantMessage.getId());
      eventWriter.sendBufferedEvent(
          activeStream,
          "start",
          eventWriter.toStartJson(
              String.valueOf(finalSessionId),
              assistantMessage.getId(),
              activeStream.getGenerationId()));
      eventWriter.sendBufferedEvent(
          activeStream,
          "meta",
          eventWriter.toMetaJson(
              resolveProvider(),
              resolveModelName(),
              request.useKnowledgeBase(),
              request.thinking()));
      if (retrieval.hasSources()) {
        eventWriter.sendBufferedEvent(
            activeStream, "sources", eventWriter.toSourcesJson(retrieval.sources()));
      }

      List<ToolCallback> toolCallbacks = permittedToolCallbacks(currentUser);
      activeStream.setContext(
          AiChatStreamContext.initial(
              finalSessionId,
              emitter,
              messages,
              toolCallbacks,
              System.currentTimeMillis(),
              currentUser,
              request.thinking()));
      activeStream.setSources(retrieval.sources());
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
  }

  private void generateTitleAsync(
      Long sessionId,
      Long userId,
      String message,
      boolean needTitle,
      AiStreamRegistry.ActiveStream activeStream) {
    if (!needTitle) {
      return;
    }
    CompletableFuture.runAsync(
        () -> {
          String title = promptService.generateTitle(chatClientBuilder, message);
          if (title != null && !title.isBlank()) {
            aiSessionService.updateTitle(sessionId, userId, title);
            if (!activeStream.isFinished()) {
              eventWriter.sendBufferedEvent(activeStream, "title", eventWriter.toTitleJson(title));
            }
          }
        },
        aiTitleExecutor);
  }

  private List<ToolCallback> permittedToolCallbacks(RoleAO currentUser) {
    if (currentUser != null
        && (currentUser.getPermissions() == null || currentUser.getPermissions().isEmpty())) {
      currentUser.setPermissions(permissionService.getPermissionList(currentUser.getRoleId()));
    }
    return aiToolRegistry.getPermittedToolCallbacks(currentUser);
  }

  private String resolveProvider() {
    ModelProvider modelProvider =
        modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
    String result;
    if (modelProvider != null) {
      result = modelProvider.provider();
    } else {
      // ModelProvider 缺失时沿用当前 CRM 基线，避免阻塞 base 实现落地。
      result = environment.getProperty("spring.ai.chat.provider", "dashscope");
    }
    return result;
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
    boolean result = false;
    if (activeStream != null && !activeStream.isFinished()) {
      result = streamLifecycle.cancel(activeStream, sessionId);
    }
    return result;
  }
}
