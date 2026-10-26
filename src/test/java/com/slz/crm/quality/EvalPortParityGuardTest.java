package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;

/**
 * 评测端口生产化锚守卫（eval-port-production-parity，只读锚 + 运行时事实独立复核）。
 *
 * <p>断言面（判据 R1–R7 / D6）：
 *
 * <ul>
 *   <li><b>默认零外呼</b>：默认路径的结构性自证（桩即抛 + 无 key）+ 锚 {@code defaultExternalCalls==0} + {@code
 *       externalCalls} 与 {@code binary.ledger} 台账一致；
 *   <li><b>R1 措辞禁令</b>：锚与 md 不得出现等价/一致/无差异/可替换类措辞；稀疏腿差异表必须逐查询可重算交集；
 *   <li><b>R2 三口径齐备</b>：每条用例行必须同时有 rawFusedScore / rank / margin，且归一化恒等式 {@code portScore ==
 *       wv·vecNorm + (zScore - wv)} 与 {@code vecNorm == (raw-rawMin)/(rawMax-rawMin)} 在 ≤1e-12
 *       内成立（缺一即红）；
 *   <li><b>R4 预算自洽</b>：planned = 查询数 + 切片数、估算成本 ≤ 帽、估 ≤ 实测 ≤ 帽、单价来源非空；
 *   <li><b>R5 残差</b>：{@code residualDeviation} 非空且点名「语料腿」，每条引 {@code
 *       eval-port-route-v1#nonSupportable}；
 *   <li><b>D6 CI 端口</b>：{@code ciPortUnchanged} 必须写明 {@code RagQualityRegressionTest} 未改 + 原因 D6；
 *   <li><b>R6</b>：md ≤ 40 行、纯 LF、单尾换行；锚 JSON 纯 LF、单尾换行。
 * </ul>
 */
class EvalPortParityGuardTest {

  private static final Path ANCHOR = Path.of("docs", "rag-quality", "eval-port-parity-v1.json");

  private static final Path ANCHOR_MD = Path.of("docs", "rag-quality", "eval-port-parity-v1.md");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** R1 措辞禁令（与生成侧同源）。 */
  private static final List<String> BANNED_SPARSE_WORDING =
      List.of("等价", "一致", "无差异", "可替换", "基本相同", "大致相同", "没有区别");

  /** R7 外推禁令：不得把本锚数字写成生产语义。 */
  private static final List<String> BANNED_EXTRAPOLATION =
      List.of("生产分数分布", "生产召回质量", "可跨卡比较", "生产语义召回");

  private static JsonNode anchor;

  private static String anchorRaw;

  private static String mdRaw;

  @BeforeAll
  static void loadAnchor() throws Exception {
    assertTrue(Files.exists(ANCHOR), "口径锚缺失（未写锚或被删）: " + ANCHOR.toAbsolutePath());
    assertTrue(Files.exists(ANCHOR_MD), "锚 md 缺失: " + ANCHOR_MD.toAbsolutePath());
    anchorRaw = Files.readString(ANCHOR, StandardCharsets.UTF_8);
    mdRaw = Files.readString(ANCHOR_MD, StandardCharsets.UTF_8);
    anchor = MAPPER.readTree(anchorRaw);
  }

  // ==================== 默认零外呼 ====================

