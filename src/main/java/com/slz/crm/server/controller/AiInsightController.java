package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.server.ai.InsightToActionService;
import com.slz.crm.server.ai.insight.CustomerInsightResult;
import com.slz.crm.server.ai.insight.CustomerInsightService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 智能商业诊断端点（add-ai-customer-360-insight 任务 2.2）。
 *
 * <p>{@code GET /ai/insight/customer/{companyId}}：挂方法级
 * {@code @RequirePermission(CUSTOMER_VIEW_COMPANY)} （复用 117，零新增权限码），服务层经 {@code
 * BusinessRecordAccessService} DataScope 守卫拦截跨部门无权访问（12002）。
 */
@RestController
@RequestMapping("/ai/insight")
@Validated
@Slf4j
public class AiInsightController {

  @Autowired private CustomerInsightService customerInsightService;

  @Autowired private InsightToActionService insightToActionService;

  /**
   * 客户 360° AI 智能商业诊断。
   *
   * @param companyId 客户公司 ID
   * @return 健康分、健康等级、摘要、风险点、行动建议与维度评分
   */
  @GetMapping("/customer/{companyId}")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  public Result<CustomerInsightResult> getCustomerInsight(@PathVariable Long companyId) {
    return Result.success(customerInsightService.diagnose(companyId));
  }

  /**
   * 将诊断报告第 {@code suggestionIndex} 条行动建议一键转为 PendingAction 草稿（add-ai-insight-to-action-conversion
   * 任务 2.1）。目标 ActionType 缺省 {@code CREATE_CONTACT_TASK}（转跟进待办）；越界/空建议/非法 actionType 返回 400
   * 业务错误不静默，写权限闸由确认流既有 ActionType 映射把关（与诊断同锚：可见即可转）。
   *
   * @param companyId 客户公司 ID
   * @param request 建议序号 + 目标 ActionType（缺省 CREATE_CONTACT_TASK）
   * @return pendingId、草稿状态、actionType 与建议文本
   */
  @PostMapping("/customer/{companyId}/to-action")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  public Result<InsightToActionService.InsightToActionResult> convertSuggestionToAction(
      @PathVariable Long companyId,
      @Valid @RequestBody InsightToActionService.InsightToActionRequest request) {
    return Result.success(insightToActionService.toAction(companyId, request));
  }
}
