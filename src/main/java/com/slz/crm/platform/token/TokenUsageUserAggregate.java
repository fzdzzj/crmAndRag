package com.slz.crm.platform.token;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 按用户聚合的计量投影（Mapper 直出，未做用户维度还原）。
 *
 * <p>token-usage-admin-view 任务 2.1：只承载 SQL 聚合结果，展示口径（用户名/占比）由 {@link TokenUsageByUserVO}
 * 承担，避免把「库内引用」与「展示名」混在一个类型里。
 */
@Data
public class TokenUsageUserAggregate {

  /** 计量表内的用户引用：{@code user:<id>} / {@code user:system} / {@code user:unknown}。 */
  private String userRef;

  /** 输入 token 合计。 */
  private Long promptTokens;

  /** 输出 token 合计。 */
  private Long completionTokens;

  /** 总 token 合计。 */
  private Long totalTokens;

  /** 调用次数（含失败；失败也计量）。 */
  private Long calls;

  /** 失败调用次数（success = 0）。 */
  private Long failedCalls;

  /** 区间内最近一次调用时间。 */
  private LocalDateTime lastCallTime;
}
