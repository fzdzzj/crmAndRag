package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.vo.PermissionGroupedVO;
import com.slz.crm.pojo.vo.PermissionVO;
import com.slz.crm.pojo.vo.SubPermissionVO;
import com.slz.crm.server.mapper.PermissionsMapper;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code GET /permission/list} 的模块分组口径（close-permission-module-gap 任务 2.2）。
 *
 * <p>三条不变式：返回 Map 恒含 10 个模块键（空模块保留为空列表）；每条权限恰好出现一次（无丢弃、无重复）； 800/810 落 AI、900 落知识库、枚举外 ID
 * 落「其他模块权限」，data-scope 权限进 subPermissions 并携带 parentPermissionId。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("权限目录模块分组：恒 10 桶 + 零丢弃 + AI/知识库归位")
class PermissionServiceImplModuleGroupingTest {

  private static final List<String> EXPECTED_MODULES =
      List.of(
          "客户管理模块权限",
          "销售管理模块权限",
          "财务管理模块权限",
          "联络任务模块权限",
          "统计报表模块权限",
          "权限管理模块权限",
          "隐私信息查看权限",
          "AI 模块权限",
          "知识库管理模块权限",
          "其他模块权限");

  @Mock private PermissionsMapper permissionsMapper;

  @Mock private RoleMapper roleMapper;

  @Mock private UserMapper userMapper;

  @InjectMocks private PermissionServiceImpl permissionService;

  private static PermissionsEntity permission(long id) {
    PermissionsEntity entity = new PermissionsEntity();
    entity.setId(id);
    entity.setPermissionsName("PERM_" + id);
    entity.setPermissionsDesc("权限" + id);
    return entity;
  }

  private static List<Long> mainIds(PermissionGroupedVO grouped) {
    return grouped.getMainPermissions().stream().map(PermissionVO::getId).toList();
  }

  private static List<Long> allIds(Map<String, PermissionGroupedVO> result) {
    List<Long> ids = new ArrayList<>();
    for (PermissionGroupedVO grouped : result.values()) {
      grouped.getMainPermissions().forEach(permission -> ids.add(permission.getId()));
      grouped.getSubPermissions().forEach(permission -> ids.add(permission.getId()));
    }
    return ids;
  }

  @Test
  @DisplayName("没有任何权限行时仍返回 10 个键，且每个模块为空列表")
  void keepsTenEmptyModulesWhenNoPermissionRows() {
    when(permissionsMapper.selectList(null)).thenReturn(List.of());

    Map<String, PermissionGroupedVO> result =
        permissionService.getAllPermissionsGroupedByModuleGrouped();

    assertEquals(EXPECTED_MODULES, new ArrayList<>(result.keySet()));
    for (PermissionGroupedVO grouped : result.values()) {
      assertTrue(grouped.getMainPermissions().isEmpty(), "空模块的 mainPermissions 必须为空列表");
      assertTrue(grouped.getSubPermissions().isEmpty(), "空模块的 subPermissions 必须为空列表");
    }
  }

  @Test
  @DisplayName("每条权限恰好出现一次，AI 含 800/810、知识库含 900、枚举外 ID 落其他模块")
  void groupsEveryRowExactlyOnceBySegment() {
    when(permissionsMapper.selectList(null))
        .thenReturn(
            List.of(
                permission(101),
                permission(608),
                permission(800),
                permission(810),
                permission(900),
                permission(9999),
                permission(1171)));

    Map<String, PermissionGroupedVO> result =
        permissionService.getAllPermissionsGroupedByModuleGrouped();

    assertEquals(EXPECTED_MODULES, new ArrayList<>(result.keySet()));

    List<Long> ids = allIds(result);
    assertEquals(7, ids.size(), "7 条权限行必须全部出现，零丢弃");
    Set<Long> distinct = new HashSet<>(ids);
    assertEquals(7, distinct.size(), "同一权限不得重复出现在多个模块");

    assertEquals(List.of(800L, 810L), mainIds(result.get("AI 模块权限")));
    assertEquals(List.of(900L), mainIds(result.get("知识库管理模块权限")));
    assertEquals(List.of(9999L), mainIds(result.get("其他模块权限")));
    assertEquals(List.of(101L), mainIds(result.get("客户管理模块权限")));
    assertEquals(List.of(608L), mainIds(result.get("权限管理模块权限")));
    assertTrue(result.get("财务管理模块权限").getMainPermissions().isEmpty());
  }

  @Test
  @DisplayName("data-scope 权限进 subPermissions 并携带 parentPermissionId，其余进 mainPermissions")
  void dataScopePermissionsGoToSubListWithParentId() {
    when(permissionsMapper.selectList(null))
        .thenReturn(List.of(permission(117), permission(1171), permission(800)));

    Map<String, PermissionGroupedVO> customer =
        permissionService.getAllPermissionsGroupedByModuleGrouped();

    PermissionGroupedVO grouped = customer.get("客户管理模块权限");
    assertEquals(List.of(117L), mainIds(grouped));

    List<SubPermissionVO> subs = grouped.getSubPermissions();
    assertEquals(1, subs.size(), "1171 是 data-scope 子权限");
    assertEquals(1171L, subs.get(0).getId().longValue());
    assertEquals(117L, subs.get(0).getParentPermissionId().longValue());

    PermissionGroupedVO ai = customer.get("AI 模块权限");
    assertEquals(List.of(800L), mainIds(ai), "800 是普通权限，必须进 mainPermissions");
    assertTrue(ai.getSubPermissions().isEmpty());
  }
}
