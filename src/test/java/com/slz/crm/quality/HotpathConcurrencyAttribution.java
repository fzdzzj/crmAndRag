package com.slz.crm.quality;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.datasource.pooled.PooledDataSource;

/**
 * c8 并发档波动归因诊断（update-hotpath-concurrency-attribution）。
 *
 * <p><b>纯测试侧、显式 opt-in、默认关闭</b>：诊断只在 {@link RepresentativeHotpathBenchmark#OPT_IN_ENV}=1 且显式给出诊断选项
 * （{@link #ATTR_PROP} 或同名 env）时启用；两者缺一即在<b>任何容器/装配/模型请求之前</b> fail closed。诊断关闭时本类零参与， 原度量路径与 {@code
 * REPHOT} 输出逐字节保持历史形状。不读真实密钥、不下载镜像、不调用真实模型——隔离边界与原度量入口一致。
 *
 * <p><b>能力（仅测试侧）</b>：
 *
 * <ol>
 *   <li><b>请求级关联</b>：经 {@link RepresentativeHotpathBenchmark.Counters} 的诊断钩子把每个请求的分段（连接获取、SQL 分桶、
 *       Qdrant、嵌入/改写桩、融合/重排/上下文等）按同一 seq/worker/阶段写入 JSONL 账本；逐请求样本与全局计数一致性为硬校验；
 *   <li><b>受控对照</b>：{@link Condition#WARMUP_SYNC}（预热次数不变，顺序预热 → 8-worker 同步起跑）与 {@link
 *       Condition#PHASE_REVERSED}（仅反转 c1/c8 顺序）各只变一个因素；
 *   <li><b>同窗资源采样</b>：进程/系统 CPU、堆、线程逐样本落盘（名义 vs 实际采样间隔、单样本开销同点记录）； 容器 CPU/内存仅在独立诊断轮按显式子开关用只读 {@code
 *       docker stats --no-stream} 采集，取不到记 null（不是 0）；
 *   <li><b>MyBatis PooledDataSource 池画像</b>：池类型、限制与窗口末 active/idle/had-to-wait 快照（取不到记未知）。
 * </ol>
 *
 * <p><b>边界</b>：本机 JDK + Docker 的请求级归因不等于生产环境；即使某分段解释了 c8 波动，也不授权任何生产连接池/线程池/JVM/ 检索算法改动。ThreadLocal
 * 仅测试侧使用且一律在 {@code finally} 清理（{@link RequestLedger} 的 begin/end 成对契约）。
 */
final class HotpathConcurrencyAttribution {

  /** 归因诊断选项（系统属性优先，env 兜底）：缺失/空白 = 诊断关闭。 */
  static final String ATTR_PROP = "rephot.attribution";

  static final String ATTR_ENV = "REPHOT_ATTRIBUTION";

  /** 诊断执行编号（写进账本文件与记录，用于跨命令独立执行关联）。 */
  static final String EXEC_PROP = "rephot.attribution.exec";

  static final String EXEC_ENV = "REPHOT_ATTRIBUTION_EXEC";

  /** 账本输出目录（默认 target/rephot-attribution，被 .gitignore 覆盖，不入库）。 */
  static final String OUT_PROP = "rephot.attribution.out";

  static final String OUT_ENV = "REPHOT_ATTRIBUTION_OUT";

  /** 容器资源子开关（独立诊断轮才开；默认关，主对照矩阵不采集 docker stats 以免扰动主测量）。 */
  static final String DOCKER_STATS_PROP = "rephot.attribution.dockerStats";

  static final String DOCKER_STATS_ENV = "REPHOT_ATTRIBUTION_DOCKER_STATS";

  static final String DEFAULT_OUT_DIR = "target/rephot-attribution";

  static final String CONDITION_ORIGINAL = "original";

  static final String CONDITION_WARMUP_SYNC = "warmup-sync";

  static final String CONDITION_PHASE_REVERSED = "phase-reversed";

  /** 资源采样名义间隔（与原 ResourceWindow 同为 50ms；实际间隔逐样本记录，Windows sleep 粒度会造成漂移）。 */
  static final int RESOURCE_INTERVAL_MS = 50;

  private HotpathConcurrencyAttribution() {}

  // ---------------------------------------------------------------- 条件与开关

  /** 诊断对照条件：ORIGINAL=原序列（c1→c8、顺序预热）；WARMUP_SYNC=仅换 8-worker 同步预热；PHASE_REVERSED=仅反转 c1/c8 顺序。 */
  enum Condition {
    ORIGINAL,
    WARMUP_SYNC,
    PHASE_REVERSED
  }

  /** 诊断设置。{@code on=false} 时其余字段无意义，原路径必须零参与。 */
  record Settings(
      boolean on, Condition condition, String execId, String outDir, boolean dockerStats) {

    static Settings off() {
      return new Settings(false, Condition.ORIGINAL, "off", DEFAULT_OUT_DIR, false);
    }
  }

