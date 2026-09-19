package com.slz.crm.platform.security;

/**
 * 内容安全检查结果。
 *
 * @param riskLevel 风险等级
 * @param action 建议处置
 * @param matchedRule 命中规则
 * @param reason 命中原因
 */
public record ContentSecurityResult(
    ContentRiskLevel riskLevel, ContentSecurityAction action, String matchedRule, String reason) {

  /**
   * @return true 表示必须停止进入系统提示、记忆或模型调用
   */
  public boolean isBlocked() {
    return action == ContentSecurityAction.BLOCK;
  }
}
