package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.retrieval.SourceReferenceFactory;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * 真实 PDF 资产可提取性 + 默认零外呼单测（production-gray-real-run 任务 4.1，surefire，默认门禁恒跑）。
 *
 * <p><b>为什么「看着有图」不算数</b>：网上 PDF 的图常见是矢量图（内容流绘制，提取器看不见）、&lt;64px 装饰图、整页扫描（一页一张大图，语义上是「页」 而不是「插图」）、或
 * JPX/JBIG2 解不开——这四类都会让「肉眼有图」的文档在 {@code PdfImageExtractor} 上返回空表。本类逐份调<b>生产提取器</b>
 * 实测，把「可提取图数」变成入库硬条件，而不是发布者页面上的说明。
 *
 * <p><b>三条判据</b>（任务 1.1 原话）：① 每份 PDF 可提取 ≥2 张 {@code PDImageXObject} 位图；② 每张最短边 ≥64px（生产 {@code
 * MIN_EDGE_PX} 同值）且 PNG 可解码；③ 单份 ≤5MB，SHA-256 与授权登记表一致（资产不可被悄悄替换）。
 *
 * <p><b>零外呼</b>：摄取链用 {@link ScriptedProvider}（fake 描述 + fake 向量）驱动，验证「真实 PDF 走通生产 {@code
 * buildSlices}（总开关开 + 视觉路开）并产出切片」，全程不碰网络——真跑在 {@code ProductionGrayRealRunIT}（opt-in {@code
 * RAG_VISION_EMBED_REAL}）。
 */
@DisplayName("真实 PDF 资产：可提取性 / 授权登记 / 零外呼摄取形态")
class RealPdfAssetsTest {

  /** fake 描述（去空白后 ≥ 生产闸门 40 字；不含「（截图不清）」，故必过闸门）。 */
  private static final String FAKE_DESCRIPTION =
      "示意图：横向坐标轴与三条柱形，标注单位为月度工单量，图例位于右上角，" + "纵轴刻度为 0 到 120，柱形颜色依次为深蓝、中蓝、浅蓝，页面下方有一行脚注说明数据来源。";

  @Test
  @DisplayName("授权登记表：2~3 份、URL/授权/摘要齐备、体积 ≤5MB、SHA-256 与登记一致")
  void manifestShouldRegisterTwoToThreePublicDomainPdfs() throws Exception {
    JsonNode manifest = RealPdfAssets.manifest();
    List<RealPdfAssets.Asset> assets = RealPdfAssets.assets();
    assertTrue(
        assets.size() >= 2 && assets.size() <= 3,
        "入库真实 PDF 应为 2~3 份（owner 口径：控制 repo 负担），实测 " + assets.size() + " 份");
    assertEquals(
        "real-pdf-sources-v1",
        manifest.path("schemaVersion").asText(),
        "授权登记表 schema 版本必须与单测同源，改结构要同步改这里");

    for (RealPdfAssets.Asset asset : assets) {
      Path path = RealPdfAssets.DIR.resolve(asset.file());
      assertTrue(Files.exists(path), "登记在册的 PDF 必须真实存在: " + path.toAbsolutePath());
      long size = Files.size(path);
      assertTrue(
          size <= RealPdfAssets.MAX_BYTES, asset.key() + " 体积 " + size + " 超 5MB 上限（repo 负担）");
      assertEquals(asset.bytes(), size, asset.key() + " 登记体积与实际不符（资产被替换？）");
      assertEquals(
          asset.sha256(),
          RealPdfAssets.sha256(Files.readAllBytes(path)),
          asset.key() + " SHA-256 与登记不符（防资产被悄悄替换）");
      assertTrue(
          asset.sourceUrl().startsWith("https://"),
          asset.key() + " 来源 URL 必须可溯源（https）: " + asset.sourceUrl());
      assertFalse(asset.license().isBlank(), asset.key() + " 授权结论不能为空（本卡要求可确认合法入仓）");
      assertFalse(asset.licenseEvidence().isBlank(), asset.key() + " 授权依据页不能为空");
      assertFalse(asset.licenseQuote().isBlank(), asset.key() + " 授权原文摘录不能为空");
      assertTrue(asset.pages() > 0, asset.key() + " 页数必须登记");
      assertTrue(asset.verifiedRasterImages() >= 2, asset.key() + " 登记的可提取位图数必须 ≥2（任务 1.1 判据）");
    }
  }

