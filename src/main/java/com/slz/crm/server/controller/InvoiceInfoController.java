package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.server.service.InvoiceInfoService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 开票信息控制器 */
@RestController
@RequestMapping("/invoice/info")
public class InvoiceInfoController {

  @Autowired private InvoiceInfoService invoiceInfoService;

  /**
   * 创建开票信息
   *
   * @param dto 开票信息DTO
   * @return 是否创建成功
   */
  @PostMapping
  @RequirePermission(PermissionOperates.FINANCE_RECORD_INVOICE)
  public Result<Boolean> create(@RequestBody InvoiceInfoDTO dto) {
    return Result.success(invoiceInfoService.create(dto) != null);
  }

  /**
   * 更新开票信息
   *
   * @param dto 开票��息DTO
   * @return 是否更新成功
   */
  @PutMapping
  @RequirePermission(PermissionOperates.FINANCE_EDIT_INVOICE)
  public Result<Boolean> update(@RequestBody InvoiceInfoDTO dto) {
    return Result.success(invoiceInfoService.update(dto));
  }

  /**
   * 删除开票信息
   *
   * @param idList 开票信息ID列表
   * @return 删除数量
   */
  @DeleteMapping
  @RequirePermission(PermissionOperates.FINANCE_DELETE_INVOICE)
  public Result<Integer> delete(@RequestBody List<Long> idList) {
    return Result.success(invoiceInfoService.deleteByIds(idList));
  }

  /**
   * 根据ID查询开票信息详情
   *
   * @param id 开票信息ID
   * @return 开票信息详情
   */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_INVOICE)
  public Result<InvoiceInfoVO> getById(@PathVariable Long id) {
    return Result.success(invoiceInfoService.getDetailById(id));
  }

  /**
   * 分页查询开票信息
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件DTO
   * @return 开票信息分页结果
   */
  @PostMapping("/query")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_INVOICE)
  public Result<Page<InvoiceInfoVO>> queryPage(
      @RequestParam Integer pageNum,
      @RequestParam Integer pageSize,
      @RequestBody InvoiceInfoDTO dto) {
    return Result.success(invoiceInfoService.queryPage(pageNum, pageSize, dto));
  }

  /**
   * 根据合同ID查询开票信息列表
   *
   * @param contractId 合同ID
   * @return 开票信息列表
   */
  @GetMapping("/contract/{contractId}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_INVOICE)
  public Result<List<InvoiceInfoVO>> listByContractId(@PathVariable Long contractId) {
    return Result.success(invoiceInfoService.listByContractId(contractId));
  }

  /**
   * 根据回款ID查询开票信息列表
   *
   * @param paymentId 回款ID
   * @return 开票信息列表
   */
  @GetMapping("/payment/{paymentId}")
  @RequirePermission(PermissionOperates.FINANCE_VIEW_INVOICE)
  public Result<List<InvoiceInfoVO>> listByPaymentId(@PathVariable Long paymentId) {
    return Result.success(invoiceInfoService.listByPaymentId(paymentId));
  }

  /**
   * 作废发票
   *
   * @param id 开票信息ID
   * @return 是否作废成功
   */
  @PutMapping("/void/{id}")
  @RequirePermission(PermissionOperates.FINANCE_EDIT_INVOICE)
  public Result<Boolean> voidInvoice(@PathVariable Long id) {
    return Result.success(invoiceInfoService.voidInvoice(id));
  }
}
