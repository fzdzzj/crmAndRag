package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 回退演练（提案4 任务 5.4）：{@code rag.chunking.strategy=fixed} + {@code rag.context.parent-expand=off}
 * 双开关关闭后，切分与上下文行为必须回到提案3完成态 （基线用例的真检索部分由 5.1 在授权后真跑，本演练在单测层锁定机械等价）。
 *
 * <p>三条等价链：
 *
 * <ol>
 *   <li>fixed 切分序列 = 升级前 320/40 滑窗逐字一致，且不产生父块；
 *   <li>parent-expand=off → 邻居模式输出与提案3完成态一致（含 neighbors 开关语义）；
 *   <li>fixed 数据 + parent-expand=on（默认）→ 输出仍与 off 完全一致——fixed 无父块， 展开路径逐块回退，默认开不破坏回退能力。
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkingRollbackDrillTest {
  private static final String DOC_ID = "doc-1";
  private static final String QUERY = "回款流程";

  @Mock private KnowledgeBaseAuthorizationService authorizationService;
  @Mock private EmbeddingService embeddingService;
  @Mock private CrmVectorStore vectorStore;
  @Mock private ModelProvider modelProvider;
  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  @Mock private DynamicConfigService dynamicConfigService;
  @Mock private DocumentVectorChunkMapper chunkMapper;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** 链1：显式 fixed 的切分序列与升级前算法逐字一致、无父块。 */
  @Test
  void fixedStrategyChunkingEqualsLegacy() throws Exception {
    MockEnvironment environment =
        new MockEnvironment().withProperty("rag.chunking.strategy", "fixed");
    DocumentService service =
        new DocumentService(providerOf(new DynamicConfigFromEnvironment(environment)));

    String text = "销售过程管理是CRM系统中的关键能力，客户、商机、合同与回款数据需要保持一致。".repeat(40);
    List<DocumentChunk> chunks =
        service.process(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "sales.txt", "crm");

    assertThat(chunks.stream().map(DocumentChunk::text).toList())
        .containsExactlyElementsOf(
            legacySplit(text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").strip()));
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.parentText()).isNull());
  }

  /** 链2+链3：parent-expand off / on(默认) 在 fixed 数据上输出逐字一致且等于提案3邻居模式。 */
  @Test
  void contextBehaviorReturnsToProposal3State() {
    UserContextHolder.set(new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user"));
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dynamicConfigService.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true))
        .thenReturn(false);
    when(authorizationService.authorizedKnowledgeBaseIds(any(), any())).thenReturn(List.of(1L));
    when(embeddingService.embed(QUERY)).thenReturn(new float[] {1f, 0f});
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "12",
                    DOC_ID,
                    0.9d,
                    "命中块二：回款节点说明",
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L)),
                new VectorSearchHit(
                    "19",
                    DOC_ID,
                    0.8d,
                    "命中块九：逾期处理口径",
                    Map.of("chunkIndex", 9, "filename", "sales.txt", "pageNo", 5L))));
    when(chunkMapper.selectList(any()))
        .thenReturn(
            List.of(
                neighborRow(1, "邻居一：合同审批前置流程"),
                neighborRow(3, "邻居三：回款确认后续动作"),
                neighborRow(8, "邻居八：逾期前提醒机制"),
                neighborRow(10, "邻居十：法务介入流程")));

    // off：显式回退邻居模式（提案3完成态）
    when(dynamicConfigService.get("rag.context.parent-expand", String.class, "on"))
        .thenReturn("off");
    String contextOff = retrieve(serviceWithBuilder());

    // on（默认）：fixed 切分数据无父块，展开路径逐块回退邻居模式——输出必须与 off 逐字一致
    when(dynamicConfigService.get("rag.context.parent-expand", String.class, "on"))
        .thenReturn("on");
    String contextOn = retrieve(serviceWithBuilder());

    assertThat(contextOff)
        .contains("[1] （前文承接）邻居一：合同审批前置流程")
        .contains("命中块二：回款节点说明")
        .contains("（后文承接）邻居三：回款确认后续动作")
        .contains("[2] （前文承接）邻居八：逾期前提醒机制")
        .contains("（后文承接）邻居十：法务介入流程");
    assertThat(contextOn).isEqualTo(contextOff);
  }

  private KnowledgeRetrievalServiceImpl serviceWithBuilder() {
    return new KnowledgeRetrievalServiceImpl(
        authorizationService,
        embeddingService,
        vectorStore,
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
        dynamicConfigProvider,
        null,
        null,
        new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider),
        null,
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null));
  }

  private String retrieve(KnowledgeRetrievalServiceImpl service) {
    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 2, null, null));
    List<String> excerpts = result.sources().stream().map(SourceReference::excerpt).toList();
    assertThat(excerpts).containsExactly("命中块二：回款节点说明", "命中块九：逾期处理口径");
    return result.context();
  }

  private static ObjectProvider<DynamicConfigService> providerOf(DynamicConfigService config) {
    return new ObjectProvider<>() {
      @Override
      public DynamicConfigService getObject() {
        return config;
      }

      @Override
      public DynamicConfigService getIfAvailable() {
        return config;
      }

      @Override
      public DynamicConfigService getIfUnique() {
        return config;
      }
    };
  }

  /** 环境变量型 DynamicConfig 桩：读 MockEnvironment 的属性（rollback 演练的配置载体）。 */
  private static final class DynamicConfigFromEnvironment implements DynamicConfigService {
    private final MockEnvironment environment;

    private DynamicConfigFromEnvironment(MockEnvironment environment) {
      this.environment = environment;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
      String value = environment.getProperty(key);
      if (value == null || value.isBlank()) {
        return defaultValue;
      }
      if (type == String.class) {
        return (T) value;
      }
      if (type == Integer.class) {
        return (T) Integer.valueOf(value);
      }
      return defaultValue;
    }
  }

  private static List<String> legacySplit(String normalized) {
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < normalized.length()) {
      int end = Math.min(start + 320, normalized.length());
      chunks.add(normalized.substring(start, end));
      if (end >= normalized.length()) {
        break;
      }
      start = Math.max(end - 40, start + 1);
    }
    return chunks;
  }

  private DocumentVectorChunkEntity neighborRow(int chunkIndex, String text) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId((long) chunkIndex);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex(chunkIndex);
    entity.setChunkText(text);
    entity.setChunkRole("CHILD");
    return entity;
  }
}
