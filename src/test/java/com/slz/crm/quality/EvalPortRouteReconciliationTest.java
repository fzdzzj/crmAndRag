package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * 检索端口口径对账（eval-port-route-reconciliation，卡 29，零外呼）。
 *
 * <p>回答储备 3 的前置问题：72 例基准套件的检索端口是不是「生产路」，其分数能不能当「文本路真分数分布」用。 判据 R0–R6 与决策 D1–D3 见 {@code
 * openspec/changes/eval-port-route-reconciliation/proposal.md}。
 *
 * <ul>
 *   <li><b>R0</b>：全程零外呼——唯一 ModelProvider 是本类确定性桩（streamChat/vision 即抛），不读 key、不跑 {@code
 *       RAG_BENCHMARK_REAL}；{@code src/main} 零 diff；
 *   <li><b>R1</b>：四条腿（装配/输入/稀疏/度量）与两个面（语料/归一化）逐条落六列表，每条挂 {@code 文件:行号}， 装配腿附运行时实证（工厂造的是生产 {@link
 *       KnowledgeRetrievalServiceImpl}）；
 *   <li><b>R2</b>：平移不变性实验——同一查询同一候选集跑三遍（原始 / 向量分整体 +100 / 整体 ×2）重跑<b>生产</b> {@link
 *       DefaultWeightedReranker}（public 构造器直构，D2），断言 rerankScore 不变（逐位或落实测 FP 噪声界）；
 *       并附单点抬高对照（只抬高一个候选 ⇒ 名次必升），证明测的是 min-max 归一而非恒等函数；
 *   <li><b>R4/R5</b>：verdict 三选一由腿表机械推导；「可支持 / 不可支持」结论清单逐条挂证据编号；
 *   <li><b>R6</b>：只判不改——既有断言、基线锚、套件用例数（72）零变动；结论锚 {@code
 *       docs/rag-quality/eval-port-route-v1.json}（纯 LF，写模式 {@code
 *       -Deval.port.route.anchor.write=true}， 默认逐字节复算）+ 同名 {@code .md}。
 * </ul>
 *
 * <p>D1 边界：只复用 {@code RagQualityRegressionTest.measure()} 的<b>装配读法</b>（语料 prepare → 套件改写 → 改写查询 →
 * 拆路 → 双路召回 → RRF），不调用其任何私有方法；语料向量化用本类独立实现的确定性词面向量（与套件伪向量同型、独立实现，锚内声明）—— 输入腿是替身这件事本身就是本卡结论，
 * 不反射进套件私有类。
 */
@DisplayName("检索端口口径对账：四腿六面逐条 + 平移不变性实验 + verdict 机械推导（零外呼）")
class EvalPortRouteReconciliationTest {

  private static final Path ANCHOR_JSON = Path.of("docs", "rag-quality", "eval-port-route-v1.json");

  private static final Path ANCHOR_MD = Path.of("docs", "rag-quality", "eval-port-route-v1.md");

  /** 首次生成开关（-Deval.port.route.anchor.write=true）：常规运行只做逐字节比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("eval.port.route.anchor.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 评测 KB id：与套件同值（RagQualityRegressionTest :97 私有常量按值引用）。 */
  private static final long EVAL_KB_ID = 99001L;

  /** 生产默认 minScore（公开常量直引，不复制值）。 */
  private static final double MIN_SCORE = RetrievalDefaults.MIN_SCORE;

  /** 候选扩量口径 = topK(5) × 4（RetrievalConfigResolver :17 私有常量按值引用）。 */
  private static final int CANDIDATE_LIMIT = RetrievalDefaults.TOP_K * 4;

  /** 生产默认 rrf-k = 60（RetrievalConfigResolver :23 私有常量按值引用）。 */
  private static final int RRF_K = 60;

  /** R2 整体平移量。 */
  private static final double SHIFT = 100.0d;

  /** R2 整体放大倍数。 */
  private static final double SCALE = 2.0d;

  /** 单点抬高对照的抬高量（只抬一个候选；+10 远超分数量级，保证名次必升的算术裕度）。 */
  private static final double SINGLE_BOOST = 10.0d;

  /**
   * FP 噪声容差上界：hit.score 加 100 后落在本机 double 的 ULP≈1.42e-14（2^-52×2^6）格内， min-max
   * 分子/分母各一次舍入、加权和再一次舍入， 噪声量级 1e-14~1e-13，1e-12 为一个数量级裕度。实测最大绝对差落锚（坑 4：不许无据放宽）。
   */
  private static final double FP_BOUND = 1e-12d;

  /** 平移实验结论位唯一合法值（守卫同名单向校验；改「变化」即红）。 */
  static final String CONCLUSION_INVARIANT = "SET-RELATIVE-INVARIANT";

