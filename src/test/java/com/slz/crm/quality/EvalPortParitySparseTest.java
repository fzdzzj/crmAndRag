package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Metrics;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import reactor.core.publisher.Flux;

/**
 * 评测端口生产化 · 稀疏腿真 ngram 差异表 + 锚生成（eval-port-production-parity，零 LLM 外呼）。
 *
 * <p>判据 R1/R2/R4/R5/R6 与决策 D1/D2/D6 见 {@code
 * openspec/changes/eval-port-production-parity/proposal.md}。
 *
 * <ul>
 *   <li><b>装置</b>：Testcontainers {@code mysql:8.0.36}（与生产同版本）+ 真 Flyway 迁移建出 V22 的 ngram FULLTEXT
 *       索引；fixture 切片按 {@code RagBenchmarkDataPreparer} 同一批真实分块灌入 {@code document_vector_chunk}，并落
 *       {@code uploaded_file} 归属行（稀疏路授权 JOIN 的执行点）。
 *   <li><b>取候选</b>：一律走生产 {@link SparseRecallService}（同一条 {@code
 *       DocumentVectorChunkMapper#fulltextSearch} SQL、同授权 JOIN、同 {@code is_deleted}/{@code
 *       chunk_role='CHILD'} 过滤），不写裸 MATCH 断言路径。
 *   <li><b>差异表</b>：72 例逐查询对比「bigram 近似（{@link SparseBenchmarkRecallService}）vs 真 ngram」的 top-k
 *       集合重叠数、名次位移、分数 min/p50/max；只报数字，不下结论（措辞禁令由守卫断言）。
 *   <li><b>D6</b>：本类不替换默认 {@code mvn test} 端口，也不改 {@code RagQualityRegressionTest}；无 Docker
 *       时整类跳过（assumeTrue），CI 的 surefire 契约（无 Docker）不变。
 *   <li><b>锚</b>：{@code docs/rag-quality/eval-port-parity-v1.json} + {@code .md}，写模式 {@code
 *       -Deval.port.parity.write=true} 落盘，默认逐字节复算。
 * </ul>
 */
class EvalPortParitySparseTest {

  private static final Path ANCHOR_JSON =
      Path.of("docs", "rag-quality", "eval-port-parity-v1.json");

  private static final Path ANCHOR_MD = Path.of("docs", "rag-quality", "eval-port-parity-v1.md");

  private static final String CARD = "eval-port-production-parity";

  /** 首次生成开关：常规运行只做逐字节比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("eval.port.parity.write");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 评测 KB id：与套件同值（99001）。 */
  private static final long EVAL_KB_ID = 99001L;

  private static final int TOP_K = RetrievalDefaults.TOP_K;

  /** 候选扩量口径 = topK × 4（与生产 RetrievalConfigResolver 同值）。 */
  private static final int CANDIDATE_LIMIT = RetrievalDefaults.TOP_K * 4;

  /** 生产默认 rrf-k。 */
  private static final int RRF_K = 60;

  /** 生产默认 minScore。 */
  private static final double MIN_SCORE = RetrievalDefaults.MIN_SCORE;

  /** 三项恒等式容差上界（min-max 分子/分母与加权和共三次舍入，1e-12 为一个数量级裕度）。 */
  static final double IDENTITY_TOLERANCE = 1e-12d;

  /** 预算硬帽（Owner 给定上界）。 */
  static final double CAP_CNY = 5.0d;

  /** 单价：百炼 text-embedding-v3「文本输入」0.5 元/百万 tokens（华北2·北京）。 */
  static final double PRICE_CNY_PER_MILLION_TOKENS = 0.5d;

  /** 单价来源（可复核的官方页 + 查价日；查不到即停手，禁猜）。 */
  static final String PRICE_SOURCE =
      "https://help.aliyun.com/zh/model-studio/text-embedding-v3"
          + "（华北2·北京「文本输入」0.5 元/百万 tokens，查价日 2026-10-27）";

  /** 生产默认嵌入模型名。 */
  static final String EMBEDDING_MODEL = "text-embedding-v3";

  /** 与生产同版本的容器镜像。 */
  static final String MYSQL_IMAGE = "mysql:8.0.36";

  private static MySQLContainer<?> mysql;

  private static SqlSession sqlSession;

  private static SparseRecallService productionSparse;

  /** fixture 全量切片的基准命名空间视图（chunkId = {@code <语料key>-<chunkIndex>}）。 */
  private static RagBenchmarkDataPreparer.Preparation preparation;

  private static List<QueryDifferential> differentials;

  private static List<CaseTriplet> triplets;

  private static List<String> nonApplicableCases;

  private static Metrics pseudoMetrics;

  private static int slugCount;

  private static int queryEmbedCalls;

  // ==================== 装置 ====================

