package com.slz.crm.server.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ai.AiBusinessActivityDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiContactTaskDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.ai.insight.CustomerInsightResult;
import com.slz.crm.server.ai.insight.CustomerInsightService;
import com.slz.crm.server.service.PendingActionService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 诊断建议一键转 Action（add-ai-insight-to-action-conversion 任务 1.1/1.2）。
 *
 * <p>读取 {@link CustomerInsightService} 诊断结果的 {@code actionSuggestions[index]}，组装对齐 {@link
 * AiDraftToolExecutors} 既有草稿消费格式的 payload（直接复用 {@code AiContactTaskDraftPayloadDTO} / {@code
 * AiBusinessActivityDraftPayloadDTO}），调 {@link PendingActionService#submitDraft} 落库返回 pendingId
 * 与草稿摘要。
 *
 * <p>红线：仅接受 {@code CREATE_CONTACT_TASK}（默认）/ {@code CREATE_BUSINESS_ACTIVITY}；空建议、index
 * 越界、actionType 非法均抛业务异常（错误码 400 语义）明确拒绝，不静默；不发起任何新的 LLM 外呼。
 */
@Slf4j
@Component
public class InsightToActionService {

  /** 默认目标：转跟进待办。 */
  private static final String DEFAULT_ACTION_TYPE = ActionTypeEnum.CREATE_CONTACT_TASK.getValue();

  /** 仅支持的两种 ActionType（禁新建枚举，红线）。 */
  private static final Set<String> SUPPORTED_ACTION_TYPES =
      Set.of(
          ActionTypeEnum.CREATE_CONTACT_TASK.getValue(),
          ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue());

  private final CustomerInsightService customerInsightService;

  private final PendingActionService pendingActionService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public InsightToActionService(
      CustomerInsightService customerInsightService, PendingActionService pendingActionService) {
    this.customerInsightService = customerInsightService;
    this.pendingActionService = pendingActionService;
  }

  /**
   * 将诊断报告第 {@code suggestionIndex} 条行动建议转为目标 ActionType 的 PendingAction 草稿。
   *
   * @param companyId 客户公司 ID（诊断锚）
   * @param request 建议序号 + 目标 ActionType（缺省 CREATE_CONTACT_TASK）
   * @return pendingId / 草稿状态 / actionType / 建议文本
   */
  public InsightToActionResult toAction(Long companyId, InsightToActionRequest request) {
    String actionType = resolveActionType(request);
    Integer index = resolveIndex(request);

    CustomerInsightResult insight = customerInsightService.diagnose(companyId);
    String suggestion = resolveSuggestion(insight, index);

    String payloadJson = buildPayload(actionType, companyId, suggestion);
    Long userId = BaseUnit.getCurrentId();
    AiDraftResult draft = pendingActionService.submitDraft(null, userId, actionType, payloadJson);

    return new InsightToActionResult(
        draft.getPendingId(), draft.getStatus(), actionType, suggestion);
  }

  /** 解析并校验目标 ActionType；缺省转跟进待办，非法值明确 400 拒绝。 */
  private String resolveActionType(InsightToActionRequest request) {
    String actionType =
        request.actionType() == null || request.actionType().isBlank()
            ? DEFAULT_ACTION_TYPE
            : request.actionType().toUpperCase(Locale.ROOT).trim();
    if (!SUPPORTED_ACTION_TYPES.contains(actionType)) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR,
          "不支持的 actionType: " + actionType + "（仅支持 " + SUPPORTED_ACTION_TYPES + "）");
    }
    return actionType;
  }

  /** 解析并校验建议序号；缺失或负数按必填缺失 400 拒绝。 */
  private Integer resolveIndex(InsightToActionRequest request) {
    if (request.suggestionIndex() == null || request.suggestionIndex() < 0) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "suggestionIndex 缺失或非法");
    }
    return request.suggestionIndex();
  }

  /** 取第 {@code index} 条建议；空建议 / 越界均明确 400，不静默。 */
  private String resolveSuggestion(CustomerInsightResult insight, int index) {
    List<String> suggestions = insight == null ? List.of() : insight.actionSuggestions();
    if (suggestions.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_OUT_OF_RANGE, "当前诊断无可用建议，无法转动作");
    }
    if (index >= suggestions.size()) {
      throw new BaseException(
          ErrorCode.PARAM_OUT_OF_RANGE,
          "suggestionIndex 越界（共 " + suggestions.size() + " 条建议，请求 " + index + "）");
    }
    String suggestion = suggestions.get(index);
    if (suggestion == null || suggestion.isBlank()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "该条建议为空，无法转动作");
    }
    return suggestion;
  }

  /** 组装对齐执行器消费格式的 payload：直接复用 AiDraft*DraftPayloadDTO 序列化，锁死字段形状。 */
  private String buildPayload(String actionType, Long companyId, String suggestion) {
    final String payloadJson;
    try {
      if (ActionTypeEnum.CREATE_CONTACT_TASK.getValue().equals(actionType)) {
        AiContactTaskDraftPayloadDTO payload = new AiContactTaskDraftPayloadDTO();
        payload.setCompanyId(companyId);
        payload.setTaskTitle(suggestion);
        payloadJson = objectMapper.writeValueAsString(payload);
      } else {
        AiBusinessActivityDraftPayloadDTO payload = new AiBusinessActivityDraftPayloadDTO();
        payload.setActivityTitle(suggestion);
        payloadJson = objectMapper.writeValueAsString(payload);
      }
    } catch (JsonProcessingException e) {
      throw new BaseException(ErrorCode.SYSTEM_ERROR, "建议转草稿 payload 序列化失败");
    }
    return payloadJson;
  }

  /** 请求体：建议序号 + 目标 ActionType（缺省 CREATE_CONTACT_TASK）。 */
  public record InsightToActionRequest(Integer suggestionIndex, String actionType) {}

  /** 响应体：pendingId / 草稿状态 / actionType / 建议文本。 */
  public record InsightToActionResult(
      String pendingId, String status, String actionType, String suggestion) {}
}
