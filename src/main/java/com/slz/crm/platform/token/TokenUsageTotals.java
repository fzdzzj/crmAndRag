package com.slz.crm.platform.token;

import lombok.Data;

/** 区间总量投影（Mapper 直出）：token 三口径 + 调用/失败次数 + 去重用户数。 */
@Data
public class TokenUsageTotals {

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
}
