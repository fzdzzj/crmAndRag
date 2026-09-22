package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.service.SalesOpportunityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 销售机会 */
@RestController
@RequestMapping("/sales")
@Slf4j
public class SalesOpportunityController {

  @Autowired private SalesOpportunityService salesOpportunityService;

  /**
   * 修改销售机会
   *
   * @param salesOpportunityDTO 销售机会DTO
   * @return 是否修改成功
   */
  @PutMapping
  @RequirePermission(PermissionOperates.SALES_UPDATE_SALE_OPPORTUNITY)
  public Result<Boolean> update(@RequestBody SalesOpportunityDTO salesOpportunityDTO) {
    return Result.success(salesOpportunityService.update(salesOpportunityDTO));
  }

  /**
   * 删除销售机会
   *
   * @param id 销售机会ID
   * @return 是否删除成功
   */
  @DeleteMapping("/{id}")
  @RequirePermission(PermissionOperates.SALES_DELETE_SALE_OPPORTUNITY)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 删除service外呼，失败转错误结果
  public Result<Boolean> delete(@PathVariable("id") Long id) {
    Result<Boolean> result;
    try {
      result = Result.success(salesOpportunityService.delete(id));
    } catch (RuntimeException e) {
      result = Result.error(e.getMessage());
    }
    return result;
  }

  /**
   * 获取所有销售机会
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 销售机会VO列表
   */
  @GetMapping
  @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY)
  public Result<Page<SalesOpportunityVO>> getAllSalesOpportunity(
      @RequestParam Integer pageNum, @RequestParam Integer pageSize) {
    if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
      pageNum = 1;
      pageSize = 10;
    }
    return Result.success(salesOpportunityService.getAllSalesOpportunity(pageNum, pageSize));
  }

  /**
   * 创建销售机会
   *
   * @param dto 销售机会DTO
   * @return 销售机会VO
   */
  @PostMapping
  @RequirePermission(PermissionOperates.SALES_CREATE_SALE_OPPORTUNITY)
  public Result<SalesOpportunityVO> create(@RequestBody SalesOpportunityDTO dto) {
    return Result.success(salesOpportunityService.create(dto));
  }

  /**
   * 自定义查询销售机会
   *
   * @param queryDTO 查询条件
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 销售机会VO列表
   */
  @GetMapping("/query")
  @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY)
  public Result<Page<SalesOpportunityVO>> getSalesOpportunityByQuery(
      @ModelAttribute SalesOpportunityQueryDTO queryDTO,
      @RequestParam Integer pageNum,
      @RequestParam Integer pageSize) {
    return Result.success(
        salesOpportunityService.getSalesOpportunityByQuery(queryDTO, pageNum, pageSize));
  }

  /**
   * 根据销售机会ID查询商机详情（包含商机信息、业务活动和状态变更记录）
   *
   * @param opportunityId 销售机会ID
   * @return 商机详情VO（包含商机信息、按商机状态分组的业务活动列表、状态变更记录）
   */
  @GetMapping("/detail/{opportunityId}")
  @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 详情查询service外呼，失败转错误结果
  public Result<com.slz.crm.pojo.vo.OpportunityDetailVO> getOpportunityDetailById(
      @PathVariable("opportunityId") Long opportunityId) {
    Result<com.slz.crm.pojo.vo.OpportunityDetailVO> result;
    try {
      result = Result.success(salesOpportunityService.getOpportunityDetailById(opportunityId));
    } catch (RuntimeException e) {
      result = Result.error(e.getMessage());
    }
    return result;
  }

  /**
   * 根据合同ID查询商机详情（包含商机信息、业务活动和状态变更记录）
   *
   * @param contractId 合同ID
   * @return 商机详情VO（包含商机信息、按商机状态分组的业务活动列表、状态变更记录）
   */
  @GetMapping("/detail/by-contract/{contractId}")
  @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 详情查询service外呼，失败转错误结果
  public Result<com.slz.crm.pojo.vo.OpportunityDetailVO> getOpportunityDetailByContractId(
      @PathVariable("contractId") Long contractId) {
    Result<com.slz.crm.pojo.vo.OpportunityDetailVO> result;
    try {
      result = Result.success(salesOpportunityService.getOpportunityDetailByContractId(contractId));
    } catch (RuntimeException e) {
      result = Result.error(e.getMessage());
    }
    return result;
  }
}
