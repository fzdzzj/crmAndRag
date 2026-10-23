package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NearDuplicateMechanismAnalyzer} / {@link PerceptualHashSupport} 的手算单测（G1 任务 1.2.3 +
 * 1.3.6）。
 *
 * <p><b>为什么 dHash 单测并进本类</b>：proposal §1.4 与 task-card 的写入面并集都<b>没有</b> {@code
 * PerceptualHashSupportTest} 这个文件（tasks.md 1.2.3 曾点名），为不越写入面，感知哈希的确定性/距离断言并入本类的 {@code dHash*}
 * 用例组，覆盖内容一字不减。
 *
 * <p><b>手算口径</b>：合成图一律 9×8 像素（= dHash 的目标格数），每格恰好对应一个像素，格灰度即像素灰度，64 位差分可手推。
 */
class NearDuplicateMechanismAnalyzerTest {

  /** 9×8 = dHash 的目标格数：此尺寸下每个格 = 一个像素，格值可手算。 */
  private static final int WIDTH = PerceptualHashSupport.COLUMNS;

  private static final int HEIGHT = PerceptualHashSupport.ROWS;

  @Test
  @DisplayName("dHash-64：同图两次距离 0、递增灰度全 0、递减灰度全 1、纯色全 0")
  void dHashIsDeterministicOnDesignedRasters() {
    BufferedImage rising = ramp();
    BufferedImage falling = descendingRamp();
    long risingHash = PerceptualHashSupport.dHash(rising);
    assertEquals(0L, risingHash, "灰度递增（左 < 右）应得全 0 哈希");
    assertEquals(-1L, PerceptualHashSupport.dHash(falling), "灰度递减（左 > 右）应得全 1 哈希");
    assertEquals(risingHash, PerceptualHashSupport.dHash(rising), "同图两次必须同值");
    assertEquals(0, PerceptualHashSupport.hamming(risingHash, risingHash), "同图距离必须 0");
    assertEquals(
        64, PerceptualHashSupport.hamming(risingHash, PerceptualHashSupport.dHash(falling)));
    assertEquals(
        1.0D,
        PerceptualHashSupport.distanceRatio(risingHash, PerceptualHashSupport.dHash(falling)));
    assertEquals(0L, PerceptualHashSupport.dHash(solid(0x80)), "纯色图无相邻差分，应为全 0");
  }

  @Test
  @DisplayName("dHash-64：1 bit 翻转距离 1；2× 面积放大距离 0；首行 5 格降序距离 5")
  void dHashToleratesRescaleAndCountsFlips() {
    BufferedImage rising = ramp();
    long base = PerceptualHashSupport.dHash(rising);
    BufferedImage flipped = copyOf(rising);
    flipped.setRGB(0, 0, gray(255));
    long flippedHash = PerceptualHashSupport.dHash(flipped);
    assertEquals(1, PerceptualHashSupport.hamming(base, flippedHash), "只翻第 0 对相邻格 ⇒ 距离 1");
    assertEquals(1L, flippedHash, "翻转的正是 bit 0");
    BufferedImage doubled = upscaleX2(rising);
    assertEquals(
        0,
        PerceptualHashSupport.hamming(base, PerceptualHashSupport.dHash(doubled)),
        "2× 面积放大后每格均值不变 ⇒ 距离 0");
    BufferedImage five = fiveDescendingCells();
    assertEquals(31L, PerceptualHashSupport.dHash(five), "首行 5 格严格降序 ⇒ bit 0..4");
    assertEquals(
        5,
        PerceptualHashSupport.hamming(base, PerceptualHashSupport.dHash(five)),
        "首行 5 格降序 ⇒ 恰好 5 位不同");
  }

