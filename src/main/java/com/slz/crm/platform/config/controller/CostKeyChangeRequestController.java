package com.slz.crm.platform.config.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.platform.config.CostKeyChangeRequestVO;
import com.slz.crm.platform.config.service.CostKeyChangeRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本键变更申请-审批 REST 接口（add-cost-key-approval-workflow 任务 3.1）。
 *
 * <p>权限语义：5 端点全挂方法级 {@code @RequirePermission(PLATFORM_DYNAMIC_CONFIG_MANAGE(608))}； 普通 608
 * 持有者可提交申请、查询本人申请、撤回本人申请； approve/reject 审批通过与驳回由服务层超管闸控制（仅超级管理员 roleId=1 可操作，非超管抛 FORBIDDEN
 * 96005）。
 */
@RestController
@RequestMapping("/platform/config/cost-requests")
public class CostKeyChangeRequestController {

  private final CostKeyChangeRequestService requestService;

  public CostKeyChangeRequestController(CostKeyChangeRequestService requestService) {
    this.requestService = requestService;
  }

  /**
   * 提交成本键变更申请。
   *
   * @param req 提交参数（键必须属于 COST 22 键且已注册，值预校验同写路径）
   * @return 申请单视图（PENDING 态）
   */
  @PostMapping
  @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)
  public Result<CostKeyChangeRequestVO> submit(@RequestBody CostKeyChangeRequestSubmitReq req) {
    return Result.success(requestService.submit(req));
  }

  /**
   * 清单分页查询。
   *
   * @param pageNum 页码
   * @param pageSize 每页条数
   * @param status 状态过滤（可选）
   * @param configKey 键名过滤（可选）
   * @return 分页申请单（超管看全部、普通 608 用户仅看本人）
   */
  @GetMapping
  @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)
  public Result<Page<CostKeyChangeRequestVO>> list(
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "10") Integer pageSize,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String configKey) {
    return Result.success(requestService.list(pageNum, pageSize, status, configKey));
  }

  /**
   * 撤回申请（仅本人且处于 PENDING 态）。
   *
   * @param id 申请单 ID
   * @return 申请单视图（WITHDRAWN 态）
   */
  @PostMapping("/{id}/withdraw")
  @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)
  public Result<CostKeyChangeRequestVO> withdraw(@PathVariable Long id) {
    return Result.success(requestService.withdraw(id));
  }

  /**
   * 审批通过（仅超级管理员）。
   *
   * @param id 申请单 ID
   * @return 申请单视图（APPROVED 态，回填 appliedConfigVersion）
   */
  @PostMapping("/{id}/approve")
  @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)
  public Result<CostKeyChangeRequestVO> approve(@PathVariable Long id) {
    return Result.success(requestService.approve(id));
  }

  /**
   * 驳回申请（仅超级管理员）。
   *
   * @param id 申请单 ID
   * @param req 驳回理由参数
   * @return 申请单视图（REJECTED 态）
   */
  @PostMapping("/{id}/reject")
  @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)
  public Result<CostKeyChangeRequestVO> reject(
      @PathVariable Long id, @RequestBody(required = false) CostKeyChangeRequestRejectReq req) {
    return Result.success(requestService.reject(id, req));
  }
}
