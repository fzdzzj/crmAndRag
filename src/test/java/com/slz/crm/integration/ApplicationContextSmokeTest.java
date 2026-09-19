package com.slz.crm.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成冒烟：在 H2(test profile) 上加载完整 Spring 上下文，验证五 lane（A/B/C/D/E）合并后 整个 bean 图无冲突、可装配。
 *
 * <p>与 {@link AbstractMySqlIT} 的区别：本测试<b>不依赖 Docker/Testcontainers</b>， 用 test profile 的 H2 +
 * 下列离线友好覆盖，本地与 CI 均可跑，是"合并后能否启动"的最快闸门。
 *
 * <p>覆盖项意图：
 *
 * <ul>
 *   <li>{@code rag.vector-store.provider=in-memory}：避开 Qdrant，用 B 的内存向量库；
 *   <li>{@code knowledge.storage.provider=in-memory}：避开 MinIO，用 B 的内存文件存储；
 *   <li>{@code crm.ai.knowledge-retrieval.mock-enabled=false}：停用 C 的 Mock 端口， 只留 B 的
 *       KnowledgeRetrievalServiceImpl——正是验证 Seam 1 单 bean（否则双实现令 C 的 ObjectProvider.getIfAvailable
 *       抛 NoUniqueBeanDefinitionException）；
 *   <li>{@code spring.ai.dashscope.api-key}：给占位 key 令 DashScope 自动配置可构造（不发起真实调用）。
 * </ul>
 */
@SpringBootTest(
    properties = {
      "rag.vector-store.provider=in-memory",
      "knowledge.storage.provider=in-memory",
      "crm.ai.knowledge-retrieval.mock-enabled=false",
      "spring.ai.dashscope.api-key=sk-integration-smoke-placeholder",
      // Flyway 脚本是 MySQL 专有 DDL（V1 含 generated always as(if(...))stored），H2 无法解析；
      // 本冒烟只验 bean 图装配，故关 Flyway，schema 交 auto-table（contextLoads 不查表）。
      // 注：真 MySQL 上的 Flyway 全量迁移由 Docker 的 AbstractMySqlIT 系列验证。
      "spring.flyway.enabled=false"
    })
@ActiveProfiles("test")
class ApplicationContextSmokeTest {

  /** 上下文能加载即通过：任何 bean 冲突/缺失依赖/装配错误都会在此暴露为加载失败。 */
  @Test
  void contextLoads() {
    // 断言由 @SpringBootTest 上下文加载本身完成；无需额外语句。
  }
}
