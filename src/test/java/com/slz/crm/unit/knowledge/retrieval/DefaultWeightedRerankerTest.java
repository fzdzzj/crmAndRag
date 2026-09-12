package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 * 默认重排器行为等价单测（complete-hybrid-retrieval-and-rerank 任务 4.1）：
 * 与升级前 {@code KnowledgeRetrievalServiceImpl} 的路内 rerank 算法（测试内参考实现）逐分对照——
 * 同输入必须同分数、同排序。
 */
@ExtendWith(MockitoExtension.class)
class DefaultWeightedRerankerTest {
    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    private final Bm25Scorer bm25Scorer = new Bm25Scorer();

    /** 同输入同排序同分数：新实现 vs 升级前参考实现（含中文/英文/同分/零分边界）。 */
    @Test
    void rerankMustBeBehaviorallyEquivalentToPreUpgradeInlineLogic() {
        List<RetrievalCandidate> candidates = List.of(
                candidate("c1", "XR-500 设备售后对接人是王建国", 0.82),
                candidate("c2", "销售流程与回款计划", 0.91),
                candidate("c3", "XR-500 备件库位 B-12", 0.82),
                candidate("c4", "完全无关的水文段落没有任何重合词", 0.10),
                candidate("c5", "回款", 0.65));
        DefaultWeightedReranker reranker = new DefaultWeightedReranker(bm25Scorer, dynamicConfigProvider);

        List<RetrievalCandidate> actual = reranker.rerank("XR-500 回款", candidates);
        List<RetrievalCandidate> expected = referenceRerank("XR-500 回款", candidates);

        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).hit().chunkId(), actual.get(index).hit().chunkId(),
                    "第 " + index + " 位切片应与升级前一致");
            assertEquals(expected.get(index).rerankScore(), actual.get(index).rerankScore(), 1e-12,
                    "第 " + index + " 位分数应与升级前一致");
        }
    }

    /** 空候选透传空列表。 */
    @Test
    void emptyCandidatesShouldReturnEmpty() {
        assertEquals(0, new DefaultWeightedReranker(bm25Scorer, dynamicConfigProvider)
                .rerank("q", List.of()).size());
    }

    /** 升级前（提案2之前）KnowledgeRetrievalServiceImpl.rerank 的原实现，作为等价性参考。 */
    private List<RetrievalCandidate> referenceRerank(String query, List<RetrievalCandidate> candidates) {
        List<Double> vectorScores = candidates.stream().map(candidate -> candidate.hit().score()).toList();
        List<Double> bm25Scores = bm25Scorer.score(query,
                candidates.stream().map(candidate -> candidate.hit().text()).toList());
        List<Double> normalizedVectorScores = normalize(vectorScores);
        List<Double> normalizedBm25Scores = normalize(bm25Scores);
        double vectorWeight = 0.60;
        double bm25Weight = 0.40;
        List<RetrievalCandidate> reranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            double score = normalizedVectorScores.get(index) * vectorWeight
                    + normalizedBm25Scores.get(index) * bm25Weight;
            reranked.add(new RetrievalCandidate(candidates.get(index).hit(), score));
        }
        return reranked;
    }

    private List<Double> normalize(List<Double> scores) {
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

    private RetrievalCandidate candidate(String chunkId, String text, double vectorScore) {
        return new RetrievalCandidate(new VectorSearchHit(chunkId, "doc-1", vectorScore, text, Map.of()),
                vectorScore);
    }
}
