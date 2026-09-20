package com.slz.crm.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * ngram 召回质量闸门（complete-hybrid-retrieval-and-rerank 任务 1.2；expand-rag-benchmark 任务 3.2 扩
 * L-14；TASK-21 改走生产链路 + 用例拆可定位；TASK-23 断言收紧为黄金 chunkId）。
 *
 * <p>目的：在真 MySQL（与生产同版本 8.0.36）上验证 V22 的 ngram 全文索引能把 LEXICAL 型查询（型号/编号/专有名词）的黄金切片召回进 top-5——这是「选项
 * A（FULLTEXT ngram）能否作为稀疏召回路载体」的实测决策点：不达标则切换方案 B（内存倒排索引），结论写回 tasks.md 验证记录。
 *
 * <p>断言路径（TASK-21 改动契约 1）：查询一律经<b>生产稀疏召回服务 {@link SparseRecallService}</b>（与线上同一个类、同一段 {@code
 * DocumentVectorChunkMapper#fulltextSearch} SQL、同一套授权 JOIN / {@code is_deleted} / {@code
 * chunk_role='CHILD'} 过滤语义），门禁不再直接执行裸 {@code MATCH...AGAINST}。裸 SQL
 * 只在断言失败的消息里作诊断对照，用于区分「索引层就没召回」与「服务层把召回吃掉了」。 语料因此必须与生产同构：每个切片所属文档都要有 {@code uploaded_file}
 * 归属行，否则稀疏路按授权口径一行都查不到。
 *
 * <p>断言判据（TASK-23 改动契约 1，从 TASK-21 的「top-K 正文含锚点串」收紧）：每个 LEXICAL 用例携带一个<b>可接受黄金 chunkId
 * 集合</b>，断言生产稀疏路 top-K 返回候选中存在 chunkId ∈ 该集合。锚点文本判据弱于「命中正确 chunk」——锚点在入库时被剥离、且同一锚点可出现在多个黄金切片（如
 * {@code XR-500} 同现 model-xr500 与维护表行），共享锚点的用例按实测填多元素集合，不为凑单值改语料。可接受集合 = 所有「剥离后正文仍含该锚点、且原文自带完整 GOLD
 * 标记」的入库切片的 chunkId。
 *
 * <p>chunkId 捕获（TASK-23 改动契约 2，确定性口径）：种子插入<b>不依赖自增顺序假设</b>——每个带 GOLD 标记的切片入库后按业务键（document_id +
 * chunk_index）回查主键，回查必须恰得 1 行，否则装配直接失败；逐用例的可接受集合在装配期算好（用例号 → chunkId 集合），经 {@code @MethodSource}
 * 数据结构以懒取 supplier 形式传入断言（装配在 {@code @BeforeAll} 完成，用例参数在 JUnit 发现期即创建，故集合必须延迟解析而非发现期求值）。
 *
 * <p>方法：把 rag-quality/fixtures 的 LEXICAL 相关语料（型号目录/代号登记册 + 扩容新增的 客户 SOP/价格政策/区域政策/SLA/维保周期表，其余
 * fixtures 作干扰项）经真实 {@link DocumentService} 分块后灌入 document_vector_chunk（剥离 GOLD 标记，与基准数据准备
 * 同口径），用整句查询（不做关键词抽取）走服务层，断言每个 LEXICAL 用例（L-01～L-14）的黄金切片 chunkId 进入 top-5。
 *
 * <p>可定位性（TASK-21 改动契约 2）：14 个 LEXICAL 用例为 {@code @ParameterizedTest} 的 14 条独立用例，用例号即展示名，
 * 单条退化只红那一条； 语料装配哨兵（GOLD 标记全集被发现）是第 15 条独立用例。TASK-23 只收紧判据（锚点文本 → 黄金 chunkId 集合），用例总数、{@code TOP_K}
 * 截断、Docker 守卫与建数据方式逐字不动；失败消息仍保留「生产路 top-K 明细 + 裸 SQL 诊断对照」（TASK-23 契约 3）。
 *
 * <p>recall 门槛（TASK-21 改动契约 2，禁止顺手动）：{@code TOP_K = 5}，即稀疏路 recall@5 必须 14/14 命中；5 与生产 topK 默认值
 * （单一真相源 {@code platform.contract.RetrievalDefaults.TOP_K}）及质量基准 TOP_K=5 同口径。
 *
 * <p>FTS 刷盘（expand-rag-benchmark 任务 3.2 实测）：InnoDB FULLTEXT 索引对批量插入的 更新先进内存 index
 * cache，刷盘完成前查询过滤正确但相关度全为 0（ORDER BY 退化）。 闸门在入库后执行 {@code OPTIMIZE TABLE} 强制把缓存刷进磁盘索引，保证相关度排序确定；
 * 生产路径由自然的时间间隔（入库与查询不同秒）覆盖，本实测结论记入 tasks.md 验证记录。
 *
 * <p>Docker 门禁：无 Docker 时 assumeTrue 跳过（本地开发机）；CI 真跑。
 */
