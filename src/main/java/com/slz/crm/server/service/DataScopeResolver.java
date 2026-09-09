package com.slz.crm.server.service;

import com.slz.crm.pojo.ao.RoleAO;

import java.util.List;

/**
 * 数据权限解析器（轻量只读组件）
 * <p>专门为 MyDataPermissionHandler 提供只读权限解析能力</p>
 * <p>不依赖 Mapper 层，通过 JDBC 直接查询，避免与 MyBatis 初始化链路产生循环依赖</p>
 */
public interface DataScopeResolver {

    /**
     * 获取用户对指定资源类型的最高权限级别
     *
     * @param user         用户角色信息
     * @param resourceType 资源类型（表名）
     * @return 权限级别：1=仅自己，2=标签，3=全部
     */
    Integer getHighestDataScopeLevel(RoleAO user, String resourceType);

    /**
     * 获取角色绑定的标签资源ID列表
     *
     * @param roleId       角色ID
     * @param resourceType 资源类型（表名）
     * @return 资源ID列表
     */
    List<Long> getTageResourceIds(Long roleId, String resourceType);

    /**
     * 获取共享给用户/角色的资源ID列表
     *
     * @param userId       用户ID
     * @param roleId       角色ID（可为null）
     * @param resourceType 资源类型（表名）
     * @return 资源ID列表
     */
    List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType);

    /**
     * 获取角色拥有的权限ID列表
     *
     * @param roleId 角色ID
     * @return 权限ID列表
     */
    List<Long> getRolePermissionIds(Long roleId);
}