package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 检索参数配置解析协作类（tighten-pmd-residual-325 任务 6.3 自 KnowledgeRetrievalServiceImpl 拆出，行为等价；
 * add-per-kb-retrieval-strategy-override 任务 3.2 扩展 nullable singleKbId 三层合并重载族）。
 *
 * <p>各动态配置键的缺省与回落口径集中于此。<b>限制</b>：per-KB 单库作用域合并仅作用于本体解析的检索参数
 * （topK/minScore/fusion/rrf-k/candidate-multiplier/图文路由权重），以及三个独立方法
 * （neighbors/parent-expand/query-rewrite）；这三个键的运行时消费方（ContextBuilder /
 * RetrievalQueryRewriteService） 为保持冻结契约签名与「既有调用方零改动」，本卡不重接其消费路径（见回填注记）。
 */
final class RetrievalConfigResolver {
  private static final int DEFAULT_CANDIDATE_MULTIPLIER = 4;
  private static final double DEFAULT_TEXT_ROUTE_WEIGHT = 0.70;
  private static final double DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30;
  private static final String FUSION_MODE_KEY = "rag.retrieval.fusion.mode";
  private static final String FUSION_RRF_K_KEY = "rag.retrieval.fusion.rrf-k";
  private static final String FUSION_MODE_WEIGHTED = "weighted";
  private static final int DEFAULT_RRF_K = 60;
  private static final String RERANK_MODE_KEY = "rag.retrieval.rerank.mode";
  private static final String RERANK_MODE_LLM = "llm";
  private static final String NEIGHBORS_KEY = "rag.context.neighbors";
  private static final String PARENT_EXPAND_KEY = "rag.context.parent-expand";
  private static final String QUERY_REWRITE_KEY = "rag.retrieval.query-rewrite.enabled";
  private static final String PARENT_EXPAND_ON = "on";

  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final Supplier<KbRetrievalStrategyService> kbStrategySupplier;

  RetrievalConfigResolver(ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this(dynamicConfigProvider, null);
  }

  RetrievalConfigResolver(
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      Supplier<KbRetrievalStrategyService> kbStrategySupplier) {
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.kbStrategySupplier = kbStrategySupplier;
  }

  /** 融合模式开关：仅显式配置 weighted 时回退升级前行为，其余（含未配置）走 RRF 默认。 */
  boolean useRrfFusion() {
    return useRrfFusion(null);
  }

  /** 融合模式开关（per-KB 覆盖优先）：覆盖值合法（weighted/rrf）时生效；否则回落全局/默认。 */
  boolean useRrfFusion(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String mode = config == null ? null : config.get(FUSION_MODE_KEY, String.class, "rrf");
    String override = kbResolve(singleKbId, FUSION_MODE_KEY, String.class, mode);
    return override == null || !FUSION_MODE_WEIGHTED.equalsIgnoreCase(override.strip());
  }

  int resolveRrfK() {
    return resolveRrfK(null);
  }

  int resolveRrfK(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(FUSION_RRF_K_KEY, Integer.class, DEFAULT_RRF_K);
    Integer merged = kbResolve(singleKbId, FUSION_RRF_K_KEY, Integer.class, configured);
    return merged == null || merged < 1 ? DEFAULT_RRF_K : merged;
  }

  int resolveTopK(Integer requested) {
    return resolveTopK(requested, null);
  }

  /** topK 三层合并：调用方显式传参最优先 > per-KB 覆盖 > 全局 > 注册表默认（维持既有显式传参语义）。 */
  int resolveTopK(Integer requested, Long singleKbId) {
    int result;
    if (requested != null && requested > 0) {
      result = requested;
    } else {
      DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
      Integer configured =
          config == null
              ? null
              : config.get(
                  "rag.retrieval.topK", Integer.class, KnowledgeRetrievalServiceImpl.DEFAULT_TOP_K);
      Integer merged = kbResolve(singleKbId, "rag.retrieval.topK", Integer.class, configured);
      result = merged != null && merged > 0 ? merged : KnowledgeRetrievalServiceImpl.DEFAULT_TOP_K;
    }
    return result;
  }

  double resolveMinScore() {
    return resolveMinScore(null);
  }

