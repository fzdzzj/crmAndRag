package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 承载判定单测（fix-citation-grading-overlap 任务 1.1/1.2/1.4/2.1，红测先行）。
 *
 * <p>判卷口径受控解冻的机械锁：黄金承载集 = 位置区间承载黄金句 span 的全部块（区间交集 ≥ {@link
 * RagBenchmarkDataPreparer#MIN_CARRIAGE_CHARS}），三形态（重叠复本承载 / 跨块段落承载 / 无承载零误报）+ MIN_CARRIAGE 边界 +
 * 锚点恒在集内 + 向量输入红线。承载集只供 citP（见 {@code RagBenchmarkCarrierScoringTest}），recall/ansC 口径不动。
 */
class RagBenchmarkCarrierTest {

  private static final long EVAL_KB_ID = 99001L;

  /** 与 RagBenchmarkDataPreparerTest 同款确定性 fake 向量（不外呼）。 */
  private static float[] fakeEmbed(String text) {
    float[] vector = new float[64];
    for (int i = 0; i < text.length(); i++) {
      vector[(text.charAt(i) * 31 + i) % vector.length] += 1f;
    }
    if (text.isEmpty()) {
      vector[0] = 1f;
    }
    return vector;
  }

  private static RagBenchmarkDataPreparer.Preparation prepare() {
    return RagBenchmarkDataPreparer.prepare(
        new InMemoryVectorStore(), RagBenchmarkCarrierTest::fakeEmbed, EVAL_KB_ID);
  }

  /** 取证 v4 T-02 实锤形态：黄金句尾部在邻块头部的重叠复本——邻块必须入承载集（B1 判卷低估的第一形态）。 */
  @Test
  void overlapReplicaChunkCarriesGoldenSentence() {
    RagBenchmarkDataPreparer.Preparation preparation = prepare();
    String anchor = preparation.goldenToChunkId().get("payment-plan-1");
    assertTrue("payment-plan-0".equals(anchor), "payment-plan-1 归点应为 payment-plan-0: " + anchor);
    Set<String> carriers = preparation.goldenToCarrierChunkIds().get("payment-plan-1");
    assertTrue(carriers.contains("payment-plan-0"), "锚点块必须在承载集内: " + carriers);
    assertTrue(
        carriers.contains("payment-plan-1"),
        "重叠复本块 payment-plan-1 必须承载黄金句（取证 v4 §3 T-02 B1 形态）: " + carriers);
  }

  /** 取证 v4 T-15 场景：标记撕裂致归点与语义主体分离——承载集按同一区间规则涵盖语义主体块（B3 顺带缓解）。 */
  @Test
  void tornMarkerSemanticBodyChunkIsCarried() {
    RagBenchmarkDataPreparer.Preparation preparation = prepare();
    String anchor = preparation.goldenToChunkId().get("customer-onboard-3");
    // b2-suite-recalibration 任务 1.2：T-15 标记移至报备句主体段（customer-sop-3）后，锚归点顺应从 customer-sop-2 移至
    // customer-sop-3；承载集含锚点块。
    assertEquals("customer-sop-3", anchor, "T-15 归点应为 customer-sop-3（包含报备句主体）");
    Set<String> carriers = preparation.goldenToCarrierChunkIds().get("customer-onboard-3");
    assertTrue(carriers.contains("customer-sop-3"), "锚点块必须在承载集内: " + carriers);
  }

  /** 零误报形态：句尾远离切分边界的黄金，承载集恰为锚点单块（防承载集虚增拉低 citP 判别力）。 */
  @Test
  void goldenFarFromBoundaryCarriesAnchorOnly() {
    RagBenchmarkDataPreparer.Preparation preparation = prepare();
    String anchor = preparation.goldenToChunkId().get("maint-xr500-q");
    Set<String> carriers = preparation.goldenToCarrierChunkIds().get("maint-xr500-q");
    assertEquals(Set.of(anchor), carriers, "短行黄金承载集必须恰为锚点单块: " + carriers);
  }

  /** 承载集全局健康度：每个黄金集非空且含锚点；承载块与锚点同语料（不跨语料虚增）。 */
  @Test
  void carrierSetsNonEmptyContainAnchorAndStayInFixture() {
    RagBenchmarkDataPreparer.Preparation preparation = prepare();
    assertEquals(
        preparation.goldenToChunkId().keySet(),
        preparation.goldenToCarrierChunkIds().keySet(),
        "承载集映射键必须与黄金映射键一一对应");
    for (Map.Entry<String, String> entry : preparation.goldenToChunkId().entrySet()) {
      Set<String> carriers = preparation.goldenToCarrierChunkIds().get(entry.getKey());
      assertFalse(carriers.isEmpty(), "承载集不得为空: " + entry.getKey());
      assertTrue(
          carriers.contains(entry.getValue()),
          "锚点块恒在承载集内（design D2）: " + entry.getKey() + " -> " + carriers);
      String fixturePrefix = entry.getValue().substring(0, entry.getValue().lastIndexOf('-'));
      for (String carrier : carriers) {
        assertTrue(
            carrier.startsWith(fixturePrefix + "-"),
            "承载块必须与锚点同语料: " + entry.getKey() + " -> " + carrier);
      }
    }
  }

  /**
   * MIN_CARRIAGE 边界（任务 1.2/2.1，合成语料直测判定核）：overlap=40 固定复现滑窗几何， 段末（换行）落在重叠区的承载面精确 15 / 16 / 2
   * 字符三档——15 与 2 不承载（防 1-2 字符偶然相交虚增），16 恰承载。span 末界由换行符控制（段末口径），交集 = 40 - tail。
   */
  @Test
  void minCarriageBoundarySynthetic() {
    String marker = "【GOLD:carriage-edge】";
    String paragraph = "承".repeat(50) + "\n";
    for (int tail : new int[] {25, 24, 38}) {
      String aText = "h".repeat(300) + marker + paragraph + "t".repeat(tail);
      String bText = aText.substring(aText.length() - 40) + "n".repeat(280);
      List<DocumentChunk> group = List.of(chunk(aText, 0), chunk(bText, 1));
      Map<String, String> golden = Map.of("carriage-edge", "synthetic-0");
      Map<String, Set<String>> carriers =
          RagBenchmarkDataPreparer.computeCarrierChunkIds(
              List.of(Map.entry("synthetic", group)), golden);
      int expected = 40 - tail;
      if (expected >= RagBenchmarkDataPreparer.MIN_CARRIAGE_CHARS) {
        assertEquals(
            Set.of("synthetic-0", "synthetic-1"),
            carriers.get("carriage-edge"),
            "tail=" + tail + " 交集=" + expected + " 必须承载");
      } else {
        assertEquals(
            Set.of("synthetic-0"),
            carriers.get("carriage-edge"),
            "tail=" + tail + " 交集=" + expected + " 不得虚增承载");
      }
    }
  }

  private static DocumentChunk chunk(String text, int chunkIndex) {
    return new DocumentChunk(text, chunkIndex, 1, null, "benchmark", List.of(), null);
  }

  /**
   * 向量输入红线（任务 1.4）：indexedText/embedText 逐字节不变——SHA256 钉值取自实现前探针 （VectorInputDigestProbe
   * 于实现前一轮实测，57 切片），承载判定任何实现都不得触碰索引/嵌入输入。
   */
  @Test
  void vectorInputsStayByteIdentical() {
    StringBuilder indexed = new StringBuilder();
    StringBuilder embed = new StringBuilder();
    RagBenchmarkDataPreparer.ChunkEmbedder recorder =
        text -> {
          embed.append(text).append('\u0001');
          return new float[1];
        };
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(new InMemoryVectorStore(), recorder, EVAL_KB_ID);
    for (String text : preparation.chunkTexts()) {
      indexed.append(text).append('\u0001');
    }
    assertEquals(57, preparation.chunkTexts().size(), "切片数（锚点形态）");
    assertEquals(
        "da7a3ef9850fd563294e57ec1dceda8d43fadcdf7be895a9d01be3654d8cac05",
        sha256(indexed.toString()),
        "indexedText 逐字节不变（红线）");
    assertEquals(
        "79a804d5f06d4a87b1c93b6896b4dc060109a1cd5e077fdbff6080ff615a39a2",
        sha256(embed.toString()),
        "embedText 逐字节不变（红线）");
  }

  private static String sha256(String data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 防呆：承载集值必须为不可变集合（Preparation 只读视图约定）。 */
  @Test
  void carrierMapViewIsReadOnly() {
    RagBenchmarkDataPreparer.Preparation preparation = prepare();
    Set<String> carriers = preparation.goldenToCarrierChunkIds().get("payment-plan-1");
    assertTrue(carriers instanceof Set, "承载集必须为 Set");
    Set<String> copy = new LinkedHashSet<>(carriers);
    copy.add("intruder");
    assertFalse(carriers.contains("intruder") || carriers.equals(copy), "承载集必须不可变（只读视图）");
  }
}