  @Test
  @DisplayName("默认零外呼：桩即抛 + 无 key + defaultExternalCalls==0 + externalCalls 与台账一致")
  void defaultZeroExternalCalls() {
    EvalGuardStubProvider stub = new EvalGuardStubProvider();
    assertThrows(
        UnsupportedOperationException.class,
        () -> stub.streamChat(new Prompt("零外呼警哨")),
        "桩 streamChat 必须即抛（默认路径不许偷偷外呼）");
    assertThrows(
        UnsupportedOperationException.class,
        () -> stub.vision(new Prompt("零外呼警哨")),
        "桩 vision 必须即抛（默认路径不许偷偷外呼）");
    ModelProviderProperties properties = new ModelProviderProperties();
    assertTrue(
        properties.getApiKey() == null || properties.getApiKey().isBlank(),
        "测试侧 ModelProviderProperties 不携带 key（零外呼结构保证）");

    assertEquals(0, anchor.path("defaultExternalCalls").asInt(-1), "默认端口必须零外呼（R3）");
    JsonNode ledger = anchor.path("binary").path("ledger");
    assertTrue(ledger.isObject(), "binary.ledger 台账必须落锚");
    assertEquals(
        anchor.path("externalCalls").asInt(-1),
        ledger.path("actualCalls").asInt(-2),
        "externalCalls 必须与台账 actualCalls 一致（R3）");
    boolean executed = anchor.path("binary").path("executed").asBoolean(false);
    if (!executed) {
      assertEquals(0, anchor.path("externalCalls").asInt(-1), "未执行真跑时 externalCalls 必须为 0");
      assertEquals(0, ledger.path("actualCalls").asInt(-1), "未执行真跑时台账调用数必须为 0");
      assertEquals("NOT_EXECUTED", anchor.path("binary").path("status").asText(), "未执行状态位必须显式");
      assertFalse(anchor.path("binary").path("reason").asText().isBlank(), "未执行必须写明原因（禁静默）");
    }
    assertTrue(
        anchor.path("zeroExternalCallBasis").asText().contains("未读 key")
            || anchor.path("zeroExternalCallBasis").asText().contains("未构造真实 provider"),
        "零外呼结构依据必须落锚");
  }

  // ==================== R1 措辞禁令与差异表 ====================

  @Test
  @DisplayName("R1：措辞禁令（锚与 md）+ 稀疏差异表逐查询交集可重算 + 稀疏腿仍在 CI 端口外")
  void r1WordingBanAndSparseDifferential() {
    for (String banned : BANNED_SPARSE_WORDING) {
      assertFalse(anchorRaw.contains(banned), "锚 JSON 出现禁用措辞「" + banned + "」（R1）");
      assertFalse(mdRaw.contains(banned), "锚 MD 出现禁用措辞「" + banned + "」（R1）");
    }
    JsonNode sparse = anchor.path("sparseDifferential");
    assertTrue(sparse.isObject(), "sparseDifferential 段必须落锚（R1）");
    assertEquals(72, sparse.path("queries").asInt(-1), "差异表必须覆盖 72 例逐查询");
    assertEquals(5, sparse.path("topK").asInt(-1), "差异表 topK 必须与生产默认 5 同口径");
    assertTrue(
        sparse.path("productionSql").asText().contains("fulltextSearch"),
        "取候选必须点名生产同一条 fulltextSearch SQL");
    JsonNode rows = sparse.path("rows");
    assertEquals(72, rows.size(), "逐查询行必须 72 条");
    int totalRealHits = 0;
    int totalBigramHits = 0;
    int totalOverlap = 0;
    for (JsonNode row : rows) {
      List<String> real = texts(row.path("realTopK"));
      List<String> bigram = texts(row.path("bigramTopK"));
      int overlap = 0;
      for (String chunkId : real) {
        if (bigram.contains(chunkId)) {
          overlap++;
        }
      }
      assertEquals(
          row.path("overlap").asInt(-1),
          overlap,
          "overlap 与两集合交集大小不一致（手改即红）: " + row.path("caseId").asText());
      assertTrue(row.path("maxRankShift").asInt(-1) >= 0, "名次位移必须 >= 0");
      for (String side : List.of("realScore", "bigramScore")) {
        JsonNode stats = row.path(side);
        double min = stats.path("min").asDouble();
        double p50 = stats.path("p50").asDouble();
        double max = stats.path("max").asDouble();
        assertTrue(min <= p50 && p50 <= max, side + " 必须满足 min <= p50 <= max");
      }
      totalRealHits += real.size();
      totalBigramHits += bigram.size();
      totalOverlap += overlap;
    }
    assertEquals(sparse.path("totalRealHits").asInt(-1), totalRealHits, "totalRealHits 与逐行重算不一致");
    assertEquals(
        sparse.path("totalBigramHits").asInt(-1), totalBigramHits, "totalBigramHits 与逐行重算不一致");
    assertEquals(sparse.path("totalOverlap").asInt(-1), totalOverlap, "totalOverlap 与逐行重算不一致");
    assertTrue(totalRealHits > 0, "真 ngram 全量零命中 ⇒ 该腿应记 UNDETERMINED 并停手（R8）");
    JsonNode sparseLeg = legByName("稀疏腿");
    assertTrue(
        sparseLeg.path("disposition").asText().contains("ngram")
            && sparseLeg.path("disposition").asText().contains("bigram"),
        "稀疏腿处置必须点名真 ngram 与 bigram 近似");
    assertEquals(
        false, sparseLeg.path("aligned").asBoolean(true), "CI 端口仍用 bigram 近似 ⇒ 稀疏腿不得标已对齐（D6）");
  }

