package com.slz.crm.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentDownloadTokenUtilTest {

  /** 仅用于单测的固定密钥（16 字节），生产环境通过环境变量注入 */
  private static final String TEST_KEY = "unit-test-key-16";

  @Test
  @DisplayName("下载令牌保留附件业务模型与历史协助上下文")
  void tokenRoundTripKeepsModelNameAndHistoricalAssistId() {
    AttachmentDownloadTokenUtil util = new AttachmentDownloadTokenUtil(TEST_KEY);

    String token =
        util.generateDownloadToken(10L, 2L, "approval_attachment", "business_activity", 20L);
    AttachmentDownloadTokenUtil.DownloadToken parsed = util.parseDownloadToken(token);

    assertEquals(10L, parsed.getAttachmentId());
    assertEquals(2L, parsed.getUserId());
    assertEquals("business_activity", parsed.getModelName());
    assertEquals(20L, parsed.getHistoricalAssistId());
  }

  @Test
  @DisplayName("令牌被篡改后 GCM 认证失败，拒绝解析")
  void tamperedTokenRejectedByGcmAuthentication() {
    AttachmentDownloadTokenUtil util = new AttachmentDownloadTokenUtil(TEST_KEY);

    String token = util.generateDownloadToken(10L, 2L);
    // 篡改密文中部一个字符（模拟攻击者伪造/篡改令牌）
    char[] chars = token.toCharArray();
    int pos = chars.length / 2;
    chars[pos] = chars[pos] == 'A' ? 'B' : 'A';
    String tampered = new String(chars);

    assertThrows(IllegalArgumentException.class, () -> util.parseDownloadToken(tampered));
  }

  @Test
  @DisplayName("密钥缺失或长度非法时快速失败")
  void missingOrInvalidKeyFailsFast() {
    assertThrows(IllegalStateException.class, () -> new AttachmentDownloadTokenUtil(""));
    assertThrows(IllegalStateException.class, () -> new AttachmentDownloadTokenUtil("short-key"));
  }

  @Test
  @DisplayName("相同明文两次生成的令牌不同（随机 IV），且均可解析")
  void randomIvProducesDistinctTokens() {
    AttachmentDownloadTokenUtil util = new AttachmentDownloadTokenUtil(TEST_KEY);

    String first = util.generateDownloadToken(10L, 2L);
    String second = util.generateDownloadToken(10L, 2L);

    org.junit.jupiter.api.Assertions.assertNotEquals(
        first, second, "随机 IV 应使相同明文产生不同密文（ECB 确定性加密已被废弃）");
    assertEquals(10L, util.parseDownloadToken(first).getAttachmentId());
    assertEquals(10L, util.parseDownloadToken(second).getAttachmentId());
  }
}
