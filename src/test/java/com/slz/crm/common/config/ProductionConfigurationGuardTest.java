package com.slz.crm.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 生产配置守卫纯 JVM 单元测试（卡 P-d）：MockEnvironment 直构守卫，不起 Spring 容器。
 *
 * <p>正向覆盖：全部 8 项配置合法时启动放行（含 single-replica 大小写不敏感）。反向覆盖：逐项破坏一个配置 （第 8 项缺失或为 false，及既有 7
 * 项——JWT/附件令牌密钥过短、数据源凭据缺失、auto-table 非 none、 Flyway 关闭、Actuator 裸奔、内存向量库、默认管理员口令），断言 fail-fast 的
 * IllegalStateException 带对应文案。
 */
class ProductionConfigurationGuardTest {

  /** 32 字节以上的合规占位密钥（仅测试用，非真实凭据）。 */
  private static final String VALID_SECRET = "unit-test-secret-0123456789abcdef-0123456789abcdef";

  /** 全部既有 7 项合法、但不含第 8 项（single-replica）的基础环境；正向用例自行补上该项。 */
  private MockEnvironment baseEnvironment() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("slz.jwt.secret-key", VALID_SECRET);
    env.setProperty("slz.attach-token.secret-key", VALID_SECRET);
    env.setProperty("spring.datasource.username", "crm");
    env.setProperty("spring.datasource.password", "unit-test-password");
    env.setProperty("auto-table.mode", "none");
    env.setProperty("spring.flyway.enabled", "true");
    env.setProperty("platform.actuator.protected-enabled", "true");
    env.setProperty("rag.vector-store.provider", "qdrant");
    env.setProperty("data.init.admin.password", "unit-test-admin-password");
    return env;
  }

  /** 运行守卫并返回 fail-fast 异常文案（不通过则该断言自身先红）。 */
  private String failReasonOf(MockEnvironment env) {
    return assertThrows(
            IllegalStateException.class,
            () -> new ProductionConfigurationGuard(env).afterPropertiesSet())
        .getMessage();
  }

  @Test
  void allEightChecksValidEnvironmentPassesStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("platform.architecture.single-replica", "true");
    assertDoesNotThrow(() -> new ProductionConfigurationGuard(env).afterPropertiesSet());
  }

  @Test
  void singleReplicaTrueIsCaseInsensitive() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("platform.architecture.single-replica", "TRUE");
    assertDoesNotThrow(() -> new ProductionConfigurationGuard(env).afterPropertiesSet());
  }

  @Test
  void missingSingleReplicaBlocksStartup() {
    assertTrue(
        failReasonOf(baseEnvironment()).contains("platform.architecture.single-replica=true"),
        "single-replica 未配置必须被第 8 项检查阻断");
  }

  @Test
  void falseSingleReplicaBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("platform.architecture.single-replica", "false");
    assertTrue(failReasonOf(env).contains("系统设计基于单副本单体架构"), "single-replica=false 必须被第 8 项检查阻断");
  }

  @Test
  void shortJwtSecretBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("slz.jwt.secret-key", "too-short");
    assertTrue(failReasonOf(env).contains("slz.jwt.secret-key 长度不足 32 字节"));
  }

  @Test
  void shortAttachTokenSecretBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("slz.attach-token.secret-key", "too-short");
    assertTrue(failReasonOf(env).contains("slz.attach-token.secret-key 长度不足 32 字节"));
  }

  @Test
  void missingDatasourceCredentialsBlockStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("spring.datasource.password", "");
    assertTrue(failReasonOf(env).contains("spring.datasource.password 未配置"));
  }

  @Test
  void nonNoneAutoTableBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("auto-table.mode", "update");
    assertTrue(failReasonOf(env).contains("auto-table.mode=none"));
  }

  @Test
  void flywayDisabledBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("spring.flyway.enabled", "false");
    assertTrue(failReasonOf(env).contains("spring.flyway.enabled=true"));
  }

  @Test
  void unprotectedActuatorBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("platform.actuator.protected-enabled", "false");
    assertTrue(failReasonOf(env).contains("platform.actuator.protected-enabled=true"));
  }

  @Test
  void inMemoryVectorProviderBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("rag.vector-store.provider", "in-memory");
    assertTrue(failReasonOf(env).contains("rag.vector-store.provider=qdrant"));
  }

  @Test
  void defaultAdminPasswordBlocksStartup() {
    MockEnvironment env = baseEnvironment();
    env.setProperty("data.init.admin.password", "admin123");
    assertTrue(failReasonOf(env).contains("SLZ_ADMIN_PASSWORD"));
  }
}
