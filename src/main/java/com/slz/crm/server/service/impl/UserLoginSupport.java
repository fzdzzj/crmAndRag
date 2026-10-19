package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.PasswordHashUtil;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.support.LoginAttemptService;

/** 用户登录辅助逻辑（harden-login-security H-10）。 拆自 UserServiceImpl 以收敛类与方法圈复杂度与 NCSS。 */
final class UserLoginSupport {

  private UserLoginSupport() {}

  static void checkLocked(LoginAttemptService attemptService, String email) {
    if (attemptService != null) {
      attemptService.checkLocked(email);
    }
  }

  static void verifyCredentials(
      UserEntity user, String rawPassword, String email, LoginAttemptService attemptService) {
    if (user == null || !PasswordHashUtil.matches(rawPassword, user.getPassword())) {
      int remaining =
          (attemptService != null)
              ? attemptService.recordFailure(email)
              : LoginAttemptService.MAX_ATTEMPTS;
      if (remaining <= 0) {
        throw new BaseException(
            ErrorCode.USER_IS_LOCK,
            String.format("登录失败次数过多，账号已被锁定 %d 分钟", LoginAttemptService.LOCK_DURATION.toMinutes()));
      }
      throw new BaseException(
          ErrorCode.PASSWORD_OR_EMAIL_ERROR, String.format("密码或邮箱错误，还剩 %d 次重试机会", remaining));
    }
    if (attemptService != null) {
      attemptService.loginSucceeded(email);
    }
  }

  static void upgradeLegacyPassword(UserEntity user, String rawPassword, UserMapper userMapper) {
    if (PasswordHashUtil.isMd5(user.getPassword())) {
      UserEntity upgrade = new UserEntity();
      upgrade.setId(user.getId());
      upgrade.setPassword(PasswordHashUtil.hash(rawPassword));
      userMapper.updateById(upgrade);
    }
  }

  static void validateUserRole(UserEntity user, RoleMapper roleMapper) {
    if (user.getRoleId() != null && user.getRoleId() != 0) {
      RoleEntity role = roleMapper.selectById(user.getRoleId());
      if (role == null || Boolean.TRUE.equals(role.getIsDeleted())) {
        throw new BaseException(ErrorCode.ROLE_NOT_EXISTS, "该用户角色已被删除，无法登录，请联系管理员");
      }
    }
  }
}
