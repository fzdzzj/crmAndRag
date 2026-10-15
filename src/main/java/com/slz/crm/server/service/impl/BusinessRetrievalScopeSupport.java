package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.service.DataScopeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * 业务检索数据权限档位协作类（add-business-activity-semantic-retrieval 任务 3.1；从 {@link BusinessRetrievalService}
 * 拆出，PMD NcssCount 阈值）。
 *
 * <p>职责三件：① 按资源类型解析档位（unknown/NONE 收敛为 NONE，零外发最保守）；② 主 filter 之外的**档位复核**——SELF/TAGE 档逐命中按落库
 * payload 复判归属，向量库 filter 语义漂移时不致越权放大；③ 补充召回授权清单（TAGE/共享）装配。
 *
 * <p>复核口径：ownerUserId=本人 放行；ownerDeptId=本部门且该实体开启补充召回 放行；resourceId 命中授权清单 放行；DEPT / DEPT_AND_CHILD
 * 以 ownerDeptId 子树检索自行收口、ALL 档超管直通，两档不做二次复核（避免额外外呼）。无可变状态。
 */
@RequiredArgsConstructor
class BusinessRetrievalScopeSupport {

  /** 主 filter 的 owner 维度键（B4 冻结 payload 键）。 */
  private static final String OWNER_USER_ID_KEY = "ownerUserId";

  /** 主 filter 的部门维度键（B4 冻结 payload 键）。 */
  private static final String OWNER_DEPT_ID_KEY = "ownerDeptId";

  /** 补充召回逐 id 过滤用的 payload 键。 */
  private static final String RESOURCE_ID_KEY = "resourceId";

  private final DataScopeService dataScopeService;

  /** 解析用户在指定资源类型上的数据范围档位（未知码值按 NONE 收敛）。 */
  DataScopeLevel resolveLevel(UserContext user, String resourceType) {
    return DataScopeLevel.fromCode(
        dataScopeService.getHighestDataScopeLevel(toRoleAO(user), resourceType));
  }

  /** 档位是否非 NONE（任一方非 NONE 即可外发）。 */
  boolean isScoped(DataScopeLevel level) {
    return level != DataScopeLevel.NONE;
  }

  /** 该档位是否需要 owner 归属复核（SELF/TAGE 以 ownerUserId 承接主过滤）。 */
  boolean needsOwnerPostFilter(DataScopeLevel level) {
    return level == DataScopeLevel.SELF || level == DataScopeLevel.TAGE;
  }

  /** 该档位是否开启 TAGE 补充召回（对齐页面列表 SQL 超集不变量）。 */
  boolean hasTageSupplement(DataScopeLevel level) {
    return level == DataScopeLevel.TAGE
        || level == DataScopeLevel.DEPT
        || level == DataScopeLevel.DEPT_AND_CHILD;
  }

  /**
   * 档位复核：仅当任一面处于 SELF/TAGE 档时逐命中复判（其余档零过滤、零额外外呼）。
   *
   * @param hits 主检索 + 补充召回 union 结果
   * @param user 当前用户身份快照
   * @param entityLevels 各实体的（资源类型, 档位）对，用于装配复核所需的授权清单
   * @return 复核后的命中
   */
  List<VectorSearchHit> applyPostFilter(
      List<VectorSearchHit> hits, UserContext user, Map<String, DataScopeLevel> entityLevels) {
    List<VectorSearchHit> result = hits;
    if (!hits.isEmpty() && needsAnyOwnerPostFilter(entityLevels)) {
      List<Long> granted = grantedResourceIds(user, entityLevels);
      result =
          hits.stream()
              .filter(hit -> hit != null && hit.metadata() != null)
              .filter(hit -> isOwnedBy(hit, user) || isGrantedResource(hit, granted))
              .toList();
    }
    return result;
  }

  private boolean needsAnyOwnerPostFilter(Map<String, DataScopeLevel> entityLevels) {
    boolean needed = false;
    for (DataScopeLevel level : entityLevels.values()) {
      if (needsOwnerPostFilter(level)) {
        needed = true;
        break;
      }
    }
    return needed;
  }

  /** 补充召回授权清单（共享召回对全部非 ALL 档恒开；TAGE 召回仅 TAGE/DEPT/DEPT_AND_CHILD 档存在）。 */
  private List<Long> grantedResourceIds(
      UserContext user, Map<String, DataScopeLevel> entityLevels) {
    List<Long> granted = new ArrayList<>();
    boolean tageNeeded = false;
    for (Map.Entry<String, DataScopeLevel> entry : entityLevels.entrySet()) {
      if (entry.getValue() != DataScopeLevel.ALL) {
        granted.addAll(
            nullSafe(
                dataScopeService.getSharedResourceIds(
                    user.userId(), user.roleId(), entry.getKey())));
      }
      tageNeeded = tageNeeded || hasTageSupplement(entry.getValue());
    }
    if (tageNeeded) {
      for (String resourceType : entityLevels.keySet()) {
        granted.addAll(nullSafe(dataScopeService.getTageResourceIds(user.roleId(), resourceType)));
      }
    }
    return granted;
  }

  /** 归属复核：ownerUserId=本人 或 ownerDeptId=本部门（两键皆缺或不等即无权）。 */
  private boolean isOwnedBy(VectorSearchHit hit, UserContext user) {
    Map<String, Object> metadata = hit.metadata();
    boolean owned = false;
    if (user.userId() != null && user.userId().equals(asLong(metadata.get(OWNER_USER_ID_KEY)))) {
      owned = true;
    } else if (user.deptId() != null
        && user.deptId().equals(asLong(metadata.get(OWNER_DEPT_ID_KEY)))) {
      owned = true;
    }
    return owned;
  }

  /** 显式授权复核：命中 resourceId 位于 TAGE/共享授权清单内。 */
  private boolean isGrantedResource(VectorSearchHit hit, List<Long> grantedResourceIds) {
    Long resourceId = asLong(hit.metadata().get(RESOURCE_ID_KEY));
    return resourceId != null && grantedResourceIds.contains(resourceId);
  }

  /** payload 数值兼容转换（Qdrant 侧 Long/Integer 均可能回来）。 */
  private Long asLong(Object value) {
    Long result = null;
    if (value instanceof Number number) {
      result = number.longValue();
    } else if (value != null) {
      try {
        result = Long.valueOf(String.valueOf(value));
      } catch (NumberFormatException ignored) {
        result = null;
      }
    }
    return result;
  }

  private List<Long> nullSafe(List<Long> values) {
    return values == null ? List.of() : values;
  }

  /** 身份快照转数据权限判权入参（getHighestDataScopeLevel 只消费 id/roleId/deptId 三元组）。 */
  private RoleAO toRoleAO(UserContext user) {
    RoleAO role = new RoleAO();
    role.setId(user.userId());
    role.setRoleId(user.roleId());
    role.setDeptId(user.deptId());
    return role;
  }
}
