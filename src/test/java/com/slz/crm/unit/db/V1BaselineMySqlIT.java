package com.slz.crm.unit.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Flyway V1 基线在真实 MySQL 上的可执行性验证（IT，默认跳过）。
 *
 * <p>为什么放 IT 而不是单测：V1 基线包含 MySQL 专属特性（{@code IF()} 生成列、
 * {@code ON UPDATE CURRENT_TIMESTAMP}、位字面量），H2 无法等价执行；
 * 只有真 MySQL 才能证明“脚本在目标库上真的能跑”。</p>
 *
 * <p>触发条件（环境变量全部就绪才执行，避免 CI 无 MySQL 时误报）：</p>
 * <ul>
 *   <li>{@code SLZ_MYSQL_VERIFY_DATABASE}：验证用库名（建议用一次性库，跑完可删）；</li>
 *   <li>{@code SLZ_MYSQL_VERIFY_USERNAME} / {@code SLZ_MYSQL_VERIFY_PASSWORD}</li>
 * </ul>
 *
 * <p>副作用：会在目标实例上创建/接管 {@code SLZ_MYSQL_VERIFY_DATABASE} 库并写入
 * {@code flyway_schema_history}；请始终使用一次性验证库，禁止指向业务库。</p>
 */
@EnabledIfEnvironmentVariable(named = "SLZ_MYSQL_VERIFY_DATABASE", matches = ".+")
class V1BaselineMySqlIT {

    /** 基线必须覆盖的表数量（与 CRM 源仓 @TableName 实体数一致） */
    private static final int EXPECTED_TABLE_COUNT = 36;

    @Test
    void shouldApplyBaselineOnRealMySql() throws Exception {
        String database = System.getenv("SLZ_MYSQL_VERIFY_DATABASE");
        String username = System.getenv("SLZ_MYSQL_VERIFY_USERNAME");
        String password = System.getenv("SLZ_MYSQL_VERIFY_PASSWORD");
        String host = System.getenv().getOrDefault("SLZ_MYSQL_VERIFY_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("SLZ_MYSQL_VERIFY_PORT", "3306");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf-8&createDatabaseIfNotExist=true";
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            int tableCount = countTables(statement);
            assertEquals(EXPECTED_TABLE_COUNT, tableCount,
                    "V1 基线在 MySQL 上建表数量必须与 CRM 现有实体表数一致");
            int appliedCount = countAppliedMigrations(statement);
            assertEquals(1, appliedCount, "flyway_schema_history 应只记录 V1 基线一次");
        }
    }

    /**
     * 统计业务表数量（排除 Flyway 自身的 history 表）。
     *
     * @param statement 目标库连接语句对象
     * @return 业务表数量
     * @throws Exception 查询失败时抛出
     */
    private int countTables(Statement statement) throws Exception {
        String sql = "select count(*) from information_schema.tables"
                + " where table_schema = database() and table_name <> 'flyway_schema_history'";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * 统计已应用的迁移版本数（保证脚本不会被重复执行两次）。
     *
     * @param statement 目标库连接语句对象
     * @return 已应用迁移数
     * @throws Exception 查询失败时抛出
     */
    private int countAppliedMigrations(Statement statement) throws Exception {
        String sql = "select count(*) from flyway_schema_history where success = 1";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
