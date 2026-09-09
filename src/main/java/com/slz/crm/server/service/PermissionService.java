package com.slz.crm.server.service;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.RoleANDPermissionDTO;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.vo.AuditorVO;
import com.slz.crm.pojo.vo.PermissionGroupedVO;
import com.slz.crm.pojo.vo.PermissionVO;

import java.util.List;
import java.util.Map;

public interface PermissionService {
    /**
     * 检查用户是否有指定权限
     * @param userId 用户ID
     * @param targetPerm 权限枚举
     * @return 是否拥有权限
     */
    boolean hasPermission(Long userId, PermissionOperates targetPerm);

    /**
     * 判断权限是否在权限链表中
     * @param targetPerm 权限枚举
     * @param permissionList 权限列表
     * @return 是否拥有权限
     */
    boolean hasPermission(PermissionOperates targetPerm, List<PermissionsEntity> permissionList);

    /**
     * 获取权限链表
     * @param roleId 角色ID
     * @return
     */
    List<PermissionsEntity> getPermissionList(Long roleId);

    /**
     * 条件查询权限链表
     * @param permissionsEntity 权限实体类
     * @return
     */
     List<PermissionVO> getPermissionList(PermissionsEntity permissionsEntity);

    /**
     * 获取角色权限链表（分组）
     * @return 分组后的权限（主权限 + 子权限）
     */
    PermissionGroupedVO getMyPermissionGrouped();

    /**
     * 根据角色ID查询权限（分组）
     * @param roleId 角色ID
     * @return 分组后的权限（主权限 + 子权限）
     */
    PermissionGroupedVO getByRoleGrouped(Long roleId);

    /**
     * 获取所有权限并按模块分组（分组）
     * @return 按模块分组的权限Map（主权限 + 子权限）
     */
    Map<String, PermissionGroupedVO> getAllPermissionsGroupedByModuleGrouped();

    /**
     * 将权限实体列表分组为主权限和子权限
     * @param permissionList 权限实体列表
     * @return 分组结果
     */
    PermissionGroupedVO groupPermissions(List<PermissionsEntity> permissionList);

    /**
     * 获取角色权限链表
     * @return
     */
    List<PermissionVO> getMyPermission();

    /**
     * 获取所有权限的种类列表
     * @return 所有权限的种类列表
     */
    List<String> getKeyList();

    /**
     * 为角色添加删除权限
     * @param roleANDPermissionDTOS 角色权限DTO列表
     * @return 添加或删除权限成功否
     */
    boolean addOrDeletePermissionsToRole(RoleANDPermissionDTO roleANDPermissionDTOS);

    /**
     * 根据角色ID查询权限
     * @param roleId 角色ID
     * @return 相关权限
     */
    List<PermissionVO> getByRole(Long roleId);

    /**
     * 获取所有权限并按模块分组
     * @return 按模块分组的权限Map
     */
    Map<String, List<PermissionVO>> getAllPermissionsGroupedByModule();

    /**
     * 查询拥有审批权限的用户列表
     * @return 拥有审批权限的用户列表（用户ID和用户名）
     */
    List<AuditorVO> getAuditorList();
}
