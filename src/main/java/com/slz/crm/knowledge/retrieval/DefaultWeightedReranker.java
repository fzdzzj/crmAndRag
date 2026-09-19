package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 默认重排器：向量/BM25 归一化加权（方案08升级的回退基线，complete-hybrid-retrieval-and-rerank 任务 4.1）。
 *
 * <p>行为等价承诺：逻辑自 {@code KnowledgeRetrievalServiceImpl} 原路内 rerank 一比一搬运—— 向量分与 BM25 分各自 min-max
 * 归一化后按权重线性组合，权重键 {@code rag.retrieval.rerank.vector-weight} / {@code
 * rag.retrieval.rerank.bm25-weight}（0~1）。 升级前（weighted 融合/纯向量）的检索行为由此保证不变。
 */
@Service
public class DefaultWeightedReranker implements Reranker {
  private static final double DEFAULT_VECTOR_WEIGHT = 0.60;
  private static final double DEFAULT_BM25_WEIGHT = 0.40;

  private final Bm25Scorer bm25Scorer;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  public DefaultWeightedReranker(
      Bm25Scorer bm25Scorer, ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.bm25Scorer = bm25Scorer;
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  @Override
  public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<Double> vectorScores =
        candidates.stream().map(candidate -> candidate.hit().score()).toList();
    List<Double> bm25Scores =
        bm25Scorer.score(
            query, candidates.stream().map(candidate -> candidate.hit().text()).toList());
    List<Double> normalizedVectorScores = normalizeScores(vectorScores);
    List<Double> normalizedBm25Scores = normalizeScores(bm25Scores);

    double vectorWeight = resolveRatio("rag.retrieval.rerank.vector-weight", DEFAULT_VECTOR_WEIGHT);
    double bm25Weight = resolveRatio("rag.retrieval.rerank.bm25-weight", DEFAULT_BM25_WEIGHT);
    List<RetrievalCandidate> reranked = new ArrayList<>(candidates.size());
    for (int index = 0; index < candidates.size(); index++) {
      double score =
          normalizedVectorScores.get(index) * vectorWeight
              + normalizedBm25Scores.get(index) * bm25Weight;
      reranked.add(new RetrievalCandidate(candidates.get(index).hit(), score));
    }
    return reranked;
  }

  private List<Double> normalizeScores(List<Double> scores) {
    if (scores.isEmpty()) {
      return List.of();
    }
    double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    if (max <= 0) {
      return java.util.Collections.nCopies(scores.size(), 0.0);
    }
    if (max == min) {
      return java.util.Collections.nCopies(scores.size(), 1.0);
    }
    return scores.stream().map(score -> (score - min) / (max - min)).toList();
  }

  private double resolveRatio(String key, double defaultValue) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured = config == null ? null : config.get(key, Double.class, defaultValue);
    return configured == null || configured < 0 || configured > 1 ? defaultValue : configured;
  }
}
