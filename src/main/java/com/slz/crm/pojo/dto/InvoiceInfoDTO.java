package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 开票信息数据传输对象 */
@Data
public class InvoiceInfoDTO {
  /** 开票ID */
  private Long id;

  /** 关联合同ID */
  private Long contractId;

  /** 关联回款ID（可选） */
  private Long paymentId;

  /** 发票编号 */
  private String invoiceNo;

  /** 开票金额 */
  private BigDecimal invoiceAmount;

  /** 开票日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime invoiceDate;

  /** 发票类型（如：增值税专用发票、普通发票） */
  private String invoiceType;

  /** 状态（0已开具/1已作废） */
  private Integer status;

  /** 创建人ID */
  private Long creatorId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 备注（如：开票说明、特殊要求等） */
  private String remark;

  /** 开票日期-开始（范围查询） */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime minInvoiceDate;

  /** 开票日期-结束（范围查询） */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime maxInvoiceDate;

  /** 开票金额-最小值（范围查询） */
  private BigDecimal minInvoiceAmount;

  /** 开票金额-最大值（范围查询） */
  private BigDecimal maxInvoiceAmount;

  /** 创建时间-开始（范围查询） */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime minCreateTime;

  /** 创建时间-结束（范围查询） */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime maxCreateTime;
}
