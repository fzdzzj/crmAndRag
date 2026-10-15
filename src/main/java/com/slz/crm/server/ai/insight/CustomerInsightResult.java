package com.slz.crm.server.ai.insight;

import java.util.List;

/**
 * 客户 360° AI 智能商业诊断结果（add-ai-customer-360-insight 任务 2.1/2.2，端点响应体）。
 *
 * @param companyId 客户公司 ID
 * @param companyName 客户公司名称
 * @param healthScore 确定性健康分（0~100）
 * @param healthLevel 健康等级
 * @param summary 一句话客户全景商业画像摘要（LLM 或规则回退）
 * @param riskAlerts 关键流失与成单阻碍风险点
 * @param actionSuggestions 下一步销售拜访跟进建议
 * @param dimensionScores 四维度评分明细
 * @param ruleWarnings 底层规则风险预警清单
 * @param llmEnhanced 是否由 LLM 增强（false = 规则安全降级输出）
 */
public record CustomerInsightResult(
    Long companyId,
    String companyName,
    int healthScore,
    CustomerInsightHealthLevel healthLevel,
    String summary,
    List<String> riskAlerts,
    List<String> actionSuggestions,
    List<CustomerInsightDimensionScore> dimensionScores,
    List<String> ruleWarnings,
    boolean llmEnhanced) {}
