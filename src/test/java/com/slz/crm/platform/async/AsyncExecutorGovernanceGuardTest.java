package com.slz.crm.platform.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 异步执行器治理静态守卫（卡 E async-executor-governance 任务 4）：扫描 src/main/java 全部源码， 任何 {@code
 * supplyAsync(}/{@code runAsync(} 调用若未显式传 executor 实参即判红——未传 executor 会落 JVM commonPool：无界、无拒绝策略、不经
 * {@link TaskExecutorMetricsBinder}，阻塞式远程调用占满会拖累整个 JVM。
 *
 * <p>纯 JVM 文本扫描（无 Docker 无外网，surefire 日常跑必红必绿都可信）。匹配口径（F-5 标识符边界）：调用名前一 字符不属于 [A-Za-z0-9_$]
 * 即命中，覆盖点前缀、静态导入、显式类型实参（{@code Foo.<String>supplyAsync(}）与括号前 空白四种形态；前缀粘连名（{@code
 * mySupplyAsync(}）与方法引用（{@code Foo::supplyAsync}）不命中。解析口径：从调用开括号起按
 * 括号/花括号/方括号深度找实参列表，跳过字符串/字符/文本块/注释；逗号只有落在实参顶层（括号深度 1 且花括号/方括号 深度 0）才算"第二个实参已传"——块 lambda
 * 体、嵌套调用与多行续行实参（如 {@code supplyAsync(() -> ...,\n routeExecutor)}） 都能正确识别。嵌套的异步提交点同样递归校验。
 *
 * <p>合法例外必须登记进 {@link #ALLOWLIST}（文件 → 理由，登记即代表 owner 已拍板）。当前为零豁免： 卡 E 修完后全仓 7 处
 * supplyAsync/runAsync 全部显式传受治理的有界池。
 */
class AsyncExecutorGovernanceGuardTest {

  private static final Path MAIN_SOURCES = Paths.get("src/main/java");

  /** 豁免登记：允许无 executor 提交的主源码文件（相对 src/main/java 的 / 分隔路径）→ 理由。当前为零豁免。 */
  private static final Map<String, String> ALLOWLIST = Map.of();

  @Test
  void everyAsyncSubmissionInMainSourcesCarriesExplicitExecutor() throws IOException {
    GuardResult result = scanMainSources();
    assertTrue(result.fileCount() >= 100, "扫描到的主源码文件过少，守卫扫描路径失效（应在仓库根目录运行）: " + result.fileCount());
    assertTrue(result.callCount() >= 1, "守卫一个 supplyAsync/runAsync 提交点都没扫到（解析器或扫描路径失效）——本守卫不许静默假绿");
    assertTrue(
        result.violations().isEmpty(),
        "以下 supplyAsync/runAsync 调用未显式传 executor（将落 JVM commonPool：无界、无拒绝策略、"
            + "不经 TaskExecutorMetricsBinder），必须改为传入受治理的有界池：\n  "
            + String.join("\n  ", result.violations()));
  }

  /** 解析器自证（单参判红 / 双参判绿，含多行续行、块 lambda、字符串与注释干扰、嵌套调用）。 */
  @Test
  void parserFlagsMissingExecutorAndAcceptsExecutorOnContinuationLines() {
    ScanOutcome singleLine =
        scanSource("class T { Object f() { return Foo.supplyAsync(() -> bar(1, 2)); } }");
    assertEquals(List.of(1), singleLine.violationLines());
    assertEquals(1, singleLine.callCount());

    ScanOutcome runAsync =
        scanSource("class T { void f() { Foo.runAsync(() -> log.info(\"x\")); } }");
    assertEquals(List.of(1), runAsync.violationLines());

    String executorOnContinuation =
        """
        class T {
          Object f() {
            return CompletableFuture.supplyAsync(
                () -> recaller.recall(
                    variantQuery, embeddingService.embed(variantQuery), knowledgeBaseIds),
                routeExecutor);
          }
        }
        """;
    assertEquals(List.of(), scanSource(executorOnContinuation).violationLines());

    String blockLambdaWithExecutor =
        """
        class T {
          void f() {
            CompletableFuture.supplyAsync(
                () -> {
                  int a = 1, b = 2;
                  ModelCallResult<String> r = provider.chat(new Prompt(m), options);
                  return r == null ? null : r.content();
                },
                llmAuxExecutor);
          }
        }
        """;
    assertEquals(List.of(), scanSource(blockLambdaWithExecutor).violationLines());
  }

  /** 解析器自证：字符串/注释/字符字面量里的调用字样不计入。 */
  @Test
  void parserIgnoresCallShapedTextInsideStringsAndComments() {
    String source =
        """
        class T {
          String hint = "please use CompletableFuture.supplyAsync(task) here";
          // Foo.supplyAsync(() -> x) in a line comment
          /* Bar.runAsync(task) in a block comment */
          char openParen = '(';
          void f() { CompletableFuture.supplyAsync(() -> x(), executor); }
        }
        """;
    ScanOutcome outcome = scanSource(source);
    assertEquals(1, outcome.callCount());
    assertEquals(List.of(), outcome.violationLines());
  }

  /** 解析器自证：嵌套异步提交点同样被校验（外层缺 executor 与内层缺 executor 都要红）。 */
  @Test
  void parserFlagsNestedSubmissionsMissingExecutor() {
    String bothMissing =
        "class T { void f() { CompletableFuture.supplyAsync(() -> Foo.supplyAsync(() -> x())); } }";
    ScanOutcome nested = scanSource(bothMissing);
    assertEquals(2, nested.callCount());
    assertEquals(List.of(1, 1), nested.violationLines());

    String bothPresent =
        "class T { void f() { Foo.supplyAsync(() -> Bar.runAsync(() -> x(), inner), outer); } }";
    ScanOutcome ok = scanSource(bothPresent);
    assertEquals(2, ok.callCount());
    assertEquals(List.of(), ok.violationLines());
  }

  /**
   * 解析器自证（F-5 回归）：静态导入、显式类型实参、括号前空白三种逃逸形态必须全部判红。探针原文与主 agent 实测的 work/_cardE-probe-content.java
   * 逐字一致（4 形态分别在行 11/15/19/23；行 3 的静态 import 声明不算调用）。
   */
  @Test
  void parserFlagsStaticImportTypeArgumentAndSpacedEscapeForms() {
    String probe =
        """
        package com.slz.crm.platform.async;

        import static java.util.concurrent.CompletableFuture.supplyAsync;

        import java.util.concurrent.CompletableFuture;

        /** 主 agent 复验探针（临时文件，验完即删；不进版本库）。 */
        class ZzGuardProbeTmp {

          CompletableFuture<String> dottedNoExecutor() {
            return CompletableFuture.supplyAsync(() -> "a");
          }

          CompletableFuture<String> staticImportNoExecutor() {
            return supplyAsync(() -> "b");
          }

          CompletableFuture<String> typeArgNoExecutor() {
            return CompletableFuture.<String>supplyAsync(() -> "c");
          }

          CompletableFuture<String> spacedNoExecutor() {
            return CompletableFuture.supplyAsync (() -> "d");
          }
        }""";
    ScanOutcome outcome = scanSource(probe);
    assertEquals(List.of(11, 15, 19, 23), outcome.violationLines());
    assertEquals(4, outcome.callCount());
  }

  /** 解析器自证（F-5 回归）：前缀粘连名、方法引用、import 声明与字符串/注释内的字样不得命中（不引入误报）。 */
  @Test
  void parserDoesNotFlagNonCallShapesOfSameNames() {
    String source =
        """
        import static java.util.concurrent.CompletableFuture.supplyAsync;

        class T {
          String hint = "use CompletableFuture.supplyAsync (() -> x) or CompletableFuture.<T>supplyAsync(y)";
          // supplyAsync(() -> x) in a line comment
          /* runAsync(() -> y) in a block comment */
          void mySupplyAsync(Runnable r) {}
          void xsRunAsync(Runnable r) {}
          void g() {
            mySupplyAsync(() -> {});
            this.xsRunAsync(() -> {});
            _supplyAsync();
            $runAsync();
            Runnable ref = CompletableFuture::supplyAsync;
            java.util.function.Supplier<Runnable> ref2 = CompletableFuture::runAsync;
          }
        }""";
    ScanOutcome outcome = scanSource(source);
    assertEquals(0, outcome.callCount());
    assertEquals(List.of(), outcome.violationLines());
  }

  // ---------- 扫描实现 ----------

  private record GuardResult(int fileCount, int callCount, List<String> violations) {}

  private record ScanOutcome(int callCount, List<Integer> violationLines) {}

  private record CallScan(int endIndex, int endLine, boolean hasExecutorArg) {}

  /** 可变计数器：递归扫描时共享（记录扫到的提交点总数，供"守卫不许假绿"断言用）。 */
  private static final class CallCounter {
    int value;
  }

  private static GuardResult scanMainSources() throws IOException {
    List<String> violations = new ArrayList<>();
    CallCounter counter = new CallCounter();
    int fileCount = 0;
    try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
      List<Path> files = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
      for (Path file : files) {
        fileCount++;
        String relative = MAIN_SOURCES.relativize(file).toString().replace('\\', '/');
        ScanOutcome outcome = scanSource(Files.readString(file, StandardCharsets.UTF_8));
        counter.value += outcome.callCount();
        if (!outcome.violationLines().isEmpty() && !ALLOWLIST.containsKey(relative)) {
          for (Integer line : outcome.violationLines()) {
            violations.add(relative + ":" + line);
          }
        }
      }
    }
    return new GuardResult(fileCount, counter.value, List.copyOf(violations));
  }

  static ScanOutcome scanSource(String content) {
    List<Integer> violationLines = new ArrayList<>();
    CallCounter counter = new CallCounter();
    int line = 1;
    int i = 0;
    int n = content.length();
    while (i < n) {
      char c = content.charAt(i);
      if (c == '\n') {
        line++;
        i++;
      } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
        i = endOfLineComment(content, i);
      } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
        int[] skipped = skipBlockComment(content, i);
        line += skipped[1];
        i = skipped[0];
      } else if (c == '"') {
        int[] skipped = skipStringLiteral(content, i);
        line += skipped[1];
        i = skipped[0];
      } else if (c == '\'') {
        int[] skipped = skipCharLiteral(content, i);
        i = skipped[0];
      } else {
        String call = callNameAt(content, i);
        if (call != null) {
          counter.value++;
          int callLine = line;
          CallScan scan =
              scanCallArguments(content, i + call.length() - 1, line, violationLines, counter);
          if (!scan.hasExecutorArg()) {
            violationLines.add(callLine);
          }
          line = scan.endLine();
          i = scan.endIndex();
        } else {
          i++;
        }
      }
    }
    return new ScanOutcome(counter.value, List.copyOf(violationLines));
  }

  /** 从调用开括号起扫描实参列表：返回扫完后的下标/行号/是否出现过顶层第二实参（executor）。嵌套提交点递归校验。 */
  private static CallScan scanCallArguments(
      String content,
      int openParenIndex,
      int startLine,
      List<Integer> violationLines,
      CallCounter counter) {
    int depth = 1;
    int braceDepth = 0;
    int bracketDepth = 0;
    boolean hasExecutorArg = false;
    int line = startLine;
    int i = openParenIndex + 1;
    int n = content.length();
    while (i < n && depth > 0) {
      char c = content.charAt(i);
      if (c == '\n') {
        line++;
        i++;
      } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
        i = endOfLineComment(content, i);
      } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
        int[] skipped = skipBlockComment(content, i);
        line += skipped[1];
        i = skipped[0];
      } else if (c == '"') {
        int[] skipped = skipStringLiteral(content, i);
        line += skipped[1];
        i = skipped[0];
      } else if (c == '\'') {
        int[] skipped = skipCharLiteral(content, i);
        i = skipped[0];
      } else if (c == '(') {
        depth++;
        i++;
      } else if (c == ')') {
        depth--;
        i++;
      } else if (c == '{') {
        braceDepth++;
        i++;
      } else if (c == '}') {
        braceDepth--;
        i++;
      } else if (c == '[') {
        bracketDepth++;
        i++;
      } else if (c == ']') {
        bracketDepth--;
        i++;
      } else {
        String nested = callNameAt(content, i);
        if (nested != null) {
          counter.value++;
          int nestedLine = line;
          CallScan nestedScan =
              scanCallArguments(content, i + nested.length() - 1, line, violationLines, counter);
          if (!nestedScan.hasExecutorArg()) {
            violationLines.add(nestedLine);
          }
          line = nestedScan.endLine();
          i = nestedScan.endIndex();
        } else {
          if (c == ',' && depth == 1 && braceDepth == 0 && bracketDepth == 0) {
            hasExecutorArg = true;
          }
          i++;
        }
      }
    }
    return new CallScan(i, line, hasExecutorArg);
  }

  private static String callNameAt(String content, int index) {
    String call = identifierCallAt(content, index, "supplyAsync");
    if (call != null) {
      return call;
    }
    return identifierCallAt(content, index, "runAsync");
  }

  /**
   * 标识符边界匹配（F-5）：{@code name} 前一字符不属于标识符字符集 [A-Za-z0-9_$] 且后面（允许空格/制表符）紧跟
   * 开括号才算调用——点前缀、静态导入、显式类型实参、括号前空白都命中；前缀粘连名（{@code mySupplyAsync(}、 {@code _runAsync(}）与方法引用（{@code
   * Foo::supplyAsync}，后面不紧跟开括号）不命中。返回从名字首字符到开括号 的切片，长度满足调用方契约 {@code index + 返回值长度 - 1 == 开括号下标}。
   */
  private static String identifierCallAt(String content, int index, String name) {
    if (!content.startsWith(name, index)) {
      return null;
    }
    if (index > 0 && isIdentifierPart(content.charAt(index - 1))) {
      return null;
    }
    int i = index + name.length();
    int n = content.length();
    while (i < n && (content.charAt(i) == ' ' || content.charAt(i) == '\t')) {
      i++;
    }
    if (i >= n || content.charAt(i) != '(') {
      return null;
    }
    return content.substring(index, i + 1);
  }

  private static boolean isIdentifierPart(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '$';
  }

  /** 从开引号起跳过字符串字面量或文本块，返回 {结束下标, 消耗换行数}。 */
  private static int[] skipStringLiteral(String content, int start) {
    if (content.startsWith("\"\"\"", start)) {
      return skipTextBlock(content, start);
    }
    int i = start + 1;
    int n = content.length();
    int newlines = 0;
    while (i < n) {
      char c = content.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '"') {
        return new int[] {i + 1, newlines};
      }
      if (c == '\n') {
        newlines++;
      }
      i++;
    }
    return new int[] {i, newlines};
  }

  private static int[] skipTextBlock(String content, int start) {
    int i = start + 3;
    int n = content.length();
    int newlines = 0;
    while (i < n) {
      char c = content.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '"' && content.startsWith("\"\"\"", i)) {
        return new int[] {i + 3, newlines};
      }
      if (c == '\n') {
        newlines++;
      }
      i++;
    }
    return new int[] {i, newlines};
  }

  private static int[] skipCharLiteral(String content, int start) {
    int i = start + 1;
    int n = content.length();
    while (i < n) {
      char c = content.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '\'') {
        return new int[] {i + 1, 0};
      } else {
        i++;
      }
    }
    return new int[] {i, 0};
  }

  private static int endOfLineComment(String content, int start) {
    int end = content.indexOf('\n', start);
    return end < 0 ? content.length() : end;
  }

  private static int[] skipBlockComment(String content, int start) {
    int end = content.indexOf("*/", start + 2);
    int stop = end < 0 ? content.length() : end + 2;
    return new int[] {stop, countNewlines(content, start, stop)};
  }

  private static int countNewlines(String content, int from, int to) {
    int count = 0;
    for (int i = from; i < to; i++) {
      if (content.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }
}
