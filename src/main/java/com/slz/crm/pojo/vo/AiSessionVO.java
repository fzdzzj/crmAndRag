package com.slz.crm.pojo.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 会话 VO
 */
@Data
public class AiSessionVO {
    private Long id;
    private String title;
    private Integer status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}