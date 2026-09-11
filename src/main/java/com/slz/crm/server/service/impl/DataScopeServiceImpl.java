package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import com.slz.crm.server.service.DataScopeService;
import com.slz.crm.platform.contract.DataScope;
import com.slz.crm.platform.contract.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限服务实现
 * <p>多级数据权限系统的核心服务，同时实现冻结契约 {@link DataScope}。</p>
 * <p>线程安全：本类无可变共享状态，所有请求状态都在方法栈内，可安全被并发调用。</p>
 */
@Slf4j
@Service
public class DataScopeServiceImpl implements DataScopeService, DataScope {

    @Resource
    private TageRoleBindingMapper tageRoleBindingMapper;
    @Resource
    private TageResourceBindingMapper tageResourceBindingMapper;
    @Resource
    private DataShareMapper dataShareMapper;
    @Resource
    private RolePermissionsMapper rolePermissionsMapper;
    @Resource
    private SysDeptMapper sysDeptMapper;
    @Resource
    private UserMapper userMapper;

    /** TABLE_PERMISSIONS 中“仅本人”权限的下标。 */
    private static final int PERMISSION_SELF = 0;
    /** TABLE_PERMISSIONS 中“标签”权限的下标。 */
    private static final int PERMISSION_TAGE = 1;
    /** TABLE_PERMISSIONS 中“全部”权限的下标。 */
    private static final int PERMISSION_ALL = 2;
    /** TABLE_PERMISSIONS 中“本部门”权限的下标。 */
    private static final int PERMISSION_DEPT = 3;
    /** TABLE_PERMISSIONS 中“本部门及以下”权限的下标。 */
    private static final int PERMISSION_DEPT_AND_CHILD = 4;

