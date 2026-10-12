package com.slz.crm.pojo.dto;

import java.util.Map;
import lombok.Data;

/**
 * 提示词模板渲染入参 DTO（add-prompt-template-admin 任务 2.2/2.3 + add-prompt-template-slots 任务 1.2）。
 *
 * <p>入参 {@code code} 定位启用态模板，{@code variables} 为渲染变量键值表；渲染接口只做纯 Java 占位符组装， 零真实模型外呼。{@code slots}
 * 为可选分段槽位键值表：与 {@code variables} 语义分离（槽位=运行时可选整段注入，不参与 variables 必填/未定义校验）， 缺省 null 等价空表、旧调用零行为变化。
 */
@Data
public class PromptTemplateRenderDTO {

  /** 模板业务键 */
  private String code;

  /** 渲染变量键值表（{@code name → value}） */
  private Map<String, String> variables;

  /**
   * 可选分段槽位键值表（{@code key → 整段成品值}），槽位不参与 variables 校验，缺省 null 等价空表（add-prompt-template-slots 任务
   * 1.2）。
   */
  private Map<String, String> slots;
}
