package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.AssistantChatRequest;
import com.slz.crm.pojo.dto.AiChatRequestDTO;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.pojo.vo.AiMessageVO;
import com.slz.crm.pojo.vo.AiSessionVO;
import com.slz.crm.server.ai.AiChatImageService;
import com.slz.crm.server.ai.AiChatResume;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiChatService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 助手接口（会话管理 + SSE 流式对话） */
@RestController
@RequestMapping("/ai")
@Validated
@Slf4j
public class AiChatController {

  @Autowired private AiChatService aiChatService;

  @Autowired private AiSessionService aiSessionService;

  @Autowired private AiMessageService aiMessageService;

  @Autowired private AiProperties aiProperties;

  @Autowired private AiChatImageService aiChatImageService;

  /** 核心对话接口（SSE 流式） */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  // apply-permission-matrix 任务 1.2：AI 模块流式对话
  @RequirePermission(PermissionOperates.AI_CHAT_STREAM)
  public SseEmitter streamChat(
      @Valid @RequestBody AiChatRequestDTO dto,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    SseEmitter emitter = new SseEmitter(resolveSseTimeoutMillis());

    AssistantChatRequest request =
        new AssistantChatRequest(
            dto.getSessionId(),
            dto.getMessage(),
            dto.getUseKnowledgeBase(),
            dto.getThinking(),
            dto.getImageRef(),
            dto.getAttachments());
    AiChatResume resume =
        lastEventId != null && !lastEventId.isBlank()
            ? new AiChatResume(
                lastEventId.contains(":")
                    ? lastEventId.substring(0, lastEventId.indexOf(':'))
                    : null,
                lastEventId)
            : dto.getResume() == null
                ? null
                : new AiChatResume(
                    dto.getResume().getGenerationId(), dto.getResume().getLastEventId());
    aiChatService.streamChat(request, emitter, resume);

    return emitter;
  }

  /** 取消生成中的对话（幂等；已输出内容保留并标记 interrupted） */
  @PostMapping("/chat/cancel")
  // apply-permission-matrix 任务 1.2：AI 模块取消生成
  @RequirePermission(PermissionOperates.AI_CHAT_CANCEL)
  public Result<Boolean> cancelChat(@RequestParam Long sessionId) {
    boolean cancelled = aiChatService.cancelStream(sessionId, BaseUnit.getCurrentId());

    return Result.success(cancelled);
  }

  /** 上传助手会话聊天图片，返回后续对话可用的 imageRef。 */
  @PostMapping("/sessions/{sessionId}/images")
  // apply-permission-matrix 任务 1.2：AI 模块会话管理
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<AiChatImageUploadVO> uploadImage(
      @PathVariable Long sessionId, @RequestParam("file") MultipartFile file) {
    Result<AiChatImageUploadVO> result;
    AiSessionEntity session = aiSessionService.getOwnedSession(sessionId, BaseUnit.getCurrentId());
    if (session == null) {
      result = Result.error(ErrorCode.PERMISSION_DENIED, "无权访问该AI会话");
    } else if (file == null || file.isEmpty()) {
      result = Result.error(ErrorCode.PARAM_REQUIRED, "请上传图片");
    } else {
      String contentType = file.getContentType();
      if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
        result = Result.error(ErrorCode.FILE_FORMAT_ERROR, "仅支持图片文件");
      } else {
        try {
          AiChatImageEntity image =
              aiChatImageService.save(
                  sessionId, BaseUnit.getCurrentId(), contentType, file.getBytes());
          result =
              Result.success(
                  new AiChatImageUploadVO(
                      image.getId(), image.getImageHash(), String.valueOf(image.getId())));
        } catch (IOException exception) {
          log.warn("AI聊天图片上传读取失败，sessionId={}", sessionId, exception);
          result = Result.error(ErrorCode.FILE_READ_FAILED, "读取图片失败");
        }
      }
    }
    return result;
  }

  @PostMapping("/sessions")
  // apply-permission-matrix 任务 1.2：AI 模块会话管理
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<AiSessionVO> createSession(@RequestParam(required = false) String title) {
    AiSessionEntity session = aiSessionService.createSession(BaseUnit.getCurrentId(), title);

    AiSessionVO vo = new AiSessionVO();

    org.springframework.beans.BeanUtils.copyProperties(session, vo);

    return Result.success(vo);
  }

  /**
   * 当前用户会话列表（滚动查询，按最后活跃时间倒序）
   *
   * @param cursorTime 游标时间（上一页最后一条的 updatedTime，首页不传）
   * @param cursorId 游标ID（上一页最后一条的 id，首页不传）
   * @param limit 每页条数（默认 10）
   */
  @GetMapping("/sessions")
  // apply-permission-matrix 任务 1.2：AI 模块会话管理
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<List<AiSessionVO>> listSessions(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime cursorTime,
      @RequestParam(required = false) Long cursorId,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer limit) {
    final Result<List<AiSessionVO>> result;
    if (isInvalidLimit(limit)) {
      result = Result.error(ErrorCode.PARAM_OUT_OF_RANGE, "limit 必须在 1 到 100 之间");
    } else {
      result =
          Result.success(
              aiSessionService.scrollSessions(
                  BaseUnit.getCurrentId(), cursorTime, cursorId, limit));
    }
    return result;
  }

  /**
   * 会话消息历史（滚动查询，按 id 升序）
   *
   * @param id 会话ID
   * @param afterId 游标：只返回 id 大于此值的消息（首次加载不传或传 0）
   * @param limit 条数上限（默认 20）
   */
  @GetMapping("/sessions/{id}/messages")
  // apply-permission-matrix 任务 1.2：AI 模块会话管理
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<List<AiMessageVO>> listMessages(
      @PathVariable Long id,
      @RequestParam(required = false) Long afterId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer limit) {
    final Result<List<AiMessageVO>> result;
    if (isInvalidLimit(limit)) {
      result = Result.error(ErrorCode.PARAM_OUT_OF_RANGE, "limit 必须在 1 到 100 之间");
    } else if (aiSessionService.getOwnedSession(id, BaseUnit.getCurrentId()) == null) {
      result = Result.error("会话不存在或无权访问");
    } else {
      result = Result.success(aiMessageService.scrollMessages(id, afterId, limit));
    }
    return result;
  }

  /** 归档会话（软删 status=0），并级联取消其 PENDING 待确认操作 */
  @DeleteMapping("/sessions/{id}")
  // apply-permission-matrix 任务 1.2：AI 模块会话管理
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<Boolean> archiveSession(@PathVariable Long id) {
    aiSessionService.archiveSession(id, BaseUnit.getCurrentId());

    return Result.success(true);
  }

  /** 显式兜底校验分页上限，避免测试容器或旧调用链绕过方法级校验。 */
  private boolean isInvalidLimit(Integer limit) {
    return limit == null || limit < 1 || limit > 100;
  }

  /** SSE 超时必须有限；未配置或配置非法时使用 300 秒默认值。 */
  private long resolveSseTimeoutMillis() {
    Integer timeoutSeconds = aiProperties.getSseTimeoutSeconds();
    if (timeoutSeconds == null || timeoutSeconds <= 0) {
      timeoutSeconds = 300;
    }
    return timeoutSeconds * 1000L;
  }

  /** 聊天图片上传结果；imageRef 默认使用本表 ID。 */
  public record AiChatImageUploadVO(Long imageId, String imageHash, String imageRef) {}
}
