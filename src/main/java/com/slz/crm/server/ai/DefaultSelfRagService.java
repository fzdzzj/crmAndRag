package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 统一 Self-RAG 生成侧反思服务（add-self-rag-reflection 任务 2.1/2.2 门面）。
 *
 * <p>根据动态配置 {@code rag.generation.selfrag.mode}（默认 rule）路由反思策略：
 *
 * <ul>
 *   <li>{@code off}：完全关闭反思，保持原有引用与文本不变；
 *   <li>{@code rule}（默认）：执行确定性规则反思（{@link RuleSelfRagReflector}）；
 *   <li>{@code llm}：执行 LLM 支持度自评（{@link LlmSelfRagReflector}），失败自动回退规则链；
 *   <li>非法值/未配置：统一回退默认 {@code rule} 规则反思。
 * </ul>
 */
@Primary
@Service
public class DefaultSelfRagService implements SelfRagReflector {

  private static final String MODE_KEY = "rag.generation.selfrag.mode";
  private static final String DEFAULT_MODE = "rule";
  private static final String MODE_OFF = "off";
  private static final String MODE_LLM = "llm";

  private final RuleSelfRagReflector ruleReflector;
  private final ObjectProvider<LlmSelfRagReflector> llmReflectorProvider;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  public DefaultSelfRagService(
      RuleSelfRagReflector ruleReflector,
      ObjectProvider<LlmSelfRagReflector> llmReflectorProvider,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.ruleReflector = ruleReflector;
    this.llmReflectorProvider = llmReflectorProvider;
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  @Override
  public SelfRagResult reflect(
      String answer, List<SourceReference> sources, List<Integer> initialCitations) {
    String mode = resolveMode();
    SelfRagResult result;
    if (MODE_OFF.equalsIgnoreCase(mode)) {
      result =
          new SelfRagResult(
              answer,
              initialCitations != null
                  ? initialCitations
                  : CitationSupport.extractCitations(answer, sources),
              false);
    } else if (MODE_LLM.equalsIgnoreCase(mode)) {
      LlmSelfRagReflector llmReflector =
          llmReflectorProvider == null ? null : llmReflectorProvider.getIfAvailable();
      if (llmReflector != null) {
        result = llmReflector.reflect(answer, sources, initialCitations);
      } else {
        result = ruleReflector.reflect(answer, sources, initialCitations);
      }
    } else {
      result = ruleReflector.reflect(answer, sources, initialCitations);
    }
    return result;
  }

  private String resolveMode() {
    DynamicConfigService config =
        dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
    String configured = config == null ? null : config.get(MODE_KEY, String.class, DEFAULT_MODE);
    return configured == null || configured.isBlank() ? DEFAULT_MODE : configured.trim();
  }
}
