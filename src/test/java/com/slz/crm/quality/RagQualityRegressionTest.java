package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.CaseScore;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;

/**
 * RAG 质量回归门禁（TASK-17）：纯 JVM、无外发、无随机，日常 {@code mvn test} 与 CI 阶段 1 真跑。
 *
 * <p><b>与 {@code RagRealRetrievalBenchmarkIT} 的分工</b>：IT 量的是"绝对质量"——真模型、真嵌入、真判卷，双门控 （{@code
 * RAG_BENCHMARK_REAL=1} + 有 key）平时必跳，且只断言 failureRate/hitRate，recall@5 与 MRR <b>没有任何下
 * 限</b>。本类量的是"不回退"——同一批 fixture 语料（{@code src/test/resources/rag-quality/fixtures/}）、同一份黄 金集（{@code
 * RagBenchmarkSuite.standard()}）、同一条生产检索管线（{@code KnowledgeRetrievalServiceImpl} + {@code
 * RrfFusion} + {@code DefaultWeightedReranker} + 测试侧稀疏路 {@code SparseBenchmarkRecallService}），
 * 只把两处必须外呼的环节换成确定性替身：嵌入换成字符 n-gram 有符号哈希伪向量，答案生成与判卷整段不参与（只门禁检索排名）。 因此零外发、零随机、无 Docker、不读 .env、不碰
 * Qdrant，秒级可跑。
 *
 * <p><b>断言</b>：实测 {@code recall@5 >= 基线 recallAt5 * 0.95} 且 {@code mrr >= 基线 mrr * 0.95}。基线读 {@code
 * docs/rag-quality/baseline-v1.json} 的 {@code fixtureRegression} 段——那是本门禁独占的段，真跑 IT 写的 {@code
 * suiteVersion}/{@code metrics}/{@code cases} 原样保留，两段互不覆盖。
 *
 * <p><b>不静默放行</b>：基线文件缺失、{@code fixtureRegression} 段缺失、{@code recallAt5}/{@code mrr} 缺失或非 (0,1]
 * 数值、度量口径不一致（截断 k / 基准集版本 / 用例数 / 门禁实现版本）——一律失败并打印再生成指令。基线值必须落在 (0,1]： 记录值为 0 会让"95%
 * 下限"退化成恒真，门禁形同虚设，所以非法值与缺失等价处理。
 *
 * <p><b>★ 唯一合法写入口 ★</b>：这份基线只允许下面这一条命令写，任何其它代码路径都不得写它：
 *
 * <pre>{@code
 * mvn -B -ntp test -Dtest=RagQualityRegressionTest -Drag.baseline.regen=true
 * }</pre>
 *
 * <p>regen 把本轮实测值合并写回 {@code fixtureRegression} 段（其余键不动），并在 {@code generatedAt} 留时间戳。基线 数值变化 = 一次显式
 * regen + 一次 code review；禁止手改 JSON。
 *
 * <p><b>覆盖边界</b>：伪向量只承载词面（n-gram）相似度，不代表真模型的语义质量。本类变红说明"检索编排 / 融合 / 重排 / topK /
 * 语料与黄金集对齐"发生了回退；真模型语义检索是否变好变差仍由 opt-in 的真跑 IT 负责。
 */
class RagQualityRegressionTest {

  /** 一次性基线再生成开关：{@code -Drag.baseline.regen=true}（唯一合法写入口，见类注释）。 */
  static final String REGEN_PROP = "rag.baseline.regen";

  /** 基线文件（仓库根相对路径——surefire/failsafe 工作目录都是仓库根）。 */
  static final Path BASELINE_PATH = Path.of("docs", "rag-quality", "baseline-v1.json");

  /** 本门禁在基线 JSON 里独占的段名。 */
  static final String BASELINE_SECTION = "fixtureRegression";

  /** recall@5 / MRR 的截断排名 = 生产默认 topK = 真跑 IT 口径。 */
  static final int TOP_K = 5;

  /** 下限系数：实测不得低于基线记录值的 95%（5% 抖动额度，回退即红）。 */
  static final double FLOOR_RATIO = 0.95;

  /** 度量口径版本：本类的实现（语料 / 替身 / 管线装配）一旦变更就 +1，旧基线自动失效并要求 regen。 */
  static final String HARNESS_VERSION = "1";