  /** 诊断入口组合闸：解析诊断选项；显式给出选项时要求独立 opt-in 同时成立，缺任一 fail closed。 必须在任何容器/装配/模型请求之前调用。 */
  static Settings gate(
      String optInEnvValue,
      String attrOption,
      String execOption,
      String outOption,
      String dockerStatsOption) {
    Settings settings = resolve(attrOption, execOption, outOption, dockerStatsOption);
    if (settings.on() && !"1".equals(optInEnvValue)) {
      throw new IllegalStateException(
          "未测：归因诊断选项已传入（"
              + ATTR_PROP
              + "="
              + attrOption
              + "）但缺少独立 opt-in 开关 "
              + RepresentativeHotpathBenchmark.OPT_IN_ENV
              + "=1。诊断必须在任何容器与请求之前 fail closed。");
    }
    return settings;
  }

  /** 解析诊断设置：属性缺失 = 关闭；给了值就必须合法（未知值 fail closed，绝不静默回落 original）。 */
  static Settings resolve(
      String attrOption, String execOption, String outOption, String dockerStatsOption) {
    if (attrOption == null || attrOption.isBlank()) {
      return Settings.off();
    }
    Condition condition = parseCondition(attrOption);
    String execId =
        execOption == null || execOption.isBlank() ? defaultExecId() : execOption.trim();
    String outDir = outOption == null || outOption.isBlank() ? DEFAULT_OUT_DIR : outOption.trim();
    boolean dockerStats = dockerStatsOption != null && "1".equals(dockerStatsOption.trim());
    return new Settings(true, condition, execId, outDir, dockerStats);
  }

  /** 诊断条件解析：只认三个合法值，其余 fail closed（防止打错字静默跑成 original 造成假对照）。 */
  static Condition parseCondition(String value) {
    String trimmed = value == null ? "" : value.trim();
    switch (trimmed) {
      case CONDITION_ORIGINAL:
        return Condition.ORIGINAL;
      case CONDITION_WARMUP_SYNC:
        return Condition.WARMUP_SYNC;
      case CONDITION_PHASE_REVERSED:
        return Condition.PHASE_REVERSED;
      default:
        throw new IllegalStateException(
            "未测：诊断选项 "
                + ATTR_PROP
                + " 的值非法：'"
                + value
                + "'（合法值="
                + CONDITION_ORIGINAL
                + "|"
                + CONDITION_WARMUP_SYNC
                + "|"
                + CONDITION_PHASE_REVERSED
                + "；缺省=关闭诊断）");
    }
  }

  /** 系统属性优先、env 兜底读取一个诊断选项值（都缺 = null）。 */
  static String option(String propKey, String envKey) {
    String prop = System.getProperty(propKey);
    if (prop != null && !prop.isBlank()) {
      return prop.trim();
    }
    String env = System.getenv(envKey);
    return env == null || env.isBlank() ? null : env.trim();
  }

  private static String defaultExecId() {
    return "exec-" + System.currentTimeMillis();
  }

  // ---------------------------------------------------------------- 钩子与监听接口

  /** 计数器诊断钩子：诊断开启时由 {@code Counters.add/recordSearchedKb} 同步回调（同线程、同一次调用）。 */
  interface CountersHook {

    void onAdd(int index, long amount);

    void onSearchedKb(Object kbId);
  }

  /** worker 生命周期监听：就绪→同步起跑/调度关联与线程复用清理的采集点。 */
  interface WorkerListener {

    /** worker 线程开始执行任务（尚未到同步栅栏）。 */
    void onWorkerStart(int workerId);

    /** 主线程放行瞬间（go.countDown 之前一刻）。 */
    void onGoSignaled(long goEpochMs);

    /** worker 通过同步栅栏（go.await 返回后）。 */
    void onWorkerReady(int workerId);

    /** worker 配额跑完（含异常路径；finally 语义，负责清理线程内标识）。 */
    void onWorkerEnd(int workerId);
  }

  // ---------------------------------------------------------------- 请求级账本

  /** 单请求分段快照（线程封闭：仅创建它的 worker 线程读写，endSpan 之后只读）。 */
  static final class RequestSpan {
    final int workerId;
    final long reqInWorker;
    final long seq;
    final long startEpochMs;
    final long[] segs;
    final List<String> kbIds = new ArrayList<>();
    String error;
    long e2eNanos;

    RequestSpan(int workerId, long reqInWorker, long seq) {
      this.workerId = workerId;
      this.reqInWorker = reqInWorker;
      this.seq = seq;
      this.startEpochMs = System.currentTimeMillis();
      this.segs = new long[RepresentativeHotpathBenchmark.Counters.COUNTER_COUNT];
    }

    void completeOk(long e2eNanos) {
      this.e2eNanos = e2eNanos;
    }

    void completeFail(long e2eNanos, String errorClass, String errorMessage) {
      this.e2eNanos = e2eNanos;
      this.error = errorClass + (errorMessage == null ? "" : (": " + truncate(errorMessage, 200)));
    }

    boolean ok() {
      return error == null;
    }

    long e2eMicros() {
      return e2eNanos / 1000L;
    }
  }

  /** 单 worker 生命周期记录（各自 worker 线程写自己的槽位，主线程在 futures join 后读）。 */
  private static final class WorkerRecord {
    final int workerId;
    final long submitEpochMs = System.currentTimeMillis();
    volatile long readyEpochMs = -1;
    long firstStartEpochMs = -1;
    volatile long endEpochMs = -1;
    long requests;

    WorkerRecord(int workerId) {
      this.workerId = workerId;
    }
  }

