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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 外部依赖统一重试与熔断执行器。
 *
 * <p>嵌入、向量库、对象存储和模型调用都可能出现瞬时故障；这里按依赖名隔离熔断状态， 指数退避重试可恢复异常。所有拒绝都携带 {@link
 * DependencyFailureType}，供批量处理区分 “暂时回 PENDING”与“永久失败”。
 *
 * <p>wire-circuit-half-open 任务 3：熔断为 CLOSED → OPEN → HALF_OPEN 三态，OPEN 到期后不再全量放行， 而是由 HALF_OPEN
 * 内的单探测 CAS 抢占一个调用真实试水，其余调用按 {@link DependencyFailureType#CIRCUIT_OPEN} 拒绝；探测成功回 CLOSED
 * 清零计数，探测失败立即回 OPEN 重置完整窗口。
 *
 * <p>wire-circuit-dynamic-config 任务 3：熔断阈值与开闸保持时长经 {@link ResilienceConfigResolver} 动态配置化，
 * 每次调用实时读取；F-3 次序修复：execute 循环不可重试异常判定提前于熔断计数，不可重试异常不消耗熔断预算、上抛 NON_RETRYABLE。
 */
@Component
public class DependencyResilienceExecutor {

  private static final LongSupplier SYSTEM_NANO_CLOCK = System::nanoTime;

  private final MeterRegistry meterRegistry;
  private final Map<String, Counter> counters = new ConcurrentHashMap<>();
  private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
  private final int maxAttempts;
  private final long initialBackoffMillis;
  private final double backoffMultiplier;
  private final IntSupplier failureThresholdSupplier;
  private final LongSupplier openDurationMillisSupplier;
  private final LongSupplier nanoClock;

  /**
   * 使用默认治理参数构造执行器（门面层兼容兜底入口）。
   *
   * @param meterRegistry Micrometer 注册表
   */
  public DependencyResilienceExecutor(MeterRegistry meterRegistry) {
    this(
        meterRegistry,
        new ResilienceConfigResolver((com.slz.crm.platform.contract.DynamicConfigService) null));
  }

  /**
   * 生产装配构造器：通过 {@link ResilienceConfigResolver} 实时读取动态配置（wire-circuit-dynamic-config 任务 3）。
   *
   * @param meterRegistry Micrometer 注册表
   * @param resilienceConfigResolver 熔断韧性配置解析器
   */
  @Autowired
  public DependencyResilienceExecutor(
      MeterRegistry meterRegistry, ResilienceConfigResolver resilienceConfigResolver) {
    this(
        meterRegistry,
        3,
        200,
        2.0,
        resilienceConfigResolver != null
            ? resilienceConfigResolver::resolveFailureThreshold
            : () -> 5,
        resilienceConfigResolver != null
            ? resilienceConfigResolver::resolveOpenDurationMillis
            : () -> 30000L,
        SYSTEM_NANO_CLOCK);
  }

  /**
   * 构造可调参执行器，供测试与固定参数场景使用。
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
    this(
        meterRegistry,
        maxAttempts,
        initialBackoffMillis,
        backoffMultiplier,
        failureThreshold,
        openDurationMillis,
        SYSTEM_NANO_CLOCK);
  }

  /**
   * 构造固定参数且可注入 nano 时钟来源的执行器（测试固定参数路径保持）。
   *
   * @param meterRegistry Micrometer 注册表
   * @param maxAttempts 最大调用次数，必须大于 0
   * @param initialBackoffMillis 首次退避毫秒数
   * @param backoffMultiplier 退避倍数
   * @param failureThreshold 连续失败熔断阈值
   * @param openDurationMillis 熔断开启时长毫秒数
   * @param nanoClock nano 时间来源，生产默认 {@code System::nanoTime}
   */
  DependencyResilienceExecutor(
      MeterRegistry meterRegistry,
      int maxAttempts,
      long initialBackoffMillis,
      double backoffMultiplier,
      int failureThreshold,
      long openDurationMillis,
      LongSupplier nanoClock) {
    this(
        meterRegistry,
        maxAttempts,
        initialBackoffMillis,
        backoffMultiplier,
        () -> failureThreshold,
        () -> openDurationMillis,
        nanoClock);
  }

  DependencyResilienceExecutor(
      MeterRegistry meterRegistry,
      int maxAttempts,
      long initialBackoffMillis,
      double backoffMultiplier,
      IntSupplier failureThresholdSupplier,
      LongSupplier openDurationMillisSupplier,
      LongSupplier nanoClock) {
    if (maxAttempts <= 0) {
      throw new IllegalArgumentException("maxAttempts 必须大于 0");
    }
    this.meterRegistry = meterRegistry;
    this.maxAttempts = maxAttempts;
    this.initialBackoffMillis = initialBackoffMillis;
    this.backoffMultiplier = backoffMultiplier;
    this.failureThresholdSupplier = failureThresholdSupplier;
    this.openDurationMillisSupplier = openDurationMillisSupplier;
    this.nanoClock = nanoClock;
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
    CircuitState circuitState = circuitOf(dependency);
    CircuitState.Permit permit = acquirePermit(dependency, circuitState);

    Throwable lastError = null;
    try {
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
          T result = operation.call();
          markSuccess(dependency, circuitState, permit);
          return result;
        } catch (Exception exception) {
          lastError = exception;
          if (!retryable.test(exception)) {
            counter("dependency.call", dependency, "failure").increment();
            throw new DependencyUnavailableException(
                "依赖调用不可重试: " + dependency, exception, DependencyFailureType.NON_RETRYABLE);
          }
          if (markFailure(dependency, circuitState, permit)) {
            throw new DependencyUnavailableException(
                "依赖连续失败已熔断: " + dependency, exception, DependencyFailureType.CALL_FAILED);
          }
          if (attempt >= maxAttempts) {
            break;
          }
          counter("dependency.retry", dependency).increment();
          sleep(dependency, initialBackoffMillis, backoffMultiplier, attempt);
        }
      }
    } finally {
      releaseProbe(circuitState, permit);
    }

    throw new DependencyUnavailableException(
        "依赖调用失败: " + dependency, lastError, DependencyFailureType.CALL_FAILED);
  }

  /**
   * 单次执行依赖调用，仅做失败计数与熔断检查，不重试不退避。
   *
   * <p>wire-dependency-circuit-breaker 任务 2：面向自带重试语义的外部门面（如 Qdrant 内建重试）， 避免熔断器叠加自动重试放大物理调用次数；OPEN
   * 时直接拒绝，失败按 {@link DependencyFailureType#CALL_FAILED} 上抛。
   *
   * <p>wire-circuit-half-open 任务 3：与 {@link #execute} 共用同一套三态状态机，到期后同样只放行一个探测。
   *
   * @param dependency 依赖名称
   * @param operation 依赖调用
   * @param <T> 返回类型
   * @return 调用结果
   * @throws DependencyUnavailableException 依赖失败或熔断拒绝
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 容错执行器：operation.call()任意抛，计数熔断/上抛
  public <T> T executeNoRetry(String dependency, Callable<T> operation) {
    CircuitState circuitState = circuitOf(dependency);
    CircuitState.Permit permit = acquirePermit(dependency, circuitState);
    try {
      try {
        T result = operation.call();
        markSuccess(dependency, circuitState, permit);
        return result;
      } catch (Exception exception) {
        if (markFailure(dependency, circuitState, permit)) {
          throw new DependencyUnavailableException(
              "依赖连续失败已熔断: " + dependency, exception, DependencyFailureType.CALL_FAILED);
        }
        throw new DependencyUnavailableException(
            "依赖调用失败: " + dependency, exception, DependencyFailureType.CALL_FAILED);
      }
    } finally {
      releaseProbe(circuitState, permit);
    }
  }

  private CircuitState circuitOf(String dependency) {
    return circuitStates.computeIfAbsent(
        dependency,
        name ->
            new CircuitState(
                name,
                meterRegistry,
                failureThresholdSupplier,
                openDurationMillisSupplier,
                nanoClock));
  }

  /**
   * 取半开状态机的调用许可；被拒时按既有 CIRCUIT_OPEN 语义快速失败，不执行任何外呼。
   *
   * @param dependency 依赖名称
   * @param circuitState 该依赖的熔断状态
   * @return 调用许可（普通放行或单探测放行）
   */
  private CircuitState.Permit acquirePermit(String dependency, CircuitState circuitState) {
    CircuitState.Permit permit = circuitState.acquire();
    if (permit == CircuitState.Permit.DENIED) {
      counter("dependency.circuit.rejected", dependency).increment();
      throw DependencyUnavailableException.circuitOpen(dependency);
    }
    return permit;
  }

  private void markSuccess(
      String dependency, CircuitState circuitState, CircuitState.Permit permit) {
    boolean probing = permit == CircuitState.Permit.PROBE;
    circuitState.recordSuccess(probing);
    if (probing) {
      counter("dependency.circuit.probe", dependency, "success").increment();
    }
    counter("dependency.call", dependency, "success").increment();
  }

  private boolean markFailure(
      String dependency, CircuitState circuitState, CircuitState.Permit permit) {
    boolean probing = permit == CircuitState.Permit.PROBE;
    counter("dependency.call", dependency, "failure").increment();
    boolean opened = circuitState.recordFailure(probing);
    if (opened) {
      counter("dependency.circuit.opened", dependency).increment();
    }
    if (probing) {
      counter("dependency.circuit.probe", dependency, "failure").increment();
    }
    return opened;
  }

  /**
   * 探测调用没有产出成功或失败结论就退出时（不可重试、中断或 Error）归还探测资格， 避免 HALF_OPEN 因一次夭折的探测永久拒绝所有调用。
   *
   * @param circuitState 该依赖的熔断状态
   * @param permit 本次调用的许可
   */
  private void releaseProbe(CircuitState circuitState, CircuitState.Permit permit) {
    if (permit == CircuitState.Permit.PROBE) {
      circuitState.releaseProbe();
    }
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
}

