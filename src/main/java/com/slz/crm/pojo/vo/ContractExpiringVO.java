package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 合同到期预警 VO（add-contract-warning-and-overdue-reminder 任务 1.2）。
 *
 * <p>承载合同到期扫描命中结果：合同标识、负责人、剩余天数、合同总额、已确认回款金额及未结敞口。 由 {@code ContractMapper.selectExpiringContracts}
 * 直接按列映射（mapUnderscoreToCamelCase）。
 */
@Data
public class ContractExpiringVO {

  private Long id;

  /** 合同编号 */
  private String contractNo;

  /** 合同名称 */
  private String contractName;

  /** 客户公司id */
  private Long companyId;

  /** 客户公司名称 */
  private String companyName;

  /** 商机id */
  private Long opportunityId;

  /** 商机名称 */
  private String opportunityName;

  /** 负责人id */
  private Long ownerId;

  /** 负责人姓名 */
  private String ownerName;

  /** 合同生效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startDate;

  /** 合同失效日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endDate;

  /** 合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用） */
  private Integer contractStatus;

  /** 合同总金额 */
  private BigDecimal totalAmount;

  /** 已确认回款金额（payment_status=0 求和） */
  private BigDecimal paidAmount;

  /** 未结敞口（合同总额 - 已确认回款） */
  private BigDecimal remainingAmount;

  /** 剩余天数（负值表示已超期，由 SQL DATEDIFF 计算） */
  private Integer remainDays;
}
