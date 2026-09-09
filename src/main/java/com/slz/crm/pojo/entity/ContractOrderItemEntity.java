package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 合同订单详情
 */
@Data
@AutoTable
@Table(value = "contract_order_item", comment = "合同订单明细表")
@TableName("contract_order_item")
@TableIndex(name = "contract_order_item_pk", fields = {"productName", "remark", "quantity", "unitPrice", "contractId"}, type = IndexTypeEnum.UNIQUE)
public class ContractOrderItemEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "明细ID")
    private Long id;
    /**
     * 合同ID
     */
    @TableField("contract_id")
    @Column(comment = "关联合同ID", type = "bigint", notNull = true)
    private Long contractId;
    /**
     * 产品名称
     */
    @TableField("product_name")
    @Column(comment = "产品/ 服务名称", type = "varchar(100)", notNull = true)
    private String productName;
    /**
     * 数量
     */
    @TableField("quantity")
    @Column(comment = "数量", type = "decimal(10,2)", notNull = true)
    private BigDecimal quantity;
    /**
     * 单价
     */
    @TableField("unit_price")
    @Column(comment = "单价", type = "decimal(15,2)", notNull = true)
    private BigDecimal unitPrice;
    /**
     * 金额
     */
    @TableField("amount")
    @Column(comment = "金额（数量×单价）", type = "decimal(15,2)", notNull = true)
    private BigDecimal amount;
    /**
     * 备注
     */
    @TableField("remark")
    @Column(comment = "备注（如：规格、服务周期等）", type = "varchar(500)")
    private String remark;
    /**
     * 是否删除
     */
    @Column(comment = "是否删除", type = "int", notNull = true, defaultValue = "0")
    private Integer isDeleted;

}
