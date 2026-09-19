package com.slz.crm.platform.health;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * MinIO 存储健康指示器。
 *
 * <p>仅当 Lane B 暴露 {@link MinioClient} 时装配；通过 bucketExists 同时确认 服务可达和配置桶存在，探测失败不会影响 liveness。
 */
@Component("minioHealthIndicator")
@ConditionalOnBean(MinioClient.class)
public class MinioHealthIndicator implements HealthIndicator {

  private final MinioClient minioClient;
  private final String bucket;

  public MinioHealthIndicator(
      MinioClient minioClient, @Value("${knowledge.minio.bucket:knowledge-files}") String bucket) {
    this.minioClient = minioClient;
    this.bucket = bucket;
  }

  @Override
  public Health health() {
    try {
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (exists) {
        return Health.up().withDetail("bucket", bucket).build();
      }
      return Health.status(Status.DOWN)
          .withDetail("bucket", bucket)
          .withDetail("message", "MinIO 可达但存储桶不存在")
          .build();
    } catch (Exception exception) {
      return Health.down(exception).withDetail("bucket", bucket).build();
    }
  }
}
