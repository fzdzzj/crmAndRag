package com.slz.crm.knowledge.retrieval;

import java.util.regex.Pattern;

/**
 * 承重句判定（tighten-pmd-residual-325 任务 6.3 自 RuleContextCompressor 拆出，行为等价）。
 * 承重句：含数字（金额/期限/数量）、定义或键值冒号、序号条目、条款引用—— 对应 rag-kb 方案10"保留锚点句"的确定性口径。
 */
final class LoadBearingSentences {
  private static final Pattern NUMBERED_ITEM =
      Pattern.compile("^\\s*[（(]?[0-9一二三四五六七八九十]{1,4}[）)、，.．]");
  private static final Pattern CLAUSE_REF = Pattern.compile("第[0-9一二三四五六七八九十百]+[条款章项步期季度]{1,2}");

  private LoadBearingSentences() {}

  static boolean isLoadBearing(String sentence) {
    return containsDigit(sentence)
        || sentence.contains("：")
        || sentence.contains(": ")
        || NUMBERED_ITEM.matcher(sentence).find()
        || CLAUSE_REF.matcher(sentence).find();
  }

  private static boolean containsDigit(String sentence) {
    boolean found = false;
    for (int index = 0; index < sentence.length() && !found; index++) {
      found = Character.isDigit(sentence.charAt(index));
    }
    return found;
  }
}
