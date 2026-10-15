package com.slz.crm.pojo.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 一键转化销售线索数据传输对象（add-lead-management-and-conversion-funnel 任务 2.1）。
 *
 * <p>单事务内将线索转化为客户公司、首要联系人及可选销售机会，转化后线索状态置为已转化（2）并回填 ID。
 */
@Data
public class LeadConvertDTO {
  /** 待转化的线索 ID（必填） */
  private Long leadId;

  /** 是否同步创建销售机会（默认 false） */
  private Boolean createOpportunity;

  /** 商机名称（可选；createOpportunity=true 时建议填写） */
  private String opportunityName;

  /** 预估商机金额（可选） */
  private BigDecimal estimatedAmount;
}