  /** 评测知识库/用户 id：与真跑 IT 同值，metadata 与授权过滤口径一致。 */
  private static final long EVAL_KB_ID = 99001L;

  private static final long EVAL_USER_ID = 99001L;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 一轮完整实测缓存：measure() 是纯函数，同 JVM 内多个用例共用结果（JUnit 同类内方法串行执行）。 */
  private static volatile Measurement cached;

  @Test
  void recallAt5AndMrrStayAboveNinetyFivePercentOfBaseline() {
    Measurement current = measurement();
    Metrics metrics = current.report().metrics();
    printMetrics("实测", metrics, current.chunkCount());
    assertEquals(
        0d,
        metrics.failureRate(),
        "门禁不允许任何用例抛异常（failureRate 必须为 0），失败用例=" + failedCaseIds(current.report()));

    if (regenRequested()) {
      try {
        writeBaseline(BASELINE_PATH, current);
      } catch (IOException exception) {
        throw new AssertionError(
            "regen 写基线失败：" + exception.getMessage() + "；目标文件=" + BASELINE_PATH.toAbsolutePath(),
            exception);
      }
      System.out.println(
          "[rag-quality-regression] REGEN 已把本轮实测写回基线段: " + BASELINE_PATH.toAbsolutePath());
      return;
    }

    Baseline baseline = requireBaseline(BASELINE_PATH, current);
    System.out.println(
        "[rag-quality-regression] 基线 recall@5="
            + baseline.recallAt5()
            + " mrr="
            + baseline.mrr()
            + "（generatedAt="
            + baseline.generatedAt()
            + "，harnessVersion="
            + baseline.harnessVersion()
            + "）");
    assertFloor("recall@5", metrics.meanRecallAtK(), baseline.recallAt5(), baseline);
    assertFloor("MRR", metrics.mrr(), baseline.mrr(), baseline);
  }

  /** 门禁前提：整轮实测必须逐位可复现。空语料/黄金集错位会让指标恒为 0 而"稳定通过"，所以这里同时把语料规模与"整套 recall 不为 0"一起钉住，防止门禁退化成一具空壳。 */
  @Test
  void harnessIsDeterministicAndNotVacuous() {
    Measurement first = measure();
    Measurement second = measure();
    assertTrue(
        first.chunkCount() >= 40,
        "fixture 语料必须真的灌进向量库（空库会让指标恒为 0 而稳定通过），实际切片数=" + first.chunkCount());
    assertEquals(
        RagBenchmarkDataPreparer.FIXTURES.size(),
        first.fixtureKeyCount(),
        "登记的每份 fixture 都必须产出切片（少一份 = 语料缺失或解析退化成空，指标会假性稳定），实际="
            + first.fixtureKeyCount()
            + "/"
            + RagBenchmarkDataPreparer.FIXTURES.size()
            + " 份、切片数="
            + first.chunkCount());
    assertEquals(first.chunkCount(), second.chunkCount(), "两次入库的切片数必须一致（语料解析不确定 → 门禁不可复现）");
    assertEquals(first.rankings(), second.rankings(), "两次实测的逐条召回排名必须逐位一致，否则下限断言没有意义");
    assertEquals(
        first.report().metrics().meanRecallAtK(),
        second.report().metrics().meanRecallAtK(),
        0d,
        "recall@5 必须逐位复现");
    assertEquals(first.report().metrics().mrr(), second.report().metrics().mrr(), 0d, "MRR 必须逐位复现");
    assertTrue(
        first.report().metrics().meanRecallAtK() > 0d,
        "整套 recall@5 为 0：语料或黄金集对齐已断，门禁失去意义（先修语料再 regen）");
    assertEquals(
        RagBenchmarkSuite.standard().size(),
        first.report().metrics().caseCount(),
        "每条基准用例都必须进入评分，否则分母漂移会让基线不可比");
  }

