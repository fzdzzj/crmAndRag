package com.slz.crm.common.enumeration;

public class ChartOperates {
  /** 数据统计表格模块时间范围枚举 */
  public enum TimeRangeOperate {
    // 时间范围管理
    THIS_WEEK("本周内"),
    THIS_MONTH("一个月内"),
    THIS_YEAR("一年内"),
    CUSTOM_RANGE("自定义时间范围");

    private final String description;

    TimeRangeOperate(String description) {
      this.description = description;
    }

    public String getDescription() {
      return description;
    }
  }

  /** 数据统计表格模块表格枚举 */
  public enum ChartOperate {
    LINE_CHART("折线图"),
    PIE_CHART("饼图");

    private final String description;

    ChartOperate(String description) {
      this.description = description;
    }

    public String getDescription() {
      return description;
    }
  }
}
