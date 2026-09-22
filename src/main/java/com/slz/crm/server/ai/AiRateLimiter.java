package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI 请求限流器：按用户滑动窗口（60 秒），上限取 {@code crm.ai.rate-limit-per-minute}（默认 10）。 单实例部署用内存实现即可；超限由调用方返回
 * RATE_LIMITED，不消耗 LLM 调用。
 */
@Component
public class AiRateLimiter {

  /** 滑动窗口长度（毫秒） */
  private static final long WINDOW_MILLIS = 60_000L;

  /** 时间源（可注入，便于测试模拟窗口滑动） */
  private LongSupplier clock = System::currentTimeMillis;

  @Autowired private AiProperties aiProperties;

  /** userId → 窗口内的请求时间戳队列 */
  private final ConcurrentMap<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

  /** 尝试获取一次请求配额：窗口内未超限则放行并记录，超限则拒绝 */
  public boolean tryAcquire(Long userId) {
    boolean result = true;
    if (userId != null) {
      int limit =
          aiProperties.getRateLimitPerMinute() == null ? 10 : aiProperties.getRateLimitPerMinute();
      long now = clock.getAsLong();
      cleanupStaleWindows(now - WINDOW_MILLIS);
      boolean[] allowed = {false};
      windows.compute(
          userId,
          (key, queue) -> {
            if (queue == null) {
              queue = new ArrayDeque<>();
            }
            synchronized (queue) {
              long windowStart = now - WINDOW_MILLIS;
              while (!queue.isEmpty() && queue.peekFirst() <= windowStart) {
                queue.pollFirst();
              }
              if (queue.size() < limit) {
                queue.addLast(now);
                allowed[0] = true;
              }
            }
            return queue.isEmpty() ? null : queue;
          });
      result = allowed[0];
    }
    return result;
  }

  /** 惰性清理：滑动窗口外的空闲用户条目从映射中移除 */
  private void cleanupStaleWindows(long windowStart) {
    windows
        .entrySet()
        .removeIf(
            entry -> {
              Deque<Long> queue = entry.getValue();
              synchronized (queue) {
                while (!queue.isEmpty() && queue.peekFirst() <= windowStart) {
                  queue.pollFirst();
                }
                return queue.isEmpty();
              }
            });
  }

  /** 设置时间源（测试用） */
  void setClock(LongSupplier clock) {
    this.clock = clock;
  }
}