  /** 基线缺失 / 字段缺失 / 口径不一致 / 非法值都必须红，并且带着再生成指令红。 */
  @Test
  void incomparableBaselineFailsWithRegenerationInstruction(@TempDir Path dir) throws Exception {
    Measurement current = measurement();
    String command = regenCommand();

    // 1) 文件不存在
    AssertionError absent =
        assertThrows(
            AssertionError.class,
            () -> requireBaseline(dir.resolve("no-such-baseline.json"), current),
            "基线文件缺失必须失败，不得静默放行");
    assertTrue(
        absent.getMessage().contains(command), "缺失基线的失败信息必须给出再生成指令，实际=" + absent.getMessage());

    // 2) 段缺失 / 字段缺失 / 口径漂移 / 非法值
    Map<String, ObjectNode> broken = new LinkedHashMap<>();
    broken.put("缺 fixtureRegression 段", MAPPER.createObjectNode());
    broken.put("缺 recallAt5 与 mrr", baselineSection(null, null));
    broken.put("截断排名 k 不一致", baselineSection(0.9d, 0.8d).put("k", TOP_K + 5));
    broken.put("基准集版本不一致", baselineSection(0.9d, 0.8d).put("suiteVersion", "0.0"));
    broken.put("用例数不一致", baselineSection(0.9d, 0.8d).put("caseCount", 1));
    broken.put("门禁实现版本不一致", baselineSection(0.9d, 0.8d).put("harnessVersion", "0"));
    broken.put("recallAt5 记 0（会让下限恒真）", baselineSection(0d, 0.8d));
    broken.put("mrr 越界 1", baselineSection(0.9d, 1.5d));
    for (Map.Entry<String, ObjectNode> entry : broken.entrySet()) {
      Path file = dir.resolve("baseline.json");
      Files.writeString(file, itReportWith(entry.getValue()), StandardCharsets.UTF_8);
      AssertionError failure =
          assertThrows(
              AssertionError.class,
              () -> requireBaseline(file, current),
              "基线异常场景必须失败：" + entry.getKey());
      assertTrue(
          failure.getMessage().contains(command),
          entry.getKey() + "：失败信息必须给出再生成指令，实际=" + failure.getMessage());
    }
  }

  /** regen 只允许动 {@code fixtureRegression} 段：真跑 IT 的 metrics/cases 必须逐字保留，且写回后能原样读回。 */
  @Test
  void regenRewritesOnlyItsOwnSectionAndRoundTrips(@TempDir Path dir) throws Exception {
    Measurement current = measurement();
    Path file = dir.resolve("baseline-v1.json");
    Files.writeString(file, itReportWith(null), StandardCharsets.UTF_8);

    writeBaseline(file, current);

    JsonNode root = MAPPER.readTree(file.toFile());
    assertEquals("1.0", root.path("suiteVersion").asText(), "regen 不得改写真跑 IT 写的 suiteVersion 等既有键");
    assertEquals(0.847d, root.path("metrics").path("mrr").asDouble(), 1e-12, "metrics 段必须原样保留");
    assertEquals(1, root.path("cases").size(), "cases 段必须原样保留");
    JsonNode section = root.path(BASELINE_SECTION);
    assertEquals(
        current.report().metrics().meanRecallAtK(),
        section.path("recallAt5").asDouble(),
        1e-12,
        "regen 写入的必须是本轮实测 recall@5");
    assertEquals(
        current.report().metrics().mrr(),
        section.path("mrr").asDouble(),
        1e-12,
        "regen 写入的必须是本轮实测 MRR");

    Baseline reread = requireBaseline(file, current);
    assertEquals(
        current.report().metrics().meanRecallAtK(),
        reread.recallAt5(),
        1e-12,
        "写回后读回的基线值必须与实测一致（JSON 精度不失真）");
    assertEquals(
        current.report().metrics().mrr(), reread.mrr(), 1e-12, "写回后读回的基线值必须与实测一致（JSON 精度不失真）");

    // 目标文件不存在时 regen 必须自建（含建目录），首刷才能跑起来
    Path fresh = dir.resolve("nested").resolve("baseline-v1.json");
    writeBaseline(fresh, current);
    requireBaseline(fresh, current);
  }

  // ==================== 实测装配 ====================

  /** 同一 JVM 内复用一次实测（纯函数）；{@link #harnessIsDeterministicAndNotVacuous} 才是真正跑两遍的那个。 */
  private static Measurement measurement() {
    Measurement local = cached;
    if (local == null) {
      local = measure();
      cached = local;
    }
    return local;
  }

