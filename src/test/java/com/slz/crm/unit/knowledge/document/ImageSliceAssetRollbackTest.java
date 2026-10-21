package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentIngestionCommand;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * d8-image-asset-rollback 单测：图片摄取失败孤儿对象即时回滚（VLM/向量全 mock，零真外呼）。
 *
 * <p>四条契约：失败只删本次新落盘 key / 成功零额外删除 / 回滚自身删除异常不吞原始异常 / 空集合零调用； 另覆盖收集作用域边界（仅 store()
 * 成功且返回非空者入集合，作用域外不登记）。
 */
@DisplayName("图片摄取失败孤儿对象即时回滚（d8）")
class ImageSliceAssetRollbackTest {

  private static final UserContext USER = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");

  private static final String PAPER = "demo.pdf";

  private static final String DOC_KEY = "doc-key";

  private static final String IMAGE_KEY = "img-key-1";

  private static final String DESCRIPTION = "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长。";

  private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
  private final UploadedFileMapper uploadedFileMapper = mock(UploadedFileMapper.class);
  private final DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
  private final KnowledgeBaseAuthorizationService authorizationService =
      mock(KnowledgeBaseAuthorizationService.class);
  private final FileStorageService storage = mock(FileStorageService.class);
  private final DocumentService documentService = mock(DocumentService.class);
  private final EmbeddingService embeddingService = mock(EmbeddingService.class);
  private final CrmVectorStore vectorStore = mock(CrmVectorStore.class);
  private final GovernanceAuditRecorder auditRecorder = mock(GovernanceAuditRecorder.class);
  private final ModelProvider modelProvider = mock(ModelProvider.class);

