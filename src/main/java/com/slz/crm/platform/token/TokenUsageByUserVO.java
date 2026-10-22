package com.slz.crm.platform.token;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户维度 token 用量行（超管「AI 用量」页表格）。
 *
 * <p>{@code userRef} 保留库内原值便于对账；{@code userId}/{@code userName} 为还原后的展示维度： {@code user:system} →
 * 「系统」、{@code user:unknown} → 「未知来源」、查不到的 id → 「已删除用户(#id)」。
 */
@Data
public class TokenUsageByUserVO {

  /** 库内用户引用原值（user:<id> / user:system / user:unknown）。 */
  private String userRef;

  /** 解析出的用户 ID；系统/未知/无法解析时为 null。 */
  private Long userId;

  /** 展示名：真实姓名 / 系统 / 未知来源 / 已删除用户(#id)。 */
  private String userName;

  /** 输入 token。 */
  private Long promptTokens;

  /** 输出 token。 */
  private Long completionTokens;

  /** 总 token。 */
  private Long totalTokens;

  /** 调用次数（含失败，失败也计量）。 */
  private Long calls;

  /** 失败调用次数。 */
  private Long failedCalls;

  /** 区间内最近一次调用时间。 */
  private LocalDateTime lastCallTime;

  /** 总 token 占本区间总量的百分比（两位小数）。 */
  private Double sharePct;
}
