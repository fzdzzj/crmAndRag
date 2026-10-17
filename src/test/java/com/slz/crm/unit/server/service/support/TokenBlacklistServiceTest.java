package com.slz.crm.unit.server.service.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.server.service.support.TokenBlacklistService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 登出 Token 黑名单服务测试（harden-login-security H-9）。 */
@DisplayName("TokenBlacklistService 黑名单与过期测试")
class TokenBlacklistServiceTest {

  private TokenBlacklistService blacklistService;

  @BeforeEach
  void setUp() {
    blacklistService = new TokenBlacklistService();
  }

  @Test
  @DisplayName("未加入黑名单的 Token 校验应返回 false")
  void shouldReturnFalseForNonBlacklistedToken() {
    assertFalse(blacklistService.isBlacklisted("some-jwt-token"));
    assertFalse(blacklistService.isBlacklisted(null));
    assertFalse(blacklistService.isBlacklisted("   "));
  }

  @Test
  @DisplayName("加入黑名单后应能立即检测到已黑名单")
  void shouldDetectBlacklistedToken() {
    String token = "jwt-token-to-invalidate-12345";
    blacklistService.addToBlacklist(token);

    assertTrue(blacklistService.isBlacklisted(token));
  }

  @Test
  @DisplayName("清空黑名单后所有记录被清除")
  void shouldClearBlacklist() {
    String token1 = "token-1";
    String token2 = "token-2";
    blacklistService.addToBlacklist(token1);
    blacklistService.addToBlacklist(token2);

    blacklistService.clear();

    assertFalse(blacklistService.isBlacklisted(token1));
    assertFalse(blacklistService.isBlacklisted(token2));
  }

  @Test
  @DisplayName("超过 TTL 后黑名单应自动过期失效")
  void shouldExpireAfterTtl() throws InterruptedException {
    TokenBlacklistService shortLivedService = new TokenBlacklistService(Duration.ofMillis(50));
    String token = "short-lived-token";
    shortLivedService.addToBlacklist(token);

    assertTrue(shortLivedService.isBlacklisted(token));

    Thread.sleep(70);

    assertFalse(shortLivedService.isBlacklisted(token));
  }
}
