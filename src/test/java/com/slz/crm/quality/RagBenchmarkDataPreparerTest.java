package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.quality.RagBenchmarkCase.Category;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 数据准备 runner 单测（add-rag-quality-baseline 任务 2.3/2.4；expand-rag-benchmark 任务 3.1 扩容）。
 *
 * <p>用确定性 fake embedder 驱动<b>真实 fixtures + 真实 {@code DocumentService} 分块</b>， 不需要模型 key
 * 与外网：幂等性、对齐拒绝、套件占位 id 与语料 GOLD 标记的同步关系 都在这里机械保证——fixtures 改版导致对齐破坏时会在此先红，而不是等真跑基线才发现。
 */
class RagBenchmarkDataPreparerTest {

  private static final long EVAL_KB_ID = 99001L;

  /**
   * 确定性 fake 向量：按字符位置累加到固定 64 维槽位，同一文本永远得到同一向量， 不同文本得到不同向量——足够让 InMemoryVectorStore 的余弦检索在单测里正常工作。
   */
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

  @Test
  void rerunProducesIdenticalChunkIdSetAndMapping() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation first =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
    Set<String> firstChunkIds = new HashSet<>(first.chunkIds());
    Map<String, String> firstMapping = first.goldenToChunkId();

    // 同一环境连续跑第二次：chunkId 集合与黄金映射必须完全一致（幂等，任务 2.3）
    RagBenchmarkDataPreparer.Preparation second =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
    assertEquals(firstChunkIds, second.chunkIds(), "重跑后 chunkId 集合必须一致");
    assertEquals(firstMapping, second.goldenToChunkId(), "重跑后黄金映射必须一致");

    // 换一个全新向量库再跑一遍：产出仍然一致（幂等不依赖库内残留状态）
    RagBenchmarkDataPreparer.Preparation fresh =
        RagBenchmarkDataPreparer.prepare(
            new InMemoryVectorStore(), RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
    assertEquals(firstChunkIds, fresh.chunkIds(), "全新向量库产出的 chunkId 集合必须一致");

    // 十五份语料全部产出了切片（fix-i05-caption-chunk：sla-arch 独立图注；expand-rag-benchmark-mismatch
    // 任务 1.1 新增 trade-jargon/equipment/expense 三份失配语料）
    assertEquals(
        15,
        first.chunkIds().stream()
            .map(id -> id.substring(0, id.lastIndexOf('-')))
            .distinct()
            .count());
  }

