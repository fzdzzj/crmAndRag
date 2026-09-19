package com.slz.crm.common.untils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 附件下载令牌工具类（AES-GCM 加密，带完整性认证） 用于生成和验证附件下载的加密令牌。
 *
 * <p>密钥从环境变量 {@code SLZ_ATTACH_TOKEN_KEY}（通过 {@code slz.attach-token.secret-key} 配置）注入，不再硬编码在代码中；
 * 未配置或长度非法时启动直接失败，避免静默降级。
 */
@Slf4j
@Component
public class AttachmentDownloadTokenUtil {

  /** 加密算法 */
  private static final String ALGORITHM = "AES";

  /** 加密模式：AES-GCM（自带完整性认证，防篡改/伪造） */
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  /** GCM 随机 IV 长度（字节） */
  private static final int GCM_IV_LENGTH = 12;

  /** GCM 认证标签长度（位） */
  private static final int GCM_TAG_LENGTH_BITS = 128;

  /** 令牌有效期：30分钟（单位：毫秒） */
  private static final long DOWNLOAD_TOKEN_TTL = 30 * 60 * 1000;

  private final SecretKeySpec keySpec;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  /** Spring 构造：密钥由配置注入（生产环境通过环境变量 SLZ_ATTACH_TOKEN_KEY 提供）。 密钥缺失或长度非法时快速失败，避免无密钥静默运行。 */
  public AttachmentDownloadTokenUtil(@Value("${slz.attach-token.secret-key:}") String secretKey) {
    this.keySpec = buildKeySpec(secretKey);
  }

