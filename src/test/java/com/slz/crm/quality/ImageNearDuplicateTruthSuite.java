package com.slz.crm.quality;

import com.slz.crm.quality.RagQualityEvaluator.RetrievalFunction;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图像近重复检索级真值用例集（query-relevance-truth G2，任务 2.1.1~2.1.3，零外呼）。
 *
 * <p><b>沿用既有 {@code expectedChunkIds} 真值机制</b>（{@code ImageCrossModalSuite:49}「expectedChunkIds =
 * 同图切片 id，供 {@code RagQualityEvaluator} 判 recall」、{@code PdfIngestImageFixture:247}
 * 同图用例形状）：本类把<b>近重复机制自身的判别面</b>接进该机制， 不新造指标口径。真值全部来自<b>语料 provenance</b>（校准锚 pairs 的 {@code
 * baseKey}/{@code kind}/{@code docKey} 键关系）， 不含任何人工判断；{@code goldenId = "golden:" + baseKey}
 * 由键唯一导出，无手写字面量 id。
 *
 * <p><b>三类用例</b>（观测单位 = 校准锚的一对，得分 = 该对逐对余弦，检索判定 = {@code cosine >= floor}）：
 *
 * <ul>
 *   <li><b>正例 120</b>（24 base × 5 kind）：query = base 的 kind 变体，期望 = 该 base 的黄金切片；得分 =
 *       near-duplicate 对余弦；
 *   <li><b>负例 A · 跨文档近似 176</b>：query = base（再编码），期望 = 空（诚实空判：top-k 非空即污染）；得分 = cross-class 对余弦；
 *   <li><b>负例 B · 同文档不同图（hard negative）100</b>：query = base（再编码），期望 = 查询 base 自身；同文档其它 base 是
 *       「相关但不必须」的竞争者（A1 假设域），得分面 = same-image（自身，锚内实测全 1.0）+ same-class（同文档竞争者）。
 * </ul>
 *
 * <p><b>适用范围（诚实声明）</b>：本套件只覆盖<b>图像路内检索级</b>（pair 级重建），不含文本路、不含融合后排序；负例的查询侧用 base 再编码而非变体——校准锚 540
 * 对中不存在变体↔第三图余弦，零编造优先于字面形状（偏差已入锚 {@code deviations}）。 {@code contaminationAtK} 是既有 top-k
 * 结果的<b>派生量</b>，不是新评测口径。
 *
 * <p><b>零外呼</b>：整类只有查表与内存容器，无 I/O、无网络、无环境变量，不做任何 embedding。
 */
final class ImageNearDuplicateTruthSuite {

  /** 黄金切片 id 前缀：{@code goldenId = GOLDEN_ID_PREFIX + baseKey}（provenance 唯一导出）。 */
  static final String GOLDEN_ID_PREFIX = "golden:";

  /** 召回截断排名（与主基准、图文跨模态套件同口径）。 */
  static final int TOP_K = 5;

  /** 正例分母 = 24 base × 5 kind。 */
  static final int POSITIVE_TOTAL = 120;

  /** 负例 A 分母 = cross-class 对总数。 */
  static final int NEGATIVE_A_TOTAL = 176;

  /** 负例 B 分母 = same-class 对总数。 */
  static final int NEGATIVE_B_TOTAL = 100;

  /** 每 base 的变体 kind 数（T1 完整性：24 × 5 = 120）。 */
  static final int KINDS_PER_BASE = 5;

  private ImageNearDuplicateTruthSuite() {}

  /** 黄金切片 id：由 baseKey 唯一导出（T1：禁手写字面量 id 的唯一生成点）。 */
  static String goldenId(String baseKey) {
    return GOLDEN_ID_PREFIX + baseKey;
  }

  /**
   * 正例 120 条：query = 变体，期望 = 该 base 的黄金切片。
   *
   * @param pairs 校准锚样本对
   * @return 用例表（不可变）
   */
  static List<RagBenchmarkCase> positiveCases(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    List<RagBenchmarkCase> cases = new ArrayList<>(POSITIVE_TOTAL);
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_NEAR_DUPLICATE.equals(pair.stratum())) {
        cases.add(
            new RagBenchmarkCase(
                caseId(pair),
                RagBenchmarkCase.Category.IMAGE,
                "image-query:" + pair.rightKey(),
                Set.of(goldenId(pair.leftKey())),
                List.of(),
                true));
      }
    }
    return List.copyOf(cases);
  }

  /**
   * 负例 A（跨文档近似）176 条：query = base 再编码，期望 = 空；候选出现即污染。
   *
   * @param pairs 校准锚样本对
   * @return 用例表（不可变）
   */
  static List<RagBenchmarkCase> negativeACases(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    List<RagBenchmarkCase> cases = new ArrayList<>(NEGATIVE_A_TOTAL);
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_CROSS_CLASS.equals(pair.stratum())) {
        cases.add(
            new RagBenchmarkCase(
                caseId(pair),
                RagBenchmarkCase.Category.IMAGE,
                "image-query:" + pair.leftKey(),
                Set.of(),
                List.of(),
                true));
      }
    }
    return List.copyOf(cases);
  }

  /**
   * 负例 B（同文档不同图，hard negative）100 条：query = base 再编码，期望 = 查询 base 自身；同文档其它 base 记「相关但不必须」 （照 {@code
   * RagQualityEvaluator} 口径如实分类：不在 expected 集内，但其出现不判污染——A1 假设域，见锚 scopeLimits）。
   *
   * @param pairs 校准锚样本对
   * @return 用例表（不可变）
   */
  static List<RagBenchmarkCase> negativeBCases(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    List<RagBenchmarkCase> cases = new ArrayList<>(NEGATIVE_B_TOTAL);
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_SAME_CLASS.equals(pair.stratum())) {
        cases.add(
            new RagBenchmarkCase(
                caseId(pair),
                RagBenchmarkCase.Category.IMAGE,
                "image-query:" + pair.leftKey(),
                Set.of(goldenId(pair.leftKey())),
                List.of(),
                true));
      }
    }
    return List.copyOf(cases);
  }

  /**
   * 用例 id（确定性：由 provenance 键拼装）。
   *
   * @param pair 样本对
   * @return {@code NDT-P-baseKey@kind} / {@code NDT-NA-a|b} / {@code NDT-NB-a|b}
   */
  static String caseId(NearDupFusionImpactAnalyzer.Pair pair) {
    String id;
    switch (pair.stratum()) {
      case NearDupFusionImpactAnalyzer.STRATUM_NEAR_DUPLICATE ->
          id = "NDT-P-" + pair.leftKey() + "@" + pair.kind();
      case NearDupFusionImpactAnalyzer.STRATUM_CROSS_CLASS ->
          id = "NDT-NA-" + pair.leftKey() + "|" + pair.rightKey();
      case NearDupFusionImpactAnalyzer.STRATUM_SAME_CLASS ->
          id = "NDT-NB-" + pair.leftKey() + "|" + pair.rightKey();
      default -> throw new IllegalArgumentException("非用例档: " + pair.stratum());
    }
    return id;
  }

  /**
   * 逐 case 的 top-k 检索结果（floor 参数化的图像路内重建，排序从校准锚逐对余弦取，<b>不做任何 embedding</b>）。
   *
   * <p>正例：own base 黄金切片 {@code >= floor} 才召回；负例 A：跨文档候选 {@code >= floor} 才出现（空期望 + 非空 top-k = 污染）；
   * 负例 B：自身（same-image 余弦）+ 同文档竞争者（same-class 余弦）各自 {@code >= floor} 才出现，按分降序。
   *
   * @param pairs 校准锚样本对
   * @param floor 生效下限
   * @return case id → top-k 检索结果（升序排名，不可变）
   */
  static Map<String, List<String>> retrievedByCaseId(
      List<NearDupFusionImpactAnalyzer.Pair> pairs, double floor) {
    Map<String, Double> sameImageCosine = sameImageCosineByBase(pairs);
    Map<String, List<String>> retrieved = new LinkedHashMap<>();
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_SAME_IMAGE.equals(pair.stratum())) {
        continue; // 同图对不是用例，只进 same-image 余弦表（sameImageCosineByBase）
      }
      switch (pair.stratum()) {
        case NearDupFusionImpactAnalyzer.STRATUM_NEAR_DUPLICATE ->
            retrieved.put(
                caseId(pair),
                pair.cosine() >= floor ? List.of(goldenId(pair.leftKey())) : List.of());
        case NearDupFusionImpactAnalyzer.STRATUM_CROSS_CLASS ->
            retrieved.put(
                caseId(pair),
                pair.cosine() >= floor ? List.of(goldenId(pair.rightKey())) : List.of());
        case NearDupFusionImpactAnalyzer.STRATUM_SAME_CLASS -> {
          Double ownCosine = sameImageCosine.get(pair.leftKey());
          if (ownCosine == null) {
            throw new IllegalStateException(
                "same-class 对的查询 base 缺 same-image 余弦: " + pair.leftKey());
          }
          List<double[]> scored = new ArrayList<>(2);
          scored.add(new double[] {ownCosine, 0D});
          scored.add(new double[] {pair.cosine(), 1D});
          scored.sort(Comparator.comparingDouble((double[] item) -> item[0]).reversed());
          List<String> hits = new ArrayList<>(2);
          for (double[] item : scored) {
            if (item[0] >= floor) {
              hits.add(item[1] < 0.5D ? goldenId(pair.leftKey()) : goldenId(pair.rightKey()));
            }
          }
          retrieved.put(caseId(pair), List.copyOf(hits));
        }
        default -> throw new IllegalArgumentException("非用例档: " + pair.stratum());
      }
    }
    return Map.copyOf(retrieved);
  }

  /**
   * 检索函数工厂：把逐 case 检索结果表接到既有 {@code RagQualityEvaluator}（口径零改动，T2）。
   *
   * @param retrievedByCaseId {@link #retrievedByCaseId(List, double)} 的产物
   * @param allCaseIds 全部用例 id（fail-fast：缺一个 id 即抛，防静默按失败计分）
   * @return 检索函数
   */
  static RetrievalFunction retrieval(
      Map<String, List<String>> retrievedByCaseId, Set<String> allCaseIds) {
    if (!retrievedByCaseId.keySet().equals(allCaseIds)) {
      Set<String> missing = new LinkedHashSet<>(allCaseIds);
      missing.removeAll(retrievedByCaseId.keySet());
      Set<String> extra = new LinkedHashSet<>(retrievedByCaseId.keySet());
      extra.removeAll(allCaseIds);
      throw new IllegalStateException("检索结果表与用例集不对齐: missing=" + missing + " extra=" + extra);
    }
    return benchmarkCase -> {
      List<String> chunkIds = retrievedByCaseId.getOrDefault(benchmarkCase.id(), List.of());
      return new CaseOutcome(chunkIds, List.of(), 0D, 0L, 0L, 0, true);
    };
  }

  /** same-image 余弦表（键 = 图自身键；锚内实测 144 对全 1.0，本表数据驱动、不写死）。 */
  static Map<String, Double> sameImageCosineByBase(List<NearDupFusionImpactAnalyzer.Pair> pairs) {
    Map<String, Double> sameImage = new LinkedHashMap<>();
    for (NearDupFusionImpactAnalyzer.Pair pair : pairs) {
      if (NearDupFusionImpactAnalyzer.STRATUM_SAME_IMAGE.equals(pair.stratum())) {
        sameImage.put(pair.leftKey(), pair.cosine());
      }
    }
    return Map.copyOf(sameImage);
  }
}
