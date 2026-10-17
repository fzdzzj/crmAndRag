package com.slz.crm.server.service.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录防爆破限流服务（harden-login-security H-10）。
 *
 * <p>契合单副本部署契约，使用 Caffeine 本地缓存，维护 Email 账号与客户端 IP 双维度试错计数器。 策略：连续 5 次失败锁定 15
 * 分钟，锁定期间阻断登录并提示剩余时间；登录成功时清除计数。
 */
@Service
public class LoginAttemptService {

  public static final int MAX_ATTEMPTS = 5;
  public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  private final Clock clock;
  private final Cache<String, AttemptRecord> attemptCache;

  public LoginAttemptService() {
    this(Clock.systemDefaultZone(), LOCK_DURATION);
  }

  public LoginAttemptService(Clock clock, Duration lockDuration) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.attemptCache = Caffeine.newBuilder().expireAfterWrite(lockDuration).build();
  }

  public static class AttemptRecord {
    private final int failureCount;
    private final long lockedUntilEpochMilli;

    public AttemptRecord(int failureCount, long lockedUntilEpochMilli) {
      this.failureCount = failureCount;
      this.lockedUntilEpochMilli = lockedUntilEpochMilli;
    }

    public int getFailureCount() {
      return failureCount;
    }

    public long getLockedUntilEpochMilli() {
      return lockedUntilEpochMilli;
    }

    public boolean isLocked(long now) {
      return lockedUntilEpochMilli > now;
    }
  }

  /**
   * 检查指定账号与当前客户端 IP 是否处于锁定状态。若锁定，抛出业务异常阻断。
   *
   * @param email 用户邮箱
   */
  public void checkLocked(String email) {
    checkLocked(email, resolveClientIp());
  }

  /**
   * 检查指定账号与指定 IP 是否处于锁定状态。
   *
   * @param email 用户邮箱
   * @param ip 客户端 IP
   */
  public void checkLocked(String email, String ip) {
    long now = clock.millis();

    if (StringUtils.hasText(email)) {
      AttemptRecord emailRecord = attemptCache.getIfPresent(emailKey(email));
      if (emailRecord != null && emailRecord.isLocked(now)) {
        long remainingMinutes =
            calculateRemainingMinutes(emailRecord.getLockedUntilEpochMilli(), now);
        throw new BaseException(
            ErrorCode.USER_IS_LOCK, String.format("登录失败次数过多，账号已被锁定，请在 %d 分钟后重试", remainingMinutes));
      }
    }

    if (isValidIp(ip)) {
      AttemptRecord ipRecord = attemptCache.getIfPresent(ipKey(ip));
      if (ipRecord != null && ipRecord.isLocked(now)) {
        long remainingMinutes = calculateRemainingMinutes(ipRecord.getLockedUntilEpochMilli(), now);
        throw new BaseException(
            ErrorCode.USER_IS_LOCK,
            String.format("登录失败次数过多，当前 IP 已被锁定，请在 %d 分钟后重试", remainingMinutes));
      }
    }
  }

  /**
   * 记录一次登录失败，累加计数器；若达到 5 次则锁定 15 分钟。
   *
   * @param email 用户邮箱
   * @return 账号维度的剩余尝试次数（若已锁定或超限返回 0）
   */
  public int recordFailure(String email) {
    return recordFailure(email, resolveClientIp());
  }

  /**
   * 记录一次登录失败（指定 IP）。
   *
   * @param email 用户邮箱
   * @param ip 客户端 IP
   * @return 账号维度的剩余尝试次数（若已锁定或超限返回 0）
   */
  public int recordFailure(String email, String ip) {
    long now = clock.millis();

    int emailRemaining = MAX_ATTEMPTS;
    if (StringUtils.hasText(email)) {
      emailRemaining = recordFailureForKey(emailKey(email), now);
    }

    if (isValidIp(ip)) {
      recordFailureForKey(ipKey(ip), now);
    }

    return emailRemaining;
  }

  private int recordFailureForKey(String key, long now) {
    AttemptRecord existing = attemptCache.getIfPresent(key);
    int newCount = (existing == null) ? 1 : existing.getFailureCount() + 1;
    long lockedUntil = 0;
    if (newCount >= MAX_ATTEMPTS) {
      lockedUntil = now + LOCK_DURATION.toMillis();
    }
    attemptCache.put(key, new AttemptRecord(newCount, lockedUntil));
    return Math.max(0, MAX_ATTEMPTS - newCount);
  }

  /**
   * 登录成功，清除账号与当前客户端 IP 的试错计数。
   *
   * @param email 用户邮箱
   */
  public void loginSucceeded(String email) {
    loginSucceeded(email, resolveClientIp());
  }

  /**
   * 登录成功，清除账号与指定客户端 IP 的试错计数。
   *
   * @param email 用户邮箱
   * @param ip 客户端 IP
   */
  public void loginSucceeded(String email, String ip) {
    if (StringUtils.hasText(email)) {
      attemptCache.invalidate(emailKey(email));
    }
    if (isValidIp(ip)) {
      attemptCache.invalidate(ipKey(ip));
    }
  }

  /** 清空全部计数（测试辅助）。 */
  public void clear() {
    attemptCache.invalidateAll();
  }

  private long calculateRemainingMinutes(long lockedUntilEpochMilli, long now) {
    long remainingMillis = Math.max(0, lockedUntilEpochMilli - now);
    long remainingSeconds = remainingMillis / 1000;
    return Math.max(1, (remainingSeconds + 59) / 60);
  }

  private String emailKey(String email) {
    return "email:" + email.trim().toLowerCase();
  }

  private String ipKey(String ip) {
    return "ip:" + ip.trim();
  }

  private boolean isValidIp(String ip) {
    return StringUtils.hasText(ip) && !"UNKNOWN".equalsIgnoreCase(ip.trim());
  }

  private String resolveClientIp() {
    String clientIp = "UNKNOWN";
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      String forwarded = request.getHeader("X-Forwarded-For");
      if (StringUtils.hasText(forwarded)) {
        clientIp = forwarded.split(",")[0].trim();
      } else {
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
          clientIp = realIp.trim();
        } else {
          clientIp = request.getRemoteAddr();
        }
      }
    }
    return clientIp;
  }
}
