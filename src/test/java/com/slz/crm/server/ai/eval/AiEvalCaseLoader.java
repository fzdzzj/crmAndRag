package com.slz.crm.server.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.server.ai.eval.AiEvalCase.AiEvalTurn;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/** 加载并校验 AI 工具选择黄金用例集。 校验在加载时失败，避免评测报告基于残缺数据给出误导性结论。 */
public final class AiEvalCaseLoader {

  private static final String CASES_PATH = "ai-eval/cases.json";

  private AiEvalCaseLoader() {}

  public static List<AiEvalCase> load() {
    return load(CASES_PATH);
  }

  public static List<AiEvalCase> load(String classpathLocation) {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream input = new ClassPathResource(classpathLocation).getInputStream()) {
      JsonNode root = mapper.readTree(input);
      validateRoot(root);
      return parseCases(mapper, root);
    } catch (IOException e) {
      throw new IllegalStateException("加载 AI 评测用例失败: " + classpathLocation, e);
    }
  }

  private static void validateRoot(JsonNode root) {
    if (root == null || !root.isArray() || root.isEmpty()) {
      throw new IllegalStateException("AI 评测用例集必须是包含至少一条记录的数组");
    }
  }

  private static List<AiEvalCase> parseCases(ObjectMapper mapper, JsonNode root)
      throws IOException {
    Set<String> seenIds = new HashSet<>();
    List<AiEvalCase> cases = new ArrayList<>();
    for (JsonNode node : root) {
      String id = text(node, "id");
      if (id.isBlank() || !seenIds.add(id)) {
        throw new IllegalStateException("AI 评测用例 id 不能为空或重复: " + id);
      }
      String utterance = text(node, "utterance");
      String expectedTool = text(node, "expectedTool");
      if (utterance.isBlank() || expectedTool.isBlank()) {
        throw new IllegalStateException("AI 评测用例话术和期望工具不能为空: " + id);
      }
      Map<String, Object> params =
          mapper.convertValue(
              node.path("expectedParams"), new TypeReference<Map<String, Object>>() {});
      List<String> tags =
          mapper.convertValue(node.path("tags"), new TypeReference<List<String>>() {});
      cases.add(
          new AiEvalCase(
              id,
              utterance,
              expectedTool,
              params == null ? Map.of() : params,
              tags == null ? List.of() : tags,
              parseHistory(node, id)));
    }
    return List.copyOf(cases);
  }

  /**
   * 解析多轮上下文（expand-rag-eval-blind-spots 任务 1.3）：{@code history} 为可选数组，缺省即单轮用例；
   * 轮次任一字段为空视为用例集残缺，加载即失败。
   */
  private static List<AiEvalTurn> parseHistory(JsonNode node, String caseId) {
    JsonNode historyNode = node.path("history");
    if (historyNode.isMissingNode() || historyNode.isNull()) {
      return List.of();
    }
    if (!historyNode.isArray()) {
      throw new IllegalStateException("AI 评测用例 history 必须是数组: " + caseId);
    }
    List<AiEvalTurn> turns = new ArrayList<>();
    for (JsonNode turn : historyNode) {
      String user = text(turn, "user");
      String assistant = text(turn, "assistant");
      if (user.isBlank() || assistant.isBlank()) {
        throw new IllegalStateException("AI 评测用例 history 轮次缺 user/assistant: " + caseId);
      }
      turns.add(new AiEvalTurn(user, assistant));
    }
    return List.copyOf(turns);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || !value.isValueNode() ? "" : value.asText();
  }
}
