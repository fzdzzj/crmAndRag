package com.slz.crm.knowledge.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 内存文件存储实现，仅用于单元测试或离线本地验证，严禁生产使用。 */
public class InMemoryFileStorageService implements FileStorageService {
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  @Override
  public String store(InputStream content, String filename, String contentType) {
    try {
      String storageKey =
          "memory/"
              + UUID.randomUUID()
              + "/"
              + (filename == null ? "file" : filename.replaceAll("[\\\\/:*?\"<>|]", "_"));
      objects.put(storageKey, content.readAllBytes());
      return storageKey;
    } catch (Exception exception) {
      throw new StorageException("保存内存文件失败", exception);
    }
  }

  @Override
  public InputStream open(String storageKey) {
    byte[] content = objects.get(storageKey);
    if (content == null) {
      throw new StorageException("内存文件不存在: " + storageKey);
    }
    return new ByteArrayInputStream(content);
  }

  @Override
  public void delete(String storageKey) {
    objects.remove(storageKey);
  }

  @Override
  public boolean exists(String storageKey) {
    return objects.containsKey(storageKey);
  }
}