  @BeforeAll
  static void startContainerAndPrepare() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过真 ngram 差异表与锚重建（本类装置需本地 Docker，D6 不让它进默认 CI 端口）");
    mysql =
        new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("crm_eval_port_parity")
            .withUsername("crm")
            .withPassword("crm_parity_pwd");
    mysql.start();
    migrate();
    preparation =
        RagBenchmarkDataPreparer.prepare(
            new InMemoryVectorStore(), EvalPortParitySparseTest::pseudoVector, EVAL_KB_ID);
    seedCorpus();
    optimizeFullTextIndex();
    productionSparse = productionSparseRecallService();
    differentials = computeDifferentials();
    triplets = new ArrayList<>();
    nonApplicableCases = new ArrayList<>();
    pseudoMetrics = computeTripletsAndMetrics();
  }

  @AfterAll
  static void stopContainer() {
    if (sqlSession != null) {
      sqlSession.close();
    }
    if (mysql != null) {
      mysql.stop();
    }
  }

  // ==================== R1 · 稀疏腿真跑对照 ====================

  @Test
  @DisplayName("R1：72 例逐查询真 ngram vs bigram 差异表（集合重叠 / 名次位移 / 分数 min-p50-max）")
  void sparseLegRealNgramDifferentialTable() {
    assertEquals(
        RagBenchmarkSuite.standard().size(), differentials.size(), "差异表必须覆盖套件全部用例查询（逐查询落锚）");
    int totalRealHits = differentials.stream().mapToInt(row -> row.real().size()).sum();
    int totalApproxHits = differentials.stream().mapToInt(row -> row.bigram().size()).sum();
    int totalOverlap = differentials.stream().mapToInt(QueryDifferential::overlap).sum();
    assertTrue(
        totalRealHits > 0, "真 ngram 全量零命中 ⇒ 稀疏腿在 ¥5 内做不成真，按 R8 记 UNDETERMINED 并停手上报（不许近似顶替）");
    for (QueryDifferential row : differentials) {
      assertEquals(
          row.overlap(),
          intersection(row.real(), row.bigram()).size(),
          "overlap 必须等于两集合交集大小（手改即红）: " + row.caseId());
      for (String chunkId : row.real()) {
        assertTrue(
            preparation.chunkIds().contains(chunkId),
            "真库 chunkId 必须能映射回 bench 命名空间（<语料key>-<chunkIndex>）: " + chunkId);
      }
      for (String chunkId : row.bigram()) {
        assertTrue(
            preparation.chunkIds().contains(chunkId), "bigram chunkId 必须属 bench 命名空间: " + chunkId);
      }
    }
    System.out.println(
        "[eval-port-parity] 稀疏腿差异表：queries="
            + differentials.size()
            + " realHits="
            + totalRealHits
            + " bigramHits="
            + totalApproxHits
            + " overlap="
            + totalOverlap);
  }

  @Test
  @DisplayName("D6：默认 `mvn test` 端口未改——本类不触碰 RagQualityRegressionTest 的任何字段/方法")
  void ciPortUntouchedByThisClass() {
    assertTrue(
        RagQualityRegressionTest.class.getPackageName().equals("com.slz.crm.quality"),
        "回归端口必须仍在原包（本类未替换/未继承它）");
    assertEquals(
        "com.slz.crm.quality",
        EvalPortParitySparseTest.class.getPackageName(),
        "本类与回归端口并列而非替代（D6）");
    assertFalse(
        SparseRecallService.class.isAssignableFrom(EvalPortParitySparseTest.class),
        "本类不是稀疏服务实现（真装置只做实测，不替换生产件）");
  }

  // ==================== R2/R4/R5/R6 · 锚 ====================

  @Test
  @DisplayName("R2/R4/R5/R6：锚由写模式重建；默认逐字节复算；raw/rank/margin 三项并列")
  void anchorRebuildOrCompare() throws Exception {
    ObjectNode root = buildAnchor();
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    String json = MAPPER.writer(printer).writeValueAsString(root) + "\n";
    assertTrue(!json.contains("\r"), "锚 JSON 必须纯 LF（实测含 CR）");
    assertNoBannedWording(json, "锚 JSON");
    assertNoExtrapolation(json, "锚 JSON");
    writeOrCompare(ANCHOR_JSON, json.getBytes(StandardCharsets.UTF_8));

    String md = buildAnchorMd();
    assertTrue(!md.contains("\r"), "锚 MD 必须纯 LF（实测含 CR）");
    assertTrue(md.lines().count() <= 40, "锚 MD 必须 <= 40 行，实际=" + md.lines().count());
    assertFalse(md.endsWith("\n\n"), "锚 MD 不得以空行结尾（git diff --check 报 blank line at EOF）");
    assertNoBannedWording(md, "锚 MD");
    assertNoExtrapolation(md, "锚 MD");
    writeOrCompare(ANCHOR_MD, md.getBytes(StandardCharsets.UTF_8));

    System.out.println(
        "[eval-port-parity] 锚"
            + (WRITE_MODE ? "已写入（write 模式）" : "逐字节复算一致")
            + "：slugCount="
            + slugCount
            + " triplets="
            + triplets.size()
            + " nonApplicable="
            + nonApplicableCases.size());
  }

  // ==================== 数据准备与实测 ====================

  private static void migrate() {
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
  }

  /**
   * fixture 全量切片真实入库（与生产同构，D1：不为此改 src/main）。
   *
   * <p>列以 V22 DDL 与 {@code DocumentVectorChunkMapper#fulltextSearch} 的 SQL 为准：切片行落 {@code
   * document_id/chunk_index/chunk_text/...}，归属行落 {@code uploaded_file}（授权 JOIN 的执行点）；切片文本与基准
   * 索引文本同一份（已剥离 GOLD 标记）。
   */
  private static void seedCorpus() throws Exception {
    slugCount = preparation.chunks().size();
    Map<String, String> filenameByKey = new LinkedHashMap<>();
    for (RagBenchmarkDataPreparer.FixtureDocument fixture : RagBenchmarkDataPreparer.FIXTURES) {
      filenameByKey.put(fixture.key(), fixture.filename());
    }
    try (Connection connection = openConnection();
        PreparedStatement file =
            connection.prepareStatement(
                "INSERT INTO uploaded_file (user_id, filename, original_filename, file_type, "
                    + "document_id, storage_key, status, knowledge_base) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
                    + "filename, category, page_no, row_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      Map<String, Boolean> filed = new LinkedHashMap<>();
      for (RagBenchmarkDataPreparer.BenchmarkChunk chunk : preparation.chunks()) {
        String key = fixtureKey(chunk.chunkId());
        String documentId = "benchdoc-" + key;
        String filename = filenameByKey.getOrDefault(key, chunk.filename());
        if (filed.putIfAbsent(key, Boolean.TRUE) == null) {
          file.setString(1, "user:99001");
          file.setString(2, filename);
          file.setString(3, filename);
          file.setString(4, filename.substring(filename.lastIndexOf('.') + 1));
          file.setString(5, documentId);
          file.setString(6, "storage/" + documentId);
          file.setString(7, "COMPLETED");
          file.setString(8, String.valueOf(EVAL_KB_ID));
          file.executeUpdate();
        }
        insert.setString(1, documentId);
        insert.setInt(2, chunk.chunkIndex());
        insert.setString(3, chunk.text());
        insert.setString(4, "parity-hash-" + chunk.chunkId());
        insert.setString(5, filename);
        insert.setString(6, chunk.category());
        insert.setObject(7, chunk.pageNo());
        insert.setObject(8, chunk.rowIndex());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static void optimizeFullTextIndex() throws Exception {
    try (Connection connection = openConnection();
        PreparedStatement optimize =
            connection.prepareStatement("OPTIMIZE TABLE document_vector_chunk")) {
      optimize.execute();
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
  }

  /** 生产稀疏服务装配：真实 Mapper 代理 + 真实 SparseRecallService（与 ChunkNgramRecallGateIT 同款）。 */
  private static SparseRecallService productionSparseRecallService() {
    PooledDataSource dataSource =
        new PooledDataSource(
            mysql.getDriverClassName(),
            mysql.getJdbcUrl(),
            mysql.getUsername(),
            mysql.getPassword());
    Configuration configuration = new Configuration();
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.setEnvironment(
        new Environment("eval-port-parity", new JdbcTransactionFactory(), dataSource));
    configuration.addMapper(DocumentVectorChunkMapper.class);
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    sqlSession = factory.openSession(true);
    return new SparseRecallService(sqlSession.getMapper(DocumentVectorChunkMapper.class));
  }

  /** 72 例逐查询：生产稀疏路 top-k（真 ngram）vs 基准 bigram 近似 top-k。 */
  private static List<QueryDifferential> computeDifferentials() {
    SparseBenchmarkRecallService bigram = new SparseBenchmarkRecallService(preparation.chunks());
    List<QueryDifferential> rows = new ArrayList<>();
    for (RagBenchmarkCase benchmarkCase : RagBenchmarkSuite.standard()) {
      String query = benchmarkCase.question();
      List<RetrievalCandidate> real =
          productionSparse.recall(query, List.of(EVAL_KB_ID), null, TOP_K);
      List<String> realIds = real.stream().map(EvalPortParitySparseTest::toBenchmarkId).toList();
      List<RetrievalCandidate> approx = bigram.recall(query, List.of(EVAL_KB_ID), null, TOP_K);
      List<String> approxIds = approx.stream().map(candidate -> candidate.hit().chunkId()).toList();
      List<String> common = intersection(realIds, approxIds);
      int maxRankShift = 0;
      for (String chunkId : common) {
        maxRankShift =
            Math.max(maxRankShift, Math.abs(realIds.indexOf(chunkId) - approxIds.indexOf(chunkId)));
      }
      rows.add(
          new QueryDifferential(
              benchmarkCase.id(),
              List.copyOf(realIds),
              List.copyOf(approxIds),
              common.size(),
              maxRankShift,
              stats(real),
              stats(approx)));
    }
    return rows;
  }

  /** 真库候选 → bench 命名空间：{@code <documentId 去 benchdoc- 前缀>-<chunkIndex>}。 */
  private static String toBenchmarkId(RetrievalCandidate candidate) {
    VectorSearchHit hit = candidate.hit();
    String documentId = hit.documentId() == null ? "" : hit.documentId();
    String key =
        documentId.startsWith("benchdoc-")
            ? documentId.substring("benchdoc-".length())
            : documentId;
    Object chunkIndex = hit.metadata() == null ? null : hit.metadata().get("chunkIndex");
    return key + "-" + (chunkIndex == null ? "?" : chunkIndex);
  }

  /** bench 命名空间反推语料 key（chunkId = {@code <语料key>-<chunkIndex>}，key 自身可含 '-'）。 */
  private static String fixtureKey(String chunkId) {
    return chunkId.substring(0, chunkId.lastIndexOf('-'));
  }

  private static List<String> intersection(List<String> left, List<String> right) {
    Set<String> rightSet = new LinkedHashSet<>(right);
    List<String> common = new ArrayList<>();
    for (String item : left) {
      if (rightSet.contains(item)) {
        common.add(item);
      }
    }
    return common;
  }

  private static ScoreStats stats(List<RetrievalCandidate> candidates) {
    if (candidates.isEmpty()) {
      return new ScoreStats(0d, 0d, 0d);
    }
    List<Double> scores =
        candidates.stream()
            .map(candidate -> round4(candidate.hit().score()))
            .sorted(Comparator.naturalOrder())
            .toList();
    return new ScoreStats(
        scores.get(0), scores.get(scores.size() / 2), scores.get(scores.size() - 1));
  }

  private static double round4(double value) {
    return Math.round(value * 10000d) / 10000d;
  }

  // ==================== R2 · 度量三口径（一跑两用：三口径 + 套件指标） ====================

  /**
   * 逐用例三口径：{@code rawFusedScore}（RRF 融合后、归一化前的 hit.score）、{@code rank}（同集合名次）、{@code
   * margin}（与相邻候选差）。
   *
   * <p>自证（R2 关键）：raw 必须是真的归一化前值——把它按生产 {@link DefaultWeightedReranker#rerank} 的
   * 口径归一后必须等于端口输出的分。这里用三次生产调用把恒等式拆开验证（不反射私有方法、不改 src/main）：
   *
   * <ul>
   *   <li>{@code V = rerank(query, 文本清空)} = wv · N(raw)（BM25 全零 ⇒ 该路归一后恒 0）；
   *   <li>{@code Z = rerank(query, 分值恒等)} = wv + wb · N(bm25)（向量路 max==min ⇒ 归一后恒 1）；
   *   <li>{@code W = rerank(query, 分值恒等 + 文本清空)} = wv；
   *   <li>断言 {@code V == wv · (raw - rawMin)/(rawMax - rawMin)} 且 {@code port == V + Z - wv}。
   * </ul>
   */
  private static Metrics computeTripletsAndMetrics() {
    CountingStubProvider provider = new CountingStubProvider();
    EmbeddingService embeddingService =
        new EmbeddingService(provider, new ModelProviderProperties());
    InMemoryVectorStore store = new InMemoryVectorStore();
    RagBenchmarkDataPreparer.Preparation prep =
        RagBenchmarkDataPreparer.prepare(store, EvalPortParitySparseTest::pseudoVector, EVAL_KB_ID);
    List<RagBenchmarkCase> suite =
        RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), prep);
    ObjectProvider<com.slz.crm.platform.contract.DynamicConfigService> providerCfg =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(Map.of()));
    RetrievalQueryRewriteService rewrite = new RetrievalQueryRewriteService(provider, providerCfg);
    DefaultWeightedReranker reranker = new DefaultWeightedReranker(new Bm25Scorer(), providerCfg);
    RrfFusion rrf = new RrfFusion();
    SparseBenchmarkRecallService sparse = new SparseBenchmarkRecallService(prep.chunks());

    Map<String, List<String>> rankedByCase = new LinkedHashMap<>();
    for (RagBenchmarkCase benchmarkCase : suite) {
      if (!benchmarkCase.useKnowledgeBase()) {
        rankedByCase.put(benchmarkCase.id(), List.of());
        nonApplicableCases.add(benchmarkCase.id() + ":EDGE-不走知识库检索（生产语义直接返回空）");
        continue;
      }
      String query = rewrite.rewrite(benchmarkCase.question());
      List<List<RetrievalCandidate>> routes = new ArrayList<>();
      for (String routeQuery : ConstraintQuerySplitter.split(query)) {
        List<RetrievalCandidate> vectorCandidates =
            recallVector(store, embeddingService.embed(routeQuery));
        List<RetrievalCandidate> sparseCandidates =
            sparse.recall(routeQuery, List.of(EVAL_KB_ID), null, CANDIDATE_LIMIT);
        routes.add(
            sparseCandidates.isEmpty()
                ? vectorCandidates
                : rrf.fuse(vectorCandidates, sparseCandidates, RRF_K));
      }
      List<RetrievalCandidate> fused = rrf.fuseAll(routes, RRF_K);
      rankedByCase.put(
          benchmarkCase.id(),
          fused.stream().map(candidate -> candidate.hit().chunkId()).limit(TOP_K).toList());
      if (fused.isEmpty()) {
        nonApplicableCases.add(benchmarkCase.id() + ":ZERO-CANDIDATE（该查询检索零候选，无三口径可落）");
        continue;
      }
      triplets.add(buildTriplet(reranker, query, fused, benchmarkCase));
    }
    Report report =
        RagQualityEvaluator.evaluate(
            suite,
            benchmarkCase ->
                new CaseOutcome(
                    rankedByCase.getOrDefault(benchmarkCase.id(), List.of()),
                    List.of(),
                    1d,
                    0L,
                    0L,
                    0,
                    true),
            TOP_K);
    queryEmbedCalls = provider.embedCalls();
    return report.metrics();
  }

  private static CaseTriplet buildTriplet(
      DefaultWeightedReranker reranker,
      String query,
      List<RetrievalCandidate> fused,
      RagBenchmarkCase benchmarkCase) {
    List<RetrievalCandidate> port = reranker.rerank(query, fused);
    List<RetrievalCandidate> vectorOnly = reranker.rerank(query, withTexts(fused, ""));
    List<RetrievalCandidate> bm25Only = reranker.rerank(query, withScores(fused, 1.0d));
    List<RetrievalCandidate> weightProbe =
        reranker.rerank(query, withScores(withTexts(fused, ""), 1.0d));

    double wv = weightProbe.get(0).rerankScore();
    for (RetrievalCandidate candidate : weightProbe) {
      assertEquals(
          wv,
          candidate.rerankScore(),
          IDENTITY_TOLERANCE,
          "向量权重探针必须整列同值（集合内归一后为常数）: " + benchmarkCase.id());
    }
    double rawMin =
        fused.stream().mapToDouble(candidate -> candidate.hit().score()).min().orElse(0d);
    double rawMax =
        fused.stream().mapToDouble(candidate -> candidate.hit().score()).max().orElse(0d);

    double identityMaxAbsDiff = 0d;
    for (int index = 0; index < fused.size(); index++) {
      double raw = fused.get(index).hit().score();
      double expectedVecNorm = productionMinMax(raw, rawMin, rawMax);
      double actualVecNorm = vectorOnly.get(index).rerankScore() / wv;
      identityMaxAbsDiff = Math.max(identityMaxAbsDiff, Math.abs(actualVecNorm - expectedVecNorm));
      double portScore = port.get(index).rerankScore();
      identityMaxAbsDiff =
          Math.max(
              identityMaxAbsDiff,
              Math.abs(
                  portScore
                      - (wv * productionMinMax(raw, rawMin, rawMax)
                          + (bm25Only.get(index).rerankScore() - wv))));
    }
    assertTrue(
        identityMaxAbsDiff <= IDENTITY_TOLERANCE,
        "三口径恒等式越界（raw 不是归一化前值或端口分数口径漂移）: case="
            + benchmarkCase.id()
            + " maxAbsDiff="
            + identityMaxAbsDiff
            + " > "
            + IDENTITY_TOLERANCE);

    List<Integer> order = new ArrayList<>();
    for (int index = 0; index < port.size(); index++) {
      order.add(index);
    }
    order.sort(
        Comparator.comparingDouble((Integer index) -> port.get(index).rerankScore()).reversed());

    List<CandidateTriplet> rows = new ArrayList<>();
    int limit = Math.min(3, order.size());
    double tailPortScore = order.size() > limit ? port.get(order.get(limit)).rerankScore() : 0d;
    for (int rank = 0; rank < limit; rank++) {
      int index = order.get(rank);
      double margin =
          rank + 1 < order.size()
              ? port.get(index).rerankScore() - port.get(order.get(rank + 1)).rerankScore()
              : port.get(index).rerankScore() - tailPortScore;
      rows.add(
          new CandidateTriplet(
              fused.get(index).hit().chunkId(),
              rank,
              fused.get(index).hit().score(),
              productionMinMax(fused.get(index).hit().score(), rawMin, rawMax),
              bm25Only.get(index).rerankScore(),
              port.get(index).rerankScore(),
              margin));
    }
    return new CaseTriplet(
        benchmarkCase.id(),
        benchmarkCase.category().name(),
        port.size(),
        rawMin,
        rawMax,
        wv,
        identityMaxAbsDiff,
        rows,
        tailPortScore);
  }

  /** 按生产 normalizeScores 的三分支口径做 min-max（max<=0 ⇒ 0；max==min ⇒ 1；否则比例）。 */
  static double productionMinMax(double value, double min, double max) {
    if (max <= 0d) {
      return 0d;
    }
    if (max == min) {
      return 1d;
    }
    return (value - min) / (max - min);
  }

  /** 只替换候选文本（BM25 打分对象），分值原样保留。 */
  private static List<RetrievalCandidate> withTexts(
      List<RetrievalCandidate> candidates, String text) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      VectorSearchHit hit = candidate.hit();
      result.add(
          new RetrievalCandidate(
              new VectorSearchHit(
                  hit.chunkId(), hit.documentId(), hit.score(), text, hit.metadata()),
              candidate.rerankScore()));
    }
    return result;
  }

  /** 只替换候选分值（向量路打分对象），文本与元数据原样保留。 */
  private static List<RetrievalCandidate> withScores(
      List<RetrievalCandidate> candidates, double score) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      VectorSearchHit hit = candidate.hit();
      result.add(
          new RetrievalCandidate(
              new VectorSearchHit(
                  hit.chunkId(), hit.documentId(), score, hit.text(), hit.metadata()),
              candidate.rerankScore()));
    }
    return result;
  }

  /** 向量路召回（照生产 KnowledgeRetrievalServiceImpl#recallIn 读法直构）。 */
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

  // ==================== 锚装配 ====================

  private static ObjectNode buildAnchor() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", CARD);
    root.put("suiteVersion", RagBenchmarkSuite.SUITE_VERSION);
    root.put("caseCount", RagBenchmarkSuite.standard().size());
    root.put("evalKbId", EVAL_KB_ID);
    root.put(
        "zeroExternalCallBasis",
        "本类唯一 ModelProvider 是确定性桩 EvalStubProvider（streamChat/vision 即抛、embed 为本地哈希向量）；"
            + "真库侧只有 Testcontainers MySQL 与 Flyway，无任何模型网络出口；未读 key、未跑 RAG_BENCHMARK_REAL=1");
    root.put("srcMainDiff", "ZERO");
    root.put(
        "srcMainDiffBasis",
        "本卡写集只含 src/test 三个新测试、docs/rag-quality 两个新锚与 openspec 的 tasks.md；src/main/pom.xml/.github 零字节变动");
    root.put("verdictPort", "ERROR-BAR-PARTIAL-QUANTIFIED");
    root.put(
        "verdictPortScope",
        "误差条已量化到：① 稀疏腿（真 MySQL 8.0.36 ngram vs bigram 近似的逐查询差异表）；"
            + "② 度量腿（raw/rank/margin 三项并列 + 生产归一化恒等式，容差 ≤1e-12）；"
            + "③ 输入腿（真嵌入通道的调用台账 + 预算推导，执行状态见 binary 段）。"
            + "仍未量化：语料腿（15 份固定 fixture，非生产库语料）——该腿的结论仍不可得");
    root.put(
        "ciPortUnchanged",
        "RagQualityRegressionTest 端口未改：默认 `mvn test` 的 surefire 契约是无 Docker（AGENTS.md 明写），"
            + "把真 MySQL/真 ngram 塞进默认端口会让无 Docker 环境直接红（proposal D6）；"
            + "本卡真装置只进独立测试类（Docker 不可用即 assumeTrue 跳过），回归端口一字不动、判据与基线下限零改动");
    ObjectNode binary = preservedOrNotExecutedBinary();
    root.put("externalCalls", binary.path("ledger").path("actualCalls").asInt(0));
    root.put("defaultExternalCalls", 0);
    root.set("legs", buildLegs());
    root.set("sparseDifferential", buildSparseDifferential());
    root.set("metricTriplets", buildMetricTriplets());
    root.set("binary", binary);
    ObjectNode budget = buildBudget();
    budget.put("actualCalls", binary.path("ledger").path("actualCalls").asInt(0));
    root.set("budget", budget);
    root.set("residualDeviation", buildResidualDeviation());
    root.set("runtime", buildRuntime());
    root.set("numericHygiene", buildNumericHygiene());
    root.put("decisionOwner", "OWNER");
    return root;
  }

  /**
   * binary 段：真嵌入重放的实测台账归 {@link EvalPortParityRealEmbeddingReplayTest} 在写模式下写入；本生成器一旦发现 既有锚已登记
   * {@code executed=true}，就原样保留该段（证据不因再生成而丢失，同时保证默认复跑逐字节一致）。
   */
  private static ObjectNode preservedOrNotExecutedBinary() {
    if (Files.exists(ANCHOR_JSON)) {
      try {
        JsonNode existing = MAPPER.readTree(Files.readString(ANCHOR_JSON, StandardCharsets.UTF_8));
        JsonNode binary = existing.path("binary");
        if (binary.isObject() && binary.path("executed").asBoolean(false)) {
          return (ObjectNode) binary;
        }
      } catch (Exception ignored) {
        // 既有锚不可解析 → 走未执行分支（生成器只负责自洽性）
      }
    }
    ObjectNode binary = MAPPER.createObjectNode();
    binary.put("executed", false);
    binary.put("status", "NOT_EXECUTED");
    binary.put(
        "reason",
        "真嵌入是唯一花钱动作，默认路径必须零外呼（R3）：本生成器从未构造真实 provider；"
            + "需显式 opt-in（RAG_BENCHMARK_REAL=1 且存在 DASHSCOPE_API_KEY）后由 "
            + "EvalPortParityRealEmbeddingReplayTest 执行一次并回填本段");
    ObjectNode ledger = binary.putObject("ledger");
    ledger.put("plannedCalls", plannedCalls());
    ledger.put("actualCalls", 0);
    ledger.put("actualTokens", 0L);
    ledger.put("actualTokensBasis", "未执行：无 token 消耗");
    ledger.put("elapsedMs", 0L);
    ledger.put("costCny", 0d);
    binary.put("pseudoSuiteMetrics", metricsNode(pseudoMetrics));
    binary.put("realSuiteMetrics", MAPPER.createObjectNode());
    binary.put("deltaBasis", "未执行 → 伪向量 vs 真嵌入的差不可得（记 UNDETERMINED，禁近似顶替）");
    return binary;
  }

  private static int plannedCalls() {
    int querySide = queryEmbedCalls > 0 ? queryEmbedCalls : RagBenchmarkSuite.standard().size();
    return querySide + preparation.chunks().size();
  }

  private static ObjectNode buildBudget() {
    long chars = 0L;
    for (RagBenchmarkDataPreparer.BenchmarkChunk chunk : preparation.chunks()) {
      // 嵌入输入是「块头 + 切片文本」；块头上界按 64 字符保守计入
      chars += chunk.text().length() + 64L;
    }
    for (RagBenchmarkCase benchmarkCase : RagBenchmarkSuite.standard()) {
      chars += benchmarkCase.question().length();
    }
    // 保守上界：1 token / 字符（中文实际约 1 token / 1-1.5 字符，此口径只会高估不会低估）
    long estimatedTokens = chars;
    double estimatedCostCny = estimatedTokens * PRICE_CNY_PER_MILLION_TOKENS / 1_000_000d;
    if (estimatedCostCny > CAP_CNY) {
      throw new IllegalStateException(
          "预算推导超帽：estimated="
              + estimatedCostCny
              + " 元 > cap="
              + CAP_CNY
              + " 元；按 R4/D4 抛错终止，不得先跑几条看看");
    }
    ObjectNode budget = MAPPER.createObjectNode();
    budget.put("capCny", CAP_CNY);
    budget.put("priceCnyPerMillionTokens", PRICE_CNY_PER_MILLION_TOKENS);
    budget.put("priceSource", PRICE_SOURCE);
    budget.put("embeddingModel", EMBEDDING_MODEL);
    ObjectNode plan = budget.putObject("plan");
    plan.put("queryCases", RagBenchmarkSuite.standard().size());
    plan.put("queryCalls", RagBenchmarkSuite.standard().size());
    plan.put(
        "queryEmbedCalls",
        queryEmbedCalls > 0 ? queryEmbedCalls : RagBenchmarkSuite.standard().size());
    plan.put(
        "queryEmbedBasis",
        "72 查询经生产改写/拆路后每路一次 embed，干跑（本地伪向量，零外呼）实测得 queryEmbedCalls；" + "静态下界是每查询 1 次");
    plan.put("fixtureSliceCalls", preparation.chunks().size());
    plan.put(
        "plannedCalls",
        (queryEmbedCalls > 0 ? queryEmbedCalls : RagBenchmarkSuite.standard().size())
            + preparation.chunks().size());
    plan.put("estimatedTokens", estimatedTokens);
    plan.put("tokenEstimateBasis", EvalPortParityRealEmbeddingReplayTest.TOKEN_ESTIMATE_BASIS);
    plan.put("estimatedCostCny", estimatedCostCny);
    plan.put("hardCheck", "PASS：estimatedCostCny=" + estimatedCostCny + " <= capCny=" + CAP_CNY);
    plan.put("hardCheckTiming", "第一次真实调用之前（生成器侧静态推导；重放侧再次断言后才调用）");
    int planned =
        (queryEmbedCalls > 0 ? queryEmbedCalls : RagBenchmarkSuite.standard().size())
            + preparation.chunks().size();
    double averageCostPerCall = estimatedCostCny / planned;
    long capCalls = (long) Math.floor(CAP_CNY / averageCostPerCall);
    budget.put("estimatedCalls", planned);
    budget.put("actualCalls", 0);
    budget.put("capCalls", capCalls);
    budget.put(
        "monotone",
        "estimatedCalls("
            + planned
            + ") <= actualCalls(见 binary.ledger) <= capCalls("
            + capCalls
            + ")；且 actualCostCny <= capCny("
            + CAP_CNY
            + ")");
    return budget;
  }

  private static ArrayNode buildLegs() {
    ArrayNode legs = MAPPER.createArrayNode();
    legs.add(
        leg(
            "装配腿",
            "KnowledgeRetrievalServiceImpl + DefaultWeightedReranker + RrfFusion + Bm25Scorer 生产类直构（本腿本就在生产路上）",
            "本卡不动装配：只复用同一批生产类直构做三口径与差异表",
            true,
            "eval-port-route-v1 legs[装配腿] classification=PRODUCTION；本卡读法未改装配"));
    legs.add(
        leg(
            "稀疏腿",
            "生产 SparseRecallService：MySQL FULLTEXT(ngram) + uploaded_file 授权 JOIN + is_deleted/chunk_role 过滤；CI 端口用的是测试子类 SparseBenchmarkRecallService（字符 bigram 重叠近似、无授权 JOIN、无软删）",
            "本卡用生产同一条 fulltextSearch 在真 MySQL 8.0.36 上取真 ngram top-k，与 bigram 近似逐查询对照并落差异表；端口不替换（D6），近似不被认作对齐（D2）",
            false,
            "锚 sparseDifferential 逐查询登记集合重叠、名次位移、分数 min/p50/max；取候选走生产 SparseRecallService"));
    legs.add(
        leg(
            "度量腿",
            "生产 DefaultWeightedReranker：向量/BM25 各自 min-max 归一后按 0.6/0.4 加权，端口只报归一化后的单个分",
            "本卡改成三口径并列：rawFusedScore（归一化前融合分）+ rank（同集合名次）+ margin（与相邻候选差），归一化分只作对照",
            false,
            "锚 metricTriplets 逐用例三项齐备，并用生产 reranker 三次调用把归一化恒等式自证到 ≤1e-12"));
    legs.add(
        leg(
            "输入腿",
            "生产应接真实 ModelProvider#embed（text-embedding-v3）；默认路径仍是确定性哈希伪向量（256 维）",
            "本卡开一条显式 opt-in 的真嵌入通道：计数 + 预算硬帽（¥5 封顶），只跑一次；结果与伪向量基线对照后入锚",
            false,
            "锚 budget 段给出调用数 × token 估算 × 单价来源；锚 binary 段登记是否执行、调用台账与伪/真指标对照"));
    legs.add(
        leg(
            "语料面",
            "生产应接生产库真实文档语料；实测用的是 15 份固定 fixture（src/test/resources/rag-quality/fixtures/）",
            "本卡明确不碰语料（D5）：只用同一批 fixture 做端口误差量化",
            false,
            "eval-port-route-v1 legs[语料面] classification=TEST-FIXTURE；本卡 residualDeviation 显式声明该腿仍 off-production"));
    legs.add(
        leg(
            "归一化面",
            "生产 normalizeScores min-max（集合内归一，不是替身，是生产实现的语义属性）",
            "本卡不改生产归一化：只把归一化前的 raw 一并留下，并在锚内标注归一化分是集合内相对量",
            false,
            "锚 metricTriplets 的 vecNorm 由 raw 与集合 min/max 反算，与生产归一化输出对齐到 ≤1e-12"));
    return legs;
  }

  private static ObjectNode leg(
      String name, String current, String disposition, boolean aligned, String evidence) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("name", name);
    node.put("current", current);
    node.put("disposition", disposition);
    node.put("aligned", aligned);
    node.put("evidence", evidence);
    return node;
  }

  private static ObjectNode buildSparseDifferential() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("index", "document_vector_chunk.ft_chunk_text WITH PARSER ngram（V22 真迁移）");
    node.put("engine", "mysql:8.0.36（Testcontainers，与生产同版本）");
    node.put(
        "productionSql",
        "DocumentVectorChunkMapper#fulltextSearch（同授权 JOIN、同 is_deleted/chunk_role 过滤）");
    node.put("topK", TOP_K);
    node.put("queries", differentials.size());
    node.put("totalRealHits", differentials.stream().mapToInt(row -> row.real().size()).sum());
    node.put("totalBigramHits", differentials.stream().mapToInt(row -> row.bigram().size()).sum());
    node.put("totalOverlap", differentials.stream().mapToInt(QueryDifferential::overlap).sum());
    node.put(
        "namespaceMapping",
        "真库 chunkId(Long) → <document_id 去 benchdoc- 前缀>-<chunk_index>，与基准向量库 chunkId 同命名空间");
    node.put("scoreScale", "两侧分数都是各自口径的原始分，仅作分布对照，未做任何重标定");
    ArrayNode rows = node.putArray("rows");
    for (QueryDifferential row : differentials) {
      ObjectNode entry = rows.addObject();
      entry.put("caseId", row.caseId());
      ArrayNode real = entry.putArray("realTopK");
      row.real().forEach(real::add);
      ArrayNode bigram = entry.putArray("bigramTopK");
      row.bigram().forEach(bigram::add);
      entry.put("overlap", row.overlap());
      entry.put("maxRankShift", row.maxRankShift());
      entry.set("realScore", scoreNode(row.realScore()));
      entry.set("bigramScore", scoreNode(row.bigramScore()));
    }
    return node;
  }

  private static ObjectNode scoreNode(ScoreStats stats) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("min", stats.min());
    node.put("p50", stats.p50());
    node.put("max", stats.max());
    return node;
  }

  private static ObjectNode buildMetricTriplets() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("topK", TOP_K);
    node.put("caseCount", RagBenchmarkSuite.standard().size());
    node.put("applicableCases", triplets.size());
    node.put("identityTolerance", IDENTITY_TOLERANCE);
    node.put("identityBasis", "portScore == wv·(raw-rawMin)/(rawMax-rawMin) + (zScore - wv)");
    node.put(
        "identityDerivation",
        "三次生产 DefaultWeightedReranker 调用拆开恒等式：V（文本清空）= wv·N(raw)；Z（分值恒等）= wv + wb·N(bm25)；W（恒等+清空）= wv；断言 port == V + Z - wv 且 V == wv·N(raw)");
    node.put("rawDefinition", "RRF 融合后、进入 reranker 之前的 hit.score（归一化前融合分）");
    node.put("rankDefinition", "同一候选集合内按 portScore 降序的名次（0 起）");
    node.put("marginDefinition", "portScore 与相邻下一名候选之差；末位与集合外下一名（tailPortScore）之差");
    node.set("pseudoSuiteMetrics", metricsNode(pseudoMetrics));
    ArrayNode cases = node.putArray("cases");
    for (CaseTriplet triplet : triplets) {
      ObjectNode entry = cases.addObject();
      entry.put("caseId", triplet.caseId());
      entry.put("category", triplet.category());
      entry.put("fusedCount", triplet.fusedCount());
      entry.put("rawMin", triplet.rawMin());
      entry.put("rawMax", triplet.rawMax());
      entry.put("normalizedVectorWeight", triplet.normalizedVectorWeight());
      entry.put("identityMaxAbsDiff", triplet.identityMaxAbsDiff());
      entry.put("tailPortScore", triplet.tailPortScore());
      ArrayNode candidates = entry.putArray("candidates");
      for (CandidateTriplet candidate : triplet.candidates()) {
        ObjectNode row = candidates.addObject();
        row.put("chunkId", candidate.chunkId());
        row.put("rank", candidate.rank());
        row.put("rawFusedScore", candidate.rawFusedScore());
        row.put("vecNorm", candidate.vecNorm());
        row.put("zScore", candidate.zScore());
        row.put("portScore", candidate.portScore());
        row.put("margin", candidate.margin());
      }
    }
    ArrayNode skipped = node.putArray("nonApplicable");
    nonApplicableCases.forEach(skipped::add);
    return node;
  }

  private static ObjectNode metricsNode(Metrics metrics) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("caseCount", metrics.caseCount());
    node.put("recallAt5", metrics.meanRecallAtK());
    node.put("mrr", metrics.mrr());
    return node;
  }

  private static ArrayNode buildResidualDeviation() {
    ArrayNode array = MAPPER.createArrayNode();
    array.add(
        residual(
            "语料腿",
            "STILL-OFF-PRODUCTION",
            "语料仍是 15 份固定 fixture，非生产库真实文档；本卡明确不碰（D5）",
            List.of("生产语料分布", "把本锚召回率或分数外推为生产库真实语料上的检索质量（eval-port-route-v1#nonSupportable）")));
    array.add(
        residual(
            "输入腿",
            "OPT-IN-ONLY",
            "默认路径仍是确定性哈希伪向量；真嵌入只在显式 opt-in 下执行一次，执行状态见 binary 段",
            List.of("伪向量余弦分布当作真嵌入分数分布", "未执行时伪/真嵌入的差")));
    array.add(
        residual(
            "稀疏腿",
            "QUANTIFIED-NOT-ALIGNED",
            "真 ngram 差异已量化，但 CI 端口仍用 bigram 近似（D6）；本卡不认「近似=对齐」（D2）",
            List.of("把 bigram 近似当生产 FULLTEXT(ngram) 相关度", "稀疏路绝对值跨卡比较")));
    array.add(
        residual(
            "度量腿",
            "REPORTED-TRIPLET",
            "三项已并列，但归一化分仍是集合内相对量，跨查询/跨语料不可比",
            List.of("跨查询/跨语料分数比较与分位")));
    array.add(
        residual(
            "归一化面",
            "SET-RELATIVE",
            "min-max 集合内归一不动；本卡只把归一化前值一并留下，不改口径",
            List.of("从本套件取「文本路真分数分布」")));
    return array;
  }

  private static ObjectNode residual(String leg, String status, String why, List<String> excluded) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("leg", leg);
    node.put("status", status);
    node.put("whyStillOffProduction", why);
    ArrayNode array = node.putArray("excludedConclusions");
    excluded.forEach(array::add);
    node.put("ref", "docs/rag-quality/eval-port-route-v1.json（eval-port-route-v1#nonSupportable）");
    return node;
  }

  private static ObjectNode buildRuntime() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("generatedBy", "EvalPortParitySparseTest#anchorRebuildOrCompare");
    node.put("generatorWriteProperty", "-Deval.port.parity.write=true");
    node.put("mysqlImage", MYSQL_IMAGE);
    node.put("migration", "classpath:db/migration（真 Flyway 全量迁移，建出 V22 ngram 索引）");
    node.put("dockerRequired", true);
    node.put("dockerAbsentBehavior", "assumeTrue 跳过整类（CI 的 surefire 无 Docker 契约不变）");
    node.put("excludedFromByteCompare", false);
    return node;
  }

  private static ObjectNode buildNumericHygiene() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("lineEnding", "LF");
    node.put("trailingNewline", true);
    node.put("jsonIndent", "2 spaces");
    node.put("sparseScoreScale", "round to 4 decimal places（MySQL 相关度，仅分布对照）");
    node.put("metricScale", "raw IEEE-754 double（逐字节复算依赖确定性的内存管线）");
    node.put("identityTolerance", IDENTITY_TOLERANCE);
    return node;
  }

  private static String buildAnchorMd() {
    Metrics metrics = pseudoMetrics;
    return String.join(
            "\n",
            "# eval-port-parity v1 · 评测端口生产化误差条（卡 eval-port-production-parity）",
            "",
            "- 判定: ERROR-BAR-PARTIAL-QUANTIFIED —— 稀疏腿差异 + 度量三口径已量化；语料腿与默认伪向量仍未量化",
            "- CI 端口: **未改** —— RagQualityRegressionTest 一字不动，真装置只进独立测试类（proposal D6；无 Docker 即跳过）",
            "- 稀疏腿: 真 MySQL 8.0.36 + 真 Flyway（V22 ngram）跑生产同一条 `fulltextSearch`，72 查询逐条对照 bigram 近似",
            "  - 真 ngram 命中数 / bigram 命中数 / top-5 集合重叠总数见锚 `sparseDifferential`",
            "  - 差异只报数字（集合重叠、名次位移、分数 min/p50/max），定性措辞一律不落锚（守卫词表断言）",
            "- 度量腿: 每用例并列 `rawFusedScore`（归一化前融合分）/ `rank`（同集合名次）/ `margin`（与相邻候选差）",
            "  - 自证: 生产 reranker 三次调用拆开恒等式 `port == V + Z - wv` 且 `V == wv·N(raw)`，容差 1e-12",
            "- 归一化面: min-max 仍是集合内相对量；锚只保留归一化前值，不改生产实现",
            "- 输入腿: 真嵌入通道 opt-in（`RAG_BENCHMARK_REAL=1` + key），预算 ¥5 封顶、第一次调用前硬检查",
            "  - 计划调用 = 查询侧 embed（≥72，改写拆路后按干跑实测）+ fixture 全量切片；单价 text-embedding-v3「文本输入」0.5 元/百万 tokens",
            "  - 执行状态与调用台账见锚 `binary.ledger`；未执行即记 UNDETERMINED，不用近似顶替",
            "- 语料腿: 15 份固定 fixture，本卡不碰；`residualDeviation` 逐条列仍 off-production 的腿与被排除结论",
            "- 伪向量基线指标: recall@5="
                + round4(metrics.meanRecallAtK())
                + " / mrr="
                + round4(metrics.mrr())
                + "（fixture 语料 + 伪向量；禁外推）",
            "- 细节锚: docs/rag-quality/eval-port-parity-v1.json（写模式重建，默认逐字节复算；纯 LF）")
        + "\n";
  }

  // ==================== 措辞与边界守卫（生成侧先自检，守卫侧再独立断言） ====================

  /** R1 措辞禁令（卡 29 R3 同源）：稀疏腿差异不得被写成等价/可替换。 */
  private static void assertNoBannedWording(String text, String where) {
    for (String banned : List.of("等价", "一致", "无差异", "可替换", "基本相同", "大致相同", "没有区别")) {
      assertFalse(text.contains(banned), where + " 出现禁用措辞「" + banned + "」（R1 措辞禁令）");
    }
  }

  /** R7 外推禁令：不得把本锚数字写成生产语义。 */
  private static void assertNoExtrapolation(String text, String where) {
    for (String banned : List.of("生产分数分布", "生产召回质量", "可跨卡比较", "生产语义召回")) {
      assertFalse(text.contains(banned), where + " 出现外推断言「" + banned + "」（R7）");
    }
  }

  private static void writeOrCompare(Path path, byte[] expected) throws Exception {
    if (WRITE_MODE) {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(path, expected);
      System.out.println("[eval-port-parity] 锚已写入（write 模式）: " + path);
      return;
    }
    assertTrue(
        Files.exists(path),
        "结论锚缺失，先以 -Deval.port.parity.write=true 生成一次: " + path.toAbsolutePath());
    assertArrayEquals(expected, Files.readAllBytes(path), "锚与本次全量重算逐字节不一致（锚被改动或链路行为漂移）: " + path);
  }

  // ==================== 记录与确定性桩 ====================

  /** 单查询差异行（只报数字，不下结论）。 */
  private record QueryDifferential(
      String caseId,
      List<String> real,
      List<String> bigram,
      int overlap,
      int maxRankShift,
      ScoreStats realScore,
      ScoreStats bigramScore) {}

  /** 分数分布三元（各侧口径原始分，未重标定）。 */
  private record ScoreStats(double min, double p50, double max) {}

  /** 单用例三口径行。 */
  private record CaseTriplet(
      String caseId,
      String category,
      int fusedCount,
      double rawMin,
      double rawMax,
      double normalizedVectorWeight,
      double identityMaxAbsDiff,
      List<CandidateTriplet> candidates,
      double tailPortScore) {}

  /** 单候选三口径。 */
  private record CandidateTriplet(
      String chunkId,
      int rank,
      double rawFusedScore,
      double vecNorm,
      double zScore,
      double portScore,
      double margin) {}

  /** 确定性模型桩：embed 走本地哈希向量，chat 恒等回传，stream/vision 即抛。 */
  private static class EvalStubProvider implements ModelProvider {

    private static final String STUB = "eval-port-parity-stub";

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
      return ModelCallResult.ofVector(pseudoVector(text), STUB, 0L, 0L);
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

  /** 计数版确定性桩：只数 embed 次数（供预算推导取「72 查询经拆路后的实际 embed 次数」）。 */
  private static final class CountingStubProvider extends EvalStubProvider {

    private int embedCalls;

    int embedCalls() {
      return embedCalls;
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      embedCalls++;
      return super.embed(request);
    }
  }

  /** 与卡 29 同型的测试侧确定性词面向量（256 维有符号哈希 + 次线性抑制 + L2 归一）。 */
  static float[] pseudoVector(String text) {
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
