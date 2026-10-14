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

  /** extend-ai-draft-and-eval 任务 1.3：跟进任务草稿（挂 null 注册，起草自由，写门在确认链）。 */
  public Object executeCreateContactTaskDraft(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_CONTACT_TASK);
  }

  /** extend-ai-draft-and-eval 任务 2.3：业务活动草稿（挂 null 注册，起草自由，写门在确认链）。 */
  public Object executeCreateBusinessActivityDraft(
      Map<String, Object> args, ToolContext toolContext) throws Exception {
    return submitDraft(args, toolContext, ActionTypeEnum.CREATE_BUSINESS_ACTIVITY);
  }

  private Object submitDraft(
      Map<String, Object> args, ToolContext toolContext, ActionTypeEnum actionType)
      throws Exception {
    Long userId = BaseUnit.getCurrentId();
    Long sessionId = extractSessionId(toolContext);
    String pendingId = args.get("pendingId") != null ? String.valueOf(args.get("pendingId")) : null;
    String payloadJson = objectMapper.writeValueAsString(args);
    Object result;
    if (pendingId != null && !pendingId.isBlank()) {
      result = pendingActionService.mergeDraft(pendingId, userId, payloadJson);
    } else {
      result =
          pendingActionService.submitDraft(sessionId, userId, actionType.getValue(), payloadJson);
    }
    return result;
  }

  private Long extractSessionId(ToolContext toolContext) {
    Long result;
    if (toolContext == null || toolContext.getContext() == null) {
      result = null;
    } else {
      Object sessionId = toolContext.getContext().get("sessionId");
      if (sessionId instanceof Number number) {
        result = number.longValue();
      } else {
        result = sessionId != null ? Long.valueOf(String.valueOf(sessionId)) : null;
      }
    }
    return result;
  }
}
