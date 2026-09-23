package com.slz.crm.server.service.impl;

import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 标签/共享资源查询协作类（tighten-pmd-residual-325 任务 6.4 批C：拆自 {@link DataScopeServiceImpl}，行为等价）。
 *
 * <p>职责：角色标签绑定资源与用户/角色显式共享资源的查询。无可变共享状态。
 */
@Slf4j
class DataScopeShareQuery {

  private final TageRoleBindingMapper tageRoleBindingMapper;
  private final TageResourceBindingMapper tageResourceBindingMapper;
  private final DataShareMapper dataShareMapper;

  DataScopeShareQuery(
      TageRoleBindingMapper tageRoleBindingMapper,
      TageResourceBindingMapper tageResourceBindingMapper,
      DataShareMapper dataShareMapper) {
    this.tageRoleBindingMapper = tageRoleBindingMapper;
    this.tageResourceBindingMapper = tageResourceBindingMapper;
    this.dataShareMapper = dataShareMapper;
  }

  List<Long> getTageResourceIds(Long roleId, String resourceType) {
    List<Long> result = Collections.emptyList();
    // 1. 查询角色绑定的标签
    List<Long> tageIds = tageRoleBindingMapper.selectTageIdsByRoleId(roleId);

    if (tageIds == null || tageIds.isEmpty()) {
      log.debug("角色 {} 没有绑定任何标签", roleId);
      result = Collections.emptyList();
    } else {
      // 2. 查询标签绑定的资源
      List<Long> resourceIds =
          tageResourceBindingMapper.selectResourceIdsByTageIds(tageIds, resourceType);
      log.debug("角色 {} 的标签绑定了 {} 个表 {} 的资源", roleId, resourceIds.size(), resourceType);
      result = resourceIds;
    }
    return result;
  }

  List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType) {
    List<Long> resourceIds = dataShareMapper.selectSharedResourceIds(userId, roleId, resourceType);
    log.debug("用户 {} 或角色 {} 被共享了 {} 个表 {} 的资源", userId, roleId, resourceIds.size(), resourceType);
    return resourceIds;
  }
}
