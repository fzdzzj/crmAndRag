package com.slz.crm.platform.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 健康探测、失败原因明细与条件装配测试。
 *
 * <p>明细要求（TASK-10 AC2）：DOWN 必须自带 endpoint / bucket / error， 让 {@code /actuator/health} 与 Fail-Fast
 * 都能指名"哪个组件、哪个端点、什么原因"。
 */
@ExtendWith(MockitoExtension.class)
class MinioHealthIndicatorTest {

  private static final String ENDPOINT = "http://127.0.0.1:9000";
  private static final String BUCKET = "knowledge-files";

  @Mock private MinioClient minioClient;

  private MinioHealthIndicator indicator() {
    return new MinioHealthIndicator(minioClient, ENDPOINT, BUCKET);
  }

  @Test
  void shouldReturnUpWhenBucketExists() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    var health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("endpoint", ENDPOINT)
        .containsEntry("bucket", BUCKET);
  }

  @Test
  void shouldReturnDownWhenBucketIsMissing() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

    var health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("endpoint", ENDPOINT)
        .containsEntry("bucket", BUCKET)
        .containsEntry("message", "MinIO 可达但存储桶不存在");
  }

  @Test
  void shouldReportEndpointAndErrorWhenMinioIsUnavailable() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class)))
        .thenThrow(new IOException("Connection refused"));

    var health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("endpoint", ENDPOINT)
        .containsEntry("bucket", BUCKET)
        .containsEntry("error", "java.io.IOException: Connection refused");
  }

  @Test
  void shouldRegisterOnlyWhenMinioClientBeanExists() {
    new ApplicationContextRunner()
        .withPropertyValues("knowledge.minio.endpoint=" + ENDPOINT)
        .withUserConfiguration(MinioConfiguration.class, MinioHealthIndicator.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MinioHealthIndicator.class);
              assertThat(context.getBean(MinioHealthIndicator.class).health().getStatus())
                  .isEqualTo(Status.DOWN);
            });
    new ApplicationContextRunner()
        .withUserConfiguration(MinioHealthIndicator.class)
        .run(context -> assertThat(context).doesNotHaveBean(MinioHealthIndicator.class));
  }

  @Configuration
  static class MinioConfiguration {
    @Bean
    MinioClient minioClient() {
      return Mockito.mock(MinioClient.class);
    }
  }
}
