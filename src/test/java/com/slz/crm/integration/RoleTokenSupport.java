package com.slz.crm.integration;

import com.slz.crm.common.untils.JwtUntil;

import java.util.Map;

/**
 * 多角色回归测试 Token 工具。
 *
 * <p>复用测试 JWT 密钥（application-test.yml 的 slz.jwt.secret-key）。</p>
 */
public final class RoleTokenSupport {

    private static final String JWT_SECRET = "test-secret-key-for-e2e-testing-12345678";
    private static final long JWT_TTL_MS = 3600_000L;

    private RoleTokenSupport() {
    }

    public static String tokenOf(TestRole role) {
        return JwtUntil.createJWT(JWT_SECRET, JWT_TTL_MS, Map.of("userID", role.getUserId()));
    }
}
