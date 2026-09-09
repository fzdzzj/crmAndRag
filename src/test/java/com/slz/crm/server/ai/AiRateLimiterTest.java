package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Deque;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiRateLimiter 单测：滑动窗口通过/拒绝/滑动恢复/多用户隔离
 */
class AiRateLimiterTest {

    private AiRateLimiter limiter;

    private final long[] now = {1_000_000L};

    @BeforeEach
    void setUp() {
        limiter = new AiRateLimiter();
        AiProperties properties = new AiProperties();
        properties.setRateLimitPerMinute(3);
        ReflectionTestUtils.setField(limiter, "aiProperties", properties);
        limiter.setClock(() -> now[0]);
    }

    @Test
    void windowNotFull_shouldAllow() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void overLimit_shouldReject() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(1L)).isTrue();
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();
        // 拒绝不消耗配额：继续拒绝
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void windowSlides_shouldRecover() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(1L)).isTrue();
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();
        // 时间推进超过 60 秒窗口，旧请求滑出
        now[0] += 61_000L;
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void usersAreIsolated() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();
        // 用户 2 不受用户 1 限流影响
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    void nullUserId_shouldAlwaysAllow() {
        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }

    @Test
    void idleUserWindow_cleanedUpLazily() {
        limiter.tryAcquire(1L);
        assertThat(windowSize()).isEqualTo(1);

        now[0] += 61_000L;
        limiter.tryAcquire(2L);

        assertThat(windowSize()).isEqualTo(1);
    }

    @Test
    void windowSlides_ownerEntryReused() {
        limiter.tryAcquire(1L);
        now[0] += 61_000L;

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(windowSize()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private int windowSize() {
        return ((ConcurrentMap<Long, Deque<Long>>) ReflectionTestUtils.getField(limiter, "windows")).size();
    }
}
