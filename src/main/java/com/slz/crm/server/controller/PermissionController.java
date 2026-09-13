package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.RoleANDPermissionDTO;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.vo.AuditorVO;
import com.slz.crm.pojo.vo.PermissionGroupedVO;
import com.slz.crm.pojo.vo.PermissionVO;
import com.slz.crm.server.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限
 */
@RequestMapping("/permission")
@RestController
@Slf4j
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    /**
     * 获取当前用户权限（分组）
     * @return 分组后的权限（主权限 + 子权限）
     */
    @RequestMapping("/getMyPermission")
    public Result<PermissionGroupedVO> getMyPermission() {
        return Result.success(permissionService.getMyPermissionGrouped());
    }

    /**
     * 获取所有权限（按模块分组，主权限+子权限分离）
     * <p>close-permission-read-gap：复用 606（读写同权，用户已拍板），无 606 角色将收 12002。</p>
     * @return 按模块分组的权限Map
     */
    @RequestMapping("/list")
    @RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)
    public Result<Map<String, PermissionGroupedVO>> list() {
        Map<String, PermissionGroupedVO> permissionMap = permissionService.getAllPermissionsGroupedByModuleGrouped();
        return Result.success(permissionMap);
    }


    /**
     * 为角色添加或删除权限
     * @param roleANDPermissionDTOS 角色权限DTO列表
     * @return 是否添加或者删除成功
     */
    @PostMapping("/addORDeletePermissionsToRole")
    @RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)
    public Result<Boolean> addORDetelePermissionsToRole(@RequestBody RoleANDPermissionDTO roleANDPermissionDTOS) {
        return Result.success(permissionService.addOrDeletePermissionsToRole(roleANDPermissionDTOS));
    }

    /**
     * 根据角色ID查询权限（分组）
     * <p>close-permission-read-gap：复用 606（读写同权，用户已拍板），无 606 角色将收 12002。</p>
     * @param roleId 角色ID
     * @return 分组后的权限（主权限 + 子权限）
     */
    @GetMapping("/getByRole")
    @RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)
    public Result<PermissionGroupedVO> getByRole(@RequestParam("roleId") Long roleId) {
        return Result.success(permissionService.getByRoleGrouped(roleId));
    }

    /**
     * 查询拥有审批权限的用户列表
     * @return 拥有审批权限的用户列表（用户ID和用户名）
     */
    @GetMapping("/auditor")
    public Result<List<AuditorVO>> getAuditorList() {
        return Result.success(permissionService.getAuditorList());
    }






}
