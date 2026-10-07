package com.slz.crm.unit.knowledge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.storage.MinioFileStorageService;
import com.slz.crm.knowledge.storage.StorageException;
import com.slz.crm.knowledge.storage.properties.MinioProperties;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;

/**
 * wire-dependency-circuit-breaker 任务 4：MinIO 对象存储门面接入依赖熔断执行器的行为与契约锁。
 *
 * <p>锁住四条红线：① store / open / delete 三个 call 点连续失败达阈值后 OPEN 快速拒绝，物理外呼次数不多于失败请求数（零自动重试放大）； ②
 * 门面抛出类型仍是既有 {@link StorageException}，{@link DependencyUnavailableException} 不出现在签名与异常链里； ③ {@code
 * exists()} 属不接线面，失败按不存在处理、既不抛也不计入熔断；④ 既有异常消息格式零变化。
 *
 * <p>Mockito 桩 MinioClient，零真实外呼。红测试先行：计数用例在未接线的 af99e41 主代码基线上实跑为红（桩被调用 6 次而非 5 次）。
 */
@Timeout(20)
class MinioFileStorageServiceTest {

  /** 执行器骨架默认连续失败阈值（本变更不新增配置键，骨架默认值即契约）。 */
  private static final int FAILURE_THRESHOLD = 5;

  private static final String DEPENDENCY = "storage-minio";

  @Test
  void storeFailuresOpenCircuitAndFastRejectWithoutExtraPhysicalCalls() throws Exception {
    MinioClient client = mock(MinioClient.class);
    AtomicInteger physicalCalls = new AtomicInteger();
    when(client.putObject(any(PutObjectArgs.class)))
        .thenAnswer(throwAfterCount(physicalCalls, "put-object-down"));
    MinioFileStorageService service = new MinioFileStorageService(client, new MinioProperties());

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(
          catchThrowable(
              () -> service.store(new ByteArrayInputStream(new byte[0]), "a.txt", "text/plain")));
    }

