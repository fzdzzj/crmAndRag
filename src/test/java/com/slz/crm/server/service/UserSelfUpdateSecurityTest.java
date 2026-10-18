package com.slz.crm.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用户自服务更新防提权越权专项测试（harden-user-self-update 任务 3.1，纯 JVM Mockito）。
 *
 * <p>覆盖：白名单正向修改、roleId/deptId/status 三类特权字段 fail-closed 拦截（12002 且不发任何 SQL）、 管理员特权通道 {@code
 * updateUser} 能力不受影响。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserSelfUpdateSecurityTest 自服务防提权专项测试")
class UserSelfUpdateSecurityTest {

  private static final Long CURRENT_USER_ID = 42L;
  private static final Long FORGED_TARGET_ID = 999L;

  @BeforeAll
  static void initializeUserTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserEntity.class);
  }

  @Mock private UserMapper userMapper;

  @Mock private RoleMapper roleMapper;

  @Mock private SysDeptMapper sysDeptMapper;

  @Mock private DataConvertService dataConvertService;

  @Mock private JwtProperties jwtProperties;

  @Captor private ArgumentCaptor<LambdaUpdateWrapper<UserEntity>> wrapperCaptor;

  @InjectMocks private UserServiceImpl userService;

  @Test
  @DisplayName("1. 自服务白名单修改成功：仅 realName/phone/email/update_time 入 SET 且 id 绑定 currentUserId")
  void shouldUpdateWhitelistFieldsOnly() {
    UserDTO request = new UserDTO();
    request.setId(FORGED_TARGET_ID);
    request.setRealName("白名单新姓名");
    request.setPhone("13800001111");
    request.setEmail("self@crm.local");

    when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

    userService.updateMyInfo(request, CURRENT_USER_ID);

    verify(userMapper).update(isNull(), wrapperCaptor.capture());
    LambdaUpdateWrapper<UserEntity> wrapper = wrapperCaptor.getValue();
    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("real_name"));
    assertTrue(sqlSet.contains("phone"));
    assertTrue(sqlSet.contains("email"));
    assertTrue(sqlSet.contains("update_time"));
    assertFalse(sqlSet.contains("role_id"));
    assertFalse(sqlSet.contains("dept_id"));
    assertFalse(sqlSet.contains("status"));
    assertTrue(wrapper.getSqlSegment().startsWith("(id"));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(CURRENT_USER_ID));
    assertFalse(wrapper.getParamNameValuePairs().containsValue(FORGED_TARGET_ID));
  }

  @Test
  @DisplayName("2. 自服务传入非空 roleId 严格拦截 12002 且不发任何 SQL（角色未被修改）")
  void shouldRejectRoleIdInSelfUpdate() {
    UserDTO request = new UserDTO();
    request.setRealName("试图改名攻击者");
    request.setRoleId(5L);

    BaseException exception =
        assertThrows(BaseException.class, () -> userService.updateMyInfo(request, CURRENT_USER_ID));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), exception.getCode());
    verifyNoInteractions(userMapper);
  }

  @Test
  @DisplayName("3. 自服务传入非空 deptId 严格拦截 12002 且不发任何 SQL（部门未被修改）")
  void shouldRejectDeptIdInSelfUpdate() {
    UserDTO request = new UserDTO();
    request.setRealName("试图换部门攻击者");
    request.setDeptId(88L);

    BaseException exception =
        assertThrows(BaseException.class, () -> userService.updateMyInfo(request, CURRENT_USER_ID));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), exception.getCode());
    verifyNoInteractions(userMapper);
  }

  @Test
  @DisplayName("4. 自服务传入非空 status 严格拦截 12002 且不发任何 SQL（状态未被修改）")
  void shouldRejectStatusInSelfUpdate() {
    UserDTO request = new UserDTO();
    request.setRealName("试图改状态攻击者");
    request.setStatus(0);

    BaseException exception =
        assertThrows(BaseException.class, () -> userService.updateMyInfo(request, CURRENT_USER_ID));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), exception.getCode());
    verifyNoInteractions(userMapper);
  }

  @Test
  @DisplayName("5. 管理员通道 updateUser 正常修改部门与合法角色，特权能力不受影响")
  void shouldAllowAdminUpdatePrivilegedFields() {
    Long targetUserId = 7L;
    UserDTO request = new UserDTO();
    request.setId(targetUserId);
    request.setDeptId(10L);
    request.setRoleId(2L);

    UserEntity existingUser = new UserEntity();
    existingUser.setId(targetUserId);
    when(userMapper.selectById(targetUserId)).thenReturn(existingUser);
    SysDeptEntity activeDept = new SysDeptEntity();
    activeDept.setId(10L);
    activeDept.setStatus(1);
    when(sysDeptMapper.selectById(10L)).thenReturn(activeDept);
    when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

    userService.updateUser(request, targetUserId);

    verify(userMapper).update(isNull(), wrapperCaptor.capture());
    String sqlSet = wrapperCaptor.getValue().getSqlSet();
    assertTrue(sqlSet.contains("dept_id"));
    assertTrue(sqlSet.contains("role_id"));
    assertTrue(sqlSet.contains("update_time"));
    assertFalse(sqlSet.contains("real_name"));
  }
}
