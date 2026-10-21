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
import com.slz.crm.knowledge.document.ImageVectorFlushSupport;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
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
 * multimodal-shared-space 任务 5.1：真视觉向量摄取落库单测（视觉 embedding 全 mock，零外呼零真跑）。
 *
 * <p>覆盖：开关矩阵（描述开 + 视觉关 = 零视觉外呼）/ 视觉向量按切片行回填 documentId+chunkId / payload 与文本口径一致 （sourceType=IMAGE
 * 可回填 SourceReference）/ 独立 image 集合（文本集合零写入）/ 暂存表消费即清 / 视觉 embedding 失败只丢视觉路。
 */
@DisplayName("真视觉向量摄取落库（任务 3.1）")
class ImageVectorFlushSupportTest {

  private static final String LONG_DESCRIPTION =
      "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长。";

  private static final String KB_ID = "5";

  private static final String DOCUMENT_ID = "doc-image-vector";

  private static final float[] VISUAL_VECTOR = {0.1F, 0.2F, 0.3F, 0.4F};

  private final ModelProvider modelProvider = mock(ModelProvider.class);

  private final List<TokenUsageRecord> recorded = new ArrayList<>();

  private final TokenUsageRecorder recorder = recorded::add;

  @Test
  @DisplayName("视觉开关关：描述切片照常，零视觉外呼、image 集合零写入（默认行为逐字节一致）")
  void imageVectorSwitchOff_keepsLegacyBehaviour() {
    InMemoryVectorStore imageStore = new InMemoryVectorStore();
    PdfImageSliceIngestService service =
        service(config(Map.of(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE)));
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 10L, 20L, 30L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(2), "架构图.pdf", "crm", KB_ID);
    int flushed = ImageVectorFlushSupport.flush(file(), children(slices), slices, imageStore);

    assertThat(slices).hasSize(2);
    assertThat(flushed).isZero();
    assertThat(imageStore.search(anyQuery()).isEmpty()).isTrue();
    verify(modelProvider, never()).embedImage(any(byte[].class), any());
  }

  @Test
  @DisplayName("视觉开关开：视觉向量按切片行回填并写独立 image 集合（payload 与文本口径一致）")
  void imageVectorSwitchOn_writesIndependentImageSpace() {
    InMemoryVectorStore imageStore = new InMemoryVectorStore();
    PdfImageSliceIngestService service =
        service(
            config(
                Map.of(
                    PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE,
                    PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE)));
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 10L, 20L, 30L));
    when(modelProvider.embedImage(any(byte[].class), any()))
        .thenReturn(ModelCallResult.ofVector(VISUAL_VECTOR, "multimodal-embedding-v1", 7L, 11L));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(2), "架构图.pdf", "crm", KB_ID);
    List<DocumentVectorChunkEntity> rows = children(slices);
    int flushed = ImageVectorFlushSupport.flush(file(), rows, slices, imageStore);

    assertThat(flushed).isEqualTo(2);
    verify(modelProvider, times(2)).embedImage(any(byte[].class), any());

    List<VectorSearchHit> hits = imageStore.search(anyQuery());
    assertThat(hits).hasSize(2);
    for (VectorSearchHit hit : hits) {
      assertThat(hit.documentId()).isEqualTo(DOCUMENT_ID);
      assertThat(hit.text()).isEqualTo(LONG_DESCRIPTION);
      assertThat(hit.metadata())
          .containsEntry(ImageSliceMetadata.KEY_SOURCE_TYPE, ImageSliceMetadata.SOURCE_TYPE_IMAGE)
          .containsEntry("knowledgeBaseId", KB_ID)
          .containsEntry(ImageSliceMetadata.KEY_IMAGE_ASSET_ID, hit.chunkId());
      assertThat(ImageSliceMetadata.storageKeyOf(hit.metadata())).isNotBlank();
      // chunkId 即描述切片行主键：视觉命中与文本命中同一物理切片时 fusionKey 相同，不同切片永不撞键
      assertThat(rows).extracting(row -> String.valueOf(row.getId())).contains(hit.chunkId());
    }
    // 独立空间：视觉向量只写进显式传入的 image 集合，文本集合不参与本路径（文本向量由 chunkEmbedAndWrite 另行写入）

    // 暂存表消费即清：同一线程再次 flush 不再重复写
    assertThat(ImageVectorFlushSupport.flush(file(), rows, slices, imageStore)).isZero();
    assertThat(imageStore.search(anyQuery())).hasSize(2);
    // 计量口径：视觉 embedding 记 EMBEDDING（与文本 embedding 同类型）
    assertThat(recorded).extracting(TokenUsageRecord::type).contains(TokenUsageType.EMBEDDING);
  }

  @Test
  @DisplayName("视觉 embedding 失败：只丢视觉路，描述切片与文本检索不受影响")
  void imageEmbeddingFailure_degradesToTextRouteOnly() {
    InMemoryVectorStore imageStore = new InMemoryVectorStore();
    PdfImageSliceIngestService service =
        service(
            config(
                Map.of(
                    PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE,
                    PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE)));
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(LONG_DESCRIPTION, "qwen-vl-plus", 1L, 1L, 2L));
    when(modelProvider.embedImage(any(byte[].class), any()))
        .thenThrow(new IllegalStateException("视觉 embedding 端点 500"));

    List<DocumentChunk> slices = service.buildSlices(pdfWithImages(1), "架构图.pdf", "crm", KB_ID);
    int flushed = ImageVectorFlushSupport.flush(file(), children(slices), slices, imageStore);

    assertThat(slices).hasSize(1);
    assertThat(slices.getFirst().text()).isEqualTo(LONG_DESCRIPTION);
    assertThat(flushed).isZero();
    assertThat(imageStore.search(anyQuery())).isEmpty();
  }

  // ---------- 夹具与桩 ----------

  private PdfImageSliceIngestService service(DynamicConfigService config) {
    FileStorageService storage = new InMemoryFileStorageService();
    return new PdfImageSliceIngestService(
        modelProvider, provider(config), provider(recorder), provider(storage));
  }

  private static VectorSearchRequest anyQuery() {
    return new VectorSearchRequest(VISUAL_VECTOR, 10, null, Map.of());
  }

  private static UploadedFileEntity file() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(DOCUMENT_ID);
    file.setKnowledgeBase(KB_ID);
    file.setOriginalFilename("架构图.pdf");
    file.setFileType("pdf");
    return file;
  }

  private static List<DocumentVectorChunkEntity> children(List<DocumentChunk> slices) {
    List<DocumentVectorChunkEntity> rows = new ArrayList<>(slices.size());
    for (int index = 0; index < slices.size(); index++) {
      DocumentChunk chunk = slices.get(index);
      DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
      row.setId(501L + index);
      row.setDocumentId(DOCUMENT_ID);
      row.setChunkIndex(chunk.chunkIndex());
      row.setChunkText(chunk.text());
      row.setCategory(chunk.category());
      row.setPageNo(chunk.pageNo());
      row.setRowIndex(chunk.rowIndex());
      row.setExtraMetadataJson(ImageSliceMetadata.toJson(chunk.extraMetadata()));
      rows.add(row);
    }
    return rows;
  }

  /** 固定值动态配置桩：未命中键返回调用方给的默认值。 */
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
}
