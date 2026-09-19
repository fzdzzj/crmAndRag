package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
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

/**
 * 邻居增强管线级单测（add-context-compression-and-enrichment 任务 1.1/1.2）： 邻居内容进入上下文，但 sources
 * 引用仍严格等于命中块——引用不被邻居污染。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeighborContextPipelineTest {
  private static final String DOC_ID = "doc-1";
  private static final String QUERY = "回款流程";

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private com.slz.crm.platform.contract.CrmVectorStore vectorStore;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  @Mock private DocumentVectorChunkMapper chunkMapper;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** 任务 1.2：邻居块拼进上下文，但 sources 数 == 命中块数且不含邻居文本（引用不混入）。 */
  @Test
  void neighborsMustNeverEnterSourceReferences() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dynamicConfigService.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true))
        .thenReturn(false);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed(QUERY)).thenReturn(new float[] {1f, 0f});
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "c2",
                    DOC_ID,
                    0.9d,
                    "命中块二：回款节点说明",
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L)),
                new VectorSearchHit(
                    "c9",
                    DOC_ID,
                    0.8d,
                    "命中块九：逾期处理口径",
                    Map.of("chunkIndex", 9, "filename", "sales.txt", "pageNo", 5L))));
    when(chunkMapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              // 简化快照表：任何查询都返回两个命中块各自的紧邻行（邻居选取按 chunkIndex 精确匹配）
              return List.of(
                  row(1, "邻居一：合同审批前置流程"),
                  row(3, "邻居三：回款确认后续动作"),
                  row(8, "邻居八：逾期前提醒机制"),
                  row(10, "邻居十：法务介入流程"));
            });
    KnowledgeRetrievalServiceImpl service =
        new KnowledgeRetrievalServiceImpl(
            authorizationService,
            embeddingService,
            vectorStore,
            new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
            dynamicConfigProvider,
            null,
            null,
            new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider),
            null,
            new ContextBuilder(
                chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null));

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 2, null, null));

    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.sources()).hasSize(2);
    assertThat(result.sources())
        .extracting(SourceReference::excerpt)
        .containsExactly("命中块二：回款节点说明", "命中块九：逾期处理口径");
    for (SourceReference source : result.sources()) {
      assertThat(source.excerpt()).doesNotContain("邻居");
    }
    // 邻居只进上下文：四条邻居文本都应出现在 context 中，且编号与候选顺序一致
    assertThat(result.context())
        .contains("[1] （前文承接）邻居一：合同审批前置流程")
        .contains("命中块二：回款节点说明")
        .contains("（后文承接）邻居三：回款确认后续动作")
        .contains("[2] （前文承接）邻居八：逾期前提醒机制")
        .contains("命中块九：逾期处理口径")
        .contains("（后文承接）邻居十：法务介入流程");
  }

  private DocumentVectorChunkEntity row(int chunkIndex, String text) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId((long) chunkIndex);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex(chunkIndex);
    entity.setChunkText(text);
    return entity;
  }
}
