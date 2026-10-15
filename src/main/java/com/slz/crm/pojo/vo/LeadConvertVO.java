package com.slz.crm.pojo.vo;

import lombok.Data;

/** 一键转化结果视图对象（add-lead-management-and-conversion-funnel 任务 2.1）。 */
@Data
public class LeadConvertVO {
  /** 转化后客户公司ID */
  private Long companyId;

  /** 转化后联系人ID */
  private Long contactId;

  /** 转化后商机ID（未创建商机时为 null） */
  private Long opportunityId;
}
