package com.slz.crm.platform.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态配置 Flyway 脚本（V6__dynamic_config.sql）的 H2 兼容性测试。
 *
 * <p>为什么需要它：V6 是 Lane E 的库结构交付物，生产走 MySQL（由 integrator 的
 * Testcontainers IT 在真库上验证），但本 lane 验收需在无 Docker 环境自证脚本可执行；
 * 同时保证脚本在 H2（MODE=MySQL，对齐 application-test.yml 的测试数据源）下可重复执行，
 * 避免将来 H2 上下文测试被本脚本卡死。</p>
 *
 * <p>本测试不启动 Spring 容器，直接用 JDBC 执行脚本并断言表/列/约束/读写语义。</p>
 */
class DynamicConfigSchemaH2Test {

    private static Connection connection;

    @BeforeAll
    static void initDb() throws Exception {
        // 与 application-test.yml 保持同一 H2 连接参数（MODE=MySQL + 小写库表名）
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:dc_schema_test;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V6__dynamic_config.sql"));
    }

    @AfterAll
    static void closeDb() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("两张表与核心约束均按脚本建立")
    void tablesAndConstraintsExist() throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet tables = st.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (tables.next()) {
                names.add(tables.getString(1).toLowerCase());
            }
            assertThat(names).contains("dynamic_config_item", "dynamic_config_history");
        }

        // 唯一键语义：同一 config_key 重复插入必须被数据库拒绝（含软删行，复活语义依赖）
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO dynamic_config_item " +
                    "(config_key, namespace, value_type, config_value, version, is_deleted) " +
                    "VALUES ('business.feature.aiAssistantEnabled', 'business', 'BOOLEAN', 'true', 1, 0)");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    st.executeUpdate("INSERT INTO dynamic_config_item " +
                            "(config_key, namespace, value_type, config_value, version, is_deleted) " +
                            "VALUES ('business.feature.aiAssistantEnabled', 'business', 'BOOLEAN', 'false', 1, 0)"))
                    .as("重复配置键必须被 uk_dynamic_config_key 拒绝")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("插入-读取-软删-复活语义在 SQL 层可用")
    void insertReadSoftDeleteRoundTrip() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO dynamic_config_item " +
                    "(config_key, namespace, value_type, config_value, description, version, is_deleted) " +
                    "VALUES ('rag.retrieval.topK', 'rag.retrieval', 'INTEGER', '8', '检索 topK', 1, 0)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT config_value, version FROM dynamic_config_item WHERE config_key = 'rag.retrieval.topK' AND is_deleted = 0")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("8");
                assertThat(rs.getInt(2)).isEqualTo(1);
            }

            // 软删：读取侧按 is_deleted=0 过滤后不可见（回退默认），历史仍可追溯
            st.executeUpdate("UPDATE dynamic_config_item SET is_deleted = 1 WHERE config_key = 'rag.retrieval.topK'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM dynamic_config_item WHERE config_key = 'rag.retrieval.topK' AND is_deleted = 0")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            // 复活：同一唯一键不重建而是反删（版本连续性）
            st.executeUpdate("UPDATE dynamic_config_item SET is_deleted = 0, config_value = '6', version = version + 1 " +
                    "WHERE config_key = 'rag.retrieval.topK'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT config_value, version FROM dynamic_config_item WHERE config_key = 'rag.retrieval.topK' AND is_deleted = 0")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("6");
                assertThat(rs.getInt(2)).isEqualTo(2);
            }

            // 版本历史：一条 CREATE 语义行可落库
            st.executeUpdate("INSERT INTO dynamic_config_history " +
                    "(config_id, config_key, version, value_type, operation_type, old_value, new_value, operator_ref) " +
                    "VALUES (1, 'rag.retrieval.topK', 2, 'INTEGER', 'UPDATE', '8', '6', 'user:1')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT new_value FROM dynamic_config_history WHERE config_key = 'rag.retrieval.topK' AND version = 2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("6");
            }
        }
    }
}
