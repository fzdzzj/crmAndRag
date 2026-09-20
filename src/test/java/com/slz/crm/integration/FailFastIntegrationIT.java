package com.slz.crm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.slz.crm.CrmApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 真启动链 Fail-Fast 验证（TASK-10 AC1）。
 *
 * <p>用 {@link SpringApplicationBuilder} 拉起完整应用（与 {@code mvn spring-boot:run} 同一条装配链），
 * 把向量库指向一个必然拒绝连接的端口，断言：
 *
 * <ul>
 *   <li>启动以失败收场，且失败原因指名 "Qdrant 向量库不可达" —— 不是下游运行期的静默降级；
 *   <li>失败发生在上下文 refresh 期（{@code @PostConstruct}），Web 服务从未起来，进程不会带病对外服务。
 * </ul>
 *
 * <p>{@code knowledge.qdrant.initialize-on-startup=false} 是为了把断言锁定在本次新增的 Fail-Fast 探测上： Qdrant
 * 集合初始化里 已有的 3 次指数退避重试（属禁改清单外的 Lane B 代码）最坏可达数十秒， 若开着会把"探测被截断"的耗时归因混进既有多次重试。 单次依赖 ≤3s 的时间预算由 {@code
 * FailFastValidatorTest#shouldCutOffHangingProbeWithinTimeoutBudget} 与 {@code
 * scripts/fail-fast-gate.sh} 分别按 JVM 口径和进程口径守住，本类只守"归因正确 + 不挂死"。
 */
class FailFastIntegrationIT {

  @Test
  void startupShouldFailWithQdrantReasonWhenVectorStoreIsUnreachable() {
    // 走 run(args) 而不是 builder.properties()：后者是 defaultProperties，优先级低于 application.yml，
    // 会被 knowledge.qdrant.host 等 YAML 值盖掉；命令行 args 是最高的 commandLineArgs 源。
    String[] args = {
      "--spring.flyway.enabled=false",
      // 离线友好覆盖，与 ApplicationContextSmokeTest 保持同一组开关
      "--crm.ai.knowledge-retrieval.mock-enabled=false",
      "--spring.ai.dashscope.api-key=sk-fail-fast-placeholder",
      // 被验证项：provider 声明用 Qdrant，而端点指向必然拒绝连接的端口
      "--rag.vector-store.provider=qdrant",
      "--knowledge.qdrant.host=127.0.0.1",
      "--knowledge.qdrant.port=1",
      "--knowledge.qdrant.initialize-on-startup=false",
      // MinIO 走内存回退，确保失败唯一归因到 Qdrant
      "--knowledge.storage.provider=in-memory",
      "--server.port=0"
    };

    long start = System.nanoTime();
    Throwable failure =
        catchThrowable(
            () -> {
              ConfigurableApplicationContext context =
                  new SpringApplicationBuilder(CrmApplication.class)
                      .web(WebApplicationType.SERVLET)
                      .bannerMode(Banner.Mode.OFF)
                      .profiles("test")
                      .build()
                      .run(args);
              context.close();
            });
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(failure).as("Qdrant 不可达时启动必须失败").isNotNull();
    assertThat(messagesOf(failure))
        .anyMatch(message -> message.contains("Qdrant 向量库不可达"))
        .anyMatch(message -> message.contains("failFastValidator"));
    // 挂死（未截断探测）与快速失败的分界：整轮 refresh 含 JVM/Spring 装配也不该越过 60s
    assertThat(elapsedMs).as("整轮启动耗时 %sms（阈值 60000ms）", elapsedMs).isLessThan(60_000L);
  }

  private List<String> messagesOf(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current) + " | " + current.getClass().getName());
      if (current.getCause() == current) {
        break;
      }
    }
    return messages;
  }
}
