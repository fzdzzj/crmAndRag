package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合检索管线单测（complete-hybrid-retrieval-and-rerank 任务 1.4/1.5，管线级）：
 * 稀疏路候选能进入最终 top-K、授权 KB 集合原样下传稀疏路。
 * DB 层 MATCH...AGAINST 与授权 JOIN 的真库语义由 {@code SparseRecallServiceIT} 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class SparseRecallPipelineTest {
    private static final long KB_ID = 1L;

    @Mock
    private KnowledgeBaseAuthorizationService authorizationService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ModelProvider modelProvider;

    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    @Mock
    private SparseRecallService sparseRecallService;

    private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    /** 任务 1.4：向量路漏召（查询向量与切片正交，余弦 0 低于 minScore）+ 词法命中 → 稀疏路补位进 top-K。 */
    @Test
    void sparseCandidateShouldReachFinalTopKWhenVectorPathMisses() {
        UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
        UserContextHolder.set(user);
        // 切片向量 [0,1] 与查询向量 [1,0] 正交 → 余弦 0 < 0.20，向量路必然漏召
        vectorStore.upsert(new VectorRecord("point-1", "doc-9", "55", 0,
                "AX-9 备件统一放在 C-07 库位", new float[]{0f, 1f}, metadata("1")));
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
                authorizationService, embeddingService, vectorStore,
                new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
                new Bm25Scorer(), dynamicConfigProvider, sparseRecallService);
        when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1"))).thenReturn(List.of(KB_ID));
        when(embeddingService.embed("AX-9 备件在哪个库位")).thenReturn(new float[]{1f, 0f});
        when(sparseRecallService.recall(eq("AX-9 备件在哪个库位"), eq(List.of(KB_ID)), eq(null), anyInt()))
                .thenReturn(List.of(new RetrievalCandidate(
                        new com.slz.crm.platform.contract.VectorSearchHit("55", "doc-9", 3.2d,
                                "AX-9 备件统一放在 C-07 库位", metadata("1")), 3.2d)));

        KnowledgeRetrievalPort.RetrievalResult result = service.retrieve(new KnowledgeRetrievalPort.RetrievalQuery(
                "AX-9 备件在哪个库位", 1L, List.of("1"), 5, null, null));

        assertEquals(1, result.hitCount());
        assertEquals("55", result.sources().getFirst().chunkId());
        assertTrue(result.context().contains("C-07"), "注入上下文应包含稀疏路命中切片原文");
    }

    /** 任务 1.5（管线侧）：稀疏路收到的必须是授权过滤后的 KB 集合，而非原始 kbScope。 */
    @Test
    void sparseRecallMustReceiveAuthorizedKnowledgeBaseIdsOnly() {
        UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
        UserContextHolder.set(user);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
                authorizationService, embeddingService, vectorStore,
                new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
                new Bm25Scorer(), dynamicConfigProvider, sparseRecallService);
        // 请求了 KB 1/2/3，授权只放行 KB 2
        when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1", "2", "3")))
                .thenReturn(List.of(2L));
        when(embeddingService.embed("备件")).thenReturn(new float[]{1f, 0f});
        when(sparseRecallService.recall(any(), anyList(), any(), anyInt())).thenReturn(List.of());

        service.retrieve(new KnowledgeRetrievalPort.RetrievalQuery("备件", 1L, List.of("1", "2", "3"), 5, null, null));

        verify(sparseRecallService).recall(eq("备件"), eq(List.of(2L)), eq(null), anyInt());
    }

    private Map<String, Object> metadata(String kbId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledgeBaseId", kbId);
        metadata.put("category", "");
        metadata.put("filename", "a.md");
        metadata.put("pageNo", 0L);
        metadata.put("rowIndex", 0L);
        return metadata;
    }
}
