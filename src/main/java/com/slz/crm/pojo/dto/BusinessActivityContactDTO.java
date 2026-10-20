package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/** 商业活动联系人关联数据传输对象 */
@Data
public class BusinessActivityContactDTO {
  /** 关联ID */
  private Long id;

  /** 商业活动ID */
  private Long activityId;

  /** 联系人ID（关联维护的身份字段：为空时旧实现会一路下探到 list-vs-set 比对或库约束才报错） */
  @NotNull(message = "联系人ID不能为空")
  private Long contactId;

  /** 联系人角色 */
  private String contactRole;

  /** 创建人ID */
  private Long creatorId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;
}
