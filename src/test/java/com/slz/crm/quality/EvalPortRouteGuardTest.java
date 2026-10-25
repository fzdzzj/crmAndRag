package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 卡 29 检索端口口径锚守卫（eval-port-route-reconciliation，只读锚 + 运行时事实独立复核）。
 *
 * <ul>
 *   <li><b>verdict 三选一</b>：PRODUCTION-PATH / PARTIAL-OFF-PRODUCTION / INCONCLUSIVE 之外即红（禁第四种）；
 *       与六列表分类自洽重算一致（手改 verdict 即红）；
 *   <li><b>腿表与运行时事实同源</b>：装配腿=工厂独立直构产出生产服务、输入腿=套件替身是测试类私有嵌套类、 稀疏腿=测试包子类 override 生产
 *       SparseRecallService、度量腿/归一化面=生产包内 normalizeScores、 语料面=登记器住测试包——改任何一行的生产/替身标注即红；
 *   <li><b>平移结论位</b>：conclusion 只允许 SET-RELATIVE-INVARIANT；bitwise 标志与 maxAbsDiff 从逐候选表重算
 *       （改「不变→变化」或手改任一数字即红）；单点抬高对照名次必须上升；
 *   <li><b>稀疏近似纪律</b>：bigram vs FULLTEXT(ngram) 差异声明落锚，禁「等价」类措辞（R3）；
 *   <li><b>声明位</b>：externalCalls==0、src/main 零 diff 声明、caseCount 与套件实测一致（R0/R6）。
 * </ul>
 */
class EvalPortRouteGuardTest {

