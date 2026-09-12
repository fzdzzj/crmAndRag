package com.slz.crm.knowledge.retrieval;

import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库生产检索实现：授权 → 改写 → 双路召回（向量+稀疏）→ RRF 融合 → 重排 → 路由融合 → topK。
 *
 * <p>方案16补全 + 方案08升级（complete-hybrid-retrieval-and-rerank）：在纯向量召回之外接入
 * 语料级稀疏召回路（{@link SparseRecallService}），两路 RRF 融合（weighted 模式回退升级前行为）；
 * 重排器可插拔（默认 = 向量/BM25 归一化加权，可选 LLM 重排）。
 * 图文路由融合与 topK 流程保持不变，仅插入新环节。</p>
 */
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalPort {
    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_CANDIDATE_MULTIPLIER = 4;
    private static final double DEFAULT_MIN_SCORE = 0.20;
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

    @Autowired
    public KnowledgeRetrievalServiceImpl(KnowledgeBaseAuthorizationService authorizationService,
                                         EmbeddingService embeddingService,
                                         CrmVectorStore vectorStore,
                                         RetrievalQueryRewriteService queryRewriteService,
                                         ObjectProvider<DynamicConfigService> dynamicConfigProvider,
                                         SparseRecallService sparseRecallService,
                                         RrfFusion rrfFusion,
                                         DefaultWeightedReranker defaultReranker,
                                         LlmReranker llmReranker) {
        this.authorizationService = authorizationService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.queryRewriteService = queryRewriteService;
        this.dynamicConfigProvider = dynamicConfigProvider;
        this.sparseRecallService = sparseRecallService;
        this.rrfFusion = rrfFusion;
        this.defaultReranker = defaultReranker;
        this.llmReranker = llmReranker;
    }

    /** 兼容构造：不接稀疏路/RRF/LLM 重排（纯向量单路 + 路内加权，升级前行为）。 */
    public KnowledgeRetrievalServiceImpl(KnowledgeBaseAuthorizationService authorizationService,
                                         EmbeddingService embeddingService,
                                         CrmVectorStore vectorStore,
                                         RetrievalQueryRewriteService queryRewriteService,
                                         Bm25Scorer bm25Scorer,
                                         ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
        this(authorizationService, embeddingService, vectorStore, queryRewriteService,
                dynamicConfigProvider, null, null,
                new DefaultWeightedReranker(bm25Scorer, dynamicConfigProvider), null);
    }

    @Override
    public KnowledgeRetrievalPort.RetrievalResult retrieve(KnowledgeRetrievalPort.RetrievalQuery query) {
        UserContext user = UserContextHolder.require();
        if (query.userId() != null && !Objects.equals(query.userId(), user.userId())) {
            throw new SecurityException("检索用户与当前登录用户不一致");
        }
        if (query.query() == null || query.query().isBlank()) {
            throw new IllegalArgumentException("检索 query 不能为空");
        }

        List<Long> knowledgeBaseIds = authorizationService.authorizedKnowledgeBaseIds(user, query.kbScope());
        if (knowledgeBaseIds.isEmpty()) {
            return KnowledgeRetrievalPort.RetrievalResult.empty();
        }

        String retrievalQuery = queryRewriteService.rewrite(query.query());
        // D17 收尾（complete-hybrid-retrieval-and-rerank 任务 2.1）：意图类目两路同语义过滤，空 = 不过滤
        String category = normalizeCategory(query.intentCategory());
        int topK = resolveTopK(query.topK());
        double minScore = resolveMinScore();
        int candidateLimit = topK * resolveCandidateMultiplier();
        boolean hasImageVector = query.imageVector() != null && query.imageVector().length > 0;

        float[] textVector = embeddingService.embed(retrievalQuery);
        Reranker reranker = activeReranker();
        List<RetrievalCandidate> textCandidates = reranker.rerank(retrievalQuery, recallTextRoute(
                retrievalQuery, textVector, knowledgeBaseIds, category, candidateLimit, minScore));
        List<RetrievalCandidate> imageCandidates = hasImageVector ? reranker.rerank(retrievalQuery,
                recall(retrievalQuery, query.imageVector(), knowledgeBaseIds, category,
                        candidateLimit, minScore)) : List.of();
        List<RetrievalCandidate> candidates = fuseRoutes(textCandidates, imageCandidates).stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed())
                .limit(topK)
                .toList();
        if (candidates.isEmpty()) {
            return KnowledgeRetrievalPort.RetrievalResult.empty();
        }

        List<SourceReference> sources = candidates.stream()
                .map(candidate -> toSourceReference(candidate.hit(), candidate.rerankScore())).toList();
        return new KnowledgeRetrievalPort.RetrievalResult(buildContext(candidates), sources, candidates.size());
    }

    /**
     * 文本路召回：向量路（按授权 KB 逐库过滤）+ 稀疏路 → 融合（任务 3.1/3.3）。
     * 融合模式 {@code rag.retrieval.fusion.mode = rrf | weighted}（默认 rrf）；
     * weighted = 升级前行为，稀疏路完全不参与。
     */
    private List<RetrievalCandidate> recallTextRoute(String query,
                                                     float[] queryVector,
                                                     List<Long> knowledgeBaseIds,
                                                     String category,
                                                     int candidateLimit,
                                                     double minScore) {
        List<RetrievalCandidate> vectorCandidates =
                recall(query, queryVector, knowledgeBaseIds, category, candidateLimit, minScore);
        if (sparseRecallService == null || rrfFusion == null || !useRrfFusion()) {
            return vectorCandidates;
        }
        List<RetrievalCandidate> sparseCandidates =
                sparseRecallService.recall(query, knowledgeBaseIds, category, candidateLimit);
        if (sparseCandidates.isEmpty()) {
            return vectorCandidates;
        }
        return rrfFusion.fuse(vectorCandidates, sparseCandidates, resolveRrfK());
    }

    /** 融合模式开关：仅显式配置 weighted 时回退升级前行为，其余（含未配置）走 RRF 默认。 */
    private boolean useRrfFusion() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        String mode = config == null ? null : config.get(FUSION_MODE_KEY, String.class, "rrf");
        return mode == null || !FUSION_MODE_WEIGHTED.equalsIgnoreCase(mode.strip());
    }

    private int resolveRrfK() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get(FUSION_RRF_K_KEY, Integer.class, DEFAULT_RRF_K);
        return configured == null || configured < 1 ? DEFAULT_RRF_K : configured;
    }

    /** 每个知识库单独过滤，保证授权集合不能被伪造 metadata 放大；类目与授权叠加（只窄化不放大）。 */
    private List<RetrievalCandidate> recall(String query,
                                            float[] queryVector,
                                            List<Long> knowledgeBaseIds,
                                            String category,
                                            int candidateLimit,
                                            double minScore) {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            Map<String, Object> filter = category == null
                    ? Map.of("knowledgeBaseId", String.valueOf(knowledgeBaseId))
                    : Map.of("knowledgeBaseId", String.valueOf(knowledgeBaseId), "category", category);
            vectorStore.search(new VectorSearchRequest(queryVector, candidateLimit, minScore, filter))
                    .stream()
                    .filter(hit -> hit.text() != null && !hit.text().isBlank())
                    .map(hit -> new RetrievalCandidate(hit, hit.score()))
                    .forEach(candidates::add);
        }
        return candidates;
    }

    /** 类目归一：null/空白 = 不过滤，其余去首尾空白后参与两路过滤。 */
    private String normalizeCategory(String intentCategory) {
        return intentCategory == null || intentCategory.isBlank() ? null : intentCategory.strip();
    }

    /**
     * 重排器选择（任务 4.2/4.3）：{@code rag.retrieval.rerank.mode = default | llm}（默认 default）；
     * llm 未装配（兼容构造）时同样落回默认链。
     */
    private Reranker activeReranker() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        String mode = config == null ? null : config.get(RERANK_MODE_KEY, String.class, "default");
        if (mode != null && RERANK_MODE_LLM.equalsIgnoreCase(mode.strip()) && llmReranker != null) {
            return llmReranker;
        }
        return defaultReranker;
    }

    /** 图文路由融合：文本 0.7 + 图片 0.3，同切片两路命中则累加。 */
    private List<RetrievalCandidate> fuseRoutes(List<RetrievalCandidate> textCandidates,
                                                List<RetrievalCandidate> imageCandidates) {
        if (imageCandidates.isEmpty()) {
            return textCandidates;
        }
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        double textWeight = resolveRatio("rag.retrieval.image-text-route-weight", DEFAULT_TEXT_ROUTE_WEIGHT);
        double imageWeight = resolveRatio("rag.retrieval.image-vector-route-weight", DEFAULT_IMAGE_ROUTE_WEIGHT);
        mergeRoute(merged, textCandidates, textWeight);
        mergeRoute(merged, imageCandidates, imageWeight);
        return List.copyOf(merged.values());
    }

    private void mergeRoute(Map<String, RetrievalCandidate> merged,
                            List<RetrievalCandidate> candidates,
                            double routeWeight) {
        for (RetrievalCandidate candidate : candidates) {
            RetrievalCandidate existing = merged.get(candidate.fusionKey());
            if (existing == null) {
                merged.put(candidate.fusionKey(),
                        new RetrievalCandidate(candidate.hit(), candidate.rerankScore() * routeWeight));
            } else {
                merged.put(candidate.fusionKey(), new RetrievalCandidate(
                        existing.hit(),
                        existing.rerankScore() + candidate.rerankScore() * routeWeight));
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

    private String buildContext(List<RetrievalCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append('[').append(index + 1).append("] ")
                    .append(candidates.get(index).hit().text().strip());
        }
        return builder.toString();
    }

    private int resolveTopK(Integer requested) {
        if (requested != null && requested > 0) {
            return requested;
        }
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get("rag.retrieval.topK", Integer.class, DEFAULT_TOP_K);
        return configured != null && configured > 0 ? configured : DEFAULT_TOP_K;
    }

    private double resolveMinScore() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Double configured = config == null ? null
                : config.get("rag.retrieval.minScore", Double.class, DEFAULT_MIN_SCORE);
        return configured == null || configured < 0 || configured > 1 ? DEFAULT_MIN_SCORE : configured;
    }

    private int resolveCandidateMultiplier() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get("rag.retrieval.rerank.candidate-multiplier", Integer.class,
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