class ChunkNgramRecallGateIT {

  /**
   * LEXICAL 用例（与 RagBenchmarkSuite L-01～L-14 对齐）：用例号、整句问题、黄金锚点。
   *
   * <p>TASK-23 起锚点不再直接作断言判据，而是<b>可接受黄金 chunkId 集合的推导键</b>： 集合 = 所有「正文含该锚点且自带 GOLD 标记」的入库切片的
   * chunkId（多块共享锚点即多元素集，见类注释）。
   *
   * @param caseId 用例号，同时是参数化用例展示名（可定位性来源）
   * @param question 整句查询（不预切词，交给 ngram）
   * @param goldenAnchor 黄金切片正文必含的词法锚点（型号/编号/人名/版本号），用于圈定该用例的可接受黄金 chunkId 集合
   */
  private record LexicalGateCase(String caseId, String question, String goldenAnchor) {}

  private static final List<LexicalGateCase> LEXICAL_CASES =
      List.of(
          new LexicalGateCase("L-01", "XR-500 设备的售后对接人是谁", "XR-500"),
          new LexicalGateCase("L-02", "ZB-220 的备件放在哪个库位", "ZB-220"),
          new LexicalGateCase("L-03", "合同编号 HT-2024-0889 签的是哪个项目", "HT-2024-0889"),
          new LexicalGateCase("L-04", "凤凰计划由哪个交付组实施", "凤凰计划"),
          new LexicalGateCase("L-05", "KQ-9000 的固件应该升级到哪个版本", "KQ-9000"),
          new LexicalGateCase("L-06", "内部代号 BK-2024 对应哪个项目", "BK-2024"),
          // expand-rag-benchmark 任务 3.2：新增 L-07～L-14（编号/人名/版本号）
          new LexicalGateCase("L-07", "政策 RSP-2024-04 保护的是哪类业务", "RSP-2024-04"),
          new LexicalGateCase("L-08", "客户编号 CUS-2026-0088 的客户经理是谁", "CUS-2026-0088"),
          new LexicalGateCase("L-09", "工单 WO-2026-0521 是哪台设备的维保工单", "WO-2026-0521"),
          new LexicalGateCase("L-10", "价格政策的负责人是谁", "孙丽华"),
          new LexicalGateCase("L-11", "SLA 责任工程师是谁", "何建军"),
          new LexicalGateCase("L-12", "华东区的区域经理是谁", "吴国强"),
          new LexicalGateCase("L-13", "客户管理 SOP 当前是哪个版本", "v4.2"),
          new LexicalGateCase("L-14", "价格政策的版本号是多少", "v2.7"));

  /** LEXICAL 黄金标记全集：入库后必须都能被发现，否则语料装配有问题，闸门断言对象不成立。 */
  private static final Set<String> EXPECTED_GOLD_IDS =
      Set.of(
          "model-xr500",
          "model-zb220",
          "model-kq9000",
          "code-ht0889",
          "code-fenghuang",
          "code-bluewhale",
          // expand-rag-benchmark 任务 3.2：新增 LEXICAL 语料的黄金标记
          "policy-004",
          "customer-code-0088",
          "maint-xr500-q",
          "price-owner",
          "sla-owner",
          "policy-001",
          "customer-version");

