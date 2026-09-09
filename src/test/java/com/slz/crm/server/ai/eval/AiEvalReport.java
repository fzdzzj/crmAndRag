package com.slz.crm.server.ai.eval;

import java.util.List;

/**
 * 评测汇总报告。
 * 工具准确率按全部用例计算；参数准确率只统计工具选择命中的用例，避免工具错选重复扣分。
 */
public record AiEvalReport(
        int totalCases,
        int toolHits,
        int paramHits,
        double toolSelectionAccuracy,
        double parameterAccuracy,
        List<AiEvalCaseResult> results
) {
}
