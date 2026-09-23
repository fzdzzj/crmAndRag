package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.DataScope;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataScopeService;
import jakarta.annotation.Resource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 数据权限服务实现
 *
 * <p>多级数据权限系统的核心服务，同时实现冻结契约 {@link DataScope}。
 *
 * <p>线程安全：本类无可变共享状态，所有请求状态都在方法栈内，可安全被并发调用。
 *
 * <p>tighten-pmd-residual-325 任务 6.4 批C：表名→权限枚举映射拆至 {@link DataScopeTablePermissions}、 SQL 条件拼装拆至
 * {@link DataScopeConditionComposer}（纯静态零查询）、部门维度解析拆至 {@link DataScopeDepartmentResolver}、 级别解析拆至
 * {@link DataScopeLevelResolver}；本类保留数据查询、契约门面与条件路由。守卫式早返回按超集不变量单出口化， 判定结果与拆分前逐场景等价（等价基准见
 * DataScopeScopeMatrixTest），行为等价。协作对象经懒初始化装配， 构建后内容不可变（JMM final 语义保证安全发布），不破坏本类的并发安全性。
 */
@Slf4j
@Service
public class DataScopeServiceImpl implements DataScopeService, DataScope {

  @Resource private TageRoleBindingMapper tageRoleBindingMapper;
  @Resource private TageResourceBindingMapper tageResourceBindingMapper;
  @Resource private DataShareMapper dataShareMapper;
  @Resource private RolePermissionsMapper rolePermissionsMapper;
  @Resource private SysDeptMapper sysDeptMapper;
  @Resource private UserMapper userMapper;

  /** 懒初始化协作对象；构建后内容不可变，竞态只会产生等价实例，不影响判定。 */
  private DataScopeDepartmentResolver departmentResolver;

  private DataScopeLevelResolver levelResolver;

  private DataScopeShareQuery shareQuery;

  private DataScopeUserScopeGuard userScopeGuard;

  private DataScopeDepartmentResolver departmentResolver() {
    if (departmentResolver == null) {
      departmentResolver = new DataScopeDepartmentResolver(sysDeptMapper, userMapper);
    }
    return departmentResolver;
  }

  private DataScopeShareQuery shareQuery() {
    if (shareQuery == null) {
      shareQuery =
          new DataScopeShareQuery(
              tageRoleBindingMapper, tageResourceBindingMapper, dataShareMapper);
    }
    return shareQuery;
  }

  private DataScopeLevelResolver levelResolver() {
    if (levelResolver == null) {
      levelResolver =
          new DataScopeLevelResolver(rolePermissionsMapper, departmentResolver(), shareQuery());
    }
    return levelResolver;
  }

  private DataScopeUserScopeGuard userScopeGuard() {
    if (userScopeGuard == null) {
      userScopeGuard = new DataScopeUserScopeGuard(departmentResolver());
    }
    return userScopeGuard;
  }

  @Override
  public Integer getHighestDataScopeLevel(RoleAO user, String resourceType) {
    return levelResolver().getHighestDataScopeLevel(user, resourceType);
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
  public void addDataScopeCondition(
      LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType) {
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

  public void addSelfScopeCondition(
      LambdaQueryWrapper<?> wrapper, Long userId, String resourceType) {
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);
    List<Long> sharedResourceIds = getSharedResourceIds(userId, null, resourceType);

    DataScopeConditionComposer.appendSelfCondition(wrapper, userFields, userId, sharedResourceIds);

    log.debug("添加一级权限条件(Lambda) - 用户字段={}, 共享资源数={}", userFields, sharedResourceIds.size());
  }

  public void addTageScopeCondition(
      LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType) {
    List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
    List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), null, resourceType);
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

    DataScopeConditionComposer.appendTageCondition(
        wrapper, userFields, user.getId(), tageResourceIds, sharedResourceIds);