  /**
   * 请求级 JSONL 账本。诊断开启时作为 {@link CountersHook} 挂到共享计数器上：worker 线程 begin/end 成对地创建/关闭 ThreadLocal
   * 快照（end 一律在 finally，满足「线程复用必须清理」契约），同一请求的全部分段按同一线程归并。 关闭诊断时本类不存在实例，计数器钩子为 null，行为与历史一致。
   */
  static final class RequestLedger implements CountersHook, WorkerListener, AutoCloseable {
    private final Settings settings;
    private final int round;
    private final String phaseLabel;
    private final int concurrency;
    private final BufferedWriter out;
    private final Object writeLock = new Object();
    private final AtomicLong seq = new AtomicLong();
    private final ThreadLocal<RequestSpan> current = new ThreadLocal<>();
    private final ThreadLocal<Integer> workerId = new ThreadLocal<>();
    private final WorkerRecord[] workers;
    private final List<RequestSpan> recorded = new ArrayList<>();
    private final AtomicLong okCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();
    private volatile long goEpochMs = -1;
    private volatile boolean closed;

    private RequestLedger(
        Settings settings, int round, String phaseLabel, int concurrency, Path file)
        throws IOException {
      this.settings = settings;
      this.round = round;
      this.phaseLabel = phaseLabel;
      this.concurrency = concurrency;
      this.workers = new WorkerRecord[concurrency];
      Files.createDirectories(file.getParent());
      this.out =
          Files.newBufferedWriter(
              file,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
    }

    static RequestLedger open(Settings settings, int round, String phaseLabel, int concurrency)
        throws IOException {
      Path file =
          Path.of(
              settings.outDir(),
              "requests-"
                  + settings.execId()
                  + "-"
                  + settings.condition().name().toLowerCase(Locale.ROOT)
                  + "-r"
                  + round
                  + "-"
                  + phaseLabel
                  + ".jsonl");
      return new RequestLedger(settings, round, phaseLabel, concurrency, file);
    }

    // ---- worker 生命周期（WorkerListener）----

    @Override
    public void onWorkerStart(int workerIdIndex) {
      workerId.set(workerIdIndex);
      workers[workerIdIndex] = new WorkerRecord(workerIdIndex);
    }

    @Override
    public void onGoSignaled(long goEpochMs) {
      this.goEpochMs = goEpochMs;
    }

    @Override
    public void onWorkerReady(int workerIdIndex) {
      WorkerRecord record = workers[workerIdIndex];
      if (record != null) {
        record.readyEpochMs = System.currentTimeMillis();
      }
    }

    @Override
    public void onWorkerEnd(int workerIdIndex) {
      // 线程复用清理：无论配额是否跑完都清掉线程内标识，下一请求/下一相位不继承
      WorkerRecord record =
          workerIdIndex >= 0 && workerIdIndex < workers.length ? workers[workerIdIndex] : null;
      if (record != null) {
        record.endEpochMs = System.currentTimeMillis();
        synchronized (writeLock) {
          writeLine(workerJson(record));
        }
      }
      workerId.remove();
    }

    // ---- 请求级捕获（CountersHook + begin/end 成对契约）----

    RequestSpan beginSpan() {
      int workerIndex = workerId.get() == null ? -1 : workerId.get();
      long reqInWorker = 0;
      if (workerIndex >= 0 && workers[workerIndex] != null) {
        WorkerRecord record = workers[workerIndex];
        reqInWorker = ++record.requests;
        if (record.firstStartEpochMs < 0) {
          record.firstStartEpochMs = System.currentTimeMillis();
        }
      }
      RequestSpan span = new RequestSpan(workerIndex, reqInWorker, seq.incrementAndGet());
      current.set(span);
      return span;
    }

    /** 记录并清理：必须在 finally 调用；先落账再清 ThreadLocal，保证异常路径同样不留线程内状态。 */
    void endSpan(RequestSpan span) {
      synchronized (writeLock) {
        recorded.add(span);
        if (span.ok()) {
          okCount.incrementAndGet();
        } else {
          failCount.incrementAndGet();
        }
        writeLine(requestJson(span));
      }
      current.remove();
    }

    @Override
    public void onAdd(int index, long amount) {
      RequestSpan span = current.get();
      if (span != null) {
        span.segs[index] += amount;
      }
    }

    @Override
    public void onSearchedKb(Object kbId) {
      RequestSpan span = current.get();
      if (span != null) {
        span.kbIds.add(String.valueOf(kbId));
      }
    }

    // ---- 一致性校验与摘要 ----

    long recordCount() {
      synchronized (writeLock) {
        return recorded.size();
      }
    }

    /** 已落账 span 的只读快照（包内守卫测试断言逐请求关联用）。 */
    List<RequestSpan> recordedSpans() {
      synchronized (writeLock) {
        return List.copyOf(recorded);
      }
    }

    long okCount() {
      return okCount.get();
    }

    long failCount() {
      return failCount.get();
    }

    /** 逐请求样本与全局计数一致性：账本各分段索引之和必须等于窗口内全局计数器快照。 返回 null = 一致；非 null = 首个不一致项的明细。 */
    String checkGlobal(long[] globalSnapshot) {
      long[] sums = new long[RepresentativeHotpathBenchmark.Counters.COUNTER_COUNT];
      synchronized (writeLock) {
        for (RequestSpan span : recorded) {
          for (int i = 0; i < sums.length; i++) {
            sums[i] += span.segs[i];
          }
        }
      }
      for (int i = 0; i < sums.length; i++) {
        if (sums[i] != globalSnapshot[i]) {
          return "counter["
              + i
              + "/"
              + segmentName(i)
              + "] ledger="
              + sums[i]
              + " global="
              + globalSnapshot[i];
        }
      }
      return null;
    }

    /** 校验 + stdout 摘要（worker 分位、连接获取分布、慢尾个体逐条分段——全部请求级口径，不用全局平均）。 */
    void verifyAndSummarize(long[] globalSnapshot, long expectedSamples) {
      String mismatch = checkGlobal(globalSnapshot);
      if (mismatch != null) {
        throw new AssertionError(
            "归因账本与全局计数不一致（"
                + settings.execId()
                + " r"
                + round
                + " "
                + phaseLabel
                + "）："
                + mismatch);
      }
      long records = recordCount();
      if (records != expectedSamples) {
        throw new AssertionError(
            "归因账本请求数 "
                + records
                + " != 预期样本数 "
                + expectedSamples
                + "（"
                + phaseLabel
                + " r"
                + round
                + "）");
      }
      printSummary();
    }

    void printSummary() {
      out(
          "REPHOT-ATTR ledger: exec=%s cond=%s round=%d phase=%s records=%d ok=%d fail=%d global_consistent=true",
          settings.execId(),
          settings.condition().name().toLowerCase(Locale.ROOT),
          round,
          phaseLabel,
          recordCount(),
          okCount.get(),
          failCount.get());
      for (String line : workerSummaryLines()) {
        out("%s", line);
      }
      out("%s", connectionLine());
      for (String line : slowRequestLines(5)) {
        out("%s", line);
      }
    }

    /** 每个 worker 的独立分位 + 调度延迟（就绪→同步起跑）。 */
    List<String> workerSummaryLines() {
      List<String> lines = new ArrayList<>();
      for (WorkerRecord record : workers) {
        if (record == null) {
          continue;
        }
        List<Long> e2e = new ArrayList<>();
        synchronized (writeLock) {
          for (RequestSpan span : recorded) {
            if (span.workerId == record.workerId) {
              e2e.add(span.e2eNanos);
            }
          }
        }
        long[] sorted = e2e.stream().mapToLong(Long::longValue).toArray();
        String dispatch =
            record.firstStartEpochMs >= 0 && goEpochMs >= 0
                ? String.valueOf(record.firstStartEpochMs - goEpochMs)
                : "unknown";
        lines.add(
            "REPHOT-ATTR worker: phase="
                + phaseLabel
                + " round="
                + round
                + " worker="
                + record.workerId
                + " n="
                + sorted.length
                + " p50_ms="
                + millis3(percentile(sorted, 0.50d))
                + " p95_ms="
                + millis3(percentile(sorted, 0.95d))
                + " p99_ms="
                + millis3(percentile(sorted, 0.99d))
                + " max_ms="
                + millis3(percentile(sorted, 1.00d))
                + " dispatch_delay_ms="
                + dispatch);
      }
      return lines;
    }

    /** 连接获取（SQL 嵌套子段）的逐请求分布。 */
    String connectionLine() {
      int connNanosIndex = RepresentativeHotpathBenchmark.Counters.CONN_NANOS;
      long[] values = new long[(int) recordCount()];
      int n = 0;
      synchronized (writeLock) {
        for (RequestSpan span : recorded) {
          values[n++] = span.segs[connNanosIndex];
        }
      }
      return "REPHOT-ATTR conn: phase="
          + phaseLabel
          + " round="
          + round
          + " n="
          + n
          + " p50_ms="
          + millis3(percentile(values, 0.50d))
          + " p95_ms="
          + millis3(percentile(values, 0.95d))
          + " p99_ms="
          + millis3(percentile(values, 0.99d))
          + " max_ms="
          + millis3(percentile(values, 1.00d));
    }

    /** 慢尾个体：按端到端降序的 top-N，逐条给该请求自己的分段（明确不是跨请求平均）。 */
    List<String> slowRequestLines(int topN) {
      List<RequestSpan> sorted;
      synchronized (writeLock) {
        sorted = new ArrayList<>(recorded);
      }
      sorted.sort((a, b) -> Long.compare(b.e2eNanos, a.e2eNanos));
      List<String> lines = new ArrayList<>();
      int limit = Math.min(topN, sorted.size());
      for (int rank = 1; rank <= limit; rank++) {
        RequestSpan span = sorted.get(rank - 1);
        lines.add(
            "REPHOT-ATTR slow: phase="
                + phaseLabel
                + " round="
                + round
                + " rank="
                + rank
                + " seq="
                + span.seq
                + " worker="
                + span.workerId
                + " start_epoch_ms="
                + span.startEpochMs
                + " e2e_ms="
                + millis3(span.e2eNanos / 1e6d)
                + " conn_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.CONN_NANOS)
                + " sql_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.SQL_NANOS)
                + " auth_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.AUTH_SQL_NANOS)
                + " sparse_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.FULLTEXT_NANOS)
                + " parent_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.SELECT_BY_ID_NANOS)
                + " batch_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.SELECT_BATCH_NANOS)
                + " neighbor_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.SELECT_LIST_NANOS)
                + " qdrant_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.QDRANT_SEARCH_NANOS)
                + " embed_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.EMBED_NANOS)
                + " rewrite_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.REWRITE_NANOS)
                + " fuse_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.FUSE_NANOS)
                + " rerank_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.RERANK_NANOS)
                + " context_ms="
                + segmentMillis(span, RepresentativeHotpathBenchmark.Counters.CONTEXT_NANOS)
                + " kb_ids="
                + span.kbIds);
      }
      return lines;
    }

    private String requestJson(RequestSpan span) {
      JsonObj json =
          new JsonObj()
              .str("kind", "request")
              .str("exec", settings.execId())
              .str("cond", settings.condition().name().toLowerCase(Locale.ROOT))
              .num("round", round)
              .str("phase", phaseLabel)
              .num("seq", span.seq)
              .num("worker", span.workerId)
              .num("req", span.reqInWorker)
              .num("start_epoch_ms", span.startEpochMs)
              .num("e2e_us", span.e2eMicros())
              .bool("ok", span.ok())
              .str("error", span.error)
              .raw("kb_ids", jsonArray(span.kbIds.stream().map(JsonObj::escape).toList()))
              .raw("segs", segsJson(span.segs));
      return json.build();
    }

    private String workerJson(WorkerRecord record) {
      JsonObj json =
          new JsonObj()
              .str("kind", "worker")
              .str("exec", settings.execId())
              .str("cond", settings.condition().name().toLowerCase(Locale.ROOT))
              .num("round", round)
              .str("phase", phaseLabel)
              .num("worker", record.workerId)
              .num("submit_epoch_ms", record.submitEpochMs)
              .num("go_epoch_ms", goEpochMs)
              .num("ready_epoch_ms", record.readyEpochMs)
              .num("first_start_epoch_ms", record.firstStartEpochMs)
              .num("end_epoch_ms", record.endEpochMs)
              .num("requests", record.requests);
      if (goEpochMs >= 0 && record.firstStartEpochMs >= 0) {
        json.num("dispatch_delay_us", (record.firstStartEpochMs - goEpochMs) * 1000L);
      }
      return json.build();
    }

    private String segsJson(long[] segs) {
      StringBuilder json = new StringBuilder("{");
      boolean first = true;
      for (int i = 0; i < segs.length; i++) {
        if (segs[i] == 0) {
          continue;
        }
        if (!first) {
          json.append(',');
        }
        first = false;
        json.append(JsonObj.escape(segmentName(i))).append(':').append(segs[i] / 1000L);
      }
      return json.append('}').toString();
    }

    /** 单行落盘（调用方已持 writeLock；行内容自含换行控制）。 */
    private void writeLine(String line) {
      try {
        out.write(line);
        out.newLine();
      } catch (IOException ignored) {
        // 单行写失败按未采到处理；一致性校验仍以内存中的 spans 为准
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        out.flush();
        out.close();
      } catch (IOException ignored) {
        // 账本关闭失败不影响度量结论；记录已逐行写入
        Thread.currentThread().interrupt();
      }
    }
  }

  // ---------------------------------------------------------------- 同窗资源采样器

  /**
   * 逐样本资源采样器（与测量窗口同开同停）：进程/系统 CPU、堆、线程数按名义 50ms 采样，实际间隔与单样本开销同点落盘。 docker stats 只在显式子开关开启的独立诊断轮按约
   * 2s 周期只读采样（主对照矩阵不开启，避免扰动主测量）。
   */
  static final class AttributionResourceRecorder implements AutoCloseable {
    /** 容器采样节流：窗口首个样本立即采一次，之后每第 10 个样本约 2s 一次（单次 --no-stream 调用实测约 1-2s）。 */
    private static final int DOCKER_STATS_EVERY = 10;

    private final Settings settings;
    private final int round;
    private final String phaseLabel;
    private final List<String> containerIds;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean osBean =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BufferedWriter out;
    private final AtomicLong sampleIndex = new AtomicLong();
    private final Thread sampler;
    private volatile boolean dockerDisabled;
    private volatile long lastSampleNanos;

    private AttributionResourceRecorder(
        Settings settings, int round, String phaseLabel, List<String> containerIds)
        throws IOException {
      this.settings = settings;
      this.round = round;
      this.phaseLabel = phaseLabel;
      this.containerIds = containerIds;
      Path file =
          Path.of(
              settings.outDir(),
              "resources-"
                  + settings.execId()
                  + "-"
                  + settings.condition().name().toLowerCase(Locale.ROOT)
                  + "-r"
                  + round
                  + "-"
                  + phaseLabel
                  + ".jsonl");
      Files.createDirectories(file.getParent());
      this.out =
          Files.newBufferedWriter(
              file,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      // Windows 首次 getProcessCpuLoad/getSystemCpuLoad 触发 PDH 初始化（本机实测可达数百 ms），
      // 必须在起采样线程前预热丢弃，否则首个测量窗口起点被卡、首条间隔记录失真
      try {
        osBean.getProcessCpuLoad();
        osBean.getSystemCpuLoad();
        memoryBean.getHeapMemoryUsage().getUsed();
        threadBean.getThreadCount();
      } catch (RuntimeException ignored) {
        // 预热失败按逐样本 try 路径继续，采样线程内仍会如实记录
      }
      this.lastSampleNanos = System.nanoTime();
      Runnable loop =
          () -> {
            while (running.get()) {
              try {
                sampleOnce();
                Thread.sleep(RESOURCE_INTERVAL_MS);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
              } catch (RuntimeException exception) {
                // 采样线程异常不得静默死亡：落一条错误记录后停止，让「缺测」在数据里显式可见
                synchronized (AttributionResourceRecorder.this) {
                  writeLineOut(
                      new JsonObj()
                          .str("kind", "resource_sampler_error")
                          .str("error", String.valueOf(exception))
                          .build());
                }
                return;
              }
            }
          };
      this.sampler = new Thread(loop, "rephot-attribution-resource");
      this.sampler.setDaemon(true);
      this.sampler.start();
    }

    static AttributionResourceRecorder start(
        Settings settings, int round, String phaseLabel, List<String> containerIds)
        throws IOException {
      return new AttributionResourceRecorder(
          settings, round, phaseLabel, settings.dockerStats() ? containerIds : List.of());
    }

    private void sampleOnce() {
      long index = sampleIndex.incrementAndGet();
      long startNanos = System.nanoTime();
      double procCpu = osBean.getProcessCpuLoad();
      double sysCpu = osBean.getSystemCpuLoad();
      long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
      long heapCommitted = memoryBean.getHeapMemoryUsage().getCommitted();
      int threads = threadBean.getThreadCount();
      long overheadNanos = System.nanoTime() - startNanos;
      long nowNanos = System.nanoTime();
      long intervalNanos = lastSampleNanos;
      lastSampleNanos = nowNanos;

      JsonObj json =
          new JsonObj()
              .str("kind", "resource")
              .str("exec", settings.execId())
              .str("cond", settings.condition().name().toLowerCase(Locale.ROOT))
              .num("round", round)
              .str("phase", phaseLabel)
              .num("t_epoch_ms", System.currentTimeMillis())
              .num("interval_nominal_ms", RESOURCE_INTERVAL_MS)
              .num("interval_actual_ms", (nowNanos - intervalNanos) / 1e6d)
              .num("overhead_us", overheadNanos / 1000L);
      if (procCpu >= 0) {
        json.num("proc_cpu_load", procCpu);
      } else {
        json.str("proc_cpu_load", null);
      }
      if (sysCpu >= 0) {
        json.num("sys_cpu_load", sysCpu);
      } else {
        json.str("sys_cpu_load", null);
      }
      json.num("heap_used_mb", heapUsed / 1e6d).num("heap_committed_mb", heapCommitted / 1e6d);
      json.num("threads", threads);
      if (settings.dockerStats()
          && !dockerDisabled
          && (index == 1 || index % DOCKER_STATS_EVERY == 0)) {
        appendDockerStats(json);
      }
      synchronized (this) {
        writeLineOut(json.build());
      }
    }

    /** 只读 docker stats（不 pull、不改容器）；失败一次即禁用后续采样并记录原因。 */
    private void appendDockerStats(JsonObj json) {
      long startNanos = System.nanoTime();
      try {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("stats");
        command.add("--no-stream");
        command.add("--format");
        command.add("{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}");
        command.addAll(containerIds);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> entries = new ArrayList<>();
        try (java.io.BufferedReader reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\\|", -1);
            if (parts.length == 3) {
              entries.add(
                  new JsonObj()
                      .str("name", parts[0].trim())
                      .str("cpu", parts[1].trim())
                      .str("mem", parts[2].trim())
                      .build());
            }
          }
        }
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
        }
        long overheadMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (!finished || entries.isEmpty()) {
          dockerDisabled = true;
          json.str("docker_error", finished ? "empty-stats-output" : "stats-timeout")
              .num("docker_overhead_ms", overheadMs);
          return;
        }
        json.raw("docker", "[" + String.join(",", entries) + "]")
            .num("docker_overhead_ms", overheadMs);
      } catch (IOException exception) {
        dockerDisabled = true;
        json.str("docker_error", String.valueOf(exception.getMessage()))
            .num("docker_overhead_ms", (System.nanoTime() - startNanos) / 1_000_000L);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        dockerDisabled = true;
        json.str("docker_error", "interrupted");
      }
    }

