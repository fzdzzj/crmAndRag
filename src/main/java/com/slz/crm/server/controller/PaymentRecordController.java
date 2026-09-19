package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.server.service.PaymentRecordService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 回款记录控制器 */
@RestController
@RequestMapping("/payment/record")
public class PaymentRecordController {

  @Autowired private PaymentRecordService paymentRecordService;

  /**
   * 创建回款记录
   *
   * @param dto 回款记录DTO
   * @return 是否创建成功
   */
  @PostMapping
  @RequirePermission(PermissionOperates.FINANCE_RECORD_PAYMENT)
  public Result<Boolean> create(@RequestBody PaymentRecordDTO dto) {
    return Result.success(paymentRecordService.create(dto) != null);
  }

  /**
   * 更新回款记录
   *
   * @param dto 回款记录DTO
   * @return 是否更新成功
   */
  @PutMapping
  @RequirePermission(PermissionOperates.FINANCE_EDIT_PAYMENT)
  public Result<Boolean> update(@RequestBody PaymentRecordDTO dto) {
    return Result.success(paymentRecordService.update(dto));
  }

  /**
   * 删除回款记录
   *
   * @param idList 回款记录ID列表
   * @return 删除数量
   */
  @DeleteMapping
  @RequirePermission(PermissionOperates.FINANCE_DELETE_PAYMENT)
  public Result<Integer> delete(@RequestBody List<Long> idList) {
    return Result.success(paymentRecordService.deleteByIds(idList));
  }

  /**
   * 根据ID查询回款记录详情
   *
   * @param id 回款记录ID
   * @return 回款记录详情
   */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_PAYMENT)
  public Result<PaymentRecordVO> getById(@PathVariable Long id) {
    return Result.success(paymentRecordService.getDetailById(id));
  }

  /**
   * 分页查询回款记录
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件DTO
   * @return 回款记录分页结果
   */
  @PostMapping("/query")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_PAYMENT)
  public Result<Page<PaymentRecordVO>> queryPage(
      @RequestParam Integer pageNum,
      @RequestParam Integer pageSize,
      @RequestBody PaymentRecordDTO dto) {
    return Result.success(paymentRecordService.queryPage(pageNum, pageSize, dto));
  }

  /**
   * 根据合同ID查询回款记录列表
   *
   * @param contractId 合同ID
   * @return 回款记录列表
   */
  @GetMapping("/contract/{contractId}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_PAYMENT)
  public Result<List<PaymentRecordVO>> listByContractId(@PathVariable Long contractId) {
    return Result.success(paymentRecordService.listByContractId(contractId));
  }

  /**
   * 根据订单明细ID查询回款记录列表
   *
   * @param orderItemId 订单明细ID
   * @return 回款记录列表
   */
  @GetMapping("/order-item/{orderItemId}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_PAYMENT)
  public Result<List<PaymentRecordVO>> listByOrderItemId(@PathVariable Long orderItemId) {
    return Result.success(paymentRecordService.listByOrderItemId(orderItemId));
  }

  /**
   * 确认回款
   *
   * @param id 回款记录ID
   * @return 是否确认成功
   */
  @PutMapping("/confirm/{id}")
  @RequirePermission(PermissionOperates.FINANCE_EDIT_PAYMENT)
  public Result<Boolean> confirmPayment(@PathVariable Long id) {
    return Result.success(paymentRecordService.confirmPayment(id));
  }
}