  // ==================== R2 三口径齐备与归一化恒等式 ====================

  @Test
  @DisplayName("R2：每用例 rawFusedScore/rank/margin 齐备（缺一即红）+ 归一化恒等式 ≤1e-12")
  void r2TripletsCompleteAndIdentity() {
    JsonNode metric = anchor.path("metricTriplets");
    assertTrue(metric.isObject(), "metricTriplets 段必须落锚（R2）");
    assertEquals(
        RagBenchmarkSuite.standard().size(),
        metric.path("caseCount").asInt(-1),
        "三口径 caseCount 必须等于套件实测用例数");
    JsonNode cases = metric.path("cases");
    JsonNode nonApplicable = metric.path("nonApplicable");
    assertEquals(
        metric.path("caseCount").asInt(-1),
        cases.size() + nonApplicable.size(),
        "每一条用例都必须有二选一落点（三口径行或显式非适用原因）");
    assertEquals(metric.path("applicableCases").asInt(-1), cases.size(), "applicableCases 与行数不一致");
    for (JsonNode skipped : nonApplicable) {
      assertTrue(
          !skipped.asText().isBlank() && skipped.asText().contains(":"), "非适用用例必须带原因（caseId:原因）");
    }

    double weight = Double.NaN;
    for (JsonNode row : cases) {
      String caseId = row.path("caseId").asText();
      double rawMin = row.path("rawMin").asDouble();
      double rawMax = row.path("rawMax").asDouble();
      assertTrue(rawMax >= rawMin, caseId + "：rawMin/rawMax 序关系错误");
      double wv = row.path("normalizedVectorWeight").asDouble();
      assertTrue(wv > 0d && wv <= 1d, caseId + "：向量权重必须落在 (0,1]");
      if (Double.isNaN(weight)) {
        weight = wv;
      } else {
        assertEquals(weight, wv, 1e-12d, "向量权重必须跨用例同值（生产默认权重被打漂）");
      }
      assertTrue(row.path("fusedCount").asInt(0) > 0, caseId + "：融合候选数必须 > 0");
      JsonNode candidates = row.path("candidates");
      assertTrue(candidates.size() >= 1, caseId + "：每用例至少一条三口径行");
      double recomputedMaxDiff = 0d;
      double previousPort = Double.POSITIVE_INFINITY;
      for (int index = 0; index < candidates.size(); index++) {
        JsonNode candidate = candidates.get(index);
        assertFalse(
            candidate.path("rawFusedScore").isMissingNode(), caseId + "：缺 rawFusedScore（R2 缺一即红）");
        assertFalse(candidate.path("rank").isMissingNode(), caseId + "：缺 rank（R2 缺一即红）");
        assertFalse(candidate.path("margin").isMissingNode(), caseId + "：缺 margin（R2 缺一即红）");
        assertEquals(index, candidate.path("rank").asInt(-1), caseId + "：rank 必须按同一集合名次连续递增");
        double raw = candidate.path("rawFusedScore").asDouble();
        double vecNorm = candidate.path("vecNorm").asDouble();
        double zScore = candidate.path("zScore").asDouble();
        double port = candidate.path("portScore").asDouble();
        double margin = candidate.path("margin").asDouble();
        assertTrue(
            raw >= rawMin - 1e-12d && raw <= rawMax + 1e-12d,
            caseId + "：rawFusedScore 必须落在集合 min/max 内（raw 必须是归一化前值）");
        double expectedVecNorm = productionMinMax(raw, rawMin, rawMax);
        recomputedMaxDiff = Math.max(recomputedMaxDiff, Math.abs(vecNorm - expectedVecNorm));
        double rebuilt = wv * vecNorm + (zScore - wv);
        recomputedMaxDiff = Math.max(recomputedMaxDiff, Math.abs(port - rebuilt));
        assertTrue(
            recomputedMaxDiff <= EvalPortParitySparseTest.IDENTITY_TOLERANCE,
            caseId + "：归一化恒等式越界（raw 不是归一化前值或端口分数口径漂移）maxAbsDiff=" + recomputedMaxDiff);
        assertTrue(port <= 1d + 1e-12d && port >= -1e-12d, caseId + "：端口分数必须落在 [0,1]（min-max 加权）");
        assertTrue(port <= previousPort + 1e-12d, caseId + "：三口径行必须按 portScore 降序");
        previousPort = port;
        if (index + 1 < candidates.size()) {
          double nextPort = candidates.get(index + 1).path("portScore").asDouble();
          assertEquals(port - nextPort, margin, 1e-12d, caseId + "：margin 必须等于与下一名之差");
        } else {
          assertEquals(
              port - row.path("tailPortScore").asDouble(),
              margin,
              1e-12d,
              caseId + "：末位 margin 必须等于与 tailPortScore 之差");
        }
      }
      assertTrue(
          recomputedMaxDiff <= row.path("identityMaxAbsDiff").asDouble() + 1e-15d,
          caseId + "：逐行重算的恒等式误差不得大于锚登记的 identityMaxAbsDiff");
      assertTrue(
          row.path("identityMaxAbsDiff").asDouble() <= EvalPortParitySparseTest.IDENTITY_TOLERANCE,
          caseId + "：锚登记的恒等式误差必须 <= 1e-12");
    }
  }

