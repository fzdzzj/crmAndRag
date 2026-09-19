package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 商业活动查询条件数据传输对象 */
@Data
public class BusinessActivityQueryDTO {
  /** 活动标题 */
  private String activityTitle;

  /** 活动内容 */
  private String activityContent;

  /** 公司名称 */
  private String companyName;

  /** 活动类型 */
  private String activityType;

  /** 活动持续时间 */
  private Integer activityDuration;

  /** 销售机会名称 */
  private String opportunityName;

  /** 备注 */
  private String remark;

  /** 创建人姓名 */
  private String creatorName;

  /** 最小创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minCreateTime;

  /** 最大创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxCreateTime;

  /** 最小活动时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minActivityTime;

  /** 最大活动时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxActivityTime;
}
