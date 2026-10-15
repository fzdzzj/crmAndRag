package com.slz.crm.server.ai.insight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 诊断载荷构建协作类（add-ai-customer-360-insight 任务 2.1）。
 *
 * <p>显式构造 JSON 安全载荷（全原始类型/字符串/列表，规避 LocalDateTime 等 JDK 时间类型的序列化依赖）， 同时控制 {@link
 * CustomerInsightService} 的类 NCSS 在 PMD 规约内。无状态、纯函数。
 */
final class CustomerInsightLlmPayloadSupport {

  private CustomerInsightLlmPayloadSupport() {}

  /** 把快照与规则结果组装为模型入参载荷（键值均可被 Jackson 直接序列化）。 */
  static Map<String, Object> build(
      CustomerInsightSnapshot snapshot, CustomerInsightRuleEngine.RuleResult rule) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("companyId", snapshot.companyId());
    payload.put("companyName", snapshot.companyName());
    payload.put("industry", snapshot.industry());
    payload.put("status", snapshot.status());
    payload.put("healthScore", rule.healthScore());
    payload.put("healthLevel", rule.healthLevel().name());
    payload.put("activityCount", snapshot.activity().count());
    payload.put("activityCountLast30Days", snapshot.activity().countLast30Days());
    payload.put("daysSinceLastActivity", snapshot.activity().daysSinceLastActivity());
    payload.put("lastActivityTitle", snapshot.activity().lastActivityTitle());
    payload.put("opportunityCount", snapshot.opportunity().count());
    payload.put("opportunityTotalAmount", snapshot.opportunity().totalAmount());
    payload.put("maxStageStagnationDays", snapshot.opportunity().maxStageStagnationDays());
    payload.put("approvalRejectedCount", snapshot.opportunity().approvalRejectedCount());
    payload.put("contractCount", snapshot.contractPayment().contractCount());
    payload.put("contractTotalAmount", snapshot.contractPayment().contractTotalAmount());
    payload.put("paidAmount", snapshot.contractPayment().paidAmount());
    payload.put("pendingPaymentAmount", snapshot.contractPayment().pendingPaymentAmount());
    payload.put("overdueUnpaidAmount", snapshot.contractPayment().overdueUnpaidAmount());
    payload.put("unpaidContractCount", snapshot.contractPayment().unpaidContractCount());
    payload.put("highPriorityOpenTaskCount", snapshot.taskContact().highPriorityOpenTaskCount());
    payload.put(
        "overdueHighPriorityTaskCount", snapshot.taskContact().overdueHighPriorityTaskCount());
    payload.put("contactCount", snapshot.taskContact().contactCount());
    payload.put("keyContactCount", snapshot.taskContact().keyContactCount());
    payload.put("ruleWarnings", rule.ruleWarnings());
    return payload;
  }
}
