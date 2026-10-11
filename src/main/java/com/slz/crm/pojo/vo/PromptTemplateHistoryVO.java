package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** 提示词模板版本历史 VO（add-prompt-template-admin 任务 2.3）。 */
@Data
public class PromptTemplateHistoryVO {

  private Long id;
  private Long templateId;
  private Integer version;
  private String content;
  private String variables;
  private String operation;
  private Long operator;
  private LocalDateTime operatedAt;
}
