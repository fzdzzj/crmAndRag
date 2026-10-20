package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.quality.RagBenchmarkCase.Category;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Report;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 评测盲区补齐的确定性锁测（expand-rag-eval-blind-spots 任务 3.3）。
 *
 * <p>三块盲区各自锁定：多轮 history 是否真到达 {@code RetrievalFunction}（C-3）、TEMPORAL 用例是否按正常用例口径判卷（C-4）、 expected
 * 空的拒答三态（诚实拒答满分 / 借近邻编造 0 分 / 正常用例不受 refused 影响，C-5）。
 *
 * <p>全部 fake、零外呼、零 Docker——evaluator 的可插拔 {@code RetrievalFunction} 即为此设计（既有先例）。
 */
class RagEvalBlindSpotsTest {

  private static final double EPS = 1e-9;

  /** 相对时间限定词：TEMPORAL 组问句必须含其一（时间限定词改写是该类别的考察面）。 */
  private static final List<String> TIME_QUALIFIERS =
      List.of("本月", "上季度", "本季度", "最近", "这个月", "去年", "上一个");

  private static CaseOutcome hit(String chunkId, double coverage) {
    return new CaseOutcome(List.of(chunkId), List.of(1), coverage, 10L, 20L, 30, true);
  }

  @Test
  void multiTurnHistoryReachesRetrievalFunction() {
    Map<String, RagBenchmarkCase> received = new LinkedHashMap<>();
    RagQualityEvaluator.evaluate(
        RagBenchmarkSuite.standard(),
        benchmarkCase -> {
          received.put(benchmarkCase.id(), benchmarkCase);
          return new CaseOutcome(List.of(), List.of(), 0d, 0L, 0L, 0, true, true);
        },
        3);

    RagBenchmarkCase mt01 = received.get("MT-01");
    assertEquals(1, mt01.history().size(), "MT-01 必须带一轮对话历史");
    assertEquals("合同生效后金额能改吗", mt01.history().get(0).userText());
    assertEquals("可以，需重新走合同变更审批，审批链与新建合同一致。", mt01.history().get(0).assistantText());
    assertEquals("那它的审批流程具体怎么走", mt01.question(), "问句是省略式，只有结合 history 才可解析指代");
    assertEquals(Set.of("sales-flow-4"), mt01.expectedChunkIds());

    // 兼容重载：既有单轮用例的 history 必须为空表（63 例构造零改动）
    assertEquals(List.of(), received.get("T-01").history(), "既有单轮用例 history 必须为空表");
    assertEquals(List.of(), received.get("E-02").history(), "既有边界用例 history 必须为空表");

    // 多轮组 5 条，覆盖指代消解 / 上下文追问 / 澄清触发
    List<String> multiTurnIds =
        received.keySet().stream().filter(id -> id.startsWith("MT-")).toList();
    assertEquals(5, multiTurnIds.size(), "多轮用例组 5 条，实际=" + multiTurnIds);
    for (String id : multiTurnIds) {
      assertFalse(received.get(id).history().isEmpty(), "多轮用例必须带 history: " + id);
    }
  }

  @Test
  void temporalCasesJudgeBySameSemanticsAsNormalCases() {
    List<RagBenchmarkCase> temporal =
        RagBenchmarkSuite.standard().stream()
            .filter(benchmarkCase -> benchmarkCase.category() == Category.TEMPORAL)
            .toList();
    assertEquals(4, temporal.size(), "TEMPORAL 组 4 条");
    for (RagBenchmarkCase benchmarkCase : temporal) {
      assertFalse(
          benchmarkCase.expectedChunkIds().isEmpty(),
          "TEMPORAL 用例必须有静态语料黄金锚: " + benchmarkCase.id());
      assertTrue(
          TIME_QUALIFIERS.stream().anyMatch(benchmarkCase.question()::contains),
          "TEMPORAL 问句必须含时间限定词: " + benchmarkCase.id() + " -> " + benchmarkCase.question());
    }

    RagBenchmarkCase target = temporal.get(0);
    String gold = target.expectedChunkIds().iterator().next();
    Report report =
        RagQualityEvaluator.evaluate(List.of(target), benchmarkCase -> hit(gold, 0.75d), 3);
    CaseScore score = report.cases().get(0);
    assertEquals("TEMPORAL", score.category());
    assertEquals(1.0, score.recallAtK(), EPS);
    assertEquals(1.0, score.reciprocalRank(), EPS);
    assertEquals(1.0, score.citationPrecision(), EPS);
    assertTrue(score.hit());
    // 非边界用例口径零改动：ansC 仍取生成层要点覆盖度
    assertEquals(0.75, score.answerConsistency(), EPS);
  }