    private void writeLineOut(String line) {
      try {
        out.write(line);
        out.newLine();
      } catch (IOException ignored) {
        // 单行写失败按未采到处理，不打断主测量
      }
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      running.set(false);
      sampler.interrupt();
      try {
        sampler.join(5_000);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
      try {
        out.flush();
        out.close();
      } catch (IOException ignored) {
        // 同上：采样中断不回滚主测量结论
      }
    }
  }

  // ---------------------------------------------------------------- 池画像与清单

  /** PooledDataSource 实际池画像（类型/限制 + 窗口末快照；取不到的项记 -1 = 未知，不是 0）。 */
  record PoolSnapshot(
      String poolType,
      int maxActive,
      int maxIdle,
      int maxCheckoutMs,
      int timeToWaitMs,
      int activeCount,
      int idleCount,
      long hadToWaitCount,
      long avgWaitMs) {

    String activeOrUnknown() {
      return activeCount >= 0 ? String.valueOf(activeCount) : "unknown";
    }

    String idleOrUnknown() {
      return idleCount >= 0 ? String.valueOf(idleCount) : "unknown";
    }
  }

  static PoolSnapshot poolSnapshot(PooledDataSource pool) {
    int active = -1;
    int idle = -1;
    long hadToWait = -1;
    long avgWait = -1;
    try {
      PoolStateView state = new PoolStateView(pool.getPoolState());
      active = state.activeCount();
      idle = state.idleCount();
      hadToWait = state.hadToWaitCount();
      avgWait = state.averageWaitMs();
    } catch (RuntimeException ignored) {
      // 池状态快照取不到就标未知（不写 0、不抛出阻断度量）
    }
    return new PoolSnapshot(
        pool.getClass().getName(),
        pool.getPoolMaximumActiveConnections(),
        pool.getPoolMaximumIdleConnections(),
        pool.getPoolMaximumCheckoutTime(),
        pool.getPoolTimeToWait(),
        active,
        idle,
        hadToWait,
        avgWait);
  }

