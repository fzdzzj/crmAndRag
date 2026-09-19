package com.slz.crm.integration;

import com.slz.crm.common.untils.JwtUntil;
import java.util.Map;

/**
 * 多角色回归测试 Token 工具。
 *
 * <p>复用测试 JWT 密钥（application-test.yml 的 slz.jwt.secret-key）。
 */
public final class RoleTokenSupport {

  private static final String JWT_SECRET = "test-secret-key-for-e2e-testing-12345678";
  private static final long JWT_TTL_MS = 3600_000L;

  private RoleTokenSupport() {}

  public static String tokenOf(TestRole role) {
    return tokenOfUserId(role.getUserId());
  }

  /**
   * 为任意用户ID构造登录 token（apply-permission-matrix 任务 2.3/4.2：in-test seeding 的 非超管角色其 userID 未必落在
   * {@link TestRole} 枚举内，故开放任意 userID 构造）。
   */
  public static String tokenOfUserId(Long userId) {
    return JwtUntil.createJWT(JWT_SECRET, JWT_TTL_MS, Map.of("userID", userId));
  }
}
