package com.slz.crm.platform.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 记忆旁路执行器的拒绝降级与 MDC 传播测试。 */
class MemoryBypassTaskExecutorTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void cleanContext() {
    MDC.clear();
  }

  @Test
  void shouldPropagateMdcWhenSubmitted() throws Exception {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor("memory-bypass-test", 1, 1, 10, 1000, "abort", registry);
    MDC.put("traceId", "trace-123456");
    AtomicReference<String> traceId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    MemoryBypassTaskExecutor bypassExecutor = new MemoryBypassTaskExecutor(executor, registry);

    assertThat(
            bypassExecutor.tryExecute(
                () -> {
                  traceId.set(MDC.get("traceId"));
                  latch.countDown();
                }))
        .isTrue();
    assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

    assertThat(traceId.get()).isEqualTo("trace-123456");
    executor.shutdown();
  }

  @Test
  void shouldReturnFalseWhenExecutorIsSaturated() throws Exception {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor("memory-bypass-test", 1, 1, 1, 1000, "abort", registry);
    MemoryBypassTaskExecutor bypassExecutor = new MemoryBypassTaskExecutor(executor, registry);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1);

    assertThat(
            bypassExecutor.tryExecute(
                () -> {
                  running.countDown();
                  awaitRelease(release);
                }))
        .isTrue();
    assertThat(bypassExecutor.tryExecute(queued::countDown)).isTrue();
    assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(bypassExecutor.tryExecute(() -> {})).isFalse();

    assertThat(
            registry
                .find("platform.memory.bypass.rejected")
                .tag("executor", "memory-bypass")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
    release.countDown();
    assertThat(queued.await(1, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();
  }

  private void awaitRelease(CountDownLatch latch) {
    try {
      latch.await(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
