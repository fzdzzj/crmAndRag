package com.slz.crm.platform.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 外部依赖统一重试与熔断执行器。
 *
 * <p>嵌入、向量库、对象存储和模型调用都可能出现瞬时故障；这里按依赖名隔离熔断状态， 指数退避重试可恢复异常。所有拒绝都携带 {@link
 * DependencyFailureType}，供批量处理区分 “暂时回 PENDING”与“永久失败”。
 */
@Component
public class DependencyResilienceExecutor {

  private final MeterRegistry meterRegistry;
  private final Map<String, Counter> counters = new ConcurrentHashMap<>();
  private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
  private final int maxAttempts;
  private final long initialBackoffMillis;
  private final double backoffMultiplier;
  private final int failureThreshold;
  private final long openDurationMillis;

  /**
   * 使用默认治理参数构造执行器。
   *
   * <p>集成修正：本类有两个构造器（此 public + 下方 package-private 可调参版）； Spring 面对多构造器需显式 {@code @Autowired}
   * 指定注入入口，否则回退去找无参构造器， 报 "No default constructor found" 致上下文加载失败（D 单测用 new 构造，未暴露此问题）。
   *
   * @param meterRegistry Micrometer 注册表
   */
  @Autowired
  public DependencyResilienceExecutor(MeterRegistry meterRegistry) {
    this(meterRegistry, 3, 200, 2.0, 5, 30000);
  }

  /**
   * 构造可调参执行器，主要供测试与后续动态配置接入。
   *
   * @param meterRegistry Micrometer 注册表
   * @param maxAttempts 最大调用次数，必须大于 0
   * @param initialBackoffMillis 首次退避毫秒数
   * @param backoffMultiplier 退避倍数
   * @param failureThreshold 连续失败熔断阈值
   * @param openDurationMillis 熔断开启时长毫秒数
   */
  DependencyResilienceExecutor(
      MeterRegistry meterRegistry,
      int maxAttempts,
      long initialBackoffMillis,
      double backoffMultiplier,
      int failureThreshold,
      long openDurationMillis) {
    if (maxAttempts <= 0) {
      throw new IllegalArgumentException("maxAttempts 必须大于 0");
    }
    this.meterRegistry = meterRegistry;
    this.maxAttempts = maxAttempts;
    this.initialBackoffMillis = initialBackoffMillis;
    this.backoffMultiplier = backoffMultiplier;
    this.failureThreshold = failureThreshold;
    this.openDurationMillis = openDurationMillis;
  }

  /**
   * 使用默认重试边界执行依赖调用。
   *
   * @param dependency 依赖名称
   * @param operation 依赖调用
   * @param <T> 返回类型
   * @return 调用结果
   * @throws DependencyUnavailableException 依赖失败或熔断拒绝
   */
  public <T> T execute(String dependency, Callable<T> operation) {
    return execute(dependency, operation, error -> !(error instanceof IllegalArgumentException));
  }

  /**
   * 使用自定义重试边界执行依赖调用。
   *
   * @param dependency 依赖名称
   * @param operation 依赖调用
   * @param retryable 重试判定；false 时保留原始异常并标记不可恢复
   * @param <T> 返回类型
   * @return 调用结果
   * @throws DependencyUnavailableException 依赖失败或熔断拒绝
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错执行器：operation.call()任意抛，计数重试/熔断/上抛
  public <T> T execute(String dependency, Callable<T> operation, Predicate<Throwable> retryable) {
    CircuitState circuitState =
        circuitStates.computeIfAbsent(dependency, name -> new CircuitState(name, meterRegistry));
    if (circuitState.isOpen()) {
      counter("dependency.circuit.rejected", dependency).increment();
      throw DependencyUnavailableException.circuitOpen(dependency);
    }

    Throwable lastError = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        T result = operation.call();
        circuitState.recordSuccess();
        counter("dependency.call", dependency, "success").increment();
        return result;
      } catch (Exception exception) {
        lastError = exception;
        counter("dependency.call", dependency, "failure").increment();
        if (circuitState.recordFailure(failureThreshold, openDurationMillis)) {
          counter("dependency.circuit.opened", dependency).increment();
          throw new DependencyUnavailableException(
              "依赖连续失败已熔断: " + dependency, exception, DependencyFailureType.CALL_FAILED);
        }
        if (attempt >= maxAttempts) {
          break;
        }
        if (!retryable.test(exception)) {
          throw new DependencyUnavailableException(
              "依赖调用不可重试: " + dependency, exception, DependencyFailureType.NON_RETRYABLE);
        }
        counter("dependency.retry", dependency).increment();
        sleep(dependency, initialBackoffMillis, backoffMultiplier, attempt);
      }
    }

    throw new DependencyUnavailableException(
        "依赖调用失败: " + dependency, lastError, DependencyFailureType.CALL_FAILED);
  }

  private void sleep(String dependency, long initialBackoffMillis, double multiplier, int attempt) {
    long backoffMillis = (long) (initialBackoffMillis * Math.pow(multiplier, attempt - 1));
    if (backoffMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(backoffMillis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DependencyUnavailableException(
          "依赖重试等待被中断: " + dependency, exception, DependencyFailureType.INTERRUPTED);
    }
  }

  private Counter counter(String name, String dependency, String result) {
    return counters.computeIfAbsent(
        name + "|" + dependency + "|" + result,
        key ->
            Counter.builder(name)
                .tag("dependency", dependency)
                .tag("result", result)
                .register(meterRegistry));
  }

  private Counter counter(String name, String dependency) {
    return counters.computeIfAbsent(
        name + "|" + dependency,
        key -> Counter.builder(name).tag("dependency", dependency).register(meterRegistry));
  }

  /** 按依赖名隔离的连续失败与熔断到期状态。 */
  private static final class CircuitState {
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong();

    private CircuitState(String dependency, MeterRegistry meterRegistry) {
      Gauge.builder("dependency.circuit.open", this, state -> state.isOpen() ? 1 : 0)
          .tag("dependency", dependency)
          .register(meterRegistry);
    }

    private boolean isOpen() {
      return openUntilNanos.get() > System.nanoTime();
    }

    private void recordSuccess() {
      consecutiveFailures.set(0);
    }

    private boolean recordFailure(int failureThreshold, long openDurationMillis) {
      boolean result;
      if (consecutiveFailures.incrementAndGet() < failureThreshold) {
        result = false;
      } else {
        openUntilNanos.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(openDurationMillis));
        consecutiveFailures.set(0);
        result = true;
      }
      return result;
    }
  }
}
