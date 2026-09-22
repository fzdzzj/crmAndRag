package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiOrderDraftPayloadDTO;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 订单草稿校验器（CREATE_ORDER） 声明式校验：JSR-303 注解判定合法性，@AskQuestion 注解提供追问文案， 合同双通道（contractId 或
 * contractName）由 AiEntityResolver 确定性解析， 扩展字段只需修改 AiOrderItemDraftDTO，无需改动本类
 */
@Slf4j
@Component
public class OrderActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_ORDER.getValue();

  @Autowired private Validator validator;

  @Autowired private AiEntityResolver entityResolver;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiOrderDraftPayloadDTO payload = null;
    AiValidationResult result = null;
    try {
      payload = objectMapper.readValue(payloadJson, AiOrderDraftPayloadDTO.class);
    } catch (Exception e) {
      log.warn("订单草稿 payload 解析失败", e);
      result = AiValidationResult.fail(List.of("orders"), List.of("请提供订单内容（合同、产品、数量、金额）"));
    }

    if (result == null && (payload.getOrders() == null || payload.getOrders().isEmpty())) {
      result = AiValidationResult.fail(List.of("orders"), List.of("请提供订单内容（合同、产品、数量、金额）"));
    }

    if (result == null) {
      Set<String> missingFields = new LinkedHashSet<>();
      List<String> questions = new ArrayList<>();
      boolean resolved = false;

      for (AiOrderItemDraftDTO item : payload.getOrders()) {

        // 实体解析：合同双通道（contractId 或 contractName）
        AiEntityResolver.Resolution resolution =
            entityResolver.resolveContract(item.getContractId(), item.getContractName());

        switch (resolution.getStatus()) {
          case RESOLVED -> {
            if (!resolution.getResolvedId().equals(item.getContractId())) {
              item.setContractId(resolution.getResolvedId());
              resolved = true;
            }
          }
          case MISSING -> {
            if (missingFields.add("contractId")) {
              questions.add(
                  DraftValidationUtils.resolveAskQuestion(
                      AiOrderItemDraftDTO.class, "contractId", "这笔订单挂在哪个合同下？"));
            }
          }
          case NOT_FOUND -> {
            if (missingFields.add("contractId")) {
              questions.add("未找到对应合同，请确认合同编号或名称");
            }
          }
          case AMBIGUOUS -> {
            if (missingFields.add("contractId")) {
              questions.add("匹配到多个合同：" + String.join("、", resolution.getCandidates()) + "，请明确是哪一个");
            }
          }
          case FALLBACK -> {
            if (missingFields.add("contractId")) {
              questions.add(
                  "未找到名为\""
                      + item.getContractName()
                      + "\"的合同，以下是你名下的合同："
                      + String.join("、", resolution.getCandidates())
                      + "，请选择（或通过下拉框搜索）");
            }
          }
        }

        // JSR-303 声明式校验
        Set<ConstraintViolation<AiOrderItemDraftDTO>> violations = validator.validate(item);

        for (ConstraintViolation<AiOrderItemDraftDTO> violation : violations) {

          String fieldName = violation.getPropertyPath().toString();

          if (missingFields.add(fieldName)) {

            questions.add(
                DraftValidationUtils.resolveAskQuestion(
                    AiOrderItemDraftDTO.class, fieldName, violation.getMessage()));
          }
        }

        // 业务规则：amount 缺省时需 quantity×unitPrice 可推算
        if (item.getAmount() == null
            && !(item.getQuantity() != null && item.getUnitPrice() != null)
            && missingFields.add("amount")) {

          questions.add(
              DraftValidationUtils.resolveAskQuestion(
                  AiOrderItemDraftDTO.class, "amount", "订单金额是多少？"));
        }

        // 业务规则：quantity 与 unitPrice 同时提供时校验 amount 一致性
        if (item.getAmount() != null && item.getQuantity() != null && item.getUnitPrice() != null) {

          BigDecimal expected = item.getQuantity().multiply(item.getUnitPrice());

          if (expected.compareTo(item.getAmount()) != 0 && missingFields.add("amount")) {

            questions.add("金额与数量×单价不一致（" + expected + "），请确认订单金额");
          }
        }
      }

      if (missingFields.isEmpty()) {
        // 名称解析成功时返回修正后的 payload（状态机以此落库）
        if (resolved) {
          try {
            result = AiValidationResult.ok(objectMapper.writeValueAsString(payload));
          } catch (Exception e) {
            log.warn("序列化解析后 payload 失败，回退原 payload", e);
            result = AiValidationResult.ok();
          }
        } else {
          result = AiValidationResult.ok();
        }
      } else {
        result = AiValidationResult.fail(new ArrayList<>(missingFields), questions);
      }
    }
    return result;
  }
}