  @Test
  void unalignedGoldenIdFailsExplicitlyInsteadOfSilentEmptySet() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    // 已对齐的占位 id 不应报错
    RagBenchmarkDataPreparer.validateAlignment(Set.of("sales-flow-1", "model-xr500"), preparation);
    // 未对齐的占位 id 必须显式失败，并把 id 报出来（任务 2.4：拒绝静默空集）
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> RagBenchmarkDataPreparer.validateAlignment(Set.of("ghost-golden"), preparation));
    assertTrue(
        exception.getMessage().contains("ghost-golden"),
        "失败信息必须包含未对齐的占位 id，实际=" + exception.getMessage());
  }

  @Test
  void expandedSuiteV3SizeAndCategoryBreakdown() {
    // 扩容后基准集规模与分类配比（expand-rag-benchmark 任务 3.1 54；expand-rag-benchmark-mismatch
    // 任务 2.3 升 v3.0 63）：六类 17/13/6/14/4/9
    List<RagBenchmarkCase> suite = RagBenchmarkSuite.standard();
    assertEquals(63, suite.size(), "v3.0 基准集总条数必须为 63");
    assertEquals(
        "3.0",
        RagBenchmarkSuite.SUITE_VERSION,
        "expand-rag-benchmark-mismatch：SUITE_VERSION 必须为 3.0");
    Map<Category, Long> categoryCounts =
        suite.stream()
            .collect(Collectors.groupingBy(RagBenchmarkCase::category, Collectors.counting()));
    assertEquals(17L, categoryCounts.get(Category.TEXT), "TEXT 分类条数");
    assertEquals(13L, categoryCounts.get(Category.TABLE), "TABLE 分类条数");
    assertEquals(6L, categoryCounts.get(Category.IMAGE), "IMAGE 分类条数");
    assertEquals(14L, categoryCounts.get(Category.LEXICAL), "LEXICAL 分类条数");
    assertEquals(4L, categoryCounts.get(Category.EDGE), "EDGE 分类条数");
    assertEquals(9L, categoryCounts.get(Category.MISMATCH), "MISMATCH 分类条数");
  }

  @Test
  void standardSuitePlaceholdersAllAlignToRealFixtureChunks() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    Set<String> placeholders =
        RagBenchmarkSuite.standard().stream()
            .flatMap(c -> c.expectedChunkIds().stream())
            .collect(Collectors.toSet());
    // 套件与语料同步：全部占位 id 都能在语料 GOLD 标记里找到，且一一对应（多、少、拼错都算失败）
    assertEquals(
        Set.copyOf(preparation.goldenToChunkId().keySet()),
        placeholders,
        "基准集占位 id 必须与语料 GOLD 标记一一对应（多、少、拼错都算失败）");

    List<RagBenchmarkCase> rewritten =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    for (RagBenchmarkCase c : rewritten) {
      if (c.category() == Category.EDGE) {
        assertTrue(c.expectedChunkIds().isEmpty(), "边界用例期望片段必须为空: " + c.id());
        continue;
      }
      assertFalse(c.expectedChunkIds().isEmpty(), "非边界用例必须保留黄金片段: " + c.id());
      assertTrue(
          preparation.chunkIds().containsAll(c.expectedChunkIds()),
          "改写后的黄金 id 必须都是真实入库 chunkId: " + c.id());
    }
    // 黄金片段分布健康度：44 个占位 id 至少映射到 30 个不同切片，防止全部挤在同一切片让 recall 失真
    assertTrue(
        new HashSet<>(preparation.goldenToChunkId().values()).size() >= 30,
        "黄金片段过于集中，请检查语料段落长度: " + preparation.goldenToChunkId());
  }

  /** fix-i05-caption-chunk 任务 2.1：sla-arch-diagram 黄金块含完整图注词面，且不与责任/赔偿段粘连。 */
  @Test
  void slaArchDiagramGoldenChunkContainsCaptionWithoutOwnerOrCreditBleed() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    String chunkId = preparation.goldenToChunkId().get("sla-arch-diagram");
    assertTrue(chunkId != null, "sla-arch-diagram 黄金占位 id 必须对齐");
    String text =
        preparation.chunks().stream()
            .filter(c -> c.chunkId().equals(chunkId))
            .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
            .findFirst()
            .orElseThrow();

    assertTrue(text.contains("接入层") || text.contains("四层"), "图注黄金块须含接入层/四层: " + text);
    assertTrue(text.contains("台账与预警引擎"), "图注黄金块须含台账与预警引擎: " + text);
    assertFalse(text.contains("何建军"), "图注黄金块不得粘连责任工程师何建军: " + text);
    assertFalse(text.contains("赔偿当月服务费"), "图注黄金块不得粘连赔偿条款: " + text);
    assertTrue(chunkId.startsWith("sla-arch-"), "独立图注语料 key 应为 sla-arch，实际 chunkId=" + chunkId);
  }

  /** add-excel-header-projection 任务 2.4：维保/销售黄金行索引文本含列名；GOLD 标记仍剥离。 */
  @Test
  void excelGoldenRowsIndexedTextContainsProjectedColumnNames() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    String maintChunkId = preparation.goldenToChunkId().get("maint-xr500-q");
    String salesChunkId = preparation.goldenToChunkId().get("sales-q3-华东");
    assertTrue(maintChunkId != null, "维保黄金占位 id 必须对齐");
    assertTrue(salesChunkId != null, "销售黄金占位 id 必须对齐");

    String maintText =
        preparation.chunks().stream()
            .filter(c -> c.chunkId().equals(maintChunkId))
            .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
            .findFirst()
            .orElseThrow();
    String salesText =
        preparation.chunks().stream()
            .filter(c -> c.chunkId().equals(salesChunkId))
            .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
            .findFirst()
            .orElseThrow();

    assertTrue(maintText.contains("计划工时"), "维保黄金行索引文本须含列名「计划工时」: " + maintText);
    assertTrue(salesText.contains("销售额"), "销售黄金行索引文本须含列名「销售额」: " + salesText);
    assertFalse(maintText.contains("【GOLD"), "GOLD 标记必须已剥离: " + maintText);
    assertFalse(salesText.contains("【GOLD"), "GOLD 标记必须已剥离: " + salesText);
  }

  @Test
  void indexedTextIsCleanAndMetadataMatchesRetrievalFilter() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    // 索引文本不残留评测脚手架标记
    for (String text : preparation.chunkTexts()) {
      assertFalse(text.contains("【GOLD"), "索引文本不得包含 GOLD 标记: " + text);
      assertFalse(text.isBlank(), "索引文本不得为空（标记剥离后只剩空白说明语料段落为空）");
    }

    // 检索侧按 knowledgeBaseId 过滤能命中本语料，metadata 与生产入库同口径
    List<VectorSearchHit> hits =
        store.search(
            new VectorSearchRequest(
                fakeEmbed("合同审批流程"),
                10,
                0.0,
                Map.of("knowledgeBaseId", String.valueOf(EVAL_KB_ID))));
    assertFalse(hits.isEmpty(), "按评测知识库过滤必须能命中入库切片");
    for (VectorSearchHit hit : hits) {
      assertEquals(String.valueOf(EVAL_KB_ID), hit.metadata().get("knowledgeBaseId"));
      assertTrue(
          preparation.chunkIds().contains(hit.chunkId()),
          "命中 chunkId 必须来自本次准备产出: " + hit.chunkId());
      assertTrue(hit.metadata().containsKey("filename"));
      assertTrue(hit.metadata().containsKey("chunkId"));
    }
  }

  /**
   * expand-rag-benchmark-mismatch 任务 1.2：三类失配语料（行话/编号/口语-术语）登记并加载。
   *
   * <p>红测先行：语料文件先落盘、未登记进 {@code FIXTURES} 时，其 GOLD 占位 id 无法对齐 → 本测试红； 登记进 {@code
   * RagBenchmarkDataPreparer.FIXTURES} 后转绿。同时保证三类失配模式各至少有一个黄金块可被 真分块链路发现，且索引文本不含 GOLD 标记。
   */
  @Test
  void mismatchFixturesRegisterAndAlignGold() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    // 三个失配 fixture 各至少一块 GOLD 必须对齐到真实 chunkId
    List<String> expected =
        List.of(
            "jargon-factoring",
            "jargon-credit",
            "eq-041",
            "eq-107",
            "expense-travel",
            "expense-advance");
    for (String goldId : expected) {
      assertTrue(
          preparation.goldenToChunkId().containsKey(goldId),
          "失配语料 GOLD "
              + goldId
              + " 未对齐（fixture 未登记或分块丢失？），已有映射="
              + preparation.goldenToChunkId().keySet());
    }

    // 语料 key 前缀确实按新 fixture 命名
    assertTrue(
        preparation.chunkIds().stream().anyMatch(id -> id.startsWith("trade-jargon-")),
        "行话语料应有 trade-jargon- 前缀切片");
    assertTrue(
        preparation.chunkIds().stream().anyMatch(id -> id.startsWith("equipment-")),
        "编号语料应有 equipment- 前缀切片");
    assertTrue(
        preparation.chunkIds().stream().anyMatch(id -> id.startsWith("expense-")),
        "口语语料应有 expense- 前缀切片");

    // 索引文本不残留 GOLD 脚手架
    for (String text : preparation.chunkTexts()) {
      assertFalse(text.contains("【GOLD"), "新语料索引文本不得含 GOLD 标记: " + text);
    }
  }

  /**
   * harden-gold-marker-tearing 任务 1.1：T-15 撕裂现场检出（红测先行，纯检出登记、零行为变更）。
   *
   * <p>取证实测（trace-citation-redundancy citation-forensics-v4 §5-M3 + 本卡探针 2026-10-11 复测）：
   * customer-sop.md 被 320/40 滑窗切分后——左残段在 customer-sop-1 尾部（区间 [302,320)，精确形态 {@code
   * 【GOLD:customer-onb}，18 字符无闭合，FRAGMENT 剥离正确但此前无检出信号，goldId 尽力解析为 {@code customer-onb}）；完整标记
   * {@code 【GOLD:customer-onboard-3】} 因 overlap 复制在 customer-sop-2 区间 [274,299) 唯一完整命中（归点，最小
   * chunkIndex 规则，机制正确）；右残段在 customer-sop-3 开头 （{@code customer-onboard-3】}，19 字符无前缀，FRAGMENT 不识别 →
   * 泄漏进索引文本）。
   */
  @Test
  void tornMarkerT15SceneIsDetected() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    List<RagBenchmarkDataPreparer.TornMarkerEvent> torn = preparation.tornMarkers();

    // RIGHT 事件：右残段所在 chunkId=customer-sop-3，归点对照=customer-sop-2（归点与语义主体分离的机械证据）
    RagBenchmarkDataPreparer.TornMarkerEvent right =
        torn.stream()
            .filter(
                e ->
                    e.direction() == RagBenchmarkDataPreparer.TornMarkerEvent.Direction.RIGHT
                        && "customer-onboard-3".equals(e.goldId()))
            .findFirst()
            .orElse(null);
    assertTrue(right != null, "T-15 右残段必须被检出（customer-onboard-3）: " + torn);
    assertEquals("customer-sop-3", right.tornChunkId(), "右残段所在 chunkId");
    assertEquals("customer-sop-2", right.alignedChunkId(), "归点对照 chunkId");
    assertEquals(
        "customer-onboard-3】", right.matchedFragment(), "右残段精确形态（实测校准：sop-3 剥离前原文开头 19 字符）");

    // LEFT 事件：customer-sop 语料的左残段（FRAGMENT 命中中非完整标记覆盖区间者）
    RagBenchmarkDataPreparer.TornMarkerEvent left =
        torn.stream()
            .filter(
                e ->
                    e.direction() == RagBenchmarkDataPreparer.TornMarkerEvent.Direction.LEFT
                        && e.tornChunkId().startsWith("customer-sop-"))
            .findFirst()
            .orElse(null);
    assertTrue(left != null, "customer-sop 语料必须存在左残段事件: " + torn);
    assertEquals("customer-sop-1", left.tornChunkId(), "左残段所在 chunkId（实测校准：sop-1 尾部）");
    assertEquals("customer-onb", left.goldId(), "左残段 goldId 尽力解析（实测校准：部分 id）");
    assertEquals("【GOLD:customer-onb", left.matchedFragment(), "左残段精确形态（实测校准：18 字符无闭合）");
  }

  /**
   * harden-gold-marker-tearing 任务 1.1：零行为变更锁 + 检出零误报。
   *
   * <p>右残段<b>不剥</b>：剥离会改 indexedText/embedText → 向量输入变 → baseline-v3 锚点漂移（属 B2 升版
   * 受控校准范畴，本卡不碰）。本测试锁现行剥离/归点行为不因检出实现而漂移，并锁撕裂事件只登记在 customer-sop 语料（探针实测 2026-10-11：全 15 份语料中撕裂仅
   * customer-sop 一家，其余 14 份零登记）。
   */
  @Test
  void tornMarkerDetectionKeepsIndexingBehaviorUntouched() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

    // 锁剥离行为：sop-3 索引文本仍以右残段开头（右残段不剥——改了就是向量输入漂移）
    String sop3Text =
        preparation.chunks().stream()
            .filter(c -> c.chunkId().equals("customer-sop-3"))
            .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
            .findFirst()
            .orElseThrow();
    assertTrue(
        sop3Text.startsWith("customer-onboard-3】"), "零行为变更锁：sop-3 索引文本必须仍以右残段开头: " + sop3Text);

    // 锁归点：customer-onboard-3 归点仍为完整命中块 customer-sop-2
    assertEquals(
        "customer-sop-2", preparation.goldenToChunkId().get("customer-onboard-3"), "零行为变更锁：归点规则未漂");

    // 零误报：撕裂事件只登记在 customer-sop 语料（其余 14 份零登记）
    assertTrue(
        preparation.tornMarkers().stream()
            .allMatch(e -> e.tornChunkId().startsWith("customer-sop-")),
        "其余 14 份语料零登记（实测撕裂仅 customer-sop 一家）: " + preparation.tornMarkers());
  }
}
