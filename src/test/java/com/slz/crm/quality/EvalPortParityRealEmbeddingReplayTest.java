package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/**
 * 评测端口生产化 · 真嵌入重放（eval-port-production-parity，opt-in 唯一花钱动作）。
 *
 * <p>判据 R3/R4/R8 与决策 D4 见 {@code openspec/changes/eval-port-production-parity/proposal.md}。
 *
 * <ul>
 *   <li><b>默认零外呼（R3）</b>：只有显式 {@code RAG_BENCHMARK_REAL=1} 且存在 {@code DASHSCOPE_API_KEY} 时才进入
 *       {@code replayOnceWhenOptedIn}（否则 assumeTrue 跳过）；预算推导用例永远只跑本地伪向量，零调用。
 *   <li><b>预算硬帽（R4/D4）</b>：真跑前打印「调用次数 = 查询侧 embed（干跑实测）× fixture 全量切片数，token 估算 × 单价来源」，并在
 *       <b>第一次调用之前</b> 硬检查；超帽 ⇒ 抛错终止，不许「先跑几条看看」。
 *   <li><b>只跑一次</b>：调用数必须与计划数逐次相等（{@link CountingProvider} 在超计划时立刻抛错）。
 *   <li><b>通道</b>：生产 {@code ModelProvider#embed} 外包一层计数 + 硬帽；{@code chat} 走确定性桩（查询改写不产生 LLM
 *       外呼），因此花钱动作只有 embed。
 *   <li><b>入锚</b>：写模式（{@code -Deval.port.parity.write=true}）下把实测台账回填进 {@code
 *       docs/rag-quality/eval-port-parity-v1.json} 的 {@code binary} 段；生成器再生成时原样保留该段。
 * </ul>
 */
