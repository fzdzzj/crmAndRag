package com.slz.crm.server.ai.insight;

/**
 * 健康分维度明细（add-ai-customer-360-insight 任务 1.2）。
 *
 * @param name 维度名称（跟进活跃度/商机健康度/合同回款履约/任务与联系人）
 * @param score 该维度 0~100 分
 * @param weightPercent 该维度在总分中的权重（百分比）
 */
public record CustomerInsightDimensionScore(String name, int score, int weightPercent) {}
