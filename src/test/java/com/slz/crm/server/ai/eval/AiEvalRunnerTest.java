package com.slz.crm.server.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 离线回放加载与报告输出骨架验证。 */
class AiEvalRunnerTest {

  @TempDir Path tempDir;

  @Test
  void shouldLoadReplayAndWriteSummaryReport() throws Exception {
    Path replay = tempDir.resolve("predictions.json");
    Path reportFile = tempDir.resolve("report.json");
    Files.writeString(
        replay,
        """
                {
                  "predictions": [
                    { "caseId": "case-1", "toolName": "toolA", "arguments": { "id": 1 } }
                  ]
                }
                """);

    AiEvalReport result =
        AiEvalMetrics.evaluate(
            List.of(new AiEvalCase("case-1", "问句", "toolA", Map.of("id", 1), List.of())),
            aiCase -> AiEvalPredictionReplayLoader.load(replay).get(aiCase.id()));
    AiEvalRunner.writeReport(reportFile, result);

    JsonNode json = new ObjectMapper().readTree(Files.readString(reportFile));
    assertThat(json.get("totalCases").asInt()).isEqualTo(1);
    assertThat(json.get("toolHits").asInt()).isEqualTo(1);
    assertThat(json.get("results")).hasSize(1);
  }
}
