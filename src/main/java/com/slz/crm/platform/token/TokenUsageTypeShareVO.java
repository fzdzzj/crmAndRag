package com.slz.crm.platform.token;

import lombok.Data;

/** 单个 usage_type 的用量与占比（摘要页头部卡片）。 */
@Data
public class TokenUsageTypeShareVO {

  /** 计量类型（小写 wire 名：chat/embedding/ocr/vision/summary/intent）。 */
  private String usageType;

  /** 该类型总 token。 */
  private Long totalTokens;

  /** 该类型调用次数。 */
  private Long calls;

  /** 该类型失败调用次数。 */
  private Long failedCalls;

  /** 该类型总 token 占区间总量的百分比（两位小数）。 */
  private Double sharePct;
}
