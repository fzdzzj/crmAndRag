package com.slz.crm.knowledge.storage;

import com.slz.crm.knowledge.storage.properties.MinioProperties;
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

  /** 注入已构建的 MinIO 客户端，避免服务类绑定配置细节。 */
  public MinioFileStorageService(MinioClient minioClient, MinioProperties properties) {
    this.minioClient = minioClient;
    this.properties = properties;
  }

  @Override
  public String store(InputStream content, String filename, String contentType) {
    String storageKey = buildStorageKey(filename);
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

  @Override
  public InputStream open(String storageKey) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(properties.getBucket()).object(storageKey).build());
    } catch (Exception exception) {
      throw new StorageException("从 MinIO 读取文件失败: " + storageKey, exception);
    }
  }

  @Override
  public void delete(String storageKey) {
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder().bucket(properties.getBucket()).object(storageKey).build());
    } catch (Exception exception) {
      throw new StorageException("从 MinIO 删除文件失败: " + storageKey, exception);
    }
  }

  @Override
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
}
