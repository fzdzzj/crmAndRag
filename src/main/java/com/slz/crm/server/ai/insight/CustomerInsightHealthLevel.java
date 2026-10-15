package com.slz.crm.server.ai.insight;

/**
 * 客户健康等级（add-ai-customer-360-insight 任务 1.2）。
 *
 * <p>确定性映射：EXCELLENT（85-100）/ HEALTHY（70-84）/ WARNING（50-69）/ CRITICAL（&lt;50）， 与提案验收口径逐分对齐。
 */
public enum CustomerInsightHealthLevel {
  EXCELLENT,
  HEALTHY,
  WARNING,
  CRITICAL;

  /**
   * 健康分 → 健康等级。
   *
   * @param score 0~100 的确定性健康分
   * @return 对应等级（越界分按最近边界归类）
   */
  public static CustomerInsightHealthLevel of(int score) {
    CustomerInsightHealthLevel result;
    if (score >= 85) {
      result = EXCELLENT;
    } else if (score >= 70) {
      result = HEALTHY;
    } else if (score >= 50) {
      result = WARNING;
    } else {
      result = CRITICAL;
    }
    return result;
  }
}
