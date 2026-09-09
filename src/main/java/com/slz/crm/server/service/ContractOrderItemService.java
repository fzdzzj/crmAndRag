package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.OrderVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ContractOrderItemService {

    /**
     * 追加订单
     * @param orders 订单列表
     * @return 新订单 VO 列表，每项携带新记录 ID
     */
    List<OrderVO> createBatch(List<OrderDTO> orders);

    /**
     * 更新合同关联的订单
     * @param orders 订单列表
     * @return 更新结果
     */
    int updateOrders(List<OrderDTO> orders);

    /**
     * 批量删除订单
     * @param ids 订单ID列表
     * @return 删除数量
     */
    int batchDelete(List<Long> ids);

    /**
     * 自定义查询订单列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param dto 查询条件
     * @return 分页结果
     */
    Page<OrderVO> orderQuery(Integer pageNum, Integer pageSize, OrderDTO dto);

    /**
     * 根据ID查询订单详情
     * @param id 订单ID
     * @return 订单详情
     */
    OrderVO getDetailById(Long id);

    /**
     * 根据合同ID查询订单列表
     * @param contractId 合同ID
     * @return 订单列表
     */
    List<OrderVO> getOrderListByContractId(Long contractId);

    /**
     * 获取所有订单
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    Page<OrderVO> getAllOrders(Integer pageNum, Integer pageSize);

}
