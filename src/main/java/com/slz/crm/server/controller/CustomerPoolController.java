package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.CustomerPoolAssignDTO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerPoolLogVO;
import com.slz.crm.server.schedule.CustomerPoolRecycleScheduler;
import com.slz.crm.server.service.CustomerPoolService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户公海池控制器（add-customer-public-sea-pool 任务 4.1）。
 *
 * <p>零新权限常量，严格复用既有 113/116/117：公海查询 116、认领/退回/分派/手动触发回收 113、流转日志查看 117。 新 controller 已登记 {@code
 * PermissionCoverageScanner.CONTROLLER_REGISTRY}，门禁确保端点零裸奔。
 */
@RestController
@RequestMapping("/company/pool")
@Slf4j
public class CustomerPoolController {

  @Autowired private CustomerPoolService customerPoolService;

  @Autowired private CustomerPoolRecycleScheduler customerPoolRecycleScheduler;

  /** 分页查询公海池客户（复用 116 查询权限，专有过滤不被私海 DataScope 阻隔）。 */
  @GetMapping
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  public Result<Page<CustomerCompanyVO>> pool(
      @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
      @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
      @RequestParam(value = "companyName", required = false) String companyName,
      @RequestParam(value = "industry", required = false) String industry,
      @RequestParam(value = "grade", required = false) Integer grade) {
    return Result.success(
        customerPoolService.queryPool(pageNum, pageSize, companyName, industry, grade));
  }

  /** 业务员自主认领公海客户（复用 113 更新权限；私海满 50 配额抛异常）。 */
  @PostMapping("/claim/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Boolean> claim(@PathVariable Long id) {
    return Result.success(customerPoolService.claim(id));
  }

  /** 业务员主动退回客户至公海（复用 113 更新权限；仅负责人或主管可退回）。 */
  @PostMapping("/release/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Boolean> release(
      @PathVariable Long id, @RequestParam(value = "reason", required = false) String reason) {
    return Result.success(customerPoolService.release(id, reason));
  }

  /** 主管分派公海客户给指定人员（复用 113 更新权限；校验目标私海配额）。 */
  @PostMapping("/assign")
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Boolean> assign(@Valid @RequestBody CustomerPoolAssignDTO dto) {
    return Result.success(customerPoolService.assign(dto));
  }

  /** 查询客户公海流转历史日志（复用 117 查看权限）。 */
  @GetMapping("/logs/{companyId}")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  public Result<List<CustomerPoolLogVO>> logs(@PathVariable Long companyId) {
    return Result.success(customerPoolService.queryLogs(companyId));
  }

  /** 手动触发一次超时自动回收扫描（复用 113 更新权限；用于测试与运维）。 */
  @PostMapping("/recycle/trigger")
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Integer> recycleTrigger() {
    int recycled = customerPoolRecycleScheduler.recycleOverdueCustomers();
    return Result.success(recycled);
  }
}
