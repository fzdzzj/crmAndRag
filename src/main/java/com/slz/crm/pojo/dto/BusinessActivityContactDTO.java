package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 商业活动联系人关联数据传输对象 */
@Data
public class BusinessActivityContactDTO {
  /** 关联ID */
  private Long id;

  /** 商业活动ID */
  private Long activityId;

  /** 联系人ID */
  private Long contactId;

  /** 联系人角色 */
  private String contactRole;

  /** 创建人ID */
  private Long creatorId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;
}
