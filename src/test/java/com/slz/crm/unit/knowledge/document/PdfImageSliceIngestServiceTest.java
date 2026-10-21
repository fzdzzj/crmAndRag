package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * add-kb-image-slice-retrieval 任务 4.1：PDF 内嵌图片切片摄取单测（VLM 全 mock，零外呼零真跑）。
 *
 * <p>覆盖：默认关零外呼 / 开关开逐图描述切片 + 资产落盘 + 元数据口径 / KB 灰名单 / 每文档图片数帽 / 质量闸门失败不落盘 / 过小装饰图跳过 / 非 PDF 不受影响 /
 * VISION 计量成败都记录。
 */
@DisplayName("PDF 内嵌图片切片摄取（任务组 1）")
class PdfImageSliceIngestServiceTest {

  private static final String LONG_DESCRIPTION =
      "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长。";

  private static final String KB_ID = "5";

  private final ModelProvider modelProvider = mock(ModelProvider.class);
  private final List<TokenUsageRecord> recorded = new ArrayList<>();
  private final TokenUsageRecorder recorder = recorded::add;

  @Test
  @DisplayName("默认关：零 VLM 调用、零切片、零落盘（默认行为零变化）")
  void disabledByDefault_returnsNoSlicesAndNoVisionCall() {
    FileStorageService storage = mock(FileStorageService.class);
    PdfImageSliceIngestService service = service(config(Map.of()), storage);
    byte[] pdf = pdfWithImages(2);

    List<DocumentChunk> slices = service.buildSlices(pdf, "demo.pdf", "crm", KB_ID);

    assertThat(slices).isEmpty();
    verify(modelProvider, never()).vision(any(Prompt.class), any(ModelCallOptions.class));
    verify(storage, never()).store(any(), any(), any());
  }

  @Test
  @DisplayName("开关开：逐图一描述切片，资产落盘且元数据带 storageKey/尺寸，VISION 计量成功")
  void enabled_describesEachImageAndStoresAsset() {
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    PdfImageSliceIngestService service =
        service(config(Map.of(enabledKey(), Boolean.TRUE)), storage);
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 10L, 20L, 30L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(2), "架构图.pdf", "crm", KB_ID);

