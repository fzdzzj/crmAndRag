package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import com.slz.crm.server.service.DataScopeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据权限服务实现
 * <p>三级数据权限系统的核心服务</p>
 */
@Slf4j
@Service
public class DataScopeServiceImpl implements DataScopeService {

    @Resource
    private TageRoleBindingMapper tageRoleBindingMapper;
    @Resource
    private TageResourceBindingMapper tageResourceBindingMapper;
    @Resource
    private DataShareMapper dataShareMapper;
    @Resource
    private RolePermissionsMapper rolePermissionsMapper;

    /**
     * 表名到权限枚举的映射
     * <p>key: 表名, value: [ONLY_MY权限, TAGE权限, ALL权限]</p>
     */
    private static final Map<String, PermissionOperates[]> TABLE_PERMISSIONS = Map.of(
            "customer_company", new PermissionOperates[]{
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_ONLY_MY,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_TAGE,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_ALL
            },
            "sales_opportunity", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_TAGE,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ALL
            },
            "contract_order_item", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_ORDER_ONLY_MY,
                    PermissionOperates.SALES_VIEW_ORDER_TAGE,
                    PermissionOperates.SALES_VIEW_ORDER_ALL
            },
            "contract", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_CONTRACT_ONLY_MY,
                    PermissionOperates.SALES_VIEW_CONTRACT_TAGE,
                    PermissionOperates.SALES_VIEW_CONTRACT_ALL
            },
            "business_activity", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ONLY_MY,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_TAGE,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ALL
            },
            "contact_task", new PermissionOperates[]{
                    PermissionOperates.TASK_VIEW_TASK_ONLY_MY,
                    PermissionOperates.TASK_VIEW_TASK_TAGE,
                    PermissionOperates.TASK_VIEW_TASK_ALL
            },
            "sales_stage_approval", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ONLY_MY,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_TAGE,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ALL
            },
            "payment_record", new PermissionOperates[]{
                    PermissionOperates.FINANCE_VIEW_PAYMENT_ONLY_MY,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_TAGE,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_ALL
            },
            "project_file", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_ONLY_MY,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_TAGE,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_ALL
            }
    );

    @Override
    public Integer getHighestDataScopeLevel(RoleAO user, String resourceType) {
        // 超级管理员(roleId=1)拥有查看全部权限
        if (user.getRoleId().equals(1L)) {
            log.debug("用户 {} 是超级管理员,拥有查看全部权限", user.getId());
            return DataScopeLevel.ALL.getCode();
        }

        // 获取该表的权限枚举
        PermissionOperates[] permissions = TABLE_PERMISSIONS.get(resourceType);
        if (permissions == null) {
            // 未配置权限,默认仅查看自己的
            log.debug("表 {} 未配置权限,默认仅查看自己的", resourceType);
            return DataScopeLevel.SELF.getCode();
        }

        // 查询角色拥有的权限ID列表
        List<Long> rolePermissionIds = getRolePermissionIds(user.getRoleId());

        // 按权限级别从高到低检查
        // 1. 检查是否有查看全部权限
        if (rolePermissionIds.contains(permissions[2].getId())) {
            log.debug("用户 {} 拥有表 {} 的查看全部权限", user.getId(), resourceType);
            return DataScopeLevel.ALL.getCode();
        }

        // 2. 检查是否有查看标签权限(必须有明确的TAGE权限才能走第二级)
        if (rolePermissionIds.contains(permissions[1].getId())) {
            log.debug("用户 {} 拥有表 {} 的查看标签权限", user.getId(), resourceType);
            return DataScopeLevel.TAGE.getCode();
        }

        // 3. 默认:仅查看自己的(包含共享资源)
        log.debug("用户 {} 拥有表 {} 的仅查看自己权限", user.getId(), resourceType);
        return DataScopeLevel.SELF.getCode();
    }

    @Override
    public void addDataScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        Integer level = getHighestDataScopeLevel(user, resourceType);
        DataScopeLevel scopeLevel = DataScopeLevel.fromCode(level);

        switch (scopeLevel) {
            case ALL:
                // 不添加任何条件
                log.debug("用户 {} 对表 {} 拥有查看全部权限,不添加条件", user.getId(), resourceType);
                break;

            case TAGE:
                log.debug("用户 {} 对表 {} 拥有查看标签权限", user.getId(), resourceType);
                addTageScopeCondition(wrapper, user, resourceType);
                break;

            case SELF:
            default:
                log.debug("用户 {} 对表 {} 拥有仅查看自己权限", user.getId(), resourceType);
                addSelfScopeCondition(wrapper, user.getId(), resourceType);
                break;
        }
    }

    @Override
    public void addDataScopeCondition(LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        Integer level = getHighestDataScopeLevel(user, resourceType);
        DataScopeLevel scopeLevel = DataScopeLevel.fromCode(level);

        switch (scopeLevel) {
            case ALL:
                log.debug("用户 {} 对表 {} 拥有查看全部权限(Lambda)", user.getId(), resourceType);
                break;
            case TAGE:
                log.debug("用户 {} 对表 {} 拥有查看标签权限(Lambda)", user.getId(), resourceType);
                addTageScopeCondition(wrapper, user, resourceType);
                break;
            case SELF:
            default:
                log.debug("用户 {} 对表 {} 拥有仅查看自己权限(Lambda)", user.getId(), resourceType);
                addSelfScopeCondition(wrapper, user.getId(), resourceType);
                break;
        }
    }

    public void addSelfScopeCondition(LambdaQueryWrapper<?> wrapper, Long userId, String resourceType) {
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);
        List<Long> sharedResourceIds = getSharedResourceIds(userId, null, resourceType);

        wrapper.and(w -> {
            boolean hasCondition = false;

            if (userFields.isEmpty()) {
                appendEqCondition(w, "creator_id", userId, hasCondition);
                hasCondition = true;
            } else {
                for (String userField : userFields) {
                    appendEqCondition(w, userField, userId, hasCondition);
                    hasCondition = true;
                }
            }

            if (!sharedResourceIds.isEmpty()) {
                appendInCondition(w, "id", sharedResourceIds, hasCondition);
            }
        });

        log.debug("添加一级权限条件(Lambda) - 用户字段={}, 共享资源数={}", userFields, sharedResourceIds.size());
    }

    public void addTageScopeCondition(LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), null, resourceType);
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

        wrapper.and(w -> {
            boolean hasCondition = false;

            if (!tageResourceIds.isEmpty()) {
                appendInCondition(w, "id", tageResourceIds, hasCondition);
                hasCondition = true;
            }

            if (userFields.isEmpty()) {
                appendEqCondition(w, "creator_id", user.getId(), hasCondition);
                hasCondition = true;
            } else {
                for (String userField : userFields) {
                    appendEqCondition(w, userField, user.getId(), hasCondition);
                    hasCondition = true;
                }
            }

            if (!sharedResourceIds.isEmpty()) {
                appendInCondition(w, "id", sharedResourceIds, hasCondition);
            }
        });

        log.debug("添加二级权限条件(Lambda) - 标签资源数={}, 用户字段={}, 共享资源数={}",
                tageResourceIds.size(), userFields, sharedResourceIds.size());
    }

    private void appendEqCondition(LambdaQueryWrapper<?> wrapper, String columnName, Long value, boolean appendOr) {
        if (appendOr) {
            wrapper.or();
        }
        wrapper.apply(columnName + " = {0}", value);
    }

    private void appendInCondition(LambdaQueryWrapper<?> wrapper, String columnName, List<Long> values, boolean appendOr) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (appendOr) {
            wrapper.or();
        }
        wrapper.apply(columnName + " IN (" + joinIds(values) + ")");
    }

    private String joinIds(List<Long> ids) {
        return ids.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * 添加一级权限条件
     */
    public void addSelfScopeCondition(QueryWrapper<?> wrapper, Long userId, String resourceType) {
        // 第一级权限:查看自己的 + 共享资源

        // 1. 获取需要检查的用户字段
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

        // 2. 查询共享资源ID(只查询直接共享给用户的,不包含角色共享)
        List<Long> sharedResourceIds = getSharedResourceIds(userId, null, resourceType);

        // 3. 构建 OR 条件: (用户字段) OR (共享资源)
        wrapper.and(w -> {
            // 添加用户字段条件(查看自己的)
            if (userFields.isEmpty()) {
                w.eq("creator_id", userId);
            } else {
                for (int i = 0; i < userFields.size(); i++) {
                    if (i == 0) {
                        w.eq(userFields.get(i), userId);
                    } else {
                        w.or().eq(userFields.get(i), userId);
                    }
                }
            }

            // 添加共享资源条件
            if (!sharedResourceIds.isEmpty()) {
                w.or().in("id", sharedResourceIds);
            }
        });

        log.debug("添加第一级权限条件 - 用户字段={}, 共享资源数={}", userFields, sharedResourceIds.size());
    }

    /**
     * 添加二级权限条件
     */
    public void addTageScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        // 第二级权限:标签资源 + 查看自己的 + 共享资源

        // 1. 查询标签资源ID
        List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);

        // 2. 查询共享资源ID(只查询直接共享给用户的)
        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), null, resourceType);

        // 3. 获取需要检查的用户字段
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

        // 4. 构建 OR 条件: (标签资源) OR (用户字段) OR (共享资源)
        wrapper.and(w -> {
            // 添加标签资源条件
            if (!tageResourceIds.isEmpty()) {
                w.in("id", tageResourceIds);
            }

            // 添加用户字段条件(查看自己的)
            if (userFields.isEmpty()) {
                w.or().eq("creator_id", user.getId());
            } else {
                for (String userField : userFields) {
                    w.or().eq(userField, user.getId());
                }
            }

            // 添加共享资源条件
            if (!sharedResourceIds.isEmpty()) {
                w.or().in("id", sharedResourceIds);
            }
        });

        log.debug("添加第二级权限条件 - 标签资源数={}, 用户字段={}, 共享资源数={}",
                   tageResourceIds.size(), userFields, sharedResourceIds.size());
    }

    @Override
    public List<Long> getTageResourceIds(Long roleId, String resourceType) {
        // 1. 查询角色绑定的标签
        List<Long> tageIds = tageRoleBindingMapper.selectTageIdsByRoleId(roleId);

        if (tageIds == null || tageIds.isEmpty()) {
            log.debug("角色 {} 没有绑定任何标签", roleId);
            return Collections.emptyList();
        }

        // 2. 查询标签绑定的资源
        List<Long> resourceIds = tageResourceBindingMapper.selectResourceIdsByTageIds(tageIds, resourceType);
        log.debug("角色 {} 的标签绑定了 {} 个表 {} 的资源", roleId, resourceIds.size(), resourceType);

        return resourceIds;
    }

    @Override
    public List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType) {
        List<Long> resourceIds = dataShareMapper.selectSharedResourceIds(userId, roleId, resourceType);
        log.debug("用户 {} 或角色 {} 被共享了 {} 个表 {} 的资源", userId, roleId, resourceIds.size(), resourceType);
        return resourceIds;
    }

    @Override
    public boolean canReadResource(RoleAO user, String resourceType, Long resourceId, Long... relatedUserIds) {
        if (user == null || user.getId() == null || user.getRoleId() == null || resourceId == null) {
            return false;
        }

        DataScopeLevel level = DataScopeLevel.fromCode(getHighestDataScopeLevel(user, resourceType));
        if (level == DataScopeLevel.ALL) {
            return true;
        }

        if (relatedUserIds != null) {
            for (Long relatedUserId : relatedUserIds) {
                if (Objects.equals(user.getId(), relatedUserId)) {
                    return true;
                }
            }
        }

        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), null, resourceType);
        if (sharedResourceIds != null && sharedResourceIds.contains(resourceId)) {
            return true;
        }

        return level == DataScopeLevel.TAGE
                && getTageResourceIds(user.getRoleId(), resourceType).contains(resourceId);
    }

    /**
     * 查询角色拥有的权限ID列表
     *
     * @param roleId 角色ID
     * @return 权限ID列表
     */
    private List<Long> getRolePermissionIds(Long roleId) {
        List<RolePermissionsEntity> rolePermissions = rolePermissionsMapper.selectList(
                new LambdaQueryWrapper<RolePermissionsEntity>()
                        .eq(RolePermissionsEntity::getRoleId, roleId)
                        .eq(RolePermissionsEntity::getIsDeleted, false)
        );

        List<Long> permissionIds = new ArrayList<>();
        for (RolePermissionsEntity rp : rolePermissions) {
            if (rp.getPermissionsId() != null) {
                permissionIds.add(rp.getPermissionsId());
            }
        }

        return permissionIds;
    }
}
