package com.slz.crm.platform.token;

/**
 * Token 预算检查结果。
 *
 * @param allowed 是否允许调用
 * @param limit 当前周期上限
 * @param used 当前周期已用量
 * @param requested 本次预估用量
 * @param remaining 剩余可用额度
 * @param retryAfterSeconds 被拒绝后建议等待秒数
 * @param reason 机器/人可读原因
 */
public record TokenBudgetDecision(
    boolean allowed,
    long limit,
    long used,
    long requested,
    long remaining,
    long retryAfterSeconds,
    String reason) {

  /**
   * 构造允许结果。
   *
   * @param limit 周期上限
   * @param used 已用量
   * @param requested 预估用量
   * @param remaining 剩余额度
   * @return 允许决策
   */
  public static TokenBudgetDecision allow(long limit, long used, long requested, long remaining) {
    return new TokenBudgetDecision(true, limit, used, requested, remaining, 0, "OK");
  }
}
