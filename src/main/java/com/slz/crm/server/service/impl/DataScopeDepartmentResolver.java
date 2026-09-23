package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 部门维度解析协作类（tighten-pmd-residual-325 任务 6.4 批C：拆自 {@link DataScopeServiceImpl}， 行为等价）。
 *
 * <p>职责：部门负责人判定、部门子树递归解析（含 parentId 环防御）、部门范围用户集合解析。 无可变共享状态，可安全并发调用。
 */
class DataScopeDepartmentResolver {

  private final SysDeptMapper sysDeptMapper;
  private final UserMapper userMapper;

  DataScopeDepartmentResolver(SysDeptMapper sysDeptMapper, UserMapper userMapper) {
    this.sysDeptMapper = sysDeptMapper;
    this.userMapper = userMapper;
  }

  /**
   * 判断当前用户是否为其所属部门的负责人。
   *
   * @param user 当前用户
   * @return true 表示 {@code sys_dept.leaderId} 等于当前用户 ID
   */
  boolean isDepartmentLeader(RoleAO user) {
    boolean result = false;
    if (user.getId() != null && user.getDeptId() != null) {
      SysDeptEntity dept = sysDeptMapper.selectById(user.getDeptId());
      result = dept != null && Objects.equals(dept.getLeaderId(), user.getId());
    }
    return result;
  }

  /**
   * 递归解析部门子树，并用已访问集合防御脏数据造成的 parentId 循环。
   *
   * @param rootDeptId 根部门 ID
   * @return 根部门及其所有直接/间接子部门 ID；入参为空时返回空集合
   */
  List<Long> resolveDepartmentSubtreeIds(Long rootDeptId) {
    List<Long> result = Collections.emptyList();
    if (rootDeptId != null) {
      Set<Long> visited = new LinkedHashSet<>();
      Deque<Long> pending = new ArrayDeque<>();
      pending.add(rootDeptId);
      while (!pending.isEmpty()) {
        Long deptId = pending.poll();
        // visited 同时承担“结果集合”和“环检测”；重复 parentId 只会被展开一次。
        if (deptId == null || !visited.add(deptId)) {
          continue;
        }
        List<SysDeptEntity> children =
            sysDeptMapper.selectList(
                new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getParentId, deptId));
        if (children == null) {
          continue;
        }
        for (SysDeptEntity child : children) {
          if (child != null && child.getId() != null && !visited.contains(child.getId())) {
            pending.add(child.getId());
          }
        }
      }
      result = new ArrayList<>(visited);
    }
    return result;
  }

  /**
   * 解析部门范围内的用户 ID 集合。
   *
   * @param user 当前用户
   * @param includeChildren true 表示包含部门子树
   * @return 当前用户 ID 加上范围内的用户 ID；至少包含当前用户，保证不会收窄 SELF 可见性
   */
  List<Long> getSubordinateUserIds(RoleAO user, boolean includeChildren) {
    List<Long> result = Collections.emptyList();
    if (user != null && user.getId() != null) {
      Set<Long> userIds = new LinkedHashSet<>();
      userIds.add(user.getId());

      if (user.getDeptId() != null) {
        Set<Long> deptIds =
            includeChildren
                ? new LinkedHashSet<>(resolveDepartmentSubtreeIds(user.getDeptId()))
                : Set.of(user.getDeptId());

        List<UserEntity> users =
            userMapper.selectList(
                new LambdaQueryWrapper<UserEntity>().in(UserEntity::getDeptId, deptIds));
        if (users != null) {
          for (UserEntity departmentUser : users) {
            if (departmentUser != null && departmentUser.getId() != null) {
              userIds.add(departmentUser.getId());
            }
          }
        }
      }
      result = new ArrayList<>(userIds);
    }
    return result;
  }
}