    assertThat(physicalCalls.get())
        .as("store 连续失败达阈值后必须 OPEN 快速拒绝，不得继续物理外呼")
        .isEqualTo(FAILURE_THRESHOLD);
    assertFacadeExceptionsKeepContract(thrown);
    assertThat(thrown.get(0)).as("既有异常消息格式零变化").hasMessageContaining("保存文件到 MinIO 失败: ");
  }

  @Test
  void openAndDeleteFailuresShareStorageDependencyAndFastReject() throws Exception {
    MinioClient client = mock(MinioClient.class);
    AtomicInteger openCalls = new AtomicInteger();
    AtomicInteger deleteCalls = new AtomicInteger();
    when(client.getObject(any(GetObjectArgs.class)))
        .thenAnswer(throwAfterCount(openCalls, "get-object-down"));
    doAnswer(throwAfterCount(deleteCalls, "remove-object-down"))
        .when(client)
        .removeObject(any(RemoveObjectArgs.class));
    MinioFileStorageService service = new MinioFileStorageService(client, new MinioProperties());

    List<Throwable> thrown = new ArrayList<>();
    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      thrown.add(catchThrowable(() -> service.open("knowledge/2026/a.txt")));
    }
    // open 已把 storage-minio 计满阈值并开闸：delete 也必须被快速拒绝，不再物理外呼。
    for (int index = 0; index < 2; index++) {
      thrown.add(catchThrowable(() -> service.delete("knowledge/2026/a.txt")));
    }

    assertThat(openCalls.get()).as("open 连续失败达阈值后必须 OPEN 快速拒绝").isEqualTo(FAILURE_THRESHOLD);
    assertThat(deleteCalls.get()).as("同一依赖开闸后 delete 路也必须快速拒绝（依赖名共用）").isZero();
    assertFacadeExceptionsKeepContract(thrown);
    assertThat(thrown.get(0)).hasMessageContaining("从 MinIO 读取文件失败: ");
  }

  @Test
  void existsProbeFailuresNeitherThrowNorCountIntoCircuit() throws Exception {
    MinioClient client = mock(MinioClient.class);
    AtomicInteger statCalls = new AtomicInteger();
    when(client.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              statCalls.incrementAndGet();
              throw new IllegalStateException("stat-object-down");
            });
    AtomicInteger putCalls = new AtomicInteger();
    when(client.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              putCalls.incrementAndGet();
              return null;
            });
    MinioFileStorageService service = new MinioFileStorageService(client, new MinioProperties());

    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      assertThat(service.exists("knowledge/2026/a.txt")).as("exists 失败按不存在处理不抛").isFalse();
    }

    assertThat(service.store(new ByteArrayInputStream(new byte[0]), "a.txt", "text/plain"))
        .as("exists 属不接线面：其失败不得计入 storage-minio 熔断，store 仍须照常外呼")
        .isNotBlank();
    assertThat(putCalls.get()).isEqualTo(1);
    assertThat(statCalls.get()).isEqualTo(FAILURE_THRESHOLD + 1);
  }

  @Test
  void failingStoreThroughInjectedExecutorIncrementsDependencyFailureCounter() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MinioClient client = mock(MinioClient.class);
    AtomicInteger physicalCalls = new AtomicInteger();
    when(client.putObject(any(PutObjectArgs.class)))
        .thenAnswer(throwAfterCount(physicalCalls, "put-object-down"));
    MinioFileStorageService service =
        new MinioFileStorageService(
            client, new MinioProperties(), new DependencyResilienceExecutor(registry));

    assertThat(
            catchThrowable(
                () -> service.store(new ByteArrayInputStream(new byte[0]), "a.txt", "text/plain")))
        .isInstanceOf(StorageException.class);

    assertThat(physicalCalls.get()).as("一次请求一次物理外呼").isEqualTo(1);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", DEPENDENCY)
                .tag("result", "failure")
                .counter()
                .count())
        .as("失败调用必须计入 storage-minio 的 dependency.call{result=failure}")
        .isEqualTo(1.0);
    assertThat(registry.find("dependency.retry").tag("dependency", DEPENDENCY).counter())
        .as("熔断层不得引入任何自动重试")
        .isNull();
  }

  @Test
  void openCircuitRejectionCountsRejectedAndKeepsFacadeExceptionType() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MinioClient client = mock(MinioClient.class);
    AtomicInteger physicalCalls = new AtomicInteger();
    when(client.putObject(any(PutObjectArgs.class)))
        .thenAnswer(throwAfterCount(physicalCalls, "put-object-down"));
    MinioFileStorageService service =
        new MinioFileStorageService(
            client, new MinioProperties(), new DependencyResilienceExecutor(registry));

    for (int index = 0; index <= FAILURE_THRESHOLD; index++) {
      assertThat(
              catchThrowable(
                  () ->
                      service.store(new ByteArrayInputStream(new byte[0]), "a.txt", "text/plain")))
          .isInstanceOf(StorageException.class);
    }

    assertThat(physicalCalls.get()).isEqualTo(FAILURE_THRESHOLD);
    assertThat(
            registry
                .get("dependency.call")
                .tag("dependency", DEPENDENCY)
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(FAILURE_THRESHOLD);
    assertThat(
            registry
                .get("dependency.circuit.opened")
                .tag("dependency", DEPENDENCY)
                .counter()
                .count())
        .as("达阈值开闸一次")
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("dependency.circuit.rejected")
                .tag("dependency", DEPENDENCY)
                .counter()
                .count())
        .as("OPEN 后的请求被快速拒绝并计数")
        .isEqualTo(1.0);
    assertThat(physicalCalls.get()).as("OPEN 后零物理外呼").isEqualTo(FAILURE_THRESHOLD);
  }

  @Test
  void storeOperationsStayWiredToDependencyCircuitBreaker() throws IOException {
    String code = codeOnly("com/slz/crm/knowledge/storage/MinioFileStorageService.java");

    assertThat(count(code, "executeNoRetry"))
        .as("store / open / delete 三个 call 点各自单接 executeNoRetry，多接或少接都判红（exists 属不接线面）")
        .isEqualTo(3);
    assertThat(count(code, "\"" + DEPENDENCY + "\""))
        .as("三操作共用一个依赖名常量 %s", DEPENDENCY)
        .isEqualTo(1);
    assertThat(count(code, "toFacadeFailure")).as("三处 catch 各自把熔断异常转回门面既有异常类型").isEqualTo(4);
    assertThat(count(code, "\"从 MinIO 删除文件失败: \"")).as("既有 StorageException 消息格式零变化").isEqualTo(1);
  }

  // ---------- 断言与桩 ----------

  /** 门面异常契约：类型仍是 StorageException，且异常链里不得出现 DependencyUnavailableException。 */
  private static void assertFacadeExceptionsKeepContract(List<Throwable> thrown) {
    for (Throwable throwable : thrown) {
      assertThat(throwable).as("门面抛出类型保持既有 StorageException").isInstanceOf(StorageException.class);
      Throwable cursor = throwable;
      while (cursor != null) {
        assertThat(cursor)
            .as("DependencyUnavailableException 不得泄漏到门面异常链")
            .isNotInstanceOf(DependencyUnavailableException.class);
        cursor = cursor.getCause();
      }
    }
  }

  /** 计数并抛出的客户端桩：物理外呼次数即“零自动重试”的直接观测量。 */
  private static Answer<Object> throwAfterCount(AtomicInteger counter, String message) {
    return invocation -> {
      counter.incrementAndGet();
      throw new IllegalStateException(message);
    };
  }

  /** 剥掉块注释与行注释后的主源码文本，防注释把守卫喂绿。 */
  private static String codeOnly(String relativePathFromMainJava) throws IOException {
    Path path = Paths.get("src/main/java", relativePathFromMainJava);
    if (!Files.exists(path)) {
      return "";
    }
    String source = Files.readString(path, StandardCharsets.UTF_8);
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
  }

  private static int count(String haystack, String needle) {
    int result = 0;
    for (int index = haystack.indexOf(needle);
        index >= 0;
        index = haystack.indexOf(needle, index + 1)) {
      result++;
    }
    return result;
  }
}
