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
 *
 * <p>TASK-10：DOWN 必须自带 endpoint / bucket / error 明细。此前只返回一个裸 DOWN， {@code /actuator/health} 与启动期
 * {@link FailFastValidator} 都答不出"哪个端点、什么原因"， 运维只能登进容器翻日志。
 */
@Component("minioHealthIndicator")
@ConditionalOnBean(MinioClient.class)
public class MinioHealthIndicator implements HealthIndicator {

  private final MinioClient minioClient;
  private final String endpoint;
  private final String bucket;

  public MinioHealthIndicator(
      MinioClient minioClient,
      @Value("${knowledge.minio.endpoint:http://localhost:9000}") String endpoint,
      @Value("${knowledge.minio.bucket:knowledge-files}") String bucket) {
    this.minioClient = minioClient;
    this.endpoint = endpoint;
    this.bucket = bucket;
  }

  @Override
  public Health health() {
    Health result;
    try {
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (exists) {
        result = up().build();
      } else {
        result = down().withDetail("message", "MinIO 可达但存储桶不存在").build();
      }
    } catch (Exception exception) {
      result = down().withDetail("error", describe(exception)).build();
    }
    return result;
  }

  private Health.Builder up() {
    return Health.up().withDetail("endpoint", endpoint).withDetail("bucket", bucket);
  }

  private Health.Builder down() {
    return Health.status(Status.DOWN).withDetail("endpoint", endpoint).withDetail("bucket", bucket);
  }

  private static String describe(Exception exception) {
    return exception.getClass().getName() + ": " + exception.getMessage();
  }
}