class EvalPortParityRealEmbeddingReplayTest {

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "eval-port-parity-v1.json");

  private static final boolean WRITE_MODE = Boolean.getBoolean("eval.port.parity.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final long EVAL_KB_ID = 99001L;

  private static final long EVAL_USER_ID = 99001L;

  private static final int TOP_K = 5;

  private static final double CAP_CNY = 5.0d;

  private static final double PRICE_CNY_PER_MILLION_TOKENS = 0.5d;

  private static final String PRICE_SOURCE =
      "https://help.aliyun.com/zh/model-studio/text-embedding-v3"
          + "（华北2·北京「文本输入」0.5 元/百万 tokens，查价日 2026-10-27）";

  private static final String EMBEDDING_MODEL = "text-embedding-v3";

  /**
   * 保守上界口径（与锚 {@code budget.plan.tokenEstimateBasis} 同一字符串、同源）：查询文本 + 切片文本 + 每切片 block 头 64 字符，按 1
   * token / 字符计。两侧必须都计入——只算切片会让飞行前闸门的估算低于实测（fix1 修正的缺口）。
   */
  static final String TOKEN_ESTIMATE_BASIS = "1 token / 字符（保守上界：查询文本 + 切片文本 + 每切片 block 头 +64 字符）";

  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  // ==================== 预算推导（默认路径，零外呼） ====================

  @Test
  @DisplayName("R4：打印调用次数 × token 估算 × 单价来源，并在第一次调用前硬检查（零外呼）")
  void budgetDerivationPrintsAndHardChecks() {
    ReplayPlan plan = buildPlan();
    System.out.println(
        "[eval-port-parity] 真嵌入预算推导：queryEmbedCalls="
            + plan.queryEmbedCalls()
            + "（72 查询经确定性改写拆路后的实际 embed 次数）"
            + " fixtureSliceCalls="
            + plan.fixtureSliceCalls()
            + " plannedCalls="
            + plan.plannedCalls()
            + " estimatedTokens="
            + plan.estimatedTokens()
            + " estimateBasis="
            + plan.estimateBasis()
            + " price="
            + PRICE_CNY_PER_MILLION_TOKENS
            + " 元/百万tokens（来源："
            + PRICE_SOURCE
            + "） estimatedCostCny="
            + plan.estimatedCostCny()
            + " capCny="
            + CAP_CNY
            + " ⇒ "
            + (plan.estimatedCostCny() <= CAP_CNY ? "PASS" : "FAIL"));
    assertTrue(plan.queryEmbedCalls() >= 72, "查询侧 embed 次数必须 >= 用例数（每用例至少一次）");
    assertTrue(plan.fixtureSliceCalls() > 0, "fixture 全量切片数必须 > 0");
    assertEquals(
        plan.queryEmbedCalls() + plan.fixtureSliceCalls(),
        plan.plannedCalls(),
        "计划调用数必须等于查询侧 embed + 全量切片");
    assertTrue(
        plan.estimatedCostCny() <= CAP_CNY,
        "预算推导超帽（estimated=" + plan.estimatedCostCny() + " > cap=" + CAP_CNY + "）⇒ 按 R4 抛错终止");
    assertTrue(plan.plannedCalls() <= plan.capCalls(), "计划调用数超帽内可用调用数 ⇒ 停手");
  }

  // ==================== R3/R4 · 真跑一次 ====================

  @Test
  @DisplayName("R3/R4：显式 opt-in 才执行一次真嵌入重放，调用数/实测 token/耗时/指标对照入锚")
  void replayOnceWhenOptedIn() throws Exception {
    assumeTrue(
        "1".equals(System.getenv("RAG_BENCHMARK_REAL")),
        "未设置 RAG_BENCHMARK_REAL=1：真嵌入重放不执行（默认零外呼，R3）");
    String apiKey = resolveApiKey();
    assumeTrue(
        apiKey != null && !apiKey.isBlank(), "未配置 DASHSCOPE_API_KEY（env 或仓库根 .env）：不执行真嵌入重放");

    ReplayPlan plan = buildPlan();
    // 第一次真实调用之前的硬检查（R4）：任一项超帽即抛，不许先跑几条看看
    if (plan.estimatedCostCny() > CAP_CNY) {
      throw new IllegalStateException(
          "预算推导超帽（estimated=" + plan.estimatedCostCny() + " > cap=" + CAP_CNY + "），按 R4 抛错终止");
    }
    if (plan.plannedCalls() > plan.capCalls()) {
      throw new IllegalStateException(
          "计划调用数超帽内可用调用数（planned=" + plan.plannedCalls() + " > capCalls=" + plan.capCalls() + "）");
    }

    MetricPair pseudoMetrics = readPseudoMetricsFromAnchor();
    CountingProvider counting = new CountingProvider(buildRealProvider(apiKey), plan);

    long startedAt = System.nanoTime();
    EmbeddingService embeddingService =
        new EmbeddingService(counting, new ModelProviderProperties());
    com.slz.crm.knowledge.vector.InMemoryVectorStore store =
        new com.slz.crm.knowledge.vector.InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(store, embeddingService::embed, EVAL_KB_ID);
    List<RagBenchmarkCase> suite =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    KnowledgeRetrievalPort port =
        RagBenchmarkPipelineFactory.build(
            RagBenchmarkRun.HYBRID,
            evalOnlyAuthorization(),
            embeddingService,
            store,
            RagBenchmarkPipelineFactory.dynamicConfig(Map.of()),
            counting,
            preparation.chunks());

    Map<String, List<String>> ranked = new LinkedHashMap<>();
    try {
      UserContextHolder.set(
          new UserContext(
              EVAL_USER_ID,
              EVAL_USER_ID + 1,
              EVAL_USER_ID + 2,
              DataScopeLevel.NONE,
              "eval-port-parity-replay"));
      for (RagBenchmarkCase benchmarkCase : suite) {
        if (!benchmarkCase.useKnowledgeBase()) {
          ranked.put(benchmarkCase.id(), List.of());
          continue;
        }
        KnowledgeRetrievalPort.RetrievalResult result =
            port.retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(
                    benchmarkCase.question(),
                    EVAL_USER_ID,
                    List.of(String.valueOf(EVAL_KB_ID)),
                    TOP_K,
                    null,
                    null));
        ranked.put(
            benchmarkCase.id(), result.sources().stream().map(SourceReference::chunkId).toList());
      }
    } finally {
      UserContextHolder.clear();
    }
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

    Report report =
        RagQualityEvaluator.evaluate(
            suite,
            benchmarkCase ->
                new CaseOutcome(
                    ranked.getOrDefault(benchmarkCase.id(), List.of()),
                    List.of(),
                    1d,
                    0L,
                    0L,
                    0,
                    true),
            TOP_K);
    Metrics realMetrics = report.metrics();

    // 只跑一次：实际调用数必须与计划逐次相等（多一次都算违反 R4/D4）
    assertEquals(plan.plannedCalls(), counting.calls(), "实测调用数必须等于计划调用数（不得追加档位/补跑）");
    assertTrue(counting.calls() <= plan.capCalls(), "实测调用数必须 <= 帽内可用调用数");
    double actualCostCny = counting.estimatedTokens() * PRICE_CNY_PER_MILLION_TOKENS / 1_000_000d;
    assertTrue(actualCostCny <= CAP_CNY, "实测成本必须 <= ¥5 硬帽（actual=" + actualCostCny + "）");
    assertEquals(
        plan.queryEmbedCalls() + plan.fixtureSliceCalls(),
        counting.calls(),
        "台账拆分（查询侧 + 切片侧）必须与总调用数自洽");
    assertTrue(elapsedMs >= 0L, "耗时必须可记录");

    System.out.println(
        "[eval-port-parity] 真嵌入重放完成（一次）：calls="
            + counting.calls()
            + " tokens≈"
            + counting.estimatedTokens()
            + " elapsedMs="
            + elapsedMs
            + " cost≈"
            + actualCostCny
            + " 元 / cap="
            + CAP_CNY
            + " 元");

    ObjectNode measured =
        measureNode(plan, counting, elapsedMs, actualCostCny, pseudoMetrics, realMetrics);
    if (WRITE_MODE) {
      patchAnchorBinary(measured, counting.calls());
    } else {
      System.out.println("[eval-port-parity] 非写模式：实测台账只打印不落盘（-Deval.port.parity.write=true 才回填锚）");
    }
  }

  // ==================== 计划与台账 ====================

  /** 干跑算预算：用本地伪向量把 72 例检索跑一遍，精确数出查询侧 embed 次数（改写拆路后可能 > 72），再加 fixture 全量切片数。干跑零外呼、确定性。 */
  private static ReplayPlan buildPlan() {
    EmbeddingService pseudoService =
        new EmbeddingService(new PseudoProvider(), new ModelProviderProperties());
    com.slz.crm.knowledge.vector.InMemoryVectorStore store =
        new com.slz.crm.knowledge.vector.InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(store, pseudoService::embed, EVAL_KB_ID);
    List<RagBenchmarkCase> suite =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    CountingProvider counter = new CountingProvider(new PseudoProvider(), null);
    KnowledgeRetrievalPort port =
        RagBenchmarkPipelineFactory.build(
            RagBenchmarkRun.HYBRID,
            evalOnlyAuthorization(),
            new EmbeddingService(counter, new ModelProviderProperties()),
            store,
            RagBenchmarkPipelineFactory.dynamicConfig(Map.of()),
            counter,
            preparation.chunks());
    try {
      UserContextHolder.set(
          new UserContext(
              EVAL_USER_ID,
              EVAL_USER_ID + 1,
              EVAL_USER_ID + 2,
              DataScopeLevel.NONE,
              "eval-port-parity-plan"));
      for (RagBenchmarkCase benchmarkCase : suite) {
        if (!benchmarkCase.useKnowledgeBase()) {
          continue;
        }
        port.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                benchmarkCase.question(),
                EVAL_USER_ID,
                List.of(String.valueOf(EVAL_KB_ID)),
                TOP_K,
                null,
                null));
      }
    } finally {
      UserContextHolder.clear();
    }
    long plannedChars = 0L;
    for (RagBenchmarkDataPreparer.BenchmarkChunk chunk : preparation.chunks()) {
      plannedChars += chunk.text().length() + 64L;
    }
    // 查询文本同样是 embedding 输入：只算切片会低估实测（fix1），此处与锚 buildBudget 逐字同口径
    for (RagBenchmarkCase benchmarkCase : RagBenchmarkSuite.standard()) {
      plannedChars += benchmarkCase.question().length();
    }
    long queryCalls = counter.calls();
    long sliceCalls = preparation.chunks().size();
    long plannedCalls = queryCalls + sliceCalls;
    // 保守上界：1 token / 字符（中文实际约 1 token / 1-1.5 字符）
    long estimatedTokens = plannedChars;
    double estimatedCostCny = estimatedTokens * PRICE_CNY_PER_MILLION_TOKENS / 1_000_000d;
    double averageCostPerCall = estimatedCostCny / plannedCalls;
    long capCalls = (long) Math.floor(CAP_CNY / averageCostPerCall);
    return new ReplayPlan(
        queryCalls,
        sliceCalls,
        plannedCalls,
        estimatedTokens,
        estimatedCostCny,
        capCalls,
        TOKEN_ESTIMATE_BASIS);
  }

  private static MetricPair readPseudoMetricsFromAnchor() throws Exception {
    if (!Files.exists(ANCHOR_JSON)) {
      return new MetricPair(0, 0d, 0d);
    }
    JsonNode node =
        MAPPER
            .readTree(Files.readString(ANCHOR_JSON, StandardCharsets.UTF_8))
            .path("metricTriplets")
            .path("pseudoSuiteMetrics");
    return new MetricPair(
        node.path("caseCount").asInt(0),
        node.path("recallAt5").asDouble(0d),
        node.path("mrr").asDouble(0d));
  }

  private static ObjectNode measureNode(
      ReplayPlan plan,
      CountingProvider counting,
      long elapsedMs,
      double actualCostCny,
      MetricPair pseudoMetrics,
      Metrics realMetrics) {
    ObjectNode binary = MAPPER.createObjectNode();
    binary.put("executed", true);
    binary.put("status", "EXECUTED");
    binary.put(
        "reason",
        "显式 opt-in（RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY）下执行且仅执行一次："
            + "embed 走生产 ModelProvider#embed 外包的计数+硬帽层，chat/stream/vision 走确定性桩（零外呼）");
    ObjectNode ledger = binary.putObject("ledger");
    ledger.put("plannedCalls", plan.plannedCalls());
    ledger.put("queryEmbedCalls", plan.queryEmbedCalls());
    ledger.put("fixtureSliceCalls", plan.fixtureSliceCalls());
    ledger.put("actualCalls", counting.calls());
    ledger.put("actualTokens", counting.estimatedTokens());
    ledger.put(
        "actualTokensBasis",
        "provider 未回流文本 embedding usage（ModelProviderImpl#embed 注释实测）→ 按 1 token / 字符保守计，非伪造真值");
    ledger.put("elapsedMs", elapsedMs);
    ledger.put("costCny", actualCostCny);
    ledger.put("priceCnyPerMillionTokens", PRICE_CNY_PER_MILLION_TOKENS);
    ledger.put("priceSource", PRICE_SOURCE);
    ledger.put("embeddingModel", EMBEDDING_MODEL);
    binary.set("pseudoSuiteMetrics", metricsNode(pseudoMetrics));
    binary.set("realSuiteMetrics", metricsNode(realMetrics));
    binary.put(
        "deltaBasis",
        "伪向量（256 维哈希）vs 真嵌入（text-embedding-v3）在同一 fixture 语料、同一装配下的 recall@5 / MRR 对照；"
            + "语料仍为 fixture，不得外推（R7）");
    return binary;
  }

  private static ObjectNode metricsNode(Metrics metrics) {
    return metricsNode(new MetricPair(metrics.caseCount(), metrics.meanRecallAtK(), metrics.mrr()));
  }

  private static ObjectNode metricsNode(MetricPair metrics) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("caseCount", metrics.caseCount());
    node.put("recallAt5", metrics.recallAt5());
    node.put("mrr", metrics.mrr());
    return node;
  }

  /** 指标三元（锚里只登记 caseCount / recall@5 / mrr）。 */
  private record MetricPair(int caseCount, double recallAt5, double mrr) {}

  /** 写模式回填：只替换锚的 {@code binary} 段与两处调用计数（其余段由生成器负责），并用与生成器相同的 pretty printer 落盘，保证后续默认复跑逐字节一致。 */
  private static void patchAnchorBinary(ObjectNode measured, long actualCalls) throws Exception {
    if (!Files.exists(ANCHOR_JSON)) {
      System.out.println("[eval-port-parity] 锚不存在，跳过回填（先跑生成器写模式）");
      return;
    }
    JsonNode existing = MAPPER.readTree(Files.readString(ANCHOR_JSON, StandardCharsets.UTF_8));
    ObjectNode root = (ObjectNode) existing;
    root.set("binary", measured);
    root.put("externalCalls", actualCalls);
    if (root.path("budget").isObject()) {
      ((ObjectNode) root.path("budget")).put("actualCalls", actualCalls);
    }
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    Files.writeString(
        ANCHOR_JSON,
        MAPPER.writer(printer).writeValueAsString(root) + "\n",
        StandardCharsets.UTF_8);
    System.out.println("[eval-port-parity] 实测台账已回填锚 binary 段（write 模式）: " + ANCHOR_JSON);
  }

  // ==================== 真实 provider 与计数层 ====================

  private static ModelProvider buildRealProvider(String apiKey) {
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        provider(nativeChat), provider(embeddingModel), new ModelProviderProperties(), environment);
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private static String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    Path dotEnv = Path.of(".env");
    if (Files.exists(dotEnv)) {
      try {
        return Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
            .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
            .findFirst()
            .orElse(null);
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  private static KnowledgeBaseAuthorizationService evalOnlyAuthorization() {
    return new KnowledgeBaseAuthorizationService(null, null) {
      @Override
      public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
        return List.of(EVAL_KB_ID);
      }
    };
  }

  /** 预算计划（干跑实测得出，非拍脑袋）。 */
  private record ReplayPlan(
      long queryEmbedCalls,
      long fixtureSliceCalls,
      long plannedCalls,
      long estimatedTokens,
      double estimatedCostCny,
      long capCalls,
      String estimateBasis) {}

  /**
   * 计数 + 硬帽层：{@code embed} 逐次计数，超计划或超预算立即抛错终止（不许先跑几条看看）；{@code chat/stream/vision} 走确定性桩，不产生任何外呼。
   */
  private static final class CountingProvider implements ModelProvider {

    private final ModelProvider delegate;
    private final ReplayPlan plan;
    private final java.util.concurrent.atomic.AtomicLong calls =
        new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong estimatedTokens =
        new java.util.concurrent.atomic.AtomicLong();

    private CountingProvider(ModelProvider delegate, ReplayPlan plan) {
      this.delegate = delegate;
      this.plan = plan;
    }

    long calls() {
      return calls.get();
    }

    long estimatedTokens() {
      return estimatedTokens.get();
    }

    @Override
    public String provider() {
      return delegate.provider();
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      return ModelCallResult.ofText(lastUserText(prompt), "eval-port-parity-chat-stub", 0L, 0L, 0L);
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      throw new UnsupportedOperationException("重放通道不执行流式调用（chat 一律走确定性桩）");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("重放通道不执行视觉调用（vision 一律不跑）");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> instructions = request.getInstructions();
      String text = instructions == null || instructions.isEmpty() ? "" : instructions.get(0);
      synchronized (this) {
        long nextCalls = calls.get() + 1L;
        long nextTokens = estimatedTokens.get() + text.length();
        if (plan != null) {
          if (nextCalls > plan.plannedCalls()) {
            throw new IllegalStateException(
                "预算硬帽：第 " + nextCalls + " 次 embed 超出计划 " + plan.plannedCalls() + " 次，立即终止（R4）");
          }
          double nextCost = nextTokens * PRICE_CNY_PER_MILLION_TOKENS / 1_000_000d;
          if (nextCost > CAP_CNY) {
            throw new IllegalStateException(
                "预算硬帽：累计 token " + nextTokens + " 估算 " + nextCost + " 元超 ¥5，立即终止（R4）");
          }
        }
        calls.set(nextCalls);
        estimatedTokens.set(nextTokens);
      }
      return delegate.embed(request);
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

  /** 本地伪向量 provider（干跑用，零外呼）。 */
  private static final class PseudoProvider implements ModelProvider {

    @Override
    public String provider() {
      return "eval-port-parity-pseudo";
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      String text = "";
      for (Message message : prompt.getInstructions()) {
        if (message instanceof UserMessage userMessage) {
          text = userMessage.getText();
        }
      }
      return ModelCallResult.ofText(text == null ? "" : text, provider(), 0L, 0L, 0L);
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      throw new UnsupportedOperationException("干跑零外呼：流式调用被禁用");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> instructions = request.getInstructions();
      String text = instructions == null || instructions.isEmpty() ? "" : instructions.get(0);
      return ModelCallResult.ofVector(
          EvalPortParitySparseTest.pseudoVector(text), provider(), 0L, 0L);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("干跑零外呼：视觉调用被禁用");
    }
  }

  /** 供生成器保留段使用：本轮是否执行过真跑（写模式回填后由锚自身承载）。 */
  static Set<String> replayEvidenceKeys() {
    return new LinkedHashSet<>(List.of("binary", "externalCalls", "budget"));
  }
}
