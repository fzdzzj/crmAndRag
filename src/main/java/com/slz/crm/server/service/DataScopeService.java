package com.slz.crm.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.pojo.ao.RoleAO;

import java.util.List;

/**
 * 数据权限服务
 * <p>三级数据权限系统的核心服务接口</p>
 */
public interface DataScopeService {

    /**
     * 获取用户在指定资源类型上的最高数据权限级别
     * <p>权限级别: 查看全部(3) > 查看标签(2) > 仅查看自己的(1)</p>
     *
     * @param user         当前用户
     * @param resourceType 资源类型(表名)
     * @return 权限级别: 1-仅查看自己的, 2-查看标签的, 3-查看全部的
     */
    Integer getHighestDataScopeLevel(RoleAO user, String resourceType);

    /**
     * 为 QueryWrapper 添加数据权限条件
     * <p>根据用户的权限级别动态添加 WHERE 条件</p>
     *
     * @param wrapper      QueryWrapper 对象
     * @param user         当前用户
     * @param resourceType 资源类型(表名)
     */
    void addDataScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType);

    /**
     * 为 Wrapper（包含LambdaQueryWrapper）添加数据权限条件
     * <p>根据用户的权限级别动态添加 WHERE 条件</p>
     *
     * @param wrapper      LambdaQueryWrapper<?> 对象（基类）
     * @param user         当前用户
     * @param resourceType 资源类型(表名)
     */
    void addDataScopeCondition(LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType);

    /**
     * 查询标签绑定的资源ID列表
     * <p>通过角色ID查询绑定的标签,再查询这些标签绑定的资源</p>
     *
     * @param roleId       角色ID
     * @param resourceType 资源类型(表名)
     * @return 资源ID列表
     */
    List<Long> getTageResourceIds(Long roleId, String resourceType);

    /**
     * 查询共享的资源ID列表
     * <p>查询直接共享给用户和共享给用户角色的资源</p>
     *
     * @param userId       用户ID
     * @param roleId       角色ID
     * @param resourceType 资源类型(表名)
     * @return 资源ID列表
     */
    List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType);

    /**
     * 判断单条资源是否位于当前用户的正常数据范围内。
     *
     * 该方法与列表查询的 ALL / TAGE / SELF 规则保持一致，用于按ID查询详情时
     * 补足 QueryWrapperAspect 无法覆盖 selectById() 的对象级授权。
     *
     * @param user           当前用户
     * @param resourceType   资源表名
     * @param resourceId     资源ID
     * @param relatedUserIds 该资源按表配置参与 SELF 判断的用户ID字段值
     * @return 是否允许读取
     */
    boolean canReadResource(RoleAO user, String resourceType, Long resourceId, Long... relatedUserIds);
}
