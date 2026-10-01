package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.PasswordHashUtil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.UserServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;

/**
 * 用户登录服务测试。
 *
 * @author fmz
 * @since 2026/08/02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户登录服务")
class UserServiceTest {

  private static final String TEST_JWT_SECRET = "unit-test-secret-key-0123456789abcdef";

  @Mock private UserMapper userMapper;

  @Mock private RoleMapper roleMapper;

  @Mock private SysDeptMapper sysDeptMapper;

  @Mock private DataConvertService dataConvertService;

  @Mock private JwtProperties jwtProperties;

  @InjectMocks private UserServiceImpl userService;

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("新增用户时，应保留所选的初始账户状态")
  void shouldPersistRequestedStatusWhenAddingUser() {
    authenticateAs(9001L);
    UserDTO request = validAddUserRequest();
    request.setStatus(1);
    lenient()
        .when(roleMapper.selectById(request.getRoleId()))
        .thenReturn(validRole(request.getRoleId()));
    when(sysDeptMapper.selectById(request.getDeptId())).thenReturn(activeDept(request.getDeptId()));
    when(userMapper.insert(any(UserEntity.class))).thenReturn(1);

    userService.addUser(request);

    ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).insert(userCaptor.capture());
    assertEquals(1, userCaptor.getValue().getStatus());
  }

  @Test
  @DisplayName("新增用户时，初始密码应写入 BCrypt 密文")
  void shouldStoreBcryptHashWhenAddingUser() {
    authenticateAs(9001L);
    UserDTO request = validAddUserRequest();
    lenient()
        .when(roleMapper.selectById(request.getRoleId()))
        .thenReturn(validRole(request.getRoleId()));
    when(sysDeptMapper.selectById(request.getDeptId())).thenReturn(activeDept(request.getDeptId()));
    when(userMapper.insert(any(UserEntity.class))).thenReturn(1);

    userService.addUser(request);

    ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).insert(userCaptor.capture());
    String storedPassword = userCaptor.getValue().getPassword();
    assertTrue(PasswordHashUtil.isBcrypt(storedPassword));
    assertTrue(PasswordHashUtil.matches("crmjane", storedPassword));
  }

  @Test
  @DisplayName("BCrypt 用户凭正确密码登录成功并签发 Token")
  void shouldLoginBcryptUserWithCorrectPassword() {
    UserEntity user = existingUserWithPassword(PasswordHashUtil.hash("admin123"));
    when(userMapper.selectOne(any())).thenReturn(user);
    when(jwtProperties.getSecretKey()).thenReturn(TEST_JWT_SECRET);
    when(jwtProperties.getTtl()).thenReturn(3_600_000L);

    String token = userService.login(loginRequest("admin123"));

    assertNotNull(token);
  }

  @Test
  @DisplayName("历史 MD5 用户登录成功且密文被透明升级为 BCrypt")
  void shouldUpgradeLegacyMd5PasswordToBcryptAfterLogin() {
    UserEntity user = existingUserWithPassword(DigestUtils.md5DigestAsHex("admin123".getBytes()));
    when(userMapper.selectOne(any())).thenReturn(user);
    when(userMapper.updateById(any(UserEntity.class))).thenReturn(1);
    when(jwtProperties.getSecretKey()).thenReturn(TEST_JWT_SECRET);
    when(jwtProperties.getTtl()).thenReturn(3_600_000L);

    String token = userService.login(loginRequest("admin123"));

    assertNotNull(token);
    ArgumentCaptor<UserEntity> upgradeCaptor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).updateById(upgradeCaptor.capture());
    assertEquals(user.getId(), upgradeCaptor.getValue().getId());
    String upgradedPassword = upgradeCaptor.getValue().getPassword();
    assertTrue(PasswordHashUtil.isBcrypt(upgradedPassword));
    assertTrue(PasswordHashUtil.matches("admin123", upgradedPassword));
  }

  @Test
  @DisplayName("密码错误时登录失败并抛出密码或邮箱错误")
  void shouldRejectLoginWhenPasswordDoesNotMatch() {
    UserEntity user = existingUserWithPassword(PasswordHashUtil.hash("correct-password"));
    when(userMapper.selectOne(any())).thenReturn(user);

    BaseException exception =
        assertThrows(BaseException.class, () -> userService.login(loginRequest("wrong-password")));

    assertEquals(ErrorCode.PASSWORD_OR_EMAIL_ERROR.getMessage(), exception.getMessage());
  }

  @Test
  @DisplayName("邮箱不存在时登录失败并抛出密码或邮箱错误")
  void shouldRejectLoginWhenEmailNotFound() {
    when(userMapper.selectOne(any())).thenReturn(null);

    assertThrows(BaseException.class, () -> userService.login(loginRequest("admin123")));
  }

  private UserEntity existingUserWithPassword(String storedPassword) {
    UserEntity user = new UserEntity();
    user.setId(77L);
    user.setEmail("jane.doe@example.com");
    user.setPassword(storedPassword);
    user.setRoleId(0L);
    user.setStatus(0);
    return user;
  }

  private UserDTO loginRequest(String rawPassword) {
    UserDTO request = new UserDTO();
    request.setEmail("jane.doe@example.com");
    request.setPassword(
        Base64.getEncoder().encodeToString(rawPassword.getBytes(StandardCharsets.UTF_8)));
    return request;
  }

  private UserDTO validAddUserRequest() {
    UserDTO request = new UserDTO();
    request.setRealName("Jane Doe");
    request.setEmail("jane.doe@example.com");
    request.setPhone("13800138000");
    request.setDeptId(11L);
    request.setRoleId(8L);
    request.setStatus(1);
    request.setPassword("client-controlled-password");
    return request;
  }

  private RoleEntity validRole(Long roleId) {
    RoleEntity role = new RoleEntity();
    role.setId(roleId);
    role.setIsDeleted(false);
    return role;
  }

  private SysDeptEntity activeDept(Long deptId) {
    SysDeptEntity dept = new SysDeptEntity();
    dept.setId(deptId);
    dept.setStatus(1);
    return dept;
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
