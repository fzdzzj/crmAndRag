package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真跑基准的基线写盘口径单测（TASK-19 E2）：纯 JVM、零外发、零模型调用，日常 {@code mvn test} 与 CI 阶段 1 真跑。
 *
 * <p><b>钉住什么</b>：{@link RagRealRetrievalBenchmarkIT#writeBaselineReport} 必须是<b>合并写</b>——只覆盖本 IT 拥有的
 * 顶层键，其余顶层段（{@link RagQualityRegressionTest} 独占的 {@code fixtureRegression}、以及任何本测试现编的陌生键）逐字保留。 旧实现
 * {@code Files.writeString(outPath, report)} 整文件重写，会在一次真跑后把 {@code fixtureRegression} 抹掉， 随后的日常
 * {@code mvn test} 就因基线缺段而红（TASK-17 handoff §4 实锤的交互）。这条链只有真跑 IT 才会踩到，而真跑 IT 默认恒跳，
 * 所以锁测必须落在不依赖外发的单测里，直接喂假报告驱动同一条写盘代码路径。
 *
 * <p><b>不锁什么</b>：指标怎么算（{@code RagQualityEvaluator}）、{@code fixtureRegression} 段内容对不对（{@code
 * RagQualityRegressionTest} 自己的锁测）。
 */
class RagBaselineMergeWriteTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 仓库里那份真实基线：只读进来当输入，任何情况下都不写它。 */
  private static final Path REPO_BASELINE = Path.of("docs", "rag-quality", "baseline-v1.json");

  /** 本 IT 写完必须原样回来的段（真跑 IT 从不产出的键，用它同时代表"陌生段"）。 */
  private static final String FOREIGN_SECTION = "fixtureRegression";

  /** 一次真跑会产出的报告（数值随便，只为可断言"换成新值了"）。 */
  private static Report report(String generatedAt, double mrr, int caseCount) {
    Metrics metrics =
        new Metrics(caseCount, 0.9d, 0.8d, mrr, 1.0d, 0.7d, 0.6d, 120L, 3400L, 17032L, 0.0d);
    List<CaseScore> cases = new ArrayList<>();
    for (int i = 0; i < caseCount; i++) {
      cases.add(
          new CaseScore(
              "T-" + i, "TEXT", 1.0d, 0.5d, 1.0d, true, 0.9d, 1.0d, 100L, 3000L, 900, true));
    }
    return new Report("2.0", generatedAt, metrics, cases);
  }

  /** 形如"真跑 IT 写过一次、门禁又 regen 过自己那段"的基线文件。 */
  private static String existingBaseline() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", "1.0");
    root.put("generatedAt", "2026-09-12T15:46:47.491762800Z");
    ObjectNode metrics = root.putObject("metrics");
    metrics.put("caseCount", 18);
    metrics.put("mrr", 0.8472);
    root.putArray("cases").addObject().put("id", "A-01");
    root.put("runProfile", "V1");
    root.put("runChunking", "fixed");
    root.putObject("runConfig").put("sparseOn", false);
    ObjectNode section = root.putObject(FOREIGN_SECTION);
    section.put("harness", "RagQualityRegressionTest");
    section.put("recallAt5", 0.4717);
    section.put("mrr", 0.5);
    section.putArray("note").addObject().put("keep", "me");
    root.putObject("someOtherLaneSection").put("owner", "not-the-benchmark-it");
    return root.toString();
  }

  /** 合并写只换自己的段：门禁段与陌生段逐字保留，自己的段换成本轮值。 */
  @Test
  void writeKeepsForeignTopLevelSectionsAndReplacesOnlyOwnedOnes(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("baseline-v1.json");
    Files.writeString(file, existingBaseline(), StandardCharsets.UTF_8);
    JsonNode before = MAPPER.readTree(file.toFile());

    Report current = report("2026-09-20T00:00:00Z", 0.99d, 54);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(file, current.toJson(), RagBenchmarkRun.V1);

    JsonNode after = MAPPER.readTree(file.toFile());
    assertEquals(
        before.path(FOREIGN_SECTION), after.path(FOREIGN_SECTION), "fixtureRegression 段必须逐字保留");
    assertEquals(
        before.path("someOtherLaneSection"), after.path("someOtherLaneSection"), "任何陌生顶层段都必须原样保留");
    assertEquals("2.0", after.path("suiteVersion").asText(), "自有键要换成本轮值");
    assertEquals("2026-09-20T00:00:00Z", after.path("generatedAt").asText());
    assertEquals(54, after.path("metrics").path("caseCount").asInt());
    assertEquals(0.99d, after.path("metrics").path("mrr").asDouble(), 1e-12);
    assertEquals(54, after.path("cases").size(), "cases 段是本轮的 54 条，不是旧的 1 条");
    assertEquals("V1", after.path("runProfile").asText());
    assertTrue(after.path("runConfig").path("matrix").isObject(), "runConfig 快照要齐全");
  }

  /** 顶层键顺序沿用原文件（合并写不得把别人的段挤到前面，否则每次真跑都是一个巨型 diff）。 */
  @Test
  void writeKeepsTopLevelKeyOrder(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("baseline-v1.json");
    Files.writeString(file, existingBaseline(), StandardCharsets.UTF_8);
    List<String> before = fieldNames(MAPPER.readTree(file.toFile()));

    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        file, report("2026-09-20T00:00:00Z", 0.99d, 54).toJson(), RagBenchmarkRun.HYBRID);

    assertEquals(before, fieldNames(MAPPER.readTree(file.toFile())), "顶层键顺序必须不变");
  }

  /** 目标文件不存在（含父目录不存在）时必须自建，且只含本 IT 的键。 */
  @Test
  void writeCreatesMissingFileAndParentDirectories(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("nested").resolve("deeper").resolve("baseline-v2.json");
    assertFalse(Files.exists(file), "前置：目标文件不该已存在");

    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        file, report("2026-09-20T00:00:00Z", 0.5d, 3).toJson(), RagBenchmarkRun.HYBRID);

    JsonNode root = MAPPER.readTree(file.toFile());
    assertEquals(
        RagRealRetrievalBenchmarkIT.OWNED_TOP_LEVEL_KEYS.stream().sorted().toList(),
        fieldNames(root).stream().sorted().toList(),
        "新建文件只能有本 IT 拥有的顶层键");
    assertTrue(root.path("runConfig").has("matrix"), "runConfig 快照要齐全");
  }

  /** 本轮没产出的自有键必须被清掉（不给上一轮留幽灵值），但别人的键一个都不许动。 */
  @Test
  void staleOwnedKeysAreRemovedWhileForeignKeysSurvive(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("baseline-v1.json");
    Files.writeString(file, existingBaseline(), StandardCharsets.UTF_8);
    JsonNode before = MAPPER.readTree(file.toFile());

    // report.toJson() 序列化失败时返回 "{}"：本轮等于什么都没写，metrics/cases 就该消失而不是留旧值。
    RagRealRetrievalBenchmarkIT.writeBaselineReport(file, "{}", RagBenchmarkRun.V1);

    JsonNode after = MAPPER.readTree(file.toFile());
    for (String owned : RagRealRetrievalBenchmarkIT.OWNED_TOP_LEVEL_KEYS) {
      if (!"runProfile".equals(owned)
          && !"runChunking".equals(owned)
          && !"runConfig".equals(owned)) {
        assertFalse(after.has(owned), "本轮未写出的自有键必须被移除，残留=" + owned);
      }
    }
    assertEquals(before.path(FOREIGN_SECTION), after.path(FOREIGN_SECTION), "清幽灵键不得波及他段");
    assertEquals(
        before.path("someOtherLaneSection"), after.path("someOtherLaneSection"), "陌生段不得被清掉");
    assertTrue(after.has("runProfile"), "withRunConfig 仍会补上跑快照");
  }

  /** 现有文件不是合法 JSON 对象时：拒绝写盘并留原文，绝不静默覆盖。 */
  @Test
  void corruptExistingFileIsRejectedWithoutBeingOverwritten(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("baseline-v1.json");
    String garbage = "{ this is not json, and " + FOREIGN_SECTION + " lives in here }";
    Files.writeString(file, garbage, StandardCharsets.UTF_8);

    Report current = report("2026-09-20T00:00:00Z", 0.99d, 54);
    assertThrows(
        IOException.class,
        () ->
            RagRealRetrievalBenchmarkIT.writeBaselineReport(
                file, current.toJson(), RagBenchmarkRun.V1),
        "读不出来的基线必须让 IT 显式失败，而不是整文件重写");
    assertEquals(garbage, Files.readString(file, StandardCharsets.UTF_8), "拒绝写盘=一个字节都不许动");

    // 根节点是 JSON 数组（合法但不是对象）同样拒绝。
    Path array = dir.resolve("array.json");
    Files.writeString(array, "[1,2,3]", StandardCharsets.UTF_8);
    assertThrows(
        IOException.class,
        () ->
            RagRealRetrievalBenchmarkIT.writeBaselineReport(
                array, current.toJson(), RagBenchmarkRun.V1),
        "根节点不是对象时不得覆盖");
    assertEquals("[1,2,3]", Files.readString(array, StandardCharsets.UTF_8));
  }

  /** 同一份报告连写两次必须幂等：门禁段与自己的段都不漂移。 */
  @Test
  void repeatedRunsAreIdempotent(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("baseline-v1.json");
    Files.writeString(file, existingBaseline(), StandardCharsets.UTF_8);
    Report current = report("2026-09-20T00:00:00Z", 0.99d, 54);

    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        file, current.toJson(), RagBenchmarkRun.CONTEXT);
    String first = Files.readString(file, StandardCharsets.UTF_8);
    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        file, current.toJson(), RagBenchmarkRun.CONTEXT);
    String second = Files.readString(file, StandardCharsets.UTF_8);

    assertEquals(first, second, "同报告同 profile 重跑必须是字节级幂等");
    JsonNode root = MAPPER.readTree(file.toFile());
    assertEquals(
        MAPPER.readTree(existingBaseline()).path(FOREIGN_SECTION), root.path(FOREIGN_SECTION));
  }

  /**
   * 拿仓库里那份真基线走一遍：合并写之后 {@code fixtureRegression} 必须还在、还能被 {@link RagQualityRegressionTest}
   * 的口径读回去。只读仓库文件，写的都是临时副本。
   */
  @Test
  void realCommittedBaselineSurvivesABenchmarkRun(@TempDir Path dir) throws Exception {
    if (!Files.isRegularFile(REPO_BASELINE)) {
      fail("仓库基线文件不见了：" + REPO_BASELINE.toAbsolutePath());
    }
    Path copy = dir.resolve("baseline-v1.json");
    Files.copy(REPO_BASELINE, copy);
    JsonNode before = MAPPER.readTree(copy.toFile());
    assertTrue(
        before.path(FOREIGN_SECTION).isObject(),
        "前置：真实基线里必须带 " + FOREIGN_SECTION + " 段，否则这条锁测没有意义");

    RagRealRetrievalBenchmarkIT.writeBaselineReport(
        copy, report("2026-09-20T00:00:00Z", 0.99d, 54).toJson(), RagBenchmarkRun.V1);

    JsonNode after = MAPPER.readTree(copy.toFile());
    assertEquals(
        before.path(FOREIGN_SECTION), after.path(FOREIGN_SECTION), "真基线的门禁段被真跑 IT 抹掉了（E2 复现）");
    for (String key : fieldNames(before)) {
      assertTrue(
          after.has(key) || RagRealRetrievalBenchmarkIT.OWNED_TOP_LEVEL_KEYS.contains(key),
          "合并写丢了一个非自有键：" + key);
    }
    assertEquals(fieldNames(before).size(), fieldNames(after).size(), "顶层键数不得凭空变化");
  }

  private static List<String> fieldNames(JsonNode root) {
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
