package com.slz.crm.platform.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** 向量库健康分级与失败原因明细测试（TASK-10 AC2）。 */
class VectorStoreHealthIndicatorTest {

  private static final String HOST = "127.0.0.1";
  private static final int PORT = 6334;

  private VectorStoreHealthIndicator indicatorWith(CrmVectorStoreHealth storeHealth) {
    ObjectProvider<CrmVectorStoreHealth> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(storeHealth);
    return new VectorStoreHealthIndicator(provider, HOST, PORT);
  }

  @Test
  void shouldReturnWarnWhenInMemoryFallbackIsActive() {
    Health health = indicatorWith(new FixedHealth(true, true)).health();

    assertThat(health.getStatus().getCode()).isEqualTo("WARN");
    assertThat(health.getDetails())
        .containsEntry("inMemoryFallback", true)
        .containsEntry("component", "vector-store-test")
        // 内存回退时 qdrant 的 host/port 无意义，不得出现在明细里误导运维
        .doesNotContainKeys("host", "port");
  }

  @Test
  void shouldReportHostPortAndCollectionWhenProbeFails() {
    Health health = indicatorWith(new FixedHealth(false, false)).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("component", "vector-store-test")
        .containsEntry("collection", "knowledge_chunk")
        .containsEntry("host", HOST)
        .containsEntry("port", PORT)
        .containsEntry("inMemoryFallback", false)
        .containsEntry("message", "Qdrant 集合探测失败（collectionExists 返回不可用）");
  }

  @Test
  void shouldReportErrorDetailWhenProbeThrows() {
    Health health = indicatorWith(new ThrowingHealth()).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("host", HOST)
        .containsEntry("port", PORT)
        .containsEntry("error", "java.lang.RuntimeException: UNAVAILABLE: no connection")
        .containsEntry("collection", "knowledge_chunk");
  }

  @Test
  void shouldReturnUpWhenProbeSucceeds() {
    Health health = indicatorWith(new FixedHealth(false, true)).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("host", HOST)
        .containsEntry("port", PORT)
        .containsEntry("collection", "knowledge_chunk");
  }

  @Test
  void shouldReturnUpWhenLaneBImplementationIsMissing() {
    Health health = indicatorWith(null).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("component", "not-configured");
  }

  private record FixedHealth(boolean inMemoryFallback, boolean probe)
      implements CrmVectorStoreHealth {
    @Override
    public String componentName() {
      return "vector-store-test";
    }

    @Override
    public String collectionName() {
      return "knowledge_chunk";
    }
  }

  /** 探测直接抛异常的实现：模拟契约实现未兜住的底层错误。 */
  private static final class ThrowingHealth implements CrmVectorStoreHealth {
    @Override
    public String componentName() {
      return "vector-store-test";
    }

    @Override
    public boolean inMemoryFallback() {
      return false;
    }

    @Override
    public String collectionName() {
      return "knowledge_chunk";
    }

    @Override
    public boolean probe() {
      throw new RuntimeException("UNAVAILABLE: no connection");
    }
  }
}
