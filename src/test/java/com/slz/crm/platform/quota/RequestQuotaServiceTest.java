package com.slz.crm.platform.quota;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 四层请求配额边界测试。 */
class RequestQuotaServiceTest {

  private QuotaProperties properties;
  private RequestQuotaService service;

  @BeforeEach
  void setUp() {
    properties = new QuotaProperties();
    properties.setUserPerMinute(2);
    properties.setIpPerMinute(3);
    properties.setKnowledgeBasePerMinute(4);
    properties.setGlobalPerMinute(100);
    service = new RequestQuotaService(properties, new SimpleMeterRegistry());
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
  void shouldIsolateUsersAndDimensions() {
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.USER, "user:2").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.IP, "10.0.0.1").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.KNOWLEDGE_BASE, "3").allowed()).isTrue();
    assertThat(service.tryAcquire(QuotaDimension.GLOBAL, "GLOBAL").allowed()).isTrue();
  }
}