  // ==================== R0 · 结构性零外呼自证 ====================

  @Test
  @DisplayName("R0：唯一 ModelProvider 是确定性桩（stream/vision 即抛）、不携带 key、未构造真实 provider")
  void zeroExternalCallByConstruction() {
    EvalStubProvider provider = new EvalStubProvider();
    Prompt prompt = new Prompt("零外呼警哨：任何流式/视觉调用都必须在此即抛");
    assertThrows(
        UnsupportedOperationException.class,
        () -> provider.streamChat(prompt),
        "桩 streamChat 必须即抛（不许偷偷外呼）");
    assertThrows(
        UnsupportedOperationException.class,
        () -> provider.vision(prompt),
        "桩 vision 必须即抛（不许偷偷外呼）");
    ModelProviderProperties properties = new ModelProviderProperties();
    assertTrue(
        properties.getApiKey() == null || properties.getApiKey().isBlank(),
        "测试侧 ModelProviderProperties 不携带 key（零外呼结构保证，不读环境变量不读 .env）");
    System.out.println("[eval-port-route] R0 零外呼结构自证通过：桩即抛 + 无 key + 未构造真实 provider");
  }

  // ==================== R1/R2/R4/R5 · 对账 + 实验 + 锚 ====================

  @Test
  @DisplayName("四腿对账（R1）+ 平移不变性实验（R2）+ 单点对照 + verdict 机械推导 + 锚落盘/复算")
  void reconcileLegsRunTranslationExperimentAndAnchor() throws Exception {
    // ===== 装配腿运行时实证：工厂造的是生产检索服务（R1） =====
    EvalStubProvider provider = new EvalStubProvider();
    EmbeddingService embeddingService =
        new EmbeddingService(provider, new ModelProviderProperties());
    InMemoryVectorStore store = new InMemoryVectorStore();
    ObjectProvider<DynamicConfigService> providerCfg =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(Map.of()));
    KnowledgeRetrievalPort port =
        RagBenchmarkPipelineFactory.build(
            RagBenchmarkRun.HYBRID,
            evalOnlyAuthorization(),
            embeddingService,
            store,
            RagBenchmarkPipelineFactory.dynamicConfig(Map.of()),
            provider,
            List.of());
    assertInstanceOf(
        KnowledgeRetrievalServiceImpl.class,
        port,
        "装配腿运行时实证：RagBenchmarkPipelineFactory.build 必须产出生产 KnowledgeRetrievalServiceImpl");

    // ===== 语料与套件（照 RagQualityRegressionTest.measure() 装配读法直构，D1：不调用其私有方法） =====
    RagBenchmarkDataPreparer.Preparation preparation =
        RagBenchmarkDataPreparer.prepare(
            store, EvalPortRouteReconciliationTest::evalVector, EVAL_KB_ID);
    List<RagBenchmarkCase> suite =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
    assertEquals(72, RagBenchmarkSuite.standard().size(), "套件用例数必须仍为 72（卡硬边界：不改套件规模）");
    assertEquals("6.0", RagBenchmarkSuite.SUITE_VERSION, "套件版本必须仍为 6.0（口径面引用锚定）");
    RagBenchmarkCase probe =
        suite.stream()
            .filter(
                case1 ->
                    case1.useKnowledgeBase() && case1.category() == RagBenchmarkCase.Category.TEXT)
            .findFirst()
            .orElseThrow(() -> new AssertionError("套件必须存在 TEXT 类知识库用例（探针选择前提）"));

    // ===== 同构装配：改写 → 拆路 → 双路召回 → RRF（KnowledgeRetrievalServiceImpl :249-321 读法） =====
    String query =
        new RetrievalQueryRewriteService(provider, providerCfg).rewrite(probe.question());
    SparseBenchmarkRecallService sparse = new SparseBenchmarkRecallService(preparation.chunks());
    RrfFusion rrfFusion = new RrfFusion();
    ArrayNode routesNode = MAPPER.createArrayNode();
    List<List<RetrievalCandidate>> routes = new ArrayList<>();
    int vectorRouteHits = 0;
    for (String routeQuery : ConstraintQuerySplitter.split(query)) {
      List<RetrievalCandidate> vectorCandidates =
          recallVector(store, embeddingService.embed(routeQuery));
      List<RetrievalCandidate> sparseCandidates =
          sparse.recall(routeQuery, List.of(EVAL_KB_ID), null, CANDIDATE_LIMIT);
      assertTrue(
          !vectorCandidates.isEmpty(),
          "探针用例向量路必须有命中（minScore=" + MIN_SCORE + " 过滤后为空则实验退化，须换探针并停手上报）");
      vectorRouteHits += vectorCandidates.size();
      routes.add(
          sparseCandidates.isEmpty()
              ? vectorCandidates
              : rrfFusion.fuse(vectorCandidates, sparseCandidates, RRF_K));
      ObjectNode routeNode = routesNode.addObject();
      routeNode.put("routeQuery", routeQuery);
      routeNode.put("vectorCandidates", vectorCandidates.size());
      routeNode.put("sparseCandidates", sparseCandidates.size());
    }
    List<RetrievalCandidate> fused = rrfFusion.fuseAll(routes, RRF_K);
    assertTrue(fused.size() >= 3, "探针用例融合候选必须 >= 3（平移实验与单点对照才有意义），实际=" + fused.size());

