package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiBusinessActivityDraftPayloadDTO;
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
 * 新增业务活动草稿校验器（CREATE_BUSINESS_ACTIVITY） 商机双通道（opportunityId 或 opportunityName）由 AiEntityResolver
 * 确定性解析，其余字段 JSR-303 校验 extend-ai-draft-and-eval 任务 2.2（裁决假设 5：仅主实体 create，addContacts/addUsers
 * 关联子操作不入卡）
 */
@Slf4j
@Component
public class BusinessActivityActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private Validator validator;

  @Autowired private AiEntityResolver entityResolver;

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiBusinessActivityDraftPayloadDTO payload = null;
    AiValidationResult result = null;
    try {
      payload = objectMapper.readValue(payloadJson, AiBusinessActivityDraftPayloadDTO.class);
    } catch (JsonProcessingException e) {
      log.warn("业务活动草稿 payload 解析失败", e);
      result = AiValidationResult.fail(List.of("activityTitle"), List.of("请提供业务活动信息（关联商机、活动标题）"));
    }

    if (result == null) {
      result = validatePayload(payload);
    }
    return result;
  }

  /** payload 解析成功后执行实体解析与声明式校验；只在完全通过且发生字段重写时返回 ok。 */
  private AiValidationResult validatePayload(AiBusinessActivityDraftPayloadDTO payload) {
    Set<String> missingFields = new LinkedHashSet<>();
    List<String> questions = new ArrayList<>();
    boolean resolved = false;

    // 实体解析：商机双通道（opportunityId 或 opportunityName）
    AiEntityResolver.Resolution resolution =
        entityResolver.resolveOpportunity(
            payload.getOpportunityId(), payload.getOpportunityName(), null, null);
    switch (resolution.getStatus()) {
      case RESOLVED -> {
        if (!resolution.getResolvedId().equals(payload.getOpportunityId())) {
          payload.setOpportunityId(resolution.getResolvedId());
          resolved = true;
        }
      }
      case MISSING -> {
        if (missingFields.add("opportunityId")) {
          questions.add(
              DraftValidationUtils.resolveAskQuestion(
                  AiBusinessActivityDraftPayloadDTO.class, "opportunityId", "这笔业务活动挂在哪个商机下？"));
        }
      }
      case NOT_FOUND -> {
        if (missingFields.add("opportunityId")) {
          questions.add("未找到对应商机，请确认商机名称");
        }
      }
      case AMBIGUOUS -> {
        if (missingFields.add("opportunityId")) {
          questions.add("匹配到多个商机：" + String.join("、", resolution.getCandidates()) + "，请明确是哪一个");
        }
      }
      case FALLBACK -> {
        if (missingFields.add("opportunityId")) {
          questions.add(
              "未找到名为\""
                  + payload.getOpportunityName()
                  + "\"的商机，以下是你名下的商机："
                  + String.join("、", resolution.getCandidates())
                  + "，请选择（或通过下拉框搜索）");
        }
      }
    }

    // JSR-303 声明式校验
    Set<ConstraintViolation<AiBusinessActivityDraftPayloadDTO>> violations =
        validator.validate(payload);
    for (ConstraintViolation<AiBusinessActivityDraftPayloadDTO> violation : violations) {
      String fieldName = violation.getPropertyPath().toString();
      if (missingFields.add(fieldName)) {
        questions.add(
            DraftValidationUtils.resolveAskQuestion(
                AiBusinessActivityDraftPayloadDTO.class, fieldName, violation.getMessage()));
      }
    }

    return finishValidation(payload, missingFields, questions, resolved);
  }

  /** 校验收尾：完全通过时返回 ok（发生字段重写则序列化修正后的 payload），否则返回补问 */
  private AiValidationResult finishValidation(
      AiBusinessActivityDraftPayloadDTO payload,
      Set<String> missingFields,
      List<String> questions,
      boolean resolved) {
    AiValidationResult result;
    if (missingFields.isEmpty()) {
      if (resolved) {
        try {
          result = AiValidationResult.ok(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
          log.warn("序列化解析后 payload 失败，回退原 payload", e);
          result = AiValidationResult.ok();
        }
      } else {
        result = AiValidationResult.ok();
      }
    } else {
      result = AiValidationResult.fail(new ArrayList<>(missingFields), questions);
    }
    return result;
  }
}
