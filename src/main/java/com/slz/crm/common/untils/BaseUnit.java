package com.slz.crm.common.untils;

import com.slz.crm.pojo.ao.RoleAO;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

// TODO 角色测试
/** 线程工具类 */
public class BaseUnit {

  public static ThreadLocal<RoleAO> threadLocal = new ThreadLocal<>();

  /**
   * 设置当前角色
   *
   * @param role
   */
  public static void setCurrentRole(RoleAO role) {
    threadLocal.set(role);
  }

  /**
   * 获取当前角色
   *
   * @return
   */
  public static RoleAO getCurrentRole() {
    return threadLocal.get();
  }

  /**
   * 获取当前用户ID
   *
   * @return
   */
  public static Long getCurrentId() {
    return threadLocal.get().getId();
  }

  /** 删除当前角色 */
  public static void removeCurrentId() {
    threadLocal.remove();
  }

  /**
   * Base64 解密
   *
   * @param encryptedPassword Base64 加密后的密码
   * @return 解密后的明文密码
   */
  public static String decryptBase64(String encryptedPassword) {
    if (encryptedPassword == null || encryptedPassword.isEmpty()) {
      return encryptedPassword;
    }
    byte[] decodedBytes = Base64.getDecoder().decode(encryptedPassword);
    return new String(decodedBytes, StandardCharsets.UTF_8);
  }
}
