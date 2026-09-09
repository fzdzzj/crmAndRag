package com.slz.crm.server.service;

import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractVO;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;

import java.util.List;

public interface ContractService {
    /**
     * 新增合同和订单
     * @param dto 合同和订单数据
     * @return 合同视图对象
     */
    ContractVO create(ContractDTO dto);

    /**
     * 新增合同及其订单
     * @param contract 合同数据
     * @param orders 订单数据
     * @return 合同视图对象
     */
    ContractVO createWithOrders(ContractDTO contract, List<OrderDTO> orders);

    /**
     * 根据ID查询合同详情（包含订单信息）
     * @param id 合同ID
     * @return 包含订单信息的合同详情
     */
    ContractANDOrderVO getDetailById(Long id);

    /**
     * 根据ID查询合同基本信息
     * @param id 合同ID
     * @return 合同视图对象
     */
    ContractVO getContractById(Long id);

    /**
     * 自定义查询合同列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param dto 查询条件
     * @return 分页结果
     */
    Page<ContractVO> contractQuery(Integer pageNum, Integer pageSize, ContractDTO dto);

    /**
     * 更新合同信息
     * @param dto 合同数据
     * @return 更新结果
     */
    boolean update(ContractDTO dto);

    /**
     * 批量删除合同
     * @param ids 合同ID列表
     * @return 成功删除的数量
     * @throws RuntimeException 当存在非废弃状态的合同时抛出异常
     */
    int batchDelete(List<Long> ids);

    /**
     * 获取所有合同
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 合同视图对象列表
     */
    Page<ContractVO> getAllContract(Integer pageNum, Integer pageSize);


}