  /** LEXICAL 语料：型号目录 + 代号登记册 + 扩容新增 5 语料；其余 fixtures 作干扰项一并入库，检验排名而非单纯命中。 */
  private static final List<String[]> GATE_FIXTURES =
      List.of(
          new String[] {"models", "product-model-catalog.md"},
          new String[] {"codes", "contract-code-registry.txt"},
          new String[] {"sales-flow", "sales-contract-flow.md"},
          new String[] {"payment-plan", "payment-plan.md"},
          // expand-rag-benchmark 任务 3.2：新增 5 语料（L-07～L-14 的黄金锚点所在）
          new String[] {"customer-sop", "customer-sop.md"},
          new String[] {"pricing", "pricing-policy.md"},
          new String[] {"regional-policy", "regional-policy.txt"},
          new String[] {"sla", "sla-terms.md"},
          new String[] {"maint", "maintenance-schedule.xlsx"});

  private static final Pattern GOLD_MARKER =
      Pattern.compile("【GOLD:([A-Za-z0-9_\\-\\u4e00-\\u9fff]+)】");

  /** 剥离用宽松模式（expand-rag-benchmark 任务 3.2）：兼容滑窗把标记切半的残段，保持索引文本干净。 */
  private static final Pattern GOLD_MARKER_FRAGMENT = Pattern.compile("【GOLD:[^】]*】?");

  /** recall@k 截断与生产 topK 默认值（RetrievalDefaults.TOP_K）同口径；TASK-21 明令禁止顺手调门槛。 */
  private static final int TOP_K = 5;

  /** 闸门语料统一挂在这个知识库下；稀疏路的授权口径就是它（生产 SQL 经 uploaded_file JOIN 收敛）。 */
  private static final String GATE_KB_ID = "1";

  private static final String DIAGNOSTIC_RAW_SQL =
      "SELECT id, chunk_text FROM document_vector_chunk "
          + "WHERE MATCH(chunk_text) AGAINST(? IN NATURAL LANGUAGE MODE) "
          + "ORDER BY MATCH(chunk_text) AGAINST(? IN NATURAL LANGUAGE MODE) DESC LIMIT ?";

  /** 业务键回查（TASK-23 契约 2）：document_id + chunk_index 唯一圈定本次插入的一个切片行。 */
  private static final String CHUNK_ID_BY_BUSINESS_KEY_SQL =
      "SELECT id FROM document_vector_chunk WHERE document_id = ? AND chunk_index = ?";

  private static MySQLContainer<?> mysql;
  private static SqlSession sqlSession;
  private static SparseRecallService sparseRecallService;
  private static final Set<String> discoveredGoldIds = new LinkedHashSet<>();

  /**
   * 装配期捕获的黄金切片（原文自带完整 GOLD 标记者）：业务键与剥离后正文都先记住，chunkId 在批量插入后按业务键回查补齐。
   *
   * @param documentId 切片所属闸门文档 id（业务键之一）
   * @param chunkIndex 切片序号（业务键之一，DocumentService 全文档递增）
   * @param goldMarkers 该切片原文中完整出现的 GOLD 标记（可因滑窗重叠而出现多个）
   * @param indexedText 剥离标记后实际入库的正文（锚点判据作用于此，与生产可见文本逐字一致）
   * @param chunkId 回查补齐的库内主键（字符串口径与 {@code SparseRecallService} 返回的 chunkId 对齐）
   */
  private record SeededGoldenChunk(
      String documentId,
      int chunkIndex,
      List<String> goldMarkers,
      String indexedText,
      String chunkId) {}

  private static final List<SeededGoldenChunk> SEEDED_GOLDEN_CHUNKS = new ArrayList<>();

  /** 用例号 → 可接受黄金 chunkId 集合；装配期（{@code @BeforeAll}）算好，经 @MethodSource 的懒取 supplier 传入断言。 */
  private static final Map<String, Set<String>> ACCEPTED_GOLDEN_CHUNK_IDS = new LinkedHashMap<>();

  @BeforeAll
  static void startContainerAndIngestCorpus() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过 ngram 质量闸门 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_ngram_gate")
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
    migrate();
    ingestLexicalCorpus();
    captureGoldenChunkIds();
    // FTS 刷盘（expand-rag-benchmark 任务 3.2）：批量插入后 index cache 未刷盘时相关度全 0，
    // OPTIMIZE TABLE 强制把缓存写进磁盘索引，使相关度排序确定可断言
    optimizeFullTextIndex();
    sparseRecallService = productionSparseRecallService();
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

