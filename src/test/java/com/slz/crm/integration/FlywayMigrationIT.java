package com.slz.crm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * Flyway 全量迁移 IT（task18：真 MySQL 验证库结构唯一真相源）。
 *
 * <p>动机：单元测试与 H2 上下文冒烟都用 auto-table/ddl-auto，<b>从不执行 Flyway 脚本</b>； 而 V1 含 MySQL 专有 DDL（{@code
 * generated always as(if(...))stored}）H2 无法解析。
 * 因此十六张迁移脚本（V1/V3/V4/V4_1/V5/V6/V21/V22/V23/V24/V25/V26/V27/V28/V29/V30）能否在真 MySQL 上按序无撞号跑通，只能靠本
 * IT。方法顺序由 @Order 强制：migrate 用例先行，其余断言用例依赖其建库结果（add-dynamic-config-key-tier-acl 任务 1.4）。
 *
 * <p>Docker 门禁：无 Docker 时 {@code assumeTrue} 优雅跳过（本地开发机）；CI（ubuntu-latest 自带 Docker）真跑。 与 {@link
 * AbstractMySqlIT} 共用 mysql:8.0.36 镜像口径。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationIT {

  /** 迁移脚本全集（版本 → 归属 lane），任何增删都要在此登记，防漏跑/撞号。 */
  private static final Set<String> EXPECTED_VERSIONS =
      new TreeSet<>(
          List.of(
              "1", "3", "4", "4.1", "5", "6", "21", "22", "23", "24", "25", "26", "27", "28", "29",
              "30", "31"));

  /** 跨 lane 关键表抽样：确认各号段 DDL 真的建出了表（V1/V3/V4/V5/V6）。 */
  private static final List<String> SPOT_CHECK_TABLES =
      List.of(
          "sys_dept", // V1 基线（A 的 V21 会 ALTER 它加 leader_id）
          "knowledge_base", // V3 知识库（B）
          "ai_conversation_memory", // V4 助手记忆（C）
          "ai_chat_image", // V4_1 聊天图片（C）
          "dynamic_config_item" // V6 动态配置（E）
          );

  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void startContainer() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过 Flyway 真库迁移 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_flyway_it")
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
  @Order(1)
  void allMigrationsApplyInOrderOnRealMySql() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
            .locations("classpath:db/migration")
            .baselineVersion("0")
            .validateOnMigrate(true)
            .load();

    // 迁移全量脚本；任一脚本语法/顺序/撞号问题都会在此抛出
    int executed = flyway.migrate().migrationsExecuted;
    assertEquals(EXPECTED_VERSIONS.size(), executed, "执行的迁移数与登记全集不符——是否漏跑或多了未登记脚本");

    // 已应用版本集合必须与登记全集完全一致
    Set<String> appliedVersions = new TreeSet<>();
    for (MigrationInfo info : flyway.info().applied()) {
      if (info.getState() == MigrationState.SUCCESS) {
        appliedVersions.add(info.getVersion().getVersion());
      }
    }
    assertEquals(EXPECTED_VERSIONS, appliedVersions, "已应用迁移版本集与预期不符");

    // validate：脚本未被篡改、无 pending/failed
    flyway.validate();

    // 抽样确认关键表真的建出（跨 V1/V3/V4/V4_1/V6）
    List<String> missing = missingTables();
    assertTrue(missing.isEmpty(), "以下关键表迁移后仍缺失：" + missing);
  }

  /**
   * V30 断言（add-dynamic-config-key-tier-acl 任务 1.4）： 608 权限种子落库——业务角色全量获权、 roleId 0/1/2
   * 特殊角色不授（@Order(2) 在 migrate 用例之后执行，同 V22/V23 断言模式）。
   */
  @Test
  @Order(2)
  void dynamicConfigPermissionSeedGrantsBusinessRolesOnly() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      ResultSet perm =
          statement.executeQuery(
              "SELECT COUNT(*) FROM permissions WHERE id = 608"
                  + " AND permissions_name = 'PLATFORM_DYNAMIC_CONFIG_MANAGE'");
      assertTrue(perm.next() && perm.getLong(1) == 1, "V30 应种入 608 权限行恰一行");
      ResultSet granted =
          statement.executeQuery(
              "SELECT COUNT(DISTINCT rp.role_id) FROM role_permissions rp JOIN sys_role r"
                  + " ON rp.role_id = r.id WHERE rp.permissions_id = 608"
                  + " AND r.id NOT IN (0, 1, 2) AND r.is_deleted = b'0'");
      assertTrue(granted.next(), "已授权角色计数行缺失");
      long grantedRoles = granted.getLong(1); // 先取出：同一 Statement 再执行查询会自动关闭前一 ResultSet
      ResultSet totalBusiness =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sys_role WHERE id NOT IN (0, 1, 2) AND is_deleted = b'0'");
      assertTrue(totalBusiness.next(), "业务角色总数行缺失");
      assertEquals(totalBusiness.getLong(1), grantedRoles, "608 应授权全部业务角色（同轮 CROSS JOIN）");
      ResultSet special =
          statement.executeQuery(
              "SELECT COUNT(*) FROM role_permissions WHERE permissions_id = 608 AND role_id IN (0, 1, 2)");
      assertTrue(special.next() && special.getLong(1) == 0, "608 不应授给 roleId 0/1/2 特殊角色");
    }
  }

  /**
   * V22 断言（complete-hybrid-retrieval-and-rerank 任务 1.1）： document_vector_chunk 上存在 ngram parser 的
   * chunk_text 全文索引——稀疏召回路的载体。 parser 名不在 information_schema.statistics 暴露，用 SHOW CREATE TABLE 验证
   * DDL 原文。
   */
  @Test
  @Order(3)
  void chunkFulltextIndexExistsWithNgramParser() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE document_vector_chunk")) {
      assertTrue(rs.next(), "document_vector_chunk 表应存在");
      String ddl = rs.getString(2);
      assertTrue(
          ddl.contains("FULLTEXT KEY `ft_chunk_text`"), "V22 应建出 ft_chunk_text 全文索引，实际 DDL：" + ddl);
      // SHOW CREATE TABLE 会把 parser 名渲染成带反引号的 `ngram`（位于 /*!50100 条件注释内），归一化后再匹配
      String normalizedDdl = ddl.toLowerCase(java.util.Locale.ROOT).replace("`", "");
      assertTrue(
          normalizedDdl.contains("with parser ngram"),
          "全文索引必须使用 ngram parser（中文 bigram），实际 DDL：" + ddl);
    }
  }

  /**
   * V23 断言（upgrade-semantic-chunking-and-index 任务 3.1）： document_vector_chunk 增出
   * parent_chunk_id（可空自引用父块列）与 chunk_role（默认 CHILD）， 双粒度索引的库结构载体——缺列会让父块生成与展开在运行期直接 SQL 报错。
   */
  @Test
  @Order(4)
  void chunkParentLinkColumnsExist() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE document_vector_chunk")) {
      assertTrue(rs.next(), "document_vector_chunk 表应存在");
      String ddl = rs.getString(2);
      assertTrue(
          ddl.contains("`parent_chunk_id` bigint"), "V23 应增出 parent_chunk_id 列，实际 DDL：" + ddl);
      assertTrue(
          ddl.contains("`chunk_role` varchar(16) NOT NULL DEFAULT 'CHILD'"),
          "V23 应增出 chunk_role 列且存量行默认 CHILD，实际 DDL：" + ddl);
    }
  }

  /**
   * V31 断言（add-cost-key-approval-workflow 任务 1.4）： cost_key_change_request 表建出且包含
   * idx_status_created 与 idx_config_key 索引。
   */
  @Test
  @Order(5)
  void costKeyChangeRequestTableAndIndexesExist() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE cost_key_change_request")) {
      assertTrue(rs.next(), "cost_key_change_request 表应存在");
      String ddl = rs.getString(2);
      assertTrue(
          ddl.contains("KEY `idx_status_created` (`status`,`created_at`)")
              || ddl.contains("KEY `idx_status_created` (`status`, `created_at`)"),
          "V31 应建出 idx_status_created 索引，实际 DDL：" + ddl);
      assertTrue(
          ddl.contains("KEY `idx_config_key` (`config_key`)"),
          "V31 应建出 idx_config_key 索引，实际 DDL：" + ddl);
    }
  }

  /** 查 information_schema 找出未建出的抽样表。 */
  private List<String> missingTables() throws Exception {
    List<String> missing = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      for (String table : SPOT_CHECK_TABLES) {
        String sql =
            "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'crm_flyway_it' AND table_name = '"
                + table
                + "'";
        try (ResultSet rs = statement.executeQuery(sql)) {
          rs.next();
          if (rs.getInt(1) == 0) {
            missing.add(table);
          }
        }
      }
    }
    return missing;
  }
}
