package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.SysDeptDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.server.service.SysDeptService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 部门管理（沿用用户管理权限） */
@RestController
@RequestMapping("/dept")
public class SysDeptController {

  @Autowired private SysDeptService sysDeptService;

  /**
   * 查询全部启用部门（供下拉选择）
   *
   * @return 部门列表
   */
  @GetMapping("/list")
  @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
  public Result<List<SysDeptEntity>> list() {
    return Result.success(sysDeptService.listEnabled());
  }

  /**
   * 查询全部部门（含停用，供部门管理维护）
   *
   * @return 部门列表
   */
  @GetMapping("/all")
  @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
  public Result<List<SysDeptEntity>> all() {
    return Result.success(sysDeptService.listAll());
  }

  /**
   * 新增部门
   *
   * @param dto 部门信息
   * @return 是否成功
   */
  @PostMapping
  @RequirePermission(PermissionOperates.SYSTEM_CREATE_USER)
  public Result<Boolean> add(@RequestBody SysDeptDTO dto) {
    return Result.success(sysDeptService.add(dto));
  }

  /**
   * 编辑部门
   *
   * @param dto 部门信息
   * @return 是否成功
   */
  @PutMapping
  @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
  public Result<Boolean> update(@RequestBody SysDeptDTO dto) {
    return Result.success(sysDeptService.update(dto));
  }

  /**
   * 删除部门
   *
   * @param id 部门ID
   * @return 是否成功
   */
  @DeleteMapping("/{id}")
  @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
  public Result<Boolean> delete(@PathVariable Long id) {
    return Result.success(sysDeptService.delete(id));
  }
}
