package com.slz.crm.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 摄取内存优化边界测试（fix-minor-debt-family 任务 3.1/3.2）。
 *
 * <p>改造前 {@code ingest} 用 {@code readAllBytes} 无上限缓冲整篇文档再做内容安全评估；改造后「声明尺寸前置校验 +
 * 有界读取」，超限在任何存储/落库/读流动作之前就以 {@link ErrorCode#PARAM_OUT_OF_RANGE} 拒绝。本测试锁三条语义：
 *
 * <ol>
 *   <li>声明超限：连入参流都不读，直接拒（前置校验真的前置）；
 *   <li>有界读取超限：读到上限 + 1 即中止并抛码，不无限扩张缓冲；
 *   <li>正常尺寸：字节内容不变（语义等价，避免「优化」误伤正文）。
 * </ol>
 */
@DisplayName("文档摄取尺寸上限与有界读取")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentIngestionSizeLimitTest {

  private static final UserContext USER = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private UploadedFileMapper uploadedFileMapper;
  @Mock private DocumentVectorChunkMapper chunkMapper;
  @Mock private KnowledgeBaseAuthorizationService authorizationService;
  @Mock private FileStorageService fileStorageService;
  @Mock private DocumentService documentService;
  @Mock private EmbeddingService embeddingService;
  @Mock private CrmVectorStore vectorStore;
  @Mock private GovernanceAuditRecorder auditRecorder;

  private DocumentIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new DocumentIngestionService(
            knowledgeBaseMapper,
            uploadedFileMapper,
            chunkMapper,
            authorizationService,
            fileStorageService,
            documentService,
            embeddingService,
            vectorStore,
            auditRecorder,
            null);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(new KnowledgeBaseEntity());
    when(authorizationService.canWrite(any(), any())).thenReturn(true);
    when(documentService.supports(any())).thenReturn(true);
  }

  @Test
  @DisplayName("声明尺寸超限：不读流、不落存储、不落库，直接 PARAM_OUT_OF_RANGE")
  void declaredOversizeRejectedBeforeAnyReadOrWrite() {
    InputStream unreadable =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("超限文档不得读取入参流（前置尺寸校验失效）");
          }
        };
    DocumentIngestionCommand command =
        new DocumentIngestionCommand(
            1L,
            USER,
            "huge.txt",
            "text/plain",
            DocumentIngestionService.MAX_DOCUMENT_BYTES + 1,
            "crm",
            null,
            unreadable);

    BaseException ex = assertThrows(BaseException.class, () -> service.ingest(command));

    assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_OUT_OF_RANGE.getCode());
    assertThat(ex.getMessage()).contains("最大可摄取大小");
    verify(fileStorageService, never()).store(any(), any(), any());
    verify(uploadedFileMapper, never()).insert(any(UploadedFileEntity.class));
    verify(chunkMapper, never())
        .insert(any(com.slz.crm.knowledge.entity.DocumentVectorChunkEntity.class));
  }

  @Test
  @DisplayName("有界读取：恰好等于上限时放行，超过 1 字节即拒（不无限缓冲）")
  void boundedReadRejectsOnlyBeyondLimit() {
    byte[] payload = new byte[64];
    java.util.Arrays.fill(payload, (byte) 'a');

    byte[] read =
        DocumentIngestionService.readAllBytesBounded(
            new ByteArrayInputStream(payload), payload.length);
    assertThat(read).isEqualTo(payload);

    assertThatThrownBy(
            () ->
                DocumentIngestionService.readAllBytesBounded(
                    new ByteArrayInputStream(payload), payload.length - 1L))
        .isInstanceOf(BaseException.class)
        .satisfies(
            e ->
                assertThat(((BaseException) e).getCode())
                    .isEqualTo(ErrorCode.PARAM_OUT_OF_RANGE.getCode()));
  }

  @Test
  @DisplayName("正常尺寸：有界读取还原原始字节（中文正文不被截断/改写）")
  void boundedReadPreservesNormalContent() {
    byte[] payload = "正常文档正文：客户跟进记录".getBytes(StandardCharsets.UTF_8);

    byte[] read =
        DocumentIngestionService.readAllBytesBounded(
            new ByteArrayInputStream(payload), DocumentIngestionService.MAX_DOCUMENT_BYTES);

    assertThat(read).isEqualTo(payload);
    assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("正常文档正文：客户跟进记录");
  }

  /** 上限与 HTTP 上传闸对齐（100MB），避免摄取侧成为更大的缓冲区。 */
  @Test
  @DisplayName("上限与 HTTP 上传闸对齐（100MB），避免摄取侧成为更大的缓冲区")
  void maxDocumentBytesAlignedWithMultipartLimit() {
    assertThat(DocumentIngestionService.MAX_DOCUMENT_BYTES).isEqualTo(100L * 1024 * 1024);
  }
}