/**
 * 按依赖名隔离的三态熔断状态机：CLOSED → OPEN → HALF_OPEN。
 *
 * <p>wire-circuit-half-open 任务 3.5：拆分前 {@link DependencyResilienceExecutor} 的类 NCSS 实测 154， 越过
 * pmd-rules.xml 里 NcssCount 的 classReportLevel=150，故把状态机从内部类拆为包内独立类，
 * 状态机语义全部内聚于此。为守住本卡受控写集，仍与执行器同处一个源文件。
 */
final class CircuitState {

  /** 熔断三态；PROBING 是 HALF_OPEN 的“探测已在飞行中”子态，仅用于单探测的 CAS 抢占，对外仍表现为半开拒绝。 */
  private enum Phase {
    CLOSED,
    OPEN,
    HALF_OPEN,
    PROBING
  }

  /** 半开状态机对单次调用的裁决。 */
  enum Permit {
    /** CLOSED 态普通放行。 */
    ALLOWED,
    /** HALF_OPEN 抢占到的唯一探测资格。 */
    PROBE,
    /** OPEN 未到期或探测在飞行中，按 CIRCUIT_OPEN 拒绝。 */
    DENIED
  }

  private final IntSupplier failureThresholdSupplier;
  private final LongSupplier openDurationMillisSupplier;
  private final LongSupplier nanoClock;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CLOSED);
  private final AtomicLong openUntilNanos = new AtomicLong();

  CircuitState(
      String dependency,
      MeterRegistry meterRegistry,
      IntSupplier failureThresholdSupplier,
      LongSupplier openDurationMillisSupplier,
      LongSupplier nanoClock) {
    this.failureThresholdSupplier = failureThresholdSupplier;
    this.openDurationMillisSupplier = openDurationMillisSupplier;
    this.nanoClock = nanoClock;
    Gauge.builder("dependency.circuit.open", this, state -> state.isRejecting() ? 1 : 0)
        .tag("dependency", dependency)
        .register(meterRegistry);
  }

  /**
   * 裁决本次调用能否发起外呼：OPEN 未到期一律拒绝；到期后 CAS 进入 HALF_OPEN； HALF_OPEN 内再 CAS 抢占唯一探测资格，抢不到的拒绝。
   *
   * @return 本次调用的许可
   */
  Permit acquire() {
    Permit permit;
    Phase current = advanceIfExpired();
    if (current == Phase.CLOSED) {
      permit = Permit.ALLOWED;
    } else if (current == Phase.OPEN) {
      permit = Permit.DENIED;
    } else {
      permit = phase.compareAndSet(Phase.HALF_OPEN, Phase.PROBING) ? Permit.PROBE : Permit.DENIED;
    }
    return permit;
  }

  /** 把到期未有人问津的 OPEN 推进为 HALF_OPEN；CAS 失败说明已被其他调用推进。 */
  private Phase advanceIfExpired() {
    Phase current = phase.get();
    if (current == Phase.OPEN
        && openUntilNanos.get() <= nanoClock.getAsLong()
        && phase.compareAndSet(Phase.OPEN, Phase.HALF_OPEN)) {
      current = Phase.HALF_OPEN;
    }
    return current;
  }

  /**
   * 记录调用成功：探测成功回 CLOSED，两种情形都清零连续失败计数。
   *
   * @param probing 本次调用是否为抢占到的单探测
   */
  void recordSuccess(boolean probing) {
    if (probing) {
      phase.compareAndSet(Phase.PROBING, Phase.CLOSED);
    }
    consecutiveFailures.set(0);
  }

  /**
   * 记录调用失败：探测失败立即回 OPEN 并重置完整窗口，不等 failureThreshold 次； 陈旧调用凑满阈值时若探测在飞行中，开闸决定让位给探测结论。
   *
   * @param probing 本次调用是否为抢占到的单探测
   * @return 是否触发（或恢复）开闸
   */
  boolean recordFailure(boolean probing) {
    boolean opened;
    if (probing) {
      reopenByProbeFailure();
      opened = true;
    } else if (consecutiveFailures.incrementAndGet() >= failureThresholdSupplier.getAsInt()) {
      openByStaleCall();
      opened = true;
    } else {
      opened = false;
    }
    return opened;
  }

  /**
   * 探测失败：探测结论权威，写入完整窗口并清零计数，CAS 把在飞探测的 PROBING 翻为 OPEN。
   *
   * <p>F-1（卡 P-v 硬化）：旧实现无条件 {@code phase.set(OPEN)} 会把在飞探测成功后的 CAS(PROBING→CLOSED) 打断， 状态被多锁一个完整窗口。
   */
  private void reopenByProbeFailure() {
    long durationNanos = TimeUnit.MILLISECONDS.toNanos(openDurationMillisSupplier.getAsLong());
    openUntilNanos.set(nanoClock.getAsLong() + durationNanos);
    consecutiveFailures.set(0);
    phase.compareAndSet(Phase.PROBING, Phase.OPEN);
  }

  /** 陈旧调用凑满阈值：非探测在飞行时照常开闸；探测在飞行中（PROBING）时不得打断—— 否则探测成功的 CAS 落空、状态被陈旧窗口多锁一个完整窗口（F-1 交错缺口）。 */
  private void openByStaleCall() {
    long durationNanos = TimeUnit.MILLISECONDS.toNanos(openDurationMillisSupplier.getAsLong());
    openUntilNanos.set(nanoClock.getAsLong() + durationNanos);
    consecutiveFailures.set(0);
    phase.updateAndGet(current -> current == Phase.PROBING ? current : Phase.OPEN);
  }

  /** 探测未产出结论就退出时归还探测资格，让后续调用可以重新抢占。 */
  void releaseProbe() {
    phase.compareAndSet(Phase.PROBING, Phase.HALF_OPEN);
  }

  /**
   * gauge 读数：OPEN 未到期与 HALF_OPEN（含探测飞行中）对调用方都在拒绝，一律记 1。
   *
   * @return 是否处于拒绝态
   */
  private boolean isRejecting() {
    Phase current = phase.get();
    boolean rejecting;
    if (current == Phase.OPEN) {
      rejecting = openUntilNanos.get() > nanoClock.getAsLong();
    } else {
      rejecting = current == Phase.HALF_OPEN || current == Phase.PROBING;
    }
    return rejecting;
  }
}
