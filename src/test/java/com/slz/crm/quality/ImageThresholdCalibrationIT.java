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
import com.slz.crm.quality.ImageThresholdCalibrationFixture.Sample;
import com.slz.crm.quality.ImageThresholdCalibrator.Calibration;
import com.slz.crm.quality.ImageThresholdCalibrator.Encoding;
import com.slz.crm.quality.ImageThresholdCalibrator.Pair;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * 异图硬阈值定标真跑（multimodal-gray-and-eval-threshold 任务 2.2，failsafe IT，A 档真跑）。
 *
 * <p><b>门控</b>：{@code RAG_VISION_EMBED_REAL=1} 且存在 {@code DASHSCOPE_API_KEY}（env 或仓库根
 * .env）才执行；否则整类按 假设跳过、构建不失败——<b>默认 CI 与本地 verify 恒零外呼</b>（沿用 {@code VisionEmbeddingRealIT} / {@code
 * ImageCrossModalBenchmarkIT} 的同一枚 opt-in 键，不新开键）。
 *
 * <p><b>语料两层</b>（跑之前固定，不按结果调参）：
 *
 * <ol>
 *   <li><b>程序化</b>：{@link ImageThresholdCalibrationFixture} 3 图型族 × 3 实例 = 9 图，作稳定底座对照；
 *   <li><b>真实</b>：{@link RealPdfAssets} 的真实公有领域 PDF 经<b>生产提取器</b> {@code PdfImageExtractor#extract}
 *       各取前 3 张位图（同 {@code production-gray-real-run} 的成本帽口径），按 SHA-256 去重——<b>同字节不算异图</b>。
 *       同一出版物的图归为一类（同类档），跨出版物为跨类档。
 * </ol>
 *
 * <p><b>每张图编码两次</b>（同字节再编码）：A/B 两向量的余弦就是该图的「同图下限」观测；A 向量参与同类/跨类比较。 因此外呼次数 = 图数 ×
 * 2（成本按调用次数留痕，embedding 端点不回流 token， 见 {@code docs/token-metering.md}）。
 *
 * <p><b>判据</b>（跑之前固定）：真实语料必须可分（异图上限 &lt; 同图下限）且判别阈值落在严格区间 {@code (异图上限, 同图下限]} 内；
 * 不可分时本用例<b>红</b>——那说明真实语料里「不同图」的视觉余弦压过了「同图再编码」，硬阈值不可用，必须如实停步而不是硬凑一个数。
 *
 * <p><b>落盘</b>：{@code docs/rag-quality/image-threshold-calibration-v1.json}（可用 {@code
 * -Drag.image.threshold.out} 覆盖路径）；<b>不碰</b> {@code image-baseline-v1} / {@code
 * production-gray-cost-v1} / {@code baseline-v1~v7}。
 */
@DisplayName("异图硬阈值定标真跑（RAG_VISION_EMBED_REAL 显式 opt-in）")
class ImageThresholdCalibrationIT {

  /** 真跑 opt-in 键（沿用同一枚键，不新增）。 */
  static final String OPT_IN_ENV = "RAG_VISION_EMBED_REAL";

  /** 定标锚落盘路径（独立文件，不覆盖任何既有基线）。 */
  static final Path DEFAULT_REPORT_PATH =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v1.json");

  /** 落盘路径覆盖属性（与既有基准的 {@code rag.*.benchmark.out} 同形）。 */
  private static final String OUT_PROP = "rag.image.threshold.out";

  /** compatible-mode 端点（与既有真跑 IT 相同的装配方式）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 视觉 embedding 的 MIME（生产 {@code PdfImageSliceIngestService.IMAGE_MIME_TYPE} 同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** 落盘留痕：opt-in 与语料口径（复核判据直接读这一句）。 */
  private static final String OPT_IN_NOTE =
      "RAG_VISION_EMBED_REAL=1 + DASHSCOPE_API_KEY 显式真跑；语料 = 9 张程序化确定性图（3 图型族 × 3 实例，对照）+ "
          + "真实公有领域 PDF 经生产 PdfImageExtractor 各取前 3 张位图（按 SHA-256 去重，同类=同一出版物，跨类=跨出版物）；"
          + "每图经真视觉 embedding（multimodal-embedding-v1）编码两次，同图档 = 两次编码的余弦";

  @Test
  @DisplayName("真跑：定出真实语料同图下限 / 异图上限 / 判别阈值并落锚")
  void calibrateRealCorpusThreshold() throws Exception {
    requireRealRun(System.getenv(), resolveApiKey());
    ModelProvider provider = provider(resolveApiKey());

    List<Encoding> encodings = new ArrayList<>();
    for (Sample sample : ImageThresholdCalibrationFixture.samples()) {
      encodings.add(encode(provider, "programmatic", sample.key(), sample.klass(), sample.png()));
    }
    int realImages = addRealCorpus(provider, encodings);
    assertTrue(realImages > 0, "真实 PDF 语料未提取到任何位图（资产或生产提取器异常）");

    List<Pair> pairs = ImageThresholdCalibrator.pairs(encodings);
    Map<String, Calibration> calibrations = ImageThresholdCalibrator.calibrateByCorpus(pairs);
    Calibration real = calibrations.get("real-pdf");
    assertNotNull(real, "真实语料定标块缺失（语料分组异常）");
    System.out.println("[threshold-calibration] programmatic=" + calibrations.get("programmatic"));
    System.out.println("[threshold-calibration] real-pdf=" + real);

    // 判据：真实语料可分，且阈值严格落在 (异图上限, 同图下限]
    assertTrue(
        real.separable(),
        "真实语料不可分：同图下限="
            + real.sameImageFloor()
            + " 异图上限="
            + real.crossImageCeiling()
            + "（硬阈值不可用，如实停步）");
    assertTrue(
        real.crossImageCeiling() < real.sameImageFloor(),
        "异图上限必须严格低于同图下限: " + real.crossImageCeiling() + " vs " + real.sameImageFloor());
    double threshold = real.recommendedThreshold();
    assertTrue(
        threshold > real.crossImageCeiling() && threshold <= real.sameImageFloor(),
        "判别阈值必须落在 (异图上限, 同图下限]: threshold="
            + threshold
            + " ceiling="
            + real.crossImageCeiling()
            + " floor="
            + real.sameImageFloor());
    for (Calibration calibration : calibrations.values()) {
      assertEquals(
          calibration.sameImage().count(),
          encodings.stream().filter(e -> e.corpus().equals(calibration.corpus())).count(),
          "同图档对数必须等于该语料图数（每图一对 A/B 再编码）");
    }

    ObjectNode root =
        ImageThresholdCalibrator.toJson(calibrations, pairs, OPT_IN_NOTE, Instant.now().toString());
    root.put("programmaticImageCount", ImageThresholdCalibrationFixture.samples().size());
    root.put("realImageCount", realImages);
    root.put("imageEmbeddingCalls", encodings.size() * 2);
    root.put(
        "tag",
        "真实语料阈值锚：同图下限 "
            + real.sameImageFloor()
            + " / 异图上限 "
            + real.crossImageCeiling()
            + " / 判别阈值 "
            + real.recommendedThreshold());
    Path outPath = resolveOutPath();
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(
        outPath,
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root),
        StandardCharsets.UTF_8);
    System.out.println("[threshold-calibration] 锚已落盘: " + outPath.toAbsolutePath());
  }

  /**
   * 真实语料入库：真实 PDF → 生产提取器 → 按 SHA-256 去重 → 每图两次真视觉编码。
   *
   * @param provider 模型提供者
   * @param encodings 编码收集表
   * @return 实际参与定标的真实图数
   */
  private int addRealCorpus(ModelProvider provider, List<Encoding> encodings) {
    Set<String> seenDigests = new LinkedHashSet<>();
    int count = 0;
    for (RealPdfAssets.Asset asset : RealPdfAssets.assets()) {
      for (RealPdfAssets.Extracted extracted :
          RealPdfAssets.extract(asset.pdf(), RealPdfAssets.MAX_IMAGES_PER_DOCUMENT)) {
        if (!seenDigests.add(RealPdfAssets.sha256(extracted.png()))) {
          continue;
        }
        String key = asset.key() + "-p" + extracted.pageNo() + "-i" + count;
        encodings.add(encode(provider, "real-pdf", key, asset.key(), extracted.png()));
        count++;
      }
    }
    return count;
  }

  /** 单图两次真视觉编码（A/B 两向量）。 */
  private static Encoding encode(
      ModelProvider provider, String corpus, String key, String klass, byte[] png) {
    ModelCallResult<float[]> first = provider.embedImage(png, IMAGE_MIME);
    ModelCallResult<float[]> second = provider.embedImage(png, IMAGE_MIME);
    assertNotNull(first.vector(), "视觉 embedding 未返回向量: " + key);
    assertNotNull(second.vector(), "视觉 embedding 未返回向量: " + key);
    assertEquals(first.vector().length, second.vector().length, "同图两次编码的向量维度必须一致: " + key);
    return new Encoding(corpus, key, klass, first.vector(), second.vector());
  }

  /**
   * 门控：未 opt-in 或未配 key 时整类跳过（默认零外呼）。
   *
   * @param env 环境变量视图（真跑传 {@code System.getenv()}）
   * @param apiKey 解析后的 key（env 或 .env）
   */
  static void requireRealRun(Map<String, String> env, String apiKey) {
    Assumptions.assumeTrue(
        "1".equals(env.get(OPT_IN_ENV)), "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过异图阈值定标真跑（默认 CI 零外呼）");
    Assumptions.assumeTrue(
        StringUtils.hasText(apiKey), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过异图阈值定标真跑");
  }

  /** 读 key：优先环境变量，其次仓库根 .env（与既有真跑 IT 同序）。 */
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

  private Path resolveOutPath() {
    String override = System.getProperty(OUT_PROP);
    return override == null || override.isBlank() ? DEFAULT_REPORT_PATH : Path.of(override);
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
    properties.setTimeoutSeconds(60);
    properties.setConnectTimeoutSeconds(10);
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
}
