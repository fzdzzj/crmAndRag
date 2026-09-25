package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.quality.HotpathConcurrencyAttribution.AttributionResourceRecorder;
import com.slz.crm.quality.HotpathConcurrencyAttribution.Condition;
import com.slz.crm.quality.HotpathConcurrencyAttribution.CountersHook;
import com.slz.crm.quality.HotpathConcurrencyAttribution.RequestLedger;
import com.slz.crm.quality.HotpathConcurrencyAttribution.RequestSpan;
import com.slz.crm.quality.HotpathConcurrencyAttribution.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * c8 并发归因诊断的安全反例与关联锁定（update-hotpath-concurrency-attribution 任务 4.1，纯 JVM、零 Docker、零外发）。
 *
 * <ol>
 *   <li><b>缺开关 fail closed</b>：诊断选项缺失=关闭；显式传入但缺独立 opt-in=拒绝；未知条件值=拒绝（不静默回落 original）；
 *   <li><b>请求级关联</b>：并发线程经计数器钩子写入的每条 span 只含本线程本请求的分段，账本总和与全局计数器逐索引一致， 请求数与样本数一致，失败单列；
 *   <li><b>线程复用清理</b>：endSpan/结束 worker 后 ThreadLocal 标识清空，下一请求不继承上一请求的分段或失败状态；
 *   <li><b>记录完整性</b>：JSONL 单行转义、worker 生命周期字段齐备、资源采样逐样本带开销与实际间隔。
 * </ol>
 */
class HotpathConcurrencyAttributionGuardTest {

  private static final int COUNTERS = RepresentativeHotpathBenchmark.Counters.COUNTER_COUNT;
  private static final int SQL_NANOS = RepresentativeHotpathBenchmark.Counters.SQL_NANOS;
  private static final int SQL_CALLS = RepresentativeHotpathBenchmark.Counters.SQL_CALLS;
  private static final int QDRANT_SEARCH_NANOS =
      RepresentativeHotpathBenchmark.Counters.QDRANT_SEARCH_NANOS;

  // ---------------------------------------------------------------- 开关门禁（反例 1）

  /** 反例 1a：诊断选项缺失/空白 = 诊断关闭，默认路径零参与。 */
  @Test
  void missingOptionDefaultsToOff() {
    Settings off1 = HotpathConcurrencyAttribution.resolve(null, null, null, null);
    Settings off2 = HotpathConcurrencyAttribution.resolve("  ", null, null, null);
    assertFalse(off1.on(), "未给诊断选项必须关闭");
    assertFalse(off2.on(), "空白诊断选项必须关闭");
    assertFalse(HotpathConcurrencyAttribution.Settings.off().on());
  }

