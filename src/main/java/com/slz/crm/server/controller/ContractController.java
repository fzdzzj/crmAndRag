package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.ContractANDOrderDTO;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.server.service.ContractService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 合同 */
@RestController
@RequestMapping("/contract")
@Slf4j
public class ContractController {

  @Autowired private ContractService contractService;

  /**
   * 新增合同
   *
   * @param dto 合同AND订单DTO
   * @return 合同VO
   */
  @PostMapping
  @RequirePermission(PermissionOperates.SALES_CREATE_CONTRACT)
  public Result<ContractVO> create(@RequestBody ContractANDOrderDTO dto) {
    return Result.success(contractService.createWithOrders(dto.getContract(), dto.getOrders()));
  }

  /**
   * 根据ID查询合同详情（包含订单信息）
   *
   * @param id 合同ID
   * @return 包含订单信息的合同详情
   */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)
  public Result<ContractANDOrderVO> getDetailById(@PathVariable Long id) {
    ContractANDOrderVO contractDetail = contractService.getDetailById(id);
    return Result.success(contractDetail);
  }

  /**
   * 自定义查询合同列表（带条件）
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件
   * @return 分页结果
   */
  @PostMapping("/query")
  @RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)
  public Result<Page<ContractVO>> contractQuery(
      @RequestParam Integer pageNum, @RequestParam Integer pageSize, @RequestBody ContractDTO dto) {
    Page<ContractVO> page = contractService.contractQuery(pageNum, pageSize, dto);
    return Result.success(page);
  }

  /**
   * 更新合同信息
   *
   * @param dto 合同数据
   * @return 更新结果
   */
  @PutMapping
  @RequirePermission(PermissionOperates.SALES_UPDATE_CONTRACT)
  public Result<Boolean> update(@RequestBody ContractDTO dto) {
    boolean result = contractService.update(dto);
    return Result.success(result);
  }

  /**
   * 批量删除合同
   *
   * @param ids 合同ID列表
   * @return 成功删除的数量
   */
  @DeleteMapping
  @RequirePermission(PermissionOperates.SALES_UPDATE_CONTRACT)
  public Result<Integer> delete(@RequestBody List<Long> ids) {
    Result<Integer> result;
    try {
      int count = contractService.batchDelete(ids);
      result = Result.success(count);
    } catch (RuntimeException e) {
      result = Result.error(e.getMessage());
    }
    return result;
  }

  /**
   * 获取所有合同
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 合同列表
   */
  @GetMapping
  @RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)
  public Result<Page<ContractVO>> getAllContract(
      @RequestParam Integer pageNum, @RequestParam Integer pageSize) {
    if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
      pageNum = 1;
      pageSize = 10;
    }
    return Result.success(contractService.contractQuery(pageNum, pageSize, new ContractDTO()));
  }
}
