package com.slz.crm.server.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 多轮工具选择用例组与 history 透传（expand-rag-eval-blind-spots 任务 1.3）。
 *
 * <p>锁两件事：黄金集里确有带 history 的多轮用例（且单轮用例 history 为空表——兼容重载不改既有 40+ 例）；评测路径把 history 随用例传入
 * predictor——省略式话术只有读到上一轮对象才选得对工具，因此"读 history 的 predictor 全中、无视 history 的 predictor 全错"即透传生效的判别证据。
 */
class AiEvalMultiTurnTest {

  /** 上下文感知 predictor：只依据 history 的上一轮问句决定工具（话术本身是省略式，不看 history 无法解析）。 */
  private static AiEvalPrediction contextAwarePredict(AiEvalCase aiCase) {
    String lastUser =
        aiCase.history().isEmpty() ? "" : aiCase.history().get(aiCase.history().size() - 1).user();
    if (lastUser.contains("合同")) {
      return new AiEvalPrediction("queryStageApproval", Map.of(), "history-context");
    }
    if (lastUser.contains("订单")) {
      return new AiEvalPrediction("getOrderDetail", Map.of(), "history-context");
    }
    return new AiEvalPrediction(
        "getCustomerCompanyDetail", Map.of("companyId", 55), "history-context");
  }

  @Test
  void goldenSetCarriesThreeMultiTurnCasesWithHistory() {
    List<AiEvalCase> cases = AiEvalCaseLoader.load();
    List<AiEvalCase> multiTurn =
        cases.stream().filter(aiCase -> !aiCase.history().isEmpty()).toList();

    assertThat(multiTurn).hasSize(3);
    assertThat(multiTurn)
        .extracting(AiEvalCase::id)
        .containsExactlyInAnyOrder(
            "multi-turn-stage-approval-reference",
            "multi-turn-order-followup",
            "multi-turn-clarification-customer-detail");
    for (AiEvalCase aiCase : multiTurn) {
      assertThat(aiCase.tags()).contains("multi-turn");
      assertThat(aiCase.history())
          .allSatisfy(
              turn -> {
                assertThat(turn.user()).isNotBlank();
                assertThat(turn.assistant()).isNotBlank();
              });
    }
    // 兼容重载：既有单轮用例的 history 必须为空表
    assertThat(cases)
        .filteredOn(aiCase -> !aiCase.tags().contains("multi-turn"))
        .allSatisfy(aiCase -> assertThat(aiCase.history()).isEmpty());
  }

  @Test
  void historyAwarePredictorSelectsToolsForEllipticalUtterances() {
    List<AiEvalCase> multiTurn =
        AiEvalCaseLoader.load().stream().filter(aiCase -> !aiCase.history().isEmpty()).toList();

    AiEvalReport report =
        AiEvalMetrics.evaluate(multiTurn, AiEvalMultiTurnTest::contextAwarePredict);

    assertThat(report.totalCases()).isEqualTo(3);
    assertThat(report.toolSelectionAccuracy()).isEqualTo(1d);
    assertThat(report.parameterAccuracy()).isEqualTo(1d);
    assertThat(report.results()).allSatisfy(result -> assertThat(result.toolSelected()).isTrue());
  }

  @Test
  void historyBlindPredictorFailsEllipticalUtterances() {
    List<AiEvalCase> multiTurn =
        AiEvalCaseLoader.load().stream().filter(aiCase -> !aiCase.history().isEmpty()).toList();

    // 无视 history 的固定猜测：三条多轮用例全部错选——证明分数来自 history 上下文而非话术本身
    AiEvalReport report =
        AiEvalMetrics.evaluate(
            multiTurn, aiCase -> new AiEvalPrediction("queryContract", Map.of(), "history-blind"));

    assertThat(report.toolSelectionAccuracy()).isEqualTo(0d);
  }
}
