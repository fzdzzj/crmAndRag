package com.slz.crm.pojo.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 消息 VO
 */
@Data
public class AiMessageVO {
    private Long id;
    private String role;
    private String msgType;
    private String content;
    private String payload;
    private LocalDateTime createdTime;
}