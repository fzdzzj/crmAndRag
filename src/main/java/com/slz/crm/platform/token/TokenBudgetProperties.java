package com.slz.crm.platform.token;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token 预算默认阈值。
 *
 * <p>这里提供安全兜底；后续 Lane E 动态配置或 DB 预算表可覆盖。
 *
 * <p>并发安全口径（fix-minor-debt-family 任务 6.1）：本 bean 是单例，字段被全部请求线程共读，而配置绑定/动态刷新会写同一批字段。 三个字段一律声明 {@code
 * volatile}，保证写入对所有读线程立即可见，且 {@code long} 的读写在 32 位平台也不撕裂（JLS 17.7）。 读侧 {@code
 * TokenBudgetService#check} 只做单次字段读取、不做「读-改-写」，故无需锁或原子类型。
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
  private volatile boolean enabled = false;

  /** 每个预算维度单日总 token 上限。 */
  private volatile long dailyLimit = 100000;

  /** 每个预算维度单月总 token 上限。 */
  private volatile long monthlyLimit = 1000000;
}