  double resolveMinScore(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.minScore",
                Double.class,
                KnowledgeRetrievalServiceImpl.DEFAULT_MIN_SCORE);
    Double merged = kbResolve(singleKbId, "rag.retrieval.minScore", Double.class, configured);
    return merged == null || merged < 0 || merged > 1
        ? KnowledgeRetrievalServiceImpl.DEFAULT_MIN_SCORE
        : merged;
  }

  int resolveCandidateMultiplier() {
    return resolveCandidateMultiplier(null);
  }

  int resolveCandidateMultiplier(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.rerank.candidate-multiplier",
                Integer.class,
                DEFAULT_CANDIDATE_MULTIPLIER);
    Integer merged =
        kbResolve(
            singleKbId, "rag.retrieval.rerank.candidate-multiplier", Integer.class, configured);
    return merged != null && merged > 0 ? merged : DEFAULT_CANDIDATE_MULTIPLIER;
  }

  double resolveRatio(String key, double defaultValue) {
    return resolveRatio(key, defaultValue, null);
  }

  /** 比率类键三层合并（路由权重等）：覆盖值合法即生效，否则回落全局/默认。 */
  double resolveRatio(String key, double defaultValue, Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured = config == null ? null : config.get(key, Double.class, defaultValue);
    Double merged = kbResolve(singleKbId, key, Double.class, configured);
    return merged == null || merged < 0 || merged > 1 ? defaultValue : merged;
  }

  /** 图文路由融合权重（文本路）：{@code rag.retrieval.image-text-route-weight}，缺省 0.70。 */
  double resolveTextRouteWeight() {
    return resolveRatio("rag.retrieval.image-text-route-weight", DEFAULT_TEXT_ROUTE_WEIGHT);
  }

  /** 图文路由融合权重（文本路，per-KB 覆盖优先）。 */
  double resolveTextRouteWeight(Long singleKbId) {
    return resolveRatio(
        "rag.retrieval.image-text-route-weight", DEFAULT_TEXT_ROUTE_WEIGHT, singleKbId);
  }

  /** 图文路由融合权重（图片路）：{@code rag.retrieval.image-vector-route-weight}，缺省 0.30。 */
  double resolveImageRouteWeight() {
    return resolveRatio("rag.retrieval.image-vector-route-weight", DEFAULT_IMAGE_ROUTE_WEIGHT);
  }

  /** 图文路由融合权重（图片路，per-KB 覆盖优先）。 */
  double resolveImageRouteWeight(Long singleKbId) {
    return resolveRatio(
        "rag.retrieval.image-vector-route-weight", DEFAULT_IMAGE_ROUTE_WEIGHT, singleKbId);
  }

  /** 邻居增强开关（三层合并，per-KB 覆盖优先）：≥1 为开、0/负为关；缺省 1。 */
  boolean neighborsEnabled(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured = config == null ? null : config.get(NEIGHBORS_KEY, Integer.class, 1);
    Integer merged = kbResolve(singleKbId, NEIGHBORS_KEY, Integer.class, configured);
    return merged != null && merged >= 1;
  }

  /** 父块展开开关（三层合并，per-KB 覆盖优先）：on 为开、其余为关；缺省 on。 */
  boolean parentExpandEnabled(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String configured = config == null ? null : config.get(PARENT_EXPAND_KEY, String.class, "on");
    String merged = kbResolve(singleKbId, PARENT_EXPAND_KEY, String.class, configured);
    return merged != null && PARENT_EXPAND_ON.equalsIgnoreCase(merged.strip());
  }

  /** 查询改写开关（三层合并，per-KB 覆盖优先）：缺省 true。 */
  boolean queryRewriteEnabled(Long singleKbId) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Boolean configured =
        config == null ? null : config.get(QUERY_REWRITE_KEY, Boolean.class, Boolean.TRUE);
    Boolean merged = kbResolve(singleKbId, QUERY_REWRITE_KEY, Boolean.class, configured);
    return merged == null || merged;
  }

  /** per-KB 覆盖三层合并回落：singleKbId 为空或无可用覆盖时原样返回 global（全局/默认由调用方已算好）。 */
  private <T> T kbResolve(Long singleKbId, String key, Class<T> type, T global) {
    T result = global;
    if (singleKbId != null) {
      KbRetrievalStrategyService service =
          kbStrategySupplier == null ? null : kbStrategySupplier.get();
      if (service != null) {
        result = service.resolve(singleKbId, key, type, global);
      }
    }
    return result;
  }

  /** 图文路由融合：文本 0.7 + 图片 0.3，同切片两路命中则累加（拆自 KnowledgeRetrievalServiceImpl.fuseRoutes，行为等价）。 */
  java.util.List<RetrievalCandidate> fuseRoutes(
      java.util.List<RetrievalCandidate> textCandidates,
      java.util.List<RetrievalCandidate> imageCandidates) {
    return fuseRoutes(textCandidates, imageCandidates, null);
  }

  /** 图文路由融合（per-KB 覆盖优先于全局/默认路由权重）。 */
  java.util.List<RetrievalCandidate> fuseRoutes(
      java.util.List<RetrievalCandidate> textCandidates,
      java.util.List<RetrievalCandidate> imageCandidates,
      Long singleKbId) {
    java.util.List<RetrievalCandidate> result;
    if (imageCandidates.isEmpty()) {
      result = textCandidates;
    } else {
      java.util.LinkedHashMap<String, RetrievalCandidate> merged = new java.util.LinkedHashMap<>();
      double textWeight = resolveTextRouteWeight(singleKbId);
      double imageWeight = resolveImageRouteWeight(singleKbId);
      mergeRoute(merged, textCandidates, textWeight);
      mergeRoute(merged, imageCandidates, imageWeight);
      result = java.util.List.copyOf(merged.values());
    }
    return result;
  }

  private void mergeRoute(
      java.util.Map<String, RetrievalCandidate> merged,
      java.util.List<RetrievalCandidate> candidates,
      double routeWeight) {
    for (RetrievalCandidate candidate : candidates) {
      RetrievalCandidate existing = merged.get(candidate.fusionKey());
      if (existing == null) {
        merged.put(
            candidate.fusionKey(),
            new RetrievalCandidate(candidate.hit(), candidate.rerankScore() * routeWeight));
      } else {
        merged.put(
            candidate.fusionKey(),
            new RetrievalCandidate(
                existing.hit(), existing.rerankScore() + candidate.rerankScore() * routeWeight));
      }
    }
  }
}