  /**
   * @MethodSource 数据结构（TASK-23 契约 2）：每条用例携带其可接受黄金 chunkId 集合。 集合在装配期才被回查填充，而 JUnit
   * 参数对象在发现期即创建，故这里传入的是对已算好映射的懒取 supplier，而非发现期求值的快照。
   */
  static Stream<Arguments> lexicalCases() {
    return LEXICAL_CASES.stream()
        .map(
            lexicalCase ->
                Arguments.of(
                    lexicalCase.caseId(),
                    lexicalCase.question(),
                    (Supplier<Set<String>>)
                        () ->
                            ACCEPTED_GOLDEN_CHUNK_IDS.getOrDefault(
                                lexicalCase.caseId(), Set.of())));
  }

  /** 语料装配哨兵：GOLD 标记全集必须都被真实分块链路发现，否则下面 14 条断言的对象不成立。 */
  @Test
  @DisplayName("语料装配：LEXICAL 黄金标记全集必须被发现")
  void lexicalGoldMarkersMustBeDiscoveredByChunkingPipeline() {
    List<String> missingGoldIds =
        EXPECTED_GOLD_IDS.stream().filter(id -> !discoveredGoldIds.contains(id)).toList();
    assertTrue(
        missingGoldIds.isEmpty(), "LEXICAL 黄金标记未全部被发现——语料装配有问题，闸门断言对象不成立，缺失：" + missingGoldIds);
  }

  /**
   * L-01～L-14 逐条独立用例：生产稀疏路 top-5 必须含该用例可接受黄金 chunkId 集合中的某个 chunkId。
   *
   * <p>TASK-23 契约 1：判据从「top-K 正文含黄金锚点」收紧为「top-K 候选 chunkId ∈ 可接受黄金集合」——锚点入库时被剥离、
   * 多块可共享同一锚点，文本判据会放过「命中错误但含锚点」的切片；共享锚点用例的集合按实测为多元素（契约 2），不改语料。失败消息保留服务层返回明细 + 裸 SQL 诊断对照（契约
   * 3，TASK-21 契约 1：裸 SQL 不得作为断言路径）。
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("lexicalCases")
  @DisplayName("LEXICAL 用例：黄金切片必须进生产稀疏路 top-K")
  void lexicalGoldenChunkMustRankWithinTopK(
      String caseId, String question, Supplier<Set<String>> acceptedGoldenChunkIds) {
    Set<String> goldenChunkIds = acceptedGoldenChunkIds.get();
    List<RetrievalCandidate> candidates =
        sparseRecallService.recall(question, List.of(Long.valueOf(GATE_KB_ID)), null, TOP_K);
    assertTrue(
        candidates.size() <= TOP_K,
        caseId + "：topK 截断失效——生产稀疏路返回 " + candidates.size() + " 条 > TOP_K=" + TOP_K);
    boolean hit =
        candidates.stream()
            .anyMatch(candidate -> goldenChunkIds.contains(candidate.hit().chunkId()));
    assertTrue(
        hit,
        () ->
            caseId
                + "（黄金 chunkId 可接受集="
                + goldenChunkIds
                + "，top"
                + TOP_K
                + "未含任一黄金切片）——ngram 召回质量不达标（决策点：切换方案B内存倒排）。"
                + "\n  查询："
                + question
                + "\n  生产稀疏路 SparseRecallService.recall 返回："
                + describeCandidates(candidates)
                + "\n  [诊断对照，非断言路径] 裸 MATCH...AGAINST top"
                + TOP_K
                + "："
                + rawSqlDiagnosticTopK(question));
  }

  /** 全量迁移建出 V3 表 + V22 索引（执行正确性由 FlywayMigrationIT 专断，这里只求schema可用）。 */
  private static void migrate() {
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
  }

