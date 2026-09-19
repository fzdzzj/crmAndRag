package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContractNumVO implements Privacy {
  /** 总签约额度 */
  private BigDecimal totalSignAmount;

  /** 签约合同总量 */
  private Long totalSignContractNum;

  /** 新增合同数量 */
  private Long totalNewContractNum;

  /** 报表起始时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime reportStartTime;

  /** 报表结束时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime reportEndTime;
}
