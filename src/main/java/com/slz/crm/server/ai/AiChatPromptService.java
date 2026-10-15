package com.slz.crm.server.ai;

import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.vo.PromptTemplateRenderVO;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiConversationMemoryService;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.PromptTemplateService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Slf4j
@Component
public class AiChatPromptService {

  private static final String SYSTEM_PROMPT_PATH = "prompts/system-prompt.txt";
  private static final String TITLE_PROMPT_PATH = "prompts/session-title.txt";
  private static final String SYSTEM_PROMPT_TEMPLATE_CODE = "aichat-system-prompt";
  private static final String TITLE_PROMPT_TEMPLATE_CODE = "aichat-session-title";

  /** 会话标题结果侧最大长度（add-ai-customer-360-insight 任务 3.2：提案 4~12 字口径的 12 字上限）。 */
  private static final int MAX_TITLE_LENGTH = 12;

  @Autowired private AiProperties aiProperties;

  @Autowired private AiMessageService aiMessageService;

  /** 记忆服务生产可用；部分单测只装配消息服务，因此保持可选。 */
  @Autowired(required = false)
  private AiConversationMemoryService conversationMemoryService;

  /** 短问改写只影响检索/模型输入，不改变 ai_message 中的用户原文。 */
  @Autowired private AiShortQuestionRewriter shortQuestionRewriter;

  /** 提示词模板服务：运行时模板态渲染入口；渲染异常时回退类路径静态文本（aichat-prompt-template-integration 任务 2.1）。 */
  @Autowired(required = false)
  private PromptTemplateService promptTemplateService;

  /** 提示词总字符闸门；历史超限时按最旧优先丢弃，必要 system/user 不裁剪。 */
  @Value("${ai.prompt-max-chars:3400}")
  private int promptMaxChars = 3400;

  private String systemPrompt;
  private String titlePrompt;
  private String promptVersion;

  /** 最近一次系统提示词构建的生效来源（模板态/回退态），供流终观测面读取（任务 2.3 / D6）。 */
  private PromptSource promptSource = PromptSource.FALLBACK;

  @PostConstruct
  void loadPrompts() {
    loadPrompts(SYSTEM_PROMPT_PATH, TITLE_PROMPT_PATH);
  }

  void loadPrompts(String systemPromptPath, String titlePromptPath) {
    systemPrompt = loadPromptFile(systemPromptPath);
    validatePrompt(systemPromptPath, systemPrompt);
    titlePrompt = loadPromptFile(titlePromptPath);
    validatePrompt(titlePromptPath, titlePrompt);
    promptVersion = sha256Prefix(systemPrompt);
  }

  /**
   * 单轮准备（add-customer-semantic-retrieval 任务 3.2）：记忆读取与短问改写一次完成，
   * 供检索（rewrittenQuery）与消息组装（memory/clarify）两段复用，消除旧 buildMessages 内联链与检索 query 取值的耦合。
   */
  public PreparedTurn prepareTurn(Long sessionId, String message) {
    AiConversationMemoryEntity memory =
        conversationMemoryService == null
            ? null
            : conversationMemoryService.findBySessionId(sessionId);
    AiShortQuestionRewriter.RewrittenQuestion rewritten =
        shortQuestionRewriter.rewrite(message, memory, findLastUserQuestion(sessionId));
    return new PreparedTurn(sessionId, memory, rewritten.clarifyRequired(), rewritten.query());
  }

