package com.slz.crm.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * ngram 召回质量闸门（complete-hybrid-retrieval-and-rerank 任务 1.2；expand-rag-benchmark 任务 3.2 扩 L-14）。
 *
 * <p>目的：在真 MySQL（与生产同版本 8.0.36）上验证 V22 的 ngram 全文索引能把 LEXICAL 型查询（型号/编号/专有名词）的黄金切片召回进 top-5——这是「选项
 * A （FULLTEXT ngram）能否作为稀疏召回路载体」的实测决策点：不达标则切换方案 B （内存倒排索引），结论写回 tasks.md 验证记录。
 *
 * <p>方法：把 rag-quality/fixtures 的 LEXICAL 相关语料（型号目录/代号登记册 + 扩容新增的 客户 SOP/价格政策/区域政策/SLA/维保周期表，其余
 * fixtures 作干扰项）经真实 {@link DocumentService} 分块后灌入 document_vector_chunk（剥离 GOLD 标记，与基准数据准备
 * 同口径），用生产同语义的 {@code MATCH...AGAINST IN NATURAL LANGUAGE MODE} 整句查询 （不做关键词抽取），断言每个 LEXICAL
 * 用例（L-01～L-14）的黄金切片都进入 top-5。
 *
 * <p>FTS 刷盘（expand-rag-benchmark 任务 3.2 实测）：InnoDB FULLTEXT 索引对批量插入的 更新先进内存 index
 * cache，刷盘完成前查询过滤正确但相关度全为 0（ORDER BY 退化）。 闸门在入库后执行 {@code OPTIMIZE TABLE} 强制把缓存刷进磁盘索引，保证相关度排序确定；
 * 生产路径由自然的时间间隔（入库与查询不同秒）覆盖，本实测结论记入 tasks.md 验证记录。
 *
 * <p>Docker 门禁：无 Docker 时 assumeTrue 跳过（本地开发机）；CI 真跑。
 */
class ChunkNgramRecallGateIT {

  /** LEXICAL 用例（与 RagBenchmarkSuite L-01～L-14 对齐）：问题 → 黄金切片正文必含的词法锚点。 */
  private static final List<String[]> LEXICAL_CASES =
      List.of(
          new String[] {"L-01", "XR-500 设备的售后对接人是谁", "XR-500"},
          new String[] {"L-02", "ZB-220 的备件放在哪个库位", "ZB-220"},
          new String[] {"L-03", "合同编号 HT-2024-0889 签的是哪个项目", "HT-2024-0889"},
          new String[] {"L-04", "凤凰计划由哪个交付组实施", "凤凰计划"},
          new String[] {"L-05", "KQ-9000 的固件应该升级到哪个版本", "KQ-9000"},
          new String[] {"L-06", "内部代号 BK-2024 对应哪个项目", "BK-2024"},
          // expand-rag-benchmark 任务 3.2：新增 L-07～L-14（编号/人名/版本号）
          new String[] {"L-07", "政策 RSP-2024-04 保护的是哪类业务", "RSP-2024-04"},
          new String[] {"L-08", "客户编号 CUS-2026-0088 的客户经理是谁", "CUS-2026-0088"},
          new String[] {"L-09", "工单 WO-2026-0521 是哪台设备的维保工单", "WO-2026-0521"},
          new String[] {"L-10", "价格政策的负责人是谁", "孙丽华"},
          new String[] {"L-11", "SLA 责任工程师是谁", "何建军"},
          new String[] {"L-12", "华东区的区域经理是谁", "吴国强"},
          new String[] {"L-13", "客户管理 SOP 当前是哪个版本", "v4.2"},
          new String[] {"L-14", "价格政策的版本号是多少", "v2.7"});

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

