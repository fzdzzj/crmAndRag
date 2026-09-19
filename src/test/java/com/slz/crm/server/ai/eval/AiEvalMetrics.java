package com.slz.crm.server.ai.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** AI 工具选择与参数提取评测口径。 参数使用扁平路径做精确匹配；数值只比较数值大小，不比较书写格式。 */
public final class AiEvalMetrics {

  private AiEvalMetrics() {}

  public static AiEvalReport evaluate(
      List<AiEvalCase> cases, Function<AiEvalCase, AiEvalPrediction> predictor) {
    List<AiEvalCaseResult> results = new ArrayList<>();
    int toolHits = 0;
    int paramHits = 0;
    for (AiEvalCase aiCase : cases) {
      AiEvalCaseResult result = evaluateCase(aiCase, predictor.apply(aiCase));
      if (result.toolSelected()) {
        toolHits++;
        if (result.paramsMatched()) {
          paramHits++;
        }
      }
      results.add(result);
    }
    return new AiEvalReport(
        cases.size(),
        toolHits,
        paramHits,
        rate(toolHits, cases.size()),
        rate(paramHits, toolHits),
        List.copyOf(results));
  }

  private static AiEvalCaseResult evaluateCase(AiEvalCase aiCase, AiEvalPrediction prediction) {
    if (prediction == null) {
      return new AiEvalCaseResult(
          aiCase.id(),
          aiCase.expectedTool(),
          null,
          false,
          false,
          List.copyOf(aiCase.expectedParams().keySet()),
          List.of(),
          List.of(),
          "missing prediction");
    }
    boolean toolSelected = Objects.equals(aiCase.expectedTool(), prediction.toolName());
    if (!toolSelected) {
      return new AiEvalCaseResult(
          aiCase.id(),
          aiCase.expectedTool(),
          prediction.toolName(),
          false,
          false,
          List.of(),
          List.of(),
          List.of(),
          prediction.notes());
    }
    Map<String, Object> actual = prediction.arguments() == null ? Map.of() : prediction.arguments();
    ParamDiff diff = diffParams(aiCase.expectedParams(), actual);
    return new AiEvalCaseResult(
        aiCase.id(),
        aiCase.expectedTool(),
        prediction.toolName(),
        true,
        diff.missing().isEmpty() && diff.mismatched().isEmpty() && diff.unexpected().isEmpty(),
        diff.missing(),
        diff.mismatched(),
        diff.unexpected(),
        prediction.notes());
  }

  private static ParamDiff diffParams(Map<String, Object> expected, Map<String, Object> actual) {
    Map<String, Object> expectedFlat = flatten(expected);
    Map<String, Object> actualFlat = flatten(actual);
    List<String> missing = new ArrayList<>();
    List<String> mismatched = new ArrayList<>();
    List<String> unexpected = new ArrayList<>();
    expectedFlat.forEach(
        (key, value) -> {
          if (!actualFlat.containsKey(key)) {
            missing.add(key);
          } else if (!sameValue(value, actualFlat.get(key))) {
            mismatched.add(key);
          }
        });
    actualFlat.forEach(
        (key, value) -> {
          if (!expectedFlat.containsKey(key)) {
            unexpected.add(key);
          }
        });
    return new ParamDiff(List.copyOf(missing), List.copyOf(mismatched), List.copyOf(unexpected));
  }

  private static Map<String, Object> flatten(Map<String, Object> source) {
    Map<String, Object> target = new TreeMap<>();
    flatten("", source, target);
    return target;
  }

  @SuppressWarnings("unchecked")
  private static void flatten(String prefix, Object value, Map<String, Object> target) {
    String key = prefix.isEmpty() ? "" : prefix;
    if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        target.put(key, map);
        return;
      }
      map.forEach(
          (childKey, childValue) ->
              flatten(join(prefix, String.valueOf(childKey)), childValue, target));
    } else if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        target.put(key, list);
        return;
      }
      for (int i = 0; i < list.size(); i++) {
        flatten(join(prefix, "[" + i + "]"), list.get(i), target);
      }
    } else {
      target.put(key, value);
    }
  }

  private static String join(String prefix, String childKey) {
    if (prefix.isEmpty()) {
      return childKey;
    }
    return childKey.startsWith("[") ? prefix + childKey : prefix + "." + childKey;
  }

  private static boolean sameValue(Object left, Object right) {
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()))
          == 0;
    }
    return Objects.equals(left, right);
  }

  private static double rate(int numerator, int denominator) {
    return denominator == 0 ? 0 : (double) numerator / denominator;
  }

  private record ParamDiff(
      List<String> missing, List<String> mismatched, List<String> unexpected) {}
}
