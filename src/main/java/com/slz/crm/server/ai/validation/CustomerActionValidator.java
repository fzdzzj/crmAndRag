package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiCustomerDraftPayloadDTO;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 新增客户草稿校验器（CREATE_CUSTOMER） 声明式校验：JSR-303 注解判定合法性；belongGroup 为文本透传，不做存在性校验 */
@Slf4j
@Component
public class CustomerActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CUSTOMER.getValue();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private Validator validator;

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiCustomerDraftPayloadDTO payload = null;
    AiValidationResult result = null;
    try {
      payload = objectMapper.readValue(payloadJson, AiCustomerDraftPayloadDTO.class);
    } catch (Exception e) {
      log.warn("客户草稿 payload 解析失败", e);
      result = AiValidationResult.fail(List.of("companyName"), List.of("请提供客户公司名称"));
    }

    if (result == null) {
      Set<String> missingFields = new LinkedHashSet<>();
      List<String> questions = new ArrayList<>();

      Set<ConstraintViolation<AiCustomerDraftPayloadDTO>> violations = validator.validate(payload);
      for (ConstraintViolation<AiCustomerDraftPayloadDTO> violation : violations) {
        String fieldName = violation.getPropertyPath().toString();
        if (missingFields.add(fieldName)) {
          questions.add(
              DraftValidationUtils.resolveAskQuestion(
                  AiCustomerDraftPayloadDTO.class, fieldName, violation.getMessage()));
        }
      }

      if (missingFields.isEmpty()) {
        result = AiValidationResult.ok();
      } else {
        result = AiValidationResult.fail(new ArrayList<>(missingFields), questions);
      }
    }
    return result;
  }
}
