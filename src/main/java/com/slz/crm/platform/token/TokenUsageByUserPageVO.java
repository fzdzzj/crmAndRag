package com.slz.crm.platform.token;

import java.util.List;
import lombok.Data;

/** 按用户用量分页结果（total 为去重用户数，records 按总 token 降序）。 */
@Data
public class TokenUsageByUserPageVO {

  /** 区间内参与聚合的去重用户数。 */
  private long total;

  /** 当前页用户行。 */
  private List<TokenUsageByUserVO> records;
}
