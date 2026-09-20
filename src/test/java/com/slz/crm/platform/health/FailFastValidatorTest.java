package com.slz.crm.platform.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * 启动期 Fail-Fast 校验测试（TASK-10 AC1）。
 *
 * <p>三条不变式：① 必需依赖非 UP 即抛异常阻断启动，且异常指名组件与原因； ② 单次探测被 {@code app.dependency.health-timeout-ms}
 * 硬截断，慢依赖不得拖住启动； ③ provider 为 in-memory 时对应组件不参与校验，且缺指示器属于配置错误（必须报错，不得静默放行）。
 */
class FailFastValidatorTest {

  private static final Status DEGRADED = new Status("WARN", "内存回退降级");
  private static final long TIMEOUT_MS = 300;

  private final HealthContributorRegistry registry = mock(HealthContributorRegistry.class);

  private FailFastValidator validator(String vectorStoreProvider, String storageProvider) {
    return validator(vectorStoreProvider, storageProvider, TIMEOUT_MS);
  }

  private FailFastValidator validator(
      String vectorStoreProvider, String storageProvider, long timeoutMs) {
    @SuppressWarnings("unchecked")
    ObjectProvider<HealthContributorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(registry);
    return new FailFastValidator(provider, vectorStoreProvider, storageProvider, timeoutMs);
  }

  private void contributor(String name, HealthIndicator indicator) {
    when(registry.getContributor(name)).thenReturn(indicator);
  }

  @Test
  void shouldStartWhenEveryRequiredComponentIsUp() {
    contributor("db", () -> Health.up().withDetail("database", "MySQL").build());
    contributor(
        "vectorStore", () -> Health.up().withDetail("collection", "knowledge_chunk").build());
    contributor("minio", () -> Health.up().withDetail("bucket", "knowledge-files").build());

    assertThatCode(validator("qdrant", "minio")::validateDependencies).doesNotThrowAnyException();
  }

  @Test
  void shouldFailWithMysqlReasonWhenDatabaseIsDown() {
    contributor(
        "db",
        () ->
            Health.down()
                .withDetail("error", "java.sql.SQLException: Connection refused")
                .withDetail("database", "MySQL")
                .build());

    assertThatThrownBy(validator("qdrant", "minio")::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MySQL 数据库不可达")
        .hasMessageContaining("Connection refused")
        .hasMessageContaining("database=MySQL");
  }

  @Test
  void shouldFailWithQdrantReasonWhenVectorStoreIsDown() {
    contributor("db", () -> Health.up().build());
    contributor(
        "vectorStore",
        () ->
            Health.down()
                .withDetail("host", "127.0.0.1")
                .withDetail("port", 6334)
                .withDetail("collection", "knowledge_chunk")
                .build());

    assertThatThrownBy(validator("qdrant", "minio")::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Qdrant 向量库不可达")
        .hasMessageContaining("collection=knowledge_chunk");
  }

  @Test
  void shouldFailWithMinioReasonWhenObjectStorageIsDown() {
    contributor("db", () -> Health.up().build());
    contributor("vectorStore", () -> Health.up().build());
    contributor(
        "minio",
        () ->
            Health.down()
                .withDetail("endpoint", "http://127.0.0.1:9000")
                .withDetail("error", "java.io.IOException: Connection refused")
                .build());

    assertThatThrownBy(validator("qdrant", "minio")::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MinIO 对象存储不可达")
        .hasMessageContaining("http://127.0.0.1:9000");
  }

  @Test
  void shouldRejectStartupWhenQdrantProviderReportsDegradedStatus() {
    contributor("db", () -> Health.up().build());
    contributor("vectorStore", () -> Health.status(DEGRADED).build());

    assertThatThrownBy(validator("qdrant", "in-memory")::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Qdrant 向量库")
        .hasMessageContaining("WARN");
  }

  @Test
  void shouldProbeOnlyDatabaseWhenProvidersFallBackToInMemory() {
    contributor("db", () -> Health.up().build());

    assertThatCode(validator("in-memory", "in-memory")::validateDependencies)
        .doesNotThrowAnyException();

    verify(registry, never()).getContributor("vectorStore");
    verify(registry, never()).getContributor("minio");
  }

  @Test
  void shouldFailWhenRequiredContributorIsMissingFromRegistry() {
    contributor("db", () -> Health.up().build());

    assertThatThrownBy(validator("in-memory", "minio")::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MinIO 对象存储健康指示器未装配");
  }

  @Test
  void shouldCutOffHangingProbeWithinTimeoutBudget() {
    contributor("db", () -> sleepThenUp(5));
    FailFastValidator validator = validator("in-memory", "in-memory", 200);

    long start = System.nanoTime();
    assertThatThrownBy(validator::validateDependencies)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MySQL 数据库")
        .hasMessageContaining("超时");

    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
  }

  @Test
  void shouldNotBlockStartupWhenActuatorRegistryIsAbsent() {
    @SuppressWarnings("unchecked")
    ObjectProvider<HealthContributorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    FailFastValidator validator = new FailFastValidator(provider, "qdrant", "minio", TIMEOUT_MS);

    assertThatCode(validator::validateDependencies).doesNotThrowAnyException();
  }

  private Health sleepThenUp(long seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    return Health.up().withDetail("slept", seconds).build();
  }
}
