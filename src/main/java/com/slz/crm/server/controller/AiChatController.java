package com.slz.crm.server.controller;

import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.pojo.dto.AiChatRequestDTO;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.pojo.vo.AiMessageVO;
import com.slz.crm.pojo.vo.AiSessionVO;
import com.slz.crm.server.service.AiChatService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.properties.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 助手接口（会话管理 + SSE 流式对话）
 */
@RestController
@RequestMapping("/ai")
@Validated
@Slf4j

public class AiChatController {


    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AiMessageService aiMessageService;

    @Autowired
    private AiProperties aiProperties;
    /**
     * 核心对话接口（SSE 流式）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AiChatRequestDTO dto) {
        SseEmitter emitter = new SseEmitter(resolveSseTimeoutMillis());

        aiChatService.streamChat(dto.getSessionId(), dto.getMessage(), emitter);

        return emitter;

    }

    /**
     * 取消生成中的对话（幂等；已输出内容保留并标记 interrupted）
     */
    @PostMapping("/chat/cancel")
    public Result<Boolean> cancelChat(@RequestParam Long sessionId) {
        boolean cancelled = aiChatService.cancelStream(sessionId, BaseUnit.getCurrentId());

        return Result.success(cancelled);

    }

    /**
     * 新建会话
     */
    @PostMapping("/sessions")
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
     * @param cursorId   游标ID（上一页最后一条的 id，首页不传）
     * @param limit      每页条数（默认 10）
     */
    @GetMapping("/sessions")
    public Result<List<AiSessionVO>> listSessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorTime,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer limit) {
        if (isInvalidLimit(limit)) {
            return Result.error(ErrorCode.PARAM_OUT_OF_RANGE, "limit 必须在 1 到 100 之间");
        }
        return Result.success(aiSessionService.scrollSessions(BaseUnit.getCurrentId(), cursorTime, cursorId, limit));

    }

    /**
     * 会话消息历史（滚动查询，按 id 升序）
     *
     * @param id      会话ID
     * @param afterId 游标：只返回 id 大于此值的消息（首次加载不传或传 0）
     * @param limit   条数上限（默认 20）
     */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<AiMessageVO>> listMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer limit) {
        if (isInvalidLimit(limit)) {
            return Result.error(ErrorCode.PARAM_OUT_OF_RANGE, "limit 必须在 1 到 100 之间");
        }
        if (aiSessionService.getOwnedSession(id, BaseUnit.getCurrentId()) == null) {

            return Result.error("会话不存在或无权访问");

        }
        return Result.success(aiMessageService.scrollMessages(id, afterId, limit));

    }

    /**
     * 归档会话（软删 status=0），并级联取消其 PENDING 待确认操作
     */
    @DeleteMapping("/sessions/{id}")
    public Result<Boolean> archiveSession(@PathVariable Long id) {
        aiSessionService.archiveSession(id, BaseUnit.getCurrentId());

        return Result.success(true);

    }

    /**
     * 显式兜底校验分页上限，避免测试容器或旧调用链绕过方法级校验。
     */
    private boolean isInvalidLimit(Integer limit) {
        return limit == null || limit < 1 || limit > 100;
    }

    /**
     * SSE 超时必须有限；未配置或配置非法时使用 300 秒默认值。
     */
    private long resolveSseTimeoutMillis() {
        Integer timeoutSeconds = aiProperties.getSseTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 300;
        }
        return timeoutSeconds * 1000L;
    }
}
