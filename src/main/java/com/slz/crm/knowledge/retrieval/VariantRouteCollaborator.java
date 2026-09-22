package com.slz.crm.knowledge.retrieval;

import com.slz.crm.knowledge.embedding.EmbeddingService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多查询变体路与 HyDE 路召回协作类（tighten-pmd-residual-325 任务 6.3 自 KnowledgeRetrievalServiceImpl 拆出，行为等价）。
 * 变体路经 routeExecutor 并行执行「嵌入 + 文本路召回」，单路失败降级为空路，已完成路照常参与融合； HyDE 假设答案只用于产生检索向量（隔离硬约束），缺席不影响其他路。
 */
final class VariantRouteCollaborator {
  private static final Logger LOG = LoggerFactory.getLogger(VariantRouteCollaborator.class);

  /** 文本路召回回调：由宿主服务实现（recallTextRoute），协作类不感知向量库与稀疏路细节。 */
  @FunctionalInterface
  interface TextRouteRecaller {
    List<RetrievalCandidate> recall(
        String query,
        float[] queryVector,
        List<Long> knowledgeBaseIds,
        String category,
        int candidateLimit,
        double minScore);
  }

  private final EmbeddingService embeddingService;
  private final MultiQueryRewriteService multiQueryRewriteService;
  private final HydeQueryExpander hydeQueryExpander;
  private final Executor routeExecutor;
  private final TextRouteRecaller recaller;

  VariantRouteCollaborator(
      EmbeddingService embeddingService,
      MultiQueryRewriteService multiQueryRewriteService,
      HydeQueryExpander hydeQueryExpander,
      Executor routeExecutor,
      TextRouteRecaller recaller) {
    this.embeddingService = embeddingService;
    this.multiQueryRewriteService = multiQueryRewriteService;
    this.hydeQueryExpander = hydeQueryExpander;
    this.routeExecutor = routeExecutor;
    this.recaller = recaller;
  }

  /**
   * 多查询变体路（提案5 任务 1.1/1.2）：expand 产出 原始 + N 变体（关闭/失败退化为仅原始）， 变体路经 routeExecutor 并行执行「嵌入 +
   * 文本路召回」，单路失败降级为空路（exceptionally 兜底）， 已完成路照常参与融合；全部失败时路由 0 仍走单查询现行为。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 多查询扩展异常源多（expand/嵌入/召回），失败回退单查询
  List<CompletableFuture<List<RetrievalCandidate>>> submitVariantRoutes(
      String primaryQuery,
      List<Long> knowledgeBaseIds,
      String category,
      int candidateLimit,
      double minScore) {
    List<CompletableFuture<List<RetrievalCandidate>>> result = List.of();
    if (multiQueryRewriteService != null && routeExecutor != null) {
      try {
        List<String> queryRoutes = multiQueryRewriteService.expand(primaryQuery);
        if (queryRoutes.size() > 1) {
          result =
              queryRoutes.subList(1, queryRoutes.size()).stream()
                  .map(
                      variantQuery ->
                          CompletableFuture.supplyAsync(
                                  () ->
                                      recaller.recall(
                                          variantQuery,
                                          embeddingService.embed(variantQuery),
                                          knowledgeBaseIds,
                                          category,
                                          candidateLimit,
                                          minScore),
                                  routeExecutor)
                              .exceptionally(
                                  exception -> {
                                    LOG.warn("多查询路召回失败，降级为已完成路融合: {}", exception.getMessage());
                                    return List.of();
                                  }))
                  .toList();
        }
      } catch (RuntimeException exception) {
        LOG.warn("多查询扩展异常，回退单查询: {}", exception.getMessage());
      }
    }
    return result;
  }

  /**
   * HyDE 路（提案5 任务 2.1）：假设答案<b>只用于产生检索向量</b>（隔离硬约束——该文本 不进入任何候选/上下文/引用），走纯向量召回（稀疏路对假设文本无意义）。 生成失败返回
   * null、嵌入/召回失败吞掉——HyDE 路缺席不影响其他路。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // HyDE路嵌入+召回多源，失败该路跳过不影响他路
  void addHydeRoute(
      String primaryQuery,
      List<List<RetrievalCandidate>> routes,
      List<Long> knowledgeBaseIds,
      String category,
      int candidateLimit,
      double minScore) {
    if (hydeQueryExpander != null) {
      String hypothesis = hydeQueryExpander.hypotheticalAnswer(primaryQuery);
      if (hypothesis != null && !hypothesis.isBlank()) {
        try {
          float[] hypothesisVector = embeddingService.embed(hypothesis);
          routes.add(
              recaller.recall(
                  hypothesis,
                  hypothesisVector,
                  knowledgeBaseIds,
                  category,
                  candidateLimit,
                  minScore));
        } catch (RuntimeException exception) {
          LOG.warn("HyDE 路召回失败，该路跳过: {}", exception.getMessage());
        }
      }
    }
  }

  /** 收集多变体异步路结果：exceptionally 已把单路失败兜底为空路，join 再兜一层保证"已完成路照常融合" */
  void collectVariantRoutes(
      List<List<RetrievalCandidate>> routes,
      List<CompletableFuture<List<RetrievalCandidate>>> variantFutures) {
    for (CompletableFuture<List<RetrievalCandidate>> variantFuture : variantFutures) {
      try {
        routes.add(variantFuture.join());
      } catch (CompletionException exception) {
        LOG.warn("多查询路结果获取失败，该路降级跳过: {}", exception.getMessage());
      }
    }
  }

  /**
   * 多路文本候选融合：单路直接返回，多路优先用装配的 rrfFusion，V1 回退态（null）本地 new。
   *
   * @param routes 各路候选
   * @param assembledFusion 宿主装配的 RrfFusion（可为 null，回退本地 new）
   * @param rrfK RRF 融合参数（宿主按配置解析）
   */
  List<RetrievalCandidate> fuseTextRoutes(
      List<List<RetrievalCandidate>> routes, RrfFusion assembledFusion, int rrfK) {
    List<RetrievalCandidate> result;
    if (routes.size() <= 1) {
      result = routes.isEmpty() ? List.of() : routes.getFirst();
    } else {
      RrfFusion fusion = assembledFusion != null ? assembledFusion : new RrfFusion();
      result = fusion.fuseAll(routes, rrfK);
    }
    return result;
  }
}
