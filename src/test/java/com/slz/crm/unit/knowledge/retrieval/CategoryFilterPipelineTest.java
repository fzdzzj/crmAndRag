package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 类目过滤单测（complete-hybrid-retrieval-and-rerank 任务 2.2，D17 收尾）： 类目命中窄化生效、空类目不过滤、类目只能窄化不能放大结果。 稀疏路 SQL
 * 侧类目语义由 {@code SparseRecallServiceIT} 真库覆盖。
 */
@ExtendWith(MockitoExtension.class)
class CategoryFilterPipelineTest {
  private static final long KB_ID = 1L;
  private static final String QUESTION = "保修政策";

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private SparseRecallService sparseRecallService;

  private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** 类目命中：向量路与稀疏路都收到类目；结果只含该类目切片。 */
  @Test
  void categoryShouldNarrowBothRoutesWhenMatched() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-a", "整体机保修两年", "warranty");
    upsert("chunk-b", "发票开具流程说明", "invoice");
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(sparseRecallService.recall(eq(QUESTION), eq(List.of(KB_ID)), eq("warranty"), anyInt()))
        .thenReturn(List.of());

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                QUESTION, 1L, List.of("1"), 5, null, "warranty"));

    assertEquals(1, result.hitCount());
    assertEquals("chunk-a", result.sources().getFirst().chunkId());
    verify(sparseRecallService).recall(eq(QUESTION), eq(List.of(KB_ID)), eq("warranty"), anyInt());
  }

  /** 空类目：两路均不过滤，全类目候选可见。 */
  @Test
  void blankCategoryShouldNotFilter() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-a", "整体机保修两年", "warranty");
    upsert("chunk-b", "发票开具流程说明", "invoice");
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(sparseRecallService.recall(eq(QUESTION), eq(List.of(KB_ID)), eq(null), anyInt()))
        .thenReturn(List.of());

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, "  "));

    assertEquals(2, result.hitCount(), "空类目不得过滤");
    verify(sparseRecallService).recall(eq(QUESTION), eq(List.of(KB_ID)), eq(null), anyInt());
  }

  /** 越权类目：类目只与授权叠加窄化，无匹配 = 空结果而非放大。 */
  @Test
  void unmatchedCategoryMustNotExpandResults() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-a", "整体机保修两年", "warranty");
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(sparseRecallService.recall(eq(QUESTION), eq(List.of(KB_ID)), eq("不存在的类目"), anyInt()))
        .thenReturn(List.of());

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                QUESTION, 1L, List.of("1"), 5, null, "不存在的类目"));

    assertEquals(0, result.hitCount(), "类目不匹配应返回空，而不是回退为全量");
    assertTrue(result.sources().isEmpty());
  }

  private KnowledgeRetrievalServiceImpl service() {
    return new KnowledgeRetrievalServiceImpl(
        authorizationService,
        embeddingService,
        vectorStore,
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
        dynamicConfigProvider,
        sparseRecallService,
        new RrfFusion(),
        new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider),
        null);
  }

  private void stubCommon(UserContext user) {
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(KB_ID));
    when(embeddingService.embed(QUESTION)).thenReturn(new float[] {1f, 0f});
  }

  private void upsert(String chunkId, String text, String category) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", String.valueOf(KB_ID));
    metadata.put("category", category);
    metadata.put("filename", "a.md");
    metadata.put("pageNo", 0L);
    metadata.put("rowIndex", 0L);
    // 同一方向向量，保证无类目过滤时两片都过 minScore
    vectorStore.upsert(
        new VectorRecord(
            "point-" + chunkId,
            "doc-" + chunkId,
            chunkId,
            0,
            text,
            new float[] {1f, 0f},
            metadata));
  }
}
