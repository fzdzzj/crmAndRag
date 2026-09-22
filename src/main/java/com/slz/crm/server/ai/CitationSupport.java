package com.slz.crm.server.ai;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link CitationAligner} 的支撑分计算单元（零外呼、确定性）。
 *
 * <p>fix-citation-alignment 任务 1.1：CJK 二字子句覆盖率 + ASCII/数字 token 加成，用于 KEEP / REMAP 决策的
 * 支撑打分。行为等价于原内联实现，仅作为类级 NCSS 下沉的独立单元。
 */
final class CitationSupport {

  private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9]+");
  private static final double TOKEN_BONUS_WEIGHT = 0.25;

  private CitationSupport() {}

  /** 支撑分：CJK 二字子句覆盖率 + 共享 ASCII/数字 token 加成，夹到 [0,1]。 */
  static double supportScore(String clause, String excerpt) {
    double result;
    if (clause == null || clause.isBlank() || excerpt == null || excerpt.isBlank()) {
      result = 0.0d;
    } else {
      // 主信号：子句 bigram 被 excerpt 覆盖的比例（避免长 excerpt 稀释 Jaccard）
      double coverage = clauseCoverage(cjkBigrams(clause), cjkBigrams(excerpt));
      double tokenBonus = tokenOverlapBonus(clause, excerpt);
      result = Math.min(1.0d, coverage + tokenBonus);
    }
    return result;
  }

  private static double tokenOverlapBonus(String clause, String excerpt) {
    Set<String> a = asciiTokens(clause);
    Set<String> b = asciiTokens(excerpt);
    double result;
    if (a.isEmpty() || b.isEmpty()) {
      result = 0.0d;
    } else {
      int shared = 0;
      for (String t : a) {
        if (b.contains(t)) {
          shared++;
        }
      }
      if (shared == 0) {
        result = 0.0d;
      } else {
        result = TOKEN_BONUS_WEIGHT * ((double) shared / a.size());
      }
    }
    return result;
  }

  private static double clauseCoverage(Set<String> clauseGrams, Set<String> excerptGrams) {
    double result;
    if (clauseGrams.isEmpty()) {
      result = 0.0d;
    } else {
      int hit = 0;
      for (String g : clauseGrams) {
        if (excerptGrams.contains(g)) {
          hit++;
        }
      }
      result = (double) hit / (double) clauseGrams.size();
    }
    return result;
  }

  static Set<String> cjkBigrams(String text) {
    Set<String> out = new HashSet<>();
    if (text != null && text.length() >= 2) {
      for (int i = 0; i < text.length() - 1; i++) {
        char a = text.charAt(i);
        char b = text.charAt(i + 1);
        if (isCjk(a) && isCjk(b)) {
          out.add(new String(new char[] {a, b}));
        }
      }
    }
    return out;
  }

  static Set<String> asciiTokens(String text) {
    Set<String> out = new HashSet<>();
    if (text != null && !text.isBlank()) {
      Matcher matcher = ASCII_TOKEN.matcher(text);
      while (matcher.find()) {
        out.add(matcher.group().toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  private static boolean isCjk(char c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
  }
}
