package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import java.util.Comparator;
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
 * image-retrieval-threshold-gate 任务 3.1 + image-near-duplicate-band-weight：图像路独立同图阈值 + 三档分档 +
 * 近似带（NEAR_HIGH/MID/LOW）降权单测。
 *
 * <p>覆盖：①三档边界（阈值 / 准入下限 0.982 / 异图下限以下，带内边界以冻结基准 0.9887 推导）；②近重复图「不误杀但按落带降权」； ③文本路 minScore 仍为 0.20
 * 且与图像路阈值完全解耦（零回归）；④运营改独立键即改档界（含段边界随锚缩放）； ⑤异图过滤；⑥band 缺失回落 0.5、重复降权幂等。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("图像路同图阈值三档闸门 + 近似带降权（image-near-duplicate-band-weight）")
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
  @DisplayName("三档边界：≥阈值=同图 / [下限,阈值)=近似 / <下限=异图，下限取生产准入下限 0.982")
  void tierBoundaries() {
    assertThat(ImageSimilarityGate.tierOf(DEFAULT_THRESHOLD, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_SAME_IMAGE);
    assertThat(ImageSimilarityGate.tierOf(ANCHOR_SAME_IMAGE_FLOOR, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_SAME_IMAGE);
    // 阈值下方一点点（锚的 recommendedThreshold 原始值 0.9943679700394117）落近似档
    assertThat(ImageSimilarityGate.tierOf(0.9943679700394117D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    // 近似下限闭区间：恰等于生产准入下限算近似同图
    assertThat(
            ImageSimilarityGate.tierOf(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    assertThat(ImageSimilarityGate.tierOf(ANCHOR_CROSS_IMAGE_CEILING, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.TIER_NEAR_DUPLICATE);
    // 下限以下 = 异图（near-dup-floor-lowering 后 0.982–0.9887 的旧「异图」样本已准入，异图样本换 0.978）
    assertThat(ImageSimilarityGate.tierOf(0.978D, DEFAULT_THRESHOLD))
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
  @DisplayName("downWeightNearDuplicates()：近似档按 band 降权（缺 band 回落 0.5），同图/未标记候选原样")
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
  @DisplayName("近似同图中档（0.992，低于阈值）仍被召回，降权 0.6（0.18→0.108）—— 单阈值不再误杀")
  void nearDuplicateRecalledButDownWeighted() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.992D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("502");
    // 图像路单候选：向量分归一化 1.0 × 向量权重 0.6（单候选 BM25 归一化为 0）= 0.6
    // 0.992 落在间隔带中 1/3（NEAR_MID）→ ×0.6 = 0.36 → 图片路路由权重 0.3 = 0.108
    // （同一命中若落低档 ×0.5 则为 0.09；被误杀则此处根本没有 source）
    assertThat(result.sources().getFirst().relevanceScore()).isEqualTo(0.108D);
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
  @DisplayName("异图（0.978 < 准入下限 0.982）过滤，文本路命中照常返回")
  void crossImageFilteredTextUnaffected() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of(hit("10", 0.85D)));
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.978D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("10");
    // near-dup-floor-lowering：原样本 0.985 已随准入下限下探（0.982）被准入并降权，异图样本换 0.978
  }

  // ---------------- 4. 独立阈值键 / 文本路零回归 ----------------

  @Test
  @DisplayName("独立阈值：图像路按 0.982 检索，文本路仍按 minScore 0.20（两路阈值彻底解耦）")
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

  // ---------------- 5. 近似带三档（image-near-duplicate-band-weight） ----------------

  @Test
  @DisplayName("三档权重不变量：LOW == 旧 0.5、严格单调、全部落在 [0.5, 1.0)")
  void bandWeightsAreStrictlyMonotonicAndLowestNeverRelaxes() {
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW).isEqualTo(0.5D);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_DOWN_WEIGHT)
        .isEqualTo(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW)
        .isLessThan(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID)
        .isLessThan(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH).isLessThan(1.0D);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH).isGreaterThanOrEqualTo(0.5D);
  }

  @Test
  @DisplayName("落带边界：带基准 / 基准+Δ/3 / 基准+2Δ/3 / threshold 各取 ±1e-6，边界值归上一档")
  void bandBoundariesAtDefaultThreshold() {
    double lowUpper = ImageSimilarityGate.lowBandUpperBound(DEFAULT_THRESHOLD);
    double highLower = ImageSimilarityGate.highBandLowerBound(DEFAULT_THRESHOLD);
    // 带基准 0.9887/0.9944 的三等分点：0.9906、0.9925——由 bandReferenceFloor/threshold 推导，
    // 准入下限 0.982 变动不移动内边界（near-dup-floor-lowering 的纯加性硬约束）
    assertThat(lowUpper).isCloseTo(0.9906D, within(1.0E-12D));
    assertThat(highLower).isCloseTo(0.9925D, within(1.0E-12D));
    assertThat(lowUpper - ImageSimilarityGate.BAND_REFERENCE_FLOOR)
        .isCloseTo(highLower - lowUpper, within(1.0E-12D));

    // 下限：恰等于 floor 归低档（闭区间），再低 1e-6 就是异图（无带）
    assertThat(
            ImageSimilarityGate.bandOf(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(
            ImageSimilarityGate.bandOf(
                ImageSimilarityGate.NEAR_DUPLICATE_FLOOR - 1.0E-6D, DEFAULT_THRESHOLD))
        .isNull();

    // 低/中边界：±1e-6 分属低/中档，边界值本身归中档（>=）
    assertThat(ImageSimilarityGate.bandOf(lowUpper - 1.0E-6D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(ImageSimilarityGate.bandOf(lowUpper, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(lowUpper + 1.0E-6D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);

    // 中/高边界同理
    assertThat(ImageSimilarityGate.bandOf(highLower - 1.0E-6D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(highLower, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(highLower + 1.0E-6D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);

    // 阈值边界：阈值 −1e-6 = 高档；恰等于阈值 = 同图（无带）
    assertThat(ImageSimilarityGate.bandOf(DEFAULT_THRESHOLD - 1.0E-6D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(DEFAULT_THRESHOLD, DEFAULT_THRESHOLD)).isNull();
  }

  @Test
  @DisplayName("缩放性：阈值改 0.9995（带基准仍 0.9887）后三等分边界随 Δ 移动，同一分数换档")
  void bandBoundariesScaleWithConfiguredThreshold() {
    double threshold = 0.9995D;
    assertThat(ImageSimilarityGate.effectiveFloor(threshold))
        .isEqualTo(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    assertThat(ImageSimilarityGate.lowBandUpperBound(threshold))
        .isCloseTo(0.9923D, within(1.0E-12D));
    assertThat(ImageSimilarityGate.highBandLowerBound(threshold))
        .isCloseTo(0.9959D, within(1.0E-12D));

    assertThat(ImageSimilarityGate.bandOf(0.991D, threshold))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(ImageSimilarityGate.bandOf(0.9925D, threshold))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(0.996D, threshold))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(threshold, threshold)).isNull();
    // 同一分数 0.992：默认阈值下是中档，0.9995 下边界上移到 0.9923 后落低档——无第二处同步点
    assertThat(ImageSimilarityGate.bandOf(0.992D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(0.992D, threshold))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
  }

  @Test
  @DisplayName("gate()：近似档按落带打 imageSimilarityBand，同图/异图不带；tier 仍为 NEAR_DUPLICATE")
  void gateTagsNearDuplicateBands() {
    List<RetrievalCandidate> gated =
        ImageSimilarityGate.gate(
            List.of(
                candidate("same", 0.9999D),
                candidate("high", 0.9935D),
                candidate("mid", 0.9918D),
                candidate("low", 0.9892D),
                candidate("cross", 0.978D),
                // near-dup-floor-lowering：0.9886 由「被过滤的异图」翻转为「准入并落 NEAR_LOW」（新准入区样本）
                candidate("admitted-low", 0.9886D)),
            DEFAULT_THRESHOLD);

    assertThat(gated)
        .extracting(item -> item.hit().chunkId())
        .containsExactly("same", "high", "mid", "low", "admitted-low");
    assertThat(gated.get(0).hit().metadata())
        .containsEntry(ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_SAME_IMAGE)
        .doesNotContainKey(ImageSimilarityGate.BAND_METADATA_KEY);
    assertThat(gated.get(1).hit().metadata())
        .containsEntry(
            ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_NEAR_DUPLICATE)
        .containsEntry(ImageSimilarityGate.BAND_METADATA_KEY, ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(gated.get(2).hit().metadata())
        .containsEntry(ImageSimilarityGate.BAND_METADATA_KEY, ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(gated.get(3).hit().metadata())
        .containsEntry(ImageSimilarityGate.BAND_METADATA_KEY, ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(gated.get(4).hit().metadata())
        .containsEntry(
            ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_NEAR_DUPLICATE)
        .containsEntry(ImageSimilarityGate.BAND_METADATA_KEY, ImageSimilarityGate.BAND_NEAR_LOW);
    // 召回期仍不改分（降权只发生在重排之后）
    assertThat(gated)
        .extracting(RetrievalCandidate::rerankScore)
        .containsExactly(0.9999D, 0.9935D, 0.9918D, 0.9892D, 0.9886D);
  }

  @Test
  @DisplayName("兼容：band 缺失/未知值回落 0.5，imageSimilarityTier 仍为 NEAR_DUPLICATE")
  void missingOrUnknownBandFallsBackToLowestWeight() {
    assertThat(ImageSimilarityGate.weightOfBand(null)).isEqualTo(0.5D);
    assertThat(ImageSimilarityGate.weightOfBand("")).isEqualTo(0.5D);
    assertThat(ImageSimilarityGate.weightOfBand("NOT_A_BAND")).isEqualTo(0.5D);
    assertThat(ImageSimilarityGate.weightOfBand(ImageSimilarityGate.BAND_NEAR_LOW)).isEqualTo(0.5D);
    assertThat(ImageSimilarityGate.weightOfBand(ImageSimilarityGate.BAND_NEAR_MID)).isEqualTo(0.6D);
    assertThat(ImageSimilarityGate.weightOfBand(ImageSimilarityGate.BAND_NEAR_HIGH))
        .isEqualTo(0.75D);

    // 旧候选（有 tier、无 band）仍降权一半，且 tier 标记不变
    List<RetrievalCandidate> downWeighted =
        ImageSimilarityGate.downWeightNearDuplicates(
            List.of(
                new RetrievalCandidate(
                    tagged("legacy", ImageSimilarityGate.TIER_NEAR_DUPLICATE), 0.8D)));
    assertThat(downWeighted.getFirst().hit().metadata())
        .containsEntry(
            ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_NEAR_DUPLICATE)
        .doesNotContainKey(ImageSimilarityGate.BAND_METADATA_KEY);
    assertThat(downWeighted.getFirst().rerankScore()).isEqualTo(0.4D);
  }

  @Test
  @DisplayName("幂等：重复降权不叠加（已施加权重标记被识别，HIGH/LOW 两档均验证）")
  void downWeightIsIdempotent() {
    List<RetrievalCandidate> lowOnce =
        ImageSimilarityGate.downWeightNearDuplicates(
            List.of(
                new RetrievalCandidate(
                    tagged(
                        "low",
                        ImageSimilarityGate.TIER_NEAR_DUPLICATE,
                        ImageSimilarityGate.BAND_NEAR_LOW),
                    0.8D)));
    assertThat(lowOnce.getFirst().rerankScore()).isEqualTo(0.4D);
    List<RetrievalCandidate> lowTwice = ImageSimilarityGate.downWeightNearDuplicates(lowOnce);
    assertThat(lowTwice.getFirst().rerankScore()).isEqualTo(0.4D);
    assertThat(lowTwice.getFirst().hit().metadata())
        .containsEntry(ImageSimilarityGate.BAND_WEIGHT_METADATA_KEY, 0.5D);

    List<RetrievalCandidate> highOnce =
        ImageSimilarityGate.downWeightNearDuplicates(
            List.of(
                new RetrievalCandidate(
                    tagged(
                        "high",
                        ImageSimilarityGate.TIER_NEAR_DUPLICATE,
                        ImageSimilarityGate.BAND_NEAR_HIGH),
                    0.8D)));
    assertThat(highOnce.getFirst().rerankScore()).isCloseTo(0.6D, within(1.0E-12D));
    assertThat(ImageSimilarityGate.downWeightNearDuplicates(highOnce).getFirst().rerankScore())
        .isCloseTo(0.6D, within(1.0E-12D));
  }

  @Test
  @DisplayName("降权后 sources 内容保持、顺序不被二次打乱：same > high > mid > low > low-2")
  void downWeightByBandKeepsSourcesAndOrder() {
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            List.of(
                hit("low-2", 0.9892D),
                hit("low", 0.99D),
                hit("mid", 0.9918D),
                hit("same", 0.9999D),
                hit("high", 0.9935D)));

    withDefaultConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    List<SourceReference> sources = result.sources();
    assertThat(sources)
        .extracting(SourceReference::chunkId)
        .containsExactly("same", "high", "mid", "low", "low-2");
    assertThat(sources)
        .extracting(SourceReference::relevanceScore)
        .isSortedAccordingTo(Comparator.reverseOrder());
    assertThat(sources.getLast().relevanceScore()).isGreaterThanOrEqualTo(0.0D);
  }

  @Test
  @DisplayName("缩放性（经主链）：阈值 0.9995 时 0.996 落 NEAR_HIGH，降权 0.75（0.6×0.75×0.3）")
  void configuredThresholdScalesBandAlongMainChain() {
    configuredThreshold(0.9995D);
    when(embeddingService.embed(anyString())).thenReturn(TEXT_VECTOR);
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    when(imageVectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(List.of(hit("502", 0.996D)));

    withConfig();
    KnowledgeRetrievalPort.RetrievalResult result = retrieve();

    assertThat(result.sources()).extracting(SourceReference::chunkId).containsExactly("502");
    assertThat(result.sources().getFirst().relevanceScore()).isCloseTo(0.135D, within(1.0E-12D));
  }

  @Test
  @DisplayName("near-dup-floor-lowering E1：准入下限 0.982 + 带基准冻结 + 内边界仍 0.9906/0.9925")
  void productionFloorLoweredBandBasisFrozen() {
    // 判据 E1（openspec/changes/near-dup-floor-lowering proposal §4，容差 1e-12，跑前写死跑后禁改）
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_FLOOR).isEqualTo(0.982D);
    assertThat(ImageSimilarityGate.BAND_REFERENCE_FLOOR).isEqualTo(0.9887D);
    assertThat(ImageSimilarityGate.effectiveFloor(DEFAULT_THRESHOLD))
        .isCloseTo(0.982D, within(1.0E-12D));
    assertThat(ImageSimilarityGate.lowBandUpperBound(DEFAULT_THRESHOLD))
        .isCloseTo(0.9906D, within(1.0E-12D));
    assertThat(ImageSimilarityGate.highBandLowerBound(DEFAULT_THRESHOLD))
        .isCloseTo(0.9925D, within(1.0E-12D));
  }

  @Test
  @DisplayName("near-dup-floor-lowering E2：13 点 (tier, band, weight) 逐点表——新准入区全 NEAR_LOW/0.5")
  void tierBandWeightTableMatchesLoweredFloor() {
    // 判据 E2：0.9819 仍异图（0.982 闭区间下沿）、0.982–0.9887 全 NEAR_LOW/0.5、0.9906 起 MID、0.9925 起 HIGH、阈值点无带
    Object[][] expected = {
      {0.978D, ImageSimilarityGate.TIER_CROSS_IMAGE, null, null},
      {0.9819D, ImageSimilarityGate.TIER_CROSS_IMAGE, null, null},
      {0.982D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_LOW, 0.5D},
      {0.985D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_LOW, 0.5D},
      {0.988D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_LOW, 0.5D},
      {0.988699D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_LOW, 0.5D},
      {0.9887D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_LOW, 0.5D},
      {0.9906D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_MID, 0.6D},
      {0.9918D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_MID, 0.6D},
      {0.9925D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_HIGH, 0.75D},
      {0.9935D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_HIGH, 0.75D},
      {0.9943D, ImageSimilarityGate.TIER_NEAR_DUPLICATE, ImageSimilarityGate.BAND_NEAR_HIGH, 0.75D},
      {0.9944D, ImageSimilarityGate.TIER_SAME_IMAGE, null, null}
    };
    for (Object[] row : expected) {
      double cosine = (Double) row[0];
      String tier = (String) row[1];
      String band = (String) row[2];
      Double weight = (Double) row[3];
      assertThat(ImageSimilarityGate.tierOf(cosine, DEFAULT_THRESHOLD))
          .as("tier: cosine=%s", cosine)
          .isEqualTo(tier);
      assertThat(ImageSimilarityGate.bandOf(cosine, DEFAULT_THRESHOLD))
          .as("band: cosine=%s", cosine)
          .isEqualTo(band);
      if (weight != null) {
        assertThat(ImageSimilarityGate.weightOfBand(band))
            .as("weight: cosine=%s", cosine)
            .isEqualTo(weight);
      }
    }
  }

  @Test
  @DisplayName("near-dup-floor-lowering E3：改前期望表以字面量冻结——[0.9887, 0.9944) 逐点零回归")
  void legacyBandExpectationsFrozenAsLiterals() {
    // 改前期望表：字面量写死于本测试（禁活代码对照自证），取自改档前 master 242dd4d 的 bandOf 行为；
    // 本测试保证「带内候选不被无声改档」（纯加性硬约束）。
    assertThat(ImageSimilarityGate.bandOf(0.9887D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(ImageSimilarityGate.bandOf(0.989D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(ImageSimilarityGate.bandOf(0.9905D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_LOW);
    assertThat(ImageSimilarityGate.bandOf(0.9906D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(0.9918D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_MID);
    assertThat(ImageSimilarityGate.bandOf(0.9925D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(0.9935D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(0.9943D, DEFAULT_THRESHOLD))
        .isEqualTo(ImageSimilarityGate.BAND_NEAR_HIGH);
    assertThat(ImageSimilarityGate.bandOf(0.9944D, DEFAULT_THRESHOLD)).isNull();
  }

  @Test
  @DisplayName("near-dup-floor-lowering E5：三档权重逐字不变 + W_max 仍 0.15")
  void weightsAndDisplacementWindowUnchanged() {
    // 判据 E5：权重与位移窗口不得随下探变动
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH).isEqualTo(0.75D);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID).isEqualTo(0.6D);
    assertThat(ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW).isEqualTo(0.5D);
    // W_max = imageRouteWeight×(1−w_low)×1.0：图像路融合权重
    // 0.30（RetrievalConfigResolver.DEFAULT_IMAGE_ROUTE_WEIGHT
    // 源码字面值，NearDupFusionImpactDecisionTest R1 实测）× 最低档降幅（1−0.5）× 重排归一化上界 1.0；
    // 被放缩的是图像路 rerank 后分，值域 [0,1]。
    double imageRouteWeight = 0.30D;
    double wMax = imageRouteWeight * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW) * 1.0D;
    assertThat(wMax).isEqualTo(0.15D);
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

  private static VectorSearchHit tagged(String chunkId, String tier, String band) {
    return new VectorSearchHit(
        chunkId,
        "doc-1",
        0.99D,
        "图像切片描述",
        Map.of(
            ImageSimilarityGate.TIER_METADATA_KEY, tier,
            ImageSimilarityGate.BAND_METADATA_KEY, band));
  }

  private static VectorSearchHit hit(String chunkId, double score) {
    return new VectorSearchHit(
        chunkId, "doc-1", score, "图像切片描述", Map.of("chunkIndex", 1, "filename", "img.png"));
  }
}
