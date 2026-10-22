package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * image-retrieval-threshold-gate 任务 3.1：图像路独立同图阈值 + 三档分档单测。
 *
 * <p>覆盖：①三档边界（阈值 / 近似下限 0.9887 / 异图下限以下）；②近重复图「不误杀但降权」； ③文本路 minScore 仍为 0.20
 * 且与图像路阈值完全解耦（零回归）；④运营改独立键即改档界； ⑤异图过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("图像路同图阈值三档闸门（image-retrieval-threshold-gate）")
class ImageSimilarityThresholdGateTest {

  private static final float[] TEXT_VECTOR = {1F, 0F};
  private static final float[] IMAGE_VECTOR = {0.9F, 0.1F};

  /** 定标锚判别阈值（默认 0.9944）。 */
  private static final double DEFAULT_THRESHOLD = ImageSimilarityGate.DEFAULT_THRESHOLD;

  /** 锚的同图下限 0.9999999999999998（同图再编码实测下界）。 */
  private static final double ANCHOR_SAME_IMAGE_FLOOR = 0.9999999999999998D;

  /** 锚的异图上限 0.9887359400788237（任何不同图的实测上界）。 */
  private static final double ANCHOR_CROSS_IMAGE_CEILING = 0.9887359400788237D;

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private CrmVectorStore vectorStore;

  @Mock private ImageVectorStore imageVectorStore;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  // ---------------- 1. 三档边界 ----------------

