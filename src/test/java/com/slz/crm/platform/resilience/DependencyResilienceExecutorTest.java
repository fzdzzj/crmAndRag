package com.slz.crm.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 1, 1);
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
    TimeUnit.MILLISECONDS.sleep(5);

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
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1);

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
    TimeUnit.MILLISECONDS.sleep(5);

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
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 2, 1);

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
    TimeUnit.MILLISECONDS.sleep(5);

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
    DependencyResilienceExecutor executor =
        new DependencyResilienceExecutor(registry, 1, 0, 2.0, 1, 1);
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
    TimeUnit.MILLISECONDS.sleep(5);

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
}
