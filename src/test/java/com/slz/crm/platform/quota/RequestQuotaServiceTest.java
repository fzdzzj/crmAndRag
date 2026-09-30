package com.slz.crm.platform.quota;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 四层请求配额边界测试。 */
class RequestQuotaServiceTest {

  private QuotaProperties properties;
  private SimpleMeterRegistry meterRegistry;
  private RequestQuotaService service;

  @BeforeEach
  void setUp() {
    properties = new QuotaProperties();
    properties.setUserPerMinute(2);
    properties.setAdminVectorUserPerMinute(3);
    properties.setIpPerMinute(3);
    properties.setKnowledgeBasePerMinute(4);
    properties.setGlobalPerMinute(100);
    meterRegistry = new SimpleMeterRegistry();
    service = new RequestQuotaService(properties, meterRegistry);
  }

  @Test
  void shouldRejectAfterUserLimit() {
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    QuotaDecision denied = service.tryAcquire(QuotaDimension.USER, "user:1");

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.used()).isEqualTo(2);
    assertThat(denied.retryAfterSeconds()).isPositive();
  }

  @Test
  void shouldRejectAdminVectorUserAfterThreeRequestsWithoutAffectingGenericUserQuota() {
    for (int i = 0; i < 3; i++) {
      assertThat(service.tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "user:1").allowed()).isTrue();
    }
    assertThat(service.tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "user:1").allowed()).isFalse();
    assertThat(service.tryAcquire(QuotaDimension.ADMIN_VECTOR_USER, "user:2").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
  }

  @Test
  void shouldIsolateUsersAndDimensions() {
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:2").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.IP, "10.0.0.1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.KNOWLEDGE_BASE, "3").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.GLOBAL, "GLOBAL").allowed()).isTrue();
  }

  @Test
  void shouldBindTopLevelWindowsCacheIntoMeterRegistry() {
    Gauge gauge = meterRegistry.find("cache.size").tags("cache", "quota.windows").gauge();
    assertThat(gauge).isNotNull();
    assertThat(gauge.value()).isZero();

    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(meterRegistry.get("cache.size").tags("cache", "quota.windows").gauge().value())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("cache.gets")
                .tags("cache", "quota.windows", "result", "miss")
                .functionCounter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldExposeDimensionCacheHitMissAndSize() {
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();

    assertThat(
            meterRegistry
                .get("cache.gets")
                .tags("cache", "quota.user", "result", "miss")
                .functionCounter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("cache.size").tags("cache", "quota.user").gauge().value())
        .isEqualTo(1.0);

    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(
            meterRegistry
                .get("cache.gets")
                .tags("cache", "quota.user", "result", "hit")
                .functionCounter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void shouldIsolateCacheMetricsAcrossDimensions() {
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.IP, "10.0.0.1").allowed()).isTrue();

    assertThat(
            meterRegistry
                .get("cache.gets")
                .tags("cache", "quota.user", "result", "miss")
                .functionCounter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("cache.gets")
                .tags("cache", "quota.ip", "result", "miss")
                .functionCounter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("cache.size").tags("cache", "quota.ip").gauge().value())
        .isEqualTo(1.0);
  }
}
