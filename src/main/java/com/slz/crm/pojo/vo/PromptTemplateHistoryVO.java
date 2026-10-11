package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提示词模板版本历史 VO（add-prompt-template-admin 任务 2.3 / prompt-template-enhance 任务 2.3）。
 *
 * <p>{@code sourceVersion} 为 V34 新增：仅 REVERT 笔非空，记录被回滚的目标版本号，其余笔为 NULL。
 */
@Data
public class PromptTemplateHistoryVO {

  private Long id;
  private Long templateId;
  private Integer version;
  private Integer sourceVersion;
  private String content;
  private String variables;
  private String operation;
  private Long operator;
  private LocalDateTime operatedAt;
}
