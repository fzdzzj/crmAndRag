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
