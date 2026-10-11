package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提示词模板渲染/变量校验协作类（add-prompt-template-admin 任务 2.2）。
 *
 * <p>纯函数协作：负责 variables JSON 解析（坏 JSON 即拒）、渲染变量定义校验（未定义变量/必填缺失）、{{var}} 占位符替换（纯 Java，不用
 * MessageFormat）。从 {@link PromptTemplateServiceImpl} 抽出以收敛其圈复杂度与 类规模，本协作类无事务、无 Spring 依赖（与
 * AssistRequest 拆分同构但更轻）。
 */
final class PromptTemplateRenderSupport {

  private final ObjectMapper objectMapper;

  PromptTemplateRenderSupport(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** create/update 入参加载 variables：null/空放行，非空 JSON 数组坏格式即拒（单返回）。 */
  String parseVariablesOrReject(String variables) {
    String result = variables;
    if (result != null && !result.isBlank()) {
      parseVariableDefinitions(result);
    } else {
      result = null;
    }
    return result;
  }

  /** 解析变量定义 JSON 数组；坏 JSON 抛业务异常（单返回，specific catch）。 */
  List<PromptTemplateVariable> parseVariableDefinitions(String variables) {
    List<PromptTemplateVariable> result;
    if (variables == null || variables.isBlank()) {
      result = List.of();
    } else {
      try {
        result =
            objectMapper.readValue(variables, new TypeReference<List<PromptTemplateVariable>>() {});
      } catch (JsonProcessingException e) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "变量定义 variables 必须是合法 JSON 数组");
      }
    }
    return result;
  }

  /** 未定义变量提供值 → 抛业务异常（防静默吞错）。 */
  void rejectUndefined(List<PromptTemplateVariable> definitions, Map<String, String> provided) {
    Set<String> definedNames =
        definitions.stream()
            .map(PromptTemplateVariable::getName)
            .filter(n -> n != null && !n.isBlank())
            .collect(Collectors.toSet());
    List<String> undefined =
        provided.keySet().stream().filter(k -> !definedNames.contains(k)).sorted().toList();
    if (!undefined.isEmpty()) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "模板未定义变量：" + String.join(", ", undefined));
    }
  }

  /** required 变量缺失 → 抛业务异常。 */
  void rejectMissingRequired(
      List<PromptTemplateVariable> definitions, Map<String, String> provided) {
    List<String> missing =
        definitions.stream()
            .filter(d -> Boolean.TRUE.equals(d.isRequired()))
            .map(PromptTemplateVariable::getName)
            .filter(n -> provided.get(n) == null || provided.get(n).isBlank())
            .sorted()
            .toList();
    if (!missing.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "必填变量缺失：" + String.join(", ", missing));
    }
  }

  /** 纯 Java 占位符替换（不用 MessageFormat，避免格式符歧义；单返回）。 */
  String replacePlaceholders(String content, Map<String, String> provided) {
    String rendered = content;
    for (Map.Entry<String, String> entry : provided.entrySet()) {
      String value = entry.getValue();
      if (value != null) {
        rendered = rendered.replace("{{" + entry.getKey() + "}}", value);
      }
    }
    return rendered;
  }

  /** 模板变量定义（variables JSON 数组元素）。 */
  static class PromptTemplateVariable {
    private String name;
    private Boolean required;
    private String description;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Boolean isRequired() {
      return required;
    }

    public void setRequired(Boolean required) {
      this.required = required;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }
  }
}
