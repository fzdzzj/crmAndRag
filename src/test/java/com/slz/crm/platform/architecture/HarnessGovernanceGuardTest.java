package com.slz.crm.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 工程治理与工作树规约静态守卫（卡 P-g，回应 2026-09-30 /better-harness 评审 Finding 1 与 Finding 4）： 纯 JVM 文本/文件系统扫描（无
 * Docker 无外网，surefire 日常跑必红必绿都可信），把两条治理约定锁成永久门禁。
 *
 * <p>三道闸：① 根 {@code .gitignore} 必须用 {@code work/*} 收敛 work/ 草稿层，并在其后显式放行正式沉淀 （{@code
 * !work/task-card-*.md}、{@code !work/handoff-*.md}）——放行被删或顺序被调换即红（gitignore 语义是
 * 后出现者覆盖先出现者，顺序本身就是契约）；② 根 {@code AGENTS.md} 必须固化「工作树拓扑与同步纪律」小节， 含权威工作树标记、主协作树标记与未授权禁止 git push
 * 铁律，新会话不再靠口头补课；③ 仓库工作区静态扫描， 禁止出现 {@code *.java.bak} 备份或临时 {@code .class} 编译残留（构建输出与依赖目录
 * target/、build/、 node_modules/、.worktrees/ 及版本库元数据 .git/ 除外）。
 */
class HarnessGovernanceGuardTest {

  private static final Path GITIGNORE = Paths.get(".gitignore");

  private static final Path AGENTS_DOC = Paths.get("AGENTS.md");

  /** 权威工作树目录名：主干所在，代码实现、门禁运行、合并与 push 的唯一法定工作树。 */
  private static final String AUTHORITATIVE_WORKTREE = "crmAndRag-merge-add-knowledge-admin-api";

  /** 扫描时跳过的非源码目录：构建输出、依赖缓存、并行 worktree 与版本库元数据。 */
  private static final Set<String> SKIPPED_DIRECTORY_NAMES =
      Set.of(".git", "target", "build", "node_modules", ".worktrees");

  /** 禁止残留的文件后缀：源码备份与编译产物（工作区里出现即视为意外跟踪的临时残留）。 */
  private static final List<String> FORBIDDEN_ARTIFACT_SUFFIXES = List.of(".java.bak", ".class");

  /** 扫描到的常规文件数下界：低于该值说明没在仓库根目录运行，守卫会静默假绿。 */
  private static final int MIN_SCANNED_FILES = 200;

  @Test
  void gitignoreEnforcesWorkDirectoryContract() throws IOException {
    String content = Files.readString(GITIGNORE, StandardCharsets.UTF_8);
    assertTrue(
        content.contains("临时草稿与会话交接"), ".gitignore 必须保留 work/ 收敛规则的意图声明注释（收敛 09-30 评审 Finding 1）");
    int ignoreRuleLine = lineIndexOf(content, "work/*");
    int taskCardAllowLine = lineIndexOf(content, "!work/task-card-*.md");
    int handoffAllowLine = lineIndexOf(content, "!work/handoff-*.md");
    assertTrue(ignoreRuleLine >= 0, ".gitignore 必须包含 work/* 规则，把 work/ 下的日志/备份/编译残留收敛为忽略");
    assertTrue(taskCardAllowLine >= 0, ".gitignore 必须显式放行 !work/task-card-*.md（正式任务卡受 Git 跟踪与保护）");
    assertTrue(handoffAllowLine >= 0, ".gitignore 必须显式放行 !work/handoff-*.md（跨会话交接快照受 Git 跟踪与保护）");
    assertTrue(
        ignoreRuleLine >= 0 && ignoreRuleLine < taskCardAllowLine,
        "work/*（第 "
            + (ignoreRuleLine + 1)
            + " 行）必须排在 !work/task-card-*.md（第 "
            + (taskCardAllowLine + 1)
            + " 行）之前，否则任务卡会被重新忽略");
    assertTrue(
        ignoreRuleLine >= 0 && ignoreRuleLine < handoffAllowLine,
        "work/*（第 "
            + (ignoreRuleLine + 1)
            + " 行）必须排在 !work/handoff-*.md（第 "
            + (handoffAllowLine + 1)
            + " 行）之前，否则交接快照会被重新忽略");
  }

  @Test
  void agentsDocEnforcesWorktreeTopology() throws IOException {
    String content = Files.readString(AGENTS_DOC, StandardCharsets.UTF_8);
    assertTrue(
        content.contains("### 工作树拓扑与同步纪律"), "AGENTS.md 必须包含「工作树拓扑与同步纪律」小节标题，固化跨工作树同步纪律、消除口头补课");
    assertTrue(
        content.contains(AUTHORITATIVE_WORKTREE),
        "AGENTS.md 必须点名权威工作树 " + AUTHORITATIVE_WORKTREE + "：主干所在，代码实现、门禁运行、合并与 push 唯一法定工作树");
    assertTrue(
        content.contains("主协作树"),
        "AGENTS.md 必须点名主协作树（承载 docs/main-agent-execution.md 与 work/，严禁碰触业务源码）");
    assertTrue(
        content.contains("docs/main-agent-execution.md"),
        "AGENTS.md 主协作树小节必须写明其承载权威流水账 docs/main-agent-execution.md");
    assertTrue(
        content.contains("严禁未经 owner 显式授权执行") && content.contains("git push"),
        "AGENTS.md 必须保留「严禁未经 owner 显式授权执行 git push」铁律");
  }

  @Test
  void noBakOrClassFilesInWorkDirectory() throws IOException {
    Path root = Paths.get(".").toAbsolutePath().normalize();
    List<String> offenders = new ArrayList<>();
    int scanned = 0;
    try (Stream<Path> tree = Files.walk(root)) {
      for (Path file : tree.filter(Files::isRegularFile).toList()) {
        if (isInsideSkippedDirectory(root, file)) {
          continue;
        }
        scanned++;
        String name = file.getFileName().toString();
        for (String suffix : FORBIDDEN_ARTIFACT_SUFFIXES) {
          if (name.endsWith(suffix)) {
            offenders.add(root.relativize(file).toString().replace('\\', '/'));
            break;
          }
        }
      }
    }
    assertTrue(scanned >= MIN_SCANNED_FILES, "守卫只扫到 " + scanned + " 个文件，明显没在仓库根目录运行，扫描路径失效不许静默假绿");
    assertTrue(
        offenders.isEmpty(),
        "仓库工作区存在备份/编译残留文件（"
            + String.join(" / ", FORBIDDEN_ARTIFACT_SUFFIXES)
            + "），必须清理后再提交：\n  "
            + String.join("\n  ", offenders));
  }

  /** 返回 trim 后与目标完全相等的首个 0 基行号；找不到返回 -1（避免注释/子串误命中）。 */
  private static int lineIndexOf(String content, String exactLine) {
    String[] lines = content.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].trim().equals(exactLine)) {
        return i;
      }
    }
    return -1;
  }

  /** 文件路径是否落在跳过的目录（构建输出/依赖/并行 worktree/版本库元数据）内。 */
  private static boolean isInsideSkippedDirectory(Path root, Path file) {
    for (Path segment : root.relativize(file)) {
      if (SKIPPED_DIRECTORY_NAMES.contains(segment.toString())) {
        return true;
      }
    }
    return false;
  }
}
