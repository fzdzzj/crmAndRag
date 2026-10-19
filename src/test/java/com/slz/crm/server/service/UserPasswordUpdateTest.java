package com.slz.crm.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.PasswordHashUtil;
import com.slz.crm.pojo.dto.UserUpdatePasswordDTO;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.UserServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 自服务改密旧密校验专项测试（harden-user-password-and-privacy 任务 3.2，纯 JVM Mockito）。
 *
 * <p>覆盖：正确旧密码时成功更新为新哈希、错误旧密码拦截（92028/密码错误且不发更新 SQL）、 新旧密码相同拦截。经 {@code
 * UserServiceImpl#updatePassword} 委托 {@code UserPasswordSupport} 的真实链路断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserPasswordUpdateTest 自服务改密旧密校验专项测试")
class UserPasswordUpdateTest {

  private static final Long CURRENT_USER_ID = 42L;
  private static final String OLD_RAW_PASSWORD = "oldPass123";
  private static final String NEW_RAW_PASSWORD = "newPass456";

  @Mock private UserMapper userMapper;

  @Captor private ArgumentCaptor<UserEntity> userCaptor;

  @InjectMocks private UserServiceImpl userService;

  private UserEntity storedUser;

  @BeforeEach
  void setUp() {
    storedUser = new UserEntity();
    storedUser.setId(CURRENT_USER_ID);
    storedUser.setPassword(PasswordHashUtil.hash(OLD_RAW_PASSWORD));
  }

  private UserUpdatePasswordDTO dtoOf(String rawOld, String rawNew) {
    UserUpdatePasswordDTO dto = new UserUpdatePasswordDTO();
    dto.setOldPassword(base64(rawOld));
    dto.setNewPassword(base64(rawNew));
    return dto;
  }

  private static String base64(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("1. 正确旧密码：新哈希落库且可校验通过、不落明文")
  void shouldUpdatePasswordHashWhenOldPasswordMatches() {
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(storedUser);
    when(userMapper.updateById(any(UserEntity.class))).thenReturn(1);

    boolean updated =
        userService.updatePassword(dtoOf(OLD_RAW_PASSWORD, NEW_RAW_PASSWORD), CURRENT_USER_ID);

    assertTrue(updated);
    verify(userMapper).updateById(userCaptor.capture());
    UserEntity update = userCaptor.getValue();
    assertEquals(CURRENT_USER_ID, update.getId());
    assertNotEquals(NEW_RAW_PASSWORD, update.getPassword());
    assertTrue(PasswordHashUtil.matches(NEW_RAW_PASSWORD, update.getPassword()));
    assertTrue(PasswordHashUtil.isBcrypt(update.getPassword()));
  }

  @Test
  @DisplayName("2. 错误旧密码：抛 92028 密码错误且不发任何更新 SQL")
  void shouldRejectWhenOldPasswordWrong() {
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(storedUser);

    BaseException ex =
        assertThrows(
            BaseException.class,
            () ->
                userService.updatePassword(
                    dtoOf("wrongOld789", NEW_RAW_PASSWORD), CURRENT_USER_ID));

    assertEquals(ErrorCode.PASSWORD_ERROR.getCode(), ex.getCode());
    assertEquals("密码错误", ex.getMessage());
    verify(userMapper, never()).updateById(any(UserEntity.class));
  }

  @Test
  @DisplayName("3. 新旧密码相同：拦截且不发任何更新 SQL")
  void shouldRejectWhenNewPasswordSameAsOld() {
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(storedUser);

    BaseException ex =
        assertThrows(
            BaseException.class,
            () ->
                userService.updatePassword(
                    dtoOf(OLD_RAW_PASSWORD, OLD_RAW_PASSWORD), CURRENT_USER_ID));

    assertEquals("新密码不能与原密码相同", ex.getMessage());
    // fix-minor-debt-family 任务 1.3/1.5：单 String 构造器已收敛，code 由 null 变为 50002
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    verify(userMapper, never()).updateById(any(UserEntity.class));
  }
}
