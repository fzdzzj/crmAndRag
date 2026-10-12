package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 提示词模板反馈入参 DTO（prompt-template-enhance 任务 2.2）。
 *
 * <p>{@code version} 必须存在于该模板历史；{@code score} 取值 1-5（越界由 Service 拒绝）；{@code comment} ≤512 字符 （超长由
 * Service 截断校验拒绝）。
 */
@Data
public class PromptTemplateFeedbackDTO {

  /** 被反馈的模板版本号（必须存在于 history） */
  private Integer version;

  /** 评分 1-5 */
  private Integer score;

  /** 反馈意见（≤512 字符） */
  private String comment;
}