    assertThat(slices).hasSize(2);
    for (DocumentChunk slice : slices) {
      assertThat(slice.text()).isEqualTo(LONG_DESCRIPTION);
      assertThat(slice.pageNo()).isEqualTo(1);
      assertThat(slice.parentText()).isNull();
      Map<String, Object> extra = slice.extraMetadata();
      assertThat(ImageSliceMetadata.isImageSlice(extra)).isTrue();
      String storageKey = ImageSliceMetadata.storageKeyOf(extra);
      assertThat(storage.exists(storageKey)).isTrue();
      assertThat(extra.get(ImageSliceMetadata.KEY_IMAGE_MIME_TYPE)).isEqualTo("image/png");
      assertThat(extra.get(ImageSliceMetadata.KEY_IMAGE_WIDTH)).isEqualTo(300);
      assertThat(extra.get(ImageSliceMetadata.KEY_IMAGE_HEIGHT)).isEqualTo(200);
    }
    verify(modelProvider, times(2)).vision(any(Prompt.class), any(ModelCallOptions.class));
    assertThat(recorded).hasSize(2);
    assertThat(recorded.get(0).type()).isEqualTo(TokenUsageType.VISION);
    assertThat(recorded.get(0).success()).isTrue();
  }

  @Test
  @DisplayName("KB 灰度白名单不命中：零 VLM 调用、零切片")
  void kbAllowlistMiss_returnsNoSlices() {
    PdfImageSliceIngestService service =
        service(
            config(
                Map.of(
                    enabledKey(),
                    Boolean.TRUE,
                    PdfImageSliceIngestService.KB_ALLOWLIST_KEY,
                    "7, 9")),
            new InMemoryFileStorageService());

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(1), "demo.pdf", "crm", KB_ID);

    assertThat(slices).isEmpty();
    verify(modelProvider, never()).vision(any(Prompt.class), any(ModelCallOptions.class));
  }

  @Test
  @DisplayName("KB 灰度白名单命中（含空格分隔）：按命中放行")
  void kbAllowlistHit_proceedsIngest() {
    PdfImageSliceIngestService service =
        service(
            config(
                Map.of(
                    enabledKey(),
                    Boolean.TRUE,
                    PdfImageSliceIngestService.KB_ALLOWLIST_KEY,
                    "7,5")),
            new InMemoryFileStorageService());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 1L, 1L, 2L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(1), "demo.pdf", "crm", KB_ID);

    assertThat(slices).hasSize(1);
  }

  @Test
  @DisplayName("每文档图片数帽：2 张图 + 帽=1 时只处理 1 张（默认帽 5 见默认值单测）")
  void maxImagesCap_limitsProcessedImages() {
    PdfImageSliceIngestService service =
        service(
            config(
                Map.of(enabledKey(), Boolean.TRUE, PdfImageSliceIngestService.MAX_IMAGES_KEY, 1)),
            new InMemoryFileStorageService());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 1L, 1L, 2L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(2), "demo.pdf", "crm", KB_ID);

    assertThat(slices).hasSize(1);
    verify(modelProvider, times(1)).vision(any(Prompt.class), any(ModelCallOptions.class));
  }

  @Test
  @DisplayName("默认帽与硬化上限：未配置=5，配置越界按 20 封顶")
  void maxImagesCapDefaultsAndHardLimit() {
    assertThat(service(config(Map.of()), mock(FileStorageService.class)).resolveMaxImages())
        .isEqualTo(5);
    assertThat(
            service(
                    config(Map.of(PdfImageSliceIngestService.MAX_IMAGES_KEY, 999)),
                    mock(FileStorageService.class))
                .resolveMaxImages())
        .isEqualTo(20);
    assertThat(
            service(
                    config(Map.of(PdfImageSliceIngestService.MAX_IMAGES_KEY, 0)),
                    mock(FileStorageService.class))
                .resolveMaxImages())
        .isEqualTo(5);
  }

  @Test
  @DisplayName("质量闸门失败：不落盘、不产切片，但计量仍记录 success=false")
  void descriptionFailsGate_dropsSliceAndSkipsStore() {
    FileStorageService storage = mock(FileStorageService.class);
    PdfImageSliceIngestService service =
        service(config(Map.of(enabledKey(), Boolean.TRUE)), storage);
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("太短", "qwen-vl-plus", 1L, 1L, 2L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(1), "demo.pdf", "crm", KB_ID);

    assertThat(slices).isEmpty();
    verify(storage, never()).store(any(), any(), any());
    assertThat(recorded).hasSize(1);
    assertThat(recorded.get(0).success()).isFalse();
  }

  @Test
  @DisplayName("VLM 调用异常：跳过该图不抛出，解析链不受影响")
  void visionFailure_isSwallowed() {
    PdfImageSliceIngestService service =
        service(config(Map.of(enabledKey(), Boolean.TRUE)), new InMemoryFileStorageService());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenThrow(new IllegalStateException("provider down"));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(1), "demo.pdf", "crm", KB_ID);

    assertThat(slices).isEmpty();
    assertThat(recorded).hasSize(1);
    assertThat(recorded.get(0).success()).isFalse();
  }

  @Test
  @DisplayName("非 PDF 文件：开关开也不提取（Office 等格式留后续）")
  void nonPdfFile_isIgnored() {
    PdfImageSliceIngestService service =
        service(config(Map.of(enabledKey(), Boolean.TRUE)), new InMemoryFileStorageService());

    List<DocumentChunk> slices =
        service.buildSlices("hello".getBytes(), "notes.docx", "crm", KB_ID);

    assertThat(slices).isEmpty();
    verify(modelProvider, never()).vision(any(Prompt.class), any(ModelCallOptions.class));
  }

  @Test
  @DisplayName("过小图片对象（装饰/图标）跳过，只处理可见内容图")
  void tinyImagesAreSkipped() throws Exception {
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    PdfImageSliceIngestService service =
        service(config(Map.of(enabledKey(), Boolean.TRUE)), storage);
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 1L, 1L, 2L));

    List<DocumentChunk> slices =
        service.buildSlices(pdfWithTinyAndNormalImage(), "demo.pdf", "crm", KB_ID);

    assertThat(slices).hasSize(1);
    verify(modelProvider, times(1)).vision(any(Prompt.class), any(ModelCallOptions.class));
  }

  // ---------- 夹具与桩 ----------

  private PdfImageSliceIngestService service(
      DynamicConfigService config, FileStorageService storage) {
    return new PdfImageSliceIngestService(
        modelProvider, provider(config), provider(recorder), provider(storage));
  }

  private static String enabledKey() {
    return PdfImageSliceIngestService.ENABLED_KEY;
  }

  /** 固定值动态配置桩：未命中键返回调用方给的默认值（与生产 DynamicConfigService 语义一致）。 */
  private static DynamicConfigService config(Map<String, Object> values) {
    return new DynamicConfigService() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T get(String key, Class<T> type, T defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : (T) value;
      }
    };
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  /** 造 1 页 PDF：贴 n 张 300x200 的可辨认位图（无文本层）。 */
  private static byte[] pdfWithImages(int count) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          for (int index = 0; index < count; index++) {
            PDImageXObject xobject = LosslessFactory.createFromImage(document, diagram(index));
            content.drawImage(xobject, 40, 120 + index * 260, 300, 200);
          }
        }
        document.save(out);
      }
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("夹具 PDF 生成失败", exception);
    }
  }

  /** 造 1 页 PDF：一张 20x20 装饰图 + 一张 300x200 内容图。 */
  private static byte[] pdfWithTinyAndNormalImage() {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          PDImageXObject tiny =
              LosslessFactory.createFromImage(document, solid(20, 20, Color.GRAY));
          content.drawImage(tiny, 10, 700, 20, 20);
          PDImageXObject normal =
              LosslessFactory.createFromImage(document, solid(300, 200, Color.WHITE));
          content.drawImage(normal, 40, 120, 300, 200);
        }
        document.save(out);
      }
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("夹具 PDF 生成失败", exception);
    }
  }

  private static BufferedImage diagram(int seed) {
    BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 300, 200);
      graphics.setColor(new Color(0x2C, 0x6D, 0xB4));
      graphics.fillRoundRect(10 + seed * 5, 20, 120, 60, 16, 16);
      graphics.setColor(Color.BLACK);
      graphics.drawLine(20, 140, 280, 140 + seed);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static BufferedImage solid(int width, int height, Color color) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(color);
      graphics.fillRect(0, 0, width, height);
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
