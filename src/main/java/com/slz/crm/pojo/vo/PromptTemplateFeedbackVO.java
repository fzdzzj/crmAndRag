package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提示词模板使用反馈 VO（prompt-template-enhance 任务 2.3）。
 *
 * <p>反馈列表按创建时间倒序返回，供模板管理者查看某模板各版本的使用评价。
 */
@Data
public class PromptTemplateFeedbackVO {

  private Long id;
  private Long templateId;
  private Integer version;
  private Integer score;
  private String comment;
  private Long operator;
  private LocalDateTime createdAt;
}
