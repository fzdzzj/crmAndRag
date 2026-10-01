package com.slz.crm.integration.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.platform.quota.QuotaDecision;
import com.slz.crm.platform.quota.RequestQuotaService;
import com.slz.crm.pojo.dto.KnowledgeAdminRetrievalRequest;
import com.slz.crm.pojo.vo.KnowledgeAdminRetrievalResponse;
import com.slz.crm.server.service.KnowledgeAdminService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 知识库管理端点真外呼试点集成测试（任务卡 P-j，add-knowledge-admin-api 任务组 6）。
 *
 * <p>门控与守卫：
 *
 * <ul>
 *   <li>环境变量 {@code KNOWLEDGE_ADMIN_REAL_PILOT=1} 显式授权门控；未设置优雅跳过；
 *   <li>API Key 从系统环境变量 {@code DASHSCOPE_API_KEY} 或本地 {@code .env} 读取；
 *   <li>严格隔离：严禁设置或依赖 {@code RAG_BENCHMARK_REAL}；
 *   <li>使用内存向量库 {@link InMemoryVectorStore}，用例结束后自动清理，零脏数据残留。
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeAdminRealPilotIT {

  private static final String PILOT_GATE_ENV = "KNOWLEDGE_ADMIN_REAL_PILOT";
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  private static final Long PILOT_KB_ID = 888L;
  private static final String PILOT_DOC_ID = "pilot-doc-knowledge-admin-888";

  private static final String PILOT_MARKDOWN_DOC =
      """
      # CRMRag 智能知识库知识切片与向量检索规范

      本项目规范定义了企业级 CRM 与 RAG 融合平台在知识摄取、切片与多路检索编排中的核心执行标准。
      系统支持固定滑窗、段落感知以及语义切分等多种策略，切片文本在进入向量库前保持原始排版，
      并通过全局块头注入元数据以增强碎片化语义。检索阶段基于多路召回与重排机制，
      实现稀疏全文检索与真实高维向量相似度检索的精准协同，为智能会话与工单流转提供可靠的知识支撑。
      """;

  private ModelProvider modelProvider;
  private EmbeddingService embeddingService;
  private InMemoryVectorStore vectorStore;
  private TestDynamicConfigService dynamicConfig;
  private KnowledgeBaseAuthorizationService authorizationService;
  private RequestQuotaService requestQuotaService;
  private SparseRecallService sparseRecallService;
  private KnowledgeRetrievalServiceImpl retrievalService;
  private KnowledgeAdminService knowledgeAdminService;
  private UserContext testUser;

  private String sampleChunkText;

  @BeforeAll
  void setUpAll() {
    Assumptions.assumeTrue(
        "1".equals(System.getenv(PILOT_GATE_ENV)), "KNOWLEDGE_ADMIN_REAL_PILOT 未置 1，跳过真外呼试点");

    String apiKey = resolveApiKey();
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(), "未配置 DASHSCOPE_API_KEY（env 或 .env），跳过真外呼试点");

    // 1) 构造真实 DashScope 客户端与 ModelProvider（包装代理以透传 DashScopeApi.EmbeddingUsage 的实际 token 消耗）
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    ModelProviderProperties properties = new ModelProviderProperties();
    EmbeddingModel embeddingModel =
        new PilotEmbeddingModel(nativeApi, properties.getEmbeddingModel());

    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);

    modelProvider =
        new ModelProviderImpl(
            provider(nativeChat), provider(embeddingModel), properties, environment);
    embeddingService = new EmbeddingService(modelProvider, properties);

    // 2) 构造内存向量库与可切换动态配置
    vectorStore = new InMemoryVectorStore();
    dynamicConfig = new TestDynamicConfigService();
    dynamicConfig.set("rag.retrieval.admin-vector.enabled", false);
    dynamicConfig.set("rag.retrieval.query-rewrite.enabled", false);

    // 3) 构造授权、配额与稀疏检索协作组件
    authorizationService = mock(KnowledgeBaseAuthorizationService.class);
    when(authorizationService.authorizedKnowledgeBaseIds(any(), any()))
        .thenReturn(List.of(PILOT_KB_ID));
    when(authorizationService.visibleKnowledgeBaseIds(any())).thenReturn(List.of(PILOT_KB_ID));

    requestQuotaService = mock(RequestQuotaService.class);
    when(requestQuotaService.tryAcquire(any(), any()))
        .thenReturn(new QuotaDecision(true, 1, 10, 0, "OK"));

    sparseRecallService = mock(SparseRecallService.class);

    // 4) 构造检索实现与知识库管理编排服务
    RetrievalQueryRewriteService queryRewriteService =
        new RetrievalQueryRewriteService(modelProvider, provider(dynamicConfig));
    Bm25Scorer bm25Scorer = new Bm25Scorer();
    retrievalService =
        new KnowledgeRetrievalServiceImpl(
            authorizationService,
            embeddingService,
            vectorStore,
            queryRewriteService,
            bm25Scorer,
            provider(dynamicConfig));

    knowledgeAdminService =
        new KnowledgeAdminService(
            null,
            null,
            authorizationService,
            null,
            retrievalService,
            sparseRecallService,
            dynamicConfig,
            requestQuotaService);

    testUser = new UserContext(1001L, 1L, 10L, DataScopeLevel.ALL, "pilot-admin");
  }

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(
        "1".equals(System.getenv(PILOT_GATE_ENV)), "KNOWLEDGE_ADMIN_REAL_PILOT 未置 1，跳过真外呼试点");
    UserContextHolder.set(testUser);
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  @AfterAll
  void tearDownAll() {
    if (vectorStore != null) {
      vectorStore.deleteByDocumentId(PILOT_DOC_ID);
    }
  }

  /**
   * 测试用例 1：微型 Markdown 文档真实切片与向量化摄取。
   *
   * <p>断言切片为 1~2 个 chunk、向量维度为 1024、Token 消耗大于 0 且小于 1,000，并打印现场。
   */
  @Test
  @Order(1)
  void test01_ingestMicroMarkdownDocWithRealEmbedding() throws Exception {
    DocumentService documentService = new DocumentService();
    List<DocumentChunk> chunks =
        documentService.process(
            new ByteArrayInputStream(PILOT_MARKDOWN_DOC.getBytes(StandardCharsets.UTF_8)),
            "crmrag-spec.md",
            "crm");

    assertNotNull(chunks, "切片结果不能为 null");
    assertTrue(
        chunks.size() >= 1 && chunks.size() <= 2, "微型文档成功切片为 1~2 个 chunk，实际数量=" + chunks.size());

    long totalTokens = 0L;
    vectorStore.deleteByDocumentId(PILOT_DOC_ID);

    for (int i = 0; i < chunks.size(); i++) {
      DocumentChunk chunk = chunks.get(i);
      sampleChunkText = chunk.text();

      ModelCallResult<float[]> embedResult = embeddingService.embedWithUsage(chunk.text());
      assertNotNull(embedResult, "嵌入结果不能为 null");
      assertNotNull(embedResult.vector(), "真实向量不能为 null");
      assertEquals(1024, embedResult.vector().length, "真实向量维度必须为 1024 维");

      Long tokens =
          embedResult.totalTokens() != null
              ? embedResult.totalTokens()
              : embedResult.promptTokens();
      assertNotNull(tokens, "必须返回 Token 消耗计量");
      totalTokens += tokens;

      VectorRecord record =
          new VectorRecord(
              "point-pilot-" + i,
              PILOT_DOC_ID,
              "chunk-pilot-" + i,
              i,
              chunk.text(),
              embedResult.vector(),
              Map.of(
                  "knowledgeBaseId",
                  String.valueOf(PILOT_KB_ID),
                  "category",
                  "crm",
                  "filename",
                  "crmrag-spec.md",
                  "chunkIndex",
                  i));
      vectorStore.upsert(record);
    }

    assertTrue(totalTokens > 0, "总消耗 tokens 必须大于 0，实际=" + totalTokens);
    assertTrue(totalTokens < 1000, "总消耗 tokens 必须小于 1,000，实际=" + totalTokens);

    System.out.printf(
        "[PILOT] 用例 1 摄取完成: 实际 chunk 数量=%d, 总消耗 tokens=%d, 向量维度=1024%n",
        chunks.size(), totalTokens);
  }

  /**
   * 测试用例 2：检索 dry-run 对照（稀疏 vs 真向量检索）。
   *
   * <p>验证稀疏检索与真向量检索候选命中，打印稀疏得分与真向量相似度得分对比。
   */
  @Test
  @Order(2)
  void test02_retrievalComparisonSparseVsRealVector() throws Exception {
    ensureSampleChunkIngested();

    // 1) 配置稀疏召回 Mock 响应并执行稀疏检索
    double mockSparseScore = 0.8520D;
    VectorSearchHit sparseHit =
        new VectorSearchHit(
            "chunk-pilot-0",
            PILOT_DOC_ID,
            mockSparseScore,
            sampleChunkText,
            Map.of(
                "knowledgeBaseId",
                String.valueOf(PILOT_KB_ID),
                "filename",
                "crmrag-spec.md",
                "chunkIndex",
                0));
    when(sparseRecallService.recall(anyString(), anyList(), any(), anyInt()))
        .thenReturn(List.of(new RetrievalCandidate(sparseHit, mockSparseScore)));

    KnowledgeAdminRetrievalRequest sparseReq = new KnowledgeAdminRetrievalRequest();
    sparseReq.setKbId(PILOT_KB_ID);
    sparseReq.setQuery("CRMRag 智能知识库知识切片与向量检索规范");
    sparseReq.setUseVector(false);
    sparseReq.setTopK(5);

    KnowledgeAdminRetrievalResponse sparseResp = knowledgeAdminService.retrievalTest(sparseReq);
    assertNotNull(sparseResp, "稀疏检索返回不能为 null");
    assertFalse(sparseResp.getUsedVector(), "稀疏检索 usedVector 必须为 false");
    assertNotNull(sparseResp.getCandidates(), "稀疏候选列表不能为 null");
    assertFalse(sparseResp.getCandidates().isEmpty(), "稀疏检索必须返回候选");
    assertTrue(
        sparseResp.getCandidates().get(0).getText().contains("CRMRag"),
        "稀疏候选文本必须命中刚摄取的 sample chunk");
    double sparseScore = sparseResp.getCandidates().get(0).getScore();

    // 2) 开启真向量前验证禁用保护
    KnowledgeAdminRetrievalRequest vectorReq = new KnowledgeAdminRetrievalRequest();
    vectorReq.setKbId(PILOT_KB_ID);
    vectorReq.setQuery("CRMRag 智能知识库知识切片与向量检索规范");
    vectorReq.setUseVector(true);
    vectorReq.setTopK(5);

    dynamicConfig.set("rag.retrieval.admin-vector.enabled", false);
    BaseException disabledEx =
        assertThrows(
            BaseException.class,
            () -> knowledgeAdminService.retrievalTest(vectorReq),
            "未开启动态配置时必须拒绝真向量检索");
    assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), disabledEx.getCode());

    // 3) 模拟开启动态配置 rag.retrieval.admin-vector.enabled=true 并执行真向量检索
    dynamicConfig.set("rag.retrieval.admin-vector.enabled", true);
    KnowledgeAdminRetrievalResponse vectorResp = knowledgeAdminService.retrievalTest(vectorReq);

    assertNotNull(vectorResp, "真向量检索返回不能为 null");
    assertTrue(vectorResp.getUsedVector(), "真向量检索 usedVector 必须为 true");
    assertNotNull(vectorResp.getCandidates(), "真向量候选列表不能为 null");
    assertFalse(vectorResp.getCandidates().isEmpty(), "真向量检索必须返回候选");
    assertTrue(
        vectorResp.getCandidates().get(0).getText().contains("CRMRag"),
        "真向量候选文本必须命中刚摄取的 sample chunk");
    double vectorScore = vectorResp.getCandidates().get(0).getScore();

    // 4) 控制台显式打印稀疏得分与真向量相似度得分对比
    System.out.printf("[PILOT] 检索得分对比: 稀疏检索得分=%.4f, 真向量相似度得分=%.4f%n", sparseScore, vectorScore);

    // 5) 资源清理确证
    vectorStore.deleteByDocumentId(PILOT_DOC_ID);
    List<VectorSearchHit> remaining =
        vectorStore.search(
            new com.slz.crm.platform.contract.VectorSearchRequest(
                new float[1024], 10, 0.0, Map.of("knowledgeBaseId", String.valueOf(PILOT_KB_ID))));
    assertTrue(remaining.isEmpty(), "清理后向量库必须零残留垃圾数据");
  }

  private void ensureSampleChunkIngested() throws Exception {
    if (sampleChunkText == null) {
      test01_ingestMicroMarkdownDocWithRealEmbedding();
    }
  }

  private static String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    List<Path> candidateEnvFiles = List.of(Path.of(".env"), Path.of("../crmAndRag/.env"));
    for (Path dotEnv : candidateEnvFiles) {
      if (Files.exists(dotEnv)) {
        try {
          String key =
              Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
                  .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
                  .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
                  .findFirst()
                  .orElse(null);
          if (key != null && !key.isBlank()) {
            return key;
          }
        } catch (Exception ignored) {
          // 读不到就尝试下一个
        }
      }
    }
    return null;
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private static final class TestDynamicConfigService implements DynamicConfigService {
    private final Map<String, Object> configs = new ConcurrentHashMap<>();

    void set(String key, Object value) {
      if (value == null) {
        configs.remove(key);
      } else {
        configs.put(key, value);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
      Object value = configs.get(key);
      if (value == null) {
        return defaultValue;
      }
      return (T) value;
    }
  }

  /**
   * DashScope 嵌入模型包装代理：直接调用 DashScopeApi 透传真实向量与 EmbeddingUsage.totalTokens()， 弥补 spring-ai-alibaba
   * 对 DashScope 嵌入 usage 映射为 DefaultUsage{promptTokens=0, totalTokens=0} 的缺口。
   */
  private static final class PilotEmbeddingModel implements EmbeddingModel {
    private final DashScopeApi dashScopeApi;
    private final String modelName;

    PilotEmbeddingModel(DashScopeApi dashScopeApi, String modelName) {
      this.dashScopeApi = dashScopeApi;
      this.modelName = modelName;
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<String> instructions = request.getInstructions();
      DashScopeApi.EmbeddingRequest apiRequest =
          new DashScopeApi.EmbeddingRequest(instructions, modelName);
      var responseEntity = dashScopeApi.embeddings(apiRequest);
      DashScopeApi.EmbeddingList body = responseEntity.getBody();
      if (body == null || body.output() == null) {
        throw new IllegalStateException("DashScope embedding 返回空输出");
      }
      List<org.springframework.ai.embedding.Embedding> embeddings = new ArrayList<>();
      for (DashScopeApi.Embedding item : body.output().embeddings()) {
        embeddings.add(
            new org.springframework.ai.embedding.Embedding(item.embedding(), item.textIndex()));
      }
      Long tokens = body.usage() != null ? body.usage().totalTokens() : null;
      final Long finalTokens = tokens != null ? tokens : 0L;
      Usage fixedUsage =
          new Usage() {
            @Override
            public Integer getPromptTokens() {
              return finalTokens.intValue();
            }

            @Override
            public Integer getCompletionTokens() {
              return 0;
            }

            @Override
            public Integer getTotalTokens() {
              return finalTokens.intValue();
            }

            @Override
            public Object getNativeUsage() {
              return body.usage();
            }
          };
      Map<String, Object> metadataMap = new ConcurrentHashMap<>();
      metadataMap.put("model", modelName);
      metadataMap.put("total-tokens", finalTokens);
      EmbeddingResponseMetadata metadata =
          new EmbeddingResponseMetadata(modelName, fixedUsage, metadataMap);
      return new EmbeddingResponse(embeddings, metadata);
    }
  }
}
