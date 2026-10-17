package com.slz.crm.server.service.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * JWT 登出黑名单管理服务（harden-login-security H-9）。
 *
 * <p>契合单副本部署契约：复用 Caffeine 本地缓存，维护主动登出 Token 黑名单。 默认 TTL 1 小时（与系统 JWT 过期时间一致），过期自动驱逐。
 */
@Service
public class TokenBlacklistService {

  private static final Duration DEFAULT_TTL = Duration.ofHours(1);

  private final Cache<String, Boolean> blacklist;

  public TokenBlacklistService() {
    this(DEFAULT_TTL);
  }

  public TokenBlacklistService(Duration ttl) {
    this.blacklist = Caffeine.newBuilder().expireAfterWrite(ttl).build();
  }

  /**
   * 将 Token 记录至登出黑名单。
   *
   * @param token 待作废的 JWT Token
   */
  public void addToBlacklist(String token) {
    if (token != null && !token.isBlank()) {
      blacklist.put(token, Boolean.TRUE);
    }
  }

  /**
   * 判断 Token 是否处于登出黑名单中。
   *
   * @param token JWT Token
   * @return true 若已被主动登出废止
   */
  public boolean isBlacklisted(String token) {
    final boolean blacklisted;
    if (token == null || token.isBlank()) {
      blacklisted = false;
    } else {
      blacklisted = Boolean.TRUE.equals(blacklist.getIfPresent(token));
    }
    return blacklisted;
  }

  /** 清空黑名单（测试辅助）。 */
  public void clear() {
    blacklist.invalidateAll();
  }
}
