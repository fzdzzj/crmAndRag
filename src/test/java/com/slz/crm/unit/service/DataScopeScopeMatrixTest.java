package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.DataScopeServiceImpl;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 数据范围 scope×角色 判定矩阵（tighten-pmd-residual-325 任务 6.4 批C）。
 *
 * <p>超集不变量的等价基准：拆分前后各跑一遍，全部场景判定结果必须逐场景一致 （既不放宽也不收窄）。覆盖：全部数据 / 本部门及以下 / 本部门 / 本人 / 跨部门拒绝 / 标签 / 共享。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据范围判定矩阵（F-3 批C 等价基准）")
class DataScopeScopeMatrixTest {

  @Mock private TageRoleBindingMapper tageRoleBindingMapper;
  @Mock private TageResourceBindingMapper tageResourceBindingMapper;
  @Mock private DataShareMapper dataShareMapper;
  @Mock private RolePermissionsMapper rolePermissionsMapper;
  @Mock private SysDeptMapper sysDeptMapper;
  @Mock private UserMapper userMapper;

  @InjectMocks private DataScopeServiceImpl service;

  @Test
  @DisplayName("矩阵·全部数据：超管直接 ALL 且零查询")
  void superAdminIsAllWithoutAnyQuery() {
    RoleAO user = role(1L, 1L, 10L);

    assertEquals(
        DataScopeLevel.ALL.getCode(), service.getHighestDataScopeLevel(user, "sales_opportunity"));
    verifyNoInteractions(
        rolePermissionsMapper,
        sysDeptMapper,
        userMapper,
        tageRoleBindingMapper,
        tageResourceBindingMapper,
        dataShareMapper);
  }

  @Test
  @DisplayName("矩阵·全部数据：ALL 权限放行跨部门单条资源")
  void allScopeReadsAcrossDepartments() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any()))
        .thenReturn(List.of(permission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ALL)));

    assertEquals(
        DataScopeLevel.ALL.getCode(), service.getHighestDataScopeLevel(user, "sales_opportunity"));
    assertTrue(service.canReadResource(user, "sales_opportunity", 99L, 8L));
  }

  @Test
  @DisplayName("矩阵·本部门：DEPT 范围同部门放行、跨部门拒绝")
  void deptScopeOnlyWithinDepartment() {
    UserContext ctx = new UserContext(7L, 2L, 10L, DataScopeLevel.DEPT, "用户");

    assertTrue(service.canRead(ctx, 8L, 10L));
    assertFalse(service.canRead(ctx, 8L, 20L));
    assertEquals(List.of(10L), service.visibleDeptIds(ctx));
  }

  @Test
  @DisplayName("矩阵·本人：SELF 范围仅本人记录放行，跨部门只读不扩大")
  void selfScopeOnlyOwnRecords() {
    UserContext ctx = new UserContext(7L, 2L, 10L, DataScopeLevel.SELF, "用户");

    assertTrue(service.canRead(ctx, 7L, null));
    assertFalse(service.canRead(ctx, 8L, 10L));
    assertFalse(service.canRead(ctx, 8L, null));
    assertEquals(Collections.emptyList(), service.visibleDeptIds(ctx));
  }

  @Test
  @DisplayName("矩阵·本部门及以下：DEPT_AND_CHILD 覆盖子树、不覆盖无关节点")
  void deptAndChildCoversSubtreeOnly() {
    UserContext ctx = new UserContext(7L, 2L, 10L, DataScopeLevel.DEPT_AND_CHILD, "用户");
    when(sysDeptMapper.selectList(any())).thenReturn(List.of(department(11L, 10L, null)));

    assertTrue(service.canRead(ctx, 8L, 11L));
    assertFalse(service.canRead(ctx, 8L, 99L));
    assertEquals(List.of(10L, 11L), service.visibleDeptIds(ctx));
  }

  @Test
  @DisplayName("矩阵·全部数据：ALL 契约下 addDataScopeCondition 不追加任何条件")
  void allScopeAddsNoCondition() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any()))
        .thenReturn(List.of(permission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_ALL)));

    QueryWrapper<?> wrapper = new QueryWrapper<>();
    service.addDataScopeCondition(wrapper, user, "sales_opportunity");

    assertTrue(wrapper.getParamNameValuePairs().isEmpty());
  }

  @Test
  @DisplayName("矩阵·本人：SELF 的 Lambda 条件为本人字段与显式共享的并集")
  void selfScopeLambdaCombinesOwnAndShared() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any())).thenReturn(Collections.emptyList());
    when(dataShareMapper.selectSharedResourceIds(7L, null, "sales_opportunity"))
        .thenReturn(List.of(92L));

    LambdaQueryWrapper<?> wrapper = new LambdaQueryWrapper<>();
    service.addDataScopeCondition(wrapper, user, "sales_opportunity");

    String sql = wrapper.getSqlSegment();
    assertTrue(sql.contains("creator_id ="), sql);
    assertTrue(sql.contains("owner_id ="), sql);
    assertTrue(sql.contains("id IN"), sql);
    assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    // 共享资源 ID 走 joinIds 内联拼装，不进参数对，直接在 SQL 片段里断言
    assertTrue(sql.contains("id IN (92)"), sql);
  }

  @Test
  @DisplayName("矩阵·本部门：canReadResource 经下属相关人放行、非下属且无共享拒绝")
  void deptScopeResourceViaSubordinateOnly() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any()))
        .thenReturn(List.of(permission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT)));
    when(userMapper.selectList(any())).thenReturn(List.of(user(7L, 10L), user(8L, 10L)));
    when(dataShareMapper.selectSharedResourceIds(7L, 2L, "sales_opportunity"))
        .thenReturn(Collections.emptyList());

    assertTrue(service.canReadResource(user, "sales_opportunity", 99L, 8L));
    assertFalse(service.canReadResource(user, "sales_opportunity", 99L, 9L));
  }

  private RoleAO role(Long userId, Long roleId, Long deptId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    role.setRoleId(roleId);
    role.setDeptId(deptId);
    return role;
  }

  private RolePermissionsEntity permission(PermissionOperates operates) {
    RolePermissionsEntity entity = new RolePermissionsEntity();
    entity.setPermissionsId(operates.getId());
    return entity;
  }

  private UserEntity user(Long id, Long deptId) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setDeptId(deptId);
    return user;
  }

  private SysDeptEntity department(Long id, Long parentId, Long leaderId) {
    SysDeptEntity department = new SysDeptEntity();
    department.setId(id);
    department.setParentId(parentId);
    department.setLeaderId(leaderId);
    return department;
  }
}
