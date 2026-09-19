package com.slz.crm.platform.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** 向量库健康分级测试。 */
class VectorStoreHealthIndicatorTest {

  @Test
  void shouldReturnWarnWhenInMemoryFallbackIsActive() {
    ObjectProvider<CrmVectorStoreHealth> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(new FixedHealth(true, true));
    VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(provider);

    Health health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("WARN");
    assertThat(health.getDetails())
        .containsEntry("inMemoryFallback", true)
        .containsEntry("component", "vector-store-test");
  }

  @Test
  void shouldReturnDownWhenProbeFailsWithoutFallback() {
    ObjectProvider<CrmVectorStoreHealth> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(new FixedHealth(false, false));
    VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(provider);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void shouldReturnUpWhenProbeSucceeds() {
    ObjectProvider<CrmVectorStoreHealth> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(new FixedHealth(false, true));
    VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(provider);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void shouldReturnUpWhenLaneBImplementationIsMissing() {
    ObjectProvider<CrmVectorStoreHealth> provider = Mockito.mock(ObjectProvider.class);
    Mockito.when(provider.getIfAvailable()).thenReturn(null);
    VectorStoreHealthIndicator indicator = new VectorStoreHealthIndicator(provider);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
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
}
