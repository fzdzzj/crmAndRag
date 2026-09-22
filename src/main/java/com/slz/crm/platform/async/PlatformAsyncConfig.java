package com.slz.crm.platform.async;

import com.slz.crm.platform.contract.BypassTaskExecutor;
import com.slz.crm.platform.trace.MdcTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 按任务类型隔离平台线程池。
 *
 * <p>上传、解析、嵌入、流式问答和记忆旁路的耗时特征不同；独立队列可避免慢任务拖垮主答链路， 统一有界队列与拒绝指标则保证饱和行为可观测、可恢复。所有线程池都走 MDC/UserContext
 * 装饰器。
 */
@Configuration
public class PlatformAsyncConfig {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformAsyncConfig.class);

  /**
   * 批量上传线程池；队列满时记录丢弃，等待恢复流程重新接管，避免请求线程长时间阻塞。
   *
   * @param meterRegistry Micrometer 注册表
   * @param coreSize 核心线程数
   * @param maxSize 最大线程数
   * @param queueCapacity 有界队列容量
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 批量上传执行器
   */
  @Bean
  public ThreadPoolTaskExecutor batchUploadTaskExecutor(
      MeterRegistry meterRegistry,
      @Value("${platform.async.batch-upload.core-size:2}") int coreSize,
      @Value("${platform.async.batch-upload.max-size:4}") int maxSize,
      @Value("${platform.async.batch-upload.queue-capacity:100}") int queueCapacity,
      @Value("${platform.async.batch-upload.await-termination-ms:60000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-batch-upload",
        coreSize,
        maxSize,
        queueCapacity,
        awaitTerminationMillis,
        "discard-log",
        meterRegistry);
  }

  /**
   * 流式问答线程池；饱和时快速失败，由上层返回明确的生成不可用。
   *
   * @param meterRegistry Micrometer 注册表
   * @param coreSize 核心线程数
   * @param maxSize 最大线程数
   * @param queueCapacity 有界队列容量
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 流式问答执行器
   */
  @Bean
  public ThreadPoolTaskExecutor streamChatTaskExecutor(
      MeterRegistry meterRegistry,
      @Value("${platform.async.stream-chat.core-size:4}") int coreSize,
      @Value("${platform.async.stream-chat.max-size:8}") int maxSize,
      @Value("${platform.async.stream-chat.queue-capacity:50}") int queueCapacity,
      @Value("${platform.async.stream-chat.await-termination-ms:30000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-stream-chat",
        coreSize,
        maxSize,
        queueCapacity,
        awaitTerminationMillis,
        "abort",
        meterRegistry);
  }

  /**
   * 嵌入任务线程池；队列满时调用者运行，保证入库/检索请求不丢失但可被限速。
   *
   * @param meterRegistry Micrometer 注册表
   * @param coreSize 核心线程数
   * @param maxSize 最大线程数
   * @param queueCapacity 有界队列容量
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 嵌入执行器
   */
  @Bean
  public ThreadPoolTaskExecutor embeddingTaskExecutor(
      MeterRegistry meterRegistry,
      @Value("${platform.async.embedding.core-size:2}") int coreSize,
      @Value("${platform.async.embedding.max-size:4}") int maxSize,
      @Value("${platform.async.embedding.queue-capacity:100}") int queueCapacity,
      @Value("${platform.async.embedding.await-termination-ms:60000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-embedding",
        coreSize,
        maxSize,
        queueCapacity,
        awaitTerminationMillis,
        "caller-runs",
        meterRegistry);
  }

  /**
   * 文档解析线程池；解析重且非主答关键路径，饱和时快速失败并交给批量恢复。
   *
   * @param meterRegistry Micrometer 注册表
   * @param coreSize 核心线程数
   * @param maxSize 最大线程数
   * @param queueCapacity 有界队列容量
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 解析执行器
   */
  @Bean
  public ThreadPoolTaskExecutor documentParsingTaskExecutor(
      MeterRegistry meterRegistry,
      @Value("${platform.async.document-parsing.core-size:2}") int coreSize,
      @Value("${platform.async.document-parsing.max-size:4}") int maxSize,
      @Value("${platform.async.document-parsing.queue-capacity:100}") int queueCapacity,
      @Value("${platform.async.document-parsing.await-termination-ms:60000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-document-parsing",
        coreSize,
        maxSize,
        queueCapacity,
        awaitTerminationMillis,
        "abort",
        meterRegistry);
  }

  /**
   * 记忆旁路专用底层线程池；固定 2 线程，避免记忆加工抢占主答模型并发。
   *
   * @param meterRegistry Micrometer 注册表
   * @param queueCapacity 有界队列容量，默认 64
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 记忆旁路底层执行器
   */
  @Bean("memoryBypassThreadPool")
  public ThreadPoolTaskExecutor memoryBypassThreadPool(
      MeterRegistry meterRegistry,
      @Value("${platform.async.memory-bypass.queue-capacity:64}") int queueCapacity,
      @Value("${platform.async.memory-bypass.await-termination-ms:10000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-memory-bypass",
        2,
        2,
        queueCapacity,
        awaitTerminationMillis,
        "abort",
        meterRegistry);
  }

  /**
   * 冻结契约要求的记忆旁路执行器；只暴露“可拒绝的 tryExecute”语义。
   *
   * @param executor 底层线程池
   * @param meterRegistry Micrometer 注册表
   * @return C 可复用的旁路执行器
   */
  @Bean("memoryBypassExecutor")
  public BypassTaskExecutor memoryBypassExecutor(
      @Qualifier("memoryBypassThreadPool") ThreadPoolTaskExecutor executor,
      MeterRegistry meterRegistry) {
    return new MemoryBypassTaskExecutor(executor, meterRegistry);
  }

  /**
   * 衍生问题旁路线程池（enhance-query-transformation 任务 3.1）：入库主链成功后的
   * 反向问题生成/嵌入旁路。队列饱和时丢弃（discard-log）——衍生问题缺失等价于 该块退化为普通块，绝不拖累入库主链。
   *
   * @param meterRegistry Micrometer 注册表
   * @param queueCapacity 有界队列容量，默认 64
   * @param awaitTerminationMillis 优雅关停等待毫秒数
   * @return 衍生问题旁路执行器
   */
  @Bean("derivedQuestionBypassThreadPool")
  public ThreadPoolTaskExecutor derivedQuestionBypassThreadPool(
      MeterRegistry meterRegistry,
      @Value("${platform.async.derived-questions.queue-capacity:64}") int queueCapacity,
      @Value("${platform.async.derived-questions.await-termination-ms:10000}")
          long awaitTerminationMillis) {
    return buildExecutor(
        "platform-derived-questions",
        1,
        2,
        queueCapacity,
        awaitTerminationMillis,
        "discard-log",
        meterRegistry);
  }

  static ThreadPoolTaskExecutor buildExecutor(
      String prefix,
      int coreSize,
      int maxSize,
      int queueCapacity,
      long awaitTerminationMillis,
      String rejectionPolicy,
      MeterRegistry meterRegistry) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix(prefix + "-");
    executor.setCorePoolSize(coreSize);
    executor.setMaxPoolSize(Math.max(coreSize, maxSize));
    executor.setQueueCapacity(Math.max(queueCapacity, 0));
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(awaitTerminationMillis);
    executor.setTaskDecorator(MdcTaskDecorator::wrap);
    executor.setRejectedExecutionHandler(
        buildRejectionHandler(prefix, rejectionPolicy, meterRegistry));
    executor.initialize();
    return executor;
  }

  static RejectedExecutionHandler buildRejectionHandler(
      String executorName, String rejectionPolicy, MeterRegistry meterRegistry) {
    return switch (rejectionPolicy) {
      case "discard-log" ->
          withRejectionMetric(
              executorName, rejectionPolicy, meterRegistry, new LoggingDiscardPolicy(executorName));
      case "caller-runs" ->
          withRejectionMetric(
              executorName,
              rejectionPolicy,
              meterRegistry,
              new ThreadPoolExecutor.CallerRunsPolicy());
      case "abort" ->
          withRejectionMetric(
              executorName, rejectionPolicy, meterRegistry, new ThreadPoolExecutor.AbortPolicy());
      default ->
          throw new IllegalStateException(
              "不支持的线程池拒绝策略: " + rejectionPolicy + ", executor=" + executorName);
    };
  }

  private static RejectedExecutionHandler withRejectionMetric(
      String executorName,
      String rejectionPolicy,
      MeterRegistry meterRegistry,
      RejectedExecutionHandler delegate) {
    Counter counter =
        Counter.builder("async.task.rejected")
            .tag("executor", executorName)
            .tag("policy", rejectionPolicy)
            .register(meterRegistry);
    return new MetricRejectedExecutionHandler(counter, delegate);
  }

  /** 记录拒绝次数并保留原始拒绝语义。 */
  static final class MetricRejectedExecutionHandler implements RejectedExecutionHandler {
    private final Counter counter;
    private final RejectedExecutionHandler delegate;

    private MetricRejectedExecutionHandler(Counter counter, RejectedExecutionHandler delegate) {
      this.counter = counter;
      this.delegate = delegate;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
      counter.increment();
      delegate.rejectedExecution(task, executor);
    }
  }

  /** 批量任务丢弃时记录告警，后续由状态机或恢复流程补救。 */
  static final class LoggingDiscardPolicy implements RejectedExecutionHandler {
    private final String executorName;

    private LoggingDiscardPolicy(String executorName) {
      this.executorName = executorName;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
      LOG.warn("异步任务被丢弃，等待恢复流程接管: executor={}", executorName);
    }
  }
}
