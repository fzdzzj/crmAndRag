package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NearDuplicateCorpus} 的零外呼单测（calibrate-near-dup-bands 任务 1.3 + 9.3）。
 *
 * <p>守六条：<b>计数自洽</b>（base / variant / skip / 剔除 / images 互相闭合）、<b>确定性</b>（同 base
 * 两次生成字节相等，无随机源/时间戳）、 <b>可回指</b>（每个变体都能追到 kind 与 baseKey）、<b>变体确有扰动</b>（每变体 SHA-256 与 base
 * 不同）、<b>防退化①</b>（每 base 与每变体 白底合成后唯一色数 &gt; 1）、<b>防退化②</b>（同 base 五变体两两 SHA-256 不同）。
 *
 * <p><b>为什么要有防退化两条</b>：首轮真跑 24 个 base 里 6 个的五类变体余弦逐位全等——栅格化没铺白底，alpha 源被合成成黑，五类变体坍缩成同一张
 * 纯色图，近重复对退化成同图对，还差点据此把 {@code NEAR_DUPLICATE_FLOOR} 下调到 0.42（见 {@code proposal.md} §9）。这两条断言让同类退化
 * 在<b>默认测试</b>里就红，不必花一次外呼才发现。
 */
@DisplayName("近重复定标语料（零外呼）")
class NearDuplicateCorpusTest {

  /** 计划取图数之和：8 + 4 + 12（去重后实际值由本测试实测断言，不凑数）。 */
  private static final int PLANNED_BASES = 24;

  private static NearDuplicateCorpus.Corpus corpus;

  @BeforeAll
  static void loadCorpus() {
    corpus = NearDuplicateCorpus.generate();
  }

  @Test
  @DisplayName("计数自洽：base / variant / skip / images 互相闭合")
  void countsAreSelfConsistent() {
    NearDuplicateCorpus.Corpus current = corpus;
    int bases = current.bases().size();
    int variants = current.variants().size();
    int skips = current.skips().size();

    assertTrue(bases > 0, "base 数为 0：真实 PDF 资产或生产提取器异常");
    assertEquals(
        PLANNED_BASES,
        bases + current.excludedBases().size(),
        "保留 base + 剔除的退化 base 必须等于计划值（去重后不足说明语料被替换或提取器口径变了）");
    assertEquals(
        bases * NearDuplicateCorpus.KINDS.size(),
        variants + skips,
        "每个 base 必须产出 KINDS.size() 个变体名额（产出 + 跳过）");
    assertEquals(bases + variants, current.imageCount(), "images 必须 = base + 变体");
    assertEquals(
        bases * (NearDuplicateCorpus.KINDS.size() + 1),
        current.imageCount(),
        "图总数必须 = base × (1 + 变体类型数)（计数关系式，不写死魔法数）");

    int perDocument = 0;
    for (NearDuplicateCorpus.DocumentCount document : current.documents()) {
      assertTrue(document.taken() <= document.requested(), "逐文档取图数不得超过计划上界");
      assertTrue(document.taken() > 0, "登记在册的文档不得一张图都取不到: " + document.docKey());
      perDocument += document.taken();
    }
    assertEquals(bases, perDocument, "逐文档取图数之和必须等于 base 数（去重是全局的）");
    assertEquals(NearDuplicateCorpus.plan().size(), current.documents().size(), "文档数与计划文档数不一致");
    System.out.println(
        "[near-dup-corpus] bases="
            + bases
            + " variants="
            + variants
            + " skips="
            + skips
            + " excludedBases="
            + current.excludedBases().size()
            + " images="
            + current.imageCount()
            + "（计划 "
            + PLANNED_BASES
            + " 张 base）");
    for (NearDuplicateCorpus.ExcludedBase excluded : current.excludedBases()) {
      System.out.println(
          "[near-dup-corpus] 剔除 "
              + excluded.baseKey()
              + " uniqueColors="
              + excluded.uniqueColors());
    }
  }

  @Test
  @DisplayName("确定性：同 base 两次生成字节相等（无随机源、无时间戳）")
  void generationIsDeterministic() {
    NearDuplicateCorpus.Corpus second = NearDuplicateCorpus.generate();
    assertEquals(corpus.bases().size(), second.bases().size(), "两次生成的 base 数不一致（不可复现）");
    assertEquals(corpus.variants().size(), second.variants().size(), "两次生成的变体数不一致（不可复现）");
    for (int index = 0; index < corpus.bases().size(); index++) {
      NearDuplicateCorpus.Base left = corpus.bases().get(index);
      NearDuplicateCorpus.Base right = second.bases().get(index);
      assertEquals(left.key(), right.key(), "base 顺序必须稳定");
      assertArrayEquals(left.png(), right.png(), "base 字节必须稳定: " + left.key());
    }
    for (int index = 0; index < corpus.variants().size(); index++) {
      NearDuplicateCorpus.Variant left = corpus.variants().get(index);
      NearDuplicateCorpus.Variant right = second.variants().get(index);
      assertEquals(left.key(), right.key(), "变体顺序必须稳定");
      assertArrayEquals(left.png(), right.png(), "变体字节必须稳定（不可复现即不可定标）: " + left.key());
    }
  }

