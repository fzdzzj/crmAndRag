package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiOpportunityDraftPayloadDTO;
import com.slz.crm.server.ai.AiEntityResolver;
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

/**
 * 新增商机草稿校验器（CREATE_OPPORTUNITY） 客户双通道（companyId 或 companyName）由 AiEntityResolver 确定性解析，其余字段 JSR-303
 * 校验
 */
@Slf4j
@Component
public class OpportunityActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_OPPORTUNITY.getValue();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private Validator validator;

  @Autowired private AiEntityResolver entityResolver;

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiOpportunityDraftPayloadDTO payload;
    try {
      payload = objectMapper.readValue(payloadJson, AiOpportunityDraftPayloadDTO.class);
    } catch (Exception e) {
      log.warn("商机草稿 payload 解析失败", e);
      return AiValidationResult.fail(List.of("opportunityName"), List.of("请提供商机信息（所属客户、商机名称）"));
    }

    Set<String> missingFields = new LinkedHashSet<>();
    List<String> questions = new ArrayList<>();
    boolean resolved = false;

    // 实体解析：客户双通道（companyId 或 companyName）
    AiEntityResolver.Resolution resolution =
        entityResolver.resolveCustomerCompany(payload.getCompanyId(), payload.getCompanyName());
    switch (resolution.getStatus()) {
      case RESOLVED -> {
        if (!resolution.getResolvedId().equals(payload.getCompanyId())) {
          payload.setCompanyId(resolution.getResolvedId());
          resolved = true;
        }
      }
      case MISSING -> {
        if (missingFields.add("companyId")) {
          questions.add(
              DraftValidationUtils.resolveAskQuestion(
                  AiOpportunityDraftPayloadDTO.class, "companyId", "这个商机属于哪个客户公司？"));
        }
      }
      case NOT_FOUND -> {
        if (missingFields.add("companyId")) {
          questions.add("未找到对应客户公司，请确认公司名称");
        }
      }
      case AMBIGUOUS -> {
        if (missingFields.add("companyId")) {
          questions.add("匹配到多个客户公司：" + String.join("、", resolution.getCandidates()) + "，请明确是哪一个");
        }
      }
      case FALLBACK -> {
        if (missingFields.add("companyId")) {
          questions.add(
              "未找到名为\""
                  + payload.getCompanyName()
                  + "\"的客户公司，以下是你名下的客户："
                  + String.join("、", resolution.getCandidates())
                  + "，请选择（或通过下拉框搜索）");
        }
      }
    }

    // JSR-303 声明式校验
    Set<ConstraintViolation<AiOpportunityDraftPayloadDTO>> violations = validator.validate(payload);
    for (ConstraintViolation<AiOpportunityDraftPayloadDTO> violation : violations) {
      String fieldName = violation.getPropertyPath().toString();
      if (missingFields.add(fieldName)) {
        questions.add(
            DraftValidationUtils.resolveAskQuestion(
                AiOpportunityDraftPayloadDTO.class, fieldName, violation.getMessage()));
      }
    }

    if (missingFields.isEmpty()) {
      if (resolved) {
        try {
          return AiValidationResult.ok(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
          log.warn("序列化解析后 payload 失败，回退原 payload", e);
        }
      }
      return AiValidationResult.ok();
    }
    return AiValidationResult.fail(new ArrayList<>(missingFields), questions);
  }
}
