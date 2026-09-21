package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import java.math.BigDecimal;
import lombok.Data;

/** 订单视图对象 */
@Data
public class OrderVO implements Privacy {
  /** 订单ID */
  private Long id;

  /** 合同ID */
  private Long contractId;

  /** 产品名称 */
  private String productName;

  /** 数量 */
  private BigDecimal quantity;

  /** 单价 */
  private BigDecimal unitPrice;

  /** 金额 */
  private BigDecimal amount;

  /** 备注 */
  private String remark;

  /**
   * 从Entity创建VO
   *
   * @param entity 合同订单项实体
   * @return OrderVO
   */
  public static OrderVO fromEntity(ContractOrderItemEntity entity) {
    OrderVO vo = null;
    if (entity != null) {
      vo = new OrderVO();
      vo.setId(entity.getId());
      vo.setContractId(entity.getContractId());
      vo.setProductName(entity.getProductName());
      vo.setQuantity(entity.getQuantity());
      vo.setUnitPrice(entity.getUnitPrice());
      vo.setAmount(entity.getAmount());
      vo.setRemark(entity.getRemark());
    }
    return vo;
  }
}
