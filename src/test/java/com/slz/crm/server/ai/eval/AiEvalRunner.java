package com.slz.crm.server.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** AI 评测离线回放运行器。 类名不以 Test 结尾，因此不会随 mvn test 自动执行。 */
public final class AiEvalRunner {

  private AiEvalRunner() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: AiEvalRunner <predictions.json> [report.json]");
      System.exit(2);
      return;
    }
    List<AiEvalCase> cases = AiEvalCaseLoader.load();
    Map<String, AiEvalPrediction> predictions = AiEvalPredictionReplayLoader.load(Path.of(args[0]));
    // 多轮用例（expand-rag-eval-blind-spots 任务 1.3）：用例整体（含 history）传给 predictor，
    // 回放预测按用例 id 取，故多轮用例的预测必须是在含 history 上下文下产出；摘要行单列多轮条数以留痕。
    long multiTurnCases = cases.stream().filter(aiCase -> !aiCase.history().isEmpty()).count();
    AiEvalReport report = AiEvalMetrics.evaluate(cases, aiCase -> predictions.get(aiCase.id()));
    Path output = args.length > 1 ? Path.of(args[1]) : Path.of("target", "ai-eval", "report.json");
    writeReport(output, report);
    System.out.println(
        "AI eval report written to "
            + output.toAbsolutePath()
            + ", toolAccuracy="
            + report.toolSelectionAccuracy()
            + ", paramAccuracy="
            + report.parameterAccuracy()
            + ", multiTurnCases="
            + multiTurnCases);
  }

  static void writeReport(Path output, AiEvalReport report) throws Exception {
    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    Files.writeString(output, mapper.writeValueAsString(report));
  }
}
