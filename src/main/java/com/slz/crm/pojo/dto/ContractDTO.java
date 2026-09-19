package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ContractDTO {
  /** 合同ID */
  private Long id;

  /** 合同编号 */
  private String contractNo;

  /** 商机ID */
  private Long opportunityId;

  /** 公司ID */
  private Long companyId;

  /** 合同名称 */
  private String contractName;

  /** 合同金额 */
  private BigDecimal totalAmount;

  /** 签约日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime signDate;

  /** 合同生效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startDate;

  /** 合同失效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endDate;

  /** 合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用） */
  private Integer contractStatus;

  /** 负责人id */
  private Long ownerId;
}
