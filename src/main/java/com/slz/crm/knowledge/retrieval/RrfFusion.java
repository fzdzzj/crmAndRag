package com.slz.crm.knowledge.retrieval;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 双路召回 RRF 融合（rag-kb 06 检索后处理，complete-hybrid-retrieval-and-rerank 任务 3.1）。
 *
 * <p>Reciprocal Rank Fusion：对每一路按路内分数降序取排名（1 起），切片融合分 = {@code Σ 1/(k + rank)}。k 越大两路差距越平缓（生产默认
 * 60，DynamicConfig {@code rag.retrieval.fusion.rrf-k}）。只用排名不用原始分，天然回避向量相似度与 MATCH...AGAINST
 * 相关度的量纲差异。
 *
 * <p>退化语义：单路命中 = 该路排名的单调变换，顺序不变；两路命中同切片（fusionKey 相同） 得分为各路倒数和，hit 以向量路（先积累者）为准。
 */
@Service
public class RrfFusion {

  /**
   * 融合两路候选，按融合分降序返回。
   *
   * @param vectorRoute 向量路候选（路内分数为相似度）
   * @param sparseRoute 稀疏路候选（路内分数为 MATCH...AGAINST 相关度）
   * @param k RRF 常数（&gt;= 1）
   */
  public List<RetrievalCandidate> fuse(
      List<RetrievalCandidate> vectorRoute, List<RetrievalCandidate> sparseRoute, int k) {
    // Arrays.asList 兼容调用方传 null 路（既有契约：缺路安全跳过），List.of 拒绝 null 元素
    return fuseAll(java.util.Arrays.asList(vectorRoute, sparseRoute), k);
  }

  /**
   * N 路融合（enhance-query-transformation 任务 1.2，多查询/HyDE 路复用同一融合语义）： 每路独立按路内分数降序取排名（1 起），切片融合分 =
   * {@code Σ_路 1/(k + rank)}； 同一切片（fusionKey 相同）跨路命中得分累加，hit 保留先出现路的条目。 空路/null 路安全跳过；全部为空返回空列表。
   */
  public List<RetrievalCandidate> fuseAll(List<List<RetrievalCandidate>> routes, int k) {
    List<RetrievalCandidate> result = List.of();
    if (routes != null && !routes.isEmpty()) {
      Map<String, Double> scores = new HashMap<>();
      Map<String, RetrievalCandidate> hits = new LinkedHashMap<>();
      for (List<RetrievalCandidate> route : routes) {
        accumulate(scores, hits, route, k);
      }
      if (!scores.isEmpty()) {
        result =
            scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(
                    entry ->
                        new RetrievalCandidate(hits.get(entry.getKey()).hit(), entry.getValue()))
                .toList();
      }
    }
    return result;
  }

  /** 路内按分数降序取排名后累加 RRF 分；空路/缺路（null）安全跳过。 */
  private void accumulate(
      Map<String, Double> scores,
      Map<String, RetrievalCandidate> hits,
      List<RetrievalCandidate> route,
      int k) {
    if (route == null || route.isEmpty()) {
      return;
    }
    List<RetrievalCandidate> ranked =
        route.stream()
            .sorted(Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed())
            .toList();
    for (int index = 0; index < ranked.size(); index++) {
      RetrievalCandidate candidate = ranked.get(index);
      scores.merge(candidate.fusionKey(), 1.0 / (k + index + 1), Double::sum);
      hits.putIfAbsent(candidate.fusionKey(), candidate);
    }
  }
}
