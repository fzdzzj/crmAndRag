package com.slz.crm.platform.health;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 启动期依赖 Fail-Fast 校验（TASK-10）。
 *
 * <p>在容器 refresh 期（{@code @PostConstruct}，早于 Web 端口监听）逐个探测关键依赖，任一必需依赖不是 UP 就抛 {@link
 * IllegalStateException} 中断启动。此前的行为是静默降级：Qdrant/MinIO 不可达只记一行 warn，应用照常对外服务， 错误被推到第一次检索/上传时才炸。
 *
 * <p>三条设计约定：
 *
 * <ul>
 *   <li><b>与 {@code /actuator/health} 同源</b>：探测走 {@link HealthContributorRegistry} 按组件名取指示器（{@code
 *       db} / {@code vectorStore} / {@code minio}，即 readiness 分组里那三个名字）， 校验结论与健康端点不会出现"一端说 DOWN、一端说
 *       UP"的分歧；
 *   <li><b>预算硬截断</b>：每个依赖单独一线程，超过 {@code app.dependency.health-timeout-ms} 即判失败， 慢依赖（Hikari 默认 30s
 *       连接超时、gRPC 重试）不得把启动拖过 3s；
 *   <li><b>按 provider 门控</b>：{@code rag.vector-store.provider=in-memory} 或 {@code
 *       knowledge.storage.provider=in-memory} 时对应组件不参与校验（H2/内存回退的测试与本地场景不该被生产依赖挡住启动）。
 * </ul>
 *
 * <p>{@link ObjectProvider} 而非直接注入 {@link HealthContributorRegistry}：该注册表由 actuator 端点自动配置提供，
 * 端点被关闭时它不存在——此时告警放行， 不新增一种"因为运维摘掉 actuator 而起不来"的故障面。
 */
@Component
public class FailFastValidator {

  /** 组件名与 readiness 分组（{@code management.endpoint.health.group.readiness.include}）逐字一致。 */
  static final String DB_COMPONENT = "db";

  static final String VECTOR_STORE_COMPONENT = "vectorStore";
  static final String MINIO_COMPONENT = "minio";
  private static final Logger log = LoggerFactory.getLogger(FailFastValidator.class);
  private static final String QDRANT_PROVIDER = "qdrant";
  private static final String MINIO_PROVIDER = "minio";

  private final ObjectProvider<HealthContributorRegistry> registryProvider;
  private final String vectorStoreProvider;
  private final String storageProvider;
  private final long dependencyTimeoutMs;

  public FailFastValidator(
      ObjectProvider<HealthContributorRegistry> registryProvider,
      @Value("${rag.vector-store.provider:qdrant}") String vectorStoreProvider,
      @Value("${knowledge.storage.provider:minio}") String storageProvider,
      @Value("${app.dependency.health-timeout-ms:3000}") long dependencyTimeoutMs) {
    this.registryProvider = registryProvider;
    this.vectorStoreProvider = vectorStoreProvider;
    this.storageProvider = storageProvider;
    this.dependencyTimeoutMs = dependencyTimeoutMs;
  }

  /** 依赖清单：必需组件（MySQL）恒校验，Qdrant/MinIO 由 provider 配置决定是否必需。 */
  private List<Dependency> dependencies() {
    return List.of(
        new Dependency("MySQL 数据库", DB_COMPONENT, true),
        new Dependency(
            "Qdrant 向量库",
            VECTOR_STORE_COMPONENT,
            QDRANT_PROVIDER.equalsIgnoreCase(vectorStoreProvider.strip())),
        new Dependency(
            "MinIO 对象存储",
            MINIO_COMPONENT,
            MINIO_PROVIDER.equalsIgnoreCase(storageProvider.strip())));
  }

  @PostConstruct
  public void validateDependencies() {
    HealthContributorRegistry registry = registryProvider.getIfAvailable();
    if (registry == null) {
      log.warn("fail-fast skipped elapsedMs=0 reason=HealthContributorRegistry-absent");
      return;
    }
    List<Dependency> dependencies = dependencies();
    long start = System.nanoTime();
    try {
      for (Dependency dependency : dependencies) {
        validate(registry, dependency);
      }
    } catch (RuntimeException exception) {
      log.error(
          "fail-fast validation failed elapsedMs={} budgetMs={} reason={}",
          elapsedMs(start),
          dependencyTimeoutMs,
          exception.getMessage());
      throw exception;
    }
    log.info(
        "fail-fast validation passed elapsedMs={} budgetMs={} validated={}",
        elapsedMs(start),
        dependencyTimeoutMs,
        dependencies.stream().filter(Dependency::required).map(Dependency::component).toList());
  }

  private void validate(HealthContributorRegistry registry, Dependency dependency) {
    if (!dependency.required()) {
      log.info("fail-fast skipped component={} reason=provider-disabled", dependency.component());
      return;
    }
    HealthIndicator indicator = asIndicator(registry.getContributor(dependency.component()));
    if (indicator == null) {
      throw new IllegalStateException(
          dependency.displayName()
              + "健康指示器未装配：component="
              + dependency.component()
              + " 不在 HealthContributorRegistry 中（客户端 Bean 未创建或该健康组件被关闭），禁止带病启动");
    }
    Health health = probeWithinBudget(indicator, dependency);
    Status status = health.getStatus();
    if (Status.UP.equals(status)) {
      return;
    }
    throw new IllegalStateException(
        dependency.displayName()
            + (Status.DOWN.equals(status) ? "不可达" : "状态异常")
            + "：component="
            + dependency.component()
            + ", status="
            + status.getCode()
            + ", details="
            + health.getDetails());
  }

  /** 单次探测包在独立线程里跑，超时即判失败——指示器本身没有超时能力（Hikari 30s、MinIO 无超时）。 */
  private Health probeWithinBudget(HealthIndicator indicator, Dependency dependency) {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "fail-fast-" + dependency.component());
              thread.setDaemon(true);
              return thread;
            });
    Future<Health> future = executor.submit(indicator::health);
    try {
      return future.get(dependencyTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      throw new IllegalStateException(
          dependency.displayName()
              + "连接失败：健康检查超时 "
              + dependencyTimeoutMs
              + "ms 未响应，component="
              + dependency.component(),
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          dependency.displayName() + "连接失败：健康检查被中断，component=" + dependency.component(), exception);
    } catch (ExecutionException exception) {
      throw new IllegalStateException(
          dependency.displayName()
              + "连接失败：健康检查抛出异常，component="
              + dependency.component()
              + "，cause="
              + exception.getCause(),
          exception);
    } finally {
      executor.shutdownNow();
    }
  }

  private static HealthIndicator asIndicator(HealthContributor contributor) {
    return contributor instanceof HealthIndicator indicator ? indicator : null;
  }

  private static long elapsedMs(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  /** 一个待校验依赖：面向人的组件名、actuator 组件名、在本次配置下是否必需。 */
  private record Dependency(String displayName, String component, boolean required) {}
}
