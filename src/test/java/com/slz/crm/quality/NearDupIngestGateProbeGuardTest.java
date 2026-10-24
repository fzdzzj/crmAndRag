package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * S0 闸门探针守卫（near-dup-ingest-cooccurrence 任务 A.3/A.4 + B.11，默认测试集内跑，只读，零外呼）。
 *
 * <ul>
 *   <li><b>R0 合成 PDF 可行性</b>：3 图合成 PDF → 生产 {@code PdfImageExtractor} 抠图，图数与像素尺寸逐一对上；
 *   <li><b>边界覆盖</b>：单图 PDF、&gt;20 图 PDF（构造侧切两份 + 提取侧帽截断）、非 PDF 字节（生产 buildSlices 返回空表且零外呼）；
 *   <li><b>锚对账</b>：定标锚 SHA 实算、影子分类与闸门结论逐图一致（重算）、gatePassRate/gateVerdict 由逐图结果重算、 externalCalls
 *       与分项对账且不超硬帽（写 0 或硬写即红）。
 * </ul>
 */
class NearDupIngestGateProbeGuardTest {

  private static final Path ANCHOR = NearDupIngestGateProbeIT.ANCHOR_PATH;

  private static final Path CALIBRATION_ANCHOR =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode anchor;

  @BeforeAll
  static void loadAnchor() throws Exception {
    if (Files.exists(ANCHOR)) {
      anchor = MAPPER.readTree(Files.readAllBytes(ANCHOR));
    }
  }

  @Test
  @DisplayName("R0：3 图合成 PDF → 生产提取器抠图，图数与像素尺寸逐一对上（零外呼）")
  void syntheticPdfRoundTripFaithful() throws Exception {
    List<byte[]> inputs = new ArrayList<>();
    List<int[]> dims = new ArrayList<>();
    for (int index = 0; index < 3; index++) {
      int edge = 64 + index * 13;
      inputs.add(programmaticPng(edge, edge + 7, index));
      dims.add(new int[] {edge, edge + 7});
    }
    byte[] pdf = NearDupIngestPdfBuilder.buildPdfs(inputs).get(0);
    List<RealPdfAssets.Extracted> extracted =
        RealPdfAssets.extract(pdf, NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF);
    assertEquals(3, extracted.size(), "3 图 PDF 必须抠出 3 图");
    for (int index = 0; index < dims.size(); index++) {
      assertEquals(dims.get(index)[0], extracted.get(index).width(), "抠图宽度漂移 @" + index);
      assertEquals(dims.get(index)[1], extracted.get(index).height(), "抠图高度漂移 @" + index);
    }
  }

  @Test
  @DisplayName("边界：单图 PDF；>20 图构造侧切两份且提取侧帽截断；非 PDF 字节走生产旁路返回空表且零外呼")
  void builderBoundaries() throws Exception {
    byte[] single = programmaticPng(70, 70, 0);
    byte[] singlePdf = NearDupIngestPdfBuilder.buildSingleImagePdf(single);
    assertEquals(1, RealPdfAssets.extract(singlePdf, 20).size(), "单图 PDF 必须抠出 1 图");

    List<byte[]> twentyOne = new ArrayList<>();
    for (int index = 0; index < 21; index++) {
      twentyOne.add(programmaticPng(70, 70, index));
    }
    List<byte[]> pdfs = NearDupIngestPdfBuilder.buildPdfs(twentyOne);
    assertEquals(
        2, pdfs.size(), "21 图必须切成两份（帽 " + NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF + "）");
    assertEquals(
        20,
        RealPdfAssets.extract(pdfs.get(0), NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF).size(),
        "第一份必须被提取帽截断在 20 图");
    assertEquals(
        1,
        RealPdfAssets.extract(pdfs.get(1), NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF).size(),
        "第二份恰 1 图");

    // 非 PDF 字节：生产 buildSlices 的旁路语义 = 整体跳过返回空表（提取异常被生产 catch 吞掉，零外呼零落盘）
    CountingStubProvider stub = new CountingStubProvider();
    PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
    PdfImageSliceIngestService service =
        new PdfImageSliceIngestService(
            stub,
            RagBenchmarkPipelineFactory.provider(dynamicConfig()),
            provider((com.slz.crm.platform.contract.TokenUsageRecorder) null),
            provider(storage));
    byte[] garbage = "this is not a pdf".getBytes(StandardCharsets.UTF_8);
    assertTrue(
        service.buildSlices(garbage, "garbage.pdf", "IMAGE", "9905").isEmpty(),
        "非 PDF 字节必须经生产旁路返回空表");
    assertEquals(0, stub.calls.get(), "非 PDF 字节不得触发任何模型外呼（旁路吞异常但外呼必须为零）");
  }

