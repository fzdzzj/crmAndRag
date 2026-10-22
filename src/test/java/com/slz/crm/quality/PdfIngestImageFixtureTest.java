package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.ImageVectorFlushSupport;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.SourceReferenceFactory;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.quality.ImageCrossModalEvaluator.Evaluation;
import com.slz.crm.quality.ImageCrossModalEvaluator.Probe;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * PDF 摄取链图文评测的零外呼单测（pdf-ingest-image-eval 任务 3.1）。
 *
 * <p><b>四个必测面</b>：
 *
 * <ol>
 *   <li><b>PDF fixture 生成器</b>：三页各一张 {@code PDImageXObject}、最短边够得上 {@code PdfImageExtractor}
 *       的最小边闸门、像素确定性 （同一键两次生成逐字节一致）——fixture 不稳则整条评测的基线不可比；
 *   <li><b>摄取链（fake 向量回函）</b>：{@code buildSlices} 真的把三张图抽出来并暂存视觉向量、{@link
 *       ImageVectorFlushSupport#flush} 真的按切片行回填并落 image 集合，payload 与黄金 id 口径（{@code
 *       IMAGE:{行主键}}）逐字段对齐——<b>零真外呼</b>（provider 全是 Mockito 桩，断言只数调用次数）；
 *   <li><b>三型判分口径</b>：直接用 {@link ImageCrossModalEvaluator} 的余弦判据判"同图高 / 异图低"，边界值按阈值两侧各取一例，锁住 "命中 ∧
 *       拒答 ∧ 间隔 ≥ 0" 的合取语义；
 *   <li><b>无 key / 未 opt-in 跳过</b>：真跑门控 {@code ImageCrossModalBenchmarkIT#requireRealRun} 在两种缺条件时都抛
 *       {@link TestAbortedException}（假设跳过而非失败），保证默认 CI 与本地 verify 恒零外呼。
 * </ol>
 */
@DisplayName("PDF 摄取链图文评测：fixture / 摄取链 / 判分口径 / 跳过口径")
class PdfIngestImageFixtureTest {

  /** fake VLM 回函：去空白后长度必须过生产 40 字闸门，内容只需确定性可辨。 */
  private static final String FAKE_DESCRIPTION =
      "三型内嵌图的确定性转写替身：结构示意图含三层节点区块与箭头连线，柱状图含五根柱体与两条坐标轴，纯白底图无任何图形与文字";

  /** 生产 {@code PdfImageExtractor.MIN_EDGE_PX}（包内可见，此处按同值硬写并在此说明来源）。 */
  private static final int EXTRACTOR_MIN_EDGE_PX = 64;

  @Test
  @DisplayName("fixture：三页各一张可抽取的 PDImageXObject，页序与语料顺序一致")
  void pdfCarriesThreeExtractableImagesInCorpusOrder() throws IOException {
    byte[] pdf = PdfIngestImageFixture.pdfBytes();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertEquals(
          PdfIngestImageFixture.keys().size(),
          document.getNumberOfPages(),
          "一图一页：页序即提取顺序，少一页就少一张图");
      List<Integer> minEdges = new ArrayList<>();
      for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
        PDPage page = document.getPage(pageIndex);
        List<COSName> names = new ArrayList<>();
        for (COSName name : page.getResources().getXObjectNames()) {
          names.add(name);
        }
        names.sort((left, right) -> left.getName().compareTo(right.getName()));
        assertEquals(1, names.size(), "每页只放一个图对象：页内顺序不引入歧义");
        PDXObject xobject = page.getResources().getXObject(names.get(0));
        assertInstanceOf(PDImageXObject.class, xobject, "内嵌对象必须是位图 XObject（抽取器只认这个）");
        BufferedImage image = ((PDImageXObject) xobject).getImage();
        minEdges.add(Math.min(image.getWidth(), image.getHeight()));
      }
      for (int minEdge : minEdges) {
        assertTrue(
            minEdge >= EXTRACTOR_MIN_EDGE_PX, "最短边必须 ≥ 抽取器最小边闸门（否则图被视为图标直接丢弃，语料少一张）: " + minEdge);
      }
    }
  }

  @Test
  @DisplayName("fixture：确定性（同一键两次渲染逐字节一致；语料指纹稳定）")
  void fixtureIsDeterministic() {
    for (String key : PdfIngestImageFixture.keys()) {
      assertArrayEquals(
          PdfIngestImageFixture.image(key).png(),
          PdfIngestImageFixture.image(key).png(),
          "同一键两次渲染必须逐字节一致，否则基线跨轮不可比: " + key);
    }
    assertEquals(
        PdfIngestImageFixture.corpusFingerprint(),
        PdfIngestImageFixture.corpusFingerprint(),
        "语料指纹必须稳定");
  }

  @Test
  @DisplayName("fixture：三型互不相同（结构图 / 柱状图 / 纯白），纯白确是零内容易负例")
  void threeTypesAreVisuallyDistinct() {
    BufferedImage structure =
        PdfIngestImageFixture.image(PdfIngestImageFixture.STRUCTURE_KEY).raster();
    BufferedImage bars = PdfIngestImageFixture.image(PdfIngestImageFixture.CROSS_TYPE_KEY).raster();
    BufferedImage blank = PdfIngestImageFixture.image(PdfIngestImageFixture.BLANK_KEY).raster();
    assertTrue(differingRatio(structure, bars) > 0.15D, "结构图与柱状图必须跨图型（像素差异过小说明渲染画重了，异图判据失去位点）");
    assertTrue(differingRatio(structure, blank) > 0.15D, "结构图与纯白底图的像素差异必须显著");
    assertTrue(whiteRatio(blank) > 0.99D, "纯白易负例必须真接近纯白（否则它不再是判据地板）");
  }

  @Test
  @DisplayName("摄取链：fake 向量回函下 buildSlices + flush 真跑通，payload 与黄金 id 口径对齐（零真外呼）")
  void buildSlicesAndFlushRunWithFakeVectors() {
    FakeProvider fake = new FakeProvider();
    DynamicConfigService config = config(true, true);
    PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
    PdfImageSliceIngestService service =
        new PdfImageSliceIngestService(
            fake.provider,
            providerOf(config),
            providerOf((TokenUsageRecorder) null),
            providerOf((FileStorageService) storage));

    List<DocumentChunk> slices =
        service.buildSlices(
            PdfIngestImageFixture.pdfBytes(),
            PdfIngestImageFixture.FILENAME,
            PdfIngestImageFixture.IMAGE_CATEGORY,
            PdfIngestImageFixture.EVAL_KB_ID);

    assertEquals(PdfIngestImageFixture.keys().size(), slices.size(), "三张图必须一张不漏");
    for (int index = 0; index < slices.size(); index++) {
      assertEquals(index + 1, slices.get(index).pageNo(), "切片顺序必须等于页序");
      assertTrue(
          ImageSliceMetadata.isImageSlice(slices.get(index).extraMetadata()),
          "图片切片必须带资产 storageKey");
    }
    assertEquals(slices.size(), storage.size(), "一图一对象：落盘数必须等于切片数");
    assertEquals(slices.size(), fake.visionCalls.get(), "每张图一次描述外呼（此处是桩，零真外呼）");
    assertEquals(slices.size(), fake.embedImageCalls.get(), "每张图一次视觉 embedding（此处是桩，零真外呼）");

    List<DocumentVectorChunkEntity> children = childrenOf(slices);
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    int flushed = ImageVectorFlushSupport.flush(file(), children, slices, imageStore);
    assertEquals(children.size(), flushed, "切片行与 image 集合写入条数必须一一对应");

    List<VectorSearchHit> hits =
        imageStore.search(
            new VectorSearchRequest(new float[] {1F, 1F, 1F, 1F}, 10, null, Map.of()));
    assertEquals(children.size(), hits.size(), "image 集合里必须恰好三张图的视觉向量");
    Set<String> expectedChunkIds =
        PdfIngestImageFixture.keys().stream()
            .map(PdfIngestImageFixture::chunkIdOf)
            .collect(Collectors.toSet());
    for (VectorSearchHit hit : hits) {
      assertTrue(expectedChunkIds.contains(hit.chunkId()), "chunkId 必须是生产切片行主键: " + hit.chunkId());
      assertEquals(
          PdfIngestImageFixture.EVAL_KB_ID,
          hit.metadata().get("knowledgeBaseId"),
          "KB 过滤键必须落到 payload");
      assertEquals(
          ImageSliceMetadata.SOURCE_TYPE_IMAGE,
          hit.metadata().get("sourceType"),
          "视觉向量必须带 IMAGE 来源类型");
      assertEquals(
          hit.chunkId(), hit.metadata().get("imageAssetId"), "资产 id 必须等于切片行主键（引用回查与融合键同源）");
      assertFalse(
          ImageSliceMetadata.storageKeyOf(hit.metadata()).isBlank(),
          "资产 storageKey 必须随 payload 落库");
      SourceReference reference = SourceReferenceFactory.from(hit, hit.score());
      assertEquals(
          "IMAGE:" + hit.chunkId(), reference.chunkId(), "黄金 id 口径必须是生产 IMAGE:{行主键}（否则检索命中也判成零召回）");
    }
  }

  @Test
  @DisplayName("摄取开关：总开关关 → 零切片；视觉开关关 → 有切片但零视觉向量（默认行为零变化）")
  void switchesKeepBypassOff() {
    FakeProvider fake = new FakeProvider();
    PdfImageSliceIngestService disabled =
        new PdfImageSliceIngestService(
            fake.provider,
            providerOf(config(false, false)),
            providerOf((TokenUsageRecorder) null),
            providerOf((FileStorageService) PdfIngestImageFixture.inMemoryStorage()));
    assertTrue(
        disabled
            .buildSlices(
                PdfIngestImageFixture.pdfBytes(),
                PdfIngestImageFixture.FILENAME,
                PdfIngestImageFixture.IMAGE_CATEGORY,
                PdfIngestImageFixture.EVAL_KB_ID)
            .isEmpty(),
        "总开关关：整条图片旁路关闭");
    assertEquals(0, fake.visionCalls.get(), "关闭时零描述外呼");
    assertEquals(0, fake.embedImageCalls.get(), "关闭时零视觉 embedding 外呼");

    FakeProvider visionOffFake = new FakeProvider();
    PdfImageSliceIngestService visionOff =
        new PdfImageSliceIngestService(
            visionOffFake.provider,
            providerOf(config(true, false)),
            providerOf((TokenUsageRecorder) null),
            providerOf((FileStorageService) PdfIngestImageFixture.inMemoryStorage()));
    List<DocumentChunk> slices =
        visionOff.buildSlices(
            PdfIngestImageFixture.pdfBytes(),
            PdfIngestImageFixture.FILENAME,
            PdfIngestImageFixture.IMAGE_CATEGORY,
            PdfIngestImageFixture.EVAL_KB_ID);
    assertEquals(PdfIngestImageFixture.keys().size(), slices.size(), "视觉路关不影响描述切片（方案 B 照常）");
    assertEquals(0, visionOffFake.embedImageCalls.get(), "视觉开关关：零视觉 embedding 外呼");
    assertTrue(visionOffFake.visionCalls.get() > 0, "描述切片仍走 VLM（此处是桩）");
  }

  @Test
  @DisplayName("判分口径：同图 ≥ 阈值 ∧ 异图 < 阈值 ∧ 间隔 ≥ 0 才判过（阈值两侧各取边界）")
  void threeTypeScoringFollowsCosineThresholds() {
    List<ImageCase> cases =
        PdfIngestImageFixture.cases(PdfIngestImageFixture.fixtureDescriptions());
    Map<String, Probe> passing =
        Map.of(
            "PG-01", new Probe(0.97D, 0.42D, true, false),
            "PG-02", new Probe(0.95D, 0.55D, true, false),
            "PG-03", new Probe(0.96D, 0.61D, true, false));
    Evaluation vision =
        ImageCrossModalEvaluator.evaluate(
            "fake-vision", cases, imageCase -> passing.get(imageCase.id()));
    assertEquals(cases.size(), vision.metrics().caseCount(), "用例数与判分数必须一致");
    assertEquals(1D, vision.metrics().passRate(), "三型判据全过");
    assertEquals(1D, vision.metrics().sameImageHitRate(), "同图余弦全在阈值之上 → 命中率 100%");
    assertEquals(0D, vision.metrics().crossImageFalsePositiveRate(), "异图余弦全在阈值之下 → 误召率 0");
    assertTrue(vision.metrics().meanDiscriminationMargin() > 0D, "同图余弦必须高于异图余弦（间隔为正）");

    Map<String, Probe> degenerate =
        Map.of(
            "PG-01", new Probe(0.85D, 0.93D, false, true),
            "PG-02", new Probe(0.85D, 0.93D, false, true),
            "PG-03", new Probe(0.85D, 0.93D, false, true));
    Evaluation flat =
        ImageCrossModalEvaluator.evaluate(
            "fake-degenerate", cases, imageCase -> degenerate.get(imageCase.id()));
    assertEquals(0D, flat.metrics().passRate(), "同图不达标 + 异图未拒答 → 逐条判过率为 0");
    assertEquals(0D, flat.metrics().sameImageHitRate(), "同图余弦低于阈值 → 不算命中");
    assertEquals(1D, flat.metrics().crossImageFalsePositiveRate(), "异图余弦未低于阈值 → 全部误召");
  }

  @Test
  @DisplayName("跳过口径：未 opt-in 或缺 key 都按假设跳过（整类不执行、构建不失败）")
  void realRunSkipsWithoutOptInOrKey() {
    assertThrows(
        TestAbortedException.class,
        () -> ImageCrossModalBenchmarkIT.requireRealRun(Map.of(), "test-key"),
        "未显式 opt-in 必须跳过");
    assertThrows(
        TestAbortedException.class,
        () ->
            ImageCrossModalBenchmarkIT.requireRealRun(
                Map.of(ImageCrossModalBenchmarkIT.OPT_IN_ENV, "1"), "  "),
        "opt-in 了但没 key 也必须跳过（默认零外呼）");
  }

  /** 合成切片行：主键取自 fixture，其余字段与生产 {@code createChunkEntity} 同源。 */
  private static List<DocumentVectorChunkEntity> childrenOf(List<DocumentChunk> slices) {
    List<DocumentVectorChunkEntity> children = new ArrayList<>(slices.size());
    for (int index = 0; index < slices.size(); index++) {
      DocumentChunk chunk = slices.get(index);
      DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
      entity.setId(
          Long.valueOf(PdfIngestImageFixture.chunkIdOf(PdfIngestImageFixture.keys().get(index))));
      entity.setDocumentId("pdf-ingest-image-fixture-doc");
      entity.setChunkIndex(index);
      entity.setChunkText(chunk.text());
      entity.setCategory(chunk.category());
      entity.setFilename(PdfIngestImageFixture.FILENAME);
      entity.setPageNo(chunk.pageNo());
      entity.setRowIndex(chunk.rowIndex());
      entity.setExtraMetadataJson(ImageSliceMetadata.toJson(chunk.extraMetadata()));
      children.add(entity);
    }
    return children;
  }

  private static UploadedFileEntity file() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId("pdf-ingest-image-fixture-doc");
    file.setOriginalFilename(PdfIngestImageFixture.FILENAME);
    file.setFileType("pdf");
    file.setKnowledgeBase(PdfIngestImageFixture.EVAL_KB_ID);
    return file;
  }

  /** 两态开关矩阵：总开关 / 视觉向量开关。 */
  private static DynamicConfigService config(boolean ingestEnabled, boolean visionEnabled) {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, ingestEnabled);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, visionEnabled);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, PdfIngestImageFixture.keys().size());
    return RagBenchmarkPipelineFactory.dynamicConfig(matrix);
  }

  /** 只读 ObjectProvider 包装单值（null 照原样返回，供"未装配计量"的摄取构造用）。 */
  private static <T> ObjectProvider<T> providerOf(T value) {
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

  /** 两张同尺寸图的像素差异比例（任一通道差 &gt; 24 计为不同像素）。 */
  private static double differingRatio(BufferedImage left, BufferedImage right) {
    int differing = 0;
    for (int y = 0; y < left.getHeight(); y++) {
      for (int x = 0; x < left.getWidth(); x++) {
        int leftRgb = left.getRGB(x, y);
        int rightRgb = right.getRGB(x, y);
        if (channelDiff(leftRgb, rightRgb) > 24) {
          differing++;
        }
      }
    }
    return (double) differing / (left.getWidth() * left.getHeight());
  }

  /** 近白像素比例（三通道均 ≥ 250 计为白）。 */
  private static double whiteRatio(BufferedImage image) {
    int white = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y);
        if (((rgb >> 16) & 0xFF) >= 250 && ((rgb >> 8) & 0xFF) >= 250 && (rgb & 0xFF) >= 250) {
          white++;
        }
      }
    }
    return (double) white / (image.getWidth() * image.getHeight());
  }

  private static int channelDiff(int leftRgb, int rightRgb) {
    int red = Math.abs(((leftRgb >> 16) & 0xFF) - ((rightRgb >> 16) & 0xFF));
    int green = Math.abs(((leftRgb >> 8) & 0xFF) - ((rightRgb >> 8) & 0xFF));
    int blue = Math.abs((leftRgb & 0xFF) - (rightRgb & 0xFF));
    return Math.max(red, Math.max(green, blue));
  }

  /** fake Provider：描述与视觉向量都是确定性桩，调用计数用于证明"零真外呼"。 */
  private static final class FakeProvider {

    private final AtomicInteger visionCalls = new AtomicInteger();

    private final AtomicInteger embedImageCalls = new AtomicInteger();

    private final ModelProvider provider = build();

    private ModelProvider build() {
      ModelProvider mock = Mockito.mock(ModelProvider.class);
      Mockito.when(
              mock.vision(
                  ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class),
                  ArgumentMatchers.any()))
          .thenAnswer(
              invocation ->
                  ModelCallResult.ofText(
                      FAKE_DESCRIPTION + "（第 " + visionCalls.incrementAndGet() + " 张）",
                      "fake-vision",
                      12L,
                      24L,
                      36L));
      Mockito.when(
              mock.embedImage(ArgumentMatchers.any(byte[].class), ArgumentMatchers.anyString()))
          .thenAnswer(
              invocation -> {
                int call = embedImageCalls.incrementAndGet();
                return ModelCallResult.ofVector(
                    new float[] {1F, call, 0.5F, 0.25F}, "fake-vision-embed", 8L, 8L);
              });
      return mock;
    }
  }
}
