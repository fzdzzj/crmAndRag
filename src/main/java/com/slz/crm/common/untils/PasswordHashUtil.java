package com.slz.crm.common.untils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.springframework.util.DigestUtils;

/**
 * 密码哈希工具：新密文一律 BCrypt（版本 2a，cost=10，随机盐），存量 MD5 密文仅在登录校验时兼容。
 *
 * <p>BCrypt 密文形如 {@code $2a$10$...}（恒 60 字符），MD5 历史密文为 32 位十六进制小写。
 */
public final class PasswordHashUtil {

  private static final int BCRYPT_COST = 10;

  private static final String BCRYPT_VERSION = "2a";

  private static final int BCRYPT_HASH_LENGTH = 60;

  private static final int BCRYPT_SALT_BYTES = 16;

  private static final int MD5_HASH_LENGTH = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private static final Pattern MD5_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

  private PasswordHashUtil() {
    throw new AssertionError("Utility class cannot be instantiated");
  }

  /**
   * 判断密文是否为 BCrypt 格式（$2a$ 或 $2b$ 前缀，恒 60 字符）
   *
   * @param hash 库中存储的密文
   * @return true-BCrypt 密文，false-其他
   */
  public static boolean isBcrypt(String hash) {
    return hash != null
        && (hash.startsWith("$2a$") || hash.startsWith("$2b$"))
        && hash.length() == BCRYPT_HASH_LENGTH;
  }

  /**
   * 判断密文是否为历史 MD5 密文（32 位十六进制小写）
   *
   * @param hash 库中存储的密文
   * @return true-MD5 密文，false-其他
   */
  public static boolean isMd5(String hash) {
    return hash != null && MD5_PATTERN.matcher(hash).matches();
  }

  /**
   * 校验明文密码与存储密文是否匹配，按存储密文格式自适应：BCrypt 走 BCrypt 校验，MD5 走旧 MD5 对比
   *
   * @param rawPassword 明文密码
   * @param storedPassword 库中存储的密文
   * @return true-匹配，false-不匹配或密文格式未知
   */
  public static boolean matches(String rawPassword, String storedPassword) {
    boolean matched = false;
    if (rawPassword != null && storedPassword != null) {
      if (isBcrypt(storedPassword)) {
        matched = OpenBSDBCrypt.checkPassword(storedPassword, rawPassword.toCharArray());
      } else if (isMd5(storedPassword)) {
        matched =
            DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8))
                .equalsIgnoreCase(storedPassword);
      }
    }
    return matched;
  }

  /**
   * 生成 BCrypt 密文（随机盐，故同一明文两次生成结果不同）
   *
   * @param rawPassword 明文密码
   * @return BCrypt 密文（60 字符）
   */
  public static String hash(String rawPassword) {
    byte[] salt = new byte[BCRYPT_SALT_BYTES];
    SECURE_RANDOM.nextBytes(salt);
    return OpenBSDBCrypt.generate(BCRYPT_VERSION, rawPassword.toCharArray(), salt, BCRYPT_COST);
  }
}
