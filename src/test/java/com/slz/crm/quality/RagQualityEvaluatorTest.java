package com.slz.crm.quality;

import com.slz.crm.quality.RagBenchmarkCase.Category;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 质量评估器测试（task17）：用确定性 fake 检索验证指标计算正确，本地可跑、无需外部依赖。
 * 期望值均手算，任何指标口径改动都会在此暴露。
 */
class RagQualityEvaluatorTest {

    private static final double EPS = 1e-9;

    // C1: TEXT，expected{c1,c2}，召回[c1,c3,c2]，引用[1,3] → 全召回、引用全 gold
    // C2: EDGE 零命中，expected{}，召回[]，无引用 → 诚实满分
    // C3: TEXT，expected{c9}，召回[c5]（miss），无引用 → 未命中、漏引
    private static final List<RagBenchmarkCase> SUITE = List.of(
            new RagBenchmarkCase("C1", Category.TEXT, "q1", Set.of("c1", "c2"), List.of("p1"), true),
            new RagBenchmarkCase("C2", Category.EDGE, "你好", Set.of(), List.of(), false),
            new RagBenchmarkCase("C3", Category.TEXT, "q3", Set.of("c9"), List.of("p3"), true));

    private static final Map<String, CaseOutcome> CANNED = Map.of(
            "C1", new CaseOutcome(List.of("c1", "c3", "c2"), List.of(1, 3), 1.0, 120, 800, 300, true),
            "C2", new CaseOutcome(List.of(), List.of(), 1.0, 50, 200, 40, true),
            "C3", new CaseOutcome(List.of("c5"), List.of(), 0.0, 100, 600, 200, true));

    private static Report evaluateCanned() {
        return RagQualityEvaluator.evaluate(SUITE, c -> CANNED.get(c.id()), 3);
    }

    @Test
    void scoresEachCaseByHandComputedValues() {
        Report report = evaluateCanned();

        var c1 = report.cases().get(0);
        assertEquals(1.0, c1.recallAtK(), EPS);            // {c1,c2} 全在 top3
        assertEquals(2.0 / 3.0, c1.precisionAtK(), EPS);   // 2 相关 / 3 召回
        assertEquals(1.0, c1.reciprocalRank(), EPS);        // c1 排第 1
        assertTrue(c1.hit());
        assertEquals(1.0, c1.citationPrecision(), EPS);     // 引用[1,3]→c1,c2 均 gold

        var c2 = report.cases().get(1);
        assertEquals(1.0, c2.recallAtK(), EPS);             // 边界诚实空召回=满分
        assertEquals(1.0, c2.citationPrecision(), EPS);     // 无引用 + expected 空 = 诚实
        assertFalse(c2.hit());                              // 边界不计命中

        var c3 = report.cases().get(2);
        assertEquals(0.0, c3.recallAtK(), EPS);             // miss
        assertEquals(0.0, c3.reciprocalRank(), EPS);
        assertFalse(c3.hit());
        assertEquals(0.0, c3.citationPrecision(), EPS);     // 有黄金却零引用 = 漏引
    }

    @Test
    void aggregatesMetricsAcrossSuite() {
        var m = evaluateCanned().metrics();
        assertEquals(3, m.caseCount());
        assertEquals(2.0 / 3.0, m.meanRecallAtK(), EPS);       // (1+1+0)/3
        assertEquals(0.5, m.hitRate(), EPS);                   // eligible=C1,C3；hits=C1 → 1/2
        assertEquals(2.0 / 3.0, m.mrr(), EPS);                 // (1+1+0)/3
        assertEquals((2.0 / 3.0 + 1.0 + 0.0) / 3.0, m.meanPrecisionAtK(), EPS);
        assertEquals(0.0, m.failureRate(), EPS);               // 全 success
        assertEquals(540, m.totalTokens());                    // 300+40+200
    }

    @Test
    void retrievalFailureCountsInFailureRateAndDoesNotBreakRun() {
        Report report = RagQualityEvaluator.evaluate(
                List.of(new RagBenchmarkCase("X", Category.TEXT, "q", Set.of("x1"), List.of(), true)),
                c -> {
                    throw new IllegalStateException("boom");
                }, 3);
        assertEquals(1.0, report.metrics().failureRate(), EPS);
        assertFalse(report.cases().get(0).success());
    }

    @Test
    void reportSerializesToComparableJson() {
        String json = evaluateCanned().toJson();
        assertTrue(json.contains("meanRecallAtK"));
        assertTrue(json.contains("hitRate"));
        assertTrue(json.contains("citationPrecision"));
        assertTrue(json.contains("\"C1\""));
    }

    @Test
    void standardSuiteCoversAllFourCategories() {
        Set<Category> categories = RagBenchmarkSuite.standard().stream()
                .map(RagBenchmarkCase::category)
                .collect(Collectors.toSet());
        assertTrue(categories.containsAll(List.of(Category.TEXT, Category.TABLE, Category.IMAGE, Category.EDGE)),
                "基准集必须覆盖文本/表格/图片/边界四类");
    }
}
