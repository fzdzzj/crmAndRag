package com.slz.crm.server.ai.validation;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** AI 草稿校验结果 */
@Data
@Builder
public class AiValidationResult {
  /** 是否校验通过 */
  private boolean valid;

  /** 缺失/非法字段名列表 */
  private List<String> missingFields;

  /** 追问文案列表 */
  private List<String> questions;

  /** 实体解析后的修正 payload（名称→ID 解析成功时非 null，状态机以本字段落库） */
  private String resolvedPayload;

  public static AiValidationResult ok() {
    return AiValidationResult.builder()
        .valid(true)
        .missingFields(List.of())
        .questions(List.of())
        .build();
  }

  public static AiValidationResult ok(String resolvedPayload) {
    return AiValidationResult.builder()
        .valid(true)
        .missingFields(List.of())
        .questions(List.of())
        .resolvedPayload(resolvedPayload)
        .build();
  }

  public static AiValidationResult fail(List<String> missingFields, List<String> questions) {
    return AiValidationResult.builder()
        .valid(false)
        .missingFields(missingFields)
        .questions(questions)
        .build();
  }
}