  private static final Path ANCHOR = Path.of("docs", "rag-quality", "eval-port-route-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 平移实验结论位唯一合法值（与对账测试同值；改「变化」即红）。 */
  private static final String CONCLUSION_INVARIANT = "SET-RELATIVE-INVARIANT";

  private static JsonNode anchor;

  private static String anchorRaw;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "口径锚缺失（未写锚或被删）: " + ANCHOR.toAbsolutePath());
    anchorRaw = Files.readString(ANCHOR);
    anchor = MAPPER.readTree(anchorRaw);
  }

  @Test
  @DisplayName("verdict 三选一且与六列表分类自洽重算一致（手改 verdict 或自创第四种即红）")
  void verdictAllowlistAndMechanicalDerivation() {
    String verdict = anchor.path("verdict").asText();
    List<String> allowed = List.of("PRODUCTION-PATH", "PARTIAL-OFF-PRODUCTION", "INCONCLUSIVE");
    assertTrue(allowed.contains(verdict), "verdict 越界（只允许三值，禁自创第四种）: " + verdict);

    assertEquals(6, anchor.path("legs").size(), "六列表必须 = 四条腿 + 两个面 = 6 行");
    boolean anyOffProduction = false;
    for (JsonNode leg : anchor.path("legs")) {
      if (!"PRODUCTION".equals(leg.path("classification").asText())) {
        anyOffProduction = true;
        assertTrue(
            leg.path("deviationPoints").size() > 0,
            "非 PRODUCTION 腿必须逐腿给偏差点: " + leg.path("name").asText());
      }
    }
    switch (verdict) {
      case "PRODUCTION-PATH" ->
          assertFalse(anyOffProduction, "PRODUCTION-PATH 要求六行全 PRODUCTION（与腿表矛盾）");
      case "PARTIAL-OFF-PRODUCTION" ->
          assertTrue(anyOffProduction, "PARTIAL-OFF-PRODUCTION 要求至少一行非 PRODUCTION（与腿表矛盾）");
      default ->
          assertFalse(
              anchor.path("verdictReason").asText().isBlank(),
              "INCONCLUSIVE 必须写明缺哪条证据（verdictReason）");
    }
  }

  @Test
  @DisplayName("六列表逐行完整且分类与运行时事实同源（改任何一行的生产/替身标注即红）")
  void legsTableGroundedByRuntimeFacts() {
    // 装配腿：守卫侧独立直构工厂（不复用对账测试装置），产出必须是生产检索服务
    ModelProvider stub = Mockito.mock(ModelProvider.class);
    assertTrue(Mockito.mockingDetails(stub).isMock(), "守卫装配实证的唯一 ModelProvider 必须是桩（零外呼）");
    KnowledgeRetrievalPort port =
        RagBenchmarkPipelineFactory.build(
            RagBenchmarkRun.HYBRID,
            new KnowledgeBaseAuthorizationService(null, null) {
              @Override
              public List<Long> authorizedKnowledgeBaseIds(
                  UserContext user, List<String> requestedScopes) {
                return List.of(99001L);
              }
            },
            new EmbeddingService(stub, new ModelProviderProperties()),
            new InMemoryVectorStore(),
            RagBenchmarkPipelineFactory.dynamicConfig(Map.of()),
            stub,
            List.of());
    assertInstanceOf(
        KnowledgeRetrievalServiceImpl.class,
        port,
        "装配腿运行时事实：工厂必须产出生产 KnowledgeRetrievalServiceImpl");
    assertLegClassification("装配腿", "PRODUCTION");

    // 输入腿：套件的嵌入/模型替身是 RagQualityRegressionTest 的私有嵌套类（住在测试类内 ⇒ 不是 src/main 生产件）
    List<String> nested =
        Arrays.stream(RagQualityRegressionTest.class.getDeclaredClasses())
            .map(Class::getSimpleName)
            .toList();
    assertTrue(
        nested.contains("DeterministicEmbedding"),
        "套件嵌入替身必须是 RagQualityRegressionTest 私有嵌套类（实测嵌套类=" + nested + "）");
    assertTrue(
        nested.contains("DeterministicModelProvider"), "套件模型替身必须是 RagQualityRegressionTest 私有嵌套类");
    assertLegClassification("输入腿", "STUB");

    // 稀疏腿：测试包子类 override 生产 SparseRecallService ⇒ 是近似，不是生产实现
    assertEquals(
        SparseRecallService.class,
        SparseBenchmarkRecallService.class.getSuperclass(),
        "稀疏腿替身必须是生产 SparseRecallService 的子类");
    assertEquals(
        "com.slz.crm.quality",
        SparseBenchmarkRecallService.class.getPackageName(),
        "稀疏腿替身必须住在测试包（住在主代码包即等于宣称生产实现）");
    assertEquals(
        "com.slz.crm.knowledge.retrieval",
        SparseRecallService.class.getPackageName(),
        "生产稀疏路必须住在主代码包");
    assertLegClassification("稀疏腿", "APPROXIMATION");

    // 度量腿 + 归一化面：生产实现本体 + 平移实验证明其为集合内相对量
    assertEquals(
        "com.slz.crm.knowledge.retrieval",
        DefaultWeightedReranker.class.getPackageName(),
        "重排器必须住在主代码包（生产类）");
    assertTrue(
        Arrays.stream(DefaultWeightedReranker.class.getDeclaredMethods())
            .anyMatch(method -> "normalizeScores".equals(method.getName())),
        "DefaultWeightedReranker 必须自有 normalizeScores（归一化面运行时事实）");
    assertEquals(
        CONCLUSION_INVARIANT,
        translationConclusion(),
        "平移实验结论位必须是不变量（度量腿/归一化面 SET-RELATIVE 的实证前提）");
    assertLegClassification("度量腿", "SET-RELATIVE");
    assertLegClassification("归一化面", "SET-RELATIVE");

    // 语料面：fixture 登记器住在测试包（语料是测试侧资产）
    assertEquals(
        "com.slz.crm.quality", RagBenchmarkDataPreparer.class.getPackageName(), "语料登记器必须住在测试包");
    assertLegClassification("语料面", "TEST-FIXTURE");
  }

  @Test
  @DisplayName("六列表逐行完整：生产件/替身列、可 grep 的 文件:行号、受影响结论、点名关键类")
  void legsTableCompleteWithGrepableRefs() {
    Map<String, List<String>> requiredTokens =
        Map.of(
            "装配腿",
            List.of("KnowledgeRetrievalServiceImpl", "DefaultWeightedReranker"),
            "输入腿",
            List.of(
                "DeterministicEmbedding", "InMemoryVectorStore", "RagQualityRegressionTest.java"),
            "稀疏腿",
            List.of("SparseBenchmarkRecallService", "FULLTEXT", "bigram"),
            "度量腿",
            List.of("DefaultWeightedReranker", "normalizeScores"),
            "语料面",
            List.of("RagBenchmarkDataPreparer"),
            "归一化面",
            List.of("normalizeScores"));
    for (JsonNode leg : anchor.path("legs")) {
      String name = leg.path("name").asText();
      assertFalse(leg.path("production").asText().isBlank(), "行缺生产件列: " + name);
      assertFalse(leg.path("stubOrApprox").isMissingNode(), "行缺替身/近似列: " + name);
      JsonNode refs = leg.path("sourceRefs");
      assertTrue(refs.isArray() && !refs.isEmpty(), "行缺 sourceRefs: " + name);
      for (JsonNode ref : refs) {
        assertTrue(
            ref.asText().matches(".*\\.java:\\d+.*"),
            "sourceRefs 必须是可 grep 的 文件:行号: " + ref.asText());
      }
      assertTrue(leg.path("affectedConclusions").size() > 0, "行缺受影响结论列: " + name);
      String rowText =
          leg.path("production").asText() + leg.path("stubOrApprox").asText() + refs.toString();
      for (String token : requiredTokens.getOrDefault(name, List.of())) {
        assertTrue(rowText.contains(token), "行 " + name + " 必须点名 " + token + "，实际行文本=" + rowText);
      }
    }
  }

  @Test
  @DisplayName("平移实验结论位与逐候选表重算一致（改「不变→变化」或手改任一数字即红）+ 单点对照名次必升")
  void translationSlotRecomputedFromCandidateTable() {
    JsonNode experiment = anchor.path("translationExperiment");
    JsonNode candidates = experiment.path("candidates");
    assertTrue(candidates.isArray() && candidates.size() >= 3, "逐候选表必须 >= 3 行");
    double maxDiffPlus100 = 0d;
    double maxDiffTimes2 = 0d;
    boolean bitwisePlus100 = true;
    boolean bitwiseTimes2 = true;
    for (JsonNode row : candidates) {
      double original = row.path("rerankOriginal").asDouble();
      double plus = row.path("rerankPlus100").asDouble();
      double times = row.path("rerankTimes2").asDouble();
      if (Double.compare(original, plus) != 0) {
        bitwisePlus100 = false;
      }
      if (Double.compare(original, times) != 0) {
        bitwiseTimes2 = false;
      }
      maxDiffPlus100 = Math.max(maxDiffPlus100, Math.abs(original - plus));
      maxDiffTimes2 = Math.max(maxDiffTimes2, Math.abs(original - times));
    }
    assertEquals(
        bitwisePlus100,
        experiment.path("bitwiseEqualPlus100").asBoolean(),
        "bitwiseEqualPlus100 与逐候选表重算不符（手改即红）");
    assertEquals(
        bitwiseTimes2,
        experiment.path("bitwiseEqualTimes2").asBoolean(),
        "bitwiseEqualTimes2 与逐候选表重算不符（手改即红）");
    assertEquals(
        maxDiffPlus100,
        experiment.path("maxAbsDiffPlus100").asDouble(),
        "maxAbsDiffPlus100 与逐候选表重算不符（手改即红）");
    assertEquals(
        maxDiffTimes2,
        experiment.path("maxAbsDiffTimes2").asDouble(),
        "maxAbsDiffTimes2 与逐候选表重算不符（手改即红）");

    double bound = experiment.path("fpToleranceBound").asDouble();
    assertTrue(bound > 0d && bound <= 1e-9d, "容差上界必须显式且量级合理（禁无据放宽到 1e-6 级）: " + bound);
    assertEquals(
        CONCLUSION_INVARIANT,
        experiment.path("conclusion").asText(),
        "平移结论位越界：实测差在界内（逐位或 FP 噪声）时结论位必须仍是不变量，" + "「不变→变化」与数据矛盾即红");
    if (!(bitwisePlus100 && bitwiseTimes2)) {
      assertTrue(maxDiffPlus100 <= bound && maxDiffTimes2 <= bound, "非逐位但超容差界 ⇒ 锚必须处于停手改判态（本守卫应红）");
      assertFalse(
          experiment.path("fpToleranceJustification").asText().isBlank(),
          "有微差必须落实测最大绝对差与容差理由（禁无据放宽）");
    }
    assertTrue(
        experiment.path("stopCondition").asText().contains("INCONCLUSIVE"),
        "停手条件必须落锚（超界 ⇒ 改判 INCONCLUSIVE 并上报）");

    JsonNode control = experiment.path("singlePointControl");
    assertTrue(control.path("rankImproved").asBoolean(), "单点抬高对照必须名次上升（无对照则恒等函数也能通过，装置无鉴别力）");
    int rankBefore = control.path("rankBefore").asInt();
    int rankAfter = control.path("rankAfter").asInt();
    assertTrue(
        rankAfter < rankBefore,
        "rankImproved 与 rankBefore/rankAfter 重算不符: " + rankBefore + "→" + rankAfter);
    assertTrue(rankBefore > 0, "被抬高者原始名次必须不是第一名（抬第一名在 min-max 下名次不动，无鉴别力）");
    assertTrue(experiment.path("singleBoost").asDouble() > 0d, "单点抬高量必须为正");
    String boostedChunkId = control.path("boostedChunkId").asText();
    assertFalse(boostedChunkId.isBlank(), "被抬高候选必须落锚");
    boolean found = false;
    for (JsonNode row : candidates) {
      if (boostedChunkId.equals(row.path("chunkId").asText())) {
        found = true;
      }
    }
    assertTrue(found, "被抬高候选必须在逐候选表内: " + boostedChunkId);
  }

  @Test
  @DisplayName("稀疏近似纪律：bigram vs FULLTEXT(ngram) 差异声明落锚，禁「等价」类措辞（R3）")
  void sparseApproximationDeclarationAndBannedPhrases() {
    JsonNode sparse = legByName("稀疏腿");
    String declaration = anchor.path("sparseApproximationDeclaration").asText();
    assertFalse(declaration.isBlank(), "稀疏近似差异声明必须落锚");
    assertTrue(
        declaration.contains("bigram") && declaration.contains("FULLTEXT"),
        "差异声明必须点名 bigram 与 FULLTEXT(ngram)");
    assertTrue(anchorRaw.contains("无授权 JOIN"), "无授权 JOIN 偏差必须落锚");
    assertTrue(anchorRaw.contains("无软删"), "无软删偏差必须落锚");
    assertFalse(anchorRaw.contains("基本等价"), "禁「基本等价」措辞（R1：口径判断必须落到类名/行号）");
    assertFalse(anchorRaw.contains("大致相同"), "禁「大致相同」措辞（R1）");
    assertFalse(anchorRaw.contains("稀疏路等价生产"), "锚内不得出现「稀疏路等价生产」表述（R3）");
    assertTrue(sparse.path("stubOrApprox").asText().contains("bigram"), "稀疏腿替身列必须写明 bigram 近似");
  }

  @Test
  @DisplayName("声明位：externalCalls==0、src/main 零 diff、caseCount/套件版本与实测一致、R0-R6 落锚")
  void declarationsAndConclusionLists() {
    JsonNode supportable = anchor.path("supportable");
    JsonNode nonSupportable = anchor.path("nonSupportable");
    assertTrue(supportable.isArray() && !supportable.isEmpty(), "可支持清单必须非空（R5）");
    assertTrue(nonSupportable.isArray() && !nonSupportable.isEmpty(), "被污染结论清单必须非空（R5/R3）");
    for (JsonNode node : supportable) {
      assertEvidenceRefs(node, "supportable");
    }
    for (JsonNode node : nonSupportable) {
      assertEvidenceRefs(node, "nonSupportable");
    }
    assertEquals(0, anchor.path("externalCalls").asInt(-1), "externalCalls 必须 == 0（R0 零外呼）");
    assertTrue(
        anchor.path("zeroExternalCallBasis").asText().contains("未构造真实 provider"),
        "零外呼结构依据必须落锚（未构造真实 provider）");
    assertEquals("ZERO", anchor.path("srcMainDiff").asText(), "src/main 零 diff 声明位必须为 ZERO");
    assertEquals(
        RagBenchmarkSuite.standard().size(),
        anchor.path("caseCount").asInt(-1),
        "锚 caseCount 必须与套件实测一致（手改即红）");
    assertEquals(
        RagBenchmarkSuite.SUITE_VERSION, anchor.path("suiteVersion").asText(), "锚套件版本必须与实测一致");
    assertEquals(72, RagBenchmarkSuite.standard().size(), "套件用例数必须仍为 72（卡硬边界）");
    assertEquals("OWNER", anchor.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    for (String criterion : List.of("R0", "R1", "R2", "R3", "R4", "R5", "R6")) {
      assertFalse(
          anchor.path("criteria").path(criterion).asText().isBlank(),
          "锚缺判据 " + criterion + "（判据原文必须落锚）");
    }
  }

  // ==================== 复核协作 ====================

  private static void assertLegClassification(String name, String expected) {
    assertEquals(
        expected,
        legByName(name).path("classification").asText(),
        "腿分类与运行时事实不符（手改生产/替身标注即红）: " + name);
  }

  private static JsonNode legByName(String name) {
    for (JsonNode leg : anchor.path("legs")) {
      if (name.equals(leg.path("name").asText())) {
        return leg;
      }
    }
    throw new AssertionError("六列表缺行: " + name);
  }

  private static String translationConclusion() {
    return anchor.path("translationExperiment").path("conclusion").asText();
  }

  private static void assertEvidenceRefs(JsonNode node, String listName) {
    assertFalse(node.path("conclusion").asText().isBlank(), listName + " 条目缺结论文本");
    JsonNode refs = node.path("evidenceRefs");
    assertTrue(refs.isArray() && !refs.isEmpty(), listName + " 条目必须挂证据编号");
    for (JsonNode ref : refs) {
      String text = ref.asText();
      assertTrue(
          text.startsWith("R1-") || text.equals("R2") || text.equals("R3"),
          listName + " 证据编号口径（R1-<腿名面名> / R2 / R3）: " + text);
    }
  }
}
