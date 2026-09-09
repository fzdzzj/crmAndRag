package com.slz.crm.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 集成测试基类。
 *
 * <p>所有 integration 测试共用同一个静态 MySQL 容器（同一 JVM 内只启动一次），
 * 通过 {@link DynamicPropertySource} 覆盖 application-test.yml 中的 H2 数据源。
 * 业务表由 com.tangzc auto-table 在上下文启动时自动创建；
 * 各测试方法配合 {@code @Transactional + @Sql(/init_data.sql)} 保证数据隔离。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMySqlIT {

    /**
     * 静态单例容器：同一 JVM 内所有集成测试类共享，避免重复启动。
     */
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_it")
            .withUsername("crm")
            .withPassword("crm_it_pwd");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