  /**
   * 跑一轮完整实测：fixture 幂等入库 → 黄金占位 id 对齐 → 生产检索管线逐条召回 → {@link RagQualityEvaluator} 汇总。
   *
   * <p>确定性：向量库为内存实现，嵌入为 {@link DeterministicEmbedding} 伪向量，配置矩阵传空 Map（= 全部回落生产默认键：
   * fusion=rrf、topK=5、minScore=0.20、rrf-k=60、rerank=default），profile 只借 {@code HYBRID} 的装配布尔 （稀疏路
   * ON；上下文组装 OFF——它只影响注入文本，不影响召回排名）。
   */
  private static Measurement measure() {
    DeterministicModelProvider provider = new DeterministicModelProvider();
    EmbeddingService embeddingService =
        new EmbeddingService(provider, new ModelProviderProperties());
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(store, DeterministicEmbedding::vectorFor, EVAL_KB_ID);
    List<RagBenchmarkCase> suite =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    KnowledgeRetrievalPort port =
        RagBenchmarkPipelineFactory.build(
            RagBenchmarkRun.HYBRID,
            evalOnlyAuthorization(),
            embeddingService,
            store,
            RagBenchmarkPipelineFactory.dynamicConfig(Map.of()),
            provider,
            preparation.chunks());

    Map<String, CaseRetrieval> retrieved = new LinkedHashMap<>();
    UserContextHolder.set(
        new UserContext(
            EVAL_USER_ID,
            EVAL_USER_ID + 1,
            EVAL_USER_ID + 2,
            DataScopeLevel.NONE,
            "rag-quality-regression"));
    try {
      for (RagBenchmarkCase benchmarkCase : suite) {
        retrieved.put(benchmarkCase.id(), retrieve(port, benchmarkCase));
      }
    } finally {
      UserContextHolder.clear();
    }
    Report report =
        RagQualityEvaluator.evaluate(
            suite, benchmarkCase -> toOutcome(retrieved.get(benchmarkCase.id())), TOP_K);
    List<List<String>> rankings =
        suite.stream().map(c -> retrieved.get(c.id()).chunkIds()).toList();
    return new Measurement(
        report, rankings, preparation.chunkIds().size(), distinctFixtureKeys(preparation));
  }

  /** 实际产出切片的 fixture 份数：chunkId 形如 {@code <语料key>-<chunkIndex>}，取 key 去重计数。 */
  private static int distinctFixtureKeys(RagBenchmarkDataPreparer.Preparation preparation) {
    return (int)
        preparation.chunkIds().stream()
            .map(id -> id.substring(0, id.lastIndexOf('-')))
            .distinct()
            .count();
  }

  /** 单条检索：口径与真跑 IT 一致——非知识库用例不检索；检索异常不抛出，记失败用例由评估器统计。 */
  private static CaseRetrieval retrieve(
      KnowledgeRetrievalPort port, RagBenchmarkCase benchmarkCase) {
    if (!benchmarkCase.useKnowledgeBase()) {
      return new CaseRetrieval(List.of(), true);
    }
    try {
      KnowledgeRetrievalPort.RetrievalResult result =
          port.retrieve(
              new KnowledgeRetrievalPort.RetrievalQuery(
                  benchmarkCase.question(),
                  EVAL_USER_ID,
                  List.of(String.valueOf(EVAL_KB_ID)),
                  TOP_K,
                  null,
                  null));
      List<String> chunkIds = result.sources().stream().map(SourceReference::chunkId).toList();
      return new CaseRetrieval(chunkIds, true);
    } catch (RuntimeException exception) {
      System.out.println(
          "[rag-quality-regression] 用例 " + benchmarkCase.id() + " 检索抛异常，按失败用例计: " + exception);
      return new CaseRetrieval(List.of(), false);
    }
  }

  /**
   * 引用编号与答案要点覆盖属于生成阶段（只有真模型才可得），本门禁不评：coverage 记 1、citations 记空。 门禁只消费 recall@5 / mrr / failureRate
   * 三项，其余指标不参与断言。
   */
  private static CaseOutcome toOutcome(CaseRetrieval retrieval) {
    return new CaseOutcome(retrieval.chunkIds(), List.of(), 1d, 0L, 0L, 0, retrieval.success());
  }