  private static SecretKeySpec buildKeySpec(String secretKey) {
    if (secretKey == null || secretKey.isEmpty()) {
      throw new IllegalStateException(
          "附件下载令牌密钥未配置：请设置环境变量 SLZ_ATTACH_TOKEN_KEY（或 slz.attach-token.secret-key）");
    }
    byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
      throw new IllegalStateException(
          "附件下载令牌密钥长度必须为 16/24/32 字节（AES-128/192/256），当前：" + keyBytes.length);
    }
    return new SecretKeySpec(keyBytes, ALGORITHM);
  }

  /**
   * 生成附件下载令牌（AES加密）
   *
   * @param attachmentId 附件ID
   * @param userId 用户ID
   * @return Base64编码的加密令牌
   */
  public String generateDownloadToken(Long attachmentId, Long userId) {
    return generateDownloadToken(attachmentId, userId, "approval_attachment");
  }

  /**
   * 生成附件下载令牌（AES加密）
   *
   * @param attachmentId 附件ID
   * @param userId 用户ID
   * @param fileType 文件类型(approval_attachment/project_file)
   * @return Base64编码的加密令牌
   */
  public String generateDownloadToken(Long attachmentId, Long userId, String fileType) {
    return generateDownloadToken(attachmentId, userId, fileType, null);
  }

  /** 生成带业务模型标识的下载令牌。 */
  public String generateDownloadToken(
      Long attachmentId, Long userId, String fileType, String modelName) {
    return generateDownloadToken(attachmentId, userId, fileType, modelName, null);
  }

  /**
   * 生成带协助来源上下文的实时下载令牌。
   *
   * <p>实时协助来源附件必须把 assistId 放进令牌，下载请求到达公开下载入口时 才能再次核对协助记录、来源模型和来源业务记录，避免把普通附件令牌误当成协助授权。
   */
  public String generateAssistSourceDownloadToken(
      Long attachmentId, Long userId, String fileType, String modelName, Long activeAssistId) {
    return generateDownloadToken(attachmentId, userId, fileType, modelName, activeAssistId, null);
  }

  /**
   * 生成协助终态快照附件的短期下载令牌。
   *
   * <p>{@code historicalAssistId} 非空时，下载入口不使用实时业务记录授权， 而是校验该附件是否被冻结在指定协助记录的历史快照中。
   */
  public String generateDownloadToken(
      Long attachmentId, Long userId, String fileType, String modelName, Long historicalAssistId) {
    return generateDownloadToken(
        attachmentId, userId, fileType, modelName, null, historicalAssistId);
  }

  /** 内部统一构建令牌，区分实时协助上下文与终态历史快照上下文。 */
  private String generateDownloadToken(
      Long attachmentId,
      Long userId,
      String fileType,
      String modelName,
      Long activeAssistId,
      Long historicalAssistId) {
    try {
      // 创建令牌对象
      DownloadToken token = new DownloadToken();
      token.setAttachmentId(attachmentId);
      token.setUserId(userId);
      token.setFileType(fileType != null ? fileType : "approval_attachment");
      token.setModelName(modelName);
      token.setActiveAssistId(activeAssistId);
      token.setHistoricalAssistId(historicalAssistId);
      token.setExpireTime(System.currentTimeMillis() + DOWNLOAD_TOKEN_TTL);

      // 转换为JSON字符串
      String tokenJson = objectMapper.writeValueAsString(token);
      log.info(
          "生成附件下载令牌，附件ID：{}，用户ID：{}，文件类型：{}，过期时间：{}",
          attachmentId,
          userId,
          token.getFileType(),
          token.getExpireTime());

      // AES加密
      return encryptAES(tokenJson);

    } catch (Exception e) {
      log.error("生成下载令牌失败", e);
      throw new RuntimeException("生成下载令牌失败", e);
    }
  }

  /**
   * 解析并验证下载令牌
   *
   * @param encryptedToken Base64编码的加密令牌
   * @return 下载令牌对象
   * @throws IllegalArgumentException 令牌无效或已过期
   */
  public DownloadToken parseDownloadToken(String encryptedToken) {
    try {
      log.debug("开始解析下载令牌，令牌长度：{}", encryptedToken != null ? encryptedToken.length() : 0);

      // AES解密
      String tokenJson = decryptAES(encryptedToken);
      log.debug("AES解密成功，JSON：{}", tokenJson);

      // 解析JSON
      DownloadToken token = objectMapper.readValue(tokenJson, DownloadToken.class);

      // 检查是否过期
      long currentTime = System.currentTimeMillis();
      log.debug(
          "令牌过期时间：{}，当前时间：{}，剩余时间：{}毫秒",
          token.getExpireTime(),
          currentTime,
          (token.getExpireTime() - currentTime));

      if (currentTime > token.getExpireTime()) {
        log.warn("令牌已过期，过期时间：{}，当前时间：{}", token.getExpireTime(), currentTime);
        throw new IllegalArgumentException("下载链接已过期");
      }

      log.info("验证附件下载令牌成功，附件ID：{}，用户ID：{}", token.getAttachmentId(), token.getUserId());
      return token;

    } catch (IllegalArgumentException e) {
      log.error("令牌验证失败：{}", e.getMessage());
      throw e;
    } catch (Exception e) {
      log.error("验证下载令牌时发生异常，令牌：{}", encryptedToken, e);
      throw new IllegalArgumentException("下载链接无效或已过期", e);
    }
  }

  /**
   * AES加密（GCM 模式：随机 IV + 认证标签，防篡改防伪造）
   *
   * @param content 明文内容
   * @return Base64编码的密文（URL安全，格式：IV + 密文 + 认证标签）
   */
  private String encryptAES(String content) throws Exception {
    byte[] iv = new byte[GCM_IV_LENGTH];
    secureRandom.nextBytes(iv);

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

    byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
    byte[] ivAndCipher = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, ivAndCipher, 0, iv.length);
    System.arraycopy(encrypted, 0, ivAndCipher, iv.length, encrypted.length);
    // 使用 URL 安全的 Base64 编码（替换 + 和 /，去除 =）
    return Base64.getUrlEncoder().withoutPadding().encodeToString(ivAndCipher);
  }

  /**
   * AES解密（GCM 模式：校验认证标签，任何篡改都会导致解密失败）
   *
   * @param encryptedContent Base64编码的密文（URL安全）
   * @return 明文内容
   */
  private String decryptAES(String encryptedContent) throws Exception {
    byte[] decoded = Base64.getUrlDecoder().decode(encryptedContent);
    if (decoded.length <= GCM_IV_LENGTH) {
      throw new IllegalArgumentException("令牌格式无效");
    }
    byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH);
    byte[] cipherBytes = Arrays.copyOfRange(decoded, GCM_IV_LENGTH, decoded.length);

    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

    byte[] decrypted = cipher.doFinal(cipherBytes);
    return new String(decrypted, StandardCharsets.UTF_8);
  }

  /**
   * 检查令牌是否过期
   *
   * @param encryptedToken 加密令牌
   * @return true：已过期，false：未过期
   */
  public boolean isTokenExpired(String encryptedToken) {
    try {
      parseDownloadToken(encryptedToken);
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /** 下载令牌数据结构 */
  @Data
  public static class DownloadToken {
    /** 附件ID */
    private Long attachmentId;

    /** 用户ID */
    private Long userId;

    /** 文件类型(approval_attachment/project_file) */
    private String fileType;

    /** 附件真实业务模型名称，用于下载时防止令牌跨模型重放。 */
    private String modelName;

    /** 实时协助来源附件上下文；普通附件令牌为空。 */
    private Long activeAssistId;

    /** 非空表示该令牌仅用于指定终态协助快照中的历史附件。 */
    private Long historicalAssistId;

    /** 过期时间（毫秒时间戳） */
    private Long expireTime;
  }
}