    // ===== 生产重排器直构（D2：public 构造器 DefaultWeightedReranker:24；空配置=生产默认权重 0.6/0.4） =====
    DefaultWeightedReranker reranker = new DefaultWeightedReranker(new Bm25Scorer(), providerCfg);
    List<RetrievalCandidate> original = reranker.rerank(query, fused);
    List<RetrievalCandidate> plus100 =
        rerankShifted(reranker, query, fused, score -> score + SHIFT);
    List<RetrievalCandidate> times2 = rerankShifted(reranker, query, fused, score -> score * SCALE);
    assertEquals(fused.size(), original.size(), "三遍重排候选数必须一致（原始）");
    assertEquals(fused.size(), plus100.size(), "三遍重排候选数必须一致（+100）");
    assertEquals(fused.size(), times2.size(), "三遍重排候选数必须一致（×2）");

    // ===== R2 逐位比较 + 实测最大绝对差（坑 4：实测落锚，不许无据放宽） =====
    double maxDiffPlus100 = 0d;
    double maxDiffTimes2 = 0d;
    boolean bitwisePlus100 = true;
    boolean bitwiseTimes2 = true;
    ArrayNode candidatesNode = MAPPER.createArrayNode();
    for (int index = 0; index < fused.size(); index++) {
      double baseScore = original.get(index).rerankScore();
      double shiftedPlus = plus100.get(index).rerankScore();
      double shiftedTimes = times2.get(index).rerankScore();
      if (Double.compare(baseScore, shiftedPlus) != 0) {
        bitwisePlus100 = false;
      }
      if (Double.compare(baseScore, shiftedTimes) != 0) {
        bitwiseTimes2 = false;
      }
      maxDiffPlus100 = Math.max(maxDiffPlus100, Math.abs(baseScore - shiftedPlus));
      maxDiffTimes2 = Math.max(maxDiffTimes2, Math.abs(baseScore - shiftedTimes));
      ObjectNode row = candidatesNode.addObject();
      row.put("chunkId", fused.get(index).hit().chunkId());
      row.put("hitScore", fused.get(index).hit().score());
      row.put("rerankOriginal", baseScore);
      row.put("rerankPlus100", shiftedPlus);
      row.put("rerankTimes2", shiftedTimes);
    }
    assertTrue(
        maxDiffPlus100 <= FP_BOUND && maxDiffTimes2 <= FP_BOUND,
        "平移后 rerankScore 差异超出 FP 噪声上界 "
            + FP_BOUND
            + "（实测 +100="
            + maxDiffPlus100
            + "，×2="
            + maxDiffTimes2
            + "）⇒ 重排分含绝对信息，按 R2 停手条件本卡必须改判 INCONCLUSIVE 并上报");

    // ===== 单点抬高对照（task 8：无对照则恒等函数也能通过） =====
    List<RetrievalCandidate> sortedOriginal = byScoreDesc(original);
    String boostedChunkId = sortedOriginal.get(1).hit().chunkId();
    int rankBefore = rankOf(sortedOriginal, boostedChunkId);
    List<RetrievalCandidate> boosted = new ArrayList<>(fused.size());
    for (RetrievalCandidate candidate : fused) {
      boosted.add(
          candidate.hit().chunkId().equals(boostedChunkId)
              ? withHitScore(candidate, candidate.hit().score() + SINGLE_BOOST)
              : candidate);
    }
    int rankAfter = rankOf(byScoreDesc(reranker.rerank(query, boosted)), boostedChunkId);
    assertTrue(rankBefore >= 0 && rankAfter >= 0, "被抬高候选必须出现在两次重排结果中");
    assertTrue(
        rankAfter < rankBefore,
        "单点抬高对照失败：抬高 "
            + boostedChunkId
            + " 名次未上升（"
            + rankBefore
            + "→"
            + rankAfter
            + "）⇒ 实验测到的可能是恒等函数而非归一化，装置无鉴别力");

