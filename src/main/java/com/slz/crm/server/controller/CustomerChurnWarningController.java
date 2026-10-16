package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.vo.CustomerChurnWarningVO;
import com.slz.crm.server.schedule.ChurnWarningScheduler;
import com.slz.crm.server.service.CustomerChurnWarningService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户流失预警与再激活控制器（add-customer-churn-warning-and-reactivation 任务 2.1）。
 *
 * <p>零新权限常量，严格复用既有客户权限常量：清单查询复用 117（{@code CUSTOMER_VIEW_COMPANY}），手动触发扫描复用 113（{@code
 * CUSTOMER_UPDATE_CUSTOMER_COMPANY}）——与公海池 controller 同族同锚。 新 controller 已登记 {@code
 * PermissionCoverageScanner.CONTROLLER_REGISTRY}，门禁确保端点零裸奔。
 */
@RestController
@RequestMapping("/customer/churn/warning")
@Slf4j
public class CustomerChurnWarningController {

  @Autowired private CustomerChurnWarningService customerChurnWarningService;

  @Autowired private ChurnWarningScheduler churnWarningScheduler;

  /** 手动触发一次流失风险扫描与待办生成（复用客户变更权限；用于测试与运维）。 */
  @PostMapping("/scan")
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Integer> scan() {
    return Result.success(churnWarningScheduler.triggerScan());
  }

  /** 查询当前流失风险客户清单（即时计算不落库；复用客户查看权限）。 */
  @GetMapping("/list")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  public Result<List<CustomerChurnWarningVO>> list(
      @RequestParam(value = "limit", required = false) Integer limit) {
    return Result.success(customerChurnWarningService.queryInactiveCustomers(limit));
  }
}