  /** 授权评测桩：固定放行评测知识库（与真跑 IT 同款，生产授权语义不在本门禁考察范围）。 */
  private static KnowledgeBaseAuthorizationService evalOnlyAuthorization() {
    return new KnowledgeBaseAuthorizationService(null, null) {
      @Override
      public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
        return List.of(EVAL_KB_ID);
      }
    };
  }

  // ==================== 基线读 / 唯一写入口 ====================

  /** 读基线并校验可比性；任何缺失或口径漂移都直接 fail 并打印再生成指令。 */
  private static Baseline requireBaseline(Path path, Measurement current) {
    String hint = "\n  基线文件 = " + path.toAbsolutePath() + "\n  再生成（唯一合法写入口）：" + regenCommand();
    if (!Files.isRegularFile(path)) {
      fail("RAG 质量基线缺失，无法做 recall@5 / MRR 下限断言。首刷请显式跑一次 regen。" + hint);
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(path.toFile());
    } catch (IOException exception) {
      fail("RAG 质量基线读不出来（" + exception.getMessage() + "）。" + hint);
      return null;
    }
    if (!root.isObject()) {
      fail("RAG 质量基线根节点不是 JSON 对象。" + hint);
      return null;
    }
    JsonNode section = root.path(BASELINE_SECTION);
    if (!section.isObject()) {
      fail(
          "RAG 质量基线缺 "
              + BASELINE_SECTION
              + " 段（真跑 IT 写的 metrics/cases 不含本门禁口径，必须 regen 首刷一次）。"
              + hint);
      return null;
    }
    requireSame(section, "harnessVersion", HARNESS_VERSION, hint);
    requireSame(section, "k", TOP_K, hint);
    requireSame(section, "suiteVersion", RagBenchmarkSuite.SUITE_VERSION, hint);
    requireSame(
        section,
        "caseCount",
        current.report().metrics().caseCount(),
        hint + "\n  （用例数漂移 = 基准集或语料已变更，先确认变更是有意的，再 regen）");
    double recallAt5 = requireRatio(section, "recallAt5", hint);
    double mrr = requireRatio(section, "mrr", hint);
    return new Baseline(
        recallAt5,
        mrr,
        section.path("generatedAt").asText("未知"),
        section.path("harnessVersion").asText("未知"));
  }

  /** 唯一合法写入口的实现：合并写回 {@code fixtureRegression} 段，其余键原样保留。 */
  private static void writeBaseline(Path path, Measurement current) throws IOException {
    ObjectNode root;
    if (Files.isRegularFile(path)) {
      JsonNode existing = MAPPER.readTree(path.toFile());
      if (!existing.isObject()) {
        throw new IOException("基线根节点不是 JSON 对象，拒绝覆盖：" + path.toAbsolutePath());
      }
      root = (ObjectNode) existing;
    } else {
      root = MAPPER.createObjectNode();
    }
    Metrics metrics = current.report().metrics();
    ObjectNode section = MAPPER.createObjectNode();
    section.put("harness", RagQualityRegressionTest.class.getSimpleName());
    section.put("harnessVersion", HARNESS_VERSION);
    section.put("generatedAt", Instant.now().toString());
    section.put("k", TOP_K);
    section.put("suiteVersion", RagBenchmarkSuite.SUITE_VERSION);
    section.put("caseCount", metrics.caseCount());
    section.put("chunkCount", current.chunkCount());
    section.put("fixtureCount", current.fixtureKeyCount());
    section.put("recallAt5", metrics.meanRecallAtK());
    section.put("mrr", metrics.mrr());
    section.put("floorRatio", FLOOR_RATIO);
    section.put(
        "note",
        "纯 JVM 确定性 fixture 门禁口径，由 -Drag.baseline.regen=true 首刷/再生成；"
            + "与真跑 RagRealRetrievalBenchmarkIT 的 metrics 段不可互相比较");
    root.set(BASELINE_SECTION, section);
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(path, MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
  }

  private static void requireSame(JsonNode section, String field, Object expected, String hint) {
    JsonNode value = section.get(field);
    if (value == null || value.isNull()) {
      fail("基线字段缺失：" + BASELINE_SECTION + "." + field + "（当前口径=" + expected + "）。" + hint);
      return;
    }
    if (!String.valueOf(expected).equals(value.asText())) {
      fail(
          "基线度量口径与当前不一致："
              + BASELINE_SECTION
              + "."
              + field
              + " 基线="
              + value.asText()
              + "，当前="
              + expected
              + "。"
              + hint);
    }
  }

  /** 指标必须落在 (0,1]：0 会让 95% 下限退化为恒真，>1 说明写错了字段。 */
  private static double requireRatio(JsonNode section, String field, String hint) {
    JsonNode value = section.get(field);
    if (value == null || !value.isNumber()) {
      fail("基线字段缺失或非数值：" + BASELINE_SECTION + "." + field + "（需要 (0,1] 的数值）。" + hint);
      return 0d;
    }
    double ratio = value.asDouble();
    if (Double.isNaN(ratio) || !(ratio > 0d) || ratio > 1d) {
      fail("基线 " + field + " 必须落在 (0,1]，实际=" + ratio + "（记录 0 会让 95% 下限恒真，门禁形同虚设）。" + hint);
    }
    return ratio;
  }

  private static void assertFloor(
      String metric, double actual, double recorded, Baseline baseline) {
    double floor = recorded * FLOOR_RATIO;
    assertTrue(
        actual >= floor,
        () ->
            metric
                + " 低于基线下限（允许 "
                + (int) Math.round((1 - FLOOR_RATIO) * 100)
                + "% 抖动）：实测 "
                + actual
                + " < 下限 "
                + floor
                + "（基线记录 "
                + recorded
                + "，generatedAt="
                + baseline.generatedAt()
                + "，harnessVersion="
                + baseline.harnessVersion()
                + "）\n  先判定是检索链回退还是语料/口径变更：回退必须修链路，禁止顺手抬基线；\n  确认为有意变更时显式再生成一次："
                + regenCommand());
  }

  static String regenCommand() {
    return "mvn -B -ntp test -Dtest="
        + RagQualityRegressionTest.class.getSimpleName()
        + " -D"
        + REGEN_PROP
        + "=true";
  }

  private static boolean regenRequested() {
    String value = System.getProperty(REGEN_PROP, "false").strip();
    return "true".equalsIgnoreCase(value) || "1".equals(value);
  }

  private static void printMetrics(String label, Metrics metrics, int chunkCount) {
    System.out.println(
        "[rag-quality-regression] "
            + label
            + " k="
            + TOP_K
            + " cases="
            + metrics.caseCount()
            + " chunks="
            + chunkCount
            + " recall@5="
            + metrics.meanRecallAtK()
            + " mrr="
            + metrics.mrr()
            + " hitRate="
            + metrics.hitRate()
            + " failureRate="
            + metrics.failureRate());
  }

  private static List<String> failedCaseIds(Report report) {
    return report.cases().stream().filter(score -> !score.success()).map(CaseScore::id).toList();
  }

  /** 测试侧替身基线（形如真跑 IT 写出的报告），{@code section} 为 null 时不带本门禁段。 */
  private static String itReportWith(ObjectNode section) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", "1.0");
    root.put("generatedAt", "2026-09-12T15:46:47Z");
    ObjectNode metrics = root.putObject("metrics");
    metrics.put("caseCount", 18);
    metrics.put("mrr", 0.847);
    root.putArray("cases").addObject().put("id", "T-01");
    if (section != null) {
      root.set(BASELINE_SECTION, section);
    }
    return root.toString();
  }

  /** 构造一份"看起来正常"的门禁段，便于按字段做单点破坏。 */
  private static ObjectNode baselineSection(Double recallAt5, Double mrr) {
    ObjectNode section = MAPPER.createObjectNode();
    section.put("harness", RagQualityRegressionTest.class.getSimpleName());
    section.put("harnessVersion", HARNESS_VERSION);
    section.put("generatedAt", "2026-09-20T00:00:00Z");
    section.put("k", TOP_K);
    section.put("suiteVersion", RagBenchmarkSuite.SUITE_VERSION);
    section.put("caseCount", RagBenchmarkSuite.standard().size());
    if (recallAt5 != null) {
      section.put("recallAt5", recallAt5);
    }
    if (mrr != null) {
      section.put("mrr", mrr);
    }
    return section;
  }

  // ==================== 确定性替身 ====================

  /**
   * 字符 1-gram / 2-gram 的有符号特征哈希伪向量：同一文本恒得同一向量，无随机种子、无外部服务。
   *
   * <p>它承载的是词面相似度（不是真模型语义），足以让向量/稀疏双路 + RRF + 重排在纯 JVM 里真实跑起来；管线任一排序环节被改坏， 这里就掉分。维度取
   * 128：再小会饱和（几乎所有桶都非零，向量路退化成噪声），再大则切片与短查询的余弦普遍落到生产 {@code rag.retrieval.minScore} 之下，向量路整条被过滤掉。
   */
  private static final class DeterministicEmbedding {

    private static final int DIMENSIONS = 128;

    private DeterministicEmbedding() {}

    static float[] vectorFor(String text) {
      List<String> tokens = tokens(text);
      if (tokens.isEmpty()) {
        // 纯符号/空文本：给一个固定的非零向量，避免退化出零向量把余弦算成 NaN
        float[] fallback = new float[DIMENSIONS];
        fallback[0] = 1f;
        return fallback;
      }
      float[] signed = new float[DIMENSIONS];
      for (String token : tokens) {
        int hash = token.hashCode();
        int index = Math.floorMod(hash, DIMENSIONS);
        // 符号位（Chaodash）：让哈希碰撞在期望上互相抵消，而不是同向累加
        signed[index] += Math.floorMod(hash >>> 7, 2) == 0 ? 1f : -1f;
      }
      float norm = 0f;
      for (int i = 0; i < DIMENSIONS; i++) {
        // 次线性抑制：长切片的桶计数不再线性拉大模长，避免与短查询的余弦被压到 0
        signed[i] =
            signed[i] == 0f
                ? 0f
                : Math.signum(signed[i]) * (1f + (float) Math.log(Math.abs(signed[i])));
        norm += signed[i] * signed[i];
      }
      if (norm == 0f) {
        signed[0] = 1f;
        return signed;
      }
      float scale = (float) (1d / Math.sqrt(norm));
      for (int i = 0; i < DIMENSIONS; i++) {
        signed[i] *= scale;
      }
      return signed;
    }

    /** 只保留字母/数字（含 CJK）并小写化后的 1-gram + 2-gram；与稀疏路的 bigram 口径同源但多带 unigram。 */
    private static List<String> tokens(String text) {
      StringBuilder compact = new StringBuilder();
      if (text != null) {
        for (int i = 0; i < text.length(); i++) {
          char ch = text.charAt(i);
          if (Character.isLetterOrDigit(ch)) {
            compact.append(Character.toLowerCase(ch));
          }
        }
      }
      List<String> tokens = new ArrayList<>(compact.length() * 2);
      for (int i = 0; i < compact.length(); i++) {
        tokens.add(compact.substring(i, i + 1));
      }
      for (int i = 0; i + 1 < compact.length(); i++) {
        tokens.add(compact.substring(i, i + 2));
      }
      return tokens;
    }
  }

  /**
   * 确定性模型替身：嵌入 → {@link DeterministicEmbedding}；对话 → 恒等回传最后一条用户消息（查询改写码路照跑，
   * 但输出与输入一致，无模型抖动）。流式与视觉被禁用——一旦被调用即抛，充当"不许偷偷外呼"的警哨。
   */
  private static final class DeterministicModelProvider implements ModelProvider {

    private static final String STUB = "deterministic-regression-stub";

    @Override
    public String provider() {
      return STUB;
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      return ModelCallResult.ofText(lastUserText(prompt), STUB, 0L, 0L, 0L);
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      throw new UnsupportedOperationException("本门禁不生成答案，流式模型调用被禁用（防止外呼）");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> instructions = request.getInstructions();
      String text = instructions == null || instructions.isEmpty() ? "" : instructions.get(0);
      return ModelCallResult.ofVector(DeterministicEmbedding.vectorFor(text), STUB, 0L, 0L);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("本门禁不做视觉转写，vision 调用被禁用（防止外呼）");
    }

    private static String lastUserText(Prompt prompt) {
      String text = "";
      for (Message message : prompt.getInstructions()) {
        if (message instanceof UserMessage userMessage) {
          text = userMessage.getText();
        }
      }
      return text == null ? "" : text;
    }
  }

  // ==================== 数据形状 ====================

  /** 一轮实测：聚合报告 + 逐条召回排名（按基准集顺序）+ 入库切片数 + 实际产出切片的 fixture 份数。 */
  private record Measurement(
      Report report, List<List<String>> rankings, int chunkCount, int fixtureKeyCount) {}

  /** 单条用例的检索产出。 */
  private record CaseRetrieval(List<String> chunkIds, boolean success) {}

  /** 从基线 {@code fixtureRegression} 段读到的期望值。 */
  private record Baseline(
      double recallAt5, double mrr, String generatedAt, String harnessVersion) {}
}
