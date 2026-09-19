package com.slz.crm.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.PendingActionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiDraftToolExecutors {

  private final PendingActionService pendingActionService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public Object executeCreateCustomerDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_CUSTOMER);
  }

  public Object executeCreateContactDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_CONTACT);
  }

  public Object executeCreateOpportunityDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_OPPORTUNITY);
  }

  public Object executeCreatePaymentDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_PAYMENT);
  }

  public Object executeCreateInvoiceDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_INVOICE);
  }

  public Object executeCreateOrderDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_ORDER);
  }

  public Object executeCreateContractDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_CONTRACT);
  }

  private Object submitDraft(
      Map<String, Object> args, ToolContext toolContext, ActionTypeEnum actionType)
      throws Exception {
    Long userId = BaseUnit.getCurrentId();
    Long sessionId = extractSessionId(toolContext);
    String pendingId = args.get("pendingId") != null ? String.valueOf(args.get("pendingId")) : null;
    String payloadJson = objectMapper.writeValueAsString(args);
    if (pendingId != null && !pendingId.isBlank()) {
      return pendingActionService.mergeDraft(pendingId, userId, payloadJson);
    }
    return pendingActionService.submitDraft(sessionId, userId, actionType.getValue(), payloadJson);
  }

  private Long extractSessionId(ToolContext toolContext) {
    if (toolContext == null || toolContext.getContext() == null) {
      return null;
    }
    Object sessionId = toolContext.getContext().get("sessionId");
    if (sessionId instanceof Number number) {
      return number.longValue();
    }
    return sessionId != null ? Long.valueOf(String.valueOf(sessionId)) : null;
  }
}
