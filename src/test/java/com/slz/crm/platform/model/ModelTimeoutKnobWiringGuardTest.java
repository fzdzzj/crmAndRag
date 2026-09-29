package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 卡 G 永久回归锁 2：旋钮不许再空转。F-3b 的原始缺陷是 {@code platform.ai.model.timeout-seconds} 被写进 application.yml
 * 并对运维文档化、却零消费者——调它没有任何效果。
 *
 * <p>本守卫锁的是旋钮被接线，不是超时值正确；值的正确性由 buildRequestFactory 单测负责。
 *
 * <p>纯 JVM 源码扫描（无 Docker 无外网）：扫 {@code src/main/java} 全部源码里 {@code getTimeoutSeconds()} / {@code
 * getConnectTimeoutSeconds()} 的调用点，先剥掉注释与字符串/字符字面量（否则注释里提一句方法名就能把守卫喂绿）。 除 {@code
 * ModelProviderProperties.java} 自身（accessor 定义处）外命中数为 0 即判红。
 */
class ModelTimeoutKnobWiringGuardTest {

  private static final Path MAIN_SOURCES = Paths.get("src/main/java");

  private static final String DEFINITION_FILE =
      "com/slz/crm/platform/model/ModelProviderProperties.java";

  /** 每条接线各自独立：删任一路（哪怕另一路还在消费该属性）也必须判红，且要指名缺的是哪一路。 */
  private static final Map<String, String> REQUIRED_WIRING_SITES =
      Map.of(
          "com/slz/crm/platform/model/CompatibleModeSupport.java",
              "同步 compatible-mode 路（RestClient）",
          "com/slz/crm/platform/model/AiModelRestClientCustomizer.java",
              "容器 RestClient.Builder 路（dashscope 原生）");

  private static final String RENAMED_HINT =
      "若属性或 accessor 被改名，请一并更新本守卫；若确实要下线该旋钮，"
          + "必须同时删掉 application.yml 里的键与运维文档说明，不能留下'文档化了却不生效'的空转键。";

  @Test
  void timeoutKnobsStayWiredToRealCallers() throws IOException {
    List<String> files = mainSourceFiles();
    assertThat(files.size()).as("扫描到的主源码文件过少，守卫扫描路径失效（应在仓库根目录运行）").isGreaterThanOrEqualTo(100);

    Map<String, List<Integer>> hitsByFile = hitLinesByFile(files);
    Map<String, List<Integer>> consumers = new LinkedHashMap<>(hitsByFile);
    consumers.remove(DEFINITION_FILE);

    int totalConsumerHits = consumers.values().stream().mapToInt(List::size).sum();
    assertThat(totalConsumerHits)
        .as(
            "platform.ai.model.timeout-seconds / connect-timeout-seconds 在 %s 之外一个消费点都没有"
                + "（旋钮空转，调它不生效）。处置：把超时接回同步模型调用（CompatibleModeSupport 的 RestClient"
                + " 或容器 RestClient.Builder 定制器），或按 %s 的说明同步下线该键。命中明细=%s",
            DEFINITION_FILE, DEFINITION_FILE, hitsByFile)
        .isGreaterThan(0);

    for (Map.Entry<String, String> required : REQUIRED_WIRING_SITES.entrySet()) {
      assertThat(consumers.getOrDefault(required.getKey(), List.of()))
          .as(
              "%s 不再消费超时旋钮（%s）——该路已退回'没有超时'，" + "一次挂死的外呼会占住线程直到 TCP 层自己超时。%s",
              required.getKey(), required.getValue(), RENAMED_HINT)
          .isNotEmpty();
    }
  }

  private static List<String> mainSourceFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
      return paths
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .map(path -> MAIN_SOURCES.relativize(path).toString().replace('\\', '/'))
          .toList();
    }
  }

  private static Map<String, List<Integer>> hitLinesByFile(List<String> files) throws IOException {
    Map<String, List<Integer>> result = new LinkedHashMap<>();
    for (String relative : files) {
      String code =
          stripCommentsAndLiterals(
              Files.readString(MAIN_SOURCES.resolve(relative), StandardCharsets.UTF_8));
      List<Integer> lines = linesContainingKnobAccessor(code);
      if (!lines.isEmpty()) {
        result.put(relative, lines);
      }
    }
    return result;
  }

  private static List<Integer> linesContainingKnobAccessor(String code) {
    List<Integer> hits = new ArrayList<>();
    String[] lines = code.split("\n", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].contains("getTimeoutSeconds()")
          || lines[index].contains("getConnectTimeoutSeconds()")) {
        hits.add(index + 1);
      }
    }
    return hits;
  }

  /** 剥掉行注释/块注释/字符串/字符字面量/文本块，保留换行以维持行号；留下的骨架只含"真的是代码"的字符。 */
  static String stripCommentsAndLiterals(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
        while (i < n && source.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
        i += 2;
        while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
          if (source.charAt(i) == '\n') {
            out.append('\n');
          }
          i++;
        }
        i = Math.min(n, i + 2);
      } else if (c == '"') {
        i =
            source.startsWith("\"\"\"", i)
                ? skipQuoted(source, i, "\"\"\"")
                : skipQuoted(source, i, "\"");
      } else if (c == '\'') {
        i = skipQuoted(source, i, "'");
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** 从引号处跳到闭合处（文本块按三引号闭合），跳过的换行原样补回。 */
  private static int skipQuoted(String source, int start, String terminator) {
    int i = start + terminator.length();
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (source.startsWith(terminator, i)) {
        return i + terminator.length();
      }
      i++;
    }
    return n;
  }
}