  @Test
  @DisplayName("三档边界：≥阈值=同图 / [下限,阈值)=近似 / <下限=异图，下限取锚的异图上限")
  void tierBoundaries() {
    assertThat(ImageSimilarityGate.tierOf(DEFAULT_THRESHOLD, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_SAME_IMAGE);
    assertThat(ImageSimilarityGate.tierOf(ANCHOR_SAME_IMAGE_FLOOR, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_SAME_IMAGE);
    // 阈值下方一点点（锚的 recommendedThreshold 原始值 0.9943679700394117）落近似档
    assertThat(ImageSimilarityGate.tierOf(0.9943679700394117D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    // 近似下限闭区间：恰等于锚的异图上限算近似同图
    assertThat(
            ImageSimilarityGate.tierOf(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    assertThat(ImageSimilarityGate.tierOf(ANCHOR_CROSS_IMAGE_CEILING, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    // 下限以下 = 异图
    assertThat(ImageSimilarityGate.tierOf(0.98869D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_CROSS_IMAGE);
    assertThat(ImageSimilarityGate.tierOf(0.85D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_CROSS_IMAGE);
    // 锚自证：同图下限 > 异图上限，阈值严格落在区间内（锚的 separable 口径）
    assertThat(DEFAULT_THRESHOLD).isGreaterThan(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR).isLessThan(ANCHOR_SAME_IMAGE_FLOOR);
  }

  @Test
  @DisplayName("阈值被调低到下限之下：近似档退化为空区间，不与同图档重叠")
  void tierBoundaryCollapsesWhenThresholdBelowFloor() {
    assertThat(ImageSimilarityGate.effectiveFloor(0.97D)).isEqualTo(0.97D);
    assertThat(ImageSimilarityGate.tierOf(0.97D, 0.97D))
        .isEqualTo(ImageSimilarityGate.TIER_SAME_IMAGE);
    assertThat(ImageSimilarityGate.tierOf(0.9699D, 0.97D))
        .isEqualTo(ImageSimilarityGate.TIER_CROSS_IMAGE);
  }

  @Test
  @DisplayName("gate()：异图丢弃，同图/近似档打档位标记，近似档分数不在召回期改动")
  void gateFiltersCrossImageAndTagsTiers() {
    List<RetrievalCandidate> gated =
        ImageSimilarityGate.gate(
            List.of(
                candidate("same", 0.9999D), candidate("near", 0.992D), candidate("cross", 0.98D)),
            DEFAULT_THRESHOLD);

    assertThat(gated).extracting(item -> item.hit().chunkId()).containsExactly("same", "near");
    assertThat(gated.get(0).hit().metadata())
        .containsEntry(ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_SAME_IMAGE);
    assertThat(gated.get(1).hit().metadata())
        .containsEntry(
            ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    // 召回期不改分（降权由重排后的 downWeightNearDuplicates 做）
    assertThat(gated.get(1).rerankScore()).isEqualTo(0.992D);
  }

  @Test
  @DisplayName("downWeightNearDuplicates()：仅近似档乘 0.5，同图/未标记候选原样")
  void downWeightOnlyNearDuplicates() {
    List<RetrievalCandidate> downWeighted =
        ImageSimilarityGate.downWeightNearDuplicates(
            List.of(
                new RetrievalCandidate(tagged("same", ImageSimilarityGate.TIER_SAME_IMAGE), 0.8D),
                new RetrievalCandidate(
                    tagged("near", ImageSimilarityGate.TIER_NEAR_DUPLICATE), 0.8D),
                new RetrievalCandidate(hit("plain", 0.8D), 0.8D)));

    assertThat(downWeighted)
        .extracting(RetrievalCandidate::rerankScore)
        .containsExactly(0.8D, 0.4D, 0.8D);
  }

  // ---------------- 2. 近重复图不误杀 + 降权 ----------------

  @Test
  @DisplayName("近重复图（0.992，低于阈值）仍被召回，但降权一半（0.18→0.09）—— 单阈值不再误杀")
  void nearDuplicateRecalledButDownWeighted() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.992D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("502");
    // 图像路单候选：向量分归一化 1.0 × 向量权重 0.6（单候选 BM25 归一化为 0）= 0.6
    // → 近似档降权 0.5 = 0.3 → 图片路路由权重 0.3 = 0.09（被误杀则此处根本没有 source）
    assertThat(result.sources().getFirst().relevanceScore()).isEqualTo(0.09D);
  }

  @Test
  @DisplayName("真同图优先于近似同图：0.9999 排在 0.992 之前，两者都不被丢")
  void sameImageRanksBeforeNearDuplicate() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.992D), hit("501", 0.9999D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("501", "502");
    assertThat(result.sources().get(1).relevanceScore())
        .isLessThan(result.sources().getFirst().relevanceScore());
  }

  // ---------------- 3. 异图过滤 ----------------

  @Test
  @DisplayName("异图（0.985 < 近似下限 0.9887）过滤，文本路命中照常返回")
  void crossImageFilteredTextUnaffected() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of(hit("10", 0.85D)));
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.985D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("10");
  }

  // ---------------- 4. 独立阈值键 / 文本路零回归 ----------------

  @Test
  @DisplayName("独立阈值：图像路按 0.9887 检索，文本路仍按 minScore 0.20（两路阈值彻底解耦）")
  void imageRouteUsesIndependentThresholdWhileTextKeepsMinScore() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    // 文本路 0.25 > 0.20 → 必须仍被召回（文本路零回归）
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of(hit("10", 0.25D)));
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("501", 0.9999D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    ArgumentCaptor<VectorSearchRequest> textRequest =
        ArgumentCaptor.forClass(VectorSearchRequest.class);
    ArgumentCaptor<VectorSearchRequest> imageRequest =
        ArgumentCaptor.forClass(VectorSearchRequest.class);
    verify(vectorStore).search(textRequest.capture());
    verify(imageVectorStore).search(imageRequest.capture());
    assertThat(textRequest.getValue().minScore()).isEqualTo(0.20D);
    assertThat(imageRequest.getValue().minScore())
        .isEqualTo(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    assertThat(result.sources()).extracting(SourceReference::chunkId).contains("10", "501");
  }

  @Test
  @DisplayName("运营改独立键即改档界：阈值调 0.99 后 0.992 升为高置信同图（不再降权）")
  void configuredThresholdMovesTierBoundary() {
    configuredThreshold(0.99D);
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.992D)));

    withConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("502");
    // 高置信同图 → 不降权：0.6 × 0.3 = 0.18（默认阈值下同一命中为近近似档 0.09，恰为一半）
    assertThat(result.sources().getFirst().relevanceScore()).isEqualTo(0.18D);
  }

  // ---------------- helpers ----------------

  private KnowledgeRetrievalServiceImpl retrieveService() {
    return new KnowledgeRetrievalServiceImpl(
        authorizationService,
        embeddingService,
        vectorStore,
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
        new Bm25Scorer(),
        dynamicConfigProvider);
  }

  private KnowledgeRetrievalPort.RetrievalResult retrieve() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    lenient()
        .when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    KnowledgeRetrievalServiceImpl service = retrieveService();
    ReflectionTestUtils.setField(service, "imageVectorStore", imageVectorStore);
    return service.retrieve(
        new KnowledgeRetrievalPort.RetrievalQuery(
            "近重复图查询", user.userId(), List.of("1"), 5, IMAGE_VECTOR, null));
  }

  /** 未装配动态配置：全部回落注册表/代码默认（图像阈值 0.9944）。 */
  private void withDefaultConfig() {
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenAnswer(invocation -> null);
  }

  /** 装配动态配置：查询改写关闭（避免模型外呼），其余键回落默认。 */
  private void withConfig() {
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    lenient()
        .when(
            dynamicConfigService.get(
                eq("rag.retrieval.query-rewrite.enabled"), eq(Boolean.class), eq(true)))
        .thenReturn(false);
  }

  /** 运营把独立键改成指定阈值（其余键回落默认）。 */
  private void configuredThreshold(double threshold) {
    lenient()
        .when(
            dynamicConfigService.get(
                eq(ImageSimilarityGate.THRESHOLD_KEY), eq(Double.class), any(Double.class)))
        .thenReturn(threshold);
  }

  private static RetrievalCandidate candidate(String chunkId, double score) {
    return new RetrievalCandidate(hit(chunkId, score), score);
  }

  private static VectorSearchHit tagged(String chunkId, String tier) {
    return new VectorSearchHit(
        chunkId, "doc-1", 0.99D, "图像切片描述", Map.of(ImageSimilarityGate.TIER_METADATA_KEY, tier));
  }

  private static VectorSearchHit hit(String chunkId, double score) {
    return new VectorSearchHit(
        chunkId, "doc-1", score, "图像切片描述", Map.of("chunkIndex", 1, "filename", "img.png"));
  }
}
