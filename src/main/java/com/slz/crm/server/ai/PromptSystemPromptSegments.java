package com.slz.crm.server.ai;

import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AiChat 系统提示词动态段装配协作类（add-prompt-template-slots 任务 2.1/2.3）。
 *
 * <p>纯函数协作：从 {@link AiChatPromptService} 抽出槽位 map 组装与「旧追加路径」拼接，收敛其类规模以保 PMD NCSS 不超阈 （对齐
 * PromptTemplateRenderSupport/NestingSupport 拆分先例）。无事务、无 Spring 依赖。
 *
 * <p><b>回归锁口径</b>：本类与 AiChat 旧实现逐字节等价——动态段四件（当前意图/已确认事实/历史摘要/澄清）的取数与条件 逐字不动，只迁拼接位置；slot 值含标题与
 * {@code \n\n} 间距、条件空不置入（与追加路径同条件）。
 */
final class PromptSystemPromptSegments {

  /** 槽位标记前缀：模板 content 含任一 {@code {{#slot:}} 即判定注入模式。 */
  static final String SLOT_PREFIX = "{{#slot:";

  private PromptSystemPromptSegments() {}

  /** content 是否含任意槽位标记（判定注入模式；无则旧追加路径）。 */
  static boolean containsSlot(String content) {
    return content != null && content.contains(SLOT_PREFIX);
  }

  /**
   * 组装槽位四件 map：值含标题与 {@code \n\n} 间距、条件空不置入（与 {@link #appendDynamicSections} 同条件，逐字节等价）。
   *
   * @param rewriter 澄清提示来源（clarify 槽位值即 {@code rewriter.clarifyPrompt()}）
   */
  static Map<String, String> buildSlots(
      AiConversationMemoryEntity memory,
      boolean clarifyRequired,
      AiShortQuestionRewriter rewriter) {
    Map<String, String> slots = new LinkedHashMap<>();
    if (memory != null) {
      if (memory.getIntent() != null && !memory.getIntent().isBlank()) {
        slots.put("currentIntent", "\n\n【当前意图】" + memory.getIntent().trim());
      }
      if (memory.getFacts() != null
          && !memory.getFacts().isBlank()
          && !"[]".equals(memory.getFacts().trim())) {
        slots.put("confirmedFacts", "\n\n【已确认事实】" + memory.getFacts().trim());
      }
      if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
        slots.put("historySummary", "\n\n【历史摘要】" + memory.getSummary().trim());
      }
    }
    if (clarifyRequired) {
      slots.put("clarify", rewriter.clarifyPrompt());
    }
    return slots;
  }

  /** 旧追加路径（逐字节回归锁基准）：在骨架尾部按固定顺序拼接四动态段；无槽位模板/回退态唯一路径。 */
  static String appendDynamicSections(
      String skeleton,
      AiShortQuestionRewriter rewriter,
      AiConversationMemoryEntity memory,
      boolean clarifyRequired) {
    StringBuilder memoryBlock = new StringBuilder(skeleton);
    if (memory != null) {
      if (memory.getIntent() != null && !memory.getIntent().isBlank()) {
        memoryBlock.append("\n\n【当前意图】").append(memory.getIntent().trim());
      }
      if (memory.getFacts() != null
          && !memory.getFacts().isBlank()
          && !"[]".equals(memory.getFacts().trim())) {
        memoryBlock.append("\n\n【已确认事实】").append(memory.getFacts().trim());
      }
      if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
        memoryBlock.append("\n\n【历史摘要】").append(memory.getSummary().trim());
      }
    }
    if (clarifyRequired) {
      memoryBlock.append(rewriter.clarifyPrompt());
    }
    return memoryBlock.toString();
  }
}