  @Test
  @DisplayName("六图三对手算：M1/M2/M3 逐点保留数与泄漏数与手算一致")
  void threeMechanismsMatchHandComputedExpectations() {
    BufferedImage a1 = ramp();
    BufferedImage a2 = copyOf(a1);
    BufferedImage b1 = fiveDescendingCells();
    BufferedImage b2 = ramp();
    BufferedImage c1 = descendingRamp();
    BufferedImage d1 = copyOf(a1);
    Map<String, BufferedImage> images = new LinkedHashMap<>();
    images.put("img-a1", a1);
    images.put("img-a2", a2);
    images.put("img-b1", b1);
    images.put("img-b2", b2);
    images.put("img-c1", c1);
    images.put("img-d1", d1);
    Map<String, Long> hashes = hashes(images);

    List<NearDuplicateMechanismAnalyzer.Pair> pairs = new ArrayList<>();
    // 三对同源：dHash 距离 0（a1/a2）、5（b1/b2）、64（c1/d1）
    pairs.add(pair("img-a1", "img-a2", "near-duplicate", "scale-085", 0.995D));
    pairs.add(pair("img-b1", "img-b2", "near-duplicate", "crop-04", 0.990D));
    pairs.add(pair("img-c1", "img-d1", "near-duplicate", "crop-12", 0.500D));
    // 负样本：距离 64（余弦 0.30，永不泄漏）与距离 0（余弦 0.40，任何 dHash 阈值都泄漏）
    pairs.add(pair("img-c1", "img-a1", "cross-class", null, 0.300D));
    pairs.add(pair("img-a1", "img-d1", "cross-class", null, 0.400D));

    NearDuplicateMechanismAnalyzer.Outcome baseline =
        NearDuplicateMechanismAnalyzer.evaluate(
            pairs, hashes, NearDuplicateMechanismAnalyzer.Mechanism.FLOOR, 0.9887D, 0, 0);
    assertEquals(3, baseline.variantTotal());
    assertEquals(2, baseline.retainedVariant(), "0.995 与 0.990 保留，0.500 丢弃");
    assertEquals(2, baseline.negativeTotal());
    assertEquals(0, baseline.leakedNegative(), "两条负样本余弦均 < 0.9887，不应泄漏");
    assertEquals(1, baseline.retainedByKind().get("scale-085").intValue());
    assertEquals(1, baseline.retainedByKind().get("crop-04").intValue());
    assertEquals(0, baseline.retainedByKind().getOrDefault("crop-12", 0).intValue());

    NearDuplicateMechanismAnalyzer.Outcome dhashFive =
        NearDuplicateMechanismAnalyzer.evaluate(
            pairs, hashes, NearDuplicateMechanismAnalyzer.Mechanism.DHASH, 0D, 5, 5);
    assertEquals(2, dhashFive.retainedVariant(), "距离 0 与 5 保留，64 丢弃");
    assertEquals(1, dhashFive.leakedNegative(), "距离 0 的负样本在 D=5 下泄漏");
    NearDuplicateMechanismAnalyzer.Outcome dhashFour =
        NearDuplicateMechanismAnalyzer.evaluate(
            pairs, hashes, NearDuplicateMechanismAnalyzer.Mechanism.DHASH, 0D, 4, 4);
    assertEquals(1, dhashFour.retainedVariant(), "D=4 时距离 5 的同源对被丢弃");
    assertEquals(1, dhashFour.leakedNegative());

    NearDuplicateMechanismAnalyzer.Outcome hybrid =
        NearDuplicateMechanismAnalyzer.evaluate(
            pairs, hashes, NearDuplicateMechanismAnalyzer.Mechanism.HYBRID, 0D, 0, 5);
    assertEquals(2, hybrid.retainedVariant(), "同源对：距离 0 → 同图；距离 5 → 近似带；64 → 丢弃");
    assertEquals(1, hybrid.leakedNegative());
    assertEquals(2, hybrid.sameImageClassified(), "距离 0 的同源对 + 距离 0 的负样本 = 2 判同图");
  }

