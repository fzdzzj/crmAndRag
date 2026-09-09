package com.slz.crm.pojo.ao;

import com.slz.crm.pojo.entity.PermissionsEntity;
import lombok.Data;

import java.util.List;

/**
 * 角色授权对象
 * 用于存储当前用户的角色信息和权限列表
 * 在权限拦截器中自动填充权限数据
 */
@Data
public class RoleAO {
    /**
     * 用户ID
     */
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 所属部门ID
     * <p>用于“本部门 / 本部门及以下”数据范围解析；由权限拦截器在请求线程内填充。</p>
     */
    private Long deptId;

    /**
     * 角色拥有的��限列表
     * 在权限拦截器中自动加载并填充
     */
    private List<PermissionsEntity> permissions;

    /**
     * 检查是否拥有指定权限
     *
     * @param permissionId 权限ID
     * @return 是否拥有权限
     */
    public boolean hasPermission(Long permissionId) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        return permissions.stream()
                .anyMatch(permission -> permission.getId().equals(permissionId));
    }

    /**
     * 检查是否拥有指定权限(通过权限枚举)
     *
     * @param permissionOperates 权限枚举
     * @return 是否拥有权限
     */
    public boolean hasPermission(com.slz.crm.common.enumeration.PermissionOperates permissionOperates) {
        if (permissionOperates == null) {
            return false;
        }
        return hasPermission(permissionOperates.getId());
    }
}
