package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.ao.Privacy;
import lombok.Data;

import java.util.List;

/**
 * 合同和订单VO
 */
@Data
public class ContractANDOrderVO implements Privacy {

    private ContractVO contract;
    private List<OrderVO> orders;
}
