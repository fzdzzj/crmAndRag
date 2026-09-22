package com.slz.crm.server.ai.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiInvoiceDraftPayloadDTO;
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
 * 新增发票草稿校验器（CREATE_INVOICE） 合同双通道（contractId 或 contractName）由 AiEntityResolver 确定性解析，其余字段 JSR-303
 * 校验
 */
@Slf4j
@Component
public class InvoiceActionValidator implements AiActionValidator {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_INVOICE.getValue();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private Validator validator;

  @Autowired private AiEntityResolver entityResolver;

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiValidationResult validate(String payloadJson) {
    AiInvoiceDraftPayloadDTO payload = null;
    AiValidationResult result = null;
    try {
      payload = objectMapper.readValue(payloadJson, AiInvoiceDraftPayloadDTO.class);
    } catch (Exception e) {
      log.warn("发票草稿 payload 解析失败", e);
      result = AiValidationResult.fail(List.of("contractId"), List.of("请提供发票信息（合同、发票号码、金额）"));
    }

    if (result == null) {
      result = validatePayload(payload);
    }
    return result;
  }

  /** payload 解析成功后执行实体解析与声明式校验；只在完全通过且发生字段重写时返回 ok。 */
  private AiValidationResult validatePayload(AiInvoiceDraftPayloadDTO payload) {
    AiValidationResult result;
    Set<String> missingFields = new LinkedHashSet<>();
    List<String> questions = new ArrayList<>();
    boolean resolved = false;

    // 实体解析：合同双通道
    AiEntityResolver.Resolution resolution =
        entityResolver.resolveContract(payload.getContractId(), payload.getContractName());
    switch (resolution.getStatus()) {
      case RESOLVED -> {
        if (!resolution.getResolvedId().equals(payload.getContractId())) {
          payload.setContractId(resolution.getResolvedId());
          resolved = true;
        }
      }
      case MISSING -> {
        if (missingFields.add("contractId")) {
          questions.add(
              DraftValidationUtils.resolveAskQuestion(
                  AiInvoiceDraftPayloadDTO.class, "contractId", "这张发票挂在哪个合同下？"));
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
                  + payload.getContractName()
                  + "\"的合同，以下是你名下的合同："
                  + String.join("、", resolution.getCandidates())
                  + "，请选择（或通过下拉框搜索）");
        }
      }
    }

    // JSR-303 声明式校验
    Set<ConstraintViolation<AiInvoiceDraftPayloadDTO>> violations = validator.validate(payload);
    for (ConstraintViolation<AiInvoiceDraftPayloadDTO> violation : violations) {
      String fieldName = violation.getPropertyPath().toString();
      if (missingFields.add(fieldName)) {
        questions.add(
            DraftValidationUtils.resolveAskQuestion(
                AiInvoiceDraftPayloadDTO.class, fieldName, violation.getMessage()));
      }
    }

    if (missingFields.isEmpty()) {
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
    return result;
  }
}
