package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售机会数据传输对象 */
@Data
public class SalesOpportunityDTO {
  /** 销售机会ID */
  private Long id;

  /** 销售机会名称 */
  private String opportunityName;

  /** 关联公司ID */
  private Long companyId;

  /** 关联联系人ID */
  private Long contactId;

  /** 销售阶段 */
  private int stage;

  /** 预期金额 */
  private BigDecimal amount;

  /** 预期成交时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime expectedCloseDate;

  /** 销售机会来源 */
  private String source;

  /** 描述 */
  private String description;

  /** 负责人ID */
  private Long ownerId;

  /** 创建人ID */
  private Long creatorId;

  /** 审批人ID */
  private Long approverId;
}
