package com.slz.crm.unit.server.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.server.service.support.LoginAttemptService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 登录防爆破限流服务测试（harden-login-security H-10）。 */
@DisplayName("LoginAttemptService 双维度防爆破限流测试")
class LoginAttemptServiceTest {

  private TestClock testClock;
  private LoginAttemptService attemptService;

  @BeforeEach
  void setUp() {
    testClock = new TestClock(Instant.now(), ZoneId.systemDefault());
    attemptService = new LoginAttemptService(testClock, Duration.ofMinutes(15));
  }

  @Test
  @DisplayName("未达到阈值（1-4次）失败时递减剩余次数且不锁定")
  void shouldDecrementRemainingAttemptsWithoutLocking() {
    String email = "test@example.com";
    String ip = "192.168.1.10";

    attemptService.checkLocked(email, ip);

    assertEquals(4, attemptService.recordFailure(email, ip));
    attemptService.checkLocked(email, ip);

    assertEquals(3, attemptService.recordFailure(email, ip));
    assertEquals(2, attemptService.recordFailure(email, ip));
    assertEquals(1, attemptService.recordFailure(email, ip));

    attemptService.checkLocked(email, ip);
  }

  @Test
  @DisplayName("连续失败 5 次后账号被锁定 15 分钟并抛出 USER_IS_LOCK 异常")
  void shouldLockAccountAfterFiveFailures() {
    String email = "victim@example.com";
    String ip = "192.168.1.20";

    for (int i = 0; i < 4; i++) {
      attemptService.recordFailure(email, ip);
    }
    assertEquals(0, attemptService.recordFailure(email, ip));

    BaseException ex =
        assertThrows(BaseException.class, () -> attemptService.checkLocked(email, ip));
    assertEquals(ErrorCode.USER_IS_LOCK.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("账号已被锁定"));
    assertTrue(ex.getMessage().contains("15 分钟"));
  }

  @Test
  @DisplayName("同一 IP 针对不同账号试错 5 次后触发 IP 锁定")
  void shouldLockIpAfterFiveFailuresAcrossDifferentAccounts() {
    String ip = "10.0.0.99";

    for (int i = 1; i <= 5; i++) {
      attemptService.recordFailure("user" + i + "@example.com", ip);
    }

    BaseException ex =
        assertThrows(
            BaseException.class,
            () -> attemptService.checkLocked("brand-new-user@example.com", ip));
    assertEquals(ErrorCode.USER_IS_LOCK.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("当前 IP 已被锁定"));
  }

  @Test
  @DisplayName("登录成功后清空失败计数，后续失败重新从 5 次起算")
  void shouldClearCountOnLoginSuccess() {
    String email = "success@example.com";
    String ip = "192.168.1.30";

    attemptService.recordFailure(email, ip);
    attemptService.recordFailure(email, ip);
    attemptService.recordFailure(email, ip);

    attemptService.loginSucceeded(email, ip);

    assertEquals(4, attemptService.recordFailure(email, ip));
  }

  @Test
  @DisplayName("锁定 15 分钟到期后应自动解除锁定")
  void shouldAutomaticallyUnlockAfterFifteenMinutes() {
    String email = "expire@example.com";
    String ip = "192.168.1.40";

    for (int i = 0; i < 5; i++) {
      attemptService.recordFailure(email, ip);
    }

    assertThrows(BaseException.class, () -> attemptService.checkLocked(email, ip));

    // 快进时间 15 分钟 1 秒
    testClock.advance(Duration.ofMinutes(15).plusSeconds(1));

    attemptService.checkLocked(email, ip);
  }

  private static class TestClock extends Clock {
    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    TestClock(Instant initialInstant, ZoneId zone) {
      this.currentInstant = new AtomicReference<>(initialInstant);
      this.zone = zone;
    }

    void advance(Duration duration) {
      currentInstant.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new TestClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
      return currentInstant.get();
    }
  }
}
