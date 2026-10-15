package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 客户公海流转日志 VO（add-customer-public-sea-pool 任务 2.5，返回客户全生命周期流转时间线）。 */
@Data
public class CustomerPoolLogVO {

  private Long id;

  /** 关联客户公司 ID */
  private Long companyId;

  /** 操作人 ID（系统自动回收记录为 0） */
  private Long operatorId;

  /** 操作人姓名（系统自动回收显示"系统"） */
  private String operatorName;

  /** 流转动作：CLAIM-认领 / RELEASE-主动退回 / ASSIGN-主管分派 / AUTO_RECYCLE-系统超时自动回收 */
  private String actionType;

  /** 原负责人 ID */
  private Long previousOwnerId;

  /** 新负责人 ID */
  private Long newOwnerId;

  /** 流转原因或备注说明 */
  private String reason;

  /** 记录时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;
}
