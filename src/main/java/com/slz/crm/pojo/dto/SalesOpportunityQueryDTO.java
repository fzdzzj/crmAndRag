package com.slz.crm.pojo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 销售机会查询条件数据传输对象 */
@Data
public class SalesOpportunityQueryDTO {
  /** * 销售机会名称 */
  private String opportunityName;

  /** * 公司名称 */
  private String companyName;

  /** * 联系人姓名 */
  private String contactName;

  /** * 负责人姓名 */
  private String ownerName;

  /** * 创建人姓名 */
  private String creatorName;

  /** * 审批人姓名 */
  private String approverName;

  /** * 销售机会来源 */
  private String source;

  /** * 销售机会描述 */
  private String description;

  /** * 销售阶段 */
  private Integer stage;

  /** * 最小金额 */
  private BigDecimal minAmount;

  /** * 最大金额 */
  private BigDecimal maxAmount;

  /** * 最小创建时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minCreateTime;

  /** * 最大创建时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxCreateTime;

  /** * 最小预期成交时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minExpectedCloseDate;

  /** * 最大预期成交时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxExpectedCloseDate;

  /** * 负责人用户ID */
  private Long ownerId;

  /** * 负责人用户名 */
  private String ownerUserName;
}
