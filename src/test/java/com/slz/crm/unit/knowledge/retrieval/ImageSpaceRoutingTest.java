package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * multimodal-shared-space 任务 5.1：真 query-by-image 检索路由单测（视觉向量空间与文本空间异构）。
 *
 * <p>覆盖：图片路查 image 集合而非文本集合 / 异构双空间融合（同文档不同切片不误合并）/ 图片路失败降级为纯文本 / 无图片查询向量时图片路零调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("真 query-by-image 路由（任务 4.2）")
class ImageSpaceRoutingTest {

  private static final float[] IMAGE_VECTOR = {0.9F, 0.1F};

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmVectorStore vectorStore;

  @Mock private ImageVectorStore imageVectorStore;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  @Test
  @DisplayName("图片路查 image 集合：同文档不同切片不误合并，IMAGE 引用回填")
  void imageRouteQueriesImageSpace_andKeepsSlicesDistinct() {
    UserContext user = user();
    UserContextHolder.set(user);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed(anyString())).thenReturn(new float[] {1F, 0F});
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("10", "doc-1", 0.8D, "销售流程片段", Map.of("chunkIndex", 0))));
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                hit(
                    "501",
                    "doc-1",
                    0.95D,
                    "服务台架构图描述",
                    Map.of(
                        "chunkIndex",
                        0,
                        "imageAssetId",
                        "501",
                        "sourceType",
                        "IMAGE",
                        "imageSlice",
                        true,
                        "pageNo",
                        1L))));

    KnowledgeRetrievalPort.RetrievalResult result = retrieve(user, IMAGE_VECTOR);

    // 图片路走独立 image 集合，文本路仍查文本集合（异构双空间各查各的）
    verify(imageVectorStore).search(any(VectorSearchRequest.class));
    verify(vectorStore).search(any(VectorSearchRequest.class));
    assertThat(result.sources()).extracting(SourceReference::chunkId).contains("10", "IMAGE:501");
    assertThat(result.sources())
        .filteredOn(source -> "IMAGE".equals(source.sourceType()))
        .singleElement()
        .satisfies(source -> assertThat(source.documentId()).isEqualTo("501"));
    // 两路切片 key 不同（doc-1:10 vs doc-1:501），融合后仍是 2 条，未被误合并
    assertThat(result.sources()).hasSize(2);
  }

  @Test
  @DisplayName("图片路失败（维度不符/集合不可达）：降级为纯文本，不抛错")
  void imageRouteFailure_degradesToTextOnly() {
    UserContext user = user();
    UserContextHolder.set(user);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed(anyString())).thenReturn(new float[] {1F, 0F});
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("10", "doc-1", 0.8D, "销售流程片段", Map.of("chunkIndex", 0))));
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenThrow(new IllegalArgumentException("查询向量维度 2 与集合维度 1024 不一致"));

    KnowledgeRetrievalPort.RetrievalResult result = retrieve(user, IMAGE_VECTOR);

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("10");
  }

  @Test
  @DisplayName("无图片查询向量：图片路零调用（KB 未开/无图时行为与升级前一致）")
  void noImageVector_neverTouchesImageSpace() {
    UserContext user = user();
    UserContextHolder.set(user);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed(anyString())).thenReturn(new float[] {1F, 0F});
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("10", "doc-1", 0.8D, "销售流程片段", Map.of("chunkIndex", 0))));

    KnowledgeRetrievalPort.RetrievalResult result = retrieve(user, null);

    verify(imageVectorStore, never()).search(any());
    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("10");
  }

  private KnowledgeRetrievalPort.RetrievalResult retrieve(UserContext user, float[] imageVector) {
    KnowledgeRetrievalServiceImpl service =
        new KnowledgeRetrievalServiceImpl(
            authorizationService,
            embeddingService,
            vectorStore,
            new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
            new Bm25Scorer(),
            dynamicConfigProvider);
    ReflectionTestUtils.setField(service, "imageVectorStore", imageVectorStore);
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    return service.retrieve(
        new KnowledgeRetrievalPort.RetrievalQuery(
            "发票图片", user.userId(), List.of("1"), 5, imageVector, null));
  }

  private static UserContext user() {
    return new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
  }

  private static VectorSearchHit hit(
      String chunkId, String documentId, double score, String text, Map<String, Object> metadata) {
    return new VectorSearchHit(chunkId, documentId, score, text, metadata);
  }
}
