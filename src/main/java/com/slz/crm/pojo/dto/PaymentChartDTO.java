package com.slz.crm.pojo.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 回款图表数据传输对象 */
@Data
public class PaymentChartDTO {
  /** 图表标题（日期或客户名称等） */
  private String chartTitle;

  /** 回款金额 */
  private BigDecimal paymentAmount;

  /** 回款笔数 */
  private Long paymentCount;

  /** 待确认金额 */
  private BigDecimal pendingAmount;

  /** 已确认金额 */
  private BigDecimal confirmedAmount;

  /** 已作废金额 */
  private BigDecimal voidedAmount;
}
