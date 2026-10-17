package com.slz.crm.platform.token;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token 预算默认阈值。
 *
 * <p>这里提供安全兜底；后续 Lane E 动态配置或 DB 预算表可覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.token-budget")
public class TokenBudgetProperties {

  /**
   * 是否启用预算拦截；测试可关闭，生产建议开启。
   *
   * <p>默认 false（wire-built-unfinished-infra 接线灰度口径，v2 池 C-7 批次决定）：闸已接入聊天流入口但默认旁路， 生产启用置 {@code
   * platform.token-budget.enabled=true}；开启后每次流式对话按 USER 维度先查后扣。
   */
  private boolean enabled = false;

  /** 每个预算维度单日总 token 上限。 */
  private long dailyLimit = 100000;

  /** 每个预算维度单月总 token 上限。 */
  private long monthlyLimit = 1000000;
}