    // ===== R1 六列表 + R4 verdict 机械推导 =====
    ArrayNode legsNode = buildLegs();
    boolean anyOffProduction = false;
    StringBuilder offLegs = new StringBuilder();
    for (int index = 0; index < legsNode.size(); index++) {
      ObjectNode leg = (ObjectNode) legsNode.get(index);
      if (!"PRODUCTION".equals(leg.path("classification").asText())) {
        anyOffProduction = true;
        if (offLegs.length() > 0) {
          offLegs.append("、");
        }
        offLegs
            .append(leg.path("name").asText())
            .append("=")
            .append(leg.path("classification").asText());
      }
      assertTrue(
          leg.path("deviationPoints").size() > 0
              || "PRODUCTION".equals(leg.path("classification").asText()),
          "非 PRODUCTION 腿必须逐腿给偏差点: " + leg.path("name").asText());
    }
    String verdict = anyOffProduction ? "PARTIAL-OFF-PRODUCTION" : "PRODUCTION-PATH";

    // ===== 锚装配（确定性：无时间戳、无随机；Jackson INDENT_OUTPUT 系统行尾坑规避=显式 LF Indenter） =====
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "eval-port-route-reconciliation");
    root.put("baseCommit", "d812ed1");
    root.put("suiteVersion", RagBenchmarkSuite.SUITE_VERSION);
    root.put("caseCount", RagBenchmarkSuite.standard().size());
    root.put("evalKbId", EVAL_KB_ID);
    root.put("externalCalls", 0);
    root.put(
        "zeroExternalCallBasis",
        "全程唯一 ModelProvider 是本类确定性桩 EvalStubProvider（streamChat/vision 即抛、embed 为本地哈希向量），"
            + "未构造真实 provider、未读任何 key（ModelProviderProperties 实测无 apiKey）、未跑 RAG_BENCHMARK_REAL=1；"
            + "检索链各环节用生产类直构（InMemoryVectorStore/RrfFusion/Bm25Scorer/DefaultWeightedReranker），无网络出口");
    root.put("srcMainDiff", "ZERO");
    root.put(
        "srcMainDiffBasis",
        "本卡写集只含 src/test 两个新测试与 docs/rag-quality 两个新锚；src/main 零字节变动"
            + "（验收时以 git diff master -- src/main 复核，守卫只认本声明位）");

    ObjectNode probeNode = root.putObject("probe");
    probeNode.put("caseId", probe.id());
    probeNode.put("query", query);
    probeNode.set("routes", routesNode);
    probeNode.put("vectorRouteHits", vectorRouteHits);
    probeNode.put("fusedCandidates", fused.size());

    root.set("legs", legsNode);

    ObjectNode experiment = root.putObject("translationExperiment");
    experiment.put("shift", SHIFT);
    experiment.put("scale", SCALE);
    experiment.put("singleBoost", SINGLE_BOOST);
    experiment.put("fpToleranceBound", FP_BOUND);
    experiment.put("bitwiseEqualPlus100", bitwisePlus100);
    experiment.put("maxAbsDiffPlus100", maxDiffPlus100);
    experiment.put("bitwiseEqualTimes2", bitwiseTimes2);
    experiment.put("maxAbsDiffTimes2", maxDiffTimes2);
    experiment.put("conclusion", CONCLUSION_INVARIANT);
    experiment.put(
        "conclusionBasis",
        "同一查询同一候选集跑三遍（原始/+100/×2）重跑生产 DefaultWeightedReranker：rerankScore 实测最大绝对差"
            + " +100="
            + maxDiffPlus100
            + "、×2="
            + maxDiffTimes2
            + "（逐位相等="
            + bitwisePlus100
            + "/"
            + bitwiseTimes2
            + "），且单点抬高对照名次 "
            + rankBefore
            + "→"
            + rankAfter
            + " 必升"
            + " ⇒ 实验测到的是 min-max 归一而非恒等函数 ⇒ 重排分是集合内相对量，跨查询/跨语料比较无意义");
    experiment.put(
        "fpToleranceJustification",
        "实测最大绝对差 +100="
            + maxDiffPlus100
            + "、×2="
            + maxDiffTimes2
            + "；容差上界 1e-12 的理由："
            + "hit.score 加 100 后落在本机 double 的 ULP≈1.42e-14（2^-52×2^6）格内，min-max 分子/分母各一次舍入、"
            + "加权和再一次舍入，噪声量级 1e-14~1e-13，1e-12 为一个数量级裕度，非无据放宽（×2 为 2 的幂缩放，理想逐位不变）");
    experiment.put(
        "stopCondition",
        "若三遍 rerankScore 实测最大绝对差 > 1e-12 ⇒ 重排分含绝对信息，本卡必须改判 INCONCLUSIVE 并停手上报"
            + "（测试断言超界即红，红即停手信号）");
    experiment.set("candidates", candidatesNode);
    ObjectNode control = experiment.putObject("singlePointControl");
    control.put("boostedChunkId", boostedChunkId);
    control.put("rankBefore", rankBefore);
    control.put("rankAfter", rankAfter);
    control.put("rankImproved", rankAfter < rankBefore);

    root.put("verdict", verdict);
    root.put(
        "verdictReason",
        anyOffProduction
            ? "非 PRODUCTION 腿（机械推导）："
                + offLegs
                + "；逐腿偏差点见 legs[].deviationPoints，被污染结论见 nonSupportable[]"
            : "四条腿全部为生产实现（机械推导）");
    root.set("supportable", buildSupportable());
    root.set("nonSupportable", buildNonSupportable());
    root.put(
        "sparseApproximationDeclaration",
        "稀疏腿是字符 bigram 重叠近似（SparseBenchmarkRecallService :33-63 override recall、:29 super(null) 不触 DB），"
            + "不是生产 MySQL FULLTEXT(ngram)：近似 ngram 评分、无授权 JOIN、无软删（:21-22 自述「本类只在基准测试用」）。"
            + "本锚任何位置不得把稀疏腿表述为与生产稀疏路可互换或同口径的部件");
    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0", "零外呼 + src/main 零 diff：只跑现有 surefire 装配与纯 JVM 直构，禁读 key、禁真 provider、禁 *REAL* opt-in");
    criteria.put("R1", "逐腿对账：四条腿+两个面逐条落 (腿名, 生产件, 替身或近似, 文件:行号, 受影响结论)，每条口径判断必须落到可 grep 的类名/行号");
    criteria.put("R2", "平移不变性实验：同候选集 +100/×2 重跑生产重排器断言 rerankScore 不变；不成立 ⇒ 改判");
    criteria.put("R3", "稀疏腿近似不得被当成等价：bigram vs FULLTEXT(ngram) 差异声明 + 被污染结论清单落锚");
    criteria.put(
        "R4", "verdict 三选一：PRODUCTION-PATH / PARTIAL-OFF-PRODUCTION（逐腿偏差点）/ INCONCLUSIVE（缺哪条）");
    criteria.put("R5", "可支持/不可支持结论清单逐条落锚，每条挂 R1/R2 证据编号");
    criteria.put("R6", "只判不改：既有断言/基线锚/套件用例数零变动，不生成新基线");
    root.put("decisionOwner", "OWNER");

    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    String json = MAPPER.writer(printer).writeValueAsString(root) + "\n";
    assertTrue(!json.contains("\r"), "锚 JSON 必须纯 LF（坑 6），实测含 CR");
    writeOrCompare(ANCHOR_JSON, json.getBytes(StandardCharsets.UTF_8));

    String md =
        buildAnchorMd(
            verdict,
            offLegs.toString(),
            maxDiffPlus100,
            maxDiffTimes2,
            bitwisePlus100,
            bitwiseTimes2,
            rankBefore,
            rankAfter);
    assertTrue(!md.contains("\r"), "锚 MD 必须纯 LF，实测含 CR");
    assertTrue(md.lines().count() <= 40, "锚 MD 必须 <= 40 行（卡约束），实际=" + md.lines().count());
    writeOrCompare(ANCHOR_MD, md.getBytes(StandardCharsets.UTF_8));

    System.out.println(
        "[eval-port-route] verdict="
            + verdict
            + " 探针="
            + probe.id()
            + " 融合候选="
            + fused.size()
            + " maxDiff(+100)="
            + maxDiffPlus100
            + " maxDiff(×2)="
            + maxDiffTimes2
            + " 逐位相等="
            + bitwisePlus100
            + "/"
            + bitwiseTimes2
            + " 单点对照名次 "
            + rankBefore
            + "→"
            + rankAfter);
  }

  // ==================== 装配读法镜像（照生产读法直构，不复制实现） ====================

  /** 评测授权桩：固定放行评测 KB（照 RagQualityRegressionTest :366-373 读法）。 */
  private static KnowledgeBaseAuthorizationService evalOnlyAuthorization() {
    return new KnowledgeBaseAuthorizationService(null, null) {
      @Override
      public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
        return List.of(EVAL_KB_ID);
      }
    };
  }

  /** 向量路召回（照 KnowledgeRetrievalServiceImpl#recallIn :437-464 读法直构：单 KB、类目不过滤、文本非空过滤）。 */
  private static List<RetrievalCandidate> recallVector(
      InMemoryVectorStore store, float[] queryVector) {
    Map<String, Object> filter = Map.of("knowledgeBaseId", String.valueOf(EVAL_KB_ID));
    return store
        .search(new VectorSearchRequest(queryVector, CANDIDATE_LIMIT, MIN_SCORE, filter))
        .stream()
        .filter(hit -> hit.text() != null && !hit.text().isBlank())
        .map(hit -> new RetrievalCandidate(hit, hit.score()))
        .toList();
  }

  /** 把全体候选的 hit.score 平移/放大后重跑生产重排器（R2；重排器只读 hit().score()，DefaultWeightedReranker:36-37）。 */
  private static List<RetrievalCandidate> rerankShifted(
      DefaultWeightedReranker reranker,
      String query,
      List<RetrievalCandidate> candidates,
      DoubleUnaryOperator transform) {
    List<RetrievalCandidate> shifted = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      shifted.add(withHitScore(candidate, transform.applyAsDouble(candidate.hit().score())));
    }
    return reranker.rerank(query, shifted);
  }

  /** 同 chunkId 换分值 hit（fusionKey = documentId:chunkId 不变，RrfFusion 语义保持）。 */
  private static RetrievalCandidate withHitScore(RetrievalCandidate candidate, double score) {
    VectorSearchHit hit = candidate.hit();
    return new RetrievalCandidate(
        new VectorSearchHit(hit.chunkId(), hit.documentId(), score, hit.text(), hit.metadata()),
        candidate.rerankScore());
  }

  /** 名次 = 按 rerankScore 降序（照 KnowledgeRetrievalServiceImpl :277-280 的排序读法；稳定排序，确定性）。 */
  private static List<RetrievalCandidate> byScoreDesc(List<RetrievalCandidate> candidates) {
    return candidates.stream()
        .sorted(Comparator.comparingDouble(RetrievalCandidate::rerankScore).reversed())
        .toList();
  }

  private static int rankOf(List<RetrievalCandidate> sorted, String chunkId) {
    for (int index = 0; index < sorted.size(); index++) {
      if (sorted.get(index).hit().chunkId().equals(chunkId)) {
        return index;
      }
    }
    return -1;
  }

  // ==================== R1 六列表与结论清单 ====================

  /** R1 六列表：四条腿（装配/输入/稀疏/度量）+ 两个面（语料/归一化）。行号实测于 d812ed1。 */
  private static ArrayNode buildLegs() {
    ArrayNode legs = MAPPER.createArrayNode();
    legs.add(
        leg(
            "装配腿",
            "KnowledgeRetrievalServiceImpl + DefaultWeightedReranker + RrfFusion + Bm25Scorer（生产类直构）",
            "无（本腿无替身）",
            List.of(
                "RagBenchmarkPipelineFactory.java:50-51",
                "RagBenchmarkPipelineFactory.java:53-55",
                "RagBenchmarkPipelineFactory.java:65-75"),
            "PRODUCTION",
            List.of(),
            List.of("同一装配下的回归位移判定（recall@5/mrr 相对基线下限）")));
    legs.add(
        leg(
            "输入腿",
            "生产应接真实 ModelProvider 嵌入 + Qdrant 持久向量库",
            "DeterministicEmbedding 伪向量 + DeterministicModelProvider 恒等回话 + InMemoryVectorStore 内存库",
            List.of(
                "RagQualityRegressionTest.java:278-283",
                "RagQualityRegressionTest.java:593-654",
                "RagQualityRegressionTest.java:660-700"),
            "STUB",
            List.of(
                "向量 = 确定性哈希伪向量：只承载词面（1/2-gram），其余弦分布与真嵌入不可比（RagQualityRegressionTest :587-591 自述）",
                "对话 = 恒等回传最后一条用户消息（查询改写码路照跑但无模型抖动）",
                "存储 = 内存实现，无持久化、无 Qdrant 检索语义"),
            List.of("recall@5/mrr 绝对值不可外推为生产语义召回质量", "伪向量余弦分布不可当真嵌入分数分布（污染清单 P5）")));
    legs.add(
        leg(
            "稀疏腿",
            "SparseRecallService（生产：MySQL FULLTEXT(ngram) + 授权 JOIN + 软删过滤）",
            "SparseBenchmarkRecallService：字符 bigram 重叠近似、无授权 JOIN、无软删",
            List.of(
                "SparseBenchmarkRecallService.java:21-22",
                "SparseBenchmarkRecallService.java:24-31",
                "SparseBenchmarkRecallService.java:33-63"),
            "APPROXIMATION",
            List.of(
                "bigram 重叠分 ≠ 生产 MATCH...AGAINST(ngram) 相关度",
                "无授权 JOIN（:29 super(null) 不触 DB）",
                "无软删过滤"),
            List.of("稀疏路命中与否与绝对分不可外推（污染清单 P4）")));
    legs.add(
        leg(
            "度量腿",
            "DefaultWeightedReranker（生产类：normalizeScores min-max 后按 0.6/0.4 加权）",
            "无替身，但语义为集合内相对量（R2 平移实验实证）",
            List.of(
                "DefaultWeightedReranker.java:24",
                "DefaultWeightedReranker.java:36-42",
                "DefaultWeightedReranker.java:58-74"),
            "SET-RELATIVE",
            List.of("min-max 集合内归一：分数只反映集合内相对位置，不含绝对信息（R2 实验锚 translationExperiment）"),
            List.of("跨查询/跨语料分数比较无意义（污染清单 P2/P3）")));
    legs.add(
        leg(
            "语料面",
            "生产应接生产库真实文档语料",
            "15 份固定 fixture 文档（src/test/resources/rag-quality/fixtures/）",
            List.of("RagBenchmarkDataPreparer.java:154-174"),
            "TEST-FIXTURE",
            List.of("语料为测试侧固定 fixture，非生产库数据"),
            List.of("生产语料分布不可由此推断（污染清单 P6）")));
    legs.add(
        leg(
            "归一化面",
            "normalizeScores min-max（生产实现本体）",
            "—（面不是替身，是生产实现的语义属性）",
            List.of("DefaultWeightedReranker.java:41-42", "DefaultWeightedReranker.java:58-74"),
            "SET-RELATIVE",
            List.of("两次 normalizeScores 调用（向量腿 :41、BM25 腿 :42）均为集合内归一"),
            List.of("同度量腿：分数不可跨集合比较")));
    return legs;
  }

  private static ObjectNode leg(
      String name,
      String production,
      String stub,
      List<String> sourceRefs,
      String classification,
      List<String> deviationPoints,
      List<String> affectedConclusions) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("name", name);
    node.put("production", production);
    node.put("stubOrApprox", stub);
    ArrayNode refs = node.putArray("sourceRefs");
    sourceRefs.forEach(refs::add);
    node.put("classification", classification);
    ArrayNode deviations = node.putArray("deviationPoints");
    deviationPoints.forEach(deviations::add);
    ArrayNode conclusions = node.putArray("affectedConclusions");
    affectedConclusions.forEach(conclusions::add);
    return node;
  }

  private static ArrayNode buildSupportable() {
    ArrayNode array = MAPPER.createArrayNode();
    supportable(array, "同一装配下的回归位移判定（recall@5/mrr 相对基线 0.95 下限）", List.of("R1-装配腿", "R1-度量腿"));
    supportable(array, "同一查询内候选间排序与相对分差比较", List.of("R2"));
    supportable(array, "管线排序环节（融合/重排/topK）在该伪向量词面空间内的回归敏感性", List.of("R1-输入腿", "R1-装配腿"));
    return array;
  }

  private static ArrayNode buildNonSupportable() {
    ArrayNode array = MAPPER.createArrayNode();
    nonSupportable(array, "recall@5/mrr 绝对值当作生产语义召回质量外推", List.of("R1-输入腿", "R1-稀疏腿"));
    nonSupportable(
        array, "从本套件取「文本路真分数分布」（储备 3 原案）：重排分是集合内相对量，绝对值信息在归一化中已丢失", List.of("R2", "R1-度量腿"));
    nonSupportable(array, "跨查询/跨语料分数比较与分位", List.of("R2"));
    nonSupportable(array, "稀疏路命中与否及 MATCH...AGAINST 相关度绝对值", List.of("R1-稀疏腿"));
    nonSupportable(array, "伪向量余弦分布当作真嵌入分数分布（确定性哈希向量只承载词面，与真嵌入不可比）", List.of("R1-输入腿"));
    nonSupportable(array, "生产语料分布", List.of("R1-语料面"));
    return array;
  }

  private static void supportable(ArrayNode array, String conclusion, List<String> refs) {
    ObjectNode node = array.addObject();
    node.put("conclusion", conclusion);
    ArrayNode evidenceRefs = node.putArray("evidenceRefs");
    refs.forEach(evidenceRefs::add);
  }

  private static void nonSupportable(ArrayNode array, String conclusion, List<String> refs) {
    supportable(array, conclusion, refs);
  }

  // ==================== 锚 ====================

  private static String buildAnchorMd(
      String verdict,
      String offLegs,
      double maxDiffPlus100,
      double maxDiffTimes2,
      boolean bitwisePlus100,
      boolean bitwiseTimes2,
      int rankBefore,
      int rankAfter) {
    String md =
        String.join(
                "\n",
                "# eval-port-route v1 · 检索端口口径对账结论（卡 29，零外呼）",
                "",
                "- verdict: " + verdict + "（非 PRODUCTION 腿: " + offLegs + "）",
                "- 装配腿: PRODUCTION —— 生产 KnowledgeRetrievalServiceImpl/DefaultWeightedReranker/RrfFusion 直构"
                    + "（RagBenchmarkPipelineFactory.java:53-75）",
                "- 输入腿: STUB —— DeterministicEmbedding 伪向量 + DeterministicModelProvider + InMemoryVectorStore"
                    + "（RagQualityRegressionTest.java:278-283）",
                "- 稀疏腿: APPROXIMATION —— 字符 bigram 重叠近似 FULLTEXT(ngram)，无授权 JOIN/无软删"
                    + "（SparseBenchmarkRecallService.java:33-63）",
                "- 度量腿: SET-RELATIVE —— 向量/BM25 各自 min-max 集合内归一（DefaultWeightedReranker.java:41-42,58-74）",
                "- 平移实验: 同候选集三遍（原始/+100/×2）rerankScore 实测最大绝对差 "
                    + maxDiffPlus100
                    + " / "
                    + maxDiffTimes2
                    + "（逐位相等="
                    + bitwisePlus100
                    + "/"
                    + bitwiseTimes2
                    + "）",
                "- 单点抬高对照: 只抬高一个候选，名次 " + rankBefore + "→" + rankAfter + " 必升 ⇒ 测的是归一化不是恒等函数",
                "- 结论: "
                    + CONCLUSION_INVARIANT
                    + " —— 本套件分数不得当「文本路真分数分布」用（储备 3 需改口径或另立真跑卡，由 Owner 裁定）",
                "- 被污染结论: recall@5/mrr 绝对值、跨语料分位、生产文本路分数分布、稀疏路命中绝对值、伪向量余弦分布、生产语料分布",
                "- 细节锚: docs/rag-quality/eval-port-route-v1.json（逐字节复算；行号实测于 d812ed1）")
            + "\n";
    assertFalse(md.endsWith("\n\n"), "锚 MD 不得以空行结尾（git diff --check 报 new blank line at EOF）");
    return md;
  }

  private static void writeOrCompare(Path path, byte[] expected) throws Exception {
    if (WRITE_MODE) {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(path, expected);
      System.out.println("[eval-port-route] 锚已写入（write 模式）: " + path);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论锚缺失，先以 -Deval.port.route.anchor.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚与本次全量重算逐字节不一致：锚被改动或链路行为漂移: " + path);
  }

  // ==================== 确定性桩与独立词面向量 ====================

  /**
   * 确定性模型桩（本卡自己的替身，非套件内部类）：embed → 本类 {@link #evalVector}；chat → 恒等回传最后一条用户消息； stream/vision
   * 即抛充当「不许偷偷外呼」的警哨。
   */
  private static final class EvalStubProvider implements ModelProvider {

    private static final String STUB = "eval-port-reconciliation-stub";

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
      throw new UnsupportedOperationException("本卡零外呼：流式模型调用被禁用");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> instructions = request.getInstructions();
      String text = instructions == null || instructions.isEmpty() ? "" : instructions.get(0);
      return ModelCallResult.ofVector(evalVector(text), STUB, 0L, 0L);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("本卡零外呼：视觉调用被禁用");
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

  /**
   * 测试侧确定性词面向量：字母/数字（含 CJK）小写化后 1/2-gram 有符号哈希到 256 维 + 次线性抑制 + L2 归一。
   *
   * <p>与套件伪向量（RagQualityRegressionTest$DeterministicEmbedding，128 维）同型、<b>独立实现</b>：本卡不反射进套件私有类，
   * 候选分值与套件实测不必逐位相同——平移不变性是重排器的函数性质（R2），与候选分值来源无关；该声明即输入腿结论的一部分。
   */
  static float[] evalVector(String text) {
    int dimensions = 256;
    StringBuilder compact = new StringBuilder();
    if (text != null) {
      for (int index = 0; index < text.length(); index++) {
        char ch = text.charAt(index);
        if (Character.isLetterOrDigit(ch)) {
          compact.append(Character.toLowerCase(ch));
        }
      }
    }
    if (compact.isEmpty()) {
      float[] fallback = new float[dimensions];
      fallback[0] = 1f;
      return fallback;
    }
    float[] signed = new float[dimensions];
    for (int index = 0; index < compact.length(); index++) {
      addToken(signed, compact.substring(index, index + 1));
      if (index + 1 < compact.length()) {
        addToken(signed, compact.substring(index, index + 2));
      }
    }
    double norm = 0d;
    for (int index = 0; index < dimensions; index++) {
      signed[index] =
          signed[index] == 0f
              ? 0f
              : Math.signum(signed[index]) * (1f + (float) Math.log(Math.abs(signed[index])));
      norm += (double) signed[index] * signed[index];
    }
    if (norm == 0d) {
      float[] fallback = new float[dimensions];
      fallback[0] = 1f;
      return fallback;
    }
    float scale = (float) (1d / Math.sqrt(norm));
    for (int index = 0; index < dimensions; index++) {
      signed[index] *= scale;
    }
    return signed;
  }

  private static void addToken(float[] signed, String token) {
    int hash = token.hashCode();
    int index = Math.floorMod(hash, signed.length);
    signed[index] += Math.floorMod(hash >>> 7, 2) == 0 ? 1f : -1f;
  }
}
