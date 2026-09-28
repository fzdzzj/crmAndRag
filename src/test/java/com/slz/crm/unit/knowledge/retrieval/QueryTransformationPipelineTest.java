package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.HydeQueryExpander;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.MultiQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 查询侧增强管线测试（enhance-query-transformation 任务 1.2–1.4、2.2–2.3）。
 *
 * <p>覆盖：多查询 N 路融合的召回扩面（手算融合序在 RrfFusionTest）、单路失败降级为已完成路融合、 扩展失败回退单查询、关闭态与提案 4 完成态行为一致（嵌入仅 1 次）；
 * HyDE 假设答案只用于检索向量——绝不进入上下文与引用（隔离硬约束）、失败回退原查询路。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryTransformationPipelineTest {
  private static final UserContext USER = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");

  @Mock private KnowledgeBaseAuthorizationService authorizationService;
  @Mock private EmbeddingService embeddingService;
  @Mock private com.slz.crm.platform.contract.CrmVectorStore vectorStore;
  @Mock private ModelProvider modelProvider;
  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  @Mock private DynamicConfigService dynamicConfigService;

  private final Executor directExecutor = Runnable::run;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  private void stubConfig(String key, Object value) {
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    lenient().when(dynamicConfigService.get(eq(key), any(), any())).thenReturn(value);
  }

  private void stubCommon() {
    lenient()
        .when(authorizationService.authorizedKnowledgeBaseIds(USER, List.of("1")))
        .thenReturn(List.of(1L));
    UserContextHolder.set(USER);
    // 单轮改写关闭，隔离提案5 行为（改写器自身开关在提案2 已测）
    stubConfig("rag.retrieval.query-rewrite.enabled", false);
  }

  private KnowledgeRetrievalServiceImpl service() {
    RrfFusion rrfFusion = new RrfFusion();
    DefaultWeightedReranker reranker =
        new DefaultWeightedReranker(
            new com.slz.crm.knowledge.retrieval.Bm25Scorer(), dynamicConfigProvider);
    return new KnowledgeRetrievalServiceImpl(
        authorizationService,
        embeddingService,
        vectorStore,
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
        dynamicConfigProvider,
        null,
        rrfFusion,
        reranker,
        null,
        null,
        new MultiQueryRewriteService(modelProvider, dynamicConfigProvider),
        new HydeQueryExpander(
            modelProvider, dynamicConfigProvider, AsyncExecutorTestSupport.asyncPerTaskExecutor()),
        directExecutor);
  }

  private KnowledgeRetrievalPort.RetrievalQuery query(String text) {
    return new KnowledgeRetrievalPort.RetrievalQuery(text, 1L, List.of("1"), 5, null, null);
  }

  private void vectorAnswering(float[] vector, String chunkId, String text) {
    lenient()
        .when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenAnswer(
            invocation -> {
              VectorSearchRequest request = invocation.getArgument(0);
              if (Arrays.equals(request.queryVector(), vector)) {
                return List.of(
                    new VectorSearchHit(
                        chunkId,
                        "doc-1",
                        0.9d,
                        text,
                        Map.of("chunkIndex", 0, "filename", "a.txt", "fileType", "txt")));
              }
              return List.of();
            });
  }

  /** 任务 1.2：变体路召回结果并入融合池——只有变体向量能命中的切片进入最终结果。 */
  @Test
  void multiQueryShouldBroadenRecallWithVariantRoutes() {
    stubCommon();
    stubConfig("rag.query.multi-query.enabled", true);
    stubConfig("rag.query.hyde.enabled", false);
    lenient()
        .when(modelProvider.chat(any(), any()))
        .thenReturn(ModelCallResult.ofText("变体甲\n变体乙", "m", 10L, 20L, 30L));
    lenient().when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    lenient().when(embeddingService.embed("变体甲")).thenReturn(new float[] {0f, 1f});
    lenient().when(embeddingService.embed("变体乙")).thenReturn(new float[] {1f, 1f});
    vectorAnswering(new float[] {1f, 0f}, "1", "原始路命中");
    lenient()
        .when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenAnswer(
            invocation -> {
              VectorSearchRequest request = invocation.getArgument(0);
              if (Arrays.equals(request.queryVector(), new float[] {1f, 0f})) {
                return List.of(hit("1", "原始路命中", 0.9d));
              }
              if (Arrays.equals(request.queryVector(), new float[] {0f, 1f})) {
                return List.of(hit("2", "变体甲路命中", 0.9d));
              }
              if (Arrays.equals(request.queryVector(), new float[] {1f, 1f})) {
                return List.of(hit("3", "变体乙路命中", 0.9d));
              }
              return List.of();
            });

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    assertEquals(3, result.hitCount(), "三个查询路的命中都进入融合池");
    List<String> chunkIds = result.sources().stream().map(s -> s.chunkId()).sorted().toList();
    assertEquals(List.of("1", "2", "3"), chunkIds);
    verify(embeddingService, times(3)).embed(any());
  }

  /** 任务 1.3：单变体路失败 → 已完成路（原始 + 存活变体）照常融合，不向调用方抛错。 */
  @Test
  void failedVariantRouteShouldDegradeToCompletedRoutes() {
    stubCommon();
    stubConfig("rag.query.multi-query.enabled", true);
    stubConfig("rag.query.hyde.enabled", false);
    lenient()
        .when(modelProvider.chat(any(), any()))
        .thenReturn(ModelCallResult.ofText("变体甲\n变体乙", "m", 10L, 20L, 30L));
    when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    when(embeddingService.embed("变体甲")).thenThrow(new IllegalStateException("嵌入限流"));
    lenient().when(embeddingService.embed("变体乙")).thenReturn(new float[] {1f, 1f});
    lenient()
        .when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenAnswer(
            invocation -> {
              VectorSearchRequest request = invocation.getArgument(0);
              if (Arrays.equals(request.queryVector(), new float[] {1f, 0f})) {
                return List.of(hit("1", "原始路命中", 0.9d));
              }
              if (Arrays.equals(request.queryVector(), new float[] {1f, 1f})) {
                return List.of(hit("3", "变体乙路命中", 0.9d));
              }
              return List.of();
            });

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    List<String> chunkIds = result.sources().stream().map(s -> s.chunkId()).sorted().toList();
    assertEquals(List.of("1", "3"), chunkIds, "失败路缺席，已完成路照常融合");
  }

  /** 任务 1.3：扩展失败（模型异常）→ 回退单查询（现行为），嵌入仅 1 次。 */
  @Test
  void failedExpansionShouldFallBackToSingleQuery() {
    stubCommon();
    stubConfig("rag.query.multi-query.enabled", true);
    stubConfig("rag.query.hyde.enabled", false);
    when(modelProvider.chat(any(), any())).thenThrow(new IllegalStateException("模型超载"));
    when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    vectorAnswering(new float[] {1f, 0f}, "1", "原始路命中");

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    assertEquals(1, result.hitCount());
    verify(embeddingService, times(1)).embed(any());
  }

  /** 任务 1.4 关闭态：三开关全关（默认）时与提案 4 完成态行为逐字一致，嵌入仅 1 次。 */
  @Test
  void disabledSwitchesShouldBehaveAsProposalFourCompletionState() {
    stubCommon();
    // 全部开关缺省（config 返回默认 false/true）：multi-query/hyde=false，rrf 默认开启但无稀疏路
    lenient()
        .when(modelProvider.chat(any(), any()))
        .thenThrow(new AssertionError("关闭态不得发起任何 LLM 调用"));
    lenient().when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    vectorAnswering(new float[] {1f, 0f}, "1", "切片一");

    KnowledgeRetrievalServiceImpl full = service();
    KnowledgeRetrievalServiceImpl compat =
        new KnowledgeRetrievalServiceImpl(
            authorizationService,
            embeddingService,
            vectorStore,
            new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
            dynamicConfigProvider,
            null,
            new RrfFusion(),
            new DefaultWeightedReranker(
                new com.slz.crm.knowledge.retrieval.Bm25Scorer(), dynamicConfigProvider),
            null,
            null);

    KnowledgeRetrievalPort.RetrievalResult fullResult = full.retrieve(query("原查询"));
    KnowledgeRetrievalPort.RetrievalResult compatResult = compat.retrieve(query("原查询"));

    assertEquals(compatResult.hitCount(), fullResult.hitCount());
    assertEquals(compatResult.context(), fullResult.context(), "关闭态上下文与提案 4 完成态逐字一致");
    assertEquals(compatResult.sources(), fullResult.sources(), "关闭态引用与提案 4 完成态一致");
    verify(embeddingService, times(2)).embed(any());
  }

  /** 任务 2.1/2.2：HyDE 假设答案只产生检索向量——命中可回，文本绝不进上下文与引用。 */
  @Test
  void hydeHypothesisMustNotLeakIntoContextOrSources() {
    stubCommon();
    stubConfig("rag.query.multi-query.enabled", false);
    stubConfig("rag.query.hyde.enabled", true);
    String marker = "独特标记XYZQ";
    when(modelProvider.chat(any(), any()))
        .thenReturn(ModelCallResult.ofText(marker + " 假设答案正文", "m", 10L, 20L, 30L));
    when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    lenient().when(embeddingService.embed(marker + " 假设答案正文")).thenReturn(new float[] {0f, 1f});
    lenient()
        .when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenAnswer(
            invocation -> {
              VectorSearchRequest request = invocation.getArgument(0);
              if (Arrays.equals(request.queryVector(), new float[] {1f, 0f})) {
                return List.of(hit("1", "原始路命中内容", 0.9d));
              }
              if (Arrays.equals(request.queryVector(), new float[] {0f, 1f})) {
                return List.of(hit("2", "只有假设答案向量才命中的内容", 0.9d));
              }
              return List.of();
            });

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    assertEquals(2, result.hitCount(), "HyDE 路命中参与融合（chunk 2 只有假设向量能到）");
    assertFalse(result.context().contains(marker), "假设答案不得进入生成上下文");
    result
        .sources()
        .forEach(source -> assertFalse(source.excerpt().contains(marker), "假设答案不得进入引用摘录"));
  }

  /** 任务 2.3：HyDE 生成失败 → 回退原查询路，结果与关闭态一致，不向调用方抛错。 */
  @Test
  void hydeFailureShouldFallBackToOriginalRoute() {
    stubCommon();
    stubConfig("rag.query.multi-query.enabled", false);
    stubConfig("rag.query.hyde.enabled", true);
    when(modelProvider.chat(any(), any())).thenThrow(new IllegalStateException("限流"));
    when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    vectorAnswering(new float[] {1f, 0f}, "1", "原始路命中");

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    assertEquals(1, result.hitCount());
    assertTrue(result.sources().getFirst().chunkId().equals("1"));
    verify(embeddingService, times(1)).embed(any());
  }

  /** weighted 融合模式（升级前行为）下多查询/HyDE 路不参与。 */
  @Test
  void weightedFusionModeShouldSkipQueryTransformationRoutes() {
    stubCommon();
    stubConfig("rag.retrieval.fusion.mode", "weighted");
    stubConfig("rag.query.multi-query.enabled", true);
    stubConfig("rag.query.hyde.enabled", true);
    lenient()
        .when(modelProvider.chat(any(), any()))
        .thenThrow(new AssertionError("weighted 模式下不得发起查询侧增强 LLM 调用"));
    lenient().when(embeddingService.embed("原查询")).thenReturn(new float[] {1f, 0f});
    vectorAnswering(new float[] {1f, 0f}, "1", "原始路命中");

    KnowledgeRetrievalPort.RetrievalResult result = service().retrieve(query("原查询"));

    assertEquals(1, result.hitCount());
    verify(embeddingService, times(1)).embed(any());
  }

  private VectorSearchHit hit(String chunkId, String text, double score) {
    return new VectorSearchHit(
        chunkId,
        "doc-1",
        score,
        text,
        Map.of("chunkIndex", 0, "filename", "a.txt", "fileType", "txt"));
  }
}
