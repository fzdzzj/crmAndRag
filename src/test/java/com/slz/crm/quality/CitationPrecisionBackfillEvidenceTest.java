package com.slz.crm.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.quality.RagBenchmarkCase.Category;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.CitationAligner;
import com.slz.crm.server.ai.CitationThresholds;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回灌证据单测（fix-citation-threshold-config 任务 2.2，池账 C-2 闭环的确定性证明）。
 *
 * <p>缺口形态：评测侧 {@code RagQualityEvaluator.citationPrecision} 只按 chunk id 是否命中黄金集判分——对齐器把黄金引用误杀
 * （DROP）后，若 citations 仍按对齐前抽取结果上报，citP 仍是满分。本测试用确定性 fixture 证明「citations 上报前先过 {@link
 * CitationAligner}（生产同款路径）」这一回灌动作让 citP 如实下降。
 */
class CitationPrecisionBackfillEvidenceTest {

  private static final String GOLD_CHUNK = "gold-1";

  /** 黄金块（chunk id 命中即 citP 满分的那个块）。 */
  private static final String GOLD_EXCERPT = "服务台四层架构含台账与预警引擎，接入层负责工单登记。";

  /** 无关片段：与断言子句 bigram 零交集 → 支撑分 0。 */
  private static final String UNRELATED_EXCERPT = "图注：机房平面图标注 UPS 与精密空调位置。";

  @Test
  @DisplayName("黄金引用被对齐器 DROP → citations 不含该编号 → citationPrecision 由 1.0 降到 0.0")
  void goldenCitationDroppedByAlignerLowersCitationPrecision() {
    // 检索命中就是黄金块本身：只要 citations 还留着 [1]，citP 必然满分
    List<SourceReference> sources = List.of(src(GOLD_CHUNK, UNRELATED_EXCERPT));
    List<String> retrieved = List.of(GOLD_CHUNK);
    String rawAnswer = "今天天气晴朗适合户外活动[1]。";

    // ① 回灌前（缺口形态）：不做对齐，直接上报对齐前抽取到的 [1] → citP = 1.0（误杀黄金引用也拦不住）
    Report before = evaluate(retrieved, sources, List.of(1), 1.0d);
    assertThat(before.cases().get(0).citationPrecision()).isEqualTo(1.0d);
    assertThat(before.metrics().meanCitationPrecision()).isEqualTo(1.0d);

    // ② 回灌动作：citations 上报前先经生产同款对齐路径
    CitationAligner.Alignment aligned =
        CitationAligner.align(rawAnswer, sources, CitationThresholds.defaults());
    assertThat(aligned.citations()).as("支撑分为 0 → DROP").isEmpty();
    assertThat(aligned.text()).doesNotContain("[1]");

    // ③ 回灌后：上报对齐后的 citations（空）→ 有黄金却零引用 → citP = 0.0（C-2 缺口闭环）
    Report after = evaluate(retrieved, sources, aligned.citations(), 1.0d);
    assertThat(after.cases().get(0).citationPrecision()).isZero();
    assertThat(after.metrics().meanCitationPrecision()).isZero();
    // 召回/覆盖不受对齐影响（对齐只改编号，不动检索与答案要点）
    assertThat(after.cases().get(0).recallAtK()).isEqualTo(before.cases().get(0).recallAtK());
    assertThat(after.cases().get(0).answerConsistency())
        .isEqualTo(before.cases().get(0).answerConsistency());
  }

  @Test
  @DisplayName("对照：真正有支撑的黄金引用经对齐后保留 → citP 仍为 1.0（回灌不会误伤正常引用）")
  void supportedGoldenCitationSurvivesAlignment() {
    List<SourceReference> sources = List.of(src(GOLD_CHUNK, GOLD_EXCERPT));
    List<String> retrieved = List.of(GOLD_CHUNK);
    String rawAnswer = "服务台四层架构含台账与预警引擎[1]。";

    CitationAligner.Alignment aligned =
        CitationAligner.align(rawAnswer, sources, CitationThresholds.defaults());
    assertThat(aligned.citations()).containsExactly(1);
    assertThat(aligned.text()).isEqualTo(rawAnswer);

    Report report = evaluate(retrieved, sources, aligned.citations(), 1.0d);
    assertThat(report.cases().get(0).citationPrecision()).isEqualTo(1.0d);
    assertThat(report.metrics().meanCitationPrecision()).isEqualTo(1.0d);
  }

  private static Report evaluate(
      List<String> retrieved,
      List<SourceReference> sources,
      List<Integer> citations,
      double coverage) {
    RagBenchmarkCase benchmarkCase =
        new RagBenchmarkCase(
            "C-2-GOLD", Category.TEXT, "服务台四层架构是什么", Set.of(GOLD_CHUNK), List.of("四层架构"), true);
    return RagQualityEvaluator.evaluate(
        List.of(benchmarkCase),
        c -> new CaseOutcome(retrieved, citations, coverage, 100L, 500L, 200, true, false),
        5);
  }

  private static SourceReference src(String chunkId, String excerpt) {
    return new SourceReference(
        "md", "vector", "sla-terms.md", "doc-1", chunkId, 0, 1, null, excerpt, 0.9d);
  }
}
