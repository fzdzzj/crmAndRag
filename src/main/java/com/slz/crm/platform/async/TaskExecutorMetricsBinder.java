package com.slz.crm.platform.async;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 将平台线程池接入 Micrometer。
 *
 * <p>默认指标包含活跃线程、队列长度、剩余队列、完成任务和线程池规模；配合拒绝计数可定位饱和队列。
 */
@Component
public class TaskExecutorMetricsBinder implements ApplicationRunner {

  private final MeterRegistry meterRegistry;
  private final List<ThreadPoolTaskExecutor> executors;

  /**
   * 构造指标绑定器。
   *
   * @param meterRegistry Micrometer 注册表
   * @param executors Spring 容器中的线程池集合
   */
  public TaskExecutorMetricsBinder(
      MeterRegistry meterRegistry, List<ThreadPoolTaskExecutor> executors) {
    this.meterRegistry = meterRegistry;
    this.executors = executors;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (ThreadPoolTaskExecutor executor : executors) {
      String metricName = executor.getThreadNamePrefix();
      if (metricName.endsWith("-")) {
        metricName = metricName.substring(0, metricName.length() - 1);
      }
      new ExecutorServiceMetrics(executor.getThreadPoolExecutor(), metricName, Tags.empty())
          .bindTo(meterRegistry);
    }
  }
}