  // ==================== R4 预算 ====================

  @Test
  @DisplayName("R4：预算字段自洽（planned = 查询 + 切片；估算 ≤ 实测 ≤ 帽；单价来源非空）")
  void r4BudgetSelfConsistent() {
    JsonNode budget = anchor.path("budget");
    assertTrue(budget.isObject(), "budget 段必须落锚（R4）");
    JsonNode plan = budget.path("plan");
    int queryCases = plan.path("queryCases").asInt(-1);
    int queryCalls = plan.path("queryCalls").asInt(-1);
    int queryEmbedCalls = plan.path("queryEmbedCalls").asInt(-1);
    int sliceCalls = plan.path("fixtureSliceCalls").asInt(-1);
    int plannedCalls = plan.path("plannedCalls").asInt(-1);
    assertEquals(72, queryCases, "用例数必须等于套件用例数 72");
    assertEquals(72, queryCalls, "查询数必须等于套件用例数 72");
    assertTrue(queryEmbedCalls >= queryCases, "查询侧 embed 次数必须 >= 用例数（每查询至少一次）");
    assertTrue(sliceCalls > 0, "fixture 全量切片数必须 > 0");
    assertEquals(
        queryEmbedCalls + sliceCalls, plannedCalls, "plannedCalls 必须等于查询侧 embed 次数 + 全量切片数");
    assertEquals(plannedCalls, budget.path("estimatedCalls").asInt(-1), "estimatedCalls 必须与推导一致");

    double cap = budget.path("capCny").asDouble();
    assertEquals(5.0d, cap, 1e-12d, "预算硬帽必须是 Owner 给定的 ¥5");
    double price = budget.path("priceCnyPerMillionTokens").asDouble();
    assertTrue(price > 0d, "单价必须为正（查不到即停手，禁猜）");
    String priceSource = budget.path("priceSource").asText();
    assertTrue(priceSource.contains("http"), "单价来源必须可复核（官方 URL）");
    long estimatedTokens = plan.path("estimatedTokens").asLong(-1);
    assertTrue(estimatedTokens > 0, "token 估算必须 > 0");
    double estimatedCost = plan.path("estimatedCostCny").asDouble();
    assertEquals(
        estimatedTokens * price / 1_000_000d, estimatedCost, 1e-12d, "估算成本必须由 token 估算 × 单价机械推导");
    assertTrue(estimatedCost <= cap, "估算成本超帽必须抛错终止（R4）");
    assertTrue(plan.path("hardCheck").asText().contains("PASS"), "第一次调用前必须留下硬检查结论（R4）");

    long capCalls = budget.path("capCalls").asLong(-1);
    assertTrue(capCalls >= plannedCalls, "估算调用数必须 <= 帽内可用调用数（估 ≤ 帽）");
    JsonNode ledger = anchor.path("binary").path("ledger");
    long actualCalls = ledger.path("actualCalls").asLong(-1);
    assertTrue(
        budget.path("estimatedCalls").asLong(-1) <= (actualCalls == 0 ? plannedCalls : actualCalls),
        "估 <= 实测（未执行时退化为 估 <= 计划）");
    if (anchor.path("binary").path("executed").asBoolean(false)) {
      assertEquals(
          ledger.path("plannedCalls").asLong(-1), actualCalls, "真跑实测调用数必须等于计划（不得为多跑档位追加调用）");
      assertEquals(plannedCalls, actualCalls, "实测调用数必须等于预算推导的计划调用数（估 = 实测）");
      assertTrue(actualCalls <= capCalls, "实测调用数必须 <= 帽内可用调用数（实测 ≤ 帽）");
      double actualCost = ledger.path("costCny").asDouble(-1d);
      assertTrue(actualCost <= cap, "实测成本必须 <= ¥5 硬帽");
      assertTrue(ledger.path("elapsedMs").asLong(-1) >= 0, "耗时必须落锚");
    } else {
      assertEquals(0L, actualCalls, "未执行时实测调用数必须为 0");
    }
  }