  /** PoolState 公开 getter 的薄封装（MyBatis 3.5.x：getActiveConnectionCount 等 synchronized 计数）。 */
  private record PoolStateView(
      int activeCount, int idleCount, long hadToWaitCount, long averageWaitMs) {
    PoolStateView(Object poolState) {
      this(
          invokeCount(poolState, "getActiveConnectionCount"),
          invokeCount(poolState, "getIdleConnectionCount"),
          invokeLong(poolState, "getHadToWaitCount"),
          invokeLong(poolState, "getAverageWaitTime"));
    }

    private static int invokeCount(Object target, String method) {
      try {
        return (Integer) target.getClass().getMethod(method).invoke(target);
      } catch (Exception exception) {
        return -1;
      }
    }

    private static long invokeLong(Object target, String method) {
      try {
        return (Long) target.getClass().getMethod(method).invoke(target);
      } catch (Exception exception) {
        return -1L;
      }
    }
  }

  static void printPoolSnapshot(String phase, int round, PooledDataSource pool) {
    PoolSnapshot snapshot = poolSnapshot(pool);
    out(
        "REPHOT-ATTR pool: phase=%s round=%d type=%s max_active=%d max_idle=%d max_checkout_ms=%d"
            + " time_to_wait_ms=%d active=%s idle=%s had_to_wait=%s avg_wait_ms=%s",
        phase,
        round,
        snapshot.poolType(),
        snapshot.maxActive(),
        snapshot.maxIdle(),
        snapshot.maxCheckoutMs(),
        snapshot.timeToWaitMs(),
        snapshot.activeOrUnknown(),
        snapshot.idleOrUnknown(),
        snapshot.hadToWaitCount() >= 0 ? String.valueOf(snapshot.hadToWaitCount()) : "unknown",
        snapshot.avgWaitMs() >= 0 ? String.valueOf(snapshot.avgWaitMs()) : "unknown");
  }

