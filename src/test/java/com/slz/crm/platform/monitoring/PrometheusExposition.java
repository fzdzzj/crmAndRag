package com.slz.crm.platform.monitoring;

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Prometheus exposition text 与 fixture 清单的解析器。
 *
 * <p>不是测试类（无 {@code *Test} 后缀，surefire 不收集），由 {@link PrometheusExportTruthCaptureTest}（生产真相采集） 与
 * {@link MonitoringArtifactConsistencyTest}（消费端门禁）共用，保证"采集口径"与"校验口径"是同一套解析规则。
 */
final class PrometheusExposition {

  private PrometheusExposition() {}

  /**
   * 解析 exposition text（{@code /actuator/prometheus} 的响应体）。
   *
   * @param body 注册表导出的原文
   * @return 指标名 -> 该名下所有样本出现过的 label 键并集（均升序）
   */
  static Map<String, SortedSet<String>> parseExposition(String body) {
    Map<String, SortedSet<String>> meters = new TreeMap<>();
    for (String line : body.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int brace = trimmed.indexOf('{');
      int split = brace >= 0 ? brace : trimmed.indexOf(' ');
      if (split <= 0) {
        continue;
      }
      SortedSet<String> labels =
          meters.computeIfAbsent(trimmed.substring(0, split), key -> new TreeSet<>());
      int close = brace >= 0 ? trimmed.indexOf('}', brace) : -1;
      if (close > brace) {
        for (String token : splitTopLevel(trimmed.substring(brace + 1, close))) {
          int eq = token.indexOf('=');
          if (eq > 0) {
            labels.add(token.substring(0, eq).strip());
          }
        }
      }
    }
    return meters;
  }

  /**
   * 解析仓库内 fixture（{@code deploy/prometheus/expected-metric-names.txt}）。
   *
   * @param content fixture 全文，{@code #} 开头为注释
   * @return 指标名 -> label 键集合，格式 {@code name [k1,k2]}
   */
  static Map<String, SortedSet<String>> parseFixture(String content) {
    Map<String, SortedSet<String>> meters = new TreeMap<>();
    for (String line : content.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int open = trimmed.indexOf('[');
      if (open < 0) {
        meters.put(trimmed, new TreeSet<>());
        continue;
      }
      String name = trimmed.substring(0, open).strip();
      int close = trimmed.indexOf(']', open);
      String block = trimmed.substring(open + 1, close > open ? close : trimmed.length());
      SortedSet<String> labels = new TreeSet<>();
      for (String label : block.split(",")) {
        if (!label.isBlank()) {
          labels.add(label.strip());
        }
      }
      meters.put(name, labels);
    }
    return meters;
  }

  /** 按逗号切分，但忽略引号内的逗号（label 值里可以有逗号）。 */
  static java.util.List<String> splitTopLevel(String block) {
    java.util.List<String> tokens = new java.util.ArrayList<>();
    int depth = 0;
    StringBuilder token = new StringBuilder();
    for (int i = 0; i < block.length(); i++) {
      char c = block.charAt(i);
      if (c == '"') {
        depth = depth == 0 ? 1 : 0;
      }
      if (c == ',' && depth == 0) {
        tokens.add(token.toString().strip());
        token.setLength(0);
      } else {
        token.append(c);
      }
    }
    tokens.add(token.toString().strip());
    return tokens;
  }
}
