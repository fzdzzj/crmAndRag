package com.slz.crm.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.quality.ImageThresholdCalibrationFixture.Sample;
import com.slz.crm.quality.ImageThresholdCalibrator.Calibration;
import com.slz.crm.quality.ImageThresholdCalibrator.Encoding;
import com.slz.crm.quality.ImageThresholdCalibrator.Pair;
import com.slz.crm.quality.ImageThresholdCalibrator.Stratum;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 异图阈值定标口径单测（multimodal-gray-and-eval-threshold 任务 2.2，零外呼）。
 *
 * <p>锁三件事：① 定标数学（可分 / 不可分 / 空集三种边界，用手算 fake 向量）；② 语料确定性（同键同字节、图型族与实例数固定）； ③ <b>锚文件自洽</b>——{@code
 * image-threshold-calibration-v1.json} 里的阈值必须严格落在 (异图上限, 同图下限] 内， 防止有人手改锚点而不重跑（锚点只允许由 IT 真跑重写）。
 */
class ImageThresholdCalibrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 锚文件路径（与 IT 的默认落盘路径同源）。 */
  private static final Path ANCHOR = ImageThresholdCalibrationIT.DEFAULT_REPORT_PATH;

  @Test
  @DisplayName("可分：阈值 = (同图下限 + 异图上限) / 2，且严格落在区间内")
  void shouldCalibrateSeparableCorpus() {
    List<Pair> pairs = new ArrayList<>();
    pairs.add(pair("a", "a", ImageThresholdCalibrator.SAME_IMAGE, 0.9992D));
    pairs.add(pair("b", "b", ImageThresholdCalibrator.SAME_IMAGE, 0.9985D));
    pairs.add(pair("a", "b", ImageThresholdCalibrator.SAME_CLASS, 0.9810D));
    pairs.add(pair("a", "c", ImageThresholdCalibrator.CROSS_CLASS, 0.4200D));
    pairs.add(pair("b", "c", ImageThresholdCalibrator.CROSS_CLASS, 0.5000D));

    Calibration calibration = ImageThresholdCalibrator.calibrate("fake", pairs);

    assertThat(calibration.sameImageFloor()).isEqualTo(0.9985D);
    assertThat(calibration.crossImageCeiling()).isEqualTo(0.9810D);
    assertThat(calibration.separable()).isTrue();
    assertThat(calibration.recommendedThreshold())
        .isCloseTo((0.9985D + 0.9810D) / 2D, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(calibration.recommendedThreshold()).isGreaterThan(calibration.crossImageCeiling());
    assertThat(calibration.recommendedThreshold())
        .isLessThanOrEqualTo(calibration.sameImageFloor());
    assertThat(calibration.sameImage().count()).isEqualTo(2);
    assertThat(calibration.sameClass().max()).isEqualTo(0.9810D);
    assertThat(calibration.crossClass().count()).isEqualTo(2);
  }

  @Test
  @DisplayName("不可分：不给阈值（null），不硬凑一个数")
  void shouldRefuseThresholdWhenCorpusOverlaps() {
    List<Pair> pairs =
        List.of(
            pair("a", "a", ImageThresholdCalibrator.SAME_IMAGE, 0.9700D),
            pair("a", "b", ImageThresholdCalibrator.SAME_CLASS, 0.9887D));

    Calibration calibration = ImageThresholdCalibrator.calibrate("fake", pairs);

    assertThat(calibration.separable()).isFalse();
    assertThat(calibration.recommendedThreshold()).isNull();
    assertThat(calibration.sameImageFloor()).isEqualTo(0.9700D);
    assertThat(calibration.crossImageCeiling()).isEqualTo(0.9887D);
  }

  @Test
  @DisplayName("空集：零样本不失真（统计全 0，不可分）")
  void shouldHandleEmptyCorpus() {
    Calibration calibration = ImageThresholdCalibrator.calibrate("fake", List.of());

    assertThat(calibration.sameImage()).isEqualTo(new Stratum("same-image", 0, 0D, 0D, 0D));
    assertThat(calibration.separable()).isFalse();
    assertThat(calibration.recommendedThreshold()).isNull();
  }

  @Test
  @DisplayName("样本对：同图档每图一对、同类/跨类按图型族分档，跨语料不比较")
  void shouldBuildPairsByStratumAndCorpus() {
    List<Encoding> encodings =
        List.of(
            encoding("programmatic", "flow-0", "flow"),
            encoding("programmatic", "flow-1", "flow"),
            encoding("programmatic", "bars-0", "bars"),
            encoding("real-pdf", "doc-a", "doc-a"));

    List<Pair> pairs = ImageThresholdCalibrator.pairs(encodings);

    assertThat(pairs).hasSize(7);
    assertThat(pairs.stream().filter(p -> p.stratum().equals(ImageThresholdCalibrator.SAME_IMAGE)))
        .hasSize(4);
    assertThat(pairs.stream().filter(p -> p.stratum().equals(ImageThresholdCalibrator.SAME_CLASS)))
        .hasSize(1);
    assertThat(pairs.stream().filter(p -> p.stratum().equals(ImageThresholdCalibrator.CROSS_CLASS)))
        .hasSize(2);
    assertThat(pairs.stream().anyMatch(p -> p.corpus().equals("real-pdf"))).isTrue();
    assertThat(ImageThresholdCalibrator.calibrateByCorpus(pairs))
        .containsOnlyKeys("programmatic", "real-pdf");
  }

  @Test
  @DisplayName("语料确定性：同键同字节、3 图型族 × 3 实例、键唯一")
  void shouldProvideDeterministicCalibrationCorpus() {
    List<Sample> first = ImageThresholdCalibrationFixture.samples();
    List<Sample> second = ImageThresholdCalibrationFixture.samples();

    assertThat(first).hasSize(9);
    assertThat(first.stream().map(Sample::key).distinct()).hasSize(9);
    assertThat(first.stream().map(Sample::klass).distinct())
        .containsExactlyInAnyOrder("flow", "bars", "table");
    assertThat(first.stream().filter(s -> s.klass().equals("flow"))).hasSize(3);
    for (int index = 0; index < first.size(); index++) {
      assertThat(first.get(index).png()).isEqualTo(second.get(index).png());
    }
  }

  @Test
  @DisplayName("opt-in 门控：无 key 或未 opt-in 时整类跳过（零外呼）")
  void shouldSkipWhenNotOptedIn() {
    assertThrowsSkip(() -> ImageThresholdCalibrationIT.requireRealRun(Map.of(), "key"));
    assertThrowsSkip(
        () -> ImageThresholdCalibrationIT.requireRealRun(Map.of("RAG_VISION_EMBED_REAL", "1"), ""));
  }

  @Test
  @DisplayName("锚文件自洽：阈值严格落在 (异图上限, 同图下限] 内且语料块齐备")
  void shouldKeepAnchorSelfConsistent() throws Exception {
    assertThat(Files.exists(ANCHOR)).as("定标锚必须已由真跑落盘: %s", ANCHOR).isTrue();
    JsonNode root = MAPPER.readTree(Files.readString(ANCHOR));

    assertThat(root.path("calibrationVersion").asText())
        .isEqualTo(ImageThresholdCalibrator.CALIBRATION_VERSION);
    JsonNode real = root.path("corpora").path("real-pdf");
    assertThat(real.isMissingNode()).as("real-pdf 语料块缺失").isFalse();
    assertThat(real.path("separable").asBoolean()).isTrue();
    double floor = real.path("sameImageFloor").asDouble();
    double ceiling = real.path("crossImageCeiling").asDouble();
    double threshold = root.path("threshold").path("recommendedThreshold").asDouble();
    assertThat(ceiling).isLessThan(floor);
    assertThat(threshold).isGreaterThan(ceiling).isLessThanOrEqualTo(floor);
    assertThat(threshold).isEqualTo(real.path("recommendedThreshold").asDouble());
    assertThat(root.path("pairs")).isNotEmpty();
    assertThat(root.path("pairs").size())
        .isGreaterThanOrEqualTo(real.path("sameImage").path("count").asInt());
  }

  private static void assertThrowsSkip(org.junit.jupiter.api.function.Executable executable) {
    assertThat(
            org.junit.jupiter.api.Assertions.assertThrows(
                org.opentest4j.TestAbortedException.class, executable))
        .isNotNull();
  }

  private static Pair pair(String left, String right, String stratum, double cosine) {
    return new Pair("fake", left, right, stratum, cosine);
  }

  private static Encoding encoding(String corpus, String key, String klass) {
    float[] base = new float[] {1F, 0F, 0F};
    return new Encoding(corpus, key, klass, base, Arrays.copyOf(base, base.length));
  }
}
