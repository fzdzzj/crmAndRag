package com.slz.crm.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.slz.crm.common.untils.PasswordHashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

/**
 * 密码哈希工具测试：覆盖 BCrypt 随机盐特性、校验正反例、MD5 识别与兼容校验。
 *
 * @author fmz
 * @since 2026/10/01
 */
@DisplayName("密码哈希工具")
class PasswordHashUtilTest {

  @Test
  @DisplayName("BCrypt 生成恒 60 字符且使用随机盐，同一明文两次密文不同")
  void shouldProduceDistinctBcryptHashesForSamePlaintext() {
    String first = PasswordHashUtil.hash("secret-password");
    String second = PasswordHashUtil.hash("secret-password");

    assertEquals(60, first.length());
    assertTrue(first.startsWith("$2a$10$"));
    assertNotEquals(first, second);
    assertTrue(PasswordHashUtil.isBcrypt(first));
    assertTrue(PasswordHashUtil.isBcrypt(second));
  }

  @Test
  @DisplayName("BCrypt 校验：正确明文通过，错误明文拒绝")
  void shouldVerifyBcryptHashWithCorrectAndWrongPlaintext() {
    String stored = PasswordHashUtil.hash("correct-password");

    assertTrue(PasswordHashUtil.matches("correct-password", stored));
    assertFalse(PasswordHashUtil.matches("wrong-password", stored));
  }

  @Test
  @DisplayName("MD5 密文可被识别且与旧算法兼容校验")
  void shouldRecognizeMd5AndVerifyCompatibly() {
    String md5 = DigestUtils.md5DigestAsHex("legacy-password".getBytes());

    assertTrue(PasswordHashUtil.isMd5(md5));
    assertFalse(PasswordHashUtil.isBcrypt(md5));
    assertTrue(PasswordHashUtil.matches("legacy-password", md5));
    assertFalse(PasswordHashUtil.matches("other-password", md5));
  }

  @Test
  @DisplayName("格式判定边界：null、空串、非法或大写十六进制密文一律不识别")
  void shouldRejectMalformedOrUnknownHashes() {
    assertFalse(PasswordHashUtil.isBcrypt(null));
    assertFalse(PasswordHashUtil.isMd5(null));
    assertFalse(PasswordHashUtil.isBcrypt(""));
    assertFalse(PasswordHashUtil.isMd5(""));
    assertFalse(PasswordHashUtil.isMd5("D41D8CD98F00B204E9800998ECF8427E"));
    assertFalse(PasswordHashUtil.isBcrypt("$2b$10$too-short"));
    assertFalse(PasswordHashUtil.matches("any", "not-a-valid-hash"));
    assertFalse(PasswordHashUtil.matches(null, PasswordHashUtil.hash("any")));
    assertFalse(PasswordHashUtil.matches("any", null));
  }
}