  @Test
  @DisplayName("可提取性：每份 PDF 生产提取器实测 ≥2 张位图，最短边 ≥64px 且 PNG 可解码")
  void everyPdfShouldYieldAtLeastTwoRealRasterImages() {
    for (RealPdfAssets.Asset asset : RealPdfAssets.assets()) {
      byte[] pdf = asset.pdf();
      // 上限给到 500 而不是生产帽 3：这里要验的是「这份 PDF 究竟能提出几张」，帽会掩盖真实图数
      List<RealPdfAssets.Extracted> extracted = RealPdfAssets.extract(pdf, 500);
      assertTrue(
          extracted.size() >= 2,
          asset.key()
              + " 生产提取器只提到 "
              + extracted.size()
              + " 张（<2）：该 PDF 的图可能是矢量图 / <64px 装饰图 / 整页扫描，不适合本卡语料");
      assertEquals(
          asset.verifiedRasterImages(),
          extracted.size(),
          asset.key() + " 登记的 verifiedRasterImages 与实测不符（换资产必须重测再登记）");
      for (RealPdfAssets.Extracted image : extracted) {
        assertTrue(
            Math.min(image.width(), image.height()) >= RealPdfAssets.MIN_EDGE_PX,
            asset.key()
                + " 第 "
                + image.pageNo()
                + " 页图 "
                + image.width()
                + "x"
                + image.height()
                + " 最短边 < 64px，属装饰图，不该进语料");
        BufferedImage decoded = decode(image.png());
        assertNotNull(decoded, asset.key() + " 提取出的 PNG 解不开（不是真位图？）");
        assertEquals(image.width(), decoded.getWidth(), asset.key() + " 提取宽度与 PNG 实际宽度不一致");
        assertTrue(image.png().length > 1024, asset.key() + " PNG 体积过小，疑似空图");
      }
      // 提取顺序必须可复现（页升序 + 页内 XObject 名升序），否则切片/黄金 id 映射不可判
      List<Integer> pageNos = extracted.stream().map(RealPdfAssets.Extracted::pageNo).toList();
      assertEquals(pageNos.stream().sorted().toList(), pageNos, asset.key() + " 提取顺序必须按页升序（生产口径）");
    }
  }

  @Test
  @DisplayName("零外呼摄取形态：真实 PDF 走生产 buildSlices（开关全开）用 fake 模型产出切片")
  void realPdfShouldFlowThroughProductionSlicesWithFakeProvider() {
    RealPdfAssets.Asset asset = RealPdfAssets.assets().get(0);
    ScriptedProvider provider = new ScriptedProvider();
    PdfImageSliceIngestService ingestService =
        new PdfImageSliceIngestService(
            provider,
            provider(dynamicConfig(true)),
            provider((TokenUsageRecorder) null),
            provider(PdfIngestImageFixture.inMemoryStorage()));

    int extracted =
        RealPdfAssets.extract(asset.pdf(), RealPdfAssets.MAX_IMAGES_PER_DOCUMENT).size();
    List<String> sliceTexts =
        ingestService
            .buildSlices(asset.pdf(), asset.file(), ImageCrossModalFixture.IMAGE_CATEGORY, "9903")
            .stream()
            .map(chunk -> chunk.text())
            .toList();

    assertTrue(extracted >= 2, asset.key() + " 至少能提到 2 张图，否则真实链路无从判据");
    assertEquals(extracted, sliceTexts.size(), "fake 描述恒过质量闸门，故提取几张就该出几个切片（少于此数说明切片/落盘段掉了图）");
    // fake 模型计数即真实调用点：描述 1 张 1 次、视觉向量 1 张 1 次，全程零网络
    assertEquals(extracted, provider.visionCalls(), "每张提取图都应被请求一次描述");
    assertEquals(extracted, provider.embedImageCalls(), "每张过闸图都应请求一次视觉向量");
    assertEquals(0, provider.embedCalls(), "本轮不编码描述文本，文本 embedding 应为 0");
    sliceTexts.forEach(text -> assertEquals(FAKE_DESCRIPTION, text, "切片正文必须等于描述（生产链路把描述当正文）"));
  }