    log.debug(
        "添加二级权限条件(Lambda) - 标签资源数={}, 用户字段={}, 共享资源数={}",
        tageResourceIds.size(),
        userFields,
        sharedResourceIds.size());
  }

  /**
   * 为字符串列名的 {@link QueryWrapper} 注入本部门范围。
   *
   * @param wrapper 原生 QueryWrapper
   * @param user 当前用户
   * @param resourceType 受控资源表名
   */
  public void addDeptScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
    appendDeptScopeCondition(wrapper, user, resourceType, false);
  }

  /**
   * 为字符串列名的 {@link QueryWrapper} 注入本部门及以下范围。
   *
   * @param wrapper 原生 QueryWrapper
   * @param user 当前用户
   * @param resourceType 受控资源表名
   */
  public void addDeptAndChildScopeCondition(
      QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
    appendDeptScopeCondition(wrapper, user, resourceType, true);
  }

  /**
   * 为 LambdaWrapper 注入本部门/本部门及以下范围。
   *
   * @param wrapper LambdaQueryWrapper
   * @param user 当前用户
   * @param resourceType 受控资源表名
   * @param includeChildren true 表示递归包含子部门
   */
  public void addDeptScopeCondition(
      LambdaQueryWrapper<?> wrapper, RoleAO user, String resourceType, boolean includeChildren) {
    List<Long> subordinateUserIds =
        departmentResolver().getSubordinateUserIds(user, includeChildren);
    List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
    List<Long> sharedResourceIds =
        getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

    // 两个 Wrapper 重载必须保持同一语义：部门归属 OR 标签资源 OR 显式共享。
    // 这里的 OR 集合保证 DEPT 权限不会收窄原有 TAGE/data_share 可见范围。
    DataScopeConditionComposer.appendDeptCondition(
        wrapper, userFields, subordinateUserIds, tageResourceIds, sharedResourceIds);

    log.debug(
        "添加部门范围条件(Lambda) - 下属用户数={}, 标签资源数={}, 共享资源数={}",
        subordinateUserIds.size(),
        tageResourceIds.size(),
        sharedResourceIds.size());
  }

  private void appendDeptScopeCondition(
      QueryWrapper<?> wrapper, RoleAO user, String resourceType, boolean includeChildren) {
    List<Long> subordinateUserIds =
        departmentResolver().getSubordinateUserIds(user, includeChildren);
    List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);
    List<Long> sharedResourceIds =
        getSharedResourceIds(user.getId(), user.getRoleId(), resourceType);
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

    DataScopeConditionComposer.appendDeptCondition(
        wrapper, userFields, subordinateUserIds, tageResourceIds, sharedResourceIds);

    log.debug(
        "添加部门范围条件 - 下属用户数={}, 标签资源数={}, 共享资源数={}",
        subordinateUserIds.size(),
        tageResourceIds.size(),
        sharedResourceIds.size());
  }

  @Override
  public DataScopeLevel levelOf(UserContext user) {
    return userScopeGuard().levelOf(user);
  }

  @Override
  public List<Long> visibleDeptIds(UserContext user) {
    return userScopeGuard().visibleDeptIds(user);
  }

  @Override
  public boolean canRead(UserContext user, Long ownerId, Long ownerDeptId) {
    return userScopeGuard().canRead(user, ownerId, ownerDeptId);
  }

  /** 添加一级权限条件 */
  public void addSelfScopeCondition(QueryWrapper<?> wrapper, Long userId, String resourceType) {
    // 第一级权限:查看自己的 + 共享资源

    // 1. 获取需要检查的用户字段
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

    // 2. 查询共享资源ID(只查询直接共享给用户的,不包含角色共享)
    List<Long> sharedResourceIds = getSharedResourceIds(userId, null, resourceType);

    // 3. 构建 OR 条件: (用户字段) OR (共享资源)
    DataScopeConditionComposer.appendSelfCondition(wrapper, userFields, userId, sharedResourceIds);

    log.debug("添加第一级权限条件 - 用户字段={}, 共享资源数={}", userFields, sharedResourceIds.size());
  }

  /** 添加二级权限条件 */
  public void addTageScopeCondition(QueryWrapper<?> wrapper, RoleAO user, String resourceType) {
    // 第二级权限:标签资源 + 查看自己的 + 共享资源

    // 1. 查询标签资源ID
    List<Long> tageResourceIds = getTageResourceIds(user.getRoleId(), resourceType);

    // 2. 查询共享资源ID(只查询直接共享给用户的)
    List<Long> sharedResourceIds = getSharedResourceIds(user.getId(), null, resourceType);

    // 3. 获取需要检查的用户字段
    List<String> userFields = ResourceTypeConstant.getUserFieldsByTableName(resourceType);

    // 4. 构建 OR 条件: (标签资源) OR (用户字段) OR (共享资源)
    DataScopeConditionComposer.appendTageCondition(
        wrapper, userFields, user.getId(), tageResourceIds, sharedResourceIds);

    log.debug(
        "添加第二级权限条件 - 标签资源数={}, 用户字段={}, 共享资源数={}",
        tageResourceIds.size(),
        userFields,
        sharedResourceIds.size());
  }

  @Override
  public List<Long> getTageResourceIds(Long roleId, String resourceType) {
    return shareQuery().getTageResourceIds(roleId, resourceType);
  }

  @Override
  public List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType) {
    return shareQuery().getSharedResourceIds(userId, roleId, resourceType);
  }

  @Override
  public boolean canReadResource(
      RoleAO user, String resourceType, Long resourceId, Long... relatedUserIds) {
    return levelResolver().canReadResource(user, resourceType, resourceId, relatedUserIds);
  }
}
