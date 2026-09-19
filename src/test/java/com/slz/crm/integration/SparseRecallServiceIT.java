package com.slz.crm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * 稀疏召回路 DB 级 IT（complete-hybrid-retrieval-and-rerank 任务 1.3/1.5）： 在真 MySQL 上验证 {@code
 * DocumentVectorChunkMapper#fulltextSearch}（MATCH...AGAINST + 授权 JOIN）， 以及 {@link
 * SparseRecallService} 的授权/类目/limit 语义。
 *
 * <p>与 FlywayMigrationIT 同款「无 Spring、Flyway 建库」模式：auto-table 建不出 V22 全文索引， 稀疏路 SQL 必须跑在 Flyway 迁移后的
 * schema 上。Docker 门禁同前（无 Docker 优雅跳过）。
 */
class SparseRecallServiceIT {

  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void startContainer() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过稀疏召回 DB 级 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_sparse_it")
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
    try (Connection connection =
        DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
      Flyway.configure()
          .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
          .locations("classpath:db/migration")
          .baselineVersion("0")
          .load()
          .migrate();
      seed(connection);
    }
  }

  @AfterAll
  static void stopContainer() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  /** 语料：KB1 的 XR-500 售后/备件两片 + KB2 的 XR-500 兼容清单一片（词法命中但未授权）。 */
  private static void seed(Connection connection) throws Exception {
    try (PreparedStatement file =
            connection.prepareStatement(
                "INSERT INTO uploaded_file (user_id, filename, original_filename, file_type, document_id, "
                    + "storage_key, status, knowledge_base) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement chunk =
            connection.prepareStatement(
                "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
                    + "filename, category) VALUES (?, ?, ?, ?, ?, ?)")) {
      seedDocument(file, chunk, "doc-a", "1", "aftermarket", "XR-500 设备售后对接人是王建国，保修截止 2027-05-31。");
      seedDocument(file, chunk, "doc-c", "1", "contract", "XR-500 备件统一放在 C-07 库位，紧急走华北中心库。");
      seedDocument(file, chunk, "doc-b", "2", "aftermarket", "XR-500 兼容机型清单：ZB-220 与 KQ-9000。");
    }
  }

  private static void seedDocument(
      PreparedStatement file,
      PreparedStatement chunk,
      String documentId,
      String kbId,
      String category,
      String text)
      throws Exception {
    file.setString(1, "user:99001");
    file.setString(2, documentId + ".md");
    file.setString(3, documentId + ".md");
    file.setString(4, "md");
    file.setString(5, documentId);
    file.setString(6, "storage/" + documentId);
    file.setString(7, "COMPLETED");
    file.setString(8, kbId);
    file.executeUpdate();

    chunk.setString(1, documentId);
    chunk.setInt(2, 0);
    chunk.setString(3, text);
    chunk.setString(4, "hash-" + documentId);
    chunk.setString(5, documentId + ".md");
    chunk.setString(6, category);
    chunk.executeUpdate();
  }

  /** 任务 1.3：词法命中 + 授权 JOIN（authorized=[KB1] → KB2 的词法命中被排除）。 */
  @Test
  void fulltextSearchShouldEnforceAuthorizedKnowledgeBaseJoin() {
    SparseRecallService service = service();
    List<RetrievalCandidate> hits = service.recall("XR-500", List.of(1L), null, 10);

    assertEquals(2, hits.size(), "授权 KB1 应命中 doc-a/doc-c 两片");
    assertTrue(
        hits.stream()
            .allMatch(
                hit ->
                    hit.hit().documentId().startsWith("doc-a")
                        || hit.hit().documentId().startsWith("doc-c")),
        "只允许 KB1 的切片");
    List<RetrievalCandidate> kb2View = service.recall("XR-500", List.of(2L), null, 10);
    assertEquals(1, kb2View.size());
    assertEquals("doc-b", kb2View.getFirst().hit().documentId());
  }

  /** 任务 1.5：未授权库的切片即使词法精确命中也绝不出现在结果里（伪造 metadata 无执行面）。 */
  @Test
  void sparseRecallMustNotLeakUnauthorizedKnowledgeBaseChunks() {
    SparseRecallService service = service();
    for (RetrievalCandidate candidate : service.recall("XR-500 兼容机型", List.of(1L), null, 10)) {
      assertTrue(!"2".equals(candidate.hit().metadata().get("knowledgeBaseId")), "KB2 切片不得通过稀疏路泄露");
    }
    List<RetrievalCandidate> crossKb = service.recall("XR-500", List.of(1L, 2L), null, 10);
    assertEquals(3, crossKb.size(), "两库都授权时三片全部可见");
  }

  /** 类目过滤（任务 2.1 稀疏路侧）：类目窄化生效，空类目不过滤。 */
  @Test
  void categoryFilterShouldNarrowSparseResults() {
    SparseRecallService service = service();
    List<RetrievalCandidate> filtered = service.recall("XR-500", List.of(1L), "contract", 10);
    assertEquals(1, filtered.size());
    assertEquals("doc-c", filtered.getFirst().hit().documentId());

    List<RetrievalCandidate> unfiltered = service.recall("XR-500", List.of(1L), "", 10);
    assertEquals(2, unfiltered.size(), "空类目 = 不过滤");
  }

  /** 候选上限与空入参守卫。 */
  @Test
  void limitAndGuardsShouldBehave() {
    SparseRecallService service = service();
    assertEquals(1, service.recall("XR-500", List.of(1L, 2L), null, 1).size());
    assertTrue(service.recall("", List.of(1L), null, 10).isEmpty(), "空 query 直接返回空");
    assertTrue(service.recall("XR-500", List.of(), null, 10).isEmpty(), "无授权直接返回空");
    assertTrue(service.recall("XR-500", List.of(1L), null, 0).isEmpty(), "非法 limit 直接返回空");
  }

  /** 独立 MyBatis 装配：与 Spring 同款 mapper 代理，避免为 IT 拉起全上下文。 */
  private SparseRecallService service() {
    PooledDataSource dataSource =
        new PooledDataSource(
            mysql.getDriverClassName(),
            mysql.getJdbcUrl(),
            mysql.getUsername(),
            mysql.getPassword());
    Configuration configuration = new Configuration();
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.setEnvironment(
        new Environment("sparse-it", new JdbcTransactionFactory(), dataSource));
    configuration.addMapper(DocumentVectorChunkMapper.class);
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    SqlSession session = factory.openSession(true);
    return new SparseRecallService(session.getMapper(DocumentVectorChunkMapper.class));
  }
}
