package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 回款记录视图对象 */
@Data
public class PaymentRecordVO {
  /** 回款ID */
  private Long id;

  /** 关联合同ID */
  private Long contractId;

  /** 合同编号 */
  private String contractNo;

  /** 合同名称 */
  private String contractName;

  /** 关联订单明细ID */
  private Long orderItemId;

  /** 产品名称 */
  private String productName;

  /** 回款单号 */
  private String paymentNo;

  /** 回款金额 */
  private BigDecimal paymentAmount;

  /** 回款日期 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime paymentDate;

  /** 回款方式 */
  private String paymentMethod;

  /** 回款状态（0已确认/1待确认） */
  private Integer paymentStatus;

  /** 回款状态描述 */
  private String paymentStatusDesc;

  /** 备注 */
  private String remark;

  /** 创建人ID */
  private Long creatorId;

  /** 创建人姓名 */
  private String creatorName;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;
}
