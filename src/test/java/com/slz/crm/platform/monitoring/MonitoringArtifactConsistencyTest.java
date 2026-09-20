package com.slz.crm.platform.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.async.PlatformAsyncConfig;
import com.slz.crm.platform.async.TaskExecutorMetricsBinder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.yaml.snakeyaml.Yaml;

/**
 * 监控资产一致性门禁（TASK-16）：{@code deploy/prometheus/} 下消费端引用的每个指标名 / label 都必须等于 {@code
 * /actuator/prometheus} 的<b>实际</b>导出，否则告警永不触发、面板恒 No data。
 *
 * <p>真相源是 {@code deploy/prometheus/expected-metric-names.txt}（由 {@link
 * PrometheusExportTruthCaptureTest} 真起上下文采集，不许手抄）。本类纯 JVM：不起 Spring Boot 上下文、不连 Docker、不访问外网，因此
 * surefire 日常跑必红必绿都可信。
 *
 * <p>为什么不再靠 CI 里的 {@code grep}/{@code test -f}：旧门禁在被测文件里 grep 被测字符串（{@code grep
 * "async.task.rejected" PlatformAsyncConfig.java}），只要代码里还写着错名字，门禁就越绿——这是假绿， 也正是本次要修的 bug
 * 类型。断言只能落在"引用侧 vs 导出侧"，故登记在此。
 */
class MonitoringArtifactConsistencyTest {

  private static final String FIXTURE_REL = "deploy/prometheus/expected-metric-names.txt";
  private static final String ALERT_RULES_REL = "deploy/prometheus/alert-rules.yml";
  private static final String DASHBOARD_REL =
      "deploy/prometheus/grafana-thread-pool-dashboard.json";
  private static final String PROMETHEUS_CONFIG_REL = "deploy/prometheus/prometheus.yml";
  private static final String COMPOSE_REL = "deploy/docker-compose.yml";
  private static final String CI_REL = ".github/workflows/ci.yml";

  /** Prometheus 在采集时注入的 label，不在指标自身 label 集里，但确实是合法引用。 */
  private static final Set<String> SCRAPE_TIME_LABELS = Set.of("__name__", "job", "instance");

  private static final Map<String, SortedSet<String>> FIXTURE = new TreeMap<>();

  private static String fixtureProvenance = "";

  @BeforeAll
  static void loadTruthSource() throws IOException {
    String content = read(FIXTURE_REL);
    fixtureProvenance = content;
    FIXTURE.putAll(PrometheusExposition.parseFixture(content));
  }

  /** fixture 本身必须是真导出的快照，而且不能退化成三行手抄清单。 */
  @Test
  void fixtureIsARealExportSnapshot() {
    assertThat(FIXTURE).as("fixture 条目过少，疑似被手抄裁剪（真相采集一次导出应有数十个族）").hasSizeGreaterThanOrEqualTo(40);
    assertThat(FIXTURE.keySet())
        .as("线程池拒绝计数与 ExecutorServiceMetrics 族必须在 fixture 里")
        .contains(
            "async_task_rejected_total",
            "executor_active_threads",
            "executor_queued_tasks",
            "executor_queue_remaining_tasks",
            "executor_pool_size_threads",
            "executor_pool_max_threads",
            "executor_pool_core_threads",
            "executor_completed_tasks_total");
    assertThat(fixtureProvenance)
        .as("fixture 头部必须写明采集用例，否则无法重生成、也无法证明不是手抄")
        .contains("PrometheusExportTruthCaptureTest");
  }

  /** 告警规则引用的指标名必须全部是实际导出名（裸名 async_task_rejected 这类历史 bug 在此变红）。 */
  @Test
  void alertRulesReferenceExportedMetricNames() throws IOException {
    assertThat(unknownNames(targetsOf(readYaml(ALERT_RULES_REL), ALERT_RULES_REL)))
        .as("alert-rules.yml 引用了 /actuator/prometheus 不导出的指标名")
        .isEmpty();
  }