  @Test
  @DisplayName("变体确有扰动：每变体 SHA-256 与 base 不同")
  void everyVariantDiffersFromItsBase() {
    Map<String, String> baseDigest = new LinkedHashMap<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      baseDigest.put(base.key(), RealPdfAssets.sha256(base.png()));
    }
    Set<String> variantDigests = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      String digest = RealPdfAssets.sha256(variant.png());
      String base = baseDigest.get(variant.baseKey());
      assertNotNull(base, "变体的 baseKey 必须指向真实 base: " + variant.key());
      assertFalse(base.equals(digest), "变体与 base 字节相同（变换没有生效，近重复对会退化成同图对）: " + variant.key());
      assertTrue(variantDigests.add(digest), "变体之间不得出现字节重复: " + variant.key());
    }
  }

  @Test
  @DisplayName("可回指：kind 与 baseKey 能追回 base，键前缀即 baseKey")
  void everyVariantPointsBackToItsBase() {
    Set<String> baseKeys = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      assertTrue(baseKeys.add(base.key()), "base 键必须唯一: " + base.key());
      assertTrue(base.png().length > 0, "base PNG 不得为空: " + base.key());
      assertTrue(base.width() > 0 && base.height() > 0, "base 尺寸必须为正: " + base.key());
      assertEquals(
          NearDuplicateCorpus.key(base.docKey(), base.pageNo(), base.index()),
          base.key(),
          "base 键必须由 docKey/pageNo/index 唯一决定");
    }
    Set<String> kindsSeen = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      assertTrue(NearDuplicateCorpus.KINDS.contains(variant.kind()), "未知变体类型: " + variant.kind());
      kindsSeen.add(variant.kind());
      assertTrue(baseKeys.contains(variant.baseKey()), "变体 baseKey 不存在: " + variant.key());
      assertEquals(
          variant.baseKey() + "@" + variant.kind(),
          variant.key(),
          "变体键必须 = baseKey@kind（锚里的前缀断言依赖这一点）");
      assertTrue(variant.width() > 0 && variant.height() > 0, "变体尺寸必须为正: " + variant.key());
      assertTrue(
          Math.min(variant.width(), variant.height()) >= NearDuplicateCorpus.MIN_EDGE_PX,
          "落库变体的最短边不得低于下限: " + variant.key());
    }
    assertEquals(NearDuplicateCorpus.KINDS.size(), kindsSeen.size(), "五类变体必须每类都有产出（有跳过则说明语料太小）");
  }

  @Test
  @DisplayName("跳过登记：每条跳过都能回指 base 与 kind，且不重复")
  void skipsAreRegistered() {
    Set<String> baseKeys = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      baseKeys.add(base.key());
    }
    Set<String> seen = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Skip skip : corpus.skips()) {
      assertTrue(baseKeys.contains(skip.baseKey()), "跳过记录的 baseKey 不存在: " + skip.baseKey());
      assertTrue(NearDuplicateCorpus.KINDS.contains(skip.kind()), "跳过记录的 kind 未知: " + skip.kind());
      assertTrue(skip.reason() != null && !skip.reason().isBlank(), "跳过必须写明原因");
      assertTrue(
          Math.min(skip.width(), skip.height()) < NearDuplicateCorpus.MIN_EDGE_PX,
          "跳过原因必须是「最短边不足」，否则属于静默丢弃: " + skip.baseKey() + "@" + skip.kind());
      assertTrue(seen.add(skip.baseKey() + "@" + skip.kind()), "跳过记录不得重复");
    }
  }

  @Test
  @DisplayName("防退化①：每 base 与每变体白底合成后唯一色数 > 1，退化 base 必须登记剔除")
  void pixelContentIsNotDegenerate() {
    assertEquals(
        corpus.bases().size(), corpus.basePixels().size(), "逐 base 像素属性必须与保留 base 一一对应（不多不少）");
    Set<String> baseKeys = new LinkedHashSet<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      baseKeys.add(base.key());
    }
    for (NearDuplicateCorpus.BasePixel pixel : corpus.basePixels()) {
      assertTrue(baseKeys.contains(pixel.baseKey()), "像素属性回指的 base 不存在: " + pixel.baseKey());
      assertFalse(pixel.colorModel().isBlank(), "必须记录源 PNG 的颜色模型: " + pixel.baseKey());
      System.out.println(
          "[near-dup-corpus] 像素 "
              + pixel.baseKey()
              + " model="
              + pixel.colorModel()
              + " hasAlpha="
              + pixel.hasAlpha()
              + " uniqueColors="
              + pixel.uniqueColors()
              + " mean="
              + pixel.mean()
              + " stdDev="
              + pixel.stdDev());
      assertTrue(
          pixel.uniqueColors() > 1,
          "base 白底合成后像素全同（唯一色数 = "
              + pixel.uniqueColors()
              + "）：无可识别图像内容，其五类变体必然坍缩成同图，近重复对会退化成同图对: "
              + pixel.baseKey());
      assertTrue(
          pixel.mean() >= 0D && pixel.mean() <= 255D, "亮度均值必须落在 [0, 255]: " + pixel.baseKey());
      assertTrue(pixel.stdDev() >= 0D, "亮度标准差不得为负: " + pixel.baseKey());
    }
    for (NearDuplicateCorpus.ExcludedBase excluded : corpus.excludedBases()) {
      assertTrue(excluded.uniqueColors() <= 1, "剔除理由必须是「唯一色数 ≤ 1」，否则属于误剔: " + excluded.baseKey());
      assertFalse(excluded.reason().isBlank(), "剔除必须写明原因: " + excluded.baseKey());
      assertFalse(
          baseKeys.contains(excluded.baseKey()), "被剔除的 base 不得同时出现在语料里: " + excluded.baseKey());
    }
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      NearDuplicateCorpus.BasePixel pixel = pixels(variant.key(), variant.png());
      assertTrue(
          pixel.uniqueColors() > 1,
          "变体白底合成后像素全同（唯一色数 = "
              + pixel.uniqueColors()
              + "）：该变体不含可识别图像内容，本轮的「五变体余弦逐位全等」就是这样发生的: "
              + variant.key());
    }
  }

  @Test
  @DisplayName("防退化②：同 base 的五类变体两两 SHA-256 不同（五类变换不得坍缩成同图）")
  void variantsOfEachBaseArePairwiseDistinct() {
    Map<String, Map<String, String>> digestsByBase = new LinkedHashMap<>();
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      digestsByBase
          .computeIfAbsent(variant.baseKey(), key -> new LinkedHashMap<>())
          .put(variant.kind(), RealPdfAssets.sha256(variant.png()));
    }
    assertEquals(
        corpus.bases().size(), digestsByBase.size(), "每个保留 base 都必须产出变体（有 base 无变体说明生成中断）");
    for (Map.Entry<String, Map<String, String>> entry : digestsByBase.entrySet()) {
      Map<String, String> digests = entry.getValue();
      assertEquals(
          NearDuplicateCorpus.KINDS.size(), digests.size(), "每个 base 的五类变体必须齐全: " + entry.getKey());
      assertEquals(
          NearDuplicateCorpus.KINDS.size(),
          new LinkedHashSet<>(digests.values()).size(),
          "同 base 五变体的 SHA-256 必须两两不同（出现重复即变换坍缩成同图，近重复对不再成立）: " + entry.getKey());
    }
  }

  /** 解码 PNG 后按白底合成统计像素属性（与 {@link NearDuplicateCorpus} 内部同一口径）。 */
  private static NearDuplicateCorpus.BasePixel pixels(String key, byte[] png) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      assertNotNull(image, "PNG 必须可解码: " + key);
      return NearDuplicateCorpus.pixelsOf(key, image);
    } catch (IOException exception) {
      throw new IllegalStateException("PNG 解码失败: " + key, exception);
    }
  }

  @Test
  @DisplayName("无网络 / 无时钟依赖：字段里不出现 java.net / Clock / Random")
  void hasNoNetworkOrClockDependency() {
    List<String> offending = new ArrayList<>();
    for (Field field : NearDuplicateCorpus.class.getDeclaredFields()) {
      String type = field.getType().getName();
      if (type.startsWith("java.net.")
          || type.equals("java.time.Clock")
          || type.startsWith("java.util.Random")
          || type.startsWith("java.security.SecureRandom")) {
        offending.add(Modifier.toString(field.getModifiers()) + " " + type + " " + field.getName());
      }
    }
    assertTrue(offending.isEmpty(), "语料生成不得依赖网络/时钟/随机源: " + offending);
  }
}