  @Test
  @DisplayName("默认关闭：真实 PDF 在总开关关闭时零外呼零切片")
  void disabledSwitchShouldNotCallAnyModel() {
    ScriptedProvider provider = new ScriptedProvider();
    PdfImageSliceIngestService ingestService =
        new PdfImageSliceIngestService(
            provider,
            provider(dynamicConfig(false)),
            provider((TokenUsageRecorder) null),
            provider(PdfIngestImageFixture.inMemoryStorage()));
    RealPdfAssets.Asset asset = RealPdfAssets.assets().get(0);
    assertTrue(
        ingestService
            .buildSlices(asset.pdf(), asset.file(), ImageCrossModalFixture.IMAGE_CATEGORY, "9903")
            .isEmpty(),
        "总开关关闭时必须返回空表");
    assertEquals(0, provider.totalCalls(), "总开关关闭时零外呼（默认 CI 零成本）");
  }

  @Test
  @DisplayName("黄金引用：由切片行主键派生，口径 = 生产 IMAGE: 前缀")
  void goldenReferenceShouldUseProductionImagePrefix() {
    assertEquals(
        SourceReferenceFactory.IMAGE_CHUNK_PREFIX + "9301",
        ProductionGrayRealRunIT.goldenIdOf(9301L),
        "黄金引用 id 必须由生产前缀派生（IMAGE:{行主键}），合成键会让命中判成零召回");
  }

  // ---------- 测试替身 ----------

  private static BufferedImage decode(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (Exception exception) {
      return null;
    }
  }

  /** 开关矩阵：总开关与视觉路同开同关；图片数帽按成本帽取 3。 */
  private static DynamicConfigService dynamicConfig(boolean enabled) {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, enabled);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, enabled);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, RealPdfAssets.MAX_IMAGES_PER_DOCUMENT);
    return RagBenchmarkPipelineFactory.dynamicConfig(matrix);
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

  /**
   * 零外呼的脚本化 Provider（fake 向量回函）：描述恒过闸门、向量恒为固定向量。
   *
   * <p>为什么不用 Mockito：这里要验的是「调用点真的被走到且次数可数」，脚本实现同时给出计数与返回值，比 mock 的 verify 更直白。
   */
  private static final class ScriptedProvider implements ModelProvider {

    private final AtomicInteger visionCalls = new AtomicInteger();

    private final AtomicInteger embedImageCalls = new AtomicInteger();

    private final AtomicInteger embedCalls = new AtomicInteger();

    int visionCalls() {
      return visionCalls.get();
    }

    int embedImageCalls() {
      return embedImageCalls.get();
    }

    int embedCalls() {
      return embedCalls.get();
    }

    int totalCalls() {
      return visionCalls.get() + embedImageCalls.get() + embedCalls.get();
    }

    @Override
    public String provider() {
      return "scripted";
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      throw new UnsupportedOperationException("本单测不涉及对话调用");
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      return Flux.empty();
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      embedCalls.incrementAndGet();
      return ModelCallResult.ofVector(new float[] {1F, 0F, 0F, 0F}, "scripted-embed", 1L, 1L);
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      embedImageCalls.incrementAndGet();
      return ModelCallResult.ofVector(
          new float[] {0F, 1F, 0F, 0F}, "scripted-vision-embed", 1L, 1L);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      visionCalls.incrementAndGet();
      return ModelCallResult.ofText(FAKE_DESCRIPTION, "scripted-vision", 1L, 1L, 2L);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      return vision(prompt);
    }
  }
}
