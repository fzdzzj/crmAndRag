package com.slz.crm.knowledge.retrieval;

/**
 * 上下文 token 估算器（add-context-compression-and-enrichment 任务 2.1）。
 *
 * <p>为什么不引入分词器：规则压缩需要的是<b>确定性、零依赖、可单测</b>的长度口径， 而非精确 tokenizer 计数；估算公式对中文为主的企业文档足够保守： CJK
 * 字符（含全角标点）按 1 token/字，其余字符（拉丁词、数字、空白、ASCII 标点） 按 4 字符 ≈ 1 token 折算。
 *
 * <p>口径统一约定：预算判断（是否触发压缩）、规则压缩截断、LLM 压缩结果校验 全部使用本估算器，避免多处口径不一致。
 */
public final class TokenEstimator {

  private TokenEstimator() {}

  /**
   * 估算文本 token 数。
   *
   * @param text 任意文本（null/空 = 0）
   * @return 估算 token 数（不小于 0）
   */
  public static int estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int cjk = 0;
    int other = 0;
    for (int index = 0; index < text.length(); ) {
      int codePoint = text.codePointAt(index);
      if (isCjk(codePoint)) {
        cjk++;
      } else {
        other++;
      }
      index += Character.charCount(codePoint);
    }
    return cjk + (other + 3) / 4;
  }

  private static boolean isCjk(int codePoint) {
    return (codePoint >= 0x4E00 && codePoint <= 0x9FFF) // CJK 统一表意文字
        || (codePoint >= 0x3400 && codePoint <= 0x4DBF) // 扩展 A 区
        || (codePoint >= 0xF900 && codePoint <= 0xFAFF) // 兼容表意文字
        || (codePoint >= 0x3000 && codePoint <= 0x303F) // CJK 标点
        || (codePoint >= 0xFF00 && codePoint <= 0xFFEF); // 全角字符
  }
}
