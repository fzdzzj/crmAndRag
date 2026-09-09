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
 * '回款记录表';
 */
@Data
@AutoTable
@Table(value = "payment_record", comment = "回款记录表")
@TableName("payment_record")
@TableIndex(name = "uk_payment_no", fields = {"paymentNo"}, type = IndexTypeEnum.UNIQUE)
public class PaymentRecordEntity {
    /**
     * 回款ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 关联合同ID
     */
    @Column(comment = "关联合同ID", type = "bigint", notNull = true)
    private Long contractId;
    /**
     * 关联订单明细ID(精确到具体产品)
     */
    @Column(comment = "关联订单明细ID(精确到具体产品)", type = "bigint")
    private Long orderItemId;
    /**
     * 回款单号(唯一标识)
     */
    @Column(comment = "回款单号（唯一标识）", type = "varchar(50)", notNull = true)
    private String paymentNo;
    /**
     * 回款金额
     */
    @Column(comment = "回款金额", type = "decimal(15,2)", notNull = true)
    private BigDecimal paymentAmount;
    /**
     * 回款日期
     */
    @Column(comment = "回款日期", type = "datetime", notNull = true)
    private LocalDateTime paymentDate;
    /**
     * 回款方式(如:银行转账、支票等)
     */
    @Column(comment = "回款方式（如：银行转账、支票等）", type = "varchar(50)")
    private String paymentMethod;
    /**
     * 回款状态(0已确认/1待确认)
     */
    @Column(comment = "回款状态（0已确认/1待确认）", type = "int", notNull = true)
    private Integer paymentStatus;
    /**
     * 备注(如:付款方信息、到账说明)
     */
    @Column(comment = "备注（如：付款方信息、到账说明）", type = "varchar(500)")
    private String remark;
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


}
