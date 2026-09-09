package com.slz.crm.pojo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单数据传输对象
 */
@Data
public class OrderDTO {

    /** * 订单ID */
    private Long id;
    /**
     * 合同ID
     */
    private Long contractId;
    /**
     * 产品名称
     */
    private String productName;
    /**
     * 数量
     */
    private BigDecimal quantity;
    /**
     * 单价
     */
    private BigDecimal unitPrice;
    /**
     * 金额
     */
    private BigDecimal amount;
    /**
     * 备注
     */
    private String remark;

}
