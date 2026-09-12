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
 * 知识库生产检索实现：授权 → 改写 → 双路召回（向量+稀疏）→ 融合 → rerank → 路由融合。
 *
 * <p>方案16补全（complete-hybrid-retrieval-and-rerank）：在纯向量召回之外接入语料级稀疏
 * 召回路（{@link SparseRecallService}，词法精确命中向量漏召的块也能入池），
 * 两路候选并集合并后进入既有「向量/BM25 归一化加权」路内精排；图文路由融合与 topK 流程不变。</p>
 */
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalPort {
    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_CANDIDATE_MULTIPLIER = 4;
    private static final double DEFAULT_MIN_SCORE = 0.20;
    private static final double DEFAULT_TEXT_ROUTE_WEIGHT = 0.70;
    private static final double DEFAULT_IMAGE_ROUTE_WEIGHT = 0.30;
    private static final double DEFAULT_VECTOR_WEIGHT = 0.60;
    private static final double DEFAULT_BM25_WEIGHT = 0.40;

    private final KnowledgeBaseAuthorizationService authorizationService;
    private final EmbeddingService embeddingService;
    private final CrmVectorStore vectorStore;
    private final RetrievalQueryRewriteService queryRewriteService;
    private final Bm25Scorer bm25Scorer;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    /** 稀疏召回路；null = 关闭（回退纯向量单路，兼容既有装配与测试）。 */
    private final SparseRecallService sparseRecallService;

    @Autowired
    public KnowledgeRetrievalServiceImpl(KnowledgeBaseAuthorizationService authorizationService,
                                         EmbeddingService embeddingService,
                                         CrmVectorStore vectorStore,
                                         RetrievalQueryRewriteService queryRewriteService,
                                         Bm25Scorer bm25Scorer,
                                         ObjectProvider<DynamicConfigService> dynamicConfigProvider,
                                         SparseRecallService sparseRecallService) {
        this.authorizationService = authorizationService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.queryRewriteService = queryRewriteService;
        this.bm25Scorer = bm25Scorer;
        this.dynamicConfigProvider = dynamicConfigProvider;
        this.sparseRecallService = sparseRecallService;
    }

    /** 兼容构造：不接稀疏路（纯向量单路，升级前行为）。 */
    public KnowledgeRetrievalServiceImpl(KnowledgeBaseAuthorizationService authorizationService,
                                         EmbeddingService embeddingService,
                                         CrmVectorStore vectorStore,
                                         RetrievalQueryRewriteService queryRewriteService,
                                         Bm25Scorer bm25Scorer,
                                         ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
        this(authorizationService, embeddingService, vectorStore, queryRewriteService,
                bm25Scorer, dynamicConfigProvider, null);
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
        int topK = resolveTopK(query.topK());
        double minScore = resolveMinScore();
        int candidateLimit = topK * resolveCandidateMultiplier();
        boolean hasImageVector = query.imageVector() != null && query.imageVector().length > 0;

        float[] textVector = embeddingService.embed(retrievalQuery);
        List<RetrievalCandidate> textCandidates = rerank(retrievalQuery, recallTextRoute(
                retrievalQuery, textVector, knowledgeBaseIds, candidateLimit, minScore));
        List<RetrievalCandidate> imageCandidates = hasImageVector ? rerank(retrievalQuery,
                recall(retrievalQuery, query.imageVector(), knowledgeBaseIds, candidateLimit, minScore)) : List.of();
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

    /** 文本路召回：向量路（按授权 KB 逐库过滤）+ 稀疏路并集合并；同切片向量路优先。 */
    private List<RetrievalCandidate> recallTextRoute(String query,
                                                     float[] queryVector,
                                                     List<Long> knowledgeBaseIds,
                                                     int candidateLimit,
                                                     double minScore) {
        List<RetrievalCandidate> vectorCandidates =
                recall(query, queryVector, knowledgeBaseIds, candidateLimit, minScore);
        if (sparseRecallService == null) {
            return vectorCandidates;
        }
        List<RetrievalCandidate> sparseCandidates =
                sparseRecallService.recall(query, knowledgeBaseIds, null, candidateLimit);
        if (sparseCandidates.isEmpty()) {
            return vectorCandidates;
        }
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : vectorCandidates) {
            merged.put(candidate.fusionKey(), candidate);
        }
        for (RetrievalCandidate candidate : sparseCandidates) {
            merged.putIfAbsent(candidate.fusionKey(), candidate);
        }
        return List.copyOf(merged.values());
    }

    /** 每个知识库单独过滤，保证授权集合不能被伪造 metadata 放大。 */
    private List<RetrievalCandidate> recall(String query,
                                            float[] queryVector,
                                            List<Long> knowledgeBaseIds,
                                            int candidateLimit,
                                            double minScore) {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            Map<String, Object> filter = Map.of("knowledgeBaseId", String.valueOf(knowledgeBaseId));
            vectorStore.search(new VectorSearchRequest(queryVector, candidateLimit, minScore, filter))
                    .stream()
                    .filter(hit -> hit.text() != null && !hit.text().isBlank())
                    .map(hit -> new RetrievalCandidate(hit, hit.score()))
                    .forEach(candidates::add);
        }
        return candidates;
    }

    /** 路内 rerank：向量分与 BM25 分都先归一化，再加权融合。 */
    private List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Double> vectorScores = candidates.stream().map(candidate -> candidate.hit().score()).toList();
        List<Double> bm25Scores = bm25Scorer.score(query,
                candidates.stream().map(candidate -> candidate.hit().text()).toList());
        List<Double> normalizedVectorScores = normalizeScores(vectorScores);
        List<Double> normalizedBm25Scores = normalizeScores(bm25Scores);

        double vectorWeight = resolveRatio("rag.retrieval.rerank.vector-weight", DEFAULT_VECTOR_WEIGHT);
        double bm25Weight = resolveRatio("rag.retrieval.rerank.bm25-weight", DEFAULT_BM25_WEIGHT);
        List<RetrievalCandidate> reranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            double score = normalizedVectorScores.get(index) * vectorWeight
                    + normalizedBm25Scores.get(index) * bm25Weight;
            reranked.add(new RetrievalCandidate(candidates.get(index).hit(), score));
        }
        return reranked;
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
