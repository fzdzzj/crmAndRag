package com.slz.crm.server.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 短问题纯规则改写器。
 *
 * <p>不调用模型，只把会话记忆压入检索锚点；主答 prompt 仍使用用户原文，避免改写结果污染生成语义。
 */
@Component
public class AiShortQuestionRewriter {

  private static final int FOLLOW_UP_MAX_CHARS = 10;
  private static final int REFERENCE_MAX_CHARS = 6;
  private static final int SHORT_MAX_CHARS = 14;
  private static final int ANCHOR_MAX_CHARS = 120;
  private static final String CLARIFY_PROMPT = "\n\n【澄清要求】用户问题缺少必要锚点，回答前必须先列出候选选项请用户确认，不要猜测业务对象。";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final List<String> FOLLOW_UP_WORDS =
      List.of("继续", "接着", "然后", "展开", "详细", "具体", "补充", "再说说", "下一步", "进度", "结果", "怎么样", "如何");
  private static final List<String> REFERENCE_WORDS =
      List.of("这个", "那个", "该", "它", "他们", "她们", "上面", "刚才", "刚刚", "这些", "那些", "此");
  private static final List<String> QUESTION_WORDS =
      List.of("吗", "呢", "什么", "怎么", "多少", "哪些", "哪个", "为什么", "？", "?");

  /** 改写结果：{@code query} 只供检索使用，{@code clarifyRequired} 提示主答必须先反问。 */
  public record RewrittenQuestion(String query, boolean clarifyRequired) {}

  public RewrittenQuestion rewrite(
      String userMessage, AiConversationMemoryEntity memory, AiMessageEntity lastUserQuestion) {
    String normalized = normalize(userMessage);
    RewrittenQuestion result;
    if (normalized == null) {
      result = new RewrittenQuestion(normalizeOrDefault(userMessage), false);
    } else if (isShortQuestion(normalized, parseFacts(memory))) {
      String anchor = selectAnchor(memory, lastUserQuestion);
      if (anchor == null) {
        result = new RewrittenQuestion(normalized, true);
      } else {
        result = new RewrittenQuestion(anchor + "：" + normalized, false);
      }
    } else {
      result = new RewrittenQuestion(normalized, false);
    }
    return result;
  }

  public String clarifyPrompt() {
    return CLARIFY_PROMPT;
  }

  private boolean isShortQuestion(String question, List<String> facts) {
    return containsAny(question, FOLLOW_UP_WORDS)
        || containsAny(question, REFERENCE_WORDS)
        || (question.length() <= FOLLOW_UP_MAX_CHARS && containsAny(question, QUESTION_WORDS))
        || question.length() <= REFERENCE_MAX_CHARS
        || question.length() <= SHORT_MAX_CHARS
        || overlapsFact(question, facts);
  }

  /** 锚点严格按蓝图降级：意图 &gt; 事实 &gt; 最近用户问 &gt; 摘要。 */
  private String selectAnchor(AiConversationMemoryEntity memory, AiMessageEntity lastUserQuestion) {
    String intent = anchorText(memory == null ? null : memory.getIntent());
    List<String> facts = parseFacts(memory);
    String recent = anchorText(lastUserQuestion == null ? null : lastUserQuestion.getContent());
    String result;
    if (intent != null) {
      result = intent;
    } else if (!facts.isEmpty()) {
      result = anchorText(facts.getFirst());
    } else if (recent != null) {
      result = recent;
    } else {
      result = anchorText(memory == null ? null : memory.getSummary());
    }
    return result;
  }

  private List<String> parseFacts(AiConversationMemoryEntity memory) {
    String factsJson = memory == null ? null : memory.getFacts();
    List<String> result;
    if (factsJson == null || factsJson.isBlank() || "[]".equals(factsJson.trim())) {
      result = List.of();
    } else {
      try {
        List<String> facts =
            OBJECT_MAPPER.readValue(factsJson, new TypeReference<List<String>>() {});
        result =
            facts == null
                ? List.of()
                : facts.stream().map(this::normalize).filter(item -> item != null).toList();
      } catch (Exception exception) {
        // 记忆是可降级加工品；坏 JSON 只影响改写，不应影响主答。
        result = List.of();
      }
    }
    return result;
  }

  private boolean overlapsFact(String question, List<String> facts) {
    return facts.stream()
        .anyMatch(
            fact -> {
              String[] words = fact.split("[\\s\\p{Punct}，。；：、“”（）]+");
              for (String word : words) {
                if (word.length() >= 2 && question.contains(word)) {
                  return true;
                }
              }
              return false;
            });
  }

  private boolean containsAny(String text, List<String> words) {
    return words.stream().anyMatch(text::contains);
  }

  private String anchorText(String text) {
    String normalized = normalize(text);
    String result;
    if (normalized == null) {
      result = null;
    } else {
      result =
          normalized.length() > ANCHOR_MAX_CHARS
              ? normalized.substring(0, ANCHOR_MAX_CHARS)
              : normalized;
    }
    return result;
  }

  private String normalize(String text) {
    String result;
    if (text == null) {
      result = null;
    } else {
      String stripped = AiThinkTagStripper.strip(text).replaceAll("\\s+", " ").trim();
      result = stripped.isEmpty() ? null : stripped;
    }
    return result;
  }

  private String normalizeOrDefault(String text) {
    String normalized = normalize(text);
    return normalized == null ? "" : normalized;
  }
}
