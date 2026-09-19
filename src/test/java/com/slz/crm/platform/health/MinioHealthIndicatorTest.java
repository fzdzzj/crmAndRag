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

/** MinIO 健康探测与条件装配测试。 */
@ExtendWith(MockitoExtension.class)
class MinioHealthIndicatorTest {

  @Mock private MinioClient minioClient;

  @Test
  void shouldReturnUpWhenBucketExists() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, "knowledge-files");

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void shouldReturnDownWhenBucketIsMissing() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
    MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, "knowledge-files");

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("message", "MinIO 可达但存储桶不存在");
  }

  @Test
  void shouldReturnDownWhenMinioIsUnavailable() throws Exception {
    when(minioClient.bucketExists(any(BucketExistsArgs.class)))
        .thenThrow(new IOException("unavailable"));
    MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, "knowledge-files");

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void shouldRegisterOnlyWhenMinioClientBeanExists() {
    new ApplicationContextRunner()
        .withUserConfiguration(MinioConfiguration.class, MinioHealthIndicator.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MinioHealthIndicator.class);
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
