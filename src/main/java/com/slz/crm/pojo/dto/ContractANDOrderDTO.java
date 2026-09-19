package com.slz.crm.pojo.dto;

import java.util.List;
import lombok.Data;

/** 合同与订单关联数据传输对象 */
@Data
public class ContractANDOrderDTO {
  /** * 合同信息 */
  private ContractDTO contract;

  /** * 订单列表 */
  private List<OrderDTO> orders;
}
