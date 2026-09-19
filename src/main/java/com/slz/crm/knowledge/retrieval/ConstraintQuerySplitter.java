package com.slz.crm.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多条件查询确定性拆句器（fix-multicondition-recall 任务 1.1）。
 *
 * <p>在查询改写之后、召回之前，把「A后B / A且B / A并且B / A同时B」拆成 原查询 + 左右子查询，供多路 embedding 与文本召回融合。零 LLM、无 Spring、
 * 无配置键、无网络；不改变 {@code rag.query.multi-query.enabled} 默认。
 *
 * <p>规则：首元素恒为 strip 后原查询；仅在原句找<strong>第一处</strong>分隔符 （较长优先：并且|同时|后|且）；左右两侧都够格才追加两路，否则只返回原查询。 够格 =
 * 至少 4 个汉字，或含长度 ≥2 的 ASCII 词。不递归、最多 +2 路。
 */
public final class ConstraintQuerySplitter {

  /** 较长分隔优先，避免「并且」被「且」抢先匹配。 */
  private static final Pattern SEPARATOR = Pattern.compile("并且|同时|后|且");

  /** 长度 ≥2 的 ASCII 词（字母/数字/下划线），作为汉字不足时的够格兜底。 */
  private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z0-9_]{2,}");

  private ConstraintQuerySplitter() {}

  /**
   * 拆句：返回至少含原查询的列表；命中启发式时再追加左右子查询。
   *
   * @param query 改写后的检索查询；null/空白返回空列表
   * @return 不可变列表；[0]=原查询（strip），可能再跟左、右两路
   */
  public static List<String> split(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String trimmed = query.strip();
    List<String> routes = new ArrayList<>(3);
    routes.add(trimmed);

    Matcher matcher = SEPARATOR.matcher(trimmed);
    if (!matcher.find()) {
      return List.copyOf(routes);
    }
    String left = trimmed.substring(0, matcher.start()).strip();
    String right = trimmed.substring(matcher.end()).strip();
    if (isQualified(left) && isQualified(right)) {
      routes.add(left);
      routes.add(right);
    }
    return List.copyOf(routes);
  }

  /** 一侧够格：汉字 ≥4，或含长度 ≥2 的 ASCII 词。 */
  static boolean isQualified(String side) {
    if (side == null || side.isEmpty()) {
      return false;
    }
    int hanCount = 0;
    for (int index = 0; index < side.length(); ) {
      int codePoint = side.codePointAt(index);
      if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
        hanCount++;
        if (hanCount >= 4) {
          return true;
        }
      }
      index += Character.charCount(codePoint);
    }
    return ASCII_WORD.matcher(side).find();
  }
}
