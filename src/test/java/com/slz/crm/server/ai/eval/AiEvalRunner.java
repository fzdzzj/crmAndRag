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
    AiEvalReport report = AiEvalMetrics.evaluate(cases, aiCase -> predictions.get(aiCase.id()));
    Path output = args.length > 1 ? Path.of(args[1]) : Path.of("target", "ai-eval", "report.json");
    writeReport(output, report);
    System.out.println(
        "AI eval report written to "
            + output.toAbsolutePath()
            + ", toolAccuracy="
            + report.toolSelectionAccuracy()
            + ", paramAccuracy="
            + report.parameterAccuracy());
  }

  static void writeReport(Path output, AiEvalReport report) throws Exception {
    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    Files.writeString(output, mapper.writeValueAsString(report));
  }
}
