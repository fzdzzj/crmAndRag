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
 * generated always as(if(...))stored}）H2 无法解析。 因此全部迁移脚本（V1/V3/V4/V4_1/V5/V6/V21-V35）能否在真 MySQL
 * 上按序无撞号跑通，只能靠本 IT。方法顺序由 @Order 强制：migrate 用例先行，其余断言用例依赖其建库结果（add-dynamic-config-key-tier-acl 任务
 * 1.4； V34 断言见 prompt-template-enhance 任务 1.2）。
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
              "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40"));

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

  /**
   * V34 断言（prompt-template-enhance 任务 1.2）： prompt_template_feedback 表建出且含
   * idx_prompt_template_feedback_tpl_version 索引；prompt_template_history 扩出可空 source_version
   * 列（revert 审计链追溯载体）。
   */
  @Test
  @Order(6)
  void promptTemplateFeedbackTableAndHistorySourceVersionExist() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE prompt_template_feedback")) {
      assertTrue(rs.next(), "prompt_template_feedback 表应存在");
      String ddl = rs.getString(2);
      assertTrue(
          ddl.contains("`score` tinyint NOT NULL") || ddl.contains("`score` tinyint(4) NOT NULL"),
          "V34 应建出 score 列且 NOT NULL，实际 DDL：" + ddl);
      assertTrue(
          ddl.contains("KEY `idx_prompt_template_feedback_tpl_version` (`template_id`,`version`)")
              || ddl.contains(
                  "KEY `idx_prompt_template_feedback_tpl_version` (`template_id`, `version`)"),
          "V34 应建出 idx_prompt_template_feedback_tpl_version 索引，实际 DDL：" + ddl);
    }
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW CREATE TABLE prompt_template_history")) {
      assertTrue(rs.next(), "prompt_template_history 表应存在");
      String ddl = rs.getString(2);
      assertTrue(
          ddl.matches("(?s).*`source_version` int DEFAULT NULL.*")
              || ddl.matches("(?s).*`source_version` int(11) DEFAULT NULL.*"),
          "V34 应在 prompt_template_history 扩出可空 source_version 列，实际 DDL：" + ddl);
    }
  }

  /**
   * V35 断言（aichat-prompt-template-integration 任务 1.2）：AiChat 运行时模板种子落库—— aichat-system-prompt /
   * aichat-session-title 两 code 存在、content 非空、status=0 启用态。
   */
  @Test
  @Order(7)
  void aiChatPromptTemplateSeedInsertedAndEnabled() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      for (String code : List.of("aichat-system-prompt", "aichat-session-title")) {
        ResultSet rs =
            statement.executeQuery(
                "SELECT content, status FROM prompt_template WHERE code = '" + code + "'");
        assertTrue(rs.next(), "V35 应种入模板 " + code);
        String content = rs.getString(1);
        int status = rs.getInt(2);
        assertTrue(content != null && !content.isBlank(), "V35 模板 " + code + " content 不应为空");
        assertEquals(0, status, "V35 模板 " + code + " 应为启用态 status=0");
      }
    }
  }

  /**
   * V36 断言（add-prompt-template-nesting 任务 2.2）：嵌套引用种子落库——agent-common-discipline 公共子模板存在、两 agent 骨架
   * content 已嵌入 {{> agent-common-discipline}} 且 version+1、公共子模板与两骨架 history 快照就位。
   */
  @Test
  @Order(8)
  void promptTemplateNestingSeedInsertedAndEmbedded() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      // 公共子模板存在且启用
      ResultSet common =
          statement.executeQuery(
              "SELECT content, status, version FROM prompt_template WHERE code = 'agent-common-discipline'");
      assertTrue(common.next(), "V36 应种入公共子模板 agent-common-discipline");
      assertEquals(0, common.getInt(2), "公共子模板应为启用态 status=0");
      assertEquals(1, common.getInt(3), "公共子模板应为 version=1");
      String commonContent = common.getString(1);
      assertTrue(
          commonContent.contains("{{redLines}}"), "公共子模板应含 {{redLines}} 占位，实际：" + commonContent);

      // 两骨架 content 已嵌入引用且 version+1（V33 为 version=1 → V36 后 version=2）
      for (String code : List.of("agent-executor-brief", "agent-reviewer-brief")) {
        ResultSet rs =
            statement.executeQuery(
                "SELECT content, version FROM prompt_template WHERE code = '" + code + "'");
        assertTrue(rs.next(), "V36 应更新骨架 " + code);
        assertEquals(2, rs.getInt(2), "V36 后 " + code + " 应为 version=2");
        String content = rs.getString(1);
        assertTrue(
            content.contains("{{> agent-common-discipline}}"),
            "V36 后 " + code + " content 应嵌入嵌套引用，实际：" + content);
      }

      // history 快照：公共子模板 CREATE + 两骨架 UPDATE 各就位
      ResultSet historyUpdate =
          statement.executeQuery(
              "SELECT COUNT(*) FROM prompt_template_history h JOIN prompt_template t"
                  + " ON h.template_id = t.id WHERE t.code IN ('agent-executor-brief',"
                  + " 'agent-reviewer-brief') AND h.version = 2 AND h.operation = 'UPDATE'");
      assertTrue(historyUpdate.next(), "history UPDATE 计数行缺失");
      assertEquals(2, historyUpdate.getLong(1), "两骨架各落一笔 version=2 UPDATE 快照");
      ResultSet historyCreate =
          statement.executeQuery(
              "SELECT COUNT(*) FROM prompt_template_history h JOIN prompt_template t"
                  + " ON h.template_id = t.id WHERE t.code = 'agent-common-discipline'"
                  + " AND h.version = 1 AND h.operation = 'CREATE'");
      assertTrue(historyCreate.next(), "history CREATE 计数行缺失");
      assertEquals(1, historyCreate.getLong(1), "公共子模板落一笔 version=1 CREATE 快照");
    }
  }

  /**
   * V37 痕迹断言（add-prompt-template-slots 任务 2.2；add-customer-semantic-retrieval 任务 4.2 终态适配）：
   * 本类用例都跑在全链迁移后的最终态上，终态断言随最新迁移推进——现由 Order 10 的 V38 断言承担 （version=3、五槽位尾、history version=3
   * 快照）；本用例只锁 V37 自身痕迹：aichat-system-prompt 的 version=2 history UPDATE 快照、启用态 status=0
   * 未变、aichat-session-title 零改动。
   */
  @Test
  @Order(9)
  void aichatScriptSystemPromptV37TraceIntact() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      ResultSet rs =
          statement.executeQuery(
              "SELECT content, version, status FROM prompt_template WHERE code ="
                  + " 'aichat-system-prompt'");
      assertTrue(rs.next(), "V37 应更新 aichat-system-prompt 模板");
      assertEquals(0, rs.getInt(3), "V37 不应改变启用态 status=0");

      // aichat-session-title 零改动：不得嵌入任何槽位标记
      ResultSet titleRs =
          statement.executeQuery(
              "SELECT content FROM prompt_template WHERE code = 'aichat-session-title'");
      assertTrue(titleRs.next(), "aichat-session-title 应存在");
      assertTrue(
          !titleRs.getString(1).contains("{{#slot:"), "V37 不应改动 aichat-session-title content");

      // history UPDATE 快照：V37 落 version=2 一笔（终态 version/history 见 Order 10 的 V38 断言）
      ResultSet history =
          statement.executeQuery(
              "SELECT COUNT(*) FROM prompt_template_history h JOIN prompt_template t"
                  + " ON h.template_id = t.id WHERE t.code = 'aichat-system-prompt'"
                  + " AND h.version = 2 AND h.operation = 'UPDATE'");
      assertTrue(history.next(), "history UPDATE 计数行缺失");
      assertEquals(1, history.getLong(1), "aichat-system-prompt 应落一笔 version=2 UPDATE 快照");
    }
  }

  /**
   * V38 断言（add-customer-semantic-retrieval 任务 1.4）：aichat-system-prompt content 尾部已追加
   * retrievedContext 槽位标记、version 升为 3（V37 为 version=2）、生效态 status=0 未变； aichat-session-title
   * 零改动；history 落一笔 version=3 UPDATE 快照。
   */
  @Test
  @Order(10)
  void aichatSystemPromptRetrievalSlotMarkerAppendedAndVersionBumped() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      ResultSet rs =
          statement.executeQuery(
              "SELECT content, version, status FROM prompt_template WHERE code ="
                  + " 'aichat-system-prompt'");
      assertTrue(rs.next(), "V38 应更新 aichat-system-prompt 模板");
      String content = rs.getString(1);
      assertEquals(3, rs.getInt(2), "V38 后 aichat-system-prompt 应为 version=3");
      assertEquals(0, rs.getInt(3), "V38 不应改变启用态 status=0");
      // 五槽位标记按现顺序就位：V37 四标记 + retrievedContext（标记=可自定义开关/调序点）
      String tail =
          "{{#slot:currentIntent}}{{#slot:confirmedFacts}}{{#slot:historySummary}}{{#slot:clarify}}"
              + "{{#slot:retrievedContext}}";
      assertTrue(
          content.endsWith(tail),
          "V38 应在 content 尾部追加 retrievedContext 槽位标记，实际尾部："
              + content.substring(Math.max(0, content.length() - 170)));

      // aichat-session-title 零改动：不得嵌入任何槽位标记
      ResultSet titleRs =
          statement.executeQuery(
              "SELECT content FROM prompt_template WHERE code = 'aichat-session-title'");
      assertTrue(titleRs.next(), "aichat-session-title 应存在");
      assertTrue(
          !titleRs.getString(1).contains("{{#slot:"), "V38 不应改动 aichat-session-title content");

      // history UPDATE 快照：version=3 一笔
      ResultSet history =
          statement.executeQuery(
              "SELECT COUNT(*) FROM prompt_template_history h JOIN prompt_template t"
                  + " ON h.template_id = t.id WHERE t.code = 'aichat-system-prompt'"
                  + " AND h.version = 3 AND h.operation = 'UPDATE'");
      assertTrue(history.next(), "history UPDATE 计数行缺失");
      assertEquals(1, history.getLong(1), "aichat-system-prompt 应落一笔 version=3 UPDATE 快照");
    }
  }

  /**
   * V39 断言（add-customer-retrieval-baseline 任务 1.1/1.2）：演示种子落库且权限四档归属齐备—— 120 行演示客户（owner 覆盖 6
   * 名演示销售员）、4 个演示部门带 leader_id、演示销售角色 roleId=5 存在、 608 已授 roleId=5（FlywayMigrationIT Order2
   * 守卫的"业务角色全量获 608"在 V39 后仍成立）。
   */
  @Test
  @Order(11)
  void customerDemoSeedInsertedWithFourTierOwnerAttribution() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      ResultSet customers =
          statement.executeQuery(
              "SELECT COUNT(*), COUNT(DISTINCT owner_id) FROM customer_company"
                  + " WHERE is_deleted = 0 AND id BETWEEN 1001 AND 1120");
      assertTrue(customers.next(), "V39 客户计数行缺失");
      assertEquals(120, customers.getLong(1), "V39 应种入 120 行未删除演示客户");
      assertEquals(6, customers.getLong(2), "V39 客户 owner 应覆盖 6 名演示销售员（四档归属数据面）");

      ResultSet depts =
          statement.executeQuery(
              "SELECT COUNT(*), COUNT(leader_id) FROM sys_dept WHERE id IN (101, 102, 103, 104)");
      assertTrue(depts.next(), "V39 部门计数行缺失");
      assertEquals(4, depts.getLong(1), "V39 应种入 4 个演示部门");
      assertEquals(4, depts.getLong(2), "V39 演示部门应全部带 leader_id（上司部门可见档依赖）");

      ResultSet role =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sys_role WHERE id = 5 AND is_deleted = b'0'");
      assertTrue(role.next(), "V39 演示角色计数行缺失");
      assertEquals(1, role.getLong(1), "V39 应种入演示销售角色 roleId=5（is_deleted=0）");

      ResultSet grant =
          statement.executeQuery(
              "SELECT COUNT(*) FROM role_permissions WHERE role_id = 5 AND permissions_id = 608"
                  + " AND is_deleted = b'0'");
      assertTrue(grant.next(), "V39 角色权限计数行缺失");
      assertEquals(1, grant.getLong(1), "V39 应授 roleId=5 权限 608（Order2 业务角色全量守卫）");
    }
  }

  /**
   * V40 断言（add-customer-contact-retrieval 任务 2.2）：客户联系人演示种子落库——240 行（1001-1120 每家 2 人）、 显式 id 从
   * 20001 起避开 init_data.sql 测试种子（id=1）、全部 is_deleted=0、company_id 外键命中既有 V39 客户、 mobile
   * 全局唯一、职位分布覆盖关键岗位（销售/技术/采购/运营），供「联系人作客户附属语义段」检索使用。
   */
  @Test
  @Order(12)
  void customerContactDemoSeedInsertedForAllV39Customers() throws Exception {
    assumeTrue(mysql != null, "Docker 不可用时本用例随类跳过");
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement()) {
      ResultSet contacts =
          statement.executeQuery(
              "SELECT COUNT(*), COUNT(DISTINCT company_id), COUNT(DISTINCT mobile),"
                  + " SUM(CASE WHEN is_deleted = 0 THEN 1 ELSE 0 END)"
                  + " FROM customer_contact WHERE id BETWEEN 20001 AND 20240");
      assertTrue(contacts.next(), "V40 联系人计数行缺失");
      assertEquals(240, contacts.getLong(1), "V40 应种入 240 行客户联系人");
      assertEquals(120, contacts.getLong(2), "V40 联系人应覆盖全部 120 家 V39 演示客户（每家 ≥1）");
      assertEquals(240, contacts.getLong(3), "V40 联系人 mobile 应全局唯一（idx_mobile 约束）");
      assertEquals(240, contacts.getLong(4), "V40 联系人应全部 is_deleted=0（侵入解读语义段）");

      // 每家客户恰 2 名联系人（对称 V39 每家 2 人，也验证不触检索附段 5 人上限）
      ResultSet perCompany =
          statement.executeQuery(
              "SELECT company_id, COUNT(*) FROM customer_contact WHERE id BETWEEN 20001 AND 20240"
                  + " GROUP BY company_id HAVING COUNT(*) <> 2");
      assertTrue(!perCompany.next(), "存在公司联系人数量不为 2——V40 应每家恰 2 人");

      // 联系人外键公司存在（company_id 命中 customer_company 1001-1120）
      ResultSet orphans =
          statement.executeQuery(
              "SELECT COUNT(*) FROM customer_contact cc LEFT JOIN customer_company cm"
                  + " ON cc.company_id = cm.id WHERE cc.id BETWEEN 20001 AND 20240"
                  + " AND (cm.id IS NULL OR cm.is_deleted = 1)");
      assertTrue(orphans.next() && orphans.getLong(1) == 0, "V40 联系人外键公司缺失或公司已删");

      // 职位分布抽查：关键岗位均被覆盖（销售/技术/采购/运营 语义面）
      ResultSet positions =
          statement.executeQuery(
              "SELECT COUNT(DISTINCT position) FROM customer_contact WHERE id BETWEEN 20001 AND 20240"
                  + " AND position IN ('销售总监','技术负责人','采购经理','运营总监','总经理','财务负责人')");
      assertTrue(positions.next() && positions.getLong(1) >= 3, "V40 职位分布应覆盖 ≥3 个关键岗位");
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
