package com.slz.crm.integration.knowledge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * per-KB 检索策略存储层集成测试（add-per-kb-retrieval-strategy-override 任务 4.3）。
 *
 * <p><b>Docker 门禁</b>：本 IT 与 {@code SchemaDriftAuditIT} 同口径——无 Docker 时 {@code assumeTrue} 优雅跳过
 * （本地开发机记 skipped）；CI（ubuntu-latest 自带 Docker）真跑。数据源用独立 MySQL 容器 + 全链 Flyway V1..V29，验证真实 MySQL 上的表
 * DDL、{@code (kb_id, strategy_key)} 活覆盖唯一约束与软删语义。
 *
 * <p>端点级 900 权限/403 由 {@link com.slz.crm.integration.permission.PermissionCoverageAuditIT}（SECURED
 * 档） 与既有拦截器门禁覆盖；本 IT 专注存储层真实行为（DDL + 唯一约束 + 软删复活/回滚落库）。
 */
class KbRetrievalStrategyIT {

  private static final String DB_NAME = "crm_kb_strategy_it";
  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void startContainer() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过 per-KB 策略存储层 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName(DB_NAME)
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .validateOnMigrate(true)
        .load()
        .migrate();
  }

  @AfterAll
  static void stopContainer() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  private Connection conn() throws SQLException {
    return DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
  }

  @Test
  void v29CreatesBothTables() throws SQLException {
    try (Connection c = conn();
        ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
      boolean strategy = false;
      boolean history = false;
      while (rs.next()) {
        String name = rs.getString("TABLE_NAME");
        if ("kb_retrieval_strategy".equalsIgnoreCase(name)) strategy = true;
        if ("kb_retrieval_strategy_history".equalsIgnoreCase(name)) history = true;
      }
      assertTrue(strategy, "kb_retrieval_strategy 表必须由 V29 建立");
      assertTrue(history, "kb_retrieval_strategy_history 表必须由 V29 建立");
    }
  }

  @Test
  void liveCoverageUniqueConstraint() throws SQLException {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO kb_retrieval_strategy"
                    + " (kb_id, strategy_key, config_value, version, is_deleted)"
                    + " VALUES (?, ?, ?, ?, ?)")) {
      ps.setLong(1, 1L);
      ps.setString(2, "rag.retrieval.topK");
      ps.setString(3, "8");
      ps.setInt(4, 1);
      ps.setInt(5, 0);
      ps.executeUpdate();
      ps.setLong(1, 1L);
      ps.setString(2, "rag.retrieval.topK");
      ps.setString(3, "9");
      ps.setInt(4, 2);
      ps.setInt(5, 0);
      // 同 (kb_id, strategy_key) 二次插入（即使软删=0）必须违反 uk_kb_strategy 唯一约束
      assertThrows(SQLException.class, ps::executeUpdate, "活覆盖 (kb_id, strategy_key) 必须唯一");
    }
  }

  @Test
  void softDeleteRowSharesSameKeyAndReviveUpdates() throws SQLException {
    try (Connection c = conn()) {
      // 软删一行后，同 key 仍不得新建（复活语义：软删行保留，唯一键含软删行）
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE kb_retrieval_strategy SET is_deleted=1 WHERE kb_id=1 AND strategy_key='rag.retrieval.topK'")) {
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO kb_retrieval_strategy"
                  + " (kb_id, strategy_key, config_value, version, is_deleted)"
                  + " VALUES (1, 'rag.retrieval.topK', '9', 3, 0)")) {
        assertThrows(SQLException.class, ps::executeUpdate, "软删行存在时同 key 不得新建（应复活）");
      }
    }
  }

  @Test
  void historyTableTracksRollbackTarget() throws SQLException {
    try (Connection c = conn()) {
      try (Statement st = c.createStatement()) {
        st.executeUpdate(
            "INSERT INTO kb_retrieval_strategy_history"
                + " (strategy_id, kb_id, strategy_key, version, operation_type, old_value, new_value)"
                + " VALUES (1, 1, 'rag.retrieval.topK', 2, 'ROLLBACK', '9', '8')");
      }
      ResultSet rs =
          c.createStatement()
              .executeQuery(
                  "SELECT new_value FROM kb_retrieval_strategy_history"
                      + " WHERE kb_id=1 AND strategy_key='rag.retrieval.topK' AND version=2");
      assertTrue(rs.next(), "回滚目标历史行必须可查");
      assertTrue("8".equals(rs.getString(1)), "回滚目标 = 该版本行 new_value");
    }
  }
}
