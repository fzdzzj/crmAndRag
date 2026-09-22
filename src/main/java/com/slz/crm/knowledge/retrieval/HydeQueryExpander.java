package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * HyDE 假设答案扩展器（方案 15，enhance-query-transformation 任务 2.1）。
 *
 * <p>启用：DynamicConfig {@code rag.query.hyde.enabled}（默认 false——幻觉误导风险 + 成本）。
 * 启用后对查询生成一段假设性答案文本，交给嵌入+向量召回作为额外一路，与原查询路 RRF 融合。
 *
 * <p><b>隔离硬约束（任务 2.2）</b>：假设答案只用于产生检索向量——本类返回的文本 只允许流向 {@link
 * EmbeddingService}，绝不进入生成上下文、SourceReference 或日志正文。
 *
 * <p>降级语义（任务 2.3）：关闭 / 调用失败 / 空输出 / 等待超时 （{@code rag.query.hyde.timeout-ms}，默认 3000）——一律返回
 * null，调用方跳过 HyDE 路， 不向调用方抛错。
 */
@Service
public class HydeQueryExpander {
  private static final Logger log = LoggerFactory.getLogger(HydeQueryExpander.class);

  private static final String ENABLED_KEY = "rag.query.hyde.enabled";
  private static final String TIMEOUT_KEY = "rag.query.hyde.timeout-ms";
  private static final long DEFAULT_TIMEOUT_MS = 3000;
  private static final int MAX_LENGTH = 512;

  private static final String SYSTEM_PROMPT =
      """
            你是 CRM 知识库检索辅助器。根据用户查询写一段 60~120 字的假设性答案片段，
            用于在向量库里匹配真实证据文档。直接输出片段正文；不要解释；
            不要提及"假设""可能"等字眼；无法生成时只输出：无
            """;

  private final ModelProvider modelProvider;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  public HydeQueryExpander(
      ModelProvider modelProvider, ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.modelProvider = modelProvider;
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  /** 生成假设答案文本；关闭/失败/空输出/超时返回 null（调用方跳过 HyDE 路）。 返回值只允许用于嵌入，不得进入生成上下文（隔离硬约束见类注释）。 */
  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // LLM外呼+CompletableFuture.get多源，失败跳过HyDE路不抛
  public String hypotheticalAnswer(String query) {
    String result = null;
    if (query != null && !query.isBlank() && enabled()) {
      try {
        List<Message> messages =
            List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(query.strip()));
        ModelCallOptions options =
            new ModelCallOptions(null, false, 0.3d, 256, null, null, Map.of());
        CompletableFuture<String> future =
            CompletableFuture.supplyAsync(
                () -> modelProvider.chat(new Prompt(messages), options).content());
        result = sanitize(future.get(resolveTimeoutMs(), TimeUnit.MILLISECONDS));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        log.warn("HyDE 假设答案生成被中断，跳过 HyDE 路");
      } catch (Exception exception) {
        log.warn("HyDE 假设答案生成失败，跳过 HyDE 路: {}", exception.getMessage());
      }
    }
    return result;
  }

  /** 空输出/占位词/超长统一归一为 null 或定长文本。 */
  private String sanitize(String content) {
    String result = null;
    if (content != null && !content.isBlank()) {
      String value = content.strip().replaceAll("[\\r\\n]+", " ");
      if (value.length() > MAX_LENGTH) {
        value = value.substring(0, MAX_LENGTH).strip();
      }
      if (!value.isBlank() && !"无".equals(value) && !"无。".equals(value)) {
        result = value;
      }
    }
    return result;
  }

  private boolean enabled() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    return config != null && Boolean.TRUE.equals(config.get(ENABLED_KEY, Boolean.class, false));
  }

  /** 超时上限（protected 便于测试覆写短超时）。 */
  protected long resolveTimeoutMs() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Long configured =
        config == null ? null : config.get(TIMEOUT_KEY, Long.class, DEFAULT_TIMEOUT_MS);
    return configured == null || configured < 1 ? DEFAULT_TIMEOUT_MS : configured;
  }
}