  /**
   * 消息组装（任务 3.2/3.3）：system prompt（含检索上下文注入判定）→ 历史 → 本轮用户消息（改写后）→ 预算裁剪。
   *
   * <p>{@code retrievalContextInjected}=true 表示检索上下文已随槽位注入 system prompt（任务 3.3）， 调用方不再追加检索
   * SystemMessage；false 表示走旧追加路径（回退态/无标记模板），由调用方按旧方式插入 SystemMessage（逐字节同旧行为）。
   */
  public PreparedMessages buildMessages(
      Long sessionId, PreparedTurn prepared, String retrievalContext) {
    SystemPromptBuild build =
        buildSystemPrompt(prepared.memory(), prepared.clarifyRequired(), retrievalContext);
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(build.prompt()));
    messages.addAll(loadHistory(sessionId));
    messages.add(new UserMessage(prepared.rewrittenQuery()));
    boolean injected =
        build.slotInjected() && retrievalContext != null && !retrievalContext.isBlank();
    return new PreparedMessages(applyPromptBudget(messages), injected);
  }

  /** 系统提示词构建结果（任务 3.3）：{@code slotInjected}=true 表示检索上下文已随槽位注入成品提示词。 */
  record SystemPromptBuild(String prompt, boolean slotInjected) {}

  /** 单轮准备结果（任务 3.2）：memory/clarifyRequired 供系统提示词组装，rewrittenQuery 供检索与用户消息。 */
  public record PreparedTurn(
      Long sessionId,
      AiConversationMemoryEntity memory,
      boolean clarifyRequired,
      String rewrittenQuery) {}

  /**
   * 消息组装结果（任务 3.3）：{@code retrievalContextInjected}=true 表示检索上下文已随槽位注入系统提示词， 调用方不再以 SystemMessage
   * 方式插入检索上下文。
   */
  public record PreparedMessages(List<Message> messages, boolean retrievalContextInjected) {}

  /**
   * 将持久记忆注入系统提示词；加工品缺失时不改变原 system prompt（add-prompt-template-slots 任务 2.1/2.3）。
   *
   * <p><b>双模式运行时判定</b>：生效骨架含任一 {@code {{#slot:}} 标记 → 注入模式——组装 slots 四件 map 交由 render 链替换（停用
   * 尾部追加）；无标记（含回退静态文本）→ 旧追加路径逐字节保留。任一异常均按既有 WARN+回退链兜底。
   */
  private SystemPromptBuild buildSystemPrompt(
      AiConversationMemoryEntity memory, boolean clarifyRequired, String retrievalContext) {
    String skeleton = resolveSystemSkeleton();
    SystemPromptBuild result;
    if (PromptSystemPromptSegments.containsSlot(skeleton)) {
      result = renderInjectSlots(memory, clarifyRequired, skeleton, retrievalContext);
    } else {
      result =
          new SystemPromptBuild(
              PromptSystemPromptSegments.appendDynamicSections(
                  skeleton, shortQuestionRewriter, memory, clarifyRequired),
              false);
    }
    return result;
  }

  /**
   * 注入模式：把动态段四件（当前意图/已确认事实/历史摘要/澄清）组装为 slots 键值表再经 render 链替换槽位标记，输出整段成品提示词。
   *
   * <p>promptVersion 口径不动——由 {@link #resolveSystemSkeleton} 记录生效骨架 sha256（含标记文本、不含 slot 值，D7）； 注入
   * render 任何异常 WARN 后回退静态 + 旧追加路径（不断聊天主链路）。
   */
  private SystemPromptBuild renderInjectSlots(
      AiConversationMemoryEntity memory,
      boolean clarifyRequired,
      String skeletonWithMarkers,
      String retrievalContext) {
    SystemPromptBuild result;
    if (promptTemplateService == null) {
      result =
          new SystemPromptBuild(
              PromptSystemPromptSegments.appendDynamicSections(
                  skeletonWithMarkers, shortQuestionRewriter, memory, clarifyRequired),
              false);
    } else {
      String injected =
          renderTemplateContent(
              SYSTEM_PROMPT_TEMPLATE_CODE,
              PromptSystemPromptSegments.buildSlots(
                  memory, clarifyRequired, shortQuestionRewriter, retrievalContext));
      if (injected == null || injected.isBlank()) {
        // 注入 render 失败 → WARN 已在 renderTemplateContent 登记，回退静态 + 旧追加路径（检索上下文走 SystemMessage
        // 旧追加，injected=false）
        result =
            new SystemPromptBuild(
                PromptSystemPromptSegments.appendDynamicSections(
                    systemPrompt, shortQuestionRewriter, memory, clarifyRequired),
                false);
      } else {
        result = new SystemPromptBuild(injected, true);
      }
    }
    return result;
  }

  /** 取本次生效的系统提示词骨架：模板态 render 优先，任何异常回退启动期静态文本（aichat-prompt-template-integration 任务 2.1）。 */
  private String resolveSystemSkeleton() {
    String templateContent = renderTemplateContent(SYSTEM_PROMPT_TEMPLATE_CODE, null);
    String effective = templateContent == null ? systemPrompt : templateContent;
    applyEffectiveSource(
        effective, templateContent == null ? PromptSource.FALLBACK : PromptSource.TEMPLATE);
    return effective;
  }

  /** 取本次生效的标题提示词：模板态 render 优先，异常回退启动期静态文本（任务 2.2）。 */
  private String resolveTitlePrompt() {
    String templateContent = renderTemplateContent(TITLE_PROMPT_TEMPLATE_CODE, null);
    return templateContent == null ? titlePrompt : templateContent;
  }

  /**
   * 调用模板服务渲染骨架（任务 2.1/2.2 + add-prompt-template-slots 任务 2.1）。
   *
   * <p>回退链红线：模板不存在/停用/变量拒绝/任何运行时异常，一律 WARN 后返回 null 交由调用方回退静态文本，
   * 绝不让模板态异常中断聊天主链路（D2）。本卡零变量，variables 固定为空表；{@code slots} 为 null 时不替换槽位标记（供注入模式 判定），非 null
   * 时执行槽位注入。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 模板态任何异常都必须回退静态文本，不断聊天主链路（D2）
  private String renderTemplateContent(String code, Map<String, String> slots) {
    String content = null;
    if (promptTemplateService != null) {
      try {
        PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
        dto.setCode(code);
        dto.setVariables(Collections.emptyMap());
        dto.setSlots(slots);
        PromptTemplateRenderVO rendered = promptTemplateService.render(dto);
        content = rendered == null ? null : rendered.getContent();
        if (content == null || content.isBlank()) {
          log.warn("提示词模板渲染结果为空，回退静态文本: code={}", code);
          content = null;
        }
      } catch (RuntimeException e) {
        log.warn("提示词模板渲染失败，回退静态文本: code={}", code, e);
        content = null;
      }
    }
    return content;
  }

  /**
   * 按最终生效骨架刷新 promptVersion 与生效来源（任务 2.3 / D5 / D6）。
   *
   * <p>口径：promptVersion 始终取最终生效内容的 sha256 前 8 字节——模板被编辑后每次 render 都用实际生效骨架重算， 不做惰性单值缓存，避免版本滞后（D5
   * 最小侵入方案：在构建点即时重算）。
   */
  private void applyEffectiveSource(String skeleton, PromptSource source) {
    this.promptVersion = sha256Prefix(skeleton);
    this.promptSource = source;
  }

  private AiMessageEntity findLastUserQuestion(Long sessionId) {
    // listRecentContextMessages 返回 id 倒序，因此第一条用户消息就是最近用户问。
    return aiMessageService.listRecentContextMessages(sessionId, 2).stream()
        .filter(item -> "user".equals(item.getRole()))
        .findFirst()
        .orElse(null);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // LLM外呼多源，失败标题归null
  public String generateTitle(ChatClient.Builder chatClientBuilder, String userMessage) {
    String result;
    try {
      String title =
          chatClientBuilder
              .build()
              .prompt()
              .system(resolveTitlePrompt())
              .user(userMessage)
              .call()
              .content();
      result = title == null ? null : clampTitleLength(AiThinkTagStripper.strip(title).trim());
    } catch (Exception e) {
      log.warn("生成会话标题失败", e);
      result = null;
    }
    return result;
  }

  /**
   * 标题长度夹紧（add-ai-customer-360-insight 任务 3.2）：结果侧强制 ≤ {@value #MAX_TITLE_LENGTH} 字， 与提案 "4~12
   * 字简洁业务标题"口径对齐（提示词模板与迁移种子逐字节冻结，故在结果侧收口；不足 4 字不臆造补字）。
   *
   * @param title 模型输出标题（已去除思维链标记并 trim）
   * @return 夹紧后的标题；null 原样返回
   */
  static String clampTitleLength(String title) {
    String result = title;
    if (title != null && title.length() > MAX_TITLE_LENGTH) {
      result = title.substring(0, MAX_TITLE_LENGTH);
    }
    return result;
  }

  public String getPromptVersion() {
    return promptVersion;
  }

  /** 最近一次系统提示词构建的生效来源：TEMPLATE=模板态命中 / FALLBACK=回退静态文本（任务 2.3 / D6）。 */
  public PromptSource getPromptSource() {
    return promptSource;
  }

  /** 提示词生效来源枚举（任务 2.3 / D6）：模板态（render 成功）或回退态（类路径静态文本）。 */
  public enum PromptSource {
    TEMPLATE,
    FALLBACK
  }

  private List<Message> loadHistory(Long sessionId) {
    int maxRounds =
        aiProperties.getMaxHistoryRounds() == null ? 10 : aiProperties.getMaxHistoryRounds();
    List<AiMessageEntity> recent;
    if (conversationMemoryService != null) {
      // recentMessages 是 ai_message 的内存投影；恢复方法返回时间正序。
      recent = conversationMemoryService.restoreRecentProjection(sessionId, maxRounds * 2);
    } else {
      recent = aiMessageService.listRecentContextMessages(sessionId, maxRounds * 2);
      Collections.reverse(recent);
    }
    List<Message> history = new ArrayList<>();
    for (AiMessageEntity entity : recent) {
      String content = entity.getContent() == null ? "" : entity.getContent();
      if ("user".equals(entity.getRole())) {
        history.add(new UserMessage(content));
      } else {
        history.add(new AssistantMessage(AiThinkTagStripper.strip(content)));
      }
    }
    return history;
  }

  /** 保障 prompt 总量不超过配置上限；超限只牺牲最旧历史，不破坏本轮必要输入。 */
  private List<Message> applyPromptBudget(List<Message> messages) {
    int totalLength =
        messages.stream()
            .mapToInt(item -> item.getText() == null ? 0 : item.getText().length())
            .sum();
    while (totalLength > promptMaxChars && messages.size() > 2) {
      String removedText = messages.remove(1).getText();
      totalLength -= removedText == null ? 0 : removedText.length();
      log.info(
          "Prompt 超出预算，移除最旧历史: promptMaxChars={}, totalLength={}", promptMaxChars, totalLength);
    }
    return messages;
  }

  private String loadPromptFile(String path) {
    try {
      ClassPathResource resource = new ClassPathResource(path);
      try (InputStream is = resource.getInputStream()) {
        return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new IllegalStateException("加载提示词文件失败: " + path, e);
    }
  }

  private void validatePrompt(String path, String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("提示词文件内容不能为空白: " + path);
    }
  }

  private String sha256Prefix(String content) {
    String result;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
      StringBuilder version = new StringBuilder();
      for (int i = 0; i < 8 && i < hash.length; i++) {
        version.append(String.format("%02x", hash[i]));
      }
      result = version.toString();
    } catch (NoSuchAlgorithmException e) {
      log.warn("计算 prompt 版本哈希失败", e);
      result = "unknown";
    }
    return result;
  }
}
