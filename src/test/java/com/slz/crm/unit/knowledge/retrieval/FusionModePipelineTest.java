package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * 融合模式开关单测（complete-hybrid-retrieval-and-rerank 任务 3.3）： fusion.mode=weighted 时行为与升级前等价（稀疏路完全不参与）；
 * 默认（未配置/配置缺失）时稀疏路参与 RRF 融合。
 */
@ExtendWith(MockitoExtension.class)
class FusionModePipelineTest {
  private static final long KB_ID = 1L;
  private static final String QUESTION = "回款计划";

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  @Mock private SparseRecallService sparseRecallService;

  private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** weighted 回退开关：稀疏路零参与，结果与升级前纯向量行为一致。 */
  @Test
  void weightedModeMustBypassSparseRouteEntirely() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-1", "回款计划分三期");
    upsert("chunk-2", "开票流程与要求");
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    // topK/minScore/candidate-multiplier 未命中桩 → 默认值
    when(dynamicConfigService.get(eq("rag.retrieval.fusion.mode"), eq(String.class), eq("rrf")))
        .thenReturn("weighted");

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, null));

    assertEquals(2, result.hitCount());
    assertEquals("chunk-1", result.sources().getFirst().chunkId());
    assertEquals("chunk-2", result.sources().getLast().chunkId());
    verifyNoInteractions(sparseRecallService);
  }

  /** 默认模式（配置缺省=rrf，provider 存在）：稀疏路参与 RRF 融合。 */
  @Test
  void defaultModeShouldFuseSparseRouteViaRrf() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(sparseRecallService.recall(eq(QUESTION), eq(List.of(KB_ID)), eq(null), anyInt()))
        .thenReturn(List.of());

    service.retrieve(
        new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, null));

    verify(sparseRecallService).recall(eq(QUESTION), eq(List.of(KB_ID)), eq(null), anyInt());
  }

  /** 配置不可用（无 DynamicConfig bean）也走默认 rrf：稀疏路可用。 */
  @Test
  void missingDynamicConfigShouldDefaultToRrf() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(sparseRecallService.recall(any(), anyList(), any(), anyInt())).thenReturn(List.of());

    service.retrieve(
        new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, null));

    verify(sparseRecallService).recall(eq(QUESTION), eq(List.of(KB_ID)), eq(null), anyInt());
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
    lenient()
        .when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(KB_ID));
    lenient().when(embeddingService.embed(QUESTION)).thenReturn(new float[] {1f, 0f});
    // 通用兜底：未显式桩定的键按真实 DynamicConfigService 语义返回默认值（避免 null 拆箱/严格桩冲突）
    lenient()
        .when(dynamicConfigService.get(any(String.class), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
  }

  private void upsert(String chunkId, String text) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", String.valueOf(KB_ID));
    metadata.put("category", "");
    metadata.put("filename", "a.md");
    metadata.put("pageNo", 0L);
    metadata.put("rowIndex", 0L);
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
