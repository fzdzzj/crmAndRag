package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;

/** 图表数据视图对象 用于返回图表数据给前端，由前端进行渲染 */
@Data
public class ChartDataVO implements Serializable {
  /** 图表标题（中文） */
  private String title;

  /** 图表标题（英文） */
  private String titleEn;

  /** 图表类型（line=折线图, pie=饼图） */
  private String chartType;

  /** 图表数据（key=标签名，value=数值） */
  private Map<String, Number> data;

  /** X轴标签名称（折线图使用） */
  @JsonProperty("xAxisLabel")
  private String xAxisLabel;

  /** Y轴标签名称（折线图使用） */
  @JsonProperty("yAxisLabel")
  private String yAxisLabel;

  public ChartDataVO() {}

  public ChartDataVO(String title, String titleEn, String chartType, Map<String, Number> data) {
    this.title = title;
    this.titleEn = titleEn;
    this.chartType = chartType;
    this.data = data;
  }

  public ChartDataVO(
      String title,
      String titleEn,
      String chartType,
      Map<String, Number> data,
      String xAxisLabel,
      String yAxisLabel) {
    this.title = title;
    this.titleEn = titleEn;
    this.chartType = chartType;
    this.data = data;
    this.xAxisLabel = xAxisLabel;
    this.yAxisLabel = yAxisLabel;
  }
}
