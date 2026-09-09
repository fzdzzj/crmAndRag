package com.slz.crm.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 聊天请求 DTO
 */
@Data
public class AiChatRequestDTO {
    private Long sessionId;

    /** 用户输入内容；限制长度可避免异常请求直接放大模型与上下文开销 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息长度不能超过 4000 个字符")
    private String message;
}
