package com.slz.crm.integration.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import org.junit.jupiter.api.Test;

/** Qdrant 超时配置集成测试；纯 JVM 构造断言，不启动容器、不连接 Qdrant 服务。 */
class QdrantTimeoutConfigIT {

  @Test
  void shouldApplyCustomTimeoutConfiguration() {
    QdrantProperties properties = new QdrantProperties();
    properties.setHost("localhost");
    properties.setPort(6334);
    properties.setTimeoutMs(20000); // 自定义 20 秒超时
    properties.setCollection("test_collection");
    properties.setDimensions(1024);

    QdrantVectorStore store = new QdrantVectorStore(properties);

    assertNotNull(store);
    assertEquals(20000, properties.getTimeoutMs());
  }

  @Test
  void shouldDefaultToTenSecondsTimeout() {
    QdrantProperties properties = new QdrantProperties();
    properties.setHost("localhost");
    properties.setPort(6334);

    QdrantVectorStore store = new QdrantVectorStore(properties);

    assertNotNull(store);
    assertEquals(10000, properties.getTimeoutMs());
  }

  @Test
  void shouldCreateVectorStoreWithMinimalConfig() {
    QdrantProperties properties = new QdrantProperties();

    QdrantVectorStore store = new QdrantVectorStore(properties);

    assertNotNull(store);
    assertEquals("knowledge_chunk", properties.getCollection());
    assertEquals(1024, properties.getDimensions());
    assertEquals(10000, properties.getTimeoutMs());
  }
}
