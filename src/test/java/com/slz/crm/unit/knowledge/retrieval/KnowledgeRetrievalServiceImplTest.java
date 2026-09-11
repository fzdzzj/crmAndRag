package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 检索管线测试：授权、D15 锚点与图文双路融合。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceImplTest {
    @Mock
    private KnowledgeBaseAuthorizationService authorizationService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private com.slz.crm.platform.contract.CrmVectorStore vectorStore;

    @Mock
    private ModelProvider modelProvider;

    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    @Mock
    private DynamicConfigService dynamicConfigService;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void sourceReferenceShouldKeepZeroChunkIndex() {
        UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
        UserContextHolder.set(user);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
                authorizationService,
                embeddingService,
                vectorStore,
                new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
                new Bm25Scorer(),
                dynamicConfigProvider);
        when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1"))).thenReturn(List.of(1L));
        when(embeddingService.embed("销售流程")).thenReturn(new float[]{1f, 0f});
        when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of(new VectorSearchHit(
                "10", "doc-1", 0.8d, "销售流程片段", Map.of("chunkIndex", 0, "filename", "sales.txt"))));
        lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);

        KnowledgeRetrievalPort.RetrievalResult result = service.retrieve(new KnowledgeRetrievalPort.RetrievalQuery(
                "销售流程", 1L, List.of("1"), 5, null, null));

        assertEquals(1, result.hitCount());
        SourceReference source = result.sources().getFirst();
        assertEquals(0, source.chunkIndex());
        assertEquals("10", source.chunkId());
        assertEquals("hybrid", source.route());
    }

    @Test
    void imageRouteShouldUseThirtyPercentWeightAndFuseSameChunk() {
        UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
        UserContextHolder.set(user);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
                authorizationService,
                embeddingService,
                vectorStore,
                new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
                new Bm25Scorer(),
                dynamicConfigProvider);
        float[] textVector = {1f, 0f};
        float[] imageVector = {0f, 1f};
        when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1"))).thenReturn(List.of(1L));
        when(embeddingService.embed("回款合同")).thenReturn(textVector);
        when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
        when(dynamicConfigService.get(eq("rag.retrieval.query-rewrite.enabled"), eq(Boolean.class), eq(true)))
                .thenReturn(false);
        when(vectorStore.search(any(VectorSearchRequest.class))).thenAnswer(invocation -> {
            VectorSearchRequest request = invocation.getArgument(0);
            if (Arrays.equals(request.queryVector(), textVector)) {
                return List.of(
                        hit("10", "销售合同流程", 0.9d),
                        hit("11", "回款计划", 0.7d));
            }
            if (Arrays.equals(request.queryVector(), imageVector)) {
                return List.of(hit("11", "回款计划", 0.9d));
            }
            return List.of();
        });

        KnowledgeRetrievalPort.RetrievalResult result = service.retrieve(new KnowledgeRetrievalPort.RetrievalQuery(
                "回款合同", 1L, List.of("1"), 2, imageVector, null));

        assertEquals(2, result.hitCount());
        assertEquals("11", result.sources().getFirst().chunkId());
        assertEquals("10", result.sources().getLast().chunkId());
    }

    private VectorSearchHit hit(String chunkId, String text, double score) {
        return new VectorSearchHit(chunkId, "doc-1", score, text,
                Map.of("chunkIndex", Integer.parseInt(chunkId), "filename", "sales.txt"));
    }
}
