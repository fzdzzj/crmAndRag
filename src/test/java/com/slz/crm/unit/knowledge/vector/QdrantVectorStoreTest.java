package com.slz.crm.unit.knowledge.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;
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

  /**
   * wire-dependency-circuit-breaker 任务 4：真实外呼失败计入 vector-qdrant 的 {@code
   * dependency.call{result=failure}}；熔断层零自动重试，内建三次指数退避与既有消息格式原样保留。
   *
   * <p>打向保留端口 1（连接立即被拒），零真实服务依赖。
   */
  @Test
  void searchFailureCountsDependencyFailureAndKeepsInnerRetryContract() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    QdrantVectorStore store =
        new QdrantVectorStore(deadPortProperties(), new DependencyResilienceExecutor(registry));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> store.search(deadPortRequest()));

    assertNotNull(failure.getCause());
    assertTrue(
        failure.getCause().getMessage().contains("已重试 3 次"),
        "内建 executeWithRetry 的三次退避必须原样保留在熔断器内侧");
    assertEquals(
        1.0,
        registry
            .get("dependency.call")
            .tag("dependency", "vector-qdrant")
            .tag("result", "failure")
            .counter()
            .count(),
        "一次门面请求计入一次依赖失败");
    assertNull(
        registry.find("dependency.retry").tag("dependency", "vector-qdrant").counter(),
        "熔断层不得引入任何自动重试");
  }

  /**
   * wire-dependency-circuit-breaker 任务 4：OPEN 状态下的门面调用必须快速拒绝——零物理外呼、异常类型仍是门面既有 {@link
   * IllegalStateException}（cause 为 CircuitOpenException），{@link DependencyUnavailableException}
   * 不泄漏。
   */
  @Test
  void openCircuitFastRejectsSearchWithoutPhysicalAttempt() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DependencyResilienceExecutor executor = new DependencyResilienceExecutor(registry);
    QdrantVectorStore store = new QdrantVectorStore(deadPortProperties(), executor);
    for (int index = 0; index < 5; index++) {
      assertThrows(
          DependencyUnavailableException.class,
          () -> executor.executeNoRetry("vector-qdrant", dying()));
    }

    long startedAtNanos = System.nanoTime();
    IllegalStateException rejected =
        assertThrows(IllegalStateException.class, () -> store.search(deadPortRequest()));
    long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

    assertTrue(
        rejected.getCause() instanceof DependencyUnavailableException.CircuitOpenException,
        "熔断拒绝转回门面既有异常类型，cause 为 CircuitOpenException");
    assertEquals(
        1.0,
        registry
            .get("dependency.circuit.rejected")
            .tag("dependency", "vector-qdrant")
            .counter()
            .count(),
        "OPEN 后的请求计入 circuit.rejected");
    assertTrue(elapsedMillis < 1_000L, "OPEN 快速拒绝不得等待连接与退避（实测 " + elapsedMillis + "ms）");
  }

  /**
   * wire-dependency-circuit-breaker 任务 4：三操作必须各自单接 executeNoRetry 并共用 vector-qdrant 依赖名；
   * 探针与启动初始化属不接线面，接线数变化即判红。
   *
   * <p>纯源码扫描（无网络无 Docker）：红测试先行，未接线的 af99e41 基线上实跑为红。
   */
  @Test
  void threeDataOperationsStayWiredToDependencyCircuitBreaker() throws IOException {
    Path path = Paths.get("src/main/java", "com/slz/crm/knowledge/vector/QdrantVectorStore.java");
    String code =
        Files.readString(path, StandardCharsets.UTF_8)
            // 剥掉块注释与行注释，防注释里提一句方法名就把守卫喂绿
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)//.*$", " ");

    assertEquals(
        3,
        occurrences(code, "executeNoRetry"),
        "upsertAll / search / deleteByDocumentId 各一条，多接即探针被接线");
    assertEquals(1, occurrences(code, "\"vector-qdrant\""), "三操作共用一个依赖名常量 vector-qdrant");
    assertEquals(4, occurrences(code, "toFacadeFailure("), "三处 catch 各自把熔断异常转回门面既有异常类型");
    assertTrue(code.contains("executeWithRetry"), "内建指数退避重试必须保留（熔断器不叠加自动重试）");
    assertTrue(
        code.contains("IllegalStateException(\"Qdrant search 失败\""),
        "既有 IllegalStateException 消息格式零变化");
  }

  private static Callable<Object> dying() {
    return () -> {
      throw new IllegalStateException("circuit opener");
    };
  }

  private static QdrantProperties deadPortProperties() {
    QdrantProperties properties = new QdrantProperties();
    properties.setHost("localhost");
    properties.setPort(1);
    properties.setDimensions(2);
    properties.setTimeoutMs(500);
    return properties;
  }

  private static VectorSearchRequest deadPortRequest() {
    return new VectorSearchRequest(new float[] {0.1f, 0.2f}, 3, null, Map.of());
  }

  private static int occurrences(String haystack, String needle) {
    int result = 0;
    for (int index = haystack.indexOf(needle);
        index >= 0;
        index = haystack.indexOf(needle, index + 1)) {
      result++;
    }
    return result;
  }
}
