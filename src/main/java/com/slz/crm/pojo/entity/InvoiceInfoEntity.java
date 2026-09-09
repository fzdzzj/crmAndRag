package com.slz.crm.pojo.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * '开票信息表';
 */
@Data
@AutoTable
@Table(value = "invoice_info", comment = "开票信息表")
@TableName("invoice_info")
@TableIndex(name = "uk_invoice_no", fields = {"invoiceNo"}, type = IndexTypeEnum.UNIQUE)
public class InvoiceInfoEntity {
    /**
     * 开票ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "开票ID")
    private Long id;
    /**
     * 关联合同ID
     */
    @Column(comment = "关联合同ID", type = "bigint", notNull = true)
    private Long contractId;
    /**
     * 关联回款ID(关联具体回款)
     */
    @Column(comment = "关联回款ID(关联具体回款)", type = "bigint")
    private Long paymentId;
    /**
     * 发票编号
     */
    @Column(comment = "发票编号", type = "varchar(50)", notNull = true)
    private String invoiceNo;
    /**
     * 开票金额
     */
    @Column(comment = "开票金额", type = "decimal(15,2)", notNull = true)
    private BigDecimal invoiceAmount;
    /**
     * 开票日期
     */
    @Column(comment = "开票日期", type = "datetime", notNull = true)
    private LocalDateTime invoiceDate;
    /**
     * 发票类型(如:增值税专用发票、普通发票)
     */
    @Column(comment = "发票类型（如：增值税专用发票、普通发票）", type = "varchar(50)")
    private String invoiceType;
    /**
     * 状态(0已开具/1已作废)
     */
    @Column(comment = "状态（0已开具/1已作废）", type = "int", notNull = true)
    private Integer status;
    /**
     * 创建人ID
     */
    @Column(comment = "创建人ID", type = "bigint", notNull = true)
    private Long creatorId;
    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 备注（如：开票说明、特殊要求等）
     */
    @Column(comment = "备注（如：开票说明、特殊要求等）", type = "text")
    private String remark;
}
