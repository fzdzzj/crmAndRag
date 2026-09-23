package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据范围级别解析协作类（tighten-pmd-residual-325 任务 6.4 批C：拆自 {@link DataScopeServiceImpl}，行为等价）。
 *
 * <p>职责：按冻结契约优先级（ALL &gt; DEPT_AND_CHILD &gt; DEPT &gt; 负责人默认 &gt; TAGE &gt; SELF）
 * 解析角色对某张表的最宽数据范围。部门负责人判定经 {@link DataScopeDepartmentResolver} 委托。
 */
@Slf4j
class DataScopeLevelResolver {

  private final RolePermissionsMapper rolePermissionsMapper;
  private final DataScopeDepartmentResolver departmentResolver;
  private final DataScopeShareQuery shareQuery;

  DataScopeLevelResolver(
      RolePermissionsMapper rolePermissionsMapper,
      DataScopeDepartmentResolver departmentResolver,
      DataScopeShareQuery shareQuery) {
    this.rolePermissionsMapper = rolePermissionsMapper;
    this.departmentResolver = departmentResolver;
    this.shareQuery = shareQuery;
  }

  Integer getHighestDataScopeLevel(RoleAO user, String resourceType) {
    Integer result = DataScopeLevel.SELF.getCode();
    if (user == null || user.getRoleId() == null) {
      // 未配置权限,默认仅查看自己的
      log.debug("用户 {} 角色缺失,默认仅查看自己的", user == null ? null : user.getId());
      result = DataScopeLevel.SELF.getCode();
    } else if (user.getRoleId().equals(1L)) {
      // 超级管理员(roleId=1)拥有查看全部权限
      log.debug("用户 {} 是超级管理员,拥有查看全部权限", user.getId());
      result = DataScopeLevel.ALL.getCode();
    } else {
      // 获取该表的权限枚举
      PermissionOperates[] permissions = DataScopeTablePermissions.permissionsFor(resourceType);
      if (permissions == null) {
        // 未配置权限,默认仅查看自己的
        log.debug("表 {} 未配置权限,默认仅查看自己的", resourceType);
        result = DataScopeLevel.SELF.getCode();
      } else {
        // 查询角色拥有的权限ID列表后按冻结契约优先级解析
        result =
            resolveConfiguredLevel(
                user, resourceType, permissions, getRolePermissionIds(user.getRoleId()));
      }
    }
    return result;
  }

  /** 按冻结契约的优先级解析已配置权限表的级别。负责人默认获得本部门及以下权限， 只作用于本部门，避免跨部门负责人扩大范围。 */
  private Integer resolveConfiguredLevel(
      RoleAO user,
      String resourceType,
      PermissionOperates[] permissions,
      List<Long> rolePermissionIds) {
    Integer result = DataScopeLevel.SELF.getCode();
    if (rolePermissionIds.contains(permissions[DataScopeTablePermissions.PERMISSION_ALL].getId())) {
      log.debug("用户 {} 拥有表 {} 的查看全部权限", user.getId(), resourceType);
      result = DataScopeLevel.ALL.getCode();
    } else if (rolePermissionIds.contains(
        permissions[DataScopeTablePermissions.PERMISSION_DEPT_AND_CHILD].getId())) {
      log.debug("用户 {} 拥有表 {} 的本部门及以下权限", user.getId(), resourceType);
      result = DataScopeLevel.DEPT_AND_CHILD.getCode();
    } else if (rolePermissionIds.contains(
        permissions[DataScopeTablePermissions.PERMISSION_DEPT].getId())) {
      log.debug("用户 {} 拥有表 {} 的本部门权限", user.getId(), resourceType);
      result = DataScopeLevel.DEPT.getCode();
    } else if (departmentResolver.isDepartmentLeader(user)) {
      log.debug("用户 {} 是部门 {} 的负责人,默认拥有本部门及以下权限", user.getId(), user.getDeptId());
      result = DataScopeLevel.DEPT_AND_CHILD.getCode();
    } else if (rolePermissionIds.contains(
        permissions[DataScopeTablePermissions.PERMISSION_TAGE].getId())) {
      log.debug("用户 {} 拥有表 {} 的查看标签权限", user.getId(), resourceType);
      result = DataScopeLevel.TAGE.getCode();
    } else {
      // 默认:仅查看自己的(包含共享资源)
      log.debug("用户 {} 拥有表 {} 的仅查看自己权限", user.getId(), resourceType);
      result = DataScopeLevel.SELF.getCode();
    }
    return result;
  }

  /** 查询角色拥有的权限ID列表（未删除记录）。 */
  private List<Long> getRolePermissionIds(Long roleId) {
    List<RolePermissionsEntity> rolePermissions =
        rolePermissionsMapper.selectList(
            new LambdaQueryWrapper<RolePermissionsEntity>()
                .eq(RolePermissionsEntity::getRoleId, roleId)
                .eq(RolePermissionsEntity::getIsDeleted, false));

    List<Long> permissionIds = new ArrayList<>();
    for (RolePermissionsEntity rp : rolePermissions) {
      if (rp.getPermissionsId() != null) {
        permissionIds.add(rp.getPermissionsId());
      }
    }

    return permissionIds;
  }

  /**
   * 单条资源读判定（tighten-pmd-residual-325 任务 6.4 批C：拆自 DataScopeServiceImpl.canReadResource，
   * 行为等价）。评估顺序与拆分前一致：本人相关 → 部门下属相关 → 显式共享 → 标签绑定。
   */
  boolean canReadResource(
      RoleAO user, String resourceType, Long resourceId, Long... relatedUserIds) {
    boolean result = false;
    if (user != null && user.getId() != null && user.getRoleId() != null && resourceId != null) {
      DataScopeLevel level = DataScopeLevel.fromCode(getHighestDataScopeLevel(user, resourceType));
      if (level == DataScopeLevel.ALL) {
        result = true;
      } else {
        if (relatedUserMatches(user.getId(), relatedUserIds)) {
          result = true;
        }
        if (!result && (level == DataScopeLevel.DEPT || level == DataScopeLevel.DEPT_AND_CHILD)) {
          List<Long> subordinateUserIds =
              departmentResolver.getSubordinateUserIds(
                  user, level == DataScopeLevel.DEPT_AND_CHILD);
          result =
              relatedUserIds != null
                  && Arrays.stream(relatedUserIds).anyMatch(subordinateUserIds::contains);
        }
        if (!result) {
          List<Long> sharedResourceIds =
              shareQuery.getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
          if (sharedResourceIds != null && sharedResourceIds.contains(resourceId)) {
            result = true;
          } else if (level == DataScopeLevel.TAGE) {
            result =
                shareQuery.getTageResourceIds(user.getRoleId(), resourceType).contains(resourceId);
          }
        }
      }
    }
    return result;
  }

  /** 本人相关判定：relatedUserIds 中任一元素等于当前用户即命中。 */
  private boolean relatedUserMatches(Long userId, Long... relatedUserIds) {
    return relatedUserIds != null
        && Arrays.stream(relatedUserIds)
            .anyMatch(relatedUserId -> Objects.equals(userId, relatedUserId));
  }
}
