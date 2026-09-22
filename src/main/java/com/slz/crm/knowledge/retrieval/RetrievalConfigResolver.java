package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 检索参数配置解析协作类（tighten-pmd-residual-325 任务 6.3 自 KnowledgeRetrievalServiceImpl 拆出，行为等价）。
 * 各动态配置键的缺省与回落口径集中于此。
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

  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  RetrievalConfigResolver(ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  /** 融合模式开关：仅显式配置 weighted 时回退升级前行为，其余（含未配置）走 RRF 默认。 */
  boolean useRrfFusion() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String mode = config == null ? null : config.get(FUSION_MODE_KEY, String.class, "rrf");
    return mode == null || !FUSION_MODE_WEIGHTED.equalsIgnoreCase(mode.strip());
  }

  int resolveRrfK() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(FUSION_RRF_K_KEY, Integer.class, DEFAULT_RRF_K);
    return configured == null || configured < 1 ? DEFAULT_RRF_K : configured;
  }

  int resolveTopK(Integer requested) {
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
      result =
          configured != null && configured > 0
              ? configured
              : KnowledgeRetrievalServiceImpl.DEFAULT_TOP_K;
    }
    return result;
  }

  double resolveMinScore() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.minScore",
                Double.class,
                KnowledgeRetrievalServiceImpl.DEFAULT_MIN_SCORE);
    return configured == null || configured < 0 || configured > 1
        ? KnowledgeRetrievalServiceImpl.DEFAULT_MIN_SCORE
        : configured;
  }

  int resolveCandidateMultiplier() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.rerank.candidate-multiplier",
                Integer.class,
                DEFAULT_CANDIDATE_MULTIPLIER);
    return configured != null && configured > 0 ? configured : DEFAULT_CANDIDATE_MULTIPLIER;
  }

  double resolveRatio(String key, double defaultValue) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured = config == null ? null : config.get(key, Double.class, defaultValue);
    return configured == null || configured < 0 || configured > 1 ? defaultValue : configured;
  }

  /** 图文路由融合权重（文本路）：{@code rag.retrieval.image-text-route-weight}，缺省 0.70。 */
  double resolveTextRouteWeight() {
    return resolveRatio("rag.retrieval.image-text-route-weight", DEFAULT_TEXT_ROUTE_WEIGHT);
  }

  /** 图文路由融合权重（图片路）：{@code rag.retrieval.image-vector-route-weight}，缺省 0.30。 */
  double resolveImageRouteWeight() {
    return resolveRatio("rag.retrieval.image-vector-route-weight", DEFAULT_IMAGE_ROUTE_WEIGHT);
  }

  /** 图文路由融合：文本 0.7 + 图片 0.3，同切片两路命中则累加（拆自 KnowledgeRetrievalServiceImpl.fuseRoutes，行为等价）。 */
  java.util.List<RetrievalCandidate> fuseRoutes(
      java.util.List<RetrievalCandidate> textCandidates,
      java.util.List<RetrievalCandidate> imageCandidates) {
    java.util.List<RetrievalCandidate> result;
    if (imageCandidates.isEmpty()) {
      result = textCandidates;
    } else {
      java.util.LinkedHashMap<String, RetrievalCandidate> merged = new java.util.LinkedHashMap<>();
      double textWeight = resolveTextRouteWeight();
      double imageWeight = resolveImageRouteWeight();
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
