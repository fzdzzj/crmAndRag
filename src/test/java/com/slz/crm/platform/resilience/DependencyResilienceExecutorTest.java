package com.slz.crm.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slz.crm.platform.contract.DynamicConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 依赖韧性执行器的重试、熔断和失败分类测试。 */
class DependencyResilienceExecutorTest {

  @Test
  void shouldRetryRecoverableFailureAndExposeCallFailedSemantics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 3, 0, 2.0, 10, 60000);
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.execute(
                    "qdrant",
                    (Callable<Integer>)
                        () -> {
                          attempts.incrementAndGet();
                          throw new IOException("connection refused");
                        }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
              assertThat(error.getCause()).isInstanceOf(IOException.class);
            });

    assertThat(attempts.get()).isEqualTo(3);
    assertThat(
            DependencyFailureClassifier.isRecoverable(
                new RuntimeException(
                    new DependencyUnavailableException(
                        "wrapped", DependencyFailureType.CALL_FAILED))))
        .isTrue();
  }

  @Test
  void shouldOpenCircuitAndRejectWithCircuitOpenCause() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 60000);

    assertThatThrownBy(
            () ->
                executor.execute(
                    "qdrant",
                    () -> {
                      throw new IOException("first failure");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
            });

    assertThatThrownBy(
            () ->
                executor.execute(
                    "qdrant",
                    () -> {
                      throw new IOException("second failure");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
            });

    assertThatThrownBy(() -> executor.execute("qdrant", () -> "should-not-run"))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
              assertThat(error.getCause())
                  .isInstanceOf(DependencyUnavailableException.CircuitOpenException.class);
            });
  }

  @Test
  void shouldNotRetryOperationMarkedPermanent() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 3, 0, 2.0, 10, 60000);
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.execute(
                    "storage",
                    () -> {
                      attempts.incrementAndGet();
                      throw new IllegalArgumentException("invalid object key");
                    },
                    error -> false))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.NON_RETRYABLE);
              assertThat(error.getCause()).isInstanceOf(IllegalArgumentException.class);
            });

    assertThat(attempts.get()).isEqualTo(1);
    assertThat(
            DependencyFailureClassifier.isRecoverable(
                new DependencyUnavailableException(
                    "permanent", DependencyFailureType.NON_RETRYABLE)))
        .isFalse();
  }

  /**
   * wire-dependency-circuit-breaker 任务 2：executeNoRetry 单次执行——首次失败即 CALL_FAILED，无重试无退避， 失败计数 +1
   * 且不产生 retry/success 指标。
   */
  @Test
  void executeNoRetryShouldFailOnceWithCallFailedClassification() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 3, 0, 2.0, 10, 60000);
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    (Callable<String>)
                        () -> {
                          attempts.incrementAndGet();
                          throw new IOException("upstream 502");
                        }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
              assertThat(error.getCause()).isInstanceOf(IOException.class);
              assertThat(error).hasMessage("依赖调用失败: model-chat");
            });

    assertThat(attempts.get()).isEqualTo(1);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", "model-chat")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find("dependency.retry").tag("dependency", "model-chat").counter())
        .isNull();
    assertThat(
            registry
                .find("dependency.call")
                .tag("dependency", "model-chat")
                .tag("result", "success")
                .counter())
        .isNull();
  }

  /** wire-dependency-circuit-breaker 任务 2：executeNoRetry 连续失败计入熔断阈值，达到阈值即开闸并抛 CALL_FAILED。 */
  @Test
  void executeNoRetryShouldOpenCircuitOnConsecutiveFailures() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 3, 0, 2.0, 2, 60000);

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-vision",
                    () -> {
                      throw new IOException("vision failure 1");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED));

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-vision",
                    () -> {
                      throw new IOException("vision failure 2");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
              assertThat(error).hasMessage("依赖连续失败已熔断: model-vision");
            });

    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", "model-vision")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", "model-vision")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * wire-dependency-circuit-breaker 任务 2：executeNoRetry 在 OPEN 状态直接拒绝——操作零执行、rejected 计数 +1、cause 为
   * CircuitOpenException。
   */
  @Test
  void executeNoRetryShouldRejectWithCircuitOpenWithoutExecutingOperation() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 1, 60000);
    AtomicInteger openAttempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "storage-minio",
                    () -> {
                      openAttempts.incrementAndGet();
                      throw new IOException("first failure");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);

    AtomicInteger blockedAttempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "storage-minio",
                    () -> {
                      blockedAttempts.incrementAndGet();
                      return "should-not-run";
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
              assertThat(error.getCause())
                  .isInstanceOf(DependencyUnavailableException.CircuitOpenException.class);
            });

    assertThat(openAttempts.get()).isEqualTo(1);
    assertThat(blockedAttempts.get()).isZero();
    assertThat(
            registry
                .get("dependency.circuit.rejected")
                .tag("dependency", "storage-minio")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /** wire-dependency-circuit-breaker 任务 2：executeNoRetry 成功后连续失败计数清零，未达阈值的失败不开闸。 */
  @Test
  void executeNoRetryShouldResetFailureCountOnSuccess() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 60000);

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-embed",
                    () -> {
                      throw new IOException("failure before success");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(executor.executeNoRetry("model-embed", () -> "recovered")).isEqualTo("recovered");

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-embed",
                    () -> {
                      throw new IOException("failure after success");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
              assertThat(error).hasMessage("依赖调用失败: model-embed");
            });

    assertThat(
            registry.find("dependency.circuit.opened").tag("dependency", "model-embed").counter())
        .isNull();
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", "model-embed")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * wire-circuit-half-open 任务 3：OPEN 窗口到期后进入 HALF_OPEN，恰 1 个调用抢占探测资格真实执行， 探测在飞行期间的其余调用不执行外呼、按
   * CIRCUIT_OPEN 拒绝（红测试：基线无半开，第二个调用直接放行）。
   */
  @Test
  void halfOpenShouldRejectNonProbeCallsWhileProbeInFlight() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 1, 1, nanoTime::get);
    AtomicInteger probeExecutions = new AtomicInteger();
    AtomicInteger secondExecutions = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "qdrant",
                    () -> {
                      probeExecutions.incrementAndGet();
                      throw new IOException("qdrant down");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);
    nanoTime.set(TimeUnit.MILLISECONDS.toNanos(1));

    DependencyUnavailableException[] secondError = new DependencyUnavailableException[1];
    executor.executeNoRetry(
        "qdrant",
        () -> {
          probeExecutions.incrementAndGet();
          try {
            executor.executeNoRetry(
                "qdrant",
                () -> {
                  secondExecutions.incrementAndGet();
                  return "should-not-run";
                });
          } catch (DependencyUnavailableException exception) {
            secondError[0] = exception;
          }
          return "probe-result";
        });

    assertThat(probeExecutions.get()).isEqualTo(2);
    assertThat(secondExecutions.get()).isZero();
    assertThat(secondError[0]).isNotNull();
    assertThat(secondError[0].failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
    assertThat(secondError[0].getCause())
        .isInstanceOf(DependencyUnavailableException.CircuitOpenException.class);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", "qdrant")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * wire-circuit-half-open 任务 3：HALF_OPEN 探测失败立即回 OPEN 并重置完整窗口， 不再等 failureThreshold 次（红测试：基线阈值 2
   * 时单次到期后失败不够开闸）。
   */
  @Test
  void halfOpenProbeFailureShouldReopenImmediatelyWithoutThreshold() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1, nanoTime::get);

    for (int index = 0; index < 2; index++) {
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IOException("upstream 502");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
    }
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", "model-chat")
                .counter()
                .count())
        .isEqualTo(1.0);
    nanoTime.set(TimeUnit.MILLISECONDS.toNanos(1));

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    () -> {
                      throw new IOException("probe failed");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", "model-chat")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("dependency.circuit.probe")
                .tag("dependency", "model-chat")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * wire-circuit-half-open 任务 3：探测成功回 CLOSED 并清零连续失败计数，probe{result=success} 计数 +1（红测试：基线无探测概念，该
   * counter 不存在）。
   */
  @Test
  void halfOpenProbeSuccessShouldRecoverClosedAndClearFailureCount() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1, nanoTime::get);

    for (int index = 0; index < 2; index++) {
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-embed",
                      () -> {
                        throw new IOException("embed down");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
    }
    nanoTime.set(TimeUnit.MILLISECONDS.toNanos(1));

    assertThat(executor.executeNoRetry("model-embed", () -> "recovered")).isEqualTo("recovered");
    assertThat(
            registry
                .get("dependency.circuit.probe")
                .tag("dependency", "model-embed")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-embed",
                    () -> {
                      throw new IOException("single failure after recovery");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
              assertThat(error).hasMessage("依赖调用失败: model-embed");
            });
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", "model-embed")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * wire-circuit-half-open 任务 3.4：HALF_OPEN 期间并发 16 个调用，恰 1 个真实执行外呼、其余 15 个按 CIRCUIT_OPEN 拒绝且不执行操作。
   *
   * <p>并发用例选型：真并发闭锁压测——{@code CountDownLatch} 发令枪让 16 个线程同点起跑， 探测操作内部再等满“15 个落败者已全部返回”，使“恰 1
   * 个执行”的判定不依赖任何时间余量。
   */
  @Test
  void halfOpenShouldExecuteExactlyOneProbeUnderConcurrentCalls() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 1, 1, nanoTime::get);
    int callers = 16;
    AtomicInteger executions = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    CountDownLatch startGun = new CountDownLatch(1);
    CountDownLatch rejectedCallers = new CountDownLatch(callers - 1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "qdrant",
                    () -> {
                      throw new IOException("qdrant down");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);
    nanoTime.set(TimeUnit.MILLISECONDS.toNanos(1));

    List<Future<String>> futures = new ArrayList<>();
    for (int index = 0; index < callers; index++) {
      futures.add(
          pool.submit(
              () -> {
                startGun.await(5, TimeUnit.SECONDS);
                try {
                  executor.executeNoRetry(
                      "qdrant",
                      () -> {
                        executions.incrementAndGet();
                        rejectedCallers.await(5, TimeUnit.SECONDS);
                        return "probe";
                      });
                  return "executed";
                } catch (DependencyUnavailableException exception) {
                  if (exception.failureType() == DependencyFailureType.CIRCUIT_OPEN) {
                    rejected.incrementAndGet();
                    rejectedCallers.countDown();
                  }
                  return "rejected";
                }
              }));
    }
    startGun.countDown();
    for (Future<String> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(executions.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(callers - 1);
    assertThat(
            registry
                .get("dependency.circuit.probe")
                .tag("dependency", "qdrant")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("dependency.circuit.rejected")
                .tag("dependency", "qdrant")
                .counter()
                .count())
        .isEqualTo(callers - 1.0);
  }

  /**
   * wire-circuit-half-open 任务 3.1：注入 nano 时钟零真实等待地驱动 OPEN → HALF_OPEN → 探测失败 → 重开全窗口 → 再到期 → 探测成功回
   * CLOSED，并验证 open gauge 在 OPEN 与 HALF_OPEN 期间恒为 1。
   */
  @Test
  void injectedClockShouldDriveHalfOpenStateMachine() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 60000, nanoTime::get);
    long openWindowNanos = TimeUnit.MILLISECONDS.toNanos(60000);

    for (int index = 0; index < 2; index++) {
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IOException("upstream 502");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
    }
    assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(1.0);

    nanoTime.set(openWindowNanos);
    double[] gaugeDuringProbe = new double[1];
    DependencyUnavailableException[] inFlightError = new DependencyUnavailableException[1];
    AtomicInteger inFlightExecutions = new AtomicInteger();
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    (Callable<String>)
                        () -> {
                          gaugeDuringProbe[0] = circuitOpenGauge(registry, "model-chat");
                          try {
                            executor.executeNoRetry(
                                "model-chat",
                                () -> {
                                  inFlightExecutions.incrementAndGet();
                                  return "in-flight-should-not-run";
                                });
                          } catch (DependencyUnavailableException exception) {
                            inFlightError[0] = exception;
                          }
                          throw new IOException("probe failed");
                        }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> assertThat(error).hasMessage("依赖连续失败已熔断: model-chat"));

    assertThat(gaugeDuringProbe[0]).isEqualTo(1.0);
    assertThat(inFlightExecutions.get()).isZero();
    assertThat(inFlightError[0]).isNotNull();
    assertThat(inFlightError[0].failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);

    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    () -> {
                      throw new IllegalStateException("must-not-run-after-reopen");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN));
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", "model-chat")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("dependency.circuit.probe")
                .tag("dependency", "model-chat")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(1.0);

    nanoTime.set(2L * openWindowNanos);
    assertThat(executor.executeNoRetry("model-chat", () -> "recovered")).isEqualTo("recovered");
    assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(0.0);
    assertThat(
            registry
                .get("dependency.circuit.probe")
                .tag("dependency", "model-chat")
                .tag("result", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(executor.executeNoRetry("model-chat", () -> "normal call")).isEqualTo("normal call");
  }

  /**
   * F-1 交错硬化（卡 P-v）：CLOSED 期拿到许可的陈旧调用在探测飞行期间凑满阈值失败时， 不得打断在飞探测——探测成功必须回 CLOSED（gauge 归
   * 0、后续普通调用放行、陈旧窗口残值在 CLOSED 下无副作用）。
   *
   * <p>红测试（237ff31 基线）：陈旧开闸用无条件 {@code phase.set(OPEN)} 把飞行中的 PROBING 打成 OPEN， 探测成功的 {@code
   * CAS(PROBING→CLOSED)} 落空，gauge 仍为 1、要等陈旧窗口到期（2×window）才自愈。
   */
  @Test
  void staleCallTripDuringProbeFlightShouldNotBuryProbeSuccess() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1, nanoTime::get);
    long openWindowNanos = TimeUnit.MILLISECONDS.toNanos(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    CountDownLatch staleAcquired = new CountDownLatch(2);
    CountDownLatch staleProceed = new CountDownLatch(1);
    CountDownLatch probeStarted = new CountDownLatch(1);
    CountDownLatch probeProceed = new CountDownLatch(1);
    try {
      Future<DependencyUnavailableException> staleOne =
          pool.submit(
              () -> {
                return gatedFailure(executor, "qdrant", staleAcquired, staleProceed, "stale-1");
              });
      Future<DependencyUnavailableException> staleTwo =
          pool.submit(
              () -> {
                return gatedFailure(executor, "qdrant", staleAcquired, staleProceed, "stale-2");
              });
      assertThat(staleAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "qdrant",
                      () -> {
                        throw new IOException("threshold-1");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "qdrant",
                      () -> {
                        throw new IOException("threshold-2");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(1.0);

      nanoTime.set(openWindowNanos);
      Future<String> probe =
          pool.submit(
              () -> {
                return executor.executeNoRetry(
                    "qdrant",
                    (Callable<String>)
                        () -> {
                          probeStarted.countDown();
                          probeProceed.await(5, TimeUnit.SECONDS);
                          return "probe-ok";
                        });
              });
      assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(1.0);

      staleProceed.countDown();
      DependencyUnavailableException staleOneError = staleOne.get(5, TimeUnit.SECONDS);
      DependencyUnavailableException staleTwoError = staleTwo.get(5, TimeUnit.SECONDS);
      assertThat(staleOneError).isNotNull();
      assertThat(staleTwoError).isNotNull();
      assertThat(List.of(staleOneError.getMessage(), staleTwoError.getMessage()))
          .containsExactlyInAnyOrder("依赖调用失败: qdrant", "依赖连续失败已熔断: qdrant");
      assertThat(
              registry
                  .get("dependency.circuit.opened")
                  .tag("dependency", "qdrant")
                  .counter()
                  .count())
          .isEqualTo(2.0);
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(1.0);

      probeProceed.countDown();
      assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("probe-ok");
      assertThat(
              registry
                  .get("dependency.circuit.probe")
                  .tag("dependency", "qdrant")
                  .tag("result", "success")
                  .counter()
                  .count())
          .isEqualTo(1.0);
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(0.0);
      assertThat(executor.executeNoRetry("qdrant", () -> "normal call")).isEqualTo("normal call");

      nanoTime.set(2L * openWindowNanos);
      assertThat(executor.executeNoRetry("qdrant", () -> "after residue window"))
          .isEqualTo("after residue window");
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(0.0);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * F-1 交错硬化（卡 P-v）：陈旧调用凑满阈值的开闸决定不得打断在飞探测——探测飞行期间窗口到期（陈旧开闸写入的窗口） 不得放行第二个探测；探测失败必须经 {@code
   * CAS(PROBING→OPEN)} 权威回 OPEN 并重置完整窗口（覆盖陈旧调用留下的状态）。
   *
   * <p>红测试（237ff31 基线）：陈旧开闸把 PROBING 打成 OPEN 后其窗口到期会让第二个探测真实执行 （单探测不变量被破坏， 本用例在 t=2×window
   * 处直接抓到第二次外呼后返回）。
   */
  @Test
  void staleCallTripDuringProbeFlightShouldKeepProbeFailureAuthoritative() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong nanoTime = new AtomicLong();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1, nanoTime::get);
    long openWindowNanos = TimeUnit.MILLISECONDS.toNanos(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    CountDownLatch staleAcquired = new CountDownLatch(2);
    CountDownLatch staleProceed = new CountDownLatch(1);
    CountDownLatch probeStarted = new CountDownLatch(1);
    CountDownLatch probeProceed = new CountDownLatch(1);
    AtomicInteger strayProbeExecutions = new AtomicInteger();
    try {
      Future<DependencyUnavailableException> staleOne =
          pool.submit(
              () -> {
                return gatedFailure(executor, "model-chat", staleAcquired, staleProceed, "stale-1");
              });
      Future<DependencyUnavailableException> staleTwo =
          pool.submit(
              () -> {
                return gatedFailure(executor, "model-chat", staleAcquired, staleProceed, "stale-2");
              });
      assertThat(staleAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IOException("threshold-1");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IOException("threshold-2");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);

      nanoTime.set(openWindowNanos);
      Future<DependencyUnavailableException> probe =
          pool.submit(
              () -> {
                return gatedFailure(
                    executor, "model-chat", probeStarted, probeProceed, "probe failed");
              });
      assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();

      staleProceed.countDown();
      assertThat(staleOne.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThat(staleTwo.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(1.0);
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IllegalStateException("must-not-run-in-flight");
                      }))
          .isInstanceOfSatisfying(
              DependencyUnavailableException.class,
              error ->
                  assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN));

      nanoTime.set(2L * openWindowNanos);
      String strayOutcome;
      try {
        strayOutcome =
            executor.executeNoRetry(
                "model-chat",
                () -> {
                  strayProbeExecutions.incrementAndGet();
                  return "stray-probe-ran";
                });
      } catch (DependencyUnavailableException exception) {
        strayOutcome = "rejected:" + exception.failureType();
      }
      assertThat(strayOutcome).isEqualTo("rejected:CIRCUIT_OPEN");
      assertThat(strayProbeExecutions.get()).isZero();

      probeProceed.countDown();
      DependencyUnavailableException probeFailure = probe.get(5, TimeUnit.SECONDS);
      assertThat(probeFailure).isNotNull();
      assertThat(probeFailure).hasMessage("依赖连续失败已熔断: model-chat");
      assertThat(
              registry
                  .get("dependency.circuit.probe")
                  .tag("dependency", "model-chat")
                  .tag("result", "failure")
                  .counter()
                  .count())
          .isEqualTo(1.0);
      assertThat(
              registry
                  .get("dependency.circuit.opened")
                  .tag("dependency", "model-chat")
                  .counter()
                  .count())
          .isEqualTo(3.0);
      assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(1.0);

      nanoTime.set(2L * openWindowNanos + 1);
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "model-chat",
                      () -> {
                        throw new IllegalStateException("must-not-run-after-reopen");
                      }))
          .isInstanceOfSatisfying(
              DependencyUnavailableException.class,
              error ->
                  assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN));

      nanoTime.set(3L * openWindowNanos);
      assertThat(executor.executeNoRetry("model-chat", () -> "recovered")).isEqualTo("recovered");
      assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(0.0);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * F-1 交错用例的受闸调用：许可获取后进入操作体、报“已进入外呼”（acquired）， 等 proceed 放行后必定失败并返回捕获的异常； 若操作意外成功返回 null（由调用方断言）。
   */
  private static DependencyUnavailableException gatedFailure(
      DependencyResilienceExecutor executor,
      String dependency,
      CountDownLatch acquired,
      CountDownLatch proceed,
      String message)
      throws InterruptedException {
    try {
      executor.executeNoRetry(
          dependency,
          (Callable<String>)
              () -> {
                acquired.countDown();
                proceed.await(5, TimeUnit.SECONDS);
                throw new IOException(message);
              });
    } catch (DependencyUnavailableException exception) {
      return exception;
    }
    return null;
  }

  private static double circuitOpenGauge(SimpleMeterRegistry registry, String dependency) {
    return registry.get("dependency.circuit.open").tag("dependency", dependency).gauge().value();
  }

  @Test
  void shouldSupportRecoveryPolicyMapping() {
    DependencyRecoveryPolicy<String, String> policy =
        new DependencyRecoveryPolicy<>() {
          @Override
          public String onRecoverableFailure(String target, DependencyUnavailableException error) {
            return "PENDING";
          }

          @Override
          public String onPermanentFailure(String target, Throwable error) {
            return "FAILED";
          }
        };
    DependencyUnavailableException recoverable =
        DependencyUnavailableException.circuitOpen("qdrant");

    assertThat(policy.onRecoverableFailure("file-1", recoverable)).isEqualTo("PENDING");
    assertThat(policy.onPermanentFailure("file-1", new IllegalStateException("bad file")))
        .isEqualTo("FAILED");
  }

  @Test
  void f3NonRetryableAtThresholdShouldNotTripCircuitOrConsumeBudget() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 60000);

    // 第 1 次：可恢复失败，连续失败计 1
    assertThatThrownBy(
            () ->
                executor.execute(
                    "qdrant",
                    () -> {
                      throw new IOException("network error");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CALL_FAILED);
            });

    // 第 2 次（凑阈值那次）：抛不可重试异常（IllegalArgumentException，默认谓词判定不可重试）
    // F-3 修复后期望：NON_RETRYABLE 且不熔断开闸
    // 现状缺陷：执行 markFailure 凑满 threshold=2，直接开闸并报 CALL_FAILED "依赖连续失败已熔断: qdrant"
    assertThatThrownBy(
            () ->
                executor.execute(
                    "qdrant",
                    () -> {
                      throw new IllegalArgumentException("invalid argument");
                    }))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.NON_RETRYABLE);
              assertThat(error.getMessage()).contains("依赖调用不可重试");
            });

    // 熔断器必须未开闸：第 3 次调用仍可正常放行（证明未开闸）
    assertThat(executor.execute("qdrant", () -> "healthy")).isEqualTo("healthy");
    assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(0.0);

    // 指标 counter 校验：不可重试与可恢复失败均记 dependency.call{result=failure}
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", "qdrant")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(2.0);
    // 熔断器未开闸
    assertThat(registry.find("dependency.circuit.opened").tag("dependency", "qdrant").counter())
        .isNull();
  }

  @Test
  void dynamicFailureThresholdShouldTakeEffectOnSubsequentCalls() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MutableDynamicConfigService configService = new MutableDynamicConfigService();
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(configService);
    DependencyResilienceExecutor executor = new DependencyResilienceExecutor(registry, resolver);

    // 运行时调低阈值为 2：写入 platform.resilience.failure-threshold = 2
    configService.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 2);

    // 第 1 次单次失败
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    () -> {
                      throw new IOException("fail 1");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);

    // 第 2 次单次失败（在新阈值 2 下应触发开闸）
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    () -> {
                      throw new IOException("fail 2");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);

    // 新调用按新阈值 2 开闸拒绝
    assertThatThrownBy(() -> executor.executeNoRetry("model-chat", () -> "healthy"))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
            });
  }

  @Test
  void inFlightOpenWindowShouldNotBeRetroactivelyAdjusted() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MutableDynamicConfigService configService = new MutableDynamicConfigService();
    configService.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, 1);
    configService.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, 10000L); // 10秒

    AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
    ResilienceConfigResolver resolver = new ResilienceConfigResolver(configService);
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(
            registry,
            1,
            0,
            2.0,
            resolver::resolveFailureThreshold,
            resolver::resolveOpenDurationMillis,
            nanoTime::get);

    // 触发失败立即开闸（threshold=1）
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "model-chat",
                    () -> {
                      throw new IOException("trip");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(1.0);

    // 在飞 OPEN 期间试图将保持时长缩短为 100ms
    configService.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, 100L);

    // 推进 500ms（大于 100ms 但小于 10000ms）
    nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));

    // 在飞窗口不得被追溯缩短，依然被拒绝
    assertThatThrownBy(() -> executor.executeNoRetry("model-chat", () -> "healthy"))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            error -> {
              assertThat(error.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
            });

    // 推进到 10000ms 之后，单探测放行
    nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(10000));
    assertThat(executor.executeNoRetry("model-chat", () -> "recovered")).isEqualTo("recovered");
    assertThat(circuitOpenGauge(registry, "model-chat")).isEqualTo(0.0);
  }

  @Test
  void deletedOrInvalidConfigKeysShouldFallbackToDefaultsFailSafe() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MutableDynamicConfigService configService = new MutableDynamicConfigService();
    // 非法值：threshold=-2, duration=-500
    configService.put(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD, -2);
    configService.put(ResilienceConfigResolver.KEY_OPEN_DURATION_MS, -500L);

    ResilienceConfigResolver resolver = new ResilienceConfigResolver(configService);
    DependencyResilienceExecutor executor = new DependencyResilienceExecutor(registry, resolver);

    // 回落默认阈值 5：前 4 次失败均不开闸
    for (int i = 1; i <= 4; i++) {
      assertThatThrownBy(
              () ->
                  executor.executeNoRetry(
                      "qdrant",
                      () -> {
                        throw new IOException("fail");
                      }))
          .isInstanceOf(DependencyUnavailableException.class);
      assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(0.0);
    }

    // 第 5 次失败触发开闸（默认阈值为 5）
    assertThatThrownBy(
            () ->
                executor.executeNoRetry(
                    "qdrant",
                    () -> {
                      throw new IOException("fail 5");
                    }))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThat(circuitOpenGauge(registry, "qdrant")).isEqualTo(1.0);

    // 删键后回落默认
    configService.remove(ResilienceConfigResolver.KEY_FAILURE_THRESHOLD);
    configService.remove(ResilienceConfigResolver.KEY_OPEN_DURATION_MS);
    assertThat(resolver.resolveFailureThreshold()).isEqualTo(5);
    assertThat(resolver.resolveOpenDurationMillis()).isEqualTo(30000L);
  }

  static final class MutableDynamicConfigService implements DynamicConfigService {
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    void put(String key, Object value) {
      if (value == null) {
        values.remove(key);
      } else {
        values.put(key, value);
      }
    }

    void remove(String key) {
      values.remove(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
      Object val = values.get(key);
      if (val != null && type.isInstance(val)) {
        return (T) val;
      }
      return defaultValue;
    }
  }
}
