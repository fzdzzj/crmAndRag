package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单视图对象
 */
@Data
public class OrderVO implements Privacy {
    /**
     * 订单ID
     */
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

    /**
     * 从Entity创建VO
     * @param entity 合同订单项实体
     * @return OrderVO
     */
    public static OrderVO fromEntity(ContractOrderItemEntity entity) {
        if (entity == null) {
            return null;
        }

        OrderVO vo = new OrderVO();
        vo.setId(entity.getId());
        vo.setContractId(entity.getContractId());
        vo.setProductName(entity.getProductName());
        vo.setQuantity(entity.getQuantity());
        vo.setUnitPrice(entity.getUnitPrice());
        vo.setAmount(entity.getAmount());
        vo.setRemark(entity.getRemark());

        return vo;
    }
}
