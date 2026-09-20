package com.slz.crm.platform.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * PromQL 表达式引用抽取器（纯字符扫描，不依赖 Prometheus 客户端）。
 *
 * <p>把一段 PromQL 拆成三类引用，供监控资产一致性门禁逐条对账：
 *
 * <ul>
 *   <li>指标名：既不是函数名也不是聚合关键字、且后面不跟 {@code (} 的标识符；另外补上 {@code __name__=~"a|b"}
 *       这类正则选择器里出现的名字（面板按族聚合时用这种写法）。
 *   <li>label 名：{@code \{k=v\}} 选择器里的键，以及 {@code by (...) / without (...)} 的分组标签。
 *   <li>选择器明细：{@code (所属指标, label 键, 操作符, 值)}，用来抓"空值正向匹配"这类永远匹不到序列的条款。
 * </ul>
 *
 * <p>不是测试类（无 {@code *Test} 后缀），surefire 不收集。
 */
final class PromQlRefs {

  /** 后面紧跟 {@code (} 但这些括号里装的是 label 名而不是表达式。 */
  private static final Set<String> AGGREGATION_KEYWORDS =
      Set.of("by", "without", "on", "ignoring", "group_left", "group_right");

  /**
   * 既不是指标名也不是"名字后跟括号"的函数：保留字 + 可以写成 {@code sum by (x) (...)} 的聚合算子。
   *
   * <p>聚合算子必须显式登记：{@code sum}/{@code min} 这类词一旦落到"标识符且后面不是 ("分支"，就会被误判成指标名， 门禁会在完全合法的表达式上报假红（{@code
   * sum by (executor) (increase(x_total[1h]))} 正是这种写法）。
   */
  private static final Set<String> KEYWORDS =
      Set.of(
          "and",
          "or",
          "unless",
          "bool",
          "offset",
          "inf",
          "nan",
          "start",
          "end",
          "step",
          "sum",
          "avg",
          "count",
          "min",
          "max",
          "group",
          "stddev",
          "stdvar",
          "topk",
          "bottomk",
          "quantile",
          "count_values");

  private static final Pattern GO_TEMPLATE = Pattern.compile("\\{\\{.*?}}", Pattern.DOTALL);

  private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_.:]*");

  private final SortedSet<String> metricNames = new TreeSet<>();
  private final SortedSet<String> labelNames = new TreeSet<>();
  private final List<Selector> selectors = new ArrayList<>();

  private PromQlRefs() {}

  /** 一条 label 选择器：所属指标（可能为空，如裸 {@code \{__name__=~...}}）、键、操作符、值。 */
  record Selector(String metric, String label, String operator, String value) {}

  /**
   * 分析一段 PromQL。
   *
   * @param expression 表达式原文（Go 模板片段会被忽略）
   * @return 引用集合
   */
  static PromQlRefs analyze(String expression) {
    PromQlRefs refs = new PromQlRefs();
    refs.scan(GO_TEMPLATE.matcher(expression).replaceAll("  "));
    return refs;
  }

  SortedSet<String> metricNames() {
    return metricNames;
  }

  SortedSet<String> labelNames() {
    return labelNames;
  }

  List<Selector> selectors() {
    return selectors;
  }

  /** 正向着却永远匹不到任何序列的选择器：值被显式绑成空串。 */
  List<Selector> unsatisfiableSelectors() {
    List<Selector> unsatisfiable = new ArrayList<>();
    for (Selector selector : selectors) {
      boolean positive = "=".equals(selector.operator()) || "=~".equals(selector.operator());
      if (positive && selector.value().isEmpty()) {
        unsatisfiable.add(selector);
      }
    }
    return unsatisfiable;
  }

  private void scan(String src) {
    int i = 0;
    String owner = "";
    while (i < src.length()) {
      char c = src.charAt(i);
      if (c == '"' || c == '\'' || c == '`') {
        int end = indexOfOrEnd(src, c, i + 1);
        i = end + 1;
        continue;
      }
      if (c == '{') {
        int end = indexOfOrEnd(src, '}', i + 1);
        readSelectors(src.substring(i + 1, end), owner);
        i = end + 1;
        continue;
      }
      if (c == '[') {
        i = indexOfOrEnd(src, ']', i + 1) + 1;
        continue;
      }
      if (isIdentifierStart(c)) {
        int end = identifierEnd(src, i);
        String ident = src.substring(i, end);
        int next = skipSpaces(src, end);
        if (next < src.length() && src.charAt(next) == '(') {
          if (AGGREGATION_KEYWORDS.contains(ident)) {
            int close = indexOfOrEnd(src, ')', next + 1);
            for (String token :
                PrometheusExposition.splitTopLevel(src.substring(next + 1, close))) {
              if (METRIC_NAME.matcher(firstWord(token)).matches()) {
                labelNames.add(firstWord(token));
              }
            }
            i = close + 1;
          } else {
            owner = "";
            i = end;
          }
          continue;
        }
        if (KEYWORDS.contains(ident)) {
          owner = "";
          i = end;
          continue;
        }
        metricNames.add(ident);
        owner = ident;
        i = end;
        continue;
      }
      if (Character.isDigit(c)) {
        i = numberEnd(src, i);
        continue;
      }
      i++;
    }
  }

  private void readSelectors(String block, String owner) {
    for (String token : PrometheusExposition.splitTopLevel(block)) {
      int opStart = operatorStart(token);
      if (opStart <= 0) {
        continue;
      }
      String label = token.substring(0, opStart).strip();
      String operator = operatorOf(token.substring(opStart));
      String value = valueOf(token.substring(opStart + operator.length()));
      selectors.add(new Selector(owner, label, operator, value));
      labelNames.add(label);
      if ("__name__".equals(label)) {
        for (String candidate : value.split("\\|")) {
          String name =
              candidate.replaceAll("^[^a-zA-Z_:]+", "").replaceAll("[^a-zA-Z0-9_:]+$", "");
          if (!name.isEmpty() && METRIC_NAME.matcher(name).matches()) {
            metricNames.add(name);
          }
        }
      }
    }
  }

  private static int operatorStart(String token) {
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '=' || c == '!' || c == '~') {
        return i;
      }
    }
    return -1;
  }

  private static String operatorOf(String rest) {
    if (rest.startsWith("=~") || rest.startsWith("!~")) {
      return rest.substring(0, 2);
    }
    return rest.startsWith("!") ? "!=" : "=";
  }

  private static String valueOf(String raw) {
    String trimmed = raw.strip();
    if (trimmed.length() >= 2 && (trimmed.startsWith("\"") || trimmed.startsWith("'"))) {
      int close = trimmed.indexOf(trimmed.charAt(0), 1);
      if (close > 0) {
        return trimmed.substring(1, close);
      }
    }
    return trimmed;
  }

  private static String firstWord(String token) {
    String stripped = token.strip();
    int space = stripped.indexOf(' ');
    return space < 0 ? stripped : stripped.substring(0, space);
  }

  private static boolean isIdentifierStart(char c) {
    return Character.isLetter(c) || c == '_' || c == ':';
  }

  private static int identifierEnd(String src, int start) {
    int i = start;
    while (i < src.length()
        && (Character.isLetterOrDigit(src.charAt(i))
            || src.charAt(i) == '_'
            || src.charAt(i) == ':')) {
      i++;
    }
    return i;
  }

  private static int numberEnd(String src, int start) {
    int i = start;
    while (i < src.length()
        && (Character.isLetterOrDigit(src.charAt(i))
            || src.charAt(i) == '.'
            || src.charAt(i) == '+'
            || src.charAt(i) == '-')) {
      i++;
    }
    return i;
  }

  private static int skipSpaces(String src, int from) {
    int i = from;
    while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int indexOfOrEnd(String src, char target, int from) {
    int index = src.indexOf(target, Math.max(from, 0));
    return index < 0 ? src.length() : index;
  }
}
