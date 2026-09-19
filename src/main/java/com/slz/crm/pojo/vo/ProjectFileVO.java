package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目文件视图对象 */
@Data
public class ProjectFileVO {

  /** 主键ID */
  private Long id;

  /** 文件名 */
  private String fileName;

  /** 文件类型 */
  private String fileType;

  /** 文件大小(字节) */
  private Long fileSize;

  /** 分类 */
  private String category;

  /** 上传时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime uploadTime;

  /** 上传人员ID */
  private Long uploaderId;

  /** 上传人员姓名 */
  private String uploaderName;

  /** 主题 */
  private String theme;

  /** 说明 */
  private String description;

  /** 业务活动ID */
  private Long activityId;

  /** 销售机会ID */
  private Long opportunityId;

  /** 合同ID */
  private Long contractId;

  /** 订单项ID */
  private Long orderId;

  /** 下载URL(带令牌) */
  private String downloadUrl;
}