  /** 把清单 JSONL 落盘（manifest-&lt;exec&gt;-&lt;cond&gt;.json）并打印索引行。 */
  static void writeManifest(Settings settings, String json) {
    try {
      Path file =
          Path.of(
              settings.outDir(),
              "manifest-"
                  + settings.execId()
                  + "-"
                  + settings.condition().name().toLowerCase(Locale.ROOT)
                  + ".json");
      Files.createDirectories(file.getParent());
      Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8);
      out("REPHOT-ATTR manifest: file=%s", file);
    } catch (IOException exception) {
      throw new IllegalStateException("归因清单写盘失败（fail closed，不假装可比）", exception);
    }
  }

  // ---------------------------------------------------------------- 分段名与 JSON 工具

  private static final String[] SEGMENT_NAMES = buildSegmentNames();

  private static String[] buildSegmentNames() {
    String[] names = new String[RepresentativeHotpathBenchmark.Counters.COUNTER_COUNT];
    names[RepresentativeHotpathBenchmark.Counters.AUTH_SQL_CALLS] = "auth_sql_calls";
    names[RepresentativeHotpathBenchmark.Counters.AUTH_SQL_NANOS] = "auth_sql_us";
    names[RepresentativeHotpathBenchmark.Counters.SQL_CALLS] = "sql_calls";
    names[RepresentativeHotpathBenchmark.Counters.SQL_NANOS] = "sql_us";
    names[RepresentativeHotpathBenchmark.Counters.FULLTEXT_CALLS] = "sparse_calls";
    names[RepresentativeHotpathBenchmark.Counters.FULLTEXT_NANOS] = "sparse_us";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_BY_ID_CALLS] = "parent_calls";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_BY_ID_NANOS] = "parent_us";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_BATCH_CALLS] = "batch_calls";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_BATCH_NANOS] = "batch_us";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_LIST_CALLS] = "neighbor_calls";
    names[RepresentativeHotpathBenchmark.Counters.SELECT_LIST_NANOS] = "neighbor_us";
    names[RepresentativeHotpathBenchmark.Counters.INSERT_CHILD_CALLS] = "insert_child_calls";
    names[RepresentativeHotpathBenchmark.Counters.INSERT_PARENT_CALLS] = "insert_parent_calls";
    names[RepresentativeHotpathBenchmark.Counters.INSERT_NANOS] = "insert_us";
    names[RepresentativeHotpathBenchmark.Counters.FILE_SQL_CALLS] = "file_calls";
    names[RepresentativeHotpathBenchmark.Counters.FILE_SQL_NANOS] = "file_us";
    names[RepresentativeHotpathBenchmark.Counters.CONN_CALLS] = "conn_calls";
    names[RepresentativeHotpathBenchmark.Counters.CONN_NANOS] = "conn_us";
    names[RepresentativeHotpathBenchmark.Counters.EMBED_CALLS] = "embed_calls";
    names[RepresentativeHotpathBenchmark.Counters.EMBED_TEXTS] = "embed_texts";
    names[RepresentativeHotpathBenchmark.Counters.EMBED_NANOS] = "embed_us";
    names[RepresentativeHotpathBenchmark.Counters.REWRITE_CALLS] = "rewrite_calls";
    names[RepresentativeHotpathBenchmark.Counters.REWRITE_NANOS] = "rewrite_us";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_SEARCH_CALLS] = "qdrant_search_calls";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_SEARCH_NANOS] = "qdrant_search_us";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_UPSERT_CALLS] = "qdrant_upsert_calls";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_UPSERT_NANOS] = "qdrant_upsert_us";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_DELETE_CALLS] = "qdrant_delete_calls";
    names[RepresentativeHotpathBenchmark.Counters.QDRANT_DELETE_NANOS] = "qdrant_delete_us";
    names[RepresentativeHotpathBenchmark.Counters.FUSE_CALLS] = "fuse_calls";
    names[RepresentativeHotpathBenchmark.Counters.FUSE_NANOS] = "fuse_us";
    names[RepresentativeHotpathBenchmark.Counters.RERANK_CALLS] = "rerank_calls";
    names[RepresentativeHotpathBenchmark.Counters.RERANK_NANOS] = "rerank_us";
    names[RepresentativeHotpathBenchmark.Counters.CONTEXT_CALLS] = "context_calls";
    names[RepresentativeHotpathBenchmark.Counters.CONTEXT_NANOS] = "context_us";
    names[RepresentativeHotpathBenchmark.Counters.PARSE_CALLS] = "parse_calls";
    names[RepresentativeHotpathBenchmark.Counters.PARSE_NANOS] = "parse_us";
    return names;
  }

  static String segmentName(int index) {
    if (index < 0 || index >= SEGMENT_NAMES.length || SEGMENT_NAMES[index] == null) {
      return "counter_" + index;
    }
    return SEGMENT_NAMES[index];
  }

  /** 最近邻秩百分位（毫秒，输入纳秒数组）。 */
  static double percentile(long[] values, double p) {
    if (values.length == 0) {
      return Double.NaN;
    }
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(p * sorted.length) - 1;
    index = Math.max(0, Math.min(sorted.length - 1, index));
    return sorted[index] / 1_000_000.0d;
  }

  private static String segmentMillis(RequestSpan span, int nanosIndex) {
    return millis3(span.segs[nanosIndex] / 1e6d);
  }

  private static String millis3(double millis) {
    if (Double.isNaN(millis)) {
      return "unknown";
    }
    return String.format(Locale.ROOT, "%.3f", millis);
  }

  private static String truncate(String value, int limit) {
    return value.length() <= limit ? value : value.substring(0, limit) + "...";
  }

  private static String jsonArray(List<String> escapedValues) {
    return escapedValues.stream()
        .reduce((a, b) -> a + "," + b)
        .map(v -> "[" + v + "]")
        .orElse("[]");
  }

  /** REPHOT-ATTR stdout 输出（与原度量 printf 同通道，前缀可 grep）。 */
  static void out(String format, Object... args) {
    System.out.printf(Locale.ROOT, format + "%n", args);
  }

  /** 输出诊断模式行（gate 通过后立刻打，让每条命令的 stdout 可自证条件）。 */
  static void printModeLine(Settings settings) {
    out(
        "REPHOT-ATTR mode: enabled cond=%s exec=%s out=%s docker_stats=%s",
        settings.condition().name().toLowerCase(Locale.ROOT),
        settings.execId(),
        settings.outDir(),
        settings.dockerStats() ? "on(独立诊断轮)" : "off");
  }

  // ---------------------------------------------------------------- 极简 JSON 构造

  /** 手写 JSON 行构造器（零依赖；字符串一律转义，数组用 raw 拼）。 */
  static final class JsonObj {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    private JsonObj key(String name) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(escape(name)).append(':');
      return this;
    }

    JsonObj str(String name, String value) {
      key(name);
      if (value == null) {
        sb.append("null");
      } else {
        sb.append(escape(value));
      }
      return this;
    }

    JsonObj num(String name, long value) {
      key(name);
      sb.append(value);
      return this;
    }

    JsonObj num(String name, double value) {
      key(name);
      sb.append(Double.toString(value));
      return this;
    }

    JsonObj bool(String name, boolean value) {
      key(name);
      sb.append(value);
      return this;
    }

    JsonObj raw(String name, String rawJson) {
      key(name);
      sb.append(rawJson);
      return this;
    }

    String build() {
      return sb.append('}').toString();
    }

    static String escape(String value) {
      StringBuilder json = new StringBuilder("\"");
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '"' -> json.append("\\\"");
          case '\\' -> json.append("\\\\");
          case '\n' -> json.append("\\n");
          case '\r' -> json.append("\\r");
          case '\t' -> json.append("\\t");
          case '\b' -> json.append("\\b");
          case '\f' -> json.append("\\f");
          default -> {
            if (c < 0x20) {
              json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              json.append(c);
            }
          }
        }
      }
      return json.append('"').toString();
    }
  }
}
