package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.service.ContractOrderItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 合同订单项
 */
@RestController
@RequestMapping("/order")
@Slf4j
public class ContractOrderItemController {

    @Autowired
    private ContractOrderItemService contractOrderItemService;

    /**
     * 追加订单
     *
     * @param orders 订单列表
     * @return 新增订单数量
     */
    @PostMapping
    @RequirePermission(PermissionOperates.SALES_APPEND_ORDER)
    public Result<Integer> append(@RequestBody List<OrderDTO> orders) {
        List<OrderVO> createdOrders = contractOrderItemService.createBatch(orders);
        return Result.success(createdOrders.size());
    }

    /**
     * 批量删除订单
     *
     * @param ids 订单ID列表
     * @return 删除数量
     */
    @DeleteMapping
    @RequirePermission(PermissionOperates.SALES_UPDATE_ORDER)
    public Result<Integer> delete(@RequestBody List<Long> ids) {
        return Result.success(contractOrderItemService.batchDelete(ids));
    }

    /**
     * 自定义查询订单列表（带条件）
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @param dto      查询条件
     * @return 分页结果
     */
    @PostMapping("/query")
    @RequirePermission(PermissionOperates.SALES_VIEW_ORDER)
    public Result<Page<OrderVO>> orderQuery(@RequestParam Integer pageNum, @RequestParam Integer pageSize, @RequestBody OrderDTO dto) {
        return Result.success(contractOrderItemService.orderQuery(pageNum, pageSize, dto));
    }

    /**
     * 根据ID查询合同详情（包含订单信息）
     *
     * @param id 订单ID
     * @return 包含订单信息的订单详情
     */
    @GetMapping("/{id}")
    @RequirePermission(PermissionOperates.SALES_VIEW_ORDER)
    public Result<OrderVO> getDetailById(@PathVariable Long id) {
        OrderVO orderDetail = contractOrderItemService.getDetailById(id);
        return Result.success(orderDetail);
    }

    /**
     * 更新合同关联的订单
     *
     * @param orders 订单列表
     * @return 更新数量
     */
    @PutMapping
    @RequirePermission(PermissionOperates.SALES_UPDATE_ORDER)
    public Result<Integer> updateOrders(@RequestBody List<OrderDTO> orders) {
        return Result.success(contractOrderItemService.updateOrders(orders));
    }

    /**
     * 获取所有订单
     * @param pageNum 页码
     * @param pageSize 数量
     * @return 订单分页结果
     */
    @GetMapping
    @RequirePermission(PermissionOperates.SALES_VIEW_ORDER)
    public Result<Page<OrderVO>> getAllOrders(Integer pageNum, Integer pageSize) {
        return Result.success(contractOrderItemService.getAllOrders(pageNum, pageSize));
    }
}
