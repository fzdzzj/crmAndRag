package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.LeadConvertDTO;
import com.slz.crm.pojo.dto.SalesLeadDTO;
import com.slz.crm.pojo.dto.SalesLeadQueryDTO;
import com.slz.crm.pojo.vo.LeadConvertVO;
import com.slz.crm.pojo.vo.SalesLeadVO;
import com.slz.crm.server.service.SalesLeadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 销售线索（add-lead-management-and-conversion-funnel 任务 3.1）。全部复用既有客户管理权限常量，零新增。 */
@RestController
@RequestMapping("/sales/lead")
@Slf4j
public class SalesLeadController {

  @Autowired private SalesLeadService salesLeadService;

  /**
   * 录入新线索。
   *
   * @param dto 线索数据
   * @return 新建线索 VO
   */
  @PostMapping
  @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
  public Result<SalesLeadVO> add(@RequestBody SalesLeadDTO dto) {
    return Result.success(salesLeadService.add(dto));
  }

  /**
   * 查看线索详情。
   *
   * @param id 线索 ID
   * @return 线索 VO
   */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  public Result<SalesLeadVO> getDetail(@PathVariable Long id) {
    return Result.success(salesLeadService.getDetail(id));
  }

  /**
   * 分页查询线索列表。
   *
   * @param query 查询条件
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 线索分页
   */
  @PostMapping("/query")
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  public Result<Page<SalesLeadVO>> query(
      @RequestBody(required = false) SalesLeadQueryDTO query,
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "10") Integer pageSize) {
    return Result.success(salesLeadService.queryPage(query, pageNum, pageSize));
  }

  /**
   * 更新线索资料或跟进状态（含无效关闭：status 置 3）。
   *
   * @param dto 线索数据
   * @return 是否更新成功
   */
  @PutMapping
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Boolean> update(@RequestBody SalesLeadDTO dto) {
    return Result.success(salesLeadService.update(dto));
  }

  /**
   * 逻辑删除线索。
   *
   * @param id 线索 ID
   * @return 是否删除成功
   */
  @DeleteMapping("/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CUSTOMER_COMPANY)
  public Result<Boolean> delete(@PathVariable Long id) {
    return Result.success(salesLeadService.deleteById(id));
  }

  /**
   * 一键转化销售线索为客户公司/首要联系人及销售机会。
   *
   * @param dto 转化入参
   * @return 转化结果 VO
   */
  @PostMapping("/convert")
  @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
  public Result<LeadConvertVO> convert(@RequestBody LeadConvertDTO dto) {
    return Result.success(salesLeadService.convert(dto));
  }
}
