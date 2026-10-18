package com.slz.crm.server.service.support;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.PasswordHashUtil;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * 自服务改密支持逻辑（harden-user-password-and-privacy 任务 1.2）。
 *
 * <p>拆自 {@code UserServiceImpl} 以收敛 NCSS：核验原密码、拒绝新旧密码相同后哈希落库， 并输出安全审计日志。改密前必须先通过原密码校验，
 * 防止会话被劫持后静默接管账号。
 */
@Slf4j
public final class UserPasswordSupport {

  private UserPasswordSupport() {}

  /**
   * 自服务修改密码。
   *
   * @param userMapper 用户 Mapper
   * @param currentId 当前登录用户ID（上下文注入，不信任请求体身份字段）
   * @param encryptedOldPassword Base64 加密的原密码
   * @param encryptedNewPassword Base64 加密的新密码
   * @return 是否更新成功
   */
  public static boolean changePassword(
      UserMapper userMapper,
      Long currentId,
      String encryptedOldPassword,
      String encryptedNewPassword) {
    UserEntity user = userMapper.selectById(currentId);
    if (user == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
    }
    String decryptedOld = BaseUnit.decryptBase64(encryptedOldPassword);
    String decryptedNew = BaseUnit.decryptBase64(encryptedNewPassword);
    // 原密码核验失败抛 92028/密码错误，不泄露差异原因
    if (!PasswordHashUtil.matches(decryptedOld, user.getPassword())) {
      throw new BaseException(ErrorCode.PASSWORD_ERROR);
    }
    if (Objects.equals(decryptedOld, decryptedNew)) {
      throw new BaseException("新密码不能与原密码相同");
    }
    UserEntity update = new UserEntity();
    update.setId(currentId);
    update.setPassword(PasswordHashUtil.hash(decryptedNew));
    int updated = userMapper.updateById(update);
    if (updated == 0) {
      throw new ServiceException(ErrorCode.UPDATE_FAILED.getMessage());
    }
    log.info("用户自服务修改密码成功: userId={}", currentId);
    return true;
  }
}
