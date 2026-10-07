package com.slz.crm.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
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
