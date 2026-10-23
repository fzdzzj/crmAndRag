package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NearDuplicateCorpus} 的零外呼单测（calibrate-near-dup-bands 任务 1.3）。
 *
 * <p>守四条：<b>计数自洽</b>（base / variant / skip / images 互相闭合）、<b>确定性</b>（同 base 两次生成字节相等，无随机源/时间戳）、
 * <b>可回指</b>（每个变体都能追到 kind 与 baseKey）、<b>变体确有扰动</b>（每变体 SHA-256 与 base 不同——否则「同源近重复」不成立）。
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
    assertEquals(PLANNED_BASES, bases, "base 数必须等于计划值（去重后不足说明语料被替换或提取器口径变了）");
    assertEquals(
        bases * NearDuplicateCorpus.KINDS.size(),
        variants + skips,
        "每个 base 必须产出 KINDS.size() 个变体名额（产出 + 跳过）");
    assertEquals(bases + variants, current.imageCount(), "images 必须 = base + 变体");
    assertEquals(144, current.imageCount(), "图总数必须是 144（24 base + 120 变体）");

    int perDocument = 0;
    for (NearDuplicateCorpus.DocumentCount document : current.documents()) {
      assertTrue(document.taken() <= document.requested(), "逐文档取图数不得超过计划上界");
      assertTrue(document.taken() > 0, "登记在册的文档不得一张图都取不到: " + document.docKey());
      perDocument += document.taken();
    }
    assertEquals(bases, perDocument, "逐文档取图数之和必须等于 base 数（去重是全局的）");
    assertEquals(NearDuplicateCorpus.plan().size(), current.documents().size(), "文档数与计划文档数不一致");
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