  // ==================== R5 残差 + D6 + 腿表 ====================

  @Test
  @DisplayName("R5：residualDeviation 非空且点名「语料腿」+ 引 eval-port-route-v1#nonSupportable")
  void r5ResidualDeviation() {
    JsonNode residual = anchor.path("residualDeviation");
    assertTrue(residual.isArray() && !residual.isEmpty(), "residualDeviation 必须非空（R5）");
    boolean corpusNamed = false;
    for (JsonNode node : residual) {
      String leg = node.path("leg").asText();
      if ("语料腿".equals(leg)) {
        corpusNamed = true;
        assertEquals(
            false,
            node.path("status").asText().isBlank()
                || node.path("whyStillOffProduction").asText().isBlank(),
            "语料腿必须写明仍 off-production 与原因");
        assertTrue(node.path("excludedConclusions").size() > 0, "语料腿必须列出被排除结论");
      }
      assertFalse(
          node.path("whyStillOffProduction").asText().isBlank(), leg + "：必须写明为什么仍 off-production");
      assertTrue(
          node.path("ref").asText().contains("eval-port-route-v1#nonSupportable"),
          leg + "：被排除结论必须引 eval-port-route-v1#nonSupportable");
    }
    assertTrue(corpusNamed, "residualDeviation 必须点名「语料腿」（R5 硬要求）");
  }

  @Test
  @DisplayName("D6/腿表：ciPortUnchanged 点名 RagQualityRegressionTest + D6；六腿四列齐备")
  void d6CiPortAndLegs() {
    String ciPort = anchor.path("ciPortUnchanged").asText();
    assertTrue(ciPort.contains("RagQualityRegressionTest"), "ciPortUnchanged 必须点名回归端口未改");
    assertTrue(ciPort.contains("D6"), "ciPortUnchanged 必须写明原因 D6");
    assertEquals(72, RagBenchmarkSuite.standard().size(), "套件用例数必须仍为 72（CI 端口未改）");
    assertEquals(
        RagBenchmarkSuite.SUITE_VERSION, anchor.path("suiteVersion").asText(), "锚套件版本必须与实测一致");

    JsonNode legs = anchor.path("legs");
    assertEquals(6, legs.size(), "腿表必须 6 行（装配/稀疏/度量/输入/语料/归一化）");
    Set<String> expected = Set.of("装配腿", "稀疏腿", "度量腿", "输入腿", "语料面", "归一化面");
    for (JsonNode leg : legs) {
      String name = leg.path("name").asText();
      assertTrue(expected.contains(name), "腿名越界: " + name);
      assertFalse(leg.path("current").asText().isBlank(), name + "：缺现状列");
      assertFalse(leg.path("disposition").asText().isBlank(), name + "：缺处置列");
      assertFalse(leg.path("evidence").asText().isBlank(), name + "：缺依据列");
      assertFalse(leg.path("aligned").isMissingNode(), name + "：缺是否已对齐列");
    }
    assertEquals(false, legByName("语料面").path("aligned").asBoolean(true), "语料腿必须标未对齐（R5/D5）");
    assertEquals(true, legByName("装配腿").path("aligned").asBoolean(false), "装配腿是生产类直构，必标已对齐");
  }

