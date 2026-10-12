package com.slz.crm.server.service.impl;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板分段槽位协作类（add-prompt-template-slots 任务 1.1）。
 *
 * <p>纯函数协作：负责 {@code {{#slot:key}}} 标记抽取与整段替换，从渲染链单独收敛以保证 {@link PromptTemplateNestingSupport}
 * 类规模不超 PMD NCSS 阈值（对齐 RenderSupport/NestingSupport 拆分先例）。无事务、无 Spring 依赖、零真实外呼。
 *
 * <p><b>语义</b>：槽位标记与 {@code {{var}}} 变量、{@code {{> code}}} 引用正交互不误匹配——槽位值=整段成品直接替换，未提供=替换为空串； 槽位
 * <b>不参与</b> variables 校验（rejectUndefined 不检 slot key）。
 *
 * <p><b>替换次序</b>：槽位替换在变量校验/替换之后执行（嵌套展开 → 变量校验/替换 → 槽位替换），保证 slot 值中可能出现的 {@code {{var}}}
 * 字面不被变量替换二次加工（实测定，见任务 §0）。
 */
final class PromptTemplateSlotSupport {

  /** {@code {{#slot:key}}} 槽位标记：双大括号、{@code #slot:}、槽位 key、双大括号。key 不含空白与花括号。 */
  private static final Pattern SLOT = Pattern.compile("\\{\\{#slot:([^{}\\s]+?)\\}\\}");

  private PromptTemplateSlotSupport() {}

  /** content 是否含任意 {@code {{#slot:key}}} 槽位标记（无则模板走既有字节等价路径）。 */
  static boolean containsSlot(String content) {
    return content != null && SLOT.matcher(content).find();
  }

  /**
   * 整段槽位替换：把 content 中每个 {@code {{#slot:key}}} 替换为 {@code slots.get(key)}，未提供替换为空串； 无槽位标记时逐字节返回
   * content。
   *
   * <p><b>{@code slots} 为 null 代表「未做槽位注入」</b>——标记原样保留（供调用方判定注入模式），不替换为空串；{@code slots} 为任意
   * Map（含空表）代表「本次执行槽位注入」——所有标记均被消费（未提供 key → 空串）。
   */
  static String replaceSlots(String content, Map<String, String> slots) {
    String result;
    if (content == null) {
      result = "";
    } else if (!containsSlot(content) || slots == null) {
      // 无槽位标记逐字节返回；slots=null（未做注入）标记保留供注入模式判定
      result = content;
    } else {
      Matcher m = SLOT.matcher(content);
      StringBuilder sb = new StringBuilder(content.length());
      int last = 0;
      while (m.find()) {
        String key = m.group(1);
        sb.append(content, last, m.start());
        String value = slots.get(key);
        if (value != null) {
          sb.append(value);
        }
        last = m.end();
      }
      sb.append(content, last, content.length());
      result = sb.toString();
    }
    return result;
  }
}
