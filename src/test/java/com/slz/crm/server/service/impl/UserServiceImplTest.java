package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.DataConvertService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 用户服务实现类单元测试（harden-admin-uniqueness-and-hygiene 任务 1.3：纯 JVM Mockito 测试覆盖终极管理员唯一性防护）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl 终极管理员唯一性防护测试")
class UserServiceImplTest {

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

  @InjectMocks private UserServiceImpl userService;

  @Test
  @DisplayName("1.1 addUser 当传入 roleId=1 时拒绝并抛出 MASTER_IS_ONE")
  void shouldRejectAddUserWhenRoleIdIsOne() {
    UserDTO request = new UserDTO();
    request.setRoleId(1L);
    request.setDeptId(10L);

    SysDeptEntity activeDept = new SysDeptEntity();
    activeDept.setId(10L);
    activeDept.setStatus(1);
    when(sysDeptMapper.selectById(10L)).thenReturn(activeDept);

    BaseException exception = assertThrows(BaseException.class, () -> userService.addUser(request));

    assertEquals(MessageConstant.MASTER_IS_ONE, exception.getMessage());
  }

  @Test
  @DisplayName("1.2 updateUser 当传入 roleId=1 时拒绝并抛出 MASTER_IS_ONE")
  void shouldRejectUpdateUserWhenRoleIdIsOne() {
    Long userId = 99L;
    UserDTO request = new UserDTO();
    request.setRoleId(1L);

    UserEntity existingUser = new UserEntity();
    existingUser.setId(userId);
    when(userMapper.selectById(userId)).thenReturn(existingUser);

    BaseException exception =
        assertThrows(BaseException.class, () -> userService.updateUser(request, userId));

    assertEquals(MessageConstant.MASTER_IS_ONE, exception.getMessage());
  }

  @Test
  @DisplayName("1.3 addUser 当 roleId 为 null 时抛出 ROLE_NOT_EXISTS 既有行为回归")
  void shouldRejectAddUserWhenRoleIdIsNull() {
    UserDTO request = new UserDTO();
    request.setRoleId(null);

    BaseException exception = assertThrows(BaseException.class, () -> userService.addUser(request));

    assertEquals(ErrorCode.ROLE_NOT_EXISTS.getMessage(), exception.getMessage());
  }

  @Test
  @DisplayName("1.4 updateUser 仅传 realName 且 roleId=null 正常通过不触发校验")
  void shouldAllowUpdateUserWhenRoleIdIsNull() {
    Long userId = 99L;
    UserDTO request = new UserDTO();
    request.setRealName("新名字");
    request.setRoleId(null);

    UserEntity existingUser = new UserEntity();
    existingUser.setId(userId);
    when(userMapper.selectById(userId)).thenReturn(existingUser);
    when(userMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

    userService.updateUser(request, userId);

    verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
  }
}
