package com.slz.crm.common.enumeration;

/** 图表数据类型枚举 用于指定前端需要查询哪种统计数据 */
public enum ChartDataType {
  /** 签约合同数 */
  CONTRACT_NUM("签约合同数"),

  /** 业绩 */
  PERFORMANCE("业绩"),

  /** 客户来源分布 */
  CUSTOMER_SOURCE("客户分布"),

  /** 回款金额 */
  PAYMENT_AMOUNT("回款金额"),

  /** 回款笔数 */
  PAYMENT_COUNT("回款笔数"),

  /** 回款状态分布 */
  PAYMENT_STATUS("回款状态分布");

  private final String description;

  ChartDataType(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }
}