  /**
   * LEXICAL 语料入库（与生产同构）：真实分块 → 剥 GOLD 标记 → 每个文档先落 {@code uploaded_file} 归属行（稀疏路授权 JOIN 的 执行点），再落
   * {@code document_vector_chunk} 切片行；发现到的黄金标记记入 {@link #discoveredGoldIds}， 带完整标记的切片登记进 {@link
   * #SEEDED_GOLDEN_CHUNKS} 供装配期按业务键回查 chunkId（TASK-23 契约 2）。
   */
  private static void ingestLexicalCorpus() throws Exception {
    DocumentService documentService = new DocumentService();
    try (Connection connection = openConnection();
        PreparedStatement file =
            connection.prepareStatement(
                "INSERT INTO uploaded_file (user_id, filename, original_filename, file_type, "
                    + "document_id, storage_key, status, knowledge_base) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
                    + "filename, category, page_no, row_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      int documentSeq = 1;
      for (String[] fixture : GATE_FIXTURES) {
        String documentId = "gate-" + fixture[0];
        file.setString(1, "user:99001");
        file.setString(2, fixture[1]);
        file.setString(3, fixture[1]);
        file.setString(4, fixture[1].substring(fixture[1].lastIndexOf('.') + 1));
        file.setString(5, documentId);
        file.setString(6, "storage/" + documentId);
        file.setString(7, "COMPLETED");
        file.setString(8, GATE_KB_ID);
        file.executeUpdate();

        List<DocumentChunk> chunks = parse(documentService, fixture[1]);
        for (DocumentChunk chunk : chunks) {
          List<String> markersInChunk = new ArrayList<>();
          Matcher marker = GOLD_MARKER.matcher(chunk.text());
          while (marker.find()) {
            markersInChunk.add(marker.group(1));
            discoveredGoldIds.add(marker.group(1));
          }
          String indexedText = GOLD_MARKER_FRAGMENT.matcher(chunk.text()).replaceAll("").strip();
          if (!markersInChunk.isEmpty()) {
            SEEDED_GOLDEN_CHUNKS.add(
                new SeededGoldenChunk(
                    documentId, chunk.chunkIndex(), List.copyOf(markersInChunk), indexedText, ""));
          }
          insert.setString(1, documentId);
          insert.setInt(2, chunk.chunkIndex());
          insert.setString(3, indexedText);
          insert.setString(4, "gate-hash-" + documentSeq + "-" + chunk.chunkIndex());
          insert.setString(5, fixture[1]);
          insert.setString(6, "benchmark");
          insert.setObject(7, chunk.pageNo());
          insert.setObject(8, chunk.rowIndex());
          insert.addBatch();
        }
        documentSeq++;
      }
      insert.executeBatch();
    }
  }

  /**
   * chunkId 确定性捕获（TASK-23 契约 2）：批量插入完成后，逐个黄金切片按业务键（document_id + chunk_index）回查主键—— <b>不依赖自增顺序、不依赖
   * batch 返回序</b>；回查必须恰得 1 行，否则装配失败（语料被外部污染或键不唯一，断言对象不成立）。 回查后按「正文含锚点且自带 GOLD 标记」圈定每个用例的可接受黄金
   * chunkId 集合，共享锚点即多元素集。
   */
  private static void captureGoldenChunkIds() throws Exception {
    List<SeededGoldenChunk> resolved = new ArrayList<>();
    try (Connection connection = openConnection();
        PreparedStatement select = connection.prepareStatement(CHUNK_ID_BY_BUSINESS_KEY_SQL)) {
      for (SeededGoldenChunk seeded : SEEDED_GOLDEN_CHUNKS) {
        String chunkId = resolveChunkIdByBusinessKey(select, seeded);
        resolved.add(
            new SeededGoldenChunk(
                seeded.documentId(),
                seeded.chunkIndex(),
                seeded.goldMarkers(),
                seeded.indexedText(),
                chunkId));
      }
    }
    for (LexicalGateCase lexicalCase : LEXICAL_CASES) {
      Set<String> accepted = new LinkedHashSet<>();
      for (SeededGoldenChunk golden : resolved) {
        if (golden.indexedText().contains(lexicalCase.goldenAnchor())) {
          accepted.add(golden.chunkId());
        }
      }
      ACCEPTED_GOLDEN_CHUNK_IDS.put(lexicalCase.caseId(), Set.copyOf(accepted));
    }
  }

  /** 业务键回查恰得 1 行是硬前提：0 行 = 切片没进库，>1 行 = 键不唯一，两种情况断言对象都不成立，直接装配失败。 */
  private static String resolveChunkIdByBusinessKey(
      PreparedStatement select, SeededGoldenChunk seeded) throws SQLException {
    select.setString(1, seeded.documentId());
    select.setInt(2, seeded.chunkIndex());
    List<String> ids = new ArrayList<>();
    try (ResultSet rs = select.executeQuery()) {
      while (rs.next()) {
        ids.add(String.valueOf(rs.getLong(1)));
      }
    }
    if (ids.size() != 1) {
      throw new IllegalStateException(
          "黄金切片业务键回查失效（禁自增顺序假设的兜底）：document_id="
              + seeded.documentId()
              + ", chunk_index="
              + seeded.chunkIndex()
              + ", markers="
              + seeded.goldMarkers()
              + " 回查得 "
              + ids.size()
              + " 行，期望恰 1 行");
    }
    return ids.get(0);
  }

  private static List<DocumentChunk> parse(DocumentService documentService, String filename)
      throws Exception {
    String resourcePath = "/rag-quality/fixtures/" + filename;
    try (InputStream in = ChunkNgramRecallGateIT.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("闸门语料缺失: " + resourcePath);
      }
      return documentService.process(
          new ByteArrayInputStream(in.readAllBytes()), filename, "benchmark");
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

  /**
   * 生产稀疏服务装配（与 {@link SparseRecallServiceIT} 同款）：真实 {@link DocumentVectorChunkMapper} 代理 + 真实
   * {@link SparseRecallService}，避免为门禁拉起整个 Spring 上下文，同时保证跑的是线上那段 SQL 与服务层语义。
   */
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
        new Environment("ngram-gate", new JdbcTransactionFactory(), dataSource));
    configuration.addMapper(DocumentVectorChunkMapper.class);
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    sqlSession = factory.openSession(true);
    return new SparseRecallService(sqlSession.getMapper(DocumentVectorChunkMapper.class));
  }