  @Test
  @DisplayName("S0 锚对账：定标锚 SHA 实算 + 影子分类重算一致 + gatePassRate/verdict 重算 + 外呼对账不超帽")
  void anchorAccounting() throws Exception {
    assertNotNull(anchor, "S0 锚缺失（未真跑或被删）: " + ANCHOR.toAbsolutePath());
    JsonNode sourceAnchors = anchor.path("sourceAnchors");
    assertEquals(
        RealPdfAssets.sha256(Files.readAllBytes(CALIBRATION_ANCHOR)),
        sourceAnchors.path("calibrationAnchorSha256").asText(),
        "定标锚 SHA-256 与实算不一致（上游锚字节漂移）");

    JsonNode images = anchor.path("images");
    assertEquals(6, images.size(), "探针必须 6 图");
    int gatePassCount = 0;
    for (JsonNode image : images) {
      boolean gatePass = image.path("gatePass").asBoolean();
      JsonNode visionError = image.path("visionErrorMessage");
      String expectedReason =
          NearDupIngestGateProbeIT.classify(
              visionError.isMissingNode() || visionError.isNull() ? null : visionError.asText(),
              image.path("vlmMetrics").path("compactChars").asInt(0),
              image.path("vlmMetrics").path("unclearMarkCount").asInt(0),
              image.path("vlmMetrics").path("hanCount").asInt(0));
      assertEquals(
          expectedReason,
          image.path("failReason").asText(null),
          "failReason 重算不一致: " + image.path("assetKey").asText());
      assertEquals(
          gatePass, expectedReason == null, "影子分类与闸门结论不一致: " + image.path("assetKey").asText());
      assertEquals(
          gatePass,
          !image.path("chunkId").isNull(),
          "过闸门图必须有 chunkId，拒图必须为 null: " + image.path("assetKey").asText());
      if (gatePass) {
        gatePassCount++;
      }
    }
    assertEquals(
        gatePassCount, anchor.path("gatePassCount").asInt(-1), "gatePassCount 必须由逐图结果重算得出");
    assertEquals(
        gatePassCount / 6.0D,
        anchor.path("gatePassRate").asDouble(-1D),
        0D,
        "gatePassRate 必须由逐图结果重算得出");
    String expectedVerdict =
        gatePassCount >= NearDupIngestGateProbeIT.GATE_PASS_MIN
            ? "PROCEED-S1"
            : "STOP-GATE-REJECTION";
    assertEquals(
        expectedVerdict, anchor.path("gateVerdict").asText(), "gateVerdict 必须与 gatePassRate 自洽");

    int externalCalls = anchor.path("externalCalls").asInt(-1);
    assertTrue(externalCalls > 0, "externalCalls 必须是实测正数（写 0 即假绿），实测 " + externalCalls);
    JsonNode calls = anchor.path("callBreakdown");
    int vision = calls.path("visionCalls").asInt(-1);
    int embedImage = calls.path("embedImageCalls").asInt(-1);
    int embed = calls.path("embedCalls").asInt(-1);
    assertEquals(
        externalCalls, vision + embedImage + embed, "callBreakdown 三分项之和必须等于 externalCalls");
    assertEquals(6, vision, "vision 外呼必须每图恰 1 次");
    assertEquals(gatePassCount, embedImage, "embedImage 外呼必须等于过闸门图数（闸门拒图连带丢图像向量）");
    assertEquals(gatePassCount, embed, "文本 embed 外呼必须等于过闸门图数");
    assertTrue(externalCalls <= NearDupIngestGateProbeIT.S0_CALL_CAP, "S0 外呼实测超硬帽");
  }

  /** 程序化确定性样张（≥64px 短边，唯一底色区分图序）。 */
  private static byte[] programmaticPng(int width, int height, int ordinal) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(
          new Color(
              (ordinal * 37 + 11) % 256, (ordinal * 53 + 29) % 256, (ordinal * 71 + 47) % 256));
      graphics.fillRect(0, 0, width, height);
      graphics.setColor(Color.BLACK);
      graphics.fillRect(4, 4, Math.max(4, width / 4), Math.max(4, height / 4));
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public T getIfAvailable() {
        return value;
      }

      @Override
      public T getIfUnique() {
        return value;
      }
    };
  }

  private static DynamicConfigService dynamicConfig() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(
        PdfImageSliceIngestService.MAX_IMAGES_KEY, NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF);
    return RagBenchmarkPipelineFactory.dynamicConfig(matrix);
  }

  /** 计数桩 provider：任何外呼先计数再抛（守卫里计数必须恒 0；buildSlices 会吞异常，计数是唯一暴露面）。 */
  private static final class CountingStubProvider implements ModelProvider {

    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String provider() {
      return "guard-stub";
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt, ModelCallOptions options) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt, ModelCallOptions options) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      calls.incrementAndGet();
      throw new IllegalStateException("守卫不允许外呼");
    }
  }
}
