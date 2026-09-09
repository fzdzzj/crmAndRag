package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContractOrderItemMapper extends BaseMapper<ContractOrderItemEntity> {
    int insertBatch(List<ContractOrderItemEntity> list);

    /**
     * 根据合同ID查询订单列表
     * @param contractId 合同ID
     * @return 订单列表
     */
    @Select("SELECT * FROM contract_order_item " +
            "WHERE contract_id = #{contractId} ")
    List<ContractOrderItemEntity> selectByContractId(@Param("contractId") Long contractId);

}
