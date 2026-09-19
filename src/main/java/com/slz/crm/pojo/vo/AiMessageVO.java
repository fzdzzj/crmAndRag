package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** AI 消息 VO */
@Data
public class AiMessageVO {
  private Long id;
  private String role;
  private String msgType;
  private String content;
  private String payload;
  private LocalDateTime createdTime;
}
