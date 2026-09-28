package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.LlmReranker;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 重排模式接线单测（complete-hybrid-retrieval-and-rerank 任务 4.3）： rerank.mode=llm 时管线走 LLM
 * 重排；默认（default）时完全不触模型，走默认加权链。
 */
@ExtendWith(MockitoExtension.class)
class RerankModePipelineTest {
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

  /** mode=llm：LLM listwise 输出 [2,1] → 最终 topK 顺序 c2、c1，且确实调用了模型。 */
  @Test
  void llmModeShouldReorderFinalTopKByLlm() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-1", "回款计划分三期执行", new float[] {1f, 0f});
    upsert("chunk-2", "回款台账与对账口径", new float[] {0.6f, 0.8f});
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);
    when(dynamicConfigService.get(eq("rag.retrieval.rerank.mode"), eq(String.class), eq("default")))
        .thenReturn("llm");
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("[2,1]", "qwen-max", 100L, 10L, 110L));

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, null));

    assertEquals(2, result.hitCount());
    assertEquals("chunk-2", result.sources().getFirst().chunkId());
    assertEquals("chunk-1", result.sources().getLast().chunkId());
    verify(modelProvider).chat(any(Prompt.class), any(ModelCallOptions.class));
  }

  /** 默认模式：不触碰模型（改写显式关闭后 provider 零调用），走默认加权链。 */
  @Test
  void defaultModeMustNotTouchModelProvider() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    upsert("chunk-1", "回款计划分三期执行", new float[] {1f, 0f});
    KnowledgeRetrievalServiceImpl service = service();
    stubCommon(user);

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUESTION, 1L, List.of("1"), 5, null, null));

    assertEquals(1, result.hitCount());
    assertEquals("chunk-1", result.sources().getFirst().chunkId());
    verify(modelProvider, never()).chat(any(Prompt.class), any(ModelCallOptions.class));
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
        new LlmReranker(
            modelProvider,
            new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider),
            dynamicConfigProvider,
            AsyncExecutorTestSupport.asyncPerTaskExecutor()));
  }

  private void stubCommon(UserContext user) {
    lenient()
        .when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(KB_ID));
    lenient().when(embeddingService.embed(QUESTION)).thenReturn(new float[] {1f, 0f});
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    lenient()
        .when(dynamicConfigService.get(any(String.class), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    // 改写开关显式关闭：本测试只考察重排模式，且 default 模式要求 provider 零调用
    lenient()
        .when(
            dynamicConfigService.get(
                eq("rag.retrieval.query-rewrite.enabled"), eq(Boolean.class), eq(true)))
        .thenReturn(false);
  }

  private void upsert(String chunkId, String text, float[] vector) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", String.valueOf(KB_ID));
    metadata.put("category", "");
    metadata.put("filename", "a.md");
    metadata.put("pageNo", 0L);
    metadata.put("rowIndex", 0L);
    vectorStore.upsert(
        new VectorRecord("point-" + chunkId, "doc-" + chunkId, chunkId, 0, text, vector, metadata));
  }
}
