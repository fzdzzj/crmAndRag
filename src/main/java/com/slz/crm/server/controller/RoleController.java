package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.vo.RoleANDPermissionVO;
import com.slz.crm.pojo.vo.RoleVO;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.RoleService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 角色 */
@RestController
@RequestMapping("/role")
@Slf4j
public class RoleController {

  @Autowired private RoleService roleService;

  @Autowired private PermissionService permissionService;

  /**
   * 获取角色列表
   *
   * @return 角色列表
   */
  @GetMapping("/list")
  @RequirePermission(PermissionOperates.SYSTEM_VIEW_ROLE)
  public Result<Page<RoleANDPermissionVO>> list(
      @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
      @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
    Page<RoleVO> result = roleService.list(pageNum, pageSize);
    Page<RoleANDPermissionVO> ans = new Page<>();
    BeanUtils.copyProperties(result, ans);
    List<RoleANDPermissionVO> list = new ArrayList<>();

    result
        .getRecords()
        .forEach(
            vo -> {
              List<PermissionsEntity> permissionList =
                  permissionService.getPermissionList(vo.getId());
              RoleANDPermissionVO roleANDPermissionVO =
                  RoleANDPermissionVO.fromEntity(
                      vo, permissionService.groupPermissions(permissionList));
              list.add(roleANDPermissionVO);
            });
    ans.setRecords(list);
    return Result.success(ans);
  }

  /**
   * 获取当前用户角色
   *
   * @return
   */
  @GetMapping
  public Result<RoleVO> getMyRole() {
    RoleVO roleVO = roleService.getMyRole();
    return Result.success(roleVO);
  }

  /**
   * 新增角色
   *
   * @param roleName 角色名称
   * @param roleDesc 角色描述
   * @return 是否添加成功
   */
  @PostMapping("/add")
  @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ROLE)
  public Result<Boolean> addRole(@RequestParam String roleName, @RequestParam String roleDesc) {
    RoleEntity roleEntity = new RoleEntity();
    roleEntity.setRoleName(roleName);
    roleEntity.setRoleDesc(roleDesc);
    return Result.success(roleService.save(roleEntity));
  }

  /**
   * 删除角色
   *
   * @param roleId 角色ID
   * @return 是否添加成功
   */
  @DeleteMapping()
  @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ROLE)
  public Result<Boolean> delete(@RequestParam Long roleId) {
    roleService.delete(roleId, true);
    return Result.success(true);
  }
}
