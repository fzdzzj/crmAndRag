package com.slz.crm.pojo.dto;

import java.util.Map;
import lombok.Data;

/**
 * 提示词模板渲染入参 DTO（add-prompt-template-admin 任务 2.2/2.3）。
 *
 * <p>入参 {@code code} 定位启用态模板，{@code variables} 为渲染变量键值表；渲染接口只做纯 Java 占位符组装， 零真实模型外呼。
 */
@Data
public class PromptTemplateRenderDTO {

  /** 模板业务键 */
  private String code;

  /** 渲染变量键值表（{@code name → value}） */
  private Map<String, String> variables;
}
