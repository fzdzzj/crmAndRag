package com.slz.crm.knowledge.storage;

import com.slz.crm.knowledge.storage.properties.MinioProperties;
import com.slz.crm.platform.resilience.DependencyFailureType;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import io.micrometer.core.instrument.Metrics;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** MinIO 文件存储实现。 */
public class MinioFileStorageService implements FileStorageService {
  /** 每个对象读取时的 5MB 缓冲，兼顾内存与速度。 */
  private static final int PART_SIZE = 5 * 1024 * 1024;

  private final MinioClient minioClient;
  private final MinioProperties properties;

  /** wire-dependency-circuit-breaker 任务 4：三方法共用的依赖名（熔断状态与指标按此隔离）。 */
  private static final String DEPENDENCY = "storage-minio";

  private final DependencyResilienceExecutor resilience;

  /** 手工装配入口：自带独立熔断执行器（Micrometer 全局注册表），熔断状态不与其它实例共享。 */
  public MinioFileStorageService(MinioClient minioClient, MinioProperties properties) {
    this(minioClient, properties, new DependencyResilienceExecutor(Metrics.globalRegistry));
  }

  /**
   * 注入已构建的 MinIO 客户端与依赖韧性执行器（wire-dependency-circuit-breaker 任务 4）。
   *
   * <p>熔断层只计数与 OPEN 快速拒绝，零自动重试；{@code exists()} 属不接线面，其失败既不抛也不计入依赖。
   *
   * @param minioClient MinIO 客户端
   * @param properties 桶与端点配置
   * @param resilience 依赖韧性执行器
   */
  public MinioFileStorageService(
      MinioClient minioClient,
      MinioProperties properties,
      DependencyResilienceExecutor resilience) {
    this.minioClient = minioClient;
    this.properties = properties;
    this.resilience = resilience;
  }

  @Override
  public String store(InputStream content, String filename, String contentType) {
    String storageKey = buildStorageKey(filename);
    try {
      return resilience.executeNoRetry(
          DEPENDENCY, () -> putObject(storageKey, content, contentType));
    } catch (DependencyUnavailableException exception) {
      throw toFacadeFailure(exception);
    }
  }

  @Override
  public InputStream open(String storageKey) {
    try {
      return resilience.executeNoRetry(DEPENDENCY, () -> getObject(storageKey));
    } catch (DependencyUnavailableException exception) {
      throw toFacadeFailure(exception);
    }
  }

  @Override
  public void delete(String storageKey) {
    try {
      resilience.executeNoRetry(
          DEPENDENCY,
          () -> {
            removeObject(storageKey);
            return null;
          });
    } catch (DependencyUnavailableException exception) {
      throw toFacadeFailure(exception);
    }
  }

  /** 既有异常消息格式零改动：真实外呼失败仍包成 StorageException。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // MinIO SDK 外呼多源抛出，统一包装为 StorageException
  private String putObject(String storageKey, InputStream content, String contentType) {
    try {
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(properties.getBucket())
              .object(storageKey)
              .contentType(contentType == null ? "application/octet-stream" : contentType)
              .stream(content, -1, PART_SIZE)
              .build());
      return storageKey;
    } catch (Exception exception) {
      throw new StorageException("保存文件到 MinIO 失败: " + storageKey, exception);
    }
  }

  /** 既有异常消息格式零改动：真实外呼失败仍包成 StorageException。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // MinIO SDK 外呼多源抛出，统一包装为 StorageException
  private InputStream getObject(String storageKey) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(properties.getBucket()).object(storageKey).build());
    } catch (Exception exception) {
      throw new StorageException("从 MinIO 读取文件失败: " + storageKey, exception);
    }
  }

  /** 既有异常消息格式零改动：真实外呼失败仍包成 StorageException。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // MinIO SDK 外呼多源抛出，统一包装为 StorageException
  private void removeObject(String storageKey) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder().bucket(properties.getBucket()).object(storageKey).build());
    } catch (Exception exception) {
      throw new StorageException("从 MinIO 删除文件失败: " + storageKey, exception);
    }
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 存在性探针边界：外呼失败按不存在处理不抛
  public boolean exists(String storageKey) {
    boolean result = false;
    try {
      minioClient.statObject(
          StatObjectArgs.builder().bucket(properties.getBucket()).object(storageKey).build());
      result = true;
    } catch (Exception exception) {
      result = false;
    }
    return result;
  }

  /** 生成日期前缀 + UUID 的对象 Key，避免中文文件名和重名问题。 */
  private String buildStorageKey(String filename) {
    String safeName = filename == null ? "file" : filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return "knowledge/" + date + "/" + UUID.randomUUID() + "-" + safeName;
  }

  /**
   * 熔断层异常转回门面既有异常类型（wire-dependency-circuit-breaker 任务 4）。
   *
   * <p>真实调用失败原样透传内层已包装的 {@link StorageException}（消息与 cause 链零变化）；OPEN 快速拒绝转成 {@link
   * StorageException}，cause 为 {@link DependencyUnavailableException.CircuitOpenException}——{@link
   * DependencyUnavailableException} 本身不出现在门面链上。
   */
  private static RuntimeException toFacadeFailure(DependencyUnavailableException exception) {
    Throwable cause = exception.getCause();
    RuntimeException failure;
    if (exception.failureType() == DependencyFailureType.CIRCUIT_OPEN) {
      failure = new StorageException("MinIO 调用被熔断快速拒绝: " + DEPENDENCY, cause);
    } else if (cause instanceof RuntimeException runtimeException) {
      failure = runtimeException;
    } else {
      failure = new StorageException("MinIO 调用失败", cause);
    }
    return failure;
  }
}
