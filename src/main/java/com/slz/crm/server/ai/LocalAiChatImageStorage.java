package com.slz.crm.server.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CRM 本地文件图片存储实现。
 *
 * <p>MinIO 平台能力由独立基础设施层接入；在当前融合基座尚未提供共享 Bean 前， 本地实现保证字节可恢复，且不依赖知识库 uploaded_file。
 */
@Component
public class LocalAiChatImageStorage implements AiChatImageStorage {

  private static final Map<String, String> EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");

  private final Path root;

  public LocalAiChatImageStorage(@Value("${slz.file.path:./file}") String rootPath) {
    this.root = Path.of(rootPath).toAbsolutePath().normalize();
  }

  @Override
  public String backend() {
    return "local";
  }

  @Override
  public String store(Long sessionId, String imageHash, byte[] content, String contentType) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("图片内容不能为空");
    }
    String storageKey = buildKey(sessionId, imageHash, contentType);
    try {
      Path target = resolveSafe(storageKey);
      Files.createDirectories(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      return storageKey;
    } catch (IOException exception) {
      throw new UncheckedIOException("聊天图片保存失败", exception);
    }
  }

  @Override
  public byte[] read(String storageKey) {
    try {
      return Files.readAllBytes(resolveSafe(storageKey));
    } catch (IOException exception) {
      throw new UncheckedIOException("聊天图片读取失败", exception);
    }
  }

  private String buildKey(Long sessionId, String imageHash, String contentType) {
    String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
    String extension =
        EXTENSIONS.getOrDefault(
            contentType == null ? "" : contentType.toLowerCase(Locale.ROOT), "bin");
    return "ai-chat-images/" + datePath + "/" + sessionId + "/" + imageHash + "." + extension;
  }

  private Path resolveSafe(String storageKey) {
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("非法图片存储键");
    }
    return target;
  }

  public static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 缺少 SHA-256", exception);
    }
  }

  static boolean isHex64(String value) {
    return value != null
        && value.length() == 64
        && value.chars().allMatch(character -> Character.digit(character, 16) >= 0);
  }
}
