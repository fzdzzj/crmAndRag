package com.slz.crm.pojo.dto;

import lombok.Data;

/** 销售线索数据传输对象（创立/录入与跟进修改；add-lead-management-and-conversion-funnel 任务 2.1）。 */
@Data
public class SalesLeadDTO {
  /** 线索ID（更新时携带） */
  private Long id;

  /** 联系人姓名 */
  private String name;

  /** 公司名称 */
  private String companyName;

  /** 电话 */
  private String phone;

  /** 邮箱 */
  private String email;

  /** 线索来源（展会/官网留资/电话开拓/转介绍等） */
  private String source;

  /** 线索状态（0待跟进/1跟进中/2已转化/3已关闭无效） */
  private Integer status;

  /** 负责人ID */
  private Long ownerId;

  /** 备注 */
  private String remark;
}