  /** recall@k 截断与生产 topK 默认值同口径。 */
  private static final int TOP_K = 5;

  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void startContainer() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过 ngram 质量闸门 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_ngram_gate")
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
  }

  @AfterAll
  static void stopContainer() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  @Test
  void lexicalGoldenChunksMustRankWithinTopK() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
      migrate(connection);
      Set<String> discoveredGoldIds = ingestLexicalCorpus(connection);
      // FTS 刷盘（expand-rag-benchmark 任务 3.2）：批量插入后 index cache 未刷盘时相关度全 0，
      // OPTIMIZE TABLE 强制把缓存写进磁盘索引，使相关度排序确定可断言
      try (PreparedStatement optimize =
          connection.prepareStatement("OPTIMIZE TABLE document_vector_chunk")) {
        optimize.execute();
      }
      // 干扰项 fixtures（sales-flow/payment-plan）与基准套件共用、自带各自 GOLD 标记，
      // 发现集必然超集；闸门只要求 LEXICAL 黄金标记全部被发现（语料装配哨兵）
      List<String> missingGoldIds =
          EXPECTED_GOLD_IDS.stream().filter(id -> !discoveredGoldIds.contains(id)).toList();
      assertTrue(
          missingGoldIds.isEmpty(), "LEXICAL 黄金标记未全部被发现——语料装配有问题，闸门断言对象不成立，缺失：" + missingGoldIds);

      List<String> misses = new ArrayList<>();
      for (String[] lexicalCase : LEXICAL_CASES) {
        String caseId = lexicalCase[0];
        String question = lexicalCase[1];
        String goldenToken = lexicalCase[2];
        List<String> topTexts = fulltextTopK(connection, question, TOP_K);
        boolean hit = topTexts.stream().anyMatch(text -> text.contains(goldenToken));
        if (!hit) {
          misses.add(caseId + "（黄金锚点=" + goldenToken + "，top" + TOP_K + "未含黄金切片）");
        }
      }
      assertTrue(
          misses.isEmpty(),
          "LEXICAL 黄金切片未全部进入 top" + TOP_K + "——ngram 召回质量不达标（决策点：切换方案B内存倒排）：" + misses);
    }
  }

  /** 全量迁移建出 V3 表 + V22 索引（执行正确性由 FlywayMigrationIT 专断，这里只求schema可用）。 */
  private void migrate(Connection connection) {
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
  }

  /** LEXICAL 语料入库：真实分块 → 剥 GOLD 标记 → 插入 document_vector_chunk；返回发现的黄金标记集。 */
  private Set<String> ingestLexicalCorpus(Connection connection) throws Exception {
    DocumentService documentService = new DocumentService();
    Set<String> discoveredGoldIds = new LinkedHashSet<>();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
                + "filename, category, page_no, row_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      int documentSeq = 1;
      for (String[] fixture : GATE_FIXTURES) {
        List<DocumentChunk> chunks = parse(documentService, fixture[1]);
        for (DocumentChunk chunk : chunks) {
          Matcher marker = GOLD_MARKER.matcher(chunk.text());
          while (marker.find()) {
            discoveredGoldIds.add(marker.group(1));
          }
          String indexedText = GOLD_MARKER_FRAGMENT.matcher(chunk.text()).replaceAll("").strip();
          insert.setString(1, "gate-" + fixture[0]);
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
    return discoveredGoldIds;
  }

  private List<DocumentChunk> parse(DocumentService documentService, String filename)
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

  /** 生产同语义整句查询：MATCH...AGAINST 自然语言模式，按相关度取 top-K 正文。 */
  private List<String> fulltextTopK(Connection connection, String question, int topK)
      throws Exception {
    String sql =
        "SELECT chunk_text FROM document_vector_chunk "
            + "WHERE MATCH(chunk_text) AGAINST(? IN NATURAL LANGUAGE MODE) "
            + "ORDER BY MATCH(chunk_text) AGAINST(? IN NATURAL LANGUAGE MODE) DESC LIMIT ?";
    List<String> texts = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, question);
      statement.setString(2, question);
      statement.setInt(3, topK);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          texts.add(rs.getString(1));
        }
      }
    }
    assertTrue(texts.size() <= topK, "topK 截断失效");
    return texts;
  }
}