  /** 面板引用的指标名必须全部是实际导出名（executor_threads_active 这类推断名在此变红）。 */
  @Test
  void dashboardReferencesExportedMetricNames() throws IOException {
    assertThat(unknownNames(targetsOf(readJson(DASHBOARD_REL), DASHBOARD_REL)))
        .as("grafana dashboard 引用了 /actuator/prometheus 不导出的指标名")
        .isEmpty();
  }

  /** label 键也要对上：Counter 的 {@code executor}/{@code policy} 与 executor 族的 {@code name} 不是一回事。 */
  @Test
  void alertRuleSelectorsUseExportedLabelKeys() throws IOException {
    assertSelectorsUseRealLabels(targetsOf(readYaml(ALERT_RULES_REL), ALERT_RULES_REL));
  }

  @Test
  void dashboardSelectorsUseExportedLabelKeys() throws IOException {
    assertSelectorsUseRealLabels(targetsOf(readJson(DASHBOARD_REL), DASHBOARD_REL));
  }

  /** {@code absent()} 一类的"指标缺失"告警必须真能触发：正向着却把值绑成空串（{@code {executor=""}}）的 选择器永远匹不到任何序列，等于恒真告警。 */
  @Test
  void alertRulesHaveNoUnsatisfiableSelectors() throws IOException {
    List<PromTarget> targets = targetsOf(readYaml(ALERT_RULES_REL), ALERT_RULES_REL);
    assertThat(targets).as("alert-rules.yml 至少要有一条规则表达式").isNotEmpty();
    List<String> unsatisfiable = new ArrayList<>();
    for (PromTarget target : targets) {
      PromQlRefs refs = PromQlRefs.analyze(target.expr());
      refs.unsatisfiableSelectors().forEach(s -> unsatisfiable.add(target.source() + " -> " + s));
    }
    assertThat(unsatisfiable).as("空值正向着选择器永远匹不到序列（absent 类规则必须换成真实 label 值或裸族名）").isEmpty();
  }

