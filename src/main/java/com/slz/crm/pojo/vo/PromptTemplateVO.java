package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提示词模板 VO（列表/详情，add-prompt-template-admin 任务 2.3）。
 *
 * <p>列表不含正文（{@code content} 为空），详情含正文与变量定义。
 */
@Data
public class PromptTemplateVO {

  private Long id;
  private String code;
  private String name;
  private String category;
  private String content;
  private String variables;
  private Integer version;
  private Integer status;
  private String remark;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
