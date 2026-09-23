package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.UserContext;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * UserContext 契约范围判定协作类（tighten-pmd-residual-325 任务 6.4 批C：拆自 {@link DataScopeServiceImpl}，行为等价）。
 *
 * <p>职责：冻结契约 {@link com.slz.crm.platform.contract.DataScope} 三方法——级别解释、 可见部门集合、单条记录读判定。部门子树解析经
 * {@link DataScopeDepartmentResolver} 委托。 无可变共享状态。
 */
class DataScopeUserScopeGuard {

  private final DataScopeDepartmentResolver departmentResolver;

  DataScopeUserScopeGuard(DataScopeDepartmentResolver departmentResolver) {
    this.departmentResolver = departmentResolver;
  }

  DataScopeLevel levelOf(UserContext user) {
    DataScopeLevel result = DataScopeLevel.NONE;
    if (user != null) {
      if (user.isSuperAdmin()) {
        result = DataScopeLevel.ALL;
      } else {
        result = user.dataScope() == null ? DataScopeLevel.NONE : user.dataScope();
      }
    }
    return result;
  }

  List<Long> visibleDeptIds(UserContext user) {
    List<Long> result = Collections.emptyList();
    if (user != null && user.deptId() != null) {
      DataScopeLevel level = levelOf(user);
      if (level == DataScopeLevel.ALL) {
        // 契约约定 ALL 返回空集合表示“不限制”，调用方不得解释为“无权限”。
        result = Collections.emptyList();
      } else if (level == DataScopeLevel.DEPT) {
        result = List.of(user.deptId());
      } else if (level == DataScopeLevel.DEPT_AND_CHILD) {
        result = departmentResolver.resolveDepartmentSubtreeIds(user.deptId());
      } else {
        result = Collections.emptyList();
      }
    }
    return result;
  }

  boolean canRead(UserContext user, Long ownerId, Long ownerDeptId) {
    boolean result = false;
    if (user != null) {
      DataScopeLevel level = levelOf(user);
      if (level == DataScopeLevel.ALL || Objects.equals(user.userId(), ownerId)) {
        result = true;
      } else if (level == DataScopeLevel.DEPT) {
        result = Objects.equals(user.deptId(), ownerDeptId);
      } else if (level == DataScopeLevel.DEPT_AND_CHILD) {
        result =
            departmentResolver.resolveDepartmentSubtreeIds(user.deptId()).contains(ownerDeptId);
      } else {
        result = false;
      }
    }
    return result;
  }
}
