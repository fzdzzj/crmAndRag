package com.slz.crm.server.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AI 评测口径计算规则验证。 */
class AiEvalMetricsTest {

  @Test
  void evaluate_shouldCalculateToolAccuracyWithoutDoublePenalty() {
    List<AiEvalCase> cases =
        List.of(
            new AiEvalCase("hit", "问句", "toolA", Map.of("name", "A"), List.of()),
            new AiEvalCase("wrong-tool", "问句", "toolA", Map.of("name", "A"), List.of()),
            new AiEvalCase("missing", "问句", "toolB", Map.of("id", 1), List.of()));

    AiEvalReport report =
        AiEvalMetrics.evaluate(
            cases,
            aiCase ->
                switch (aiCase.id()) {
                  case "hit" -> new AiEvalPrediction("toolA", Map.of("name", "A"), null);
                  case "wrong-tool" -> new AiEvalPrediction("toolB", Map.of("id", 1), null);
                  default -> null;
                });

    assertThat(report.totalCases()).isEqualTo(3);
    assertThat(report.toolHits()).isEqualTo(1);
    assertThat(report.toolSelectionAccuracy()).isEqualTo(1.0 / 3.0);
    // 参数准确率只以工具命中用例为分母，避免工具错选同时计入两项失败
    assertThat(report.paramHits()).isEqualTo(1);
    assertThat(report.parameterAccuracy()).isEqualTo(1.0);
  }

  @Test
  void evaluate_shouldFlattenNestedParamsAndSeparateMismatchTypes() {
    AiEvalCase aiCase =
        new AiEvalCase(
            "nested",
            "问句",
            "toolA",
            Map.of("orders", List.of(Map.of("name", "工作站", "quantity", 10)), "extra", 1),
            List.of());

    AiEvalReport report =
        AiEvalMetrics.evaluate(
            List.of(aiCase),
            ignore ->
                new AiEvalPrediction(
                    "toolA",
                    Map.of(
                        "orders", List.of(Map.of("name", "网关", "quantity", 10.0)), "other", true),
                    null));

    AiEvalCaseResult result = report.results().get(0);
    assertThat(result.toolSelected()).isTrue();
    assertThat(result.paramsMatched()).isFalse();
    assertThat(result.missingParams()).containsExactly("extra");
    assertThat(result.mismatchedParams()).containsExactly("orders[0].name");
    assertThat(result.unexpectedParams()).containsExactly("other");
  }
}
