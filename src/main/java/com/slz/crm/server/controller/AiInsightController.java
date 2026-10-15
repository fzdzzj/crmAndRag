package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.server.ai.insight.CustomerInsightResult;
import com.slz.crm.server.ai.insight.CustomerInsightService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
