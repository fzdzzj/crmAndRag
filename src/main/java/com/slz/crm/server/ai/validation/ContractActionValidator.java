package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiContractDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiOrderItemDraftDTO;
import com.slz.crm.server.ai.AiEntityResolver;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 合同创建草稿校验器（CREATE_CONTRACT） 校验合同字段 + 订单明细；商机双通道（opportunityId 或名称）由 AiEntityResolver 确定性解析；
 * 订单不校验合同归属（执行时自动挂到新合同），扩展字段只需修改 AiContractDraftPayloadDTO
 */
@Slf4j
@Component
public class ContractActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CONTRACT.getValue();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @org.springframework.beans.factory.annotation.Autowired private Validator validator;

  @org.springframework.beans.factory.annotation.Autowired private AiEntityResolver entityResolver;

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiContractDraftPayloadDTO payload = null;
    AiValidationResult result = null;
    try {
      payload = objectMapper.readValue(payloadJson, AiContractDraftPayloadDTO.class);
    } catch (JsonProcessingException e) {
      log.warn("合同草稿 payload 解析失败", e);
      result = AiValidationResult.fail(List.of("contractName"), List.of("请提供合同信息（合同名称、关联商机、合同金额）"));
    }

    if (result == null) {
      Set<String> missingFields = new LinkedHashSet<>();
      List<String> questions = new ArrayList<>();
      boolean resolved = false;

      // 实体解析：商机双通道（opportunityId 或名称/公司/联系人）
      resolved = resolveOpportunityChannel(payload, missingFields, questions, resolved);
      // 校验合同字段（JSR-303 声明式校验）
      validateContractFields(payload, missingFields, questions);
      // 校验订单明细
      validateOrderItems(payload, missingFields, questions);

      result = finishValidation(payload, missingFields, questions, resolved);
    }
    return result;
  }

  /** 商机双通道解析：RESOLVED 回填 ID，MISSING/NOT_FOUND/AMBIGUOUS/FALLBACK 生成对应补问 */
  private boolean resolveOpportunityChannel(
      AiContractDraftPayloadDTO payload,
      Set<String> missingFields,
      List<String> questions,
      boolean resolved) {
    AiEntityResolver.Resolution resolution =
        entityResolver.resolveOpportunity(
            payload.getOpportunityId(), payload.getOpportunityName(),
            payload.getOpportunityCompanyName(), payload.getOpportunityContactName());
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
                  AiContractDraftPayloadDTO.class, "opportunityId", "这个合同关联哪个商机？"));
        }
      }
      case NOT_FOUND -> {
        if (missingFields.add("opportunityId")) {
          questions.add("未找到对应商机，请确认商机名称或公司名称");
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
              "未找到对应商机，以下是你名下的商机："
                  + String.join("、", resolution.getCandidates())
                  + "，请选择（或通过下拉框搜索）");
        }
      }
    }
    return resolved;
  }

  /** 合同本体 JSR-303 声明式校验，违规字段记入补问集合 */
  private void validateContractFields(
      AiContractDraftPayloadDTO payload, Set<String> missingFields, List<String> questions) {
    Set<ConstraintViolation<AiContractDraftPayloadDTO>> contractViolations =
        validator.validate(payload);
    for (ConstraintViolation<AiContractDraftPayloadDTO> violation : contractViolations) {
      String fieldName = violation.getPropertyPath().toString();
      if (missingFields.add(fieldName)) {
        questions.add(
            DraftValidationUtils.resolveAskQuestion(
                AiContractDraftPayloadDTO.class, fieldName, violation.getMessage()));
      }
    }
  }

  /** 订单明细整体校验：缺失整组补问，逐条明细再走条目级校验 */
  private void validateOrderItems(
      AiContractDraftPayloadDTO payload, Set<String> missingFields, List<String> questions) {
    if (payload.getOrders() == null || payload.getOrders().isEmpty()) {
      if (missingFields.add("orders")) {
        questions.add("请提供订单明细（产品、数量、金额）");
      }
      return;
    }
    for (AiOrderItemDraftDTO item : payload.getOrders()) {
      validateOrderItem(item, missingFields, questions);
    }
  }

  /** 单条订单明细校验：JSR-303 声明式校验 + amount 缺省推算与一致性两条业务规则 */
  private void validateOrderItem(
      AiOrderItemDraftDTO item, Set<String> missingFields, List<String> questions) {
    Set<ConstraintViolation<AiOrderItemDraftDTO>> violations = validator.validate(item);
    for (ConstraintViolation<AiOrderItemDraftDTO> violation : violations) {
      String fieldName = violation.getPropertyPath().toString();
      if (missingFields.add(fieldName)) {
        questions.add(
            DraftValidationUtils.resolveAskQuestion(
                AiOrderItemDraftDTO.class, fieldName, violation.getMessage()));
      }
    }
    checkOrderItemAmount(item, missingFields, questions);
  }

  /** 订单金额业务规则：amount 缺省时需 quantity×unitPrice 可推算；三者齐全时校验金额一致性 */
  private void checkOrderItemAmount(
      AiOrderItemDraftDTO item, Set<String> missingFields, List<String> questions) {
    // 业务规则：amount 缺省时需 quantity×unitPrice 可推算
    if (item.getAmount() == null
        && !(item.getQuantity() != null && item.getUnitPrice() != null)
        && missingFields.add("amount")) {
      questions.add(
          DraftValidationUtils.resolveAskQuestion(AiOrderItemDraftDTO.class, "amount", "订单金额是多少？"));
    }
    // 业务规则：amount 一致性校验
    if (item.getAmount() != null && item.getQuantity() != null && item.getUnitPrice() != null) {
      BigDecimal expected = item.getQuantity().multiply(item.getUnitPrice());
      if (expected.compareTo(item.getAmount()) != 0 && missingFields.add("amount")) {
        questions.add("金额与数量×单价不一致（" + expected + "），请确认订单金额");
      }
    }
  }

  /** 校验收尾：完全通过时返回 ok（发生字段重写则序列化修正后的 payload），否则返回补问 */
  private AiValidationResult finishValidation(
      AiContractDraftPayloadDTO payload,
      Set<String> missingFields,
      List<String> questions,
      boolean resolved) {
    AiValidationResult result;
    if (missingFields.isEmpty()) {
      // 名称解析成功时返回修正后的 payload（状态机以此落库）
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
