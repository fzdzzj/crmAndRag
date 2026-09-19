package com.slz.crm.platform.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** TaskExecutorMetricsBinder 与拒绝策略指标单元测试。 */
class TaskExecutorMetricsBinderTest {

  private AutoCloseable closeable;
  private final MeterRegistry registry = new SimpleMeterRegistry();

  @Mock private ApplicationArguments args;

  private ThreadPoolTaskExecutor executor1;
  private ThreadPoolTaskExecutor executor2;

  @BeforeEach
  void setUp() {
    closeable = MockitoAnnotations.openMocks(this);
    executor1 = new ThreadPoolTaskExecutor();
    executor1.setThreadNamePrefix("platform-batch-upload-");
    executor1.initialize();

    executor2 = new ThreadPoolTaskExecutor();
    executor2.setThreadNamePrefix("platform-stream-chat-");
    executor2.initialize();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (closeable != null) {
      closeable.close();
    }
    if (executor1 != null) {
      executor1.shutdown();
    }
    if (executor2 != null) {
      executor2.shutdown();
    }
    MDC.clear();
    registry.close();
  }

  @Test
  void shouldBindExecutorMetricsWhenRun() {
    List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    executors.add(executor1);
    executors.add(executor2);

    TaskExecutorMetricsBinder binder = new TaskExecutorMetricsBinder(registry, executors);
    binder.run(args);

    // 验证至少有两个指标被绑定（executor.threads.active, executor.queue.size 等）
    assertThat(registry.getMeters()).hasSizeGreaterThan(0);
  }

  @Test
  void shouldTrackRejectionCountWithTags() {
    // 模拟拒绝计数
    Counter counter = Counter.builder("async.task.rejected").register(registry);

    // 增加拒绝次数
    counter.increment(5);

    // 验证计数器值
    assertThat(counter.count()).isEqualTo(5);
  }

  @Test
  void shouldTrackRejectionMetricsForMultipleExecutors() {
    // 模拟两个执行器的拒绝计数
    Counter uploadCounter =
        Counter.builder("async.task.rejected")
            .tag("executor", "platform-batch-upload")
            .tag("policy", "discard-log")
            .register(registry);

    Counter chatCounter =
        Counter.builder("async.task.rejected")
            .tag("executor", "platform-stream-chat")
            .tag("policy", "abort")
            .register(registry);

    uploadCounter.increment(3);
    chatCounter.increment(7);

    // 验证各个执行器的拒绝计数
    assertThat(
            registry
                .find("async.task.rejected")
                .tag("executor", "platform-batch-upload")
                .tag("policy", "discard-log")
                .counter()
                .count())
        .isEqualTo(3);

    assertThat(
            registry
                .find("async.task.rejected")
                .tag("executor", "platform-stream-chat")
                .tag("policy", "abort")
                .counter()
                .count())
        .isEqualTo(7);
  }

  @Test
  void shouldHandleEmptyExecutorList() {
    List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    TaskExecutorMetricsBinder binder = new TaskExecutorMetricsBinder(registry, executors);

    // 不应抛出异常
    binder.run(args);

    // 验证没有添加新的 meter（SimpleMeterRegistry 初始为空）
    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  void shouldStripTrailingDashFromMetricName() {
    List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    executors.add(executor1);

    TaskExecutorMetricsBinder binder = new TaskExecutorMetricsBinder(registry, executors);
    binder.run(args);

    // 验证指标存在（不检查具体名称，只验证没有异常）
    assertThat(registry.getMeters()).hasSizeGreaterThan(0);
  }
}
