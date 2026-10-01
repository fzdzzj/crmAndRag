package com.slz.crm.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Git 换行契约静态守卫（卡 P-h）：把「Java 源码在 Git blob 层统一纯 LF」钉进 surefire 门禁。
 *
 * <p>纯 JVM 扫描（零 Docker、零外网），日常 {@code mvn test} 必跑，红绿双向可信。三道闸：① 根 {@code .gitattributes} 必须声明非注释行
 * {@code *.java text eol=lf}；② 既有 {@code *.sh text eol=lf} 与 {@code .githooks/* text eol=lf}
 * 契约不得被覆盖或删除；③ 受控的 {@code *.java} 在索引/blob 层必须全部为 {@code i/lf} ——Windows 工作区因 {@code
 * core.autocrlf=true} 显示 {@code w/crlf} 属正常，此处只锁 blob。
 */
class GitAttributesContractGuardTest {

  private static final Path GITATTRIBUTES = Paths.get(".gitattributes");

  private static final String JAVA_LF_RULE = "*.java text eol=lf";

  private static final String SHELL_LF_RULE = "*.sh text eol=lf";

  private static final String HOOK_LF_RULE = ".githooks/* text eol=lf";

  @Test
  void gitattributesMustEnforceJavaLfContract() throws IOException {
    assertTrue(
        Files.isRegularFile(GITATTRIBUTES),
        "仓库根目录缺少 .gitattributes，Java 跨平台 LF 契约无处落地：期望非注释行「" + JAVA_LF_RULE + "」");
    List<String> rules = effectiveRuleLines(GITATTRIBUTES);
    assertTrue(
        rules.contains(JAVA_LF_RULE),
        ".gitattributes 必须包含非注释行「"
            + JAVA_LF_RULE
            + "」，把 Java 源码在 Git blob 层锁死为纯 LF；当前生效规则："
            + rules);
  }

  @Test
  void gitattributesMustRetainShellAndHookContracts() throws IOException {
    List<String> rules = effectiveRuleLines(GITATTRIBUTES);
    assertTrue(
        rules.contains(SHELL_LF_RULE),
        ".gitattributes 丢失了既有 shell 脚本 LF 契约「" + SHELL_LF_RULE + "」（bash 遇 CRLF 会直接语法报错）");
    assertTrue(
        rules.contains(HOOK_LF_RULE),
        ".gitattributes 丢失了既有 git 钩子 LF 契约「" + HOOK_LF_RULE + "」（钩子在 bash 下执行，必须纯 LF）");
  }

  @Test
  void trackedJavaBlobsMustBePureLf() throws IOException, InterruptedException {
    List<String> lines = gitLsFilesEolJava();
    assertFalse(lines.isEmpty(), "git ls-files --eol 没有列出任何受控 *.java，守卫未在仓库根目录运行或扫描口径失效，禁止静默假绿");
    List<String> offenders = new ArrayList<>();
    for (String line : lines) {
      // 每行形如「i/lf    w/crlf  attr/text eol=lf<TAB>path」，首列即索引/blob 层换行属性。
      if (!line.startsWith("i/lf")) {
        offenders.add(line);
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "以下受控 *.java 在 Git 索引/blob 层不是纯 LF（首列应为 i/lf），必须修复后提交：\n  "
            + String.join("\n  ", offenders));
  }

  /** 读取 .gitattributes 的「生效规则」：去掉空行与 # 注释，逐行 trim（兼容 Windows CRLF 工作区）。 */
  private static List<String> effectiveRuleLines(Path file) throws IOException {
    List<String> rules = new ArrayList<>();
    for (String raw : Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)) {
      String line = raw.trim();
      if (!line.isEmpty() && !line.startsWith("#")) {
        rules.add(line);
      }
    }
    return rules;
  }

  /** 调 git ls-files --eol 列出受控 *.java 及其索引/工作区换行属性，stdout 逐行返回。 */
  private static List<String> gitLsFilesEolJava() throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("git", "ls-files", "--eol", "--", "*.java")
            .redirectErrorStream(false)
            .start();
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          lines.add(line);
        }
      }
    }
    int exit = process.waitFor();
    assertTrue(exit == 0, "git ls-files --eol 退出码 " + exit + "，无法读取 blob 换行属性");
    return lines;
  }
}
