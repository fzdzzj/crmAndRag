package com.slz.crm.platform.token;

import lombok.Data;

/** 按 usage_type 分组的计量投影（Mapper 直出，usageType 为库内原始值）。 */
@Data
public class TokenUsageTypeAggregate {

  /** 库内 usage_type 原始值（历史口径可能是枚举名或小写 wire 名）。 */
  private String usageType;

  /** 该类型总 token 合计。 */
  private Long totalTokens;

  /** 该类型调用次数。 */
  private Long calls;

  /** 该类型失败调用次数。 */
  private Long failedCalls;
}
