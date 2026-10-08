package com.slz.crm.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 摄取恢复重放失败分类与流转测试（wire-ingestion-recovery-replay 任务 2.1）。
 *
 * <p>覆盖契约 1 与契约 2：
 *
 * <ul>
 *   <li>熔断开闸失败进 PENDING（ingest 与 reingest 两路径）
 *   <li>非熔断业务异常仍进 FAILED（两路径零回归）
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentIngestionRecoveryReplayTest {

  private static final UserContext USER =
      new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "test-user");

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
  void setUp() throws Exception {
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
            auditRecorder);

    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(1L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    when(authorizationService.canWrite(any(), any())).thenReturn(true);
    when(documentService.supports("test.txt")).thenReturn(true);
    when(fileStorageService.store(any(), anyString(), anyString())).thenReturn("storage-key-1");
    when(fileStorageService.open(anyString()))
        .thenAnswer(inv -> new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
    doAnswer(
            inv -> {
              List<com.slz.crm.knowledge.entity.DocumentVectorChunkEntity> rows =
                  inv.getArgument(0);
              for (com.slz.crm.knowledge.entity.DocumentVectorChunkEntity row : rows) {
                row.setId(100L);
              }
              return rows.size();
            })
        .when(chunkMapper)
        .insertBatch(any());
    when(documentService.process(any(), eq("test.txt"), any()))
        .thenReturn(List.of(new DocumentChunk("content", 0, 1, null, "crm", List.of(), null)));
  }

  @Test
  @DisplayName("ingest: 熔断开闸失败应标记 PENDING 并清理向量与切片")
  void ingestFailsOnCircuitOpenShouldSetPending() {
    DependencyUnavailableException circuitError =
        DependencyUnavailableException.circuitOpen("text-embedding-v3");
    when(embeddingService.embed(anyString())).thenThrow(circuitError);

    DocumentIngestionCommand command =
        new DocumentIngestionCommand(
            1L,
            USER,
            "test.txt",
            "text/plain",
            100L,
            "crm",
            null,
            new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> service.ingest(command)).isInstanceOf(RuntimeException.class);

    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(captor.capture());
    UploadedFileEntity updated = captor.getValue();
    assertThat(updated.getStatus()).isEqualTo("PENDING");
    verify(vectorStore, atLeastOnce()).deleteByDocumentId(anyString());
    verify(chunkMapper).deletePhysicallyByDocumentId(anyString());
  }

  @Test
  @DisplayName("ingest: 普通业务异常应保持标 FAILED")
  void ingestFailsOnBusinessExceptionShouldSetFailed() {
    when(embeddingService.embed(anyString()))
        .thenThrow(new IllegalArgumentException("业务校验失败: 向量维度异常"));

    DocumentIngestionCommand command =
        new DocumentIngestionCommand(
            1L,
            USER,
            "test.txt",
            "text/plain",
            100L,
            "crm",
            null,
            new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> service.ingest(command)).isInstanceOf(IllegalArgumentException.class);

    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(captor.capture());
    UploadedFileEntity updated = captor.getValue();
    assertThat(updated.getStatus()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("reingest: 熔断开闸深层包裹失败应标记 PENDING")
  void reingestFailsOnCircuitOpenWrappedShouldSetPending() {
    UploadedFileEntity existing = new UploadedFileEntity();
    existing.setId(99L);
    existing.setDocumentId("doc-99");
    existing.setKnowledgeBase("1");
    existing.setStorageKey("storage-key-1");
    existing.setOriginalFilename("test.txt");
    existing.setStatus("PROCESSING");
    when(uploadedFileMapper.selectOne(any())).thenReturn(existing);

    RuntimeException wrappedCircuitError =
        new RuntimeException(
            "嵌入调用上层异常", DependencyUnavailableException.circuitOpen("text-embedding-v3"));
    when(embeddingService.embed(anyString())).thenThrow(wrappedCircuitError);

    assertThatThrownBy(() -> service.reingest("doc-99", USER)).isInstanceOf(RuntimeException.class);

    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(captor.capture());
    UploadedFileEntity updated = captor.getValue();
    assertThat(updated.getStatus()).isEqualTo("PENDING");
    verify(vectorStore, atLeastOnce()).deleteByDocumentId("doc-99");
    verify(chunkMapper, atLeastOnce()).deletePhysicallyByDocumentId("doc-99");
  }

  @Test
  @DisplayName("reingest: 普通异常应保持标 FAILED")
  void reingestFailsOnOrdinaryExceptionShouldSetFailed() {
    UploadedFileEntity existing = new UploadedFileEntity();
    existing.setId(99L);
    existing.setDocumentId("doc-99");
    existing.setKnowledgeBase("1");
    existing.setStorageKey("storage-key-1");
    existing.setOriginalFilename("test.txt");
    existing.setStatus("PROCESSING");
    when(uploadedFileMapper.selectOne(any())).thenReturn(existing);

    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("存储不可达普通异常"));

    assertThatThrownBy(() -> service.reingest("doc-99", USER))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(captor.capture());
    UploadedFileEntity updated = captor.getValue();
    assertThat(updated.getStatus()).isEqualTo("FAILED");
  }
}
