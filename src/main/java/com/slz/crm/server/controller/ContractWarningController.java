package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.vo.ContractExpiringVO;
import com.slz.crm.pojo.vo.ContractOverduePaymentVO;
import com.slz.crm.server.schedule.ContractWarningScheduler;
import com.slz.crm.server.service.ContractWarningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合同履约到期预警与逾期应收账款待办控制器（add-contract-warning-and-overdue-reminder 任务 3.2）。
 *
 * <p>零新权限常量，严格复用既有 215（SALES_VIEW_CONTRACT，查询）与 214（SALES_UPDATE_CONTRACT，触发写）。 新 controller 已登记
 * {@code PermissionCoverageScanner.CONTROLLER_REGISTRY}，门禁确保端点零裸奔。
 */
@RestController
@RequestMapping("/contract/warning")
@Slf4j
public class ContractWarningController {

  @Autowired private ContractWarningService contractWarningService;

  @Autowired private ContractWarningScheduler contractWarningScheduler;

  /** 分页查询到期/临期合同（复用查看合同权限）。 */
  @GetMapping("/expiring")
  @RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)
  public Result<Page<ContractExpiringVO>> expiring(
      @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
      @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
      @RequestParam(value = "days", required = false) Integer days) {
    return Result.success(contractWarningService.queryExpiringContracts(pageNum, pageSize, days));
  }

  /** 分页查询逾期应收账款合同（复用查看合同权限）。 */
  @GetMapping("/overdue-payment")
  @RequirePermission(PermissionOperates.SALES_VIEW_CONTRACT)
  public Result<Page<ContractOverduePaymentVO>> overduePayment(
      @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
      @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
    return Result.success(contractWarningService.queryOverduePaymentContracts(pageNum, pageSize));
  }

  /** 手动触发一次全量扫描与待办生成（复用更新合同权限；用于测试与运维）。 */
  @PostMapping("/trigger")
  @RequirePermission(PermissionOperates.SALES_UPDATE_CONTRACT)
  public Result<Integer> trigger() {
    int created = contractWarningScheduler.triggerScan();
    return Result.success(created);
  }
}
