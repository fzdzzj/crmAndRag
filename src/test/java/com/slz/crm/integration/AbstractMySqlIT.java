package com.slz.crm.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 集成测试基类。
 *
 * <p>所有 integration 测试共用同一个静态 MySQL 容器（同一 JVM 内只启动一次）， 通过 {@link DynamicPropertySource} 覆盖
 * application-test.yml 中的 H2 数据源。 业务表由 com.tangzc auto-table 在上下文启动时自动创建； 各测试方法配合
 * {@code @Transactional + @Sql(/init_data.sql)} 保证数据隔离。
 *
 * <p>Docker 门禁：容器在 {@code @BeforeAll} 内、{@code assumeTrue(isDockerAvailable)} 之后才启动， 无 Docker
 * 的开发机上子类 IT 记 skipped 而非 ExceptionInInitializerError；CI（自带 Docker）真跑。 与 {@link
 * FlywayMigrationIT}、{@link com.slz.crm.integration.schema.SchemaDriftAuditIT} 同一守卫口径。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMySqlIT {

  /** 静态单例容器：同一 JVM 内所有集成测试类共享，避免重复启动。 */
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.0.36")
          .withDatabaseName("crm_it")
          .withUsername("crm")
          .withPassword("crm_it_pwd");

  /** 共享容器只启动一次：每个子类 IT 都会触发一次本类的 {@code @BeforeAll}。 */
  private static boolean mysqlStarted;

  @BeforeAll
  static void startSharedMysqlContainer() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过依赖真 MySQL 的集成测试（在 CI 环境执行）");
    if (!mysqlStarted) {
      MYSQL.start();
      mysqlStarted = true;
    }
  }

  @DynamicPropertySource
  static void mysqlDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
  }
}
