package com.slz.crm.platform.token;

import java.util.List;
import lombok.Data;

/**
 * 区间总览（超管「AI 用量」页头部卡片）。
 *
 * <p>口径提醒：embedding / 视觉联合 embedding 端点不回流 token（{@code docs/token-metering.md} §1、§6）， 故 {@code
 * totalTokens} 偏低不代表调用少——{@code calls} 才是完整调用次数。
 */
@Data
public class TokenUsageSummaryVO {

  /** 实际生效的区间起点（含），ISO-8601。 */
  private String startTime;

  /** 实际生效的区间终点（不含），ISO-8601。 */
  private String endTime;

  /** 输入 token 合计。 */
  private Long promptTokens;

  /** 输出 token 合计。 */
  private Long completionTokens;

  /** 总 token 合计。 */
  private Long totalTokens;

  /** 调用次数（含失败）。 */
  private Long calls;

  /** 失败调用次数。 */
  private Long failedCalls;

  /** 去重用户数。 */
  private Long userCount;

  /** 按 usage_type 分组占比（总 token 降序）。 */
  private List<TokenUsageTypeShareVO> usageTypes;
}
