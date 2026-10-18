package com.slz.crm.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.PasswordHashUtil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ResetUserPasswordDTO;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.UserServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 管理员重置密码防篡改专项测试（harden-security-attachment-reset-and-invoice 任务 2.3，纯 JVM Mockito）。
 *
 * <p>覆盖：非超管本人重置终极管理员（roleId=1）密码必须 12002 拦截且零 SQL；超管本人与普通用户 重置正向放行且落库密文可用 {@code
 * PasswordHashUtil.matches} 验证；空密码拦截；目标 用户缺失拦截；{@code ResetUserPasswordDTO} Jakarta 校验注解语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResetPasswordSecurityTest 重置密码防篡改专项测试")
class UserResetPasswordSecurityTest {

  private static final Long SUPER_ADMIN_ID = 1L;
  private static final Long OPERATOR_ID = 42L;
  private static final Long NORMAL_TARGET_ID = 7L;

  /** 既有 String 构造的缺失用户消息（ID_NOT_EXISTS 模板格式化结果）。 */
  private static final String ID_NOT_EXISTS_MESSAGE = "【用户】不存在";

  @Mock private UserMapper userMapper;

  @InjectMocks private UserServiceImpl userService;

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("1. 非超管本人重置超管密码：12002 拦截且零 SQL（密码未被修改）")
  void nonSuperAdminResettingSuperAdminPasswordDenied() {
    setCurrentUser(OPERATOR_ID, 2L);
    when(userMapper.selectById(SUPER_ADMIN_ID)).thenReturn(userOf(SUPER_ADMIN_ID, 1L));

    BaseException exception =
        assertThrows(
            BaseException.class,
            () -> userService.resetUserPassword(SUPER_ADMIN_ID, "NewPass@123"));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), exception.getCode());
    assertEquals("仅超级管理员本人可重置超级管理员密码", exception.getMessage());
    verify(userMapper, never()).updateById(any(UserEntity.class));
  }

  @Test
  @DisplayName("2. 超管本人重置自己的密码：正向放行且密文可校验")
  void superAdminResetsOwnPasswordAllowed() {
    setCurrentUser(SUPER_ADMIN_ID, 1L);
    when(userMapper.selectById(SUPER_ADMIN_ID)).thenReturn(userOf(SUPER_ADMIN_ID, 1L));
    when(userMapper.updateById(any(UserEntity.class))).thenReturn(1);

    assertTrue(userService.resetUserPassword(SUPER_ADMIN_ID, "NewPass@123"));

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).updateById(captor.capture());
    assertEquals(SUPER_ADMIN_ID, captor.getValue().getId());
    assertTrue(
        PasswordHashUtil.matches("NewPass@123", captor.getValue().getPassword()),
        "落库密码必须是可校验的哈希密文");
  }

  @Test
  @DisplayName("3. 管理员重置普通用户密码：正向放行且密文可校验")
  void adminResetsNormalUserAllowed() {
    setCurrentUser(OPERATOR_ID, 2L);
    when(userMapper.selectById(NORMAL_TARGET_ID)).thenReturn(userOf(NORMAL_TARGET_ID, 2L));
    when(userMapper.updateById(any(UserEntity.class))).thenReturn(1);

    assertTrue(userService.resetUserPassword(NORMAL_TARGET_ID, "NewPass@456"));

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).updateById(captor.capture());
    assertTrue(PasswordHashUtil.matches("NewPass@456", captor.getValue().getPassword()));
  }

  @Test
  @DisplayName("4. 空密码/空白密码：拦截且零 SQL（既有 String 构造，断言消息而非错误码）")
  void blankNewPasswordRejected() {
    setCurrentUser(OPERATOR_ID, 2L);
    when(userMapper.selectById(NORMAL_TARGET_ID)).thenReturn(userOf(NORMAL_TARGET_ID, 2L));

    BaseException nullPassword =
        assertThrows(
            BaseException.class, () -> userService.resetUserPassword(NORMAL_TARGET_ID, null));
    assertEquals(ErrorCode.PARAM_EMPTY.getMessage(), nullPassword.getMessage());

    BaseException blankPassword =
        assertThrows(
            BaseException.class, () -> userService.resetUserPassword(NORMAL_TARGET_ID, "   "));
    assertEquals(ErrorCode.PARAM_EMPTY.getMessage(), blankPassword.getMessage());

    verify(userMapper, never()).updateById(any(UserEntity.class));
  }

  @Test
  @DisplayName("5. 目标用户不存在：拦截且零 SQL（既有 String 构造，断言消息而非错误码）")
  void missingTargetUserRejected() {
    setCurrentUser(OPERATOR_ID, 2L);
    when(userMapper.selectById(NORMAL_TARGET_ID)).thenReturn(null);

    BaseException exception =
        assertThrows(
            BaseException.class,
            () -> userService.resetUserPassword(NORMAL_TARGET_ID, "NewPass@123"));

    assertEquals(ID_NOT_EXISTS_MESSAGE, exception.getMessage());
    verify(userMapper, never()).updateById(any(UserEntity.class));
  }

  @Test
  @DisplayName("6. DTO 校验注解：userId 为空与 newPassword 空白均产生违规，合法请求零违规")
  void resetPasswordDtoRejectsMissingUserIdAndBlankPassword() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    ResetUserPasswordDTO missingUserId = new ResetUserPasswordDTO();
    missingUserId.setNewPassword("NewPass@123");
    Set<jakarta.validation.ConstraintViolation<ResetUserPasswordDTO>> userIdViolations =
        validator.validate(missingUserId);
    assertTrue(
        userIdViolations.stream().anyMatch(v -> "目标用户ID不能为空".equals(v.getMessage())),
        "userId 为空必须产生「目标用户ID不能为空」违规");

    ResetUserPasswordDTO blankPassword = new ResetUserPasswordDTO();
    blankPassword.setUserId(NORMAL_TARGET_ID);
    blankPassword.setNewPassword("  ");
    Set<jakarta.validation.ConstraintViolation<ResetUserPasswordDTO>> passwordViolations =
        validator.validate(blankPassword);
    assertTrue(
        passwordViolations.stream().anyMatch(v -> "新密码不能为空".equals(v.getMessage())),
        "newPassword 空白必须产生「新密码不能为空」违规");

    ResetUserPasswordDTO valid = new ResetUserPasswordDTO();
    valid.setUserId(NORMAL_TARGET_ID);
    valid.setNewPassword("NewPass@123");
    assertTrue(validator.validate(valid).isEmpty(), "合法请求不得产生任何校验违规");
  }

  private void setCurrentUser(Long userId, Long roleId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    role.setRoleId(roleId);
    BaseUnit.setCurrentRole(role);
  }

  private UserEntity userOf(Long id, Long roleId) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRoleId(roleId);
    user.setStatus(1);
    return user;
  }
}
