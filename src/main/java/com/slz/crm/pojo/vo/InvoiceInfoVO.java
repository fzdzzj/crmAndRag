package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 开票信息视图对象
 */
@Data
public class InvoiceInfoVO {
    /**
     * 开票ID
     */
    private Long id;

    /**
     * 关联合同ID
     */
    private Long contractId;

    /**
     * 合同编号
     */
    private String contractNo;

    /**
     * 合同名称
     */
    private String contractName;

    /**
     * 关联回款ID
     */
    private Long paymentId;

    /**
     * 回款单号
     */
    private String paymentNo;

    /**
     * 回款金额
     */
    private BigDecimal paymentAmount;

    /**
     * 发票编号
     */
    private String invoiceNo;

    /**
     * 开票金额
     */
    private BigDecimal invoiceAmount;

    /**
     * 开票日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime invoiceDate;

    /**
     * 发票类型
     */
    private String invoiceType;

    /**
     * 状态（0已开具/1已作废）
     */
    private Integer status;

    /**
     * 状态描述
     */
    private String statusDesc;

    /**
     * 创建人ID
     */
    private Long creatorId;

    /**
     * 创建人姓名
     */
    private String creatorName;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 备注（如：开票说明、特殊要求等）
     */
    private String remark;
}