  @BeforeEach
  void setUp() throws Exception {
    KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setId(1L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(knowledgeBase);
    when(authorizationService.canWrite(any(), eq(USER))).thenReturn(true);
    when(documentService.supports(PAPER)).thenReturn(true);
    when(documentService.process(any(), eq(PAPER), eq("crm"))).thenReturn(List.of(textChunk()));
    when(documentService.processOrEmpty(any(), eq(PAPER), eq("crm")))
        .thenReturn(List.of(textChunk()));
    when(storage.store(any(), eq(PAPER), any())).thenReturn(DOC_KEY);
    when(storage.store(any(), startsWith("image-"), eq("image/png"))).thenReturn(IMAGE_KEY);
    when(storage.open(anyString()))
        .thenAnswer(
            invocation -> new ByteArrayInputStream("stored".getBytes(StandardCharsets.UTF_8)));
    when(uploadedFileMapper.insert(any())).thenReturn(1);
    when(uploadedFileMapper.updateById(any())).thenReturn(1);
    when(chunkMapper.insertBatch(any()))
        .thenAnswer(
            invocation -> {
              List<DocumentVectorChunkEntity> rows = invocation.getArgument(0);
              for (int index = 0; index < rows.size(); index++) {
                rows.get(index).setId(100L + index);
              }
              return rows.size();
            });
    when(embeddingService.embed(anyString())).thenReturn(new float[] {1f, 0f});
  }

  @Test
  @DisplayName("失败分支：只删本次新落盘的图片对象，原始异常照原样上抛")
  void failureDeletesOnlyStagedImageKeys() {
    stubVision();
    IllegalStateException embeddingFailure = new IllegalStateException("嵌入服务超时");
    when(embeddingService.embed(anyString())).thenThrow(embeddingFailure);

    DocumentIngestionService service = service(imageService(true));

    assertThatThrownBy(() -> ingest(service)).isSameAs(embeddingFailure);

    verify(storage).delete(IMAGE_KEY);
    verify(storage, never()).delete(DOC_KEY);
    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper).updateById(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("成功分支：零额外删除，图片描述切片照常入库")
  void successPathDeletesNothing() {
    stubVision();
    DocumentIngestionService service = service(imageService(true));

    DocumentIngestionResult result = ingest(service);

    assertThat(result.chunkCount()).isEqualTo(2);
    verify(storage).store(any(), startsWith("image-"), eq("image/png"));
    verify(storage, never()).delete(anyString());
  }

  @Test
  @DisplayName("回滚自身删除异常：只 WARN 不吞，原始异常仍是同一个实例")
  void rollbackDeleteFailureKeepsOriginalException() {
    stubVision();
    doThrow(new IllegalStateException("对象存储不可用")).when(storage).delete(IMAGE_KEY);
    IllegalStateException embeddingFailure = new IllegalStateException("嵌入服务超时");
    when(embeddingService.embed(anyString())).thenThrow(embeddingFailure);

    DocumentIngestionService service = service(imageService(true));

    assertThatThrownBy(() -> ingest(service)).isSameAs(embeddingFailure);

    verify(storage).delete(IMAGE_KEY);
  }

  @Test
  @DisplayName("空集合：旁路未落盘时零删除调用（原始异常照常上抛）")
  void emptyCollectionCallsDeleteNever() {
    IllegalStateException embeddingFailure = new IllegalStateException("嵌入服务超时");
    when(embeddingService.embed(anyString())).thenThrow(embeddingFailure);

    DocumentIngestionService service = service(imageService(false));

    assertThatThrownBy(() -> ingest(service)).isSameAs(embeddingFailure);

    verify(storage, never()).delete(anyString());
    verify(storage, never()).store(any(), startsWith("image-"), any());
  }

  @Test
  @DisplayName("收集作用域：仅 store() 成功且返回非空者入集合，关闭后再开为空")
  void collectionScopeTracksOnlySuccessfulStores() {
    stubVision();
    PdfImageSliceIngestService imageService = imageService(true);

    Set<String> collected = PdfImageSliceIngestService.beginAssetCollection();
    try {
      imageService.buildSlices(pdfWithImage(), PAPER, "crm", "5");
    } finally {
      PdfImageSliceIngestService.endAssetCollection();
    }
    // 作用域外：不再向已关闭的集合登记（线程复用时也不串味）
    imageService.buildSlices(pdfWithImage(), PAPER, "crm", "5");
    Set<String> reopened = PdfImageSliceIngestService.beginAssetCollection();
    PdfImageSliceIngestService.endAssetCollection();

    assertThat(collected).containsExactly(IMAGE_KEY);
    assertThat(reopened).isEmpty();
  }

  // ---------- 夹具与桩 ----------

  private void stubVision() {
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(DESCRIPTION, "qwen-vl-plus", 1L, 1L, 2L));
  }

  private DocumentIngestionService service(PdfImageSliceIngestService imageService) {
    DocumentIngestionService service =
        new DocumentIngestionService(
            knowledgeBaseMapper,
            uploadedFileMapper,
            chunkMapper,
            authorizationService,
            storage,
            documentService,
            embeddingService,
            vectorStore,
            auditRecorder);
    ReflectionTestUtils.setField(service, "imageSliceIngestService", imageService);
    return service;
  }

  private DocumentIngestionResult ingest(DocumentIngestionService service) {
    return service.ingest(
        new DocumentIngestionCommand(
            1L,
            USER,
            PAPER,
            "application/pdf",
            100L,
            "crm",
            null,
            new ByteArrayInputStream(pdfWithImage())));
  }

  /** 图片切片旁路（开关按参数给人；VLM 与计量全桩，存储用共享 mock）。 */
  private PdfImageSliceIngestService imageService(boolean enabled) {
    Map<String, Object> values =
        enabled ? Map.of(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE) : Map.of();
    DynamicConfigService config =
        new DynamicConfigService() {
          @Override
          @SuppressWarnings("unchecked")
          public <T> T get(String key, Class<T> type, T defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (T) value;
          }
        };
    TokenUsageRecorder recorder = usage -> {};
    return new PdfImageSliceIngestService(
        modelProvider, provider(config), provider(recorder), provider(storage));
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private static DocumentChunk textChunk() {
    return new DocumentChunk("合同正文第一段，含服务范围与结算条款。", 0, 1, null, "crm", List.of(), null);
  }

  /** 造 1 页 PDF：贴 1 张 300x200 的可辨认位图（无文本层）。 */
  private static byte[] pdfWithImage() {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          PDImageXObject xobject = LosslessFactory.createFromImage(document, diagram());
          content.drawImage(xobject, 40, 120, 300, 200);
        }
        document.save(out);
      }
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("夹具 PDF 生成失败", exception);
    }
  }

  private static BufferedImage diagram() {
    BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 300, 200);
      graphics.setColor(new Color(0x2C, 0x6D, 0xB4));
      graphics.fillRoundRect(10, 20, 120, 60, 16, 16);
      graphics.setColor(Color.BLACK);
      graphics.drawLine(20, 140, 280, 140);
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