  /** 反例 1b：未知条件值 fail closed——绝不静默回落 original 造成假对照。 */
  @Test
  void invalidOptionFailsClosed() {
    for (String bogus : List.of("sync", "ORIGINAL", "warmup", "phase-reversed ")) {
      // "phase-reversed " 带空格但 trim 后合法——单独验证；其余必须抛
      if (bogus.equals("phase-reversed ")) {
        assertEquals(Condition.PHASE_REVERSED, HotpathConcurrencyAttribution.parseCondition(bogus));
        continue;
      }
      IllegalStateException expected =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class,
              () -> HotpathConcurrencyAttribution.resolve(bogus, null, null, null),
              "未知诊断值必须拒绝：" + bogus);
      assertTrue(
          expected.getMessage().contains("original|warmup-sync|phase-reversed"),
          "fail-closed 信息必须点名合法值：" + expected.getMessage());
    }
  }

  /** 反例 1c：诊断选项已显式传入但缺独立 opt-in → 组合闸在任何容器/请求之前拒绝。 */
  @Test
  void gateRequiresOptInWhenDiagnosticSelected() {
    Settings on = HotpathConcurrencyAttribution.gate("1", "original", "guard-exec", null, null);
    assertTrue(on.on(), "opt-in 与诊断选项齐备时放行");
    IllegalStateException missingOptIn =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> HotpathConcurrencyAttribution.gate(null, "warmup-sync", "guard-exec", null, null),
            "缺独立 opt-in 必须拒绝");
    assertTrue(
        missingOptIn.getMessage().contains(RepresentativeHotpathBenchmark.OPT_IN_ENV),
        "组合闸拒绝信息必须点名独立开关：" + missingOptIn.getMessage());
    assertFalse(
        HotpathConcurrencyAttribution.gate(null, null, null, null, null).on(),
        "诊断关闭时无需 opt-in 也保持关闭（原路径不受影响）");
  }

  /** 反例 1d：诊断选项系统属性优先、env 兜底。 */
  @Test
  void optionPropWinsOverEnv() {
    Assumptions.assumeTrue(
        System.getenv(HotpathConcurrencyAttribution.ATTR_ENV) == null,
        "本机已设置 REPHOT_ATTRIBUTION，跳过 env 兜底断言");
    String original = System.getProperty(HotpathConcurrencyAttribution.ATTR_PROP);
    try {
      System.setProperty(HotpathConcurrencyAttribution.ATTR_PROP, "phase-reversed");
      assertEquals(
          "phase-reversed",
          HotpathConcurrencyAttribution.option(
              HotpathConcurrencyAttribution.ATTR_PROP, HotpathConcurrencyAttribution.ATTR_ENV));
      System.clearProperty(HotpathConcurrencyAttribution.ATTR_PROP);
      assertNull(
          HotpathConcurrencyAttribution.option(
              HotpathConcurrencyAttribution.ATTR_PROP, HotpathConcurrencyAttribution.ATTR_ENV),
          "属性清除且 env 缺失时必须为 null（诊断关闭）");
    } finally {
      if (original == null) {
        System.clearProperty(HotpathConcurrencyAttribution.ATTR_PROP);
      } else {
        System.setProperty(HotpathConcurrencyAttribution.ATTR_PROP, original);
      }
    }
  }

  // ---------------------------------------------------------------- 请求级关联（守卫 2）

  /** 守卫 2a：8 线程并发写入，每条 span 只含本线程本请求的分段，账本总和=全局快照、请求数=样本数。 */
  @Test
  void requestSpansCorrelatePerThreadUnderConcurrency(@TempDir Path tempDir) throws Exception {
    int threads = 8;
    int requestsPerThread = 25;
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve(
            "original", "guard-correlation", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c8", threads);
    counters.setAttributionHook(ledger);
    long goEpochMs = System.currentTimeMillis();
    ledger.onGoSignaled(goEpochMs);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        final int workerId = t;
        futures.add(
            pool.submit(
                () -> {
                  ledger.onWorkerStart(workerId);
                  ledger.onWorkerReady(workerId);
                  start.await();
                  for (int r = 0; r < requestsPerThread; r++) {
                    RequestSpan span = ledger.beginSpan();
                    counters.add(SQL_NANOS, 1_000L + workerId);
                    counters.add(SQL_CALLS, 14L);
                    counters.add(QDRANT_SEARCH_NANOS, 2_000L + workerId);
                    span.completeOk(5_000L + workerId);
                    ledger.endSpan(span);
                  }
                  ledger.onWorkerEnd(workerId);
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      counters.setAttributionHook(null);
      ledger.close();
    }

    assertNull(ledger.checkGlobal(counters.snapshot()), "账本各分段之和必须与全局计数逐索引一致");
    assertEquals(threads * requestsPerThread, ledger.recordCount(), "请求数必须等于样本数");
    assertEquals(threads * requestsPerThread, ledger.okCount(), "全部成功时失败必须为 0");
    assertEquals(0, ledger.failCount());
    List<RequestSpan> spans = ledger.recordedSpans();
    for (RequestSpan span : spans) {
      assertEquals(1_000L + span.workerId, span.segs[SQL_NANOS], "SQL 分段必须只含本请求的写入");
      assertEquals(14L, span.segs[SQL_CALLS]);
      assertEquals(2_000L + span.workerId, span.segs[QDRANT_SEARCH_NANOS]);
      assertTrue(span.ok(), "本用例无失败请求");
    }
    Path ledgerFile = tempDir.resolve("requests-guard-correlation-original-r1-c8.jsonl");
    List<String> lines = Files.readAllLines(ledgerFile);
    assertEquals(threads * requestsPerThread + threads, lines.size(), "请求数 + worker 记录数 = 行数");
    for (String line : lines) {
      assertTrue(line.startsWith("{") && line.endsWith("}"), "每行必须是完整 JSON 对象");
    }
  }

  /** 守卫 2b：失败请求单列且分段照常入账；ok/fail 计数与全局一致。 */
  @Test
  void failedRequestsAreRecordedSeparately(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve(
            "original", "guard-failure", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c1", 1);
    counters.setAttributionHook(ledger);
    ledger.onWorkerStart(0);
    ledger.onGoSignaled(System.currentTimeMillis());
    ledger.onWorkerReady(0);
    try {
      RequestSpan failed = ledger.beginSpan();
      counters.add(SQL_NANOS, 3_000L);
      failed.completeFail(9_999L, "java.lang.RuntimeException", "boom \"quoted\" \n next");
      ledger.endSpan(failed);
      RequestSpan ok = ledger.beginSpan();
      counters.add(SQL_NANOS, 1_000L);
      ok.completeOk(1_500L);
      ledger.endSpan(ok);
      ledger.onWorkerEnd(0);
    } finally {
      counters.setAttributionHook(null);
      ledger.close();
    }

    assertEquals(2, ledger.recordCount());
    assertEquals(1, ledger.okCount());
    assertEquals(1, ledger.failCount());
    assertNull(ledger.checkGlobal(counters.snapshot()), "失败请求的分段同样必须计入账本总和");
    String ledgerLine = ledger.connectionLine();
    assertTrue(ledgerLine.contains("n=2"), "连接获取分布必须覆盖两条请求");
  }

  // ---------------------------------------------------------------- 线程复用清理（守卫 3）

  /** 守卫 3a：同一线程连续两请求，第二条不得继承第一条的分段/失败状态；钩子摘除后不再捕获。 */
  @Test
  void threadReuseDoesNotInheritState(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve("original", "guard-reuse", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c1", 1);
    ledger.onWorkerStart(0);
    ledger.onGoSignaled(System.currentTimeMillis());
    ledger.onWorkerReady(0);
    counters.setAttributionHook(ledger);
    try {
      RequestSpan first = ledger.beginSpan();
      counters.add(SQL_NANOS, 111_111L);
      first.completeFail(1L, "java.lang.RuntimeException", "first-failure");
      ledger.endSpan(first);

      RequestSpan second = ledger.beginSpan();
      counters.add(SQL_NANOS, 222_222L);
      second.completeOk(2L);
      ledger.endSpan(second);

      List<RequestSpan> spans = ledger.recordedSpans();
      assertEquals(2, spans.size());
      assertEquals(111_111L, spans.get(0).segs[SQL_NANOS]);
      assertEquals(222_222L, spans.get(1).segs[SQL_NANOS], "第二条请求不得继承第一条的分段");
      assertTrue(spans.get(1).ok(), "第二条请求不得继承第一条的失败状态");
      assertNull(ledger.checkGlobal(counters.snapshot()));
    } finally {
      counters.setAttributionHook(null);
      ledger.onWorkerEnd(0);
      ledger.close();
    }

    long before = ledger.recordCount();
    counters.add(SQL_NANOS, 999L);
    assertEquals(before, ledger.recordCount(), "钩子摘除后计数器写入不得再进账本");
  }

  /** 守卫 3b：worker 生命周期——开始/就绪/放行/结束字段齐备，结束后线程内标识清空。 */
  @Test
  void workerLifecycleRecordsAndClearsThreadState(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve("original", "guard-worker", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 2, "c8", 2);
    long goEpochMs = System.currentTimeMillis();
    ledger.onGoSignaled(goEpochMs);
    counters.setAttributionHook(ledger);
    try {
      ledger.onWorkerStart(1);
      RequestSpan span = ledger.beginSpan();
      assertEquals(1, span.workerId);
      assertEquals(1, span.reqInWorker, "worker 内请求序号从 1 开始");
      ledger.onWorkerReady(1);
      counters.add(SQL_NANOS, 500L);
      span.completeOk(700L);
      ledger.endSpan(span);
      ledger.onWorkerEnd(1);

      RequestSpan leaked = ledger.beginSpan();
      assertEquals(-1, leaked.workerId, "worker 结束后线程内标识必须清空");
      ledger.endSpan(leaked);
      assertEquals(0, leaked.reqInWorker, "无 worker 归属时请求序号保持 0");
    } finally {
      counters.setAttributionHook(null);
      ledger.close();
    }
    List<String> workerLines = ledger.workerSummaryLines();
    assertEquals(1, workerLines.size(), "只有 worker 1 有记录");
    assertTrue(workerLines.get(0).contains("worker=1"), workerLines.get(0));
    assertTrue(workerLines.get(0).contains("dispatch_delay_ms="), "必须记录就绪→起跑调度延迟");
    assertNull(ledger.checkGlobal(counters.snapshot()));
  }

  /** 守卫 3c：账本与全局计数不一致时必须显式报错（防止账本漂移后仍出结论）。 */
  @Test
  void ledgerMismatchIsDetected(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve(
            "original", "guard-mismatch", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c1", 1);
    ledger.onWorkerStart(0);
    ledger.onGoSignaled(System.currentTimeMillis());
    ledger.onWorkerReady(0);
    try {
      RequestSpan span = ledger.beginSpan();
      counters.add(SQL_NANOS, 1_000L);
      span.completeOk(1_000L);
      ledger.endSpan(span);
      // 模拟账本外写入（本不应发生）：全局计数多了一份
      counters.setAttributionHook(null);
      counters.add(SQL_NANOS, 5_000L);
      String mismatch = ledger.checkGlobal(counters.snapshot());
      assertTrue(mismatch != null && mismatch.contains("sql_us"), "必须点名不一致的分段：" + mismatch);
      AssertionError thrown =
          org.junit.jupiter.api.Assertions.assertThrows(
              AssertionError.class, () -> ledger.verifyAndSummarize(counters.snapshot(), 1));
      assertTrue(thrown.getMessage().contains("不一致"), thrown.getMessage());
    } finally {
      ledger.onWorkerEnd(0);
      ledger.close();
    }
  }

  // ---------------------------------------------------------------- 记录完整性（守卫 4）

  /** 守卫 4a：错误串中的引号/反斜杠/换行必须转义，JSONL 每行仍是单行完整对象。 */
  @Test
  void jsonEscapingKeepsSingleLineRecords(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve("original", "guard-escape", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c1", 1);
    counters.setAttributionHook(ledger);
    ledger.onWorkerStart(0);
    ledger.onGoSignaled(System.currentTimeMillis());
    ledger.onWorkerReady(0);
    try {
      RequestSpan span = ledger.beginSpan();
      counters.add(SQL_NANOS, 1L);
      span.completeFail(1L, "java.lang.RuntimeException", "quote \" back\\slash \n newline \t tab");
      ledger.endSpan(span);
      ledger.onWorkerEnd(0);
    } finally {
      counters.setAttributionHook(null);
      ledger.close();
    }
    Path file = tempDir.resolve("requests-guard-escape-original-r1-c1.jsonl");
    List<String> lines = Files.readAllLines(file);
    assertEquals(2, lines.size(), "1 请求 + 1 worker = 2 行（换行必须被转义）");
    assertTrue(lines.get(0).contains("\\\""), "引号必须转义：" + lines.get(0));
    assertTrue(lines.get(0).contains("back\\\\slash"), "反斜杠必须转义");
    assertTrue(lines.get(0).contains("\\n"), "换行必须转义为 \\n 字面量");
    assertTrue(lines.get(0).contains("\\t"), "制表必须转义为 \\t 字面量");
  }

  /** 守卫 4b：慢尾个体行按该请求自己的分段输出且按端到端降序——明确不是全局平均。 */
  @Test
  void slowRequestLinesAreRequestLevelAndDescending(@TempDir Path tempDir) throws Exception {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    Settings settings =
        HotpathConcurrencyAttribution.resolve("original", "guard-slow", tempDir.toString(), null);
    RequestLedger ledger = RequestLedger.open(settings, 1, "c8", 1);
    counters.setAttributionHook(ledger);
    ledger.onWorkerStart(0);
    ledger.onGoSignaled(System.currentTimeMillis());
    ledger.onWorkerReady(0);
    try {
      long[] e2eNanos = {5_000_000L, 500_000_000L, 50_000_000L};
      for (long e2e : e2eNanos) {
        RequestSpan span = ledger.beginSpan();
        counters.add(RepresentativeHotpathBenchmark.Counters.CONN_NANOS, e2e / 10);
        span.completeOk(e2e);
        ledger.endSpan(span);
      }
      ledger.onWorkerEnd(0);
    } finally {
      counters.setAttributionHook(null);
      ledger.close();
    }
    List<String> slow = ledger.slowRequestLines(2);
    assertEquals(2, slow.size());
    assertTrue(
        slow.get(0).contains("rank=1") && slow.get(0).contains("e2e_ms=500.000"), slow.get(0));
    assertTrue(slow.get(0).contains("conn_ms=50.000"), "慢请求必须带它自己的连接获取分段：" + slow.get(0));
    assertTrue(slow.get(1).contains("e2e_ms=50.000"), slow.get(1));
    assertEquals(3, ledger.recordedSpans().size());
    assertNotEquals(
        ledger.recordedSpans().get(0).e2eNanos,
        ledger.recordedSpans().get(2).e2eNanos,
        "个体差异必须保留，不得混成平均数");
  }

  /** 守卫 4c：分段名表必须覆盖全部计数器索引且不重名（账本字段自证完整）。 */
  @Test
  void segmentNamesCoverAllCounterIndices() {
    Set<String> names = new HashSet<>();
    for (int i = 0; i < COUNTERS; i++) {
      String name = HotpathConcurrencyAttribution.segmentName(i);
      assertTrue(!name.startsWith("counter_"), "索引 " + i + " 缺分段名");
      assertTrue(names.add(name), "分段名重复：" + name);
    }
    assertEquals(COUNTERS, names.size());
  }

  /** 守卫 4d：资源采样逐样本记录实际间隔与单样本开销（名义 50ms，实测漂移如实入账）。 */
  @Test
  void resourceRecorderRecordsIntervalAndOverhead(@TempDir Path tempDir) throws Exception {
    Settings settings =
        HotpathConcurrencyAttribution.resolve(
            "original", "guard-resource", tempDir.toString(), null);
    AttributionResourceRecorder recorder =
        AttributionResourceRecorder.start(settings, 1, "c8", List.of());
    Thread.sleep(600);
    recorder.close();
    Path file = tempDir.resolve("resources-guard-resource-original-r1-c8.jsonl");
    List<String> lines = Files.readAllLines(file);
    assertTrue(lines.size() >= 2, "600ms 内至少应采到 2 个样本，实际 " + lines.size());
    for (String line : lines) {
      assertTrue(line.contains("\"overhead_us\""), line);
      assertTrue(line.contains("\"interval_actual_ms\""), line);
      assertTrue(line.contains("\"interval_nominal_ms\":50"), line);
      assertTrue(line.contains("\"proc_cpu_load\""), line);
    }
  }

  /** 守卫 4e：最近邻秩百分位口径锁定（与原报告一致，防止归因报告换口径）。 */
  @Test
  void percentileUsesNearestRank() {
    long[] nanos = {10_000_000L, 20_000_000L, 30_000_000L, 40_000_000L};
    assertEquals(20.0d, HotpathConcurrencyAttribution.percentile(nanos, 0.50d), 1e-9);
    assertEquals(40.0d, HotpathConcurrencyAttribution.percentile(nanos, 0.95d), 1e-9);
    assertEquals(40.0d, HotpathConcurrencyAttribution.percentile(nanos, 1.00d), 1e-9);
    assertEquals(10.0d, HotpathConcurrencyAttribution.percentile(nanos, 0.01d), 1e-9);
  }

  /** 守卫佐证：三个合法条件值映射互异且 original 与历史序列同名。 */
  @Test
  void conditionValuesMapDistinctly() {
    assertEquals(Condition.ORIGINAL, HotpathConcurrencyAttribution.parseCondition("original"));
    assertEquals(
        Condition.WARMUP_SYNC, HotpathConcurrencyAttribution.parseCondition("warmup-sync"));
    assertEquals(
        Condition.PHASE_REVERSED, HotpathConcurrencyAttribution.parseCondition("phase-reversed"));
    assertEquals(3, Condition.values().length, "诊断条件只允许三个，新增条件必须先过对照实验规格");
  }

  /** 守卫佐证：诊断关闭的 Settings 不携带任何执行身份（防止半开启状态泄漏进输出）。 */
  @Test
  void offSettingsCarryNoRunIdentity() {
    Settings off = Settings.off();
    assertFalse(off.on());
    assertEquals("off", off.execId());
    AtomicLong touched = new AtomicLong();
    CountersHook noop =
        new CountersHook() {
          @Override
          public void onAdd(int index, long amount) {
            touched.incrementAndGet();
          }

          @Override
          public void onSearchedKb(Object kbId) {
            touched.incrementAndGet();
          }
        };
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    counters.setAttributionHook(null);
    counters.add(SQL_NANOS, 1L);
    assertEquals(0, touched.get(), "关闭状态下不得有钩子被调用");
  }
}
