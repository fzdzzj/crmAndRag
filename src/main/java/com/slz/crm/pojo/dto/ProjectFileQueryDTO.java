package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目文件查询条件DTO */
@Data
public class ProjectFileQueryDTO {

  /** 文件分类(VISIT_RECORD/MEETING_MINUTES/PROPOSAL/BID_DOCUMENT/PROJECT_CONTRACT) */
  private String category;

  /** 主题(模糊查询) */
  private String theme;

  /** 文件描述(模糊查询) */
  private String description;

  /** 上传人员ID */
  private Long uploaderId;

  /** 业务活动ID */
  private Long activityId;

  /** 销售机会ID */
  private Long opportunityId;

  /** 合同ID */
  private Long contractId;

  /** 订单项ID */
  private Long orderId;

  /** 最小上传时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minUploadTime;

  /** 最大上传时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxUploadTime;
}