  // ==================== R6/R7 锚格式与外推禁令 ====================

  @Test
  @DisplayName("R6/R7：md ≤ 40 行、纯 LF、单尾换行；锚与 md 无外推断言；verdictPort 越界即红")
  void r6FormatAndR7Extrapolation() {
    assertTrue(!anchorRaw.contains("\r"), "锚 JSON 必须纯 LF");
    assertTrue(!mdRaw.contains("\r"), "锚 MD 必须纯 LF");
    assertTrue(anchorRaw.endsWith("\n") && !anchorRaw.endsWith("\n\n"), "锚 JSON 必须单尾换行");
    assertTrue(mdRaw.endsWith("\n") && !mdRaw.endsWith("\n\n"), "锚 MD 必须单尾换行");
    assertTrue(mdRaw.lines().count() <= 40, "锚 MD 必须 <= 40 行，实际=" + mdRaw.lines().count());
    for (String banned : BANNED_EXTRAPOLATION) {
      assertFalse(anchorRaw.contains(banned), "锚 JSON 出现外推断言「" + banned + "」（R7）");
      assertFalse(mdRaw.contains(banned), "锚 MD 出现外推断言「" + banned + "」（R7）");
    }
    String verdict = anchor.path("verdictPort").asText();
    assertTrue(
        List.of("ERROR-BAR-QUANTIFIED", "ERROR-BAR-PARTIAL-QUANTIFIED").contains(verdict),
        "verdictPort 越界（只允许两值）: " + verdict);
    assertTrue(
        anchor.path("verdictPortScope").asText().contains("语料"), "verdictPortScope 必须点明未量化的语料腿");
    assertTrue(
        anchor.path("runtime").path("mysqlImage").asText().startsWith("mysql:8.0.36"),
        "运行时必须登记与生产同版本镜像");
    assertEquals(
        "LF", anchor.path("numericHygiene").path("lineEnding").asText(), "numericHygiene 必须登记纯 LF");
    assertEquals(
        EvalPortParitySparseTest.IDENTITY_TOLERANCE,
        anchor.path("numericHygiene").path("identityTolerance").asDouble(),
        1e-18d,
        "numericHygiene 的容差必须与恒等式断言一致");
  }

  // ==================== 复核协作 ====================

  private static JsonNode legByName(String name) {
    for (JsonNode leg : anchor.path("legs")) {
      if (name.equals(leg.path("name").asText())) {
        return leg;
      }
    }
    throw new AssertionError("腿表缺行: " + name);
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode node : array) {
      values.add(node.asText());
    }
    return values;
  }

  /** 与生产 normalizeScores 同口径的 min-max（独立实现，用于从 raw 反算归一化值）。 */
  private static double productionMinMax(double value, double min, double max) {
    if (max <= 0d) {
      return 0d;
    }
    if (max == min) {
      return 1d;
    }
    return (value - min) / (max - min);
  }

  /** 守卫侧零外呼桩（不复用生成侧装置，独立复核结构保证）。 */
  private static final class EvalGuardStubProvider implements ModelProvider {

    @Override
    public String provider() {
      return "eval-port-guard-stub";
    }

    @Override
    public com.slz.crm.platform.contract.ModelCallResult<String> chat(Prompt prompt) {
      throw new UnsupportedOperationException("守卫侧零外呼：chat 被禁用");
    }

    @Override
    public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> streamChat(
        Prompt prompt) {
      throw new UnsupportedOperationException("守卫侧零外呼：流式模型调用被禁用");
    }

    @Override
    public com.slz.crm.platform.contract.ModelCallResult<float[]> embed(EmbeddingRequest request) {
      throw new UnsupportedOperationException("守卫侧零外呼：embed 被禁用");
    }

    @Override
    public com.slz.crm.platform.contract.ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("守卫侧零外呼：视觉调用被禁用");
    }
  }
}
