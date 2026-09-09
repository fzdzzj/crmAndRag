package com.slz.crm.pojo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 助手流式对话请求。
 *
 * <p>{@code sessionId} 上送为字符串，后端解析成数据库 Long，避免前端 JS 精度丢失。</p>
 */
@Data
public class AiChatRequestDTO {
    /** 会话 id；数字字符串，新建会话可空。 */
    @Pattern(regexp = "^\\d+$", message = "sessionId 必须是数字")
    private String sessionId;

    /** 用户输入内容；限制长度可避免异常请求直接放大模型与上下文开销 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息长度不能超过 4000 个字符")
    private String message;

    /** 手动知识库开关；业务工具仍由 LLM 自动决定。 */
    private Boolean useKnowledgeBase;

    /** 思考过程透传开关；默认快速模式。 */
    private Boolean thinking;

    /** 助手聊天图片引用；不传绝不回退上一张图。 */
    @Size(max = 200, message = "imageRef 长度不能超过 200 个字符")
    private String imageRef;

    /** 附件引用；本任务仅透传，KB 能力由 Lane B 接线。 */
    private List<@Size(max = 200, message = "附件引用长度不能超过 200 个字符") String> attachments;

    /** 断线重连参数；优先级低于 SSE Last-Event-ID 头。 */
    @Valid
    private Resume resume;

    /** 断线续传请求参数。 */
    @Data
    public static class Resume {
        /** 要续传的 generationId。 */
        @NotBlank(message = "resume.generationId 不能为空")
        @Size(max = 100, message = "resume.generationId 长度不能超过 100 个字符")
        private String generationId;

        /** 客户端最后收到的事件 id；空表示从当前 generation 首条重放。 */
        @Size(max = 150, message = "resume.lastEventId 长度不能超过 150 个字符")
        private String lastEventId;
    }
}