  @Test
  @DisplayName("键对齐 fail-fast：少一张图必须抛错并报出键名")
  void missingImageKeyFailsFast() {
    Map<String, BufferedImage> images = new LinkedHashMap<>();
    images.put("only-key", ramp());
    List<NearDuplicateMechanismAnalyzer.Pair> pairs = new ArrayList<>();
    pairs.add(pair("only-key", "missing-key", "near-duplicate", "crop-04", 0.99D));
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> NearDuplicateMechanismAnalyzer.requireAligned(pairs, images.keySet()));
    assertTrue(failure.getMessage().contains("missing-key"), "必须报出缺失的键名: " + failure.getMessage());
  }

  @Test
  @DisplayName("floor 网格 200 点、步长 0.0005、上下界 0.90/0.9995；C3 单调性按判定链")
  void floorGridAndMonotonicityFollowTheSpec() {
    assertEquals(200, NearDuplicateMechanismAnalyzer.FLOOR_GRID_POINTS);
    assertEquals(0.90D, NearDuplicateMechanismAnalyzer.floorAt(0), 1e-12D);
    assertEquals(0.9995D, NearDuplicateMechanismAnalyzer.floorAt(199), 1e-12D);
    assertEquals(
        0.0005D,
        NearDuplicateMechanismAnalyzer.floorAt(1) - NearDuplicateMechanismAnalyzer.floorAt(0),
        1e-12D);
    List<NearDuplicateMechanismAnalyzer.Pair> empty = List.of();
    assertEquals(200, NearDuplicateMechanismAnalyzer.scanFloors(empty).size());
    assertEquals(25, NearDuplicateMechanismAnalyzer.scanDHash(empty, Map.of()).size());
    assertEquals(325, NearDuplicateMechanismAnalyzer.scanHybrid(empty, Map.of()).size());
    assertEquals("SAME_IMAGE", NearDuplicateMechanismAnalyzer.tierOfCosine(0.9944D, 0.9887D));
    assertEquals("NEAR_DUPLICATE", NearDuplicateMechanismAnalyzer.tierOfCosine(0.9887D, 0.9887D));
    assertEquals("CROSS_IMAGE", NearDuplicateMechanismAnalyzer.tierOfCosine(0.9886D, 0.9887D));

    Map<String, Integer> retained = new LinkedHashMap<>();
    retained.put("crop-12", 5);
    retained.put("crop-04", 10);
    retained.put("scale-070", 15);
    retained.put("scale-085", 20);
    retained.put("jpeg-q80", 21);
    NearDuplicateMechanismAnalyzer.Outcome monotonic =
        new NearDuplicateMechanismAnalyzer.Outcome(24, 20, 24, 0, Map.of(), retained, 0);
    assertTrue(
        monotonic.satisfiesC3(), "crop-12 ≤ crop-04 ≤ scale-070 ≤ scale-085 且 jpeg ≥ scale-070");
    Map<String, Integer> reversed = new LinkedHashMap<>(retained);
    reversed.put("scale-070", 25);
    NearDuplicateMechanismAnalyzer.Outcome broken =
        new NearDuplicateMechanismAnalyzer.Outcome(24, 20, 24, 0, Map.of(), reversed, 0);
    assertFalse(broken.satisfiesC3(), "scale-070 超过 scale-085 必须判不单调");
  }

  @Test
  @DisplayName("判据常量原样钉死（0.90 / 0.02 / 200 点 / 0..24 / 三选一）")
  void criteriaConstantsArePinned() {
    assertEquals(0.90D, NearDuplicateMechanismAnalyzer.C1_MIN_RETENTION);
    assertEquals(0.02D, NearDuplicateMechanismAnalyzer.C2_MAX_LEAKAGE);
    assertEquals(0.0005D, NearDuplicateMechanismAnalyzer.FLOOR_GRID_STEP);
    assertEquals(0, NearDuplicateMechanismAnalyzer.DHASH_MIN);
    assertEquals(24, NearDuplicateMechanismAnalyzer.DHASH_MAX);
    assertEquals(0.9944D, NearDuplicateMechanismAnalyzer.THRESHOLD);
    assertEquals(0.9887D, NearDuplicateMechanismAnalyzer.COSINE_FLOOR);
    assertEquals(
        List.of("GO", "NO-GO", "UNDECIDABLE-BY-DATA"),
        List.of(
            NearDuplicateMechanismAnalyzer.VERDICT_GO,
            NearDuplicateMechanismAnalyzer.VERDICT_NO_GO,
            NearDuplicateMechanismAnalyzer.VERDICT_UNDECIDABLE));
  }

  private static NearDuplicateMechanismAnalyzer.Pair pair(
      String leftKey, String rightKey, String stratum, String kind, double cosine) {
    return new NearDuplicateMechanismAnalyzer.Pair(leftKey, rightKey, stratum, kind, cosine);
  }

  private static Map<String, Long> hashes(Map<String, BufferedImage> images) {
    Map<String, Long> hashes = new LinkedHashMap<>();
    images.forEach((key, image) -> hashes.put(key, PerceptualHashSupport.dHash(image)));
    return hashes;
  }

  /** 灰度递增（{@code 左 < 右}）的 9×8 灰阶图：所有差分位为 0。 */
  private static BufferedImage ramp() {
    return raster((y, x) -> x * 10);
  }

  /** 灰度递减（{@code 左 > 右}）的 9×8 灰阶图：所有差分位为 1。 */
  private static BufferedImage descendingRamp() {
    return raster((y, x) -> (WIDTH - 1 - x) * 10);
  }

  /**
   * 首行严格降序 {@code 255,200,150,100,50,40,70,90,110}、其余行仍递增：第 0 行前 5 对相邻格为真、后 3 对为假，故哈希 = {@code
   * 0b11111} = 31。
   */
  private static BufferedImage fiveDescendingCells() {
    int[] firstRow = {255, 200, 150, 100, 50, 40, 70, 90, 110};
    return raster((y, x) -> y == 0 ? firstRow[x] : x * 10);
  }

  /** 纯色图。 */
  private static BufferedImage solid(int value) {
    return raster((y, x) -> value);
  }

  private static BufferedImage raster(IntBinaryOperator lumaAt) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        image.setRGB(x, y, gray(lumaAt.applyAsInt(y, x)));
      }
    }
    return image;
  }

  /** 面积放大 2×（像素复制，格均值不变）。 */
  private static BufferedImage upscaleX2(BufferedImage source) {
    BufferedImage scaled =
        new BufferedImage(
            source.getWidth() * 2, source.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < scaled.getHeight(); y++) {
      for (int x = 0; x < scaled.getWidth(); x++) {
        scaled.setRGB(x, y, source.getRGB(x / 2, y / 2));
      }
    }
    return scaled;
  }

  private static BufferedImage copyOf(BufferedImage source) {
    BufferedImage copy =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < source.getHeight(); y++) {
      for (int x = 0; x < source.getWidth(); x++) {
        copy.setRGB(x, y, source.getRGB(x, y));
      }
    }
    return copy;
  }

  private static int gray(int luma) {
    int clamped = Math.max(0, Math.min(255, luma));
    return (clamped << 16) | (clamped << 8) | clamped;
  }
}
