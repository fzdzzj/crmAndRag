package com.slz.crm.integration;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ngram 召回质量闸门（complete-hybrid-retrieval-and-rerank 任务 1.2）。
 *
 * <p>目的：在真 MySQL（与生产同版本 8.0.36）上验证 V22 的 ngram 全文索引能把
 * LEXICAL 型查询（型号/编号/专有名词）的黄金切片召回进 top-5——这是「选项 A
 * （FULLTEXT ngram）能否作为稀疏召回路载体」的实测决策点：不达标则切换方案 B
 * （内存倒排索引），结论写回 tasks.md 验证记录。</p>
 *
 * <p>方法：把 rag-quality/fixtures 的 LEXICAL 相关语料（产品型号目录 + 合同代号登记册，
 * 其余 fixtures 作干扰项）经真实 {@link DocumentService} 分块后灌入 document_vector_chunk
 * （剥离 GOLD 标记，与基准数据准备同口径），用生产同语义的
 * {@code MATCH...AGAINST IN NATURAL LANGUAGE MODE} 整句查询（不做关键词抽取），
 * 断言每个 LEXICAL 用例的黄金切片都进入 top-5。</p>
 *
 * <p>Docker 门禁：无 Docker 时 assumeTrue 跳过（本地开发机）；CI 真跑。</p>
 */
class ChunkNgramRecallGateIT {

    /** LEXICAL 用例（与 RagBenchmarkSuite L-01～L-06 对齐）：问题 → 黄金切片正文必含的词法锚点。 */
    private static final List<String[]> LEXICAL_CASES = List.of(
            new String[]{"L-01", "XR-500 设备的售后对接人是谁", "XR-500"},
            new String[]{"L-02", "ZB-220 的备件放在哪个库位", "ZB-220"},
            new String[]{"L-03", "合同编号 HT-2024-0889 签的是哪个项目", "HT-2024-0889"},
            new String[]{"L-04", "凤凰计划由哪个交付组实施", "凤凰计划"},
            new String[]{"L-05", "KQ-9000 的固件应该升级到哪个版本", "KQ-9000"},
            new String[]{"L-06", "内部代号 BK-2024 对应哪个项目", "BK-2024"});

    /** LEXICAL 黄金标记全集：入库后必须都能被发现，否则语料装配有问题，闸门断言对象不成立。 */
    private static final Set<String> EXPECTED_GOLD_IDS = Set.of(
            "model-xr500", "model-zb220", "model-kq9000", "code-ht0889", "code-fenghuang", "code-bluewhale");

    /** LEXICAL 语料：型号目录 + 代号登记册；其余 fixtures 作干扰项一并入库，检验排名而非单纯命中。 */
    private static final List<String[]> GATE_FIXTURES = List.of(
            new String[]{"models", "product-model-catalog.md"},
            new String[]{"codes", "contract-code-registry.txt"},
            new String[]{"sales-flow", "sales-contract-flow.md"},
            new String[]{"payment-plan", "payment-plan.md"});

    private static final Pattern GOLD_MARKER = Pattern.compile("【GOLD:([A-Za-z0-9_\\-\\u4e00-\\u9fff]+)】");

    /** recall@k 截断与生产 topK 默认值同口径。 */
    private static final int TOP_K = 5;

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainer() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker 不可用：跳过 ngram 质量闸门 IT（在 CI 环境执行）");
        mysql = new MySQLContainer<>("mysql:8.0.36")
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
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            migrate(connection);
            Set<String> discoveredGoldIds = ingestLexicalCorpus(connection);
            assertEquals(EXPECTED_GOLD_IDS, discoveredGoldIds,
                    "黄金标记发现集与预期不符——语料装配有问题，闸门断言对象不成立");

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
            assertTrue(misses.isEmpty(),
                    "LEXICAL 黄金切片未全部进入 top" + TOP_K + "——ngram 召回质量不达标（决策点：切换方案B内存倒排）："
                            + misses);
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
        try (PreparedStatement insert = connection.prepareStatement(
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
                    String indexedText = GOLD_MARKER.matcher(chunk.text()).replaceAll("").strip();
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

    private List<DocumentChunk> parse(DocumentService documentService, String filename) throws Exception {
        String resourcePath = "/rag-quality/fixtures/" + filename;
        try (InputStream in = ChunkNgramRecallGateIT.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("闸门语料缺失: " + resourcePath);
            }
            return documentService.process(new ByteArrayInputStream(in.readAllBytes()), filename, "benchmark");
        }
    }

    /** 生产同语义整句查询：MATCH...AGAINST 自然语言模式，按相关度取 top-K 正文。 */
    private List<String> fulltextTopK(Connection connection, String question, int topK) throws Exception {
        String sql = "SELECT chunk_text FROM document_vector_chunk "
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
