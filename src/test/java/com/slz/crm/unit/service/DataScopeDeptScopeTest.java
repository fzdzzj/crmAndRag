package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("部门数据范围")
class DataScopeDeptScopeTest {

  @Mock private TageRoleBindingMapper tageRoleBindingMapper;
  @Mock private TageResourceBindingMapper tageResourceBindingMapper;
  @Mock private DataShareMapper dataShareMapper;
  @Mock private RolePermissionsMapper rolePermissionsMapper;
  @Mock private SysDeptMapper sysDeptMapper;
  @Mock private UserMapper userMapper;

  @InjectMocks private DataScopeServiceImpl service;

  @Test
  @DisplayName("fromCode 保留旧编码并解析新部门编码")
  void fromCodeRemainsCompatible() {
    assertEquals(DataScopeLevel.NONE, DataScopeLevel.fromCode(0));
    assertEquals(DataScopeLevel.SELF, DataScopeLevel.fromCode(1));
    assertEquals(DataScopeLevel.TAGE, DataScopeLevel.fromCode(2));
    assertEquals(DataScopeLevel.ALL, DataScopeLevel.fromCode(3));
    assertEquals(DataScopeLevel.DEPT, DataScopeLevel.fromCode(4));
    assertEquals(DataScopeLevel.DEPT_AND_CHILD, DataScopeLevel.fromCode(5));
    assertEquals(DataScopeLevel.NONE, DataScopeLevel.fromCode(999));
  }

  @Test
  @DisplayName("QueryWrapper 的 DEPT 覆盖全部归属字段并 UNION 标签与共享")
  void queryWrapperDeptScopeKeepsSuperset() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any()))
        .thenReturn(List.of(permission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT)));
    when(userMapper.selectList(any())).thenReturn(List.of(user(7L, 10L), user(8L, 10L)));
    when(tageRoleBindingMapper.selectTageIdsByRoleId(2L)).thenReturn(List.of(31L));
    when(tageResourceBindingMapper.selectResourceIdsByTageIds(List.of(31L), "sales_opportunity"))
        .thenReturn(List.of(91L));
    when(dataShareMapper.selectSharedResourceIds(7L, 2L, "sales_opportunity"))
        .thenReturn(List.of(92L));

    QueryWrapper<?> wrapper = new QueryWrapper<>();
    service.addDataScopeCondition(wrapper, user, "sales_opportunity");

    String sql = wrapper.getSqlSegment();
    assertTrue(sql.contains("creator_id IN"), sql);
    assertTrue(sql.contains("owner_id IN"), sql);
    assertTrue(sql.contains("id IN"), sql);
    assertTrue(wrapper.getParamNameValuePairs().containsValue(91L));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(92L));
  }

  @Test
  @DisplayName("LambdaQueryWrapper 的 DEPT 覆盖全部归属字段")
  void lambdaWrapperDeptScopeUsesAllOwnerFields() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any()))
        .thenReturn(List.of(permission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_DEPT)));
    when(userMapper.selectList(any())).thenReturn(List.of(user(7L, 10L), user(8L, 10L)));
    when(tageRoleBindingMapper.selectTageIdsByRoleId(2L)).thenReturn(Collections.emptyList());
    when(dataShareMapper.selectSharedResourceIds(7L, 2L, "sales_opportunity"))
        .thenReturn(Collections.emptyList());

    LambdaQueryWrapper<?> wrapper = new LambdaQueryWrapper<>();
    service.addDataScopeCondition(wrapper, user, "sales_opportunity");

    String sql = wrapper.getSqlSegment();
    assertTrue(sql.contains("creator_id IN"), sql);
    assertTrue(sql.contains("owner_id IN"), sql);
  }

  @Test
  @DisplayName("customer_contact 未配置权限映射时保持 SELF")
  void customerContactRemainsSelf() {
    RoleAO user = role(7L, 2L, 10L);
    when(dataShareMapper.selectSharedResourceIds(7L, null, "customer_contact"))
        .thenReturn(Collections.emptyList());

    QueryWrapper<?> wrapper = new QueryWrapper<>();
    service.addDataScopeCondition(wrapper, user, "customer_contact");

    assertTrue(wrapper.getSqlSegment().contains("creator_id ="));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    verifyNoInteractions(rolePermissionsMapper, sysDeptMapper, userMapper);
  }

  @Test
  @DisplayName("部门负责人默认获得本部门及以下范围")
  void departmentLeaderDefaultsToDeptAndChild() {
    RoleAO user = role(7L, 2L, 10L);
    when(rolePermissionsMapper.selectList(any())).thenReturn(Collections.emptyList());
    when(sysDeptMapper.selectById(10L)).thenReturn(department(10L, null, 7L));

    assertEquals(
        DataScopeLevel.DEPT_AND_CHILD.getCode(),
        service.getHighestDataScopeLevel(user, "sales_opportunity"));
  }

  @Test
  @DisplayName("parentId 循环引用时递归安全终止")
  void cyclicDepartmentTreeTerminates() {
    UserContext user = new UserContext(7L, 2L, 1L, DataScopeLevel.DEPT_AND_CHILD, "用户");
    when(sysDeptMapper.selectList(any()))
        .thenReturn(List.of(department(2L, 1L, null)))
        .thenReturn(List.of(department(1L, 2L, null)));

    assertEquals(List.of(1L, 2L), service.visibleDeptIds(user));
    verify(sysDeptMapper, times(2)).selectList(any());
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
