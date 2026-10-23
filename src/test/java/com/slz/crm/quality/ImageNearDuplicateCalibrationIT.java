package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Analysis;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.CorpusMeta;
import com.slz.crm.quality.NearDuplicateBandAnalyzer.Pair;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 近重复三档定标真跑（calibrate-near-dup-bands 任务 3.1，failsafe IT，A 档真跑）：{@code
 * docs/rag-quality/image-threshold-calibration-v2.json} 的<b>唯一写入口</b>。
 *
 * <p><b>门控</b>：{@code RAG_VISION_EMBED_REAL=1} 且存在 {@code DASHSCOPE_API_KEY}（env 优先、回落仓库根 {@code
 * .env}，与 {@code ImageThresholdCalibrationIT} 同序）才执行；否则整类按 {@link Assumptions} 跳过、<b>不落任何文件</b>——默认
 * CI 与本地 {@code verify} 恒零外呼。
 *
 * <p><b>语料与口径</b>（跑之前固定，见 {@code openspec/changes/calibrate-near-dup-bands/proposal.md} §2）：
 *
 * <ol>
 *   <li><b>base</b>：三份已登记公有领域 PDF 经生产提取器各取前 8 / 4 / 12 张位图，按 SHA-256 全局去重（见 {@link
 *       NearDuplicateCorpus}）；
 *   <li><b>变体</b>：每个 base 五种确定性变换（{@code jpeg-q80} / {@code scale-085} / {@code scale-070} / {@code
 *       crop-04} / {@code crop-12}），只在内存里生成；
 *   <li><b>每图编码两次</b>（同字节再编码）：两次的余弦就是该图的「同图下限」观测，A 向量参与其它比较。因此外呼次数 = 图数 × 2。
 * </ol>
 *
 * <p><b>样本对三层</b>：{@code same-image}（每图一对 A/B，图数对）、{@code near-duplicate}（base ↔ 其变体，base × 5
 * 对）、{@code cross}（<b>仅 base 两两</b>，同出版物 = {@code same-class}、跨出版物 = {@code cross-class}）。
 *
 * <p><b>落盘纪律</b>：全部编码成功后<b>一次性</b>写锚；任一调用返回空向量或非限流失败即整体失败，<b>不落半成品锚</b>。判定树（F0–F5）由 {@link
 * NearDuplicateBandAnalyzer#judge} 按 proposal §4 的条件原文照判，<b>任何分支都不改常量</b>。
 *
 * <p><b>调用节奏（2026-10-24 实测补丁）</b>：端点有速率配额，2 QPS 连打会中途 {@code 429 Throttling.RateQuota}，故按 {@link
 * #PACE_MILLIS} 主动降速到约 0.9 QPS，并对 {@code 429} 做有界退避重试（{@link #THROTTLE_RETRIES} 次）；重试次数落进锚的 {@code
 * throttledRetries}。节奏与重试只影响墙钟时间，<b>不改变样本对、不改任何定标口径</b>。
 */
@DisplayName("近重复三档定标真跑（RAG_VISION_EMBED_REAL 显式 opt-in）")
class ImageNearDuplicateCalibrationIT {

  /** 真跑 opt-in 键（沿用既有真跑 IT 的同一枚键，不新增）。 */
  static final String OPT_IN_ENV = "RAG_VISION_EMBED_REAL";

  /** v2 锚落盘路径（独立文件；v1 锚一行不改）。 */
  static final Path DEFAULT_ANCHOR_PATH =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** 落盘路径覆盖属性（与既有基准的 {@code rag.*.out} 同形）。 */
  private static final String OUT_PROP = "rag.image.near.duplicate.out";

  /** compatible-mode 端点（与既有真跑 IT 相同装配）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 视觉 embedding 的 MIME（生产 {@code PdfImageSliceIngestService.IMAGE_MIME_TYPE} 同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** 落盘留痕：opt-in 与语料口径（复核判据直接读这一句）。 */
  private static final String OPT_IN_NOTE =
      "RAG_VISION_EMBED_REAL=1 + DASHSCOPE_API_KEY 显式真跑；语料 = 三份已登记公有领域 PDF 经生产 PdfImageExtractor "
          + "各取前 8/4/12 张位图（按 SHA-256 全局去重）+ 每 base 五种内存变体（jpeg-q80/scale-085/scale-070/crop-04/crop-12）；"
          + "每图经真视觉 embedding（multimodal-embedding-v1）编码两次，同图档 = 两次编码的余弦，"
          + "近重复档 = base 与其变体 A 向量的余弦，cross 仅 base 两两；本锚不改任何生产常量";

  /** 进度打印间隔（图数；真跑要看得见进展，避免像卡死）。 */
  private static final int PROGRESS_EVERY = 24;

  /**
   * 调用间隔（毫秒）：DashScope 视觉 embedding 端点实测有速率配额，2 QPS 连打会在中途收 {@code 429 Throttling.RateQuota}（本卡
   * 2026-10-24 实测：72 图 / 144 次调用、约 2 QPS 时被限流）。
   *
   * <p>本卡选择"主动降速"而不是"加重试"：限流不是样本质量信号，压到 ~0.9 QPS（{@link #PACE_MILLIS}=1100）后仍在 官方 QPS
   * 配额内连续取数；这最多拉长墙钟时间，<b>不改变任何定标口径与样本对</b>。
   */
  private static final long PACE_MILLIS = 1100L;

  /** 429 的有界退避重试次数上限（仅限流；其它失败一律整体失败，不重试、不落半成品锚）。 */
  private static final int THROTTLE_RETRIES = 3;

  /** 429 退避基数（毫秒，第 n 次重试等待 {@code 基数 × 2^(n-1)}）。 */
  private static final long THROTTLE_BACKOFF_MILLIS = 5000L;

  @Test
  @DisplayName("真跑：造同源近重复对，落 production/anchor 双视图与 v2 锚")
  void calibrateNearDuplicateBands() throws Exception {
    requireRealRun(System.getenv(), resolveApiKey());
    ModelProvider provider = provider(resolveApiKey());
    long startedAtNanos = System.nanoTime();
    long startedAtMillis = System.currentTimeMillis();

    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    assertEquals(
        NearDuplicateCorpus.plan().size(), corpus.documents().size(), "逐文档取图登记必须覆盖计划的全部文档");
    assertTrue(corpus.bases().size() >= 20, "base 数少于 20：语料被替换或生产提取器口径变了");
    assertTrue(
        corpus.skips().isEmpty(), "有变体被跳过（最短边 < 48px）：near-duplicate 对数将不再等于 base×5，须先扩语料再定标");

    Map<String, Encoded> encodings = new LinkedHashMap<>();
    List<ImageJob> jobs = jobs(corpus);
    RunStats stats = new RunStats();
    int calls = 0;
    int index = 0;
    for (ImageJob job : jobs) {
      index++;
      ModelCallResult<float[]> first = embed(provider, job.png(), job.key(), stats);
      calls++;
      ModelCallResult<float[]> second = embed(provider, job.png(), job.key(), stats);
      calls++;
      encodings.put(
          job.key(),
          new Encoded(
              job.key(), job.docKey(), vectorOf(first, job.key()), vectorOf(second, job.key())));
      if (index % PROGRESS_EVERY == 0 || index == jobs.size()) {
        System.out.println(
            "[near-dup-calibration] 已编码 "
                + index
                + "/"
                + jobs.size()
                + " 图（"
                + calls
                + " 次外呼，限流重试 "
                + stats.throttledRetries
                + " 次，耗时 "
                + elapsedMillis(startedAtMillis)
                + " ms）");
      }
    }

    List<Pair> pairs = pairs(corpus, encodings);
    assertEquals(
        corpus.imageCount(),
        count(pairs, NearDuplicateBandAnalyzer.STRATUM_SAME_IMAGE),
        "同图档对数必须等于图数（每图一对 A/B 再编码）");
    assertEquals(
        corpus.bases().size() * NearDuplicateCorpus.KINDS.size(),
        count(pairs, NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE),
        "近重复档对数必须等于 base × 5");
    assertEquals(
        corpus.bases().size() * (corpus.bases().size() - 1) / 2,
        count(pairs, NearDuplicateBandAnalyzer.STRATUM_SAME_CLASS)
            + count(pairs, NearDuplicateBandAnalyzer.STRATUM_CROSS_CLASS),
        "cross 只取 base 两两（对数 = bases×(bases−1)/2）");
    assertEquals(corpus.imageCount() * 2, calls, "外呼次数必须等于图数 × 2");

    Analysis analysis = NearDuplicateBandAnalyzer.analyze(pairs);
    long elapsedMillis = elapsedMillis(startedAtMillis);
    System.out.println(
        "[near-dup-calibration] productionView="
            + analysis.productionView()
            + System.lineSeparator()
            + "[near-dup-calibration] anchorView="
            + analysis.anchorView());
    System.out.println("[near-dup-calibration] candidateFloor=" + analysis.candidateFloor());
    for (NearDuplicateBandAnalyzer.Judgement judgement : analysis.judgements()) {
      System.out.println(
          "[near-dup-calibration] "
              + judgement.code()
              + " hit="
              + judgement.hit()
              + " "
              + judgement.evidence());
    }

    CorpusMeta meta =
        new CorpusMeta(
            corpus.bases().size(),
            corpus.variants().size(),
            corpus.imageCount(),
            calls,
            corpus.skips().size(),
            corpus.documents(),
            corpus.skips());
    ObjectNode root =
        NearDuplicateBandAnalyzer.toJson(analysis, meta, Instant.now().toString(), OPT_IN_NOTE);
    root.put("elapsedMillis", elapsedMillis);
    root.put("failedCalls", 0);
    root.put("throttledRetries", stats.throttledRetries);
    root.put("paceMillis", PACE_MILLIS);
    root.put("runHost", System.getProperty("os.name", "unknown"));
    root.put("totalNanos", System.nanoTime() - startedAtNanos);

    Path outPath = resolveOutPath();
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(
        outPath,
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root),
        StandardCharsets.UTF_8);
    System.out.println(
        "[near-dup-calibration] 锚已落盘: "
            + outPath.toAbsolutePath()
            + "（images="
            + corpus.imageCount()
            + " calls="
            + calls
            + " 耗时="
            + elapsedMillis
            + " ms）");
  }

  /**
   * 门控：未 opt-in 或未配 key 时整类跳过（默认零外呼、不落文件）。
   *
   * @param env 环境变量视图（真跑传 {@code System.getenv()}）
   * @param apiKey 解析后的 key（env 或 .env）
   */
  static void requireRealRun(Map<String, String> env, String apiKey) {
    Assumptions.assumeTrue(
        "1".equals(env.get(OPT_IN_ENV)), "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过近重复三档定标真跑（默认 CI 零外呼）");
    Assumptions.assumeTrue(
        StringUtils.hasText(apiKey), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过近重复三档定标真跑");
  }

  /** 读 key：优先环境变量，其次仓库根 .env（与既有真跑 IT 同序）；不打印明文、不写进任何文件。 */
  static String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (StringUtils.hasText(fromEnv)) {
      return fromEnv;
    }
    Path dotEnv = Path.of(".env");
    if (Files.exists(dotEnv)) {
      try {
        return Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
            .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
            .findFirst()
            .orElse(null);
      } catch (Exception ignored) {
        // 读不到 .env 就走"跳过"分支
      }
    }
    return null;
  }

  /**
   * 组装待编码图列表（顺序 = base 先、变体后，全确定）。
   *
   * @param corpus 语料
   * @return 图任务
   */
  private static List<ImageJob> jobs(NearDuplicateCorpus.Corpus corpus) {
    Map<String, String> docKeyOfBase = new LinkedHashMap<>();
    List<ImageJob> jobs = new ArrayList<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      docKeyOfBase.put(base.key(), base.docKey());
      jobs.add(new ImageJob(base.key(), base.docKey(), base.png()));
    }
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      jobs.add(
          new ImageJob(
              variant.key(),
              docKeyOfBase.getOrDefault(variant.baseKey(), variant.baseKey()),
              variant.png()));
    }
    return List.copyOf(jobs);
  }

  /**
   * 生成三层样本对（口径写死，不在跑后调参）。
   *
   * @param corpus 语料
   * @param encodings 编码表（图键 → 双向量）
   * @return 样本对
   */
  private static List<Pair> pairs(
      NearDuplicateCorpus.Corpus corpus, Map<String, Encoded> encodings) {
    List<Pair> pairs = new ArrayList<>();
    // 同图档：**每张图**（base 与变体都算）各一对 A/B 再编码——这是同图下限的观测面，
    // 只取 base 会把变体的同图下限漏掉一半以上（本卡真跑首轮就栽在这里，144 vs 24）。
    for (Encoded encoded : encodings.values()) {
      pairs.add(
          new Pair(
              encoded.key(),
              encoded.key(),
              NearDuplicateBandAnalyzer.STRATUM_SAME_IMAGE,
              null,
              ImageCrossModalEvaluator.cosine(encoded.first(), encoded.second())));
    }
    List<Encoded> baseEncodings = new ArrayList<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      baseEncodings.add(encodings.get(base.key()));
    }
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      Encoded base = encodings.get(variant.baseKey());
      Encoded encoded = encodings.get(variant.key());
      pairs.add(
          new Pair(
              variant.baseKey(),
              variant.key(),
              NearDuplicateBandAnalyzer.STRATUM_NEAR_DUPLICATE,
              variant.kind(),
              ImageCrossModalEvaluator.cosine(base.first(), encoded.first())));
    }
    for (int left = 0; left < baseEncodings.size(); left++) {
      for (int right = left + 1; right < baseEncodings.size(); right++) {
        Encoded first = baseEncodings.get(left);
        Encoded second = baseEncodings.get(right);
        String stratum =
            first.docKey().equals(second.docKey())
                ? NearDuplicateBandAnalyzer.STRATUM_SAME_CLASS
                : NearDuplicateBandAnalyzer.STRATUM_CROSS_CLASS;
        pairs.add(
            new Pair(
                first.key(),
                second.key(),
                stratum,
                null,
                ImageCrossModalEvaluator.cosine(first.first(), second.first())));
      }
    }
    for (Encoded encoded : encodings.values()) {
      assertEquals(
          encoded.first().length, encoded.second().length, "同图两次编码的向量维度必须一致: " + encoded.key());
    }
    return List.copyOf(pairs);
  }

  /**
   * 一次真视觉编码（带节奏控制 + 仅限流的有界退避）。
   *
   * <p><b>为什么先 sleep 再调</b>：端点有速率配额，连打会被 {@code 429} 打断；节奏控制是"少花一次全量真跑"的最便宜手段。
   *
   * <p><b>为什么只有 429 重试</b>：{@code 429} 是速率信号、不代表样本质量异常；其余任何失败（超时 / 5xx / 空向量）一律直接抛出，
   * 保持"单次调用失败即整体失败、不落半成品锚"的原始口径。
   *
   * @param provider 模型提供者
   * @param png 图字节
   * @param key 图键（失败与重试留痕用）
   * @param stats 本次运行的限流留痕
   * @return 调用结果
   */
  private static ModelCallResult<float[]> embed(
      ModelProvider provider, byte[] png, String key, RunStats stats) {
    for (int attempt = 0; ; attempt++) {
      pause(PACE_MILLIS);
      try {
        return provider.embedImage(png, IMAGE_MIME);
      } catch (HttpClientErrorException exception) {
        boolean throttled = exception.getStatusCode().value() == 429;
        if (!throttled || attempt >= THROTTLE_RETRIES) {
          throw exception;
        }
        long wait = THROTTLE_BACKOFF_MILLIS << attempt;
        stats.throttledRetries++;
        System.out.println(
            "[near-dup-calibration] 429 限流，"
                + key
                + " 第 "
                + (attempt + 1)
                + " 次退避 "
                + wait
                + " ms 后重试");
        pause(wait);
      }
    }
  }

  /** 睡眠（被中断即失败，不静默吞）。 */
  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("真跑被中断（不落半成品锚）", exception);
    }
  }

  /** 本次运行的限流留痕（只用于落盘与回报，不参与任何定标计算）。 */
  static final class RunStats {

    /** 429 退避重试次数（成功的那次调用不计）。 */
    private int throttledRetries;
  }

  /** 取向量：空即抛（不写半成品锚）。 */
  private static float[] vectorOf(ModelCallResult<float[]> result, String key) {
    float[] vector = result == null ? null : result.vector();
    assertNotNull(vector, "视觉 embedding 未返回向量（单次调用失败即整体失败）: " + key);
    assertTrue(vector.length > 0, "视觉 embedding 返回零维向量: " + key);
    return vector;
  }

  private static int count(List<Pair> pairs, String stratum) {
    return (int) pairs.stream().filter(pair -> stratum.equals(pair.stratum())).count();
  }

  private static long elapsedMillis(long startedAtMillis) {
    return System.currentTimeMillis() - startedAtMillis;
  }

  private Path resolveOutPath() {
    String override = System.getProperty(OUT_PROP);
    return override == null || override.isBlank() ? DEFAULT_ANCHOR_PATH : Path.of(override);
  }

  /**
   * 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode，同既有真跑 IT。
   *
   * @param apiKey DashScope API key
   * @return 真实 Provider（视觉 embedding 走原生联合端点）
   */
  private ModelProvider provider(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    // 超时口径按实测放宽（不改任何定标口径）：本机 JDK HttpClient 的「进程内首次连接」实测出现过 >10s 的挂起
    // （裸 Socket 连同一 IP 仅 ~50ms，.NET/curl 正常；288 次调用里首次栽在这上面就是整轮重烧），
    // 故连接 60s、整体 120s。语义不变：单次调用最终失败仍整体失败、不落半成品锚。
    properties.setTimeoutSeconds(120);
    properties.setConnectTimeoutSeconds(60);
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    Environment environment =
        new MockEnvironment()
            .withProperty("spring.ai.dashscope.api-key", apiKey)
            .withProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        providerOf(nativeChat), providerOf(embeddingModel), properties, environment);
  }

  private <T> ObjectProvider<T> providerOf(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  /**
   * 一张待编码的图。
   *
   * @param key 图键
   * @param docKey 出版物键（同类/跨类判定）
   * @param png PNG 字节
   */
  record ImageJob(String key, String docKey, byte[] png) {}

  /**
   * 一张图的双向量编码。
   *
   * @param key 图键
   * @param docKey 出版物键
   * @param first 第一次编码向量（参与跨图比较）
   * @param second 第二次编码向量（与 first 的余弦 = 同图下限观测）
   */
  record Encoded(String key, String docKey, float[] first, float[] second) {}
}
