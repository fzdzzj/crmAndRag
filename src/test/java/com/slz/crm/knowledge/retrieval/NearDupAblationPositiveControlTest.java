package com.slz.crm.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 卡 28 消融正对照（near-dup-ablation-redesign 判据 R1/R2，纯 JVM 无 Spring，surefire）。
 *
 * <p><b>装置鉴别力证明（R1，本卡命门）</b>：纯 JVM 直构三候选——{@code NEAR_DUPLICATE}+{@code NEAR_HIGH}（w=0.75）
 * 图像候选、一条文本候选、一条 {@code SAME_IMAGE} 非近似图像候选， A 跑 = {@link
 * ImageSimilarityGate#downWeightNearDuplicates} + {@link RetrievalConfigResolver#fuseRoutes}， B 跑 =
 * 只 {@code fuseRoutes}（两跑各自从同一原始候选重新开始，互不喂入）。断言该图像候选在 B（不降权）排前、A（降权）排后——两次顺序相反， 且翻转归因可验：B 跑融合分 ==
 * s×0.3（未降权）、A 跑 == (s×0.75)×0.3（降权），逐字节相等（0.225s vs 0.3s）。 正对照不翻转 ⇒ 装置无鉴别力 ⇒ 消融任何「无位移」结论无效（R1）。
 *
 * <p><b>负对照（R2）</b>：非 {@code NEAR_DUPLICATE} 档候选（文本与 {@code SAME_IMAGE} 图像）两跑融合分逐条相等， 证明降权不是把一切都改了。
 *
 * <p><b>窗口算术自洽（tasks A4）</b>：用 {@link RetrievalConfigResolver#resolveImageRouteWeight} 与 {@link
 * ImageSimilarityGate#weightOfBand} 算 {@code 0.30×(1−w)} 三档，与融合影响锚 {@code
 * near-dup-fusion-impact-v1.json} 的 {@code bounds} 常量断言一致（不一致 ⇒ 停手上报，测试红）。
 *
 * <p>{@link #runPositiveControl()} 同时是位移 IT 消融锚「正对照结论位」的同一实现（{@code NearDupDisplacementEvidenceIT}
 * 经 failsafe 调用本类 public static 方法；方向合规：tasks §0 只禁 surefire 测试引用 IT）。
 */
public class NearDupAblationPositiveControlTest {

  /** 融合影响锚（只读：窗口上界常量断言的对照面）。 */
  private static final Path FUSION_IMPACT_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-fusion-impact-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 正对照结论（纯数据；消融锚的「正对照结论位」复用同一实现落锚）。 名次一律 1 起、按融合分降序（与生产 {@code
   * sorted(comparingDouble(rerankScore).reversed())} 同口径）。
   */
  public record PositiveControlOutcome(
      boolean flipped,
      String nearHighBand,
      double nearHighWeight,
      double nearHighInputScore,
      double nonNearImageInputScore,
      double textInputScore,
      double textRouteWeight,
      double imageRouteWeight,
      double fusedNearHighWithDownWeight,
      double fusedNearHighWithoutDownWeight,
      double fusedNonNearImageWithDownWeight,
      double fusedNonNearImageWithoutDownWeight,
      double fusedTextWithDownWeight,
      double fusedTextWithoutDownWeight,
      int rankNearHighWithDownWeight,
      int rankNearHighWithoutDownWeight,
      int rankNonNearImageWithDownWeight,
      int rankNonNearImageWithoutDownWeight) {}

  /**
   * 跑正对照并返回全部测量值（纯 JVM 确定性）。 候选构造表：NEAR_HIGH 图像 s=1.0（cos 0.9930 落高档）、文本 s=0.5、 SAME_IMAGE 图像
   * s=0.98——调参使 B 跑该图像候选以 0.3×1.0=0.3 领先 0.3×0.98=0.294， 而 A 跑降权损失 0.3×(1−0.75)=0.075 大于该领先，名次必然翻转。
   */
  public static PositiveControlOutcome runPositiveControl() {
    RetrievalConfigResolver resolver = new RetrievalConfigResolver(emptyConfigProvider());
    double textRouteWeight = resolver.resolveTextRouteWeight();
    double imageRouteWeight = resolver.resolveImageRouteWeight();
    double nearHighWeight = ImageSimilarityGate.weightOfBand(ImageSimilarityGate.BAND_NEAR_HIGH);

    RetrievalCandidate nearHighImage =
        new RetrievalCandidate(
            new VectorSearchHit(
                "pc-img-near-high",
                "pc-doc-image",
                0.9930D,
                "near high band image slice",
                Map.of(
                    ImageSimilarityGate.TIER_METADATA_KEY,
                    ImageSimilarityGate.TIER_NEAR_DUPLICATE,
                    ImageSimilarityGate.BAND_METADATA_KEY,
                    ImageSimilarityGate.BAND_NEAR_HIGH)),
            1.0D);
    RetrievalCandidate text =
        new RetrievalCandidate(
            new VectorSearchHit("pc-txt-1", "pc-doc-text", 0.9900D, "text route chunk", Map.of()),
            0.5D);
    RetrievalCandidate nonNearImage =
        new RetrievalCandidate(
            new VectorSearchHit(
                "pc-img-same",
                "pc-doc-image2",
                0.9990D,
                "same image slice",
                Map.of(ImageSimilarityGate.TIER_METADATA_KEY, ImageSimilarityGate.TIER_SAME_IMAGE)),
            0.98D);

    // A 跑 = 生产降权 + 融合；B 跑 = 只融合（各自从同一原始候选重新开始，坑 4）
    List<RetrievalCandidate> fusedWithDownWeight =
        resolver.fuseRoutes(
            List.of(text),
            ImageSimilarityGate.downWeightNearDuplicates(List.of(nearHighImage, nonNearImage)));
    List<RetrievalCandidate> fusedWithoutDownWeight =
        resolver.fuseRoutes(List.of(text), List.of(nearHighImage, nonNearImage));

    String nearHighKey = nearHighImage.fusionKey();
    String nonNearKey = nonNearImage.fusionKey();
    String textKey = text.fusionKey();
    int rankNearHighWith = rankOf(fusedWithDownWeight, nearHighKey);
    int rankNearHighWithout = rankOf(fusedWithoutDownWeight, nearHighKey);
    int rankNonNearWith = rankOf(fusedWithDownWeight, nonNearKey);
    int rankNonNearWithout = rankOf(fusedWithoutDownWeight, nonNearKey);
    boolean flipped =
        rankNearHighWith > rankNonNearWith && rankNearHighWithout < rankNonNearWithout;

    return new PositiveControlOutcome(
        flipped,
        ImageSimilarityGate.BAND_NEAR_HIGH,
        nearHighWeight,
        nearHighImage.rerankScore(),
        nonNearImage.rerankScore(),
        text.rerankScore(),
        textRouteWeight,
        imageRouteWeight,
        scoreOf(fusedWithDownWeight, nearHighKey),
        scoreOf(fusedWithoutDownWeight, nearHighKey),
        scoreOf(fusedWithDownWeight, nonNearKey),
        scoreOf(fusedWithoutDownWeight, nonNearKey),
        scoreOf(fusedWithDownWeight, textKey),
        scoreOf(fusedWithoutDownWeight, textKey),
        rankNearHighWith,
        rankNearHighWithout,
        rankNonNearWith,
        rankNonNearWithout);
  }

  @Test
  @DisplayName("R1 正对照必翻转：NEAR_HIGH 图像候选 A（降权）排后、B（不降权）排前，归因 0.225s vs 0.3s")
  void positiveControlFlips() {
    PositiveControlOutcome outcome = runPositiveControl();
    assertTrue(outcome.flipped(), "正对照未翻转（装置无鉴别力，消融全部「无位移」结论无效，R1 命门）");
    assertEquals(
        outcome.nearHighInputScore() * outcome.imageRouteWeight(),
        outcome.fusedNearHighWithoutDownWeight(),
        "B 跑 NEAR_HIGH 图像候选融合分必须 == s×0.3（未降权，逐字节）");
    assertEquals(
        (outcome.nearHighInputScore() * outcome.nearHighWeight()) * outcome.imageRouteWeight(),
        outcome.fusedNearHighWithDownWeight(),
        "A 跑 NEAR_HIGH 图像候选融合分必须 == (s×0.75)×0.3（降权，逐字节）");
    double bRunLead =
        outcome.fusedNearHighWithoutDownWeight() - outcome.fusedNonNearImageWithoutDownWeight();
    double downWeightLoss =
        outcome.fusedNearHighWithoutDownWeight() - outcome.fusedNearHighWithDownWeight();
    assertTrue(
        downWeightLoss > bRunLead,
        "翻转归因：降权损失 " + downWeightLoss + " 必须大于 B 跑领先 " + bRunLead + "（0.3×(1−0.75) 乘数损失跨过 0.294）");
  }

  @Test
  @DisplayName("R2 负对照：非 NEAR_DUPLICATE 档候选（文本与 SAME_IMAGE）两跑融合分逐条相等")
  void negativeControlInvariant() {
    PositiveControlOutcome outcome = runPositiveControl();
    assertEquals(
        0,
        Double.compare(outcome.fusedTextWithDownWeight(), outcome.fusedTextWithoutDownWeight()),
        "文本候选两跑融合分必须逐字节相等（降权不得波及文本路）");
    assertEquals(
        0,
        Double.compare(
            outcome.fusedNonNearImageWithDownWeight(),
            outcome.fusedNonNearImageWithoutDownWeight()),
        "SAME_IMAGE 图像候选两跑融合分必须逐字节相等（降权只打 NEAR_DUPLICATE 档）");
  }

  @Test
  @DisplayName("A4 窗口算术自洽：0.30×(1−w) 三档与融合影响锚 bounds（0.075/0.12/0.15）一致，不一致即停")
  void windowBoundsMatchFusionImpactAnchor() throws Exception {
    RetrievalConfigResolver resolver = new RetrievalConfigResolver(emptyConfigProvider());
    double imageRouteWeight = resolver.resolveImageRouteWeight();
    JsonNode bounds = MAPPER.readTree(Files.readAllBytes(FUSION_IMPACT_ANCHOR)).path("bounds");
    assertEquals(3, bounds.size(), "融合影响锚 bounds 必须三档");
    for (JsonNode bound : bounds) {
      String band = bound.path("band").asText();
      double weight = bound.path("w").asDouble();
      double expected = bound.path("bound").asDouble();
      assertEquals(weight, ImageSimilarityGate.weightOfBand(band), 1e-12D, "档位权重与网关现值不一致: " + band);
      assertEquals(
          expected,
          imageRouteWeight * (1D - weight),
          1e-12D,
          "窗口上界 0.30×(1−w) 与融合影响锚不一致（常量漂移即停手上报）: " + band);
    }
  }

  /** 融合结果按生产口径（分数降序、稳定）排位后返回 1 起名次；缺候选即抛（装配错）。 */
  private static int rankOf(List<RetrievalCandidate> fused, String fusionKey) {
    List<RetrievalCandidate> sorted = new ArrayList<>(fused);
    sorted.sort(Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed());
    for (int index = 0; index < sorted.size(); index++) {
      if (sorted.get(index).fusionKey().equals(fusionKey)) {
        return index + 1;
      }
    }
    throw new IllegalStateException("融合结果缺候选: " + fusionKey);
  }

  /** 按 fusionKey 取融合分；缺候选即抛（装配错）。 */
  private static double scoreOf(List<RetrievalCandidate> fused, String fusionKey) {
    for (RetrievalCandidate candidate : fused) {
      if (candidate.fusionKey().equals(fusionKey)) {
        return candidate.rerankScore();
      }
    }
    throw new IllegalStateException("融合结果缺候选: " + fusionKey);
  }

  /** 空配置 provider：getIfAvailable 恒 null ⇒ 解析器落默认权重 0.70/0.30（与生产缺省一致）。 */
  private static ObjectProvider<DynamicConfigService> emptyConfigProvider() {
    return new ObjectProvider<>() {
      @Override
      public DynamicConfigService getObject() {
        return null;
      }

      @Override
      public DynamicConfigService getIfAvailable() {
        return null;
      }

      @Override
      public DynamicConfigService getIfUnique() {
        return null;
      }
    };
  }
}
