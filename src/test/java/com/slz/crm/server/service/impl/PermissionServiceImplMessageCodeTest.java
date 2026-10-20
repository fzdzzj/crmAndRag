package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.RoleANDPermissionDTO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.PermissionsMapper;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 角色-权限维护链路的 MessageConstant 映射码断言（harden-test-backlog-batch1 ④）。
 *
 * <p>补 {@code PermissionServiceImpl#addOrDeletePermissionsToRole} 三处 2 参构造的站点级码覆盖：这三处生产映射 （{@code
 * MessageConstant.ROLE_PERMISSION_IS_NULL} / {@code ROLE_IS_NOT_EXIST} / {@code
 * PERMISSION_IS_NOT_EXIST}） 此前在测试中零引用，只靠源码门禁兜底。码值一律以生产 {@link ErrorCode} 实读为准，不凭记忆写。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("角色权限维护：MessageConstant 文案与精确错误码双锁")
class PermissionServiceImplMessageCodeTest {

  /** 生产实读：ErrorCode.PARAM_EMPTY = 50001（ErrorCode:150）。 */
  private static final int PARAM_EMPTY_CODE = 50001;

  /** 生产实读：ErrorCode.ROLE_NOT_EXISTS = 13001（ErrorCode:68）。 */
  private static final int ROLE_NOT_EXISTS_CODE = 13001;

  /** 生产实读：ErrorCode.ID_NOT_EXISTS = 92008（ErrorCode:236）。 */
  private static final int ID_NOT_EXISTS_CODE = 92008;

  @Mock private PermissionsMapper permissionsMapper;

  @Mock private RoleMapper roleMapper;

  @Mock private UserMapper userMapper;

  @InjectMocks private PermissionServiceImpl permissionService;

  @Test
  @DisplayName("roleId 为空 → PARAM_EMPTY(50001) + ROLE_PERMISSION_IS_NULL 原文案")
  void nullRoleIdMapsToParamEmpty() {
    RoleANDPermissionDTO dto = new RoleANDPermissionDTO();
    dto.setPermissionIds(List.of(1));
    dto.setIsAdd(true);

    BaseException exception =
        assertThrows(
            BaseException.class, () -> permissionService.addOrDeletePermissionsToRole(dto));

    assertEquals(PARAM_EMPTY_CODE, exception.getCode().intValue(), "空参数必须锁 50001");
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), exception.getCode());
    assertEquals(MessageConstant.ROLE_PERMISSION_IS_NULL, exception.getMessage());
  }

  @Test
  @DisplayName("角色不存在 → ROLE_NOT_EXISTS(13001) + ROLE_IS_NOT_EXIST 原文案")
  void missingRoleMapsToRoleNotExists() {
    RoleANDPermissionDTO dto = new RoleANDPermissionDTO();
    dto.setRoleId(77);
    dto.setPermissionIds(List.of(1));
    dto.setIsAdd(true);
    when(roleMapper.selectById(77)).thenReturn(null);

    BaseException exception =
        assertThrows(
            BaseException.class, () -> permissionService.addOrDeletePermissionsToRole(dto));

    assertEquals(ROLE_NOT_EXISTS_CODE, exception.getCode().intValue(), "角色缺失必须锁 13001");
    assertEquals(ErrorCode.ROLE_NOT_EXISTS.getCode(), exception.getCode());
    assertEquals(MessageConstant.ROLE_IS_NOT_EXIST, exception.getMessage());
  }

  @Test
  @DisplayName("权限全不存在 → ID_NOT_EXISTS(92008) + PERMISSION_IS_NOT_EXIST 原文案")
  void missingPermissionsMapsToIdNotExists() {
    RoleANDPermissionDTO dto = new RoleANDPermissionDTO();
    dto.setRoleId(77);
    dto.setPermissionIds(List.of(1));
    dto.setIsAdd(true);
    RoleEntity roleEntity = new RoleEntity();
    roleEntity.setId(77L);
    when(roleMapper.selectById(77)).thenReturn(roleEntity);
    when(permissionsMapper.selectExistPermissionIds(List.of(1))).thenReturn(List.of());

    BaseException exception =
        assertThrows(
            BaseException.class, () -> permissionService.addOrDeletePermissionsToRole(dto));

    assertEquals(ID_NOT_EXISTS_CODE, exception.getCode().intValue(), "权限缺失必须锁 92008");
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), exception.getCode());
    assertEquals(MessageConstant.PERMISSION_IS_NOT_EXIST, exception.getMessage());
  }
}