  /** 服务层返回明细（rank / chunkId / 文件 / 相关度 / 正文摘要），用于失败定位。 */
  private static String describeCandidates(List<RetrievalCandidate> candidates) {
    if (candidates.isEmpty()) {
      return " 0 条";
    }
    StringBuilder detail = new StringBuilder();
    int rank = 1;
    for (RetrievalCandidate candidate : candidates) {
      detail
          .append("\n    #")
          .append(rank++)
          .append(" chunkId=")
          .append(candidate.hit().chunkId())
          .append(" doc=")
          .append(candidate.hit().documentId())
          .append(" file=")
          .append(candidate.hit().metadata().get("filename"))
          .append(" score=")
          .append(candidate.hit().score())
          .append(" text=")
          .append(abbreviate(candidate.hit().text(), 60));
    }
    return " " + candidates.size() + " 条" + detail;
  }

  /**
   * 仅诊断对照（TASK-21 契约 1：绝不参与断言）：断言失败时把同一条整句查询的裸 {@code MATCH...AGAINST} top-K 打出来， 用于判定缺口在索引层（裸 SQL
   * 也没有）还是在服务层（裸 SQL 有、服务层没有）。
   */
  private static String rawSqlDiagnosticTopK(String question) {
    StringBuilder detail = new StringBuilder();
    try (Connection connection = openConnection();
        PreparedStatement statement = connection.prepareStatement(DIAGNOSTIC_RAW_SQL)) {
      statement.setString(1, question);
      statement.setString(2, question);
      statement.setInt(3, TOP_K);
      int rank = 1;
      int rows = 0;
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          rows++;
          detail
              .append("\n    #")
              .append(rank++)
              .append(" id=")
              .append(rs.getLong(1))
              .append(" text=")
              .append(abbreviate(rs.getString(2), 60));
        }
      }
      return rows + " 条" + detail;
    } catch (Exception exception) {
      return " 诊断查询自身失败：" + exception.getMessage();
    }
  }

  private static String abbreviate(String text, int maxLength) {
    if (text == null) {
      return "<null>";
    }
    String singleLine = text.replaceAll("\\s+", " ").strip();
    return singleLine.length() <= maxLength
        ? singleLine
        : singleLine.substring(0, maxLength) + "...";
  }
}
