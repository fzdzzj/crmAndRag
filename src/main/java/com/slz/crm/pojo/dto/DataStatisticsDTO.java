package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.common.enumeration.ChartDataType;
import com.slz.crm.common.enumeration.ChartOperates.ChartOperate;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/** 数据统计数据传输对象 */
@Data
public class DataStatisticsDTO implements Serializable {
  /** 数据类型（必填） */
  private ChartDataType dataType;

  /** 时间范围操作 */
  private TimeRangeOperate timeRange;

  /** 图表类型操作 */
  private ChartOperate chartType;

  /** 开始时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startTime;

  /** 结束时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endTime;
}
