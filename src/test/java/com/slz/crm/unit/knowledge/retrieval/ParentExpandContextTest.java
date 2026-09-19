package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * 双粒度父块展开管线级单测（提案4 任务 3.3/3.4，方案03 Small-to-Big）： 命中子块 → 父块全文进上下文（生成单元）；sources
 * 引用与跳页锚点仍指命中小块（检索单元）； 开关 off / 非数字 chunkId / 未挂父块命中 → 回退提案3邻居模式。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParentExpandContextTest {
  private static final String DOC_ID = "doc-1";
  private static final String QUERY = "回款流程";
  private static final String PARENT_TEXT = "父块全文：本节完整描述回款流程，从开票到核销的全链路说明，供生成参考。";
  private static final String CHILD_TEXT = "命中子块：回款节点说明";

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

  private void stubCommonConfig() {
    UserContextHolder.set(new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user"));
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    // 未显式覆盖的键一律取代码内默认（parent-expand 默认 on、neighbors 默认 1）
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dynamicConfigService.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true))
        .thenReturn(false);
    when(authorizationService.authorizedKnowledgeBaseIds(any(), any())).thenReturn(List.of(1L));
    when(embeddingService.embed(QUERY)).thenReturn(new float[] {1f, 0f});
  }

  private KnowledgeRetrievalServiceImpl service() {
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

  /** 任务 3.4：命中子块 → 上下文含父块全文；sources 引用与页锚点仍指命中小块。 */
  @Test
  void hitChildExpandsParentIntoContextWhileSourcePointsAtChild() {
    stubCommonConfig();
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "12",
                    DOC_ID,
                    0.9d,
                    CHILD_TEXT,
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L))));
    when(chunkMapper.selectById(12L)).thenReturn(childRow(12L, 7L));
    when(chunkMapper.selectById(7L)).thenReturn(parentRow(7L, PARENT_TEXT));

    KnowledgeRetrievalPort.RetrievalResult result =
        service()
            .retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 1, null, null));

    assertThat(result.context()).contains("[1] " + PARENT_TEXT);
    assertThat(result.context()).doesNotContain("（前文承接）", "父块展开后不再叠邻居");
    // 引用锚点不降级：excerpt/chunkId/pageNo 全部指命中小块
    assertThat(result.sources()).hasSize(1);
    SourceReference source = result.sources().get(0);
    assertThat(source.excerpt()).isEqualTo(CHILD_TEXT);
    assertThat(source.chunkId()).isEqualTo("12");
    assertThat(source.pageNo()).isEqualTo(3);
  }

  /** 展开可关闭：parent-expand=off 回退邻居增强模式（提案3完成态）。 */
  @Test
  void parentExpandOffFallsBackToNeighborMode() {
    stubCommonConfig();
    when(dynamicConfigService.get("rag.context.parent-expand", String.class, "on"))
        .thenReturn("off");
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "12",
                    DOC_ID,
                    0.9d,
                    CHILD_TEXT,
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L))));
    when(chunkMapper.selectList(any()))
        .thenReturn(List.of(neighborRow(1L, "邻居一：合同审批前置流程"), neighborRow(3L, "邻居三：回款确认后续动作")));

    KnowledgeRetrievalPort.RetrievalResult result =
        service()
            .retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 1, null, null));

    assertThat(result.context())
        .contains("[1] （前文承接）邻居一：合同审批前置流程")
        .contains(CHILD_TEXT)
        .contains("（后文承接）邻居三：回款确认后续动作");
    verify(chunkMapper, never()).selectById(any());
  }

  /** 评测占位 id（非数字）与未挂父块的命中（fixed 行为）→ 逐块回退邻居模式。 */
  @Test
  void nonNumericOrParentlessHitsFallBackToNeighbors() {
    stubCommonConfig();
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "benchdoc-0",
                    DOC_ID,
                    0.9d,
                    CHILD_TEXT,
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L)),
                new VectorSearchHit(
                    "13",
                    DOC_ID,
                    0.8d,
                    "命中子块二：逾期口径",
                    Map.of("chunkIndex", 5, "filename", "sales.txt", "pageNo", 4L))));
    when(chunkMapper.selectById(13L)).thenReturn(childRow(13L, null));
    when(chunkMapper.selectList(any()))
        .thenReturn(List.of(neighborRow(1L, "邻居一：合同审批前置流程"), neighborRow(3L, "邻居三：回款确认后续动作")));

    KnowledgeRetrievalPort.RetrievalResult result =
        service()
            .retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 2, null, null));

    assertThat(result.context()).contains("（前文承接）邻居一").contains("（后文承接）邻居三");
    // 非数字占位 id 不查快照表；数字 id（未挂父块）查一次后回退邻居
    verify(chunkMapper).selectById(13L);
    assertThat(result.sources())
        .extracting(SourceReference::excerpt)
        .containsExactly(CHILD_TEXT, "命中子块二：逾期口径");
  }

  /** 父块行缺失（脏引用）→ 邻居回退，不拖垮检索链。 */
  @Test
  void missingParentRowFallsBackToNeighbors() {
    stubCommonConfig();
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                new VectorSearchHit(
                    "12",
                    DOC_ID,
                    0.9d,
                    CHILD_TEXT,
                    Map.of("chunkIndex", 2, "filename", "sales.txt", "pageNo", 3L))));
    when(chunkMapper.selectById(12L)).thenReturn(childRow(12L, 99L));
    when(chunkMapper.selectById(99L)).thenReturn(null);
    when(chunkMapper.selectList(any())).thenReturn(List.of(neighborRow(1L, "邻居一：合同审批前置流程")));

    KnowledgeRetrievalPort.RetrievalResult result =
        service()
            .retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(QUERY, 1L, List.of("1"), 1, null, null));

    assertThat(result.context()).contains("[1] （前文承接）邻居一：合同审批前置流程").contains(CHILD_TEXT);
    assertThat(result.sources()).hasSize(1);
  }

  private DocumentVectorChunkEntity childRow(Long id, Long parentChunkId) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId(id);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex(2);
    entity.setChunkText(CHILD_TEXT);
    entity.setChunkRole("CHILD");
    entity.setParentChunkId(parentChunkId);
    return entity;
  }

  private DocumentVectorChunkEntity parentRow(Long id, String text) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId(id);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex(9);
    entity.setChunkText(text);
    entity.setChunkRole("PARENT");
    return entity;
  }

  private DocumentVectorChunkEntity neighborRow(long id, String text) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId(id);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex((int) id);
    entity.setChunkText(text);
    entity.setChunkRole("CHILD");
    return entity;
  }
}
