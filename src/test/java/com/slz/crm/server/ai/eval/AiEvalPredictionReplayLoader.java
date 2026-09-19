package com.slz.crm.server.ai.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 加载离线回放预测文件。 回放文件不绑定模型实现，可由真实调用导出、日志转换或人工标注生成。 */
public final class AiEvalPredictionReplayLoader {

  private AiEvalPredictionReplayLoader() {}

  public static Map<String, AiEvalPrediction> load(Path replayFile) {
    ObjectMapper mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try {
      JsonNode root = mapper.readTree(Files.readString(replayFile));
      if (root == null || !root.has("predictions") || !root.get("predictions").isArray()) {
        throw new IllegalStateException("AI 回放文件必须包含 predictions 数组: " + replayFile);
      }
      Map<String, AiEvalPrediction> predictions = new LinkedHashMap<>();
      for (JsonNode node : root.get("predictions")) {
        AiEvalPrediction prediction = mapper.convertValue(node, AiEvalPrediction.class);
        String caseId = text(node, "caseId");
        if (caseId.isBlank() || prediction.toolName() == null || prediction.toolName().isBlank()) {
          throw new IllegalStateException("AI 回放记录必须包含 caseId 和 toolName");
        }
        predictions.put(caseId, prediction);
      }
      return Map.copyOf(predictions);
    } catch (IOException e) {
      throw new IllegalStateException("读取 AI 回放文件失败: " + replayFile, e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || !value.isValueNode() ? "" : value.asText();
  }
}
