package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.service.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据权限解析器实现（轻量只读组件）
 * <p>通过 JdbcTemplate 直接查询数据库，避免与 MyBatis Mapper 产生依赖链</p>
 * <p>专门为 MyDataPermissionHandler 提供只读权限解析能力</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataScopeResolverImpl implements DataScopeResolver {

    private final JdbcTemplate jdbcTemplate;

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
    public List<Long> getTageResourceIds(Long roleId, String resourceType) {
        // 1. 查询角色绑定的标签
        String tageSql = "SELECT tage_id FROM tage_role_binding WHERE role_id = ?";
        List<Long> tageIds = jdbcTemplate.queryForList(tageSql, Long.class, roleId);

        if (tageIds.isEmpty()) {
            log.debug("角色 {} 没有绑定任何标签", roleId);
            return Collections.emptyList();
        }

        // 2. 查询标签绑定的资源
        String placeholders = String.join(",", Collections.nCopies(tageIds.size(), "?"));
        String resourceSql = "SELECT DISTINCT resource_id FROM tage_resource_binding " +
                "WHERE tage_id IN (" + placeholders + ") AND resource_type = ?";

        List<Object> params = new ArrayList<>(tageIds);
        params.add(resourceType);
        List<Long> resourceIds = jdbcTemplate.queryForList(resourceSql, params.toArray(), Long.class);

        log.debug("角色 {} 的标签绑定了 {} 个表 {} 的资源", roleId, resourceIds.size(), resourceType);
        return resourceIds;
    }

    @Override
    public List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType) {
        String sql = "SELECT DISTINCT resource_id FROM data_share " +
                "WHERE (user_id = ? OR (? IS NOT NULL AND role_id = ?)) " +
                "AND resource_type = ?";

        List<Long> resourceIds = jdbcTemplate.queryForList(
                sql, Long.class, userId, roleId, roleId, resourceType);

        log.debug("用户 {} 或角色 {} 被共享了 {} 个表 {} 的资源",
                userId, roleId, resourceIds.size(), resourceType);
        return resourceIds;
    }

    @Override
    public List<Long> getRolePermissionIds(Long roleId) {
        String sql = "SELECT permissions_id FROM role_permissions " +
                "WHERE role_id = ? AND is_deleted = false";
        return jdbcTemplate.queryForList(sql, Long.class, roleId);
    }
}