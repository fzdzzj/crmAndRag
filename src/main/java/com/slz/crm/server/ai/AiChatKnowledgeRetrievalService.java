package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 助手检索编排。
 *
 * <p>KB OFF 绝不触发检索，也绝不注入未命中提示；KB ON 零命中时注入诚实生成的标记， 避免旧 RAG 的“强制兜底一句未检索到”。B 生产实现合入后只需替换 port 实现。
 *
 * <p>topK 参数化（add-context-compression-and-enrichment 任务 4.1）：检索条数从动态配置 {@code rag.retrieval.topK}
 * 解析；缺省/非法回退值与生产检索实现同源（{@link RetrievalDefaults#TOP_K}，TASK-18 收敛，曾孤例 4 判漏配）， 一致性由 {@code
 * RetrievalParamTruthSourceTest} 门禁保证。
 */
@Slf4j
@Component
public class AiChatKnowledgeRetrievalService {

  private static final String MISS_CONTEXT =
      """
            【知识库检索】本轮未命中知识库片段。请基于已掌握的信息诚实回答；\
            不要声称引用了知识库来源，也不要输出机械化的空结果提示。""";

  private static final String TOP_K_KEY = "rag.retrieval.topK";
  private static final int DEFAULT_TOP_K = RetrievalDefaults.TOP_K;

  private final ObjectProvider<KnowledgeRetrievalPort> retrievalPortProvider;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  public AiChatKnowledgeRetrievalService(
      ObjectProvider<KnowledgeRetrievalPort> retrievalPortProvider,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.retrievalPortProvider = retrievalPortProvider;
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 检索端口外呼多源，失败按零命中降级继续生成
  public RetrievalOutcome retrieve(
      String query, Long userId, float[] imageVector, boolean useKnowledgeBase) {
    RetrievalOutcome result;
    if (!useKnowledgeBase) {
      result = RetrievalOutcome.empty();
    } else {
      KnowledgeRetrievalPort port =
          retrievalPortProvider == null ? null : retrievalPortProvider.getIfAvailable();
      if (port == null) {
        log.warn("KnowledgeRetrievalPort 未就绪，按零命中继续生成");
        result = RetrievalOutcome.miss();
      } else {
        try {
          KnowledgeRetrievalPort.RetrievalResult retrievalResult =
              port.retrieve(
                  new KnowledgeRetrievalPort.RetrievalQuery(
                      query, userId, List.of(), resolveTopK(), imageVector, null));
          if (retrievalResult == null
              || retrievalResult.hitCount() <= 0
              || retrievalResult.context() == null
              || retrievalResult.context().isBlank()) {
            result = RetrievalOutcome.miss();
          } else {
            result =
                new RetrievalOutcome(
                    toPromptContext(retrievalResult.context()), retrievalResult.sources());
          }
        } catch (Exception exception) {
          log.warn("知识库检索失败，按零命中继续生成", exception);
          result = RetrievalOutcome.miss();
        }
      }
    }
    return result;
  }

  /**
   * 检索条数：动态配置 {@code rag.retrieval.topK}；未配置/&lt;1 回退真相源默认（与生产检索实现同源，一致性已由
   * RetrievalParamTruthSourceTest 门禁保证）。
   */
  private int resolveTopK() {
    DynamicConfigService config =
        dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(TOP_K_KEY, Integer.class, DEFAULT_TOP_K);
    return configured == null || configured < 1 ? DEFAULT_TOP_K : configured;
  }

  private String toPromptContext(String context) {
    return """
                【知识库检索】请优先使用以下片段；引用承重结论时在句尾使用 [编号]，\
                片段未支撑的内容不要编造来源。

                %s"""
        .formatted(context.trim());
  }

  public record RetrievalOutcome(String context, List<SourceReference> sources) {
    public RetrievalOutcome {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static RetrievalOutcome empty() {
      return new RetrievalOutcome(null, List.of());
    }

    public static RetrievalOutcome miss() {
      return new RetrievalOutcome(MISS_CONTEXT, List.of());
    }

    public boolean hasSources() {
      return !sources.isEmpty();
    }
  }
}
