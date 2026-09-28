package com.slz.crm.platform.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.trace.RequestTraceKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 平台线程池隔离、拒绝指标和上下文传播测试。 */
class PlatformAsyncConfigTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void cleanContext() {
    MDC.clear();
    UserContextHolder.clear();
  }

  @Test
  void shouldBuildBoundedExecutorWithConfiguredPolicy() {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor(
            "platform-batch-upload", 2, 4, 100, 60000, "discard-log", registry);

    assertThat(executor.getCorePoolSize()).isEqualTo(2);
    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
    assertThat(executor.getQueueCapacity()).isEqualTo(100);
  }

  @Test
  void shouldPropagateTraceIdAndUserContext() throws Exception {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor(
            "platform-memory-bypass", 2, 2, 64, 10000, "abort", registry);
    MDC.put(RequestTraceKey.TRACE_ID, "trace-123456");
    UserContext context = new UserContext(10L, 2L, 20L, DataScopeLevel.SELF, "tester");
    UserContextHolder.set(context);
    AtomicReference<String> traceId = new AtomicReference<>();
    AtomicReference<UserContext> userContext = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    executor.execute(
        () -> {
          traceId.set(MDC.get(RequestTraceKey.TRACE_ID));
          userContext.set(UserContextHolder.current());
          latch.countDown();
        });
    assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

    assertThat(traceId.get()).isEqualTo("trace-123456");
    assertThat(userContext.get()).isEqualTo(context);
    executor.shutdown();
  }

  @Test
  void shouldIncrementRejectionMetricWhenAbortPolicyRejects() throws Exception {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor("reject-test", 1, 1, 1, 1000, "abort", registry);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1);

    executor.execute(
        () -> {
          running.countDown();
          awaitRelease(release);
        });
    executor.execute(queued::countDown);
    assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
    try {
      executor.execute(() -> {});
    } catch (RuntimeException expected) {
      // abort 策略必须快速失败；这里只验证拒绝计数，不让测试被异常打断。
    }

    assertThat(
            registry
                .find("async.task.rejected")
                .tag("executor", "reject-test")
                .tag("policy", "abort")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
    release.countDown();
    assertThat(queued.await(1, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();
  }

  @Test
  void shouldBindExecutorMetrics() {
    ThreadPoolTaskExecutor executor =
        PlatformAsyncConfig.buildExecutor(
            "platform-memory-bypass", 2, 2, 64, 10000, "abort", registry);
    TaskExecutorMetricsBinder binder = new TaskExecutorMetricsBinder(registry, List.of(executor));

    binder.run(null);

    assertThat(registry.find("executor.queued").tag("name", "platform-memory-bypass").gauge())
        .isNotNull();
    executor.shutdown();
  }

  /** 卡 E：LLM 辅助池前缀必须 platform-llm-aux（Grafana 面板按 platform-* 选池），饱和必须 abort 快速失败并计入拒绝指标。 */
  @Test
  void llmAuxExecutorShouldUsePlatformPrefixAndAbortPolicy() throws Exception {
    ThreadPoolTaskExecutor executor =
        new PlatformAsyncConfig().llmAuxTaskExecutor(registry, 1, 1, 1, 1000);

    assertThat(executor.getThreadNamePrefix()).isEqualTo("platform-llm-aux-");

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1);
    executor.execute(
        () -> {
          running.countDown();
          awaitRelease(release);
        });
    // ThreadPoolExecutor 语义：队列未满先入队——先占满队列，下一个提交才会被 abort 拒绝
    executor.execute(queued::countDown);
    assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> executor.execute(() -> {}))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(
            registry
                .find("async.task.rejected")
                .tag("executor", "platform-llm-aux")
                .tag("policy", "abort")
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
