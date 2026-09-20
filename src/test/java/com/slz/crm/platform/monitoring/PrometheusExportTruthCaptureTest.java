package com.slz.crm.platform.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.slz.crm.CrmApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真相采集（TASK-16 步骤 1）：把 {@code /actuator/prometheus} 的<b>实际</b>导出名与 label 键采集到 {@code
 * target/prometheus-export/}，作为 {@code deploy/prometheus/expected-metric-names.txt} 的唯一来源。
 *
 * <p>为什么必须真起上下文：Micrometer→Prometheus 的改名规则（点转下划线、counter 追加 {@code _total}、gauge 按 baseUnit
 * 追加单位后缀）只有注册表本体说了算。TASK-16 修掉的正是"手抄名字导致告警永不触发"，所以名字一律以此处采集结果为准。
 *
 * <p>两个必须的测试装配开关（都是踩过坑才知道的，别删）：
 *
 * <ul>
 *   <li>{@link AutoConfigureObservability}：Boot 3.5 的 {@code ObservabilityContextCustomizerFactory}
 *       默认往测试上下文注入 {@code management.defaults.metrics.export.enabled=false}，把所有 registry
 *       导出关掉——不加这个注解，注入的 MeterRegistry 是 {@code
 *       SimpleMeterRegistry}，采集到的是假真相（生产不受该开关影响，它只作用于测试上下文）。
 *   <li>{@code platform.actuator.protected-enabled=false}：{@code /actuator/prometheus} 在
 *       ActuatorProtectionFilter 的超管名单里，测试无 JWT 会被拦成 401。
 * </ul>
 *
 * <p>重生成方式（改动线程池指标生产端后必须重跑并同步 fixture）：{@code mvn -B -ntp test
 * -Dtest=PrometheusExportTruthCaptureTest}，用 {@code
 * target/prometheus-export/expected-metric-names.txt} 覆盖仓库内 fixture（保留 fixture 头部的来源说明）。
 */
@SpringBootTest(
    classes = CrmApplication.class,
    properties = {
      "rag.vector-store.provider=in-memory",
      "knowledge.storage.provider=in-memory",
      "crm.ai.knowledge-retrieval.mock-enabled=false",
      "spring.ai.dashscope.api-key=sk-prometheus-truth-capture-placeholder",
      "spring.flyway.enabled=false",
      // 采集要拿到裸导出，不能被 actuator 鉴权过滤器拦成 401（生产恒开，仅本测试覆盖）
      "platform.actuator.protected-enabled=false"
    })
@AutoConfigureObservability
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrometheusExportTruthCaptureTest {

  private static final Path OUTPUT_DIR = Path.of("target", "prometheus-export").toAbsolutePath();

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private com.slz.crm.common.properties.ActuatorProtectionProperties actuatorProperties;

  @Test
  void captureActualPrometheusExport() throws Exception {
    assertThat(meterRegistry)
        .as(
            "Boot 3.5 + micrometer-registry-prometheus 必须装配 PrometheusMeterRegistry，实际 MeterRegistry beans="
                + java.util.Arrays.toString(
                    applicationContext.getBeanNamesForType(MeterRegistry.class)))
        .isInstanceOf(PrometheusMeterRegistry.class);

    String registryBody = ((PrometheusMeterRegistry) meterRegistry).scrape();
    String endpointBody =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    Map<String, SortedSet<String>> endpointMeters =
        PrometheusExposition.parseExposition(endpointBody);
    Map<String, SortedSet<String>> registryMeters =
        PrometheusExposition.parseExposition(registryBody);

    // 端点导出 = 注册表 scrape ∪ 服务这次 scrape 自身产生的请求指标；反向包含不成立，故只断言单向。
    assertThat(endpointMeters.keySet())
        .as("端点名集合必须覆盖注册表名集合，否则采集口径不可信")
        .containsAll(registryMeters.keySet());
    assertThat(endpointMeters.keySet())
        .as("线程池拒绝计数必须以 _total 后缀导出（消费端资产的名字以此为准）")
        .anyMatch(name -> name.startsWith("async_") && name.endsWith("_total"));
    assertThat(endpointMeters.keySet())
        .as("ExecutorServiceMetrics 绑定的 executor_* 族必须在导出里（ApplicationRunner 必须已执行）")
        .anyMatch(name -> name.startsWith("executor_"));
    assertThat(endpointMeters.keySet()).hasSizeGreaterThan(50);

    writeArtifacts(endpointBody, endpointMeters);
  }

  /**
   * 抓取侧鉴权事实核验（复用上面同一个缓存上下文，不再启一次）。
   *
   * <p>把 actuator 保护开关临时打开，匿名 {@code GET /actuator/prometheus} 必须被拒。这条断言就是 {@code
   * deploy/prometheus/prometheus.yml} 里"crm-app 这个 target 会停在 DOWN"那句注释的证据， 也是 TASK-16
   * 交接时唯一没被闭合的环节：指标端点该怎么暴露给监控系统是安全口径决策，不在本任务边界里擅自放开。
   */
  @Test
  void anonymousScrapeIsRejectedWhileProtectionIsOn() throws Exception {
    boolean before = actuatorProperties.isProtectedEnabled();
    actuatorProperties.setProtectedEnabled(true);
    try {
      int status =
          mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();
      assertThat(status)
          .as("保护开启时，无 JWT 的 Prometheus 抓取应被 ActuatorProtectionFilter 拒掉")
          .isEqualTo(401);
    } finally {
      actuatorProperties.setProtectedEnabled(before);
    }
  }

  private void writeArtifacts(String body, Map<String, SortedSet<String>> meters)
      throws IOException {
    Files.createDirectories(OUTPUT_DIR);
    Files.writeString(
        OUTPUT_DIR.resolve("actuator-prometheus-raw.txt"), body, StandardCharsets.UTF_8);

    StringBuilder names = new StringBuilder();
    for (Map.Entry<String, SortedSet<String>> entry : meters.entrySet()) {
      names.append(entry.getKey());
      if (!entry.getValue().isEmpty()) {
        names.append(" [").append(String.join(",", entry.getValue())).append(']');
      }
      names.append('\n');
    }
    Files.writeString(
        OUTPUT_DIR.resolve("expected-metric-names.txt"), names, StandardCharsets.UTF_8);

    StringBuilder threadPool = new StringBuilder();
    for (String line : body.split("\n")) {
      String head = line.startsWith("#") ? line.substring(1).strip().replace("TYPE ", "") : line;
      if (head.startsWith("async_") || head.startsWith("executor_")) {
        threadPool.append(line).append('\n');
      }
    }
    Files.writeString(
        OUTPUT_DIR.resolve("thread-pool-lines.txt"), threadPool, StandardCharsets.UTF_8);
    System.out.println("[truth-capture] exported metric families = " + meters.size());
    System.out.println("[truth-capture] output dir = " + OUTPUT_DIR);
    System.out.print("[truth-capture] thread-pool exposition:\n" + threadPool);
  }
}