  /** 面板 legend 的 {@code {{label}}} 必须是该表达式里指标真实带的 label，否则图例恒空。 */
  @Test
  void dashboardLegendsReferenceExportedLabels() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (PromTarget target : targetsOf(readJson(DASHBOARD_REL), DASHBOARD_REL)) {
      if (target.legend().isEmpty()) {
        continue;
      }
      PromQlRefs refs = PromQlRefs.analyze(target.expr());
      Set<String> allowed = allowedLabels(refs);
      for (String label : templateRefs(target.legend())) {
        if (!allowed.contains(label)) {
          offenders.add(
              target.source()
                  + " legend {{"
                  + label
                  + "}} 不在 "
                  + refs.metricNames()
                  + " 的 label 集");
        }
      }
    }
    assertThat(offenders).as("Grafana 图例引用了导出里不存在的 label").isEmpty();
  }

  /**
   * fixture 的反向防呆：不起 Spring Boot，直接用生产代码（{@link PlatformAsyncConfig} + {@link
   * TaskExecutorMetricsBinder}）把线程池指标注册进真的 {@link PrometheusMeterRegistry}， 抓出来的名字必须仍在 fixture
   * 内。改生产端命名而忘记重采 fixture 时，这条会红。
   */
  @Test
  void producerSideNamesRegeneratedFromProductionCodeStayInFixture() throws IOException {
    Map<String, SortedSet<String>> produced = scrapeProducerSideRegistry();
    SortedSet<String> threadPoolFamilies = new TreeSet<>();
    produced.keySet().stream()
        .filter(
            name ->
                name.startsWith("async_")
                    || name.startsWith("executor_")
                    || name.startsWith("platform_"))
        .forEach(threadPoolFamilies::add);
    assertThat(threadPoolFamilies).as("生产端必须至少导出拒绝计数与 executor 族，否则本门禁失去意义").isNotEmpty();
    assertThat(threadPoolFamilies)
        .filteredOn(name -> !FIXTURE.containsKey(name))
        .as("生产端实际导出名不在 fixture 里：fixture 过期，重跑 PrometheusExportTruthCaptureTest 后同步")
        .isEmpty();
    for (String name : threadPoolFamilies) {
      assertThat(FIXTURE.get(name))
          .as("fixture 里 %s 的 label 集不含生产端实际带的 label", name)
          .containsAll(produced.get(name));
    }
  }

  /** prometheus.yml 必须真的去抓 app，并把 alert-rules.yml 挂上：两份产物不能继续当死文件。 */
  @Test
  @SuppressWarnings("unchecked")
  void prometheusScrapeConfigWiresAppAndAlertRules() throws IOException {
    Map<String, Object> config = (Map<String, Object>) readYaml(PROMETHEUS_CONFIG_REL);
    Map<String, Object> global = (Map<String, Object>) config.get("global");
    assertThat(global).as("prometheus.yml 必须有 global 段").isNotNull();
    assertThat(String.valueOf(global.get("evaluation_interval")))
        .as("prometheus.yml 的 global.evaluation_interval 必须是 15s")
        .isEqualTo("15s");
    assertThat(String.valueOf(global.get("scrape_interval")))
        .as("prometheus.yml 的 global.scrape_interval 必须是 15s")
        .isEqualTo("15s");
    assertThat((List<String>) config.get("rule_files"))
        .as("prometheus.yml 必须挂载 alert-rules.yml，否则告警规则永不生效")
        .anyMatch(rule -> rule.endsWith("alert-rules.yml"));
    List<Map<String, Object>> scrapeConfigs =
        (List<Map<String, Object>>) config.get("scrape_configs");
    assertThat(scrapeConfigs).as("prometheus.yml 必须至少一个抓取任务").isNotEmpty();
    Map<String, Object> appJob =
        scrapeConfigs.stream()
            .filter(job -> "crm-app".equals(String.valueOf(job.get("job_name"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("prometheus.yml 缺少 job_name: crm-app 的应用抓取任务"));
    assertThat(String.valueOf(appJob.get("metrics_path"))).isEqualTo("/actuator/prometheus");
    assertThat(String.valueOf(appJob.get("scrape_interval"))).isEqualTo("15s");
    List<Map<String, Object>> staticConfigs =
        (List<Map<String, Object>>) appJob.get("static_configs");
    assertThat(staticConfigs).isNotEmpty();
    assertThat((List<String>) staticConfigs.get(0).get("targets"))
        .as("抓取目标必须是 compose 里的 app 服务")
        .containsExactly("app:8080");
  }

  /** compose 必须真的会起 prometheus，否则前面两份产物依然是死文件。 */
  @Test
  @SuppressWarnings("unchecked")
  void composeServesPrometheusAlongsideApp() throws IOException {
    Map<String, Object> compose = (Map<String, Object>) readYaml(COMPOSE_REL);
    Map<String, Object> services = (Map<String, Object>) compose.get("services");
    assertThat(services.keySet())
        .as("compose 必须同时有 app 与 prometheus")
        .contains("app", "prometheus");
    Map<String, Object> prometheus = (Map<String, Object>) services.get("prometheus");
    assertThat(String.valueOf(prometheus.get("image")))
        .as("prometheus 服务镜像")
        .startsWith("prom/prometheus");
    assertThat(flatten(prometheus.get("volumes")))
        .as("必须把 deploy/prometheus 整目录挂进容器（prometheus.yml 与 alert-rules.yml 都在里面）")
        .anyMatch(mount -> mount.contains(":/etc/prometheus"));
    assertThat(flatten(prometheus.get("command")))
        .as("必须显式声明挂进去的 prometheus.yml 是主配置，否则镜像默认配置不会评估本仓规则")
        .anyMatch(arg -> arg.equals("--config.file=/etc/prometheus/prometheus.yml"));
    assertThat(flatten(prometheus.get("ports"))).anyMatch(port -> port.contains("9090"));
  }

  /** CI 门禁必须换成真校验：不许再退回去 grep 被测文件 / 只 test -f。 */
  @Test
  void ciGateValidatesArtifactsInsteadOfGreppingSource() throws IOException {
    String gate = jobBlock(read(CI_REL), "thread-pool-metrics-gate");
    assertThat(gate).as("ci.yml 里必须还有 thread-pool-metrics-gate 这个 job").isNotEmpty();
    assertThat(gate).as("门禁必须用 promtool 真校验告警规则").contains("promtool").contains("check rules");
    assertThat(gate)
        .as("门禁必须跑 MonitoringArtifactConsistencyTest（纯 JVM 名字对账）")
        .contains("MonitoringArtifactConsistencyTest");
    assertThat(gate)
        .as("旧的假门禁（在被测文件里 grep 被测字符串 / 只 test -f）不得残留")
        .doesNotContain("grep -r \"async.task.rejected\"")
        .doesNotContain("test -f deploy/prometheus");
  }

  /** 抽取器自身的防呆：函数名、聚合分组、Duration、字符串里的名字都不能误判。 */
  @Test
  void promQlExtractorSeparatesMetricsFromLabelsAndFunctions() {
    PromQlRefs refs =
        PromQlRefs.analyze(
            "sum by (executor) (increase(async_task_rejected_total{application=\"crm\"}[1h]))"
                + " / rate(executor_completed_tasks_total{name=\"platform-stream-chat\"}[5m] offset 10m)"
                + " or {__name__=~\"executor_active_threads|executor_queued_tasks\"}");
    assertThat(refs.metricNames())
        .containsExactlyInAnyOrder(
            "async_task_rejected_total",
            "executor_completed_tasks_total",
            "executor_active_threads",
            "executor_queued_tasks");
    assertThat(refs.labelNames()).contains("application", "name", "executor");
    assertThat(refs.unsatisfiableSelectors())
        .as("by (executor) 里的 executor 是分组标签，不该被当成指标")
        .isEmpty();
    PromQlRefs broken = PromQlRefs.analyze("absent(async_task_rejected{executor=\"\"})");
    assertThat(broken.unsatisfiableSelectors()).hasSize(1);
    assertThat(broken.unsatisfiableSelectors().get(0).label()).isEqualTo("executor");
  }

  private static Map<String, SortedSet<String>> scrapeProducerSideRegistry() throws IOException {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(MeterRegistry.class, () -> registry);
      context.register(PlatformAsyncConfig.class);
      context.refresh();
      List<ThreadPoolTaskExecutor> executors =
          new ArrayList<>(context.getBeansOfType(ThreadPoolTaskExecutor.class).values());
      assertThat(executors).as("平台线程池 bean 应当齐备").hasSizeGreaterThanOrEqualTo(6);
      new TaskExecutorMetricsBinder(registry, executors)
          .run(new DefaultApplicationArguments(new String[0]));
      return PrometheusExposition.parseExposition(registry.scrape());
    } finally {
      registry.close();
    }
  }

  private static void assertSelectorsUseRealLabels(List<PromTarget> targets) {
    List<String> offenders = new ArrayList<>();
    for (PromTarget target : targets) {
      PromQlRefs refs = PromQlRefs.analyze(target.expr());
      for (PromQlRefs.Selector selector : refs.selectors()) {
        if (SCRAPE_TIME_LABELS.contains(selector.label())) {
          continue;
        }
        // 裸选择器 {__name__=~"a|b"} 没有宿主指标名，label 合法性按该表达式引到的所有族并集判。
        SortedSet<String> labels =
            selector.metric().isEmpty()
                ? labelsOf(refs.metricNames())
                : FIXTURE.get(selector.metric());
        if (labels == null) {
          offenders.add(
              target.source() + " 未知指标 " + selector.metric() + " 上的 label " + selector.label());
        } else if (!labels.contains(selector.label())) {
          offenders.add(
              target.source()
                  + " "
                  + selector.metric()
                  + " 没有 label "
                  + selector.label()
                  + "，实际有 "
                  + labels);
        }
      }
    }
    assertThat(offenders).as("消费端引用了导出里不存在的 label 键").isEmpty();
  }

  private static SortedSet<String> labelsOf(Set<String> names) {
    SortedSet<String> labels = new TreeSet<>();
    for (String name : names) {
      SortedSet<String> actual = FIXTURE.get(name);
      if (actual != null) {
        labels.addAll(actual);
      }
    }
    return labels;
  }

  private static Set<String> unknownNames(List<PromTarget> targets) {
    Set<String> unknown = new LinkedHashSet<>();
    for (PromTarget target : targets) {
      for (String name : PromQlRefs.analyze(target.expr()).metricNames()) {
        if (!FIXTURE.containsKey(name)) {
          unknown.add(name);
        }
      }
    }
    return unknown;
  }

  private static Set<String> allowedLabels(PromQlRefs refs) {
    Set<String> allowed = new TreeSet<>(SCRAPE_TIME_LABELS);
    for (String name : refs.metricNames()) {
      SortedSet<String> labels = FIXTURE.get(name);
      if (labels != null) {
        allowed.addAll(labels);
      }
    }
    allowed.addAll(refs.labelNames());
    return allowed;
  }

  private static List<String> templateRefs(String legend) {
    List<String> refs = new ArrayList<>();
    int i = 0;
    while (i < legend.length()) {
      int open = legend.indexOf("{{", i);
      if (open < 0) {
        return refs;
      }
      int close = legend.indexOf("}}", open + 2);
      if (close < 0) {
        return refs;
      }
      refs.add(legend.substring(open + 2, close).strip().replaceFirst("^\\/", ""));
      i = close + 2;
    }
    return refs;
  }

  /** 任何节点树里带 {@code expr} 的条目：告警规则和 Grafana target 共用一套抽取。 */
  private static List<PromTarget> targetsOf(Object tree, String source) {
    List<PromTarget> targets = new ArrayList<>();
    collectTargets(tree, source, targets);
    assertThat(targets).as(source + " 至少要有一个表达式").isNotEmpty();
    return targets;
  }

  private static void collectTargets(Object node, String source, List<PromTarget> out) {
    if (node instanceof Map<?, ?> map) {
      Object expr = map.get("expr");
      if (expr instanceof String text) {
        Object legend = map.get("legendFormat");
        out.add(new PromTarget(source, text, legend instanceof String value ? value : ""));
      }
      map.values().forEach(child -> collectTargets(child, source, out));
    } else if (node instanceof List<?> list) {
      list.forEach(child -> collectTargets(child, source, out));
    }
  }

  private static List<String> flatten(Object value) {
    List<String> flat = new ArrayList<>();
    if (value instanceof List<?> list) {
      list.forEach(item -> flat.add(String.valueOf(item)));
    } else if (value != null) {
      flat.add(String.valueOf(value));
    }
    return flat;
  }

  /** 取 ci.yml 里某个 job 的整块文本（从 job 名到下一个二级键）。 */
  private static String jobBlock(String yaml, String job) {
    String[] lines = yaml.split("\n");
    StringBuilder block = new StringBuilder();
    boolean inside = false;
    for (String line : lines) {
      if (line.startsWith("  " + job + ":")) {
        inside = true;
        block.append(line).append('\n');
        continue;
      }
      if (inside) {
        if (line.startsWith("  ")
            && !line.startsWith("   ")
            && !line.trim().isEmpty()
            && line.charAt(2) != ' ') {
          break;
        }
        block.append(line).append('\n');
      }
    }
    return block.toString();
  }

  private static Object readYaml(String relativeFromRoot) throws IOException {
    return new Yaml().load(read(relativeFromRoot));
  }

  private static Object readJson(String relativeFromRoot) throws IOException {
    return new ObjectMapper().readValue(read(relativeFromRoot), Map.class);
  }

  private static String read(String relativeFromRoot) throws IOException {
    return Files.readString(locator(relativeFromRoot), StandardCharsets.UTF_8);
  }

  private static Path locator(String relativeFromRoot) {
    for (int up = 0; up <= 3; up++) {
      StringBuilder prefix = new StringBuilder();
      for (int level = 0; level < up; level++) {
        prefix.append("../");
      }
      Path candidate =
          Path.of(prefix.append(relativeFromRoot).toString()).toAbsolutePath().normalize();
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到 " + relativeFromRoot + "；请在仓库根目录运行 `mvn -B -ntp test`，不要从子目录直跑单测");
  }

  /** 一条来自消费端资产的 PromQL 引用：所在文件、表达式、图例模板。 */
  private record PromTarget(String source, String expr, String legend) {}
}
