package com.slz.crm.knowledge.retrieval;

import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 知识库生产检索实现：授权 → 改写 → 双路召回（向量+稀疏）→ RRF 融合 → 重排 → 路由融合 → topK。
 *
 * <p>方案16补全 + 方案08升级（complete-hybrid-retrieval-and-rerank）：在纯向量召回之外接入 语料级稀疏召回路（{@link
 * SparseRecallService}），两路 RRF 融合（weighted 模式回退升级前行为）； 重排器可插拔（默认 = 向量/BM25 归一化加权，可选 LLM 重排）。
 * 图文路由融合与 topK 流程保持不变，仅插入新环节。
 *
 * <p>方案04/10（add-context-compression-and-enrichment）：topK 截取后接上下文组装器 {@link ContextBuilder}——邻居增强 +
 * 超预算压缩；未装配（兼容构造）时保持升级前纯拼接。
 */
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalPort {
  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeRetrievalServiceImpl.class);

  /**
   * 缺省 topK/minScore 引用单一真相源 {@link RetrievalDefaults}（TASK-18，漂移由 RetrievalParamTruthSourceTest
   * 门禁拦截）。
   */
  private static final int DEFAULT_TOP_K = RetrievalDefaults.TOP_K;

  private static final int DEFAULT_CANDIDATE_MULTIPLIER = 4;
  private static final double DEFAULT_MIN_SCORE = RetrievalDefaults.MIN_SCORE;
  private static final double DEFAULT_TEXT_ROUTE_WEIGHT = 0.70;
  private static final double DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30;
  private static final String FUSION_MODE_KEY = "rag.retrieval.fusion.mode";
  private static final String FUSION_RRF_K_KEY = "rag.retrieval.fusion.rrf-k";
  private static final String FUSION_MODE_WEIGHTED = "weighted";
  private static final int DEFAULT_RRF_K = 60;
  private static final String RERANK_MODE_KEY = "rag.retrieval.rerank.mode";
  private static final String RERANK_MODE_LLM = "llm";

  private final KnowledgeBaseAuthorizationService authorizationService;
  private final EmbeddingService embeddingService;
  private final CrmVectorStore vectorStore;
  private final RetrievalQueryRewriteService queryRewriteService;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  /** 稀疏召回路；null = 关闭（回退纯向量单路，兼容既有装配与测试）。 */
  private final SparseRecallService sparseRecallService;

  /** RRF 融合器（任务 3.1）；null = 兼容构造下不可用。 */
  private final RrfFusion rrfFusion;

  /** 默认重排器：向量/BM25 归一化加权（任务 4.1，行为等价搬运）。 */
  private final DefaultWeightedReranker defaultReranker;

  /** LLM 重排器（任务 4.2，默认关闭）；null = 兼容构造下不可用。 */
  private final LlmReranker llmReranker;

  /** 上下文组装器（提案3：邻居增强 + 超预算压缩）；null = 兼容构造下升级前纯拼接。 */
  private final ContextBuilder contextBuilder;

  /** 多查询变体生成器（提案5 任务 1.1，默认关闭）；null = 兼容构造下不可用。 */
  private final MultiQueryRewriteService multiQueryRewriteService;

  /** HyDE 假设答案扩展器（提案5 任务 2.1，默认关闭）；null = 兼容构造下不可用。 */
  private final HydeQueryExpander hydeQueryExpander;

  /** 多路并行召回执行器（提案5 任务 1.2）；null = 变体路退化为不可用。 */
  private final Executor routeExecutor;

  @Autowired
  public KnowledgeRetrievalServiceImpl(
      KnowledgeBaseAuthorizationService authorizationService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      RetrievalQueryRewriteService queryRewriteService,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      SparseRecallService sparseRecallService,
      RrfFusion rrfFusion,
      DefaultWeightedReranker defaultReranker,
      LlmReranker llmReranker,
      ContextBuilder contextBuilder,
      MultiQueryRewriteService multiQueryRewriteService,
      HydeQueryExpander hydeQueryExpander,
      @Qualifier("embeddingTaskExecutor") Executor routeExecutor) {
    this.authorizationService = authorizationService;
    this.embeddingService = embeddingService;
    this.vectorStore = vectorStore;
    this.queryRewriteService = queryRewriteService;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.sparseRecallService = sparseRecallService;
    this.rrfFusion = rrfFusion;
    this.defaultReranker = defaultReranker;
    this.llmReranker = llmReranker;
    this.contextBuilder = contextBuilder;
    this.multiQueryRewriteService = multiQueryRewriteService;
    this.hydeQueryExpander = hydeQueryExpander;
    this.routeExecutor = routeExecutor;
  }

  /** 兼容构造（提案4 十参）：不接提案5 查询侧增强（多查询/HyDE 关闭）。 */
  public KnowledgeRetrievalServiceImpl(
      KnowledgeBaseAuthorizationService authorizationService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      RetrievalQueryRewriteService queryRewriteService,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      SparseRecallService sparseRecallService,
      RrfFusion rrfFusion,
      DefaultWeightedReranker defaultReranker,
      LlmReranker llmReranker,
      ContextBuilder contextBuilder) {
    this(
        authorizationService,
        embeddingService,
        vectorStore,
        queryRewriteService,
        dynamicConfigProvider,
        sparseRecallService,
        rrfFusion,
        defaultReranker,
        llmReranker,
        contextBuilder,
        null,
        null,
        null);
  }

  /** 兼容构造（提案2 九参）：不接上下文组装器（升级前纯拼接行为）。 */
  public KnowledgeRetrievalServiceImpl(
      KnowledgeBaseAuthorizationService authorizationService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      RetrievalQueryRewriteService queryRewriteService,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      SparseRecallService sparseRecallService,
      RrfFusion rrfFusion,
      DefaultWeightedReranker defaultReranker,
      LlmReranker llmReranker) {
    this(
        authorizationService,
        embeddingService,
        vectorStore,
        queryRewriteService,
        dynamicConfigProvider,
        sparseRecallService,
        rrfFusion,
        defaultReranker,
        llmReranker,
        null);
  }

  /** 兼容构造：不接稀疏路/RRF/LLM 重排（纯向量单路 + 路内加权，升级前行为）。 */
  public KnowledgeRetrievalServiceImpl(
      KnowledgeBaseAuthorizationService authorizationService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      RetrievalQueryRewriteService queryRewriteService,
      Bm25Scorer bm25Scorer,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this(
        authorizationService,
        embeddingService,
        vectorStore,
        queryRewriteService,
        dynamicConfigProvider,
        null,
        null,
        new DefaultWeightedReranker(bm25Scorer, dynamicConfigProvider),
        null);
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 多查询路join降级兜底：各异步路异常源多，单路失败跳过不影响他路
  public KnowledgeRetrievalPort.RetrievalResult retrieve(
      KnowledgeRetrievalPort.RetrievalQuery query) {
    UserContext user = UserContextHolder.require();
    if (query.userId() != null && !Objects.equals(query.userId(), user.userId())) {
      throw new SecurityException("检索用户与当前登录用户不一致");
    }
    if (query.query() == null || query.query().isBlank()) {
      throw new IllegalArgumentException("检索 query 不能为空");
    }

    List<Long> knowledgeBaseIds =
        authorizationService.authorizedKnowledgeBaseIds(user, query.kbScope());
    KnowledgeRetrievalPort.RetrievalResult result;
    if (knowledgeBaseIds.isEmpty()) {
      result = KnowledgeRetrievalPort.RetrievalResult.empty();
    } else {
      result = retrieveFromKnowledgeBases(query, knowledgeBaseIds);
    }
    return result;
  }

  /** 授权 KB 非空时的主检索链路：改写 → 多路文本召回融合 → 重排 → 文图融合 → 构建结果 */
  private KnowledgeRetrievalPort.RetrievalResult retrieveFromKnowledgeBases(
      KnowledgeRetrievalPort.RetrievalQuery query, List<Long> knowledgeBaseIds) {
    String retrievalQuery = queryRewriteService.rewrite(query.query());
    // D17 收尾（complete-hybrid-retrieval-and-rerank 任务 2.1）：意图类目两路同语义过滤，空 = 不过滤
    String category = normalizeCategory(query.intentCategory());
    int topK = resolveTopK(query.topK());
    double minScore = resolveMinScore();
    int candidateLimit = topK * resolveCandidateMultiplier();
    boolean hasImageVector = query.imageVector() != null && query.imageVector().length > 0;

    List<RetrievalCandidate> fusedTextCandidates =
        recallAllTextRoutes(retrievalQuery, knowledgeBaseIds, category, candidateLimit, minScore);

    Reranker reranker = activeReranker();
    List<RetrievalCandidate> textCandidates = reranker.rerank(retrievalQuery, fusedTextCandidates);
    List<RetrievalCandidate> imageCandidates =
        hasImageVector
            ? reranker.rerank(
                retrievalQuery,
                recall(query.imageVector(), knowledgeBaseIds, category, candidateLimit, minScore))
            : List.of();
    List<RetrievalCandidate> candidates =
        fuseRoutes(textCandidates, imageCandidates).stream()
            .sorted(Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed())
            .limit(topK)
            .toList();
    return toRetrievalResult(candidates);
  }

  /**
   * 文本多路召回并融合：改写后确定性拆「A后B/A且B」为原查询+左右路，每路 embed+文本召回； 切不出时仍仅 1 次 embed。V1
   * 六参（this.rrfFusion==null）也必须能融多路 → 本地 new RrfFusion。 提案5 多查询/HyDE 仍仅在 RRF 且 this.rrfFusion
   * 非空时叠加（默认关，行为不变）。
   */
  private List<RetrievalCandidate> recallAllTextRoutes(
      String retrievalQuery,
      List<Long> knowledgeBaseIds,
      String category,
      int candidateLimit,
      double minScore) {
    List<List<RetrievalCandidate>> routes = new ArrayList<>();
    List<String> constraintQueries = ConstraintQuerySplitter.split(retrievalQuery);
    for (String routeQuery : constraintQueries) {
      routes.add(
          recallTextRoute(
              routeQuery,
              embeddingService.embed(routeQuery),
              knowledgeBaseIds,
              category,
              candidateLimit,
              minScore));
    }
    boolean multiRouteEnabled = useRrfFusion() && rrfFusion != null;
    List<CompletableFuture<List<RetrievalCandidate>>> variantFutures =
        multiRouteEnabled
            ? submitVariantRoutes(
                retrievalQuery, knowledgeBaseIds, category, candidateLimit, minScore)
            : List.of();
    if (multiRouteEnabled) {
      addHydeRoute(retrievalQuery, routes, knowledgeBaseIds, category, candidateLimit, minScore);
    }
    collectVariantRoutes(routes, variantFutures);
    return fuseTextRoutes(routes);
  }

  /** 收集多变体异步路结果：exceptionally 已把单路失败兜底为空路，join 再兜一层保证"已完成路照常融合" */
  private void collectVariantRoutes(
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

  /** 多路文本候选融合：单路直接返回，多路优先用装配的 rrfFusion，V1 回退态（null）本地 new */
  private List<RetrievalCandidate> fuseTextRoutes(List<List<RetrievalCandidate>> routes) {
    if (routes.size() <= 1) {
      return routes.isEmpty() ? List.of() : routes.getFirst();
    }
    RrfFusion fusion = rrfFusion != null ? rrfFusion : new RrfFusion();
    return fusion.fuseAll(routes, resolveRrfK());
  }

  /** 由最终候选集构建检索结果：空候选返回空结果，否则映射来源引用并携带上下文 */
  private KnowledgeRetrievalPort.RetrievalResult toRetrievalResult(
      List<RetrievalCandidate> candidates) {
    if (candidates.isEmpty()) {
      return KnowledgeRetrievalPort.RetrievalResult.empty();
    }
    List<SourceReference> sources =
        candidates.stream()
            .map(candidate -> toSourceReference(candidate.hit(), candidate.rerankScore()))
            .toList();
    return new KnowledgeRetrievalPort.RetrievalResult(
        buildContext(candidates), sources, candidates.size());
  }

  /**
   * 文本路召回：向量路（按授权 KB 逐库过滤）+ 稀疏路 → 融合（任务 3.1/3.3）。 融合模式 {@code rag.retrieval.fusion.mode = rrf |
   * weighted}（默认 rrf）； weighted = 升级前行为，稀疏路完全不参与。
   */
  private List<RetrievalCandidate> recallTextRoute(
      String query,
      float[] queryVector,
      List<Long> knowledgeBaseIds,
      String category,
      int candidateLimit,
      double minScore) {
    List<RetrievalCandidate> vectorCandidates =
        recall(queryVector, knowledgeBaseIds, category, candidateLimit, minScore);
    List<RetrievalCandidate> result;
    if (sparseRecallService == null || rrfFusion == null || !useRrfFusion()) {
      result = vectorCandidates;
    } else {
      List<RetrievalCandidate> sparseCandidates =
          sparseRecallService.recall(query, knowledgeBaseIds, category, candidateLimit);
      if (sparseCandidates.isEmpty()) {
        result = vectorCandidates;
      } else {
        result = rrfFusion.fuse(vectorCandidates, sparseCandidates, resolveRrfK());
      }
    }
    return result;
  }

  /**
   * 多查询变体路（提案5 任务 1.1/1.2）：expand 产出 原始 + N 变体（关闭/失败退化为仅原始）， 变体路经 routeExecutor 并行执行「嵌入 +
   * 文本路召回」，单路失败降级为空路（exceptionally 兜底）， 已完成路照常参与融合；全部失败时路由 0 仍走单查询现行为。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 多查询扩展异常源多（expand/嵌入/召回），失败回退单查询
  private List<CompletableFuture<List<RetrievalCandidate>>> submitVariantRoutes(
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
                                      recallTextRoute(
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
  private void addHydeRoute(
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
              recall(hypothesisVector, knowledgeBaseIds, category, candidateLimit, minScore));
        } catch (RuntimeException exception) {
          LOG.warn("HyDE 路召回失败，该路跳过: {}", exception.getMessage());
        }
      }
    }
  }

  /** 融合模式开关：仅显式配置 weighted 时回退升级前行为，其余（含未配置）走 RRF 默认。 */
  private boolean useRrfFusion() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String mode = config == null ? null : config.get(FUSION_MODE_KEY, String.class, "rrf");
    return mode == null || !FUSION_MODE_WEIGHTED.equalsIgnoreCase(mode.strip());
  }

  private int resolveRrfK() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(FUSION_RRF_K_KEY, Integer.class, DEFAULT_RRF_K);
    return configured == null || configured < 1 ? DEFAULT_RRF_K : configured;
  }

  /**
   * 每个知识库单独过滤，保证授权集合不能被伪造 metadata 放大；类目与授权叠加（只窄化不放大）。 提案5（任务 3.1）：衍生问题向量与原块共享
   * chunkId（命中即回原块），同键命中保留最高分一条， 防止同切片多向量挤占 topK；无衍生向量时键唯一，行为与升级前逐条一致。
   */
  private List<RetrievalCandidate> recall(
      float[] queryVector,
      List<Long> knowledgeBaseIds,
      String category,
      int candidateLimit,
      double minScore) {
    Map<String, RetrievalCandidate> uniqueByChunk = new LinkedHashMap<>();
    for (Long knowledgeBaseId : knowledgeBaseIds) {
      Map<String, Object> filter =
          category == null
              ? Map.of("knowledgeBaseId", String.valueOf(knowledgeBaseId))
              : Map.of("knowledgeBaseId", String.valueOf(knowledgeBaseId), "category", category);
      vectorStore
          .search(new VectorSearchRequest(queryVector, candidateLimit, minScore, filter))
          .stream()
          .filter(hit -> hit.text() != null && !hit.text().isBlank())
          .map(hit -> new RetrievalCandidate(hit, hit.score()))
          .forEach(
              candidate ->
                  uniqueByChunk.merge(
                      candidate.fusionKey(),
                      candidate,
                      (existing, replacement) ->
                          replacement.rerankScore() > existing.rerankScore()
                              ? replacement
                              : existing));
    }
    return List.copyOf(uniqueByChunk.values());
  }

  /** 类目归一：null/空白 = 不过滤，其余去首尾空白后参与两路过滤。 */
  private String normalizeCategory(String intentCategory) {
    return intentCategory == null || intentCategory.isBlank() ? null : intentCategory.strip();
  }

  /**
   * 重排器选择（任务 4.2/4.3）：{@code rag.retrieval.rerank.mode = default | llm}（默认 default）； llm
   * 未装配（兼容构造）时同样落回默认链。
   */
  private Reranker activeReranker() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String mode = config == null ? null : config.get(RERANK_MODE_KEY, String.class, "default");
    Reranker result = defaultReranker;
    if (mode != null && RERANK_MODE_LLM.equalsIgnoreCase(mode.strip()) && llmReranker != null) {
      result = llmReranker;
    }
    return result;
  }

  /** 图文路由融合：文本 0.7 + 图片 0.3，同切片两路命中则累加。 */
  private List<RetrievalCandidate> fuseRoutes(
      List<RetrievalCandidate> textCandidates, List<RetrievalCandidate> imageCandidates) {
    List<RetrievalCandidate> result;
    if (imageCandidates.isEmpty()) {
      result = textCandidates;
    } else {
      Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
      double textWeight =
          resolveRatio("rag.retrieval.image-text-route-weight", DEFAULT_TEXT_ROUTE_WEIGHT);
      double imageWeight =
          resolveRatio("rag.retrieval.image-vector-route-weight", DEFAULT_IMAGE_ROUTE_WEIGHT);
      mergeRoute(merged, textCandidates, textWeight);
      mergeRoute(merged, imageCandidates, imageWeight);
      result = List.copyOf(merged.values());
    }
    return result;
  }

  private void mergeRoute(
      Map<String, RetrievalCandidate> merged,
      List<RetrievalCandidate> candidates,
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

  private SourceReference toSourceReference(VectorSearchHit hit, double relevanceScore) {
    Map<String, Object> metadata = hit.metadata();
    String filename = string(metadata.get("filename"));
    String fileType = string(metadata.get("fileType"));
    if (fileType.isBlank() && filename.contains(".")) {
      fileType = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    return new SourceReference(
        fileType.isBlank() ? "txt" : fileType,
        "hybrid",
        filename,
        hit.documentId(),
        hit.chunkId(),
        nonNegativeInteger(metadata.get("chunkIndex")),
        positiveInteger(metadata.get("pageNo")),
        positiveInteger(metadata.get("rowIndex")),
        hit.text(),
        relevanceScore);
  }

  /**
   * 上下文组装（提案3 任务 1.1）：委托 {@link ContextBuilder} 做邻居增强与超预算压缩； 未装配（兼容构造）时保持升级前 top-K 纯拼接，编号与 sources
   * 下标的映射不变。
   */
  private String buildContext(List<RetrievalCandidate> candidates) {
    return contextBuilder == null
        ? ContextBuilder.plainNumbered(candidates)
        : contextBuilder.build(candidates);
  }

  private int resolveTopK(Integer requested) {
    int result;
    if (requested != null && requested > 0) {
      result = requested;
    } else {
      DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
      Integer configured =
          config == null ? null : config.get("rag.retrieval.topK", Integer.class, DEFAULT_TOP_K);
      result = configured != null && configured > 0 ? configured : DEFAULT_TOP_K;
    }
    return result;
  }

  private double resolveMinScore() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured =
        config == null
            ? null
            : config.get("rag.retrieval.minScore", Double.class, DEFAULT_MIN_SCORE);
    return configured == null || configured < 0 || configured > 1 ? DEFAULT_MIN_SCORE : configured;
  }

  private int resolveCandidateMultiplier() {
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

  private double resolveRatio(String key, double defaultValue) {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Double configured = config == null ? null : config.get(key, Double.class, defaultValue);
    return configured == null || configured < 0 || configured > 1 ? defaultValue : configured;
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private Integer positiveInteger(Object value) {
    Integer parsed = integer(value);
    return parsed != null && parsed > 0 ? parsed : null;
  }

  private Integer nonNegativeInteger(Object value) {
    Integer parsed = integer(value);
    return parsed != null && parsed >= 0 ? parsed : null;
  }

  private Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }
}