  @Test
  void emptyExpectedCaseScoresFullWhenAdapterReportsHonestRefusal() {
    RagBenchmarkCase edge =
        new RagBenchmarkCase(
            "RB-REFUSE", Category.EDGE, "关于虚构主题 QQ-1 的规定", Set.of(), List.of(), true);
    Report report =
        RagQualityEvaluator.evaluate(
            List.of(edge),
            benchmarkCase -> new CaseOutcome(List.of(), List.of(), 0d, 10L, 20L, 30, true, true),
            3);
    CaseScore score = report.cases().get(0);
    assertEquals(1.0, score.answerConsistency(), EPS, "诚实拒答 = 答案一致性满分");
    assertEquals(1.0, score.recallAtK(), EPS, "零召回 + expected 空 = 检索层满分（口径不变）");
    assertEquals(1.0, report.metrics().meanAnswerConsistency(), EPS);
  }

  @Test
  void emptyExpectedCaseScoresZeroWhenAnswerFabricatesFromNeighbouringChunks() {
    RagBenchmarkCase edge =
        new RagBenchmarkCase(
            "RB-FABRICATE", Category.EDGE, "关于虚构主题 QQ-2 的规定", Set.of(), List.of(), true);
    // 借近邻：检索过度召回 + 生成层给出看似相关（覆盖度自评 1.0）但未拒答的答案
    Report report =
        RagQualityEvaluator.evaluate(
            List.of(edge),
            benchmarkCase ->
                new CaseOutcome(
                    List.of("pricing-0", "policy-001"), List.of(), 1.0d, 10L, 20L, 30, true, false),
            3);
    CaseScore score = report.cases().get(0);
    assertEquals(0.0, score.answerConsistency(), EPS, "借近邻编造（未拒答）= 答案一致性 0 分，覆盖度自评不再给分");
    assertEquals(0.0, score.recallAtK(), EPS, "过度召回在检索层仍按旧口径记 0");
    assertEquals(0.0, score.precisionAtK(), EPS);
  }

  @Test
  void refusedSignalDoesNotTouchNormalCaseFormula() {
    RagBenchmarkCase text =
        new RagBenchmarkCase(
            "RB-NORMAL", Category.TEXT, "回款计划如何制定", Set.of("payment-plan-1"), List.of("账期"), true);
    // 适配层异常把 refused 填成 true：expected 非空的用例口径零改动，ansC 仍取覆盖度
    Report report =
        RagQualityEvaluator.evaluate(
            List.of(text),
            benchmarkCase ->
                new CaseOutcome(
                    List.of("payment-plan-1", "payment-plan-2"),
                    List.of(1),
                    0.5d,
                    10L,
                    20L,
                    30,
                    true,
                    true),
            3);
    CaseScore score = report.cases().get(0);
    assertEquals(0.5, score.answerConsistency(), EPS, "正常用例 ansC = 覆盖度，refused 不参与判分");
    assertEquals(1.0, score.recallAtK(), EPS);
    assertEquals(0.5, score.precisionAtK(), EPS);
    assertEquals(1.0, score.citationPrecision(), EPS, "引用精度公式不变");
    assertTrue(score.hit());
  }

  @Test
  void legacyOutcomeConstructionDefaultsToNotRefused() {
    // 兼容重载：未填报 refused 的旧构造按 false 处理 → expected 空用例不得凭空拿满分
    RagBenchmarkCase edge =
        new RagBenchmarkCase("RB-LEGACY", Category.EDGE, "嗨", Set.of(), List.of(), false);
    Report report =
        RagQualityEvaluator.evaluate(
            List.of(edge),
            benchmarkCase -> new CaseOutcome(List.of(), List.of(), 1.0d, 10L, 20L, 30, true),
            3);
    assertEquals(0.0, report.cases().get(0).answerConsistency(), EPS);
  }
}
