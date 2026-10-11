package com.slz.crm.pojo.vo;

import lombok.Data;

/**
 * 提示词模板渲染结果 VO（add-prompt-template-admin 任务 2.2/2.3）。
 *
 * <p>返回组装文本与实际使用的模板版本（可追溯）；渲染为纯 Java 占位符替换，零真实模型外呼。
 */
@Data
public class PromptTemplateRenderVO {

  /** 渲染组装后的正文 */
  private String content;

  /** 实际使用的模板版本号 */
  private Integer version;

  /** 使用的模板业务键 */
  private String code;
}
