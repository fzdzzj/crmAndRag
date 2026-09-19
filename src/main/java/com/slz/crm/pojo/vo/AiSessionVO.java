package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** AI 会话 VO */
@Data
public class AiSessionVO {
  private Long id;
  private String title;
  private Integer status;
  private LocalDateTime createdTime;
  private LocalDateTime updatedTime;
}
