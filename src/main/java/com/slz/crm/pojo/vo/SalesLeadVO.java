package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售线索视图对象（add-lead-management-and-conversion-funnel 任务 2.1）。 */
@Data
public class SalesLeadVO {
  /** 线索ID */
  private Long id;

  /** 联系人姓名 */
  private String name;

  /** 公司名称 */
  private String companyName;

  /** 电话 */
  private String phone;

  /** 邮箱 */
  private String email;

  /** 线索来源 */
  private String source;

  /** 线索状态（0待跟进/1跟进中/2已转化/3已关闭无效） */
  private Integer status;

  /** 负责人ID */
  private Long ownerId;

  /** 创建人ID */
  private Long creatorId;

  /** 转化后客户公司ID */
  private Long convertedCompanyId;

  /** 转化后联系人ID */
  private Long convertedContactId;

  /** 转化后商机ID */
  private Long convertedOpportunityId;

  /** 转化时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime convertedTime;

  /** 备注 */
  private String remark;

  /** 负责人姓名（查询时反查填充） */
  private String ownerName;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 更新时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime updateTime;
}