    /**
     * 表名到权限枚举的映射
     * <p>key: 表名, value: [ONLY_MY权限, TAGE权限, ALL权限, DEPT权限, DEPT_AND_CHILD权限]</p>
     */
    private static final Map<String, PermissionOperates[]> TABLE_PERMISSIONS = Map.of(
            "customer_company", new PermissionOperates[]{
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_ONLY_MY,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_TAGE,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_ALL,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_DEPT,
                    PermissionOperates.CUSTOMER_VIEW_COMPANY_DEPT_AND_SUB
            },
            "sales_opportunity", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_TAGE,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ALL,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT_AND_SUB
            },
            "contract_order_item", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_ORDER_ONLY_MY,
                    PermissionOperates.SALES_VIEW_ORDER_TAGE,
                    PermissionOperates.SALES_VIEW_ORDER_ALL,
                    PermissionOperates.SALES_VIEW_ORDER_DEPT,
                    PermissionOperates.SALES_VIEW_ORDER_DEPT_AND_SUB
            },
            "contract", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_CONTRACT_ONLY_MY,
                    PermissionOperates.SALES_VIEW_CONTRACT_TAGE,
                    PermissionOperates.SALES_VIEW_CONTRACT_ALL,
                    PermissionOperates.SALES_VIEW_CONTRACT_DEPT,
                    PermissionOperates.SALES_VIEW_CONTRACT_DEPT_AND_SUB
            },
            "business_activity", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ONLY_MY,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_TAGE,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_ALL,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_DEPT,
                    PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY_DEPT_AND_SUB
            },
            "contact_task", new PermissionOperates[]{
                    PermissionOperates.TASK_VIEW_TASK_ONLY_MY,
                    PermissionOperates.TASK_VIEW_TASK_TAGE,
                    PermissionOperates.TASK_VIEW_TASK_ALL,
                    PermissionOperates.TASK_VIEW_TASK_DEPT,
                    PermissionOperates.TASK_VIEW_TASK_DEPT_AND_SUB
            },
            "sales_stage_approval", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ONLY_MY,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_TAGE,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_ALL,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT,
                    PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT_AND_SUB
            },
            "payment_record", new PermissionOperates[]{
                    PermissionOperates.FINANCE_VIEW_PAYMENT_ONLY_MY,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_TAGE,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_ALL,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_DEPT,
                    PermissionOperates.FINANCE_VIEW_PAYMENT_DEPT_AND_SUB
            },
            "project_file", new PermissionOperates[]{
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_ONLY_MY,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_TAGE,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_ALL,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_DEPT,
                    PermissionOperates.SALES_VIEW_PROJECT_FILE_DEPT_AND_SUB
            }
    );

    @Override
    public Integer getHighestDataScopeLevel(RoleAO user, String resourceType) {
        if (user == null || user.getRoleId() == null) {
            return DataScopeLevel.SELF.getCode();
        }

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

        // 按冻结契约的优先级检查：ALL > DEPT_AND_CHILD > DEPT > 负责人默认 > TAGE > SELF
        if (rolePermissionIds.contains(permissions[PERMISSION_ALL].getId())) {
            log.debug("用户 {} 拥有表 {} 的查看全部权限", user.getId(), resourceType);
            return DataScopeLevel.ALL.getCode();
        }

        if (rolePermissionIds.contains(permissions[PERMISSION_DEPT_AND_CHILD].getId())) {
            log.debug("用户 {} 拥有表 {} 的本部门及以下权限", user.getId(), resourceType);
            return DataScopeLevel.DEPT_AND_CHILD.getCode();
        }

        if (rolePermissionIds.contains(permissions[PERMISSION_DEPT].getId())) {
            log.debug("用户 {} 拥有表 {} 的本部门权限", user.getId(), resourceType);
            return DataScopeLevel.DEPT.getCode();
        }

        // 负责人默认获得本部门及以下权限；只作用于本部门，避免跨部门负责人扩大范围。
        if (isDepartmentLeader(user)) {
            log.debug("用户 {} 是部门 {} 的负责人,默认拥有本部门及以下权限", user.getId(), user.getDeptId());
            return DataScopeLevel.DEPT_AND_CHILD.getCode();
        }

        if (rolePermissionIds.contains(permissions[PERMISSION_TAGE].getId())) {
            log.debug("用户 {} 拥有表 {} 的查看标签权限", user.getId(), resourceType);
            return DataScopeLevel.TAGE.getCode();
        }

        // 默认:仅查看自己的(包含共享资源)
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

            case DEPT:
                log.debug("用户 {} 对表 {} 拥有本部门权限", user.getId(), resourceType);
                addDeptScopeCondition(wrapper, user, resourceType);
                break;

            case DEPT_AND_CHILD:
                log.debug("用户 {} 对表 {} 拥有本部门及以下权限", user.getId(), resourceType);
                addDeptAndChildScopeCondition(wrapper, user, resourceType);
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
            case DEPT:
                log.debug("用户 {} 对表 {} 拥有本部门权限(Lambda)", user.getId(), resourceType);
                addDeptScopeCondition(wrapper, user, resourceType, false);
                break;
            case DEPT_AND_CHILD:
                log.debug("用户 {} 对表 {} 拥有本部门及以下权限(Lambda)", user.getId(), resourceType);
                addDeptScopeCondition(wrapper, user, resourceType, true);
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

    /**
     * 为字符串列名的 {@link QueryWrapper} 注入本部门范围。
     *
     * @param wrapper      原生 QueryWrapper
     * @param user         当前用户
     * @param resourceType 受控资源表名
     */
    public void addDeptScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        appendDeptScopeCondition(wrapper, user, resourceType, false);
    }

    /**
     * 为字符串列名的 {@link QueryWrapper} 注入本部门及以下范围。
     *
     * @param wrapper      原生 QueryWrapper
     * @param user         当前用户
     * @param resourceType 受控资源表名
     */
    public void addDeptAndChildScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
        appendDeptScopeCondition(wrapper, user, resourceType, true);
    }

    /**
     * 为 LambdaWrapper 注入本部门/本部门及以下范围。
     *
     * @param wrapper         LambdaQueryWrapper
     * @param user            当前用户
     * @param resourceType    受控资源表名
     * @param includeChildren true 表示递归包含子部门
     */
    public void addDeptScopeCondition(LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType,
                                      boolean includeChildren) {
        List<Long> subordinateUserIds = getSubordinateUserIds(user, includeChildren);
        List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

        // 两个 Wrapper 重载必须保持同一语义：部门归属 OR 标签资源 OR 显式共享。
        // 这里的 OR 集合保证 DEPT 权限不会收窄原有 TAGE/data_share 可见范围。
        wrapper.and(w -> {
            boolean hasCondition = false;
            for (String userField : userFields) {
                if (subordinateUserIds.isEmpty()) {
                    appendEqCondition(w, userField, -1L, hasCondition);
                } else {
                    appendInCondition(w, userField, subordinateUserIds, hasCondition);
                }
                hasCondition = true;
            }

            if (!tageResourceIds.isEmpty()) {
                appendInCondition(w, "id", tageResourceIds, hasCondition);
                hasCondition = true;
            }
            if (!sharedResourceIds.isEmpty()) {
                appendInCondition(w, "id", sharedResourceIds, hasCondition);
                hasCondition = true;
            }
            // 空部门且无附加授权时显式生成不可满足条件，避免空 AND 导致 SQL 异常或全表放行。
            if (!hasCondition) {
                w.apply("1 = 0");
            }
        });

        log.debug("添加部门范围条件(Lambda) - 下属用户数={}, 标签资源数={}, 共享资源数={}",
                subordinateUserIds.size(), tageResourceIds.size(), sharedResourceIds.size());
    }

    /**
     * 判断当前用户是否为其所属部门的负责人。
     *
     * @param user 当前用户
     * @return true 表示 {@code sys_dept.leaderId} 等于当前用户 ID
     */
    private boolean isDepartmentLeader(RoleAO user) {
        if (user.getId() == null || user.getDeptId() == null) {
            return false;
        }
        SysDeptEntity dept = sysDeptMapper.selectById(user.getDeptId());
        return dept != null && Objects.equals(dept.getLeaderId(), user.getId());
    }

    /**
     * 递归解析部门子树，并用已访问集合防御脏数据造成的 parentId 循环。
     *
     * @param rootDeptId 根部门 ID
     * @return 根部门及其所有直接/间接子部门 ID；入参为空时返回空集合
     */
    private List<Long> resolveDepartmentSubtreeIds(Long rootDeptId) {
        if (rootDeptId == null) {
            return Collections.emptyList();
        }

        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(rootDeptId);
        while (!pending.isEmpty()) {
            Long deptId = pending.poll();
            // visited 同时承担“结果集合”和“环检测”；重复 parentId 只会被展开一次。
            if (deptId == null || !visited.add(deptId)) {
                continue;
            }
            List<SysDeptEntity> children = sysDeptMapper.selectList(
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
        return new ArrayList<>(visited);
    }

    /**
     * 解析部门范围内的用户 ID 集合。
     *
     * @param user            当前用户
     * @param includeChildren true 表示包含部门子树
     * @return 当前用户 ID 加上范围内的用户 ID；至少包含当前用户，保证不会收窄 SELF 可见性
     */
    private List<Long> getSubordinateUserIds(RoleAO user, boolean includeChildren) {
        Set<Long> userIds = new LinkedHashSet<>();
        if (user == null || user.getId() == null) {
            return Collections.emptyList();
        }
        userIds.add(user.getId());

        if (user.getDeptId() == null) {
            return new ArrayList<>(userIds);
        }
        Set<Long> deptIds = includeChildren
                ? new LinkedHashSet<>(resolveDepartmentSubtreeIds(user.getDeptId()))
                : Set.of(user.getDeptId());

        List<UserEntity> users = userMapper.selectList(
                new LambdaQueryWrapper<UserEntity>().in(UserEntity::getDeptId, deptIds));
        if (users != null) {
            for (UserEntity departmentUser : users) {
                if (departmentUser != null && departmentUser.getId() != null) {
                    userIds.add(departmentUser.getId());
                }
            }
        }
        return new ArrayList<>(userIds);
    }

    private void appendDeptScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType,
                                          boolean includeChildren) {
        List<Long> subordinateUserIds = getSubordinateUserIds(user, includeChildren);
        List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
        List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

        wrapper.and(w -> {
            boolean hasCondition = false;
            for (String userField : userFields) {
                if (hasCondition) {
                    w.or();
                }
                if (subordinateUserIds.isEmpty()) {
                    w.apply(userField + " = {0}", -1L);
                } else {
                    w.in(userField, subordinateUserIds);
                }
                hasCondition = true;
            }

            if (!tageResourceIds.isEmpty()) {
                if (hasCondition) {
                    w.or();
                }
                w.in("id", tageResourceIds);
                hasCondition = true;
            }
            if (!sharedResourceIds.isEmpty()) {
                if (hasCondition) {
                    w.or();
                }
                w.in("id", sharedResourceIds);
                hasCondition = true;
            }
            if (!hasCondition) {
                w.apply("1 = 0");
            }
        });

        log.debug("添加部门范围条件 - 下属用户数={}, 标签资源数={}, 共享资源数={}",
                subordinateUserIds.size(), tageResourceIds.size(), sharedResourceIds.size());
    }

    @Override
    public DataScopeLevel levelOf(UserContext user) {
        if (user == null) {
            return DataScopeLevel.NONE;
        }
        if (user.isSuperAdmin()) {
            return DataScopeLevel.ALL;
        }
        return user.dataScope() == null ? DataScopeLevel.NONE : user.dataScope();
    }

    @Override
    public List<Long> visibleDeptIds(UserContext user) {
        if (user == null || user.deptId() == null) {
            return Collections.emptyList();
        }
        DataScopeLevel level = levelOf(user);
        if (level == DataScopeLevel.ALL) {
            // 契约约定 ALL 返回空集合表示“不限制”，调用方不得解释为“无权限”。
            return Collections.emptyList();
        }
        if (level == DataScopeLevel.DEPT) {
            return List.of(user.deptId());
        }
        if (level == DataScopeLevel.DEPT_AND_CHILD) {
            return resolveDepartmentSubtreeIds(user.deptId());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean canRead(UserContext user, Long ownerId, Long ownerDeptId) {
        if (user == null) {
            return false;
        }
        DataScopeLevel level = levelOf(user);
        if (level == DataScopeLevel.ALL) {
            return true;
        }
        if (Objects.equals(user.userId(), ownerId)) {
            return true;
        }
        if (level == DataScopeLevel.DEPT) {
            return Objects.equals(user.deptId(), ownerDeptId);
        }
        if (level == DataScopeLevel.DEPT_AND_CHILD) {
            return resolveDepartmentSubtreeIds(user.deptId()).contains(ownerDeptId);
        }
        return false;
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

        if (relatedUserIds != null
                && Arrays.stream(relatedUserIds).anyMatch(relatedUserId -> Objects.equals(user.getId(), relatedUserId))) {
            return true;
        }

        if (level == DataScopeLevel.DEPT || level == DataScopeLevel.DEPT_AND_CHILD) {
            List<Long> subordinateUserIds = getSubordinateUserIds(user, level == DataScopeLevel.DEPT_AND_CHILD);
            if (relatedUserIds != null
                    && Arrays.stream(relatedUserIds).anyMatch(subordinateUserIds::contains)) {
                return true;
            }
        }

        List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
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
