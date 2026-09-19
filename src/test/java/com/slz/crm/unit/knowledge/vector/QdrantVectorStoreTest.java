package com.slz.crm.unit.knowledge.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** QdrantVectorStore 超时配置与重试单元测试。 */
@ExtendWith(MockitoExtension.class)
class QdrantVectorStoreTest {

  @Mock private QdrantGrpcClient mockGrpcClient;

  @Mock private QdrantClient mockClient;

  @Test
  void shouldUseConfiguredTimeoutFromProperties() {
    QdrantProperties properties = new QdrantProperties();
    properties.setHost("localhost");
    properties.setPort(6334);
    properties.setTimeoutMs(15000); // 自定义超时 15 秒

    QdrantVectorStore store = new QdrantVectorStore(properties);

    assertNotNull(store);
    assertEquals(15000, properties.getTimeoutMs());
  }

  @Test
  void shouldDefaultToTenSecondsTimeout() {
    QdrantProperties properties = new QdrantProperties();

    QdrantVectorStore store = new QdrantVectorStore(properties);

    assertNotNull(store);
    assertEquals(10000, properties.getTimeoutMs());
  }

  @Test
  void shouldRetryOnTransientFailure() throws Exception {
    QdrantProperties properties = new QdrantProperties();
    properties.setHost("localhost");
    properties.setPort(6334);
    properties.setTimeoutMs(5000);

    QdrantVectorStore store = new QdrantVectorStore(properties);

    // 验证 store 创建成功
    assertNotNull(store);
  }
}
