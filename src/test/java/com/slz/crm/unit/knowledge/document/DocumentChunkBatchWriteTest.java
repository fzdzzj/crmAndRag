package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentIngestionCommand;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 切片批次写库的形状与故障注入单测（update-document-chunk-write-batching 任务 2.x）。
 *
 * <p>口径分野（硬约束）：<b>逻辑行</b> = Mapper 收到的行数，必须等于切片数；<b>受限批次调用次数</b> = 按固定上限切分后的 {@code insertBatch}
 * 调用次数，可观测且被断言。真实数据库执行次数、网络往返与 commit 次数 <b>在本单测不可观测</b>，一律标 unknown——不得由 Mapper 调用次数反推物理写入次数。
 *
 * <p>覆盖：24 子块单批、大文档拆批（&gt;64）、语义父块批先行、生成键不完整失败、批次中途失败、 embedding 失败、Qdrant upsert
 * 失败、失败后重试、清理子操作自身失败不得误报成功。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentChunkBatchWriteTest {

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

  /** 每次 insertBatch 调用的行快照（生产实现复用同一批缓冲并在写入后 clear，故必须按调用快照）。 */
  private final List<List<DocumentVectorChunkEntity>> batches = new ArrayList<>();

  private final AtomicLong idSequence = new AtomicLong(1000);

  @BeforeEach
  void setUp() {
    KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setId(1L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(knowledgeBase);
    when(authorizationService.canWrite(any(), eq(USER))).thenReturn(true);
    when(documentService.supports("a.txt")).thenReturn(true);
    when(fileStorageService.store(any(), eq("a.txt"), any())).thenReturn("storage-key-1");
    when(uploadedFileMapper.insert(any())).thenReturn(1);
    when(fileStorageService.open(anyString()))
        .thenAnswer(
            invocation -> new ByteArrayInputStream("file-body".getBytes(StandardCharsets.UTF_8)));
    when(embeddingService.embed(anyString())).thenReturn(new float[] {1f, 0f});
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
  }

  /** 正常批写桩：按行序逐行回填自增主键并快照本批实际写入行。 */
  private void stubBatchedBackfill() {
    batches.clear();
    idSequence.set(1000);
    doAnswer(
            invocation -> {
              List<DocumentVectorChunkEntity> rows = invocation.getArgument(0);
              batches.add(new ArrayList<>(rows));
              for (DocumentVectorChunkEntity row : rows) {
                row.setId(idSequence.getAndIncrement());
              }
              return rows.size();
            })
        .when(chunkMapper)
        .insertBatch(any());
  }

  private List<DocumentVectorChunkEntity> logicalRows() {
    return batches.stream().flatMap(List::stream).toList();
  }

  private List<Integer> batchSizes() {
    return batches.stream().map(List::size).toList();
  }

  private void stubIngestChunks(List<DocumentChunk> chunks) throws Exception {
    when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(chunks);
  }

  private DocumentIngestionResult ingest() {
    return service.ingest(
        new DocumentIngestionCommand(
            1L,
            USER,
            "a.txt",
            "text/plain",
            100L,
            "crm",
            null,
            new ByteArrayInputStream("file-body".getBytes(StandardCharsets.UTF_8))));
  }

  /** 固定策略切片：无 parentText。 */
  private static List<DocumentChunk> fixedChunks(int count) {
    List<DocumentChunk> chunks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      chunks.add(new DocumentChunk("切片正文-" + i, i, 1, null, "crm", List.of(), null));
    }
    return chunks;
  }

  /** 单个逻辑段（parentText 逐字相同）切成 count 个子块。 */
  private static List<DocumentChunk> singleParentRun(int count) {
    List<DocumentChunk> chunks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      chunks.add(new DocumentChunk("段内切片-" + i, i, 1, null, "crm", List.of(), "段全文"));
    }
    return chunks;
  }

  @Test
  void twentyFourChildChunksUseSingleBoundedBatchAndKeepLogicalRows() throws Exception {
    stubBatchedBackfill();
    stubIngestChunks(fixedChunks(24));

    DocumentIngestionResult result = ingest();

    assertThat(result.chunkCount()).isEqualTo(24);
    // 24 逻辑行 = 1 次受限批次调用（上界 64 未触发切分）
    verify(chunkMapper, times(1)).insertBatch(any());
    assertThat(batchSizes()).containsExactly(24);
    assertThat(logicalRows()).hasSize(24);
    for (int i = 0; i < 24; i++) {
      DocumentVectorChunkEntity row = logicalRows().get(i);
      assertThat(row.getId()).as("第 " + i + " 行生成键必须逐行回填").isNotNull();
      assertThat(row.getChunkIndex()).isEqualTo(i);
      assertThat(row.getChunkRole()).isEqualTo("CHILD");
      assertThat(row.getParentChunkId()).isNull();
    }

    ArgumentCaptor<List<VectorRecord>> records = vectorRecords();
    verify(vectorStore).upsertAll(records.capture());
    assertThat(records.getValue()).hasSize(24);
    for (VectorRecord record : records.getValue()) {
      assertThat(record.chunkId())
          .as("向量 chunkId 必须引用子块 DB 主键")
          .isEqualTo(String.valueOf(logicalRows().get(record.chunkIndex()).getId()));
    }
  }

  @Test
  void largeDocumentSplitsIntoBoundedBatchesWithoutLosingRows() throws Exception {
    stubBatchedBackfill();
    stubIngestChunks(fixedChunks(150));

    ingest();

    // 150 逻辑行 → 64 + 64 + 22 三次受限批次调用（固定上界，无无界参数）
    verify(chunkMapper, times(3)).insertBatch(any());
    assertThat(batchSizes()).containsExactly(64, 64, 22);
    assertThat(logicalRows()).hasSize(150);
    for (int i = 0; i < 150; i++) {
      assertThat(logicalRows().get(i).getId()).as("跨批次第 " + i + " 行生成键").isNotNull();
      assertThat(logicalRows().get(i).getChunkIndex()).isEqualTo(i);
    }
  }

  @Test
  void semanticParentBatchIsFlushedBeforeChildBatches() throws Exception {
    stubBatchedBackfill();
    // 一个逻辑段含 100 个子块：父块批必须先落库（否则子块无处挂 parent_chunk_id），子块再分 64/36 两批
    stubIngestChunks(singleParentRun(100));

    ingest();

    verify(chunkMapper, times(3)).insertBatch(any());
    assertThat(batchSizes()).containsExactly(1, 64, 36);
    DocumentVectorChunkEntity parent = batches.get(0).get(0);
    assertThat(parent.getChunkRole()).isEqualTo("PARENT");
    assertThat(parent.getChunkText()).isEqualTo("段全文");
    assertThat(parent.getChunkIndex()).isEqualTo(101);
    // 逻辑行口径区分：批内共 101 行（100 子块 + 1 父块），其中子块 100 行
    assertThat(logicalRows()).hasSize(101);
    List<DocumentVectorChunkEntity> children =
        logicalRows().stream().filter(row -> "CHILD".equals(row.getChunkRole())).toList();
    assertThat(children).hasSize(100);
    long minChildId =
        children.stream().mapToLong(DocumentVectorChunkEntity::getId).min().orElseThrow();
    assertThat(parent.getId()).as("父块必须先于全部子块取得 ID").isLessThan(minChildId);
    assertThat(children)
        .allSatisfy(row -> assertThat(row.getParentChunkId()).isEqualTo(parent.getId()));

    ArgumentCaptor<List<VectorRecord>> records = vectorRecords();
    verify(vectorStore).upsertAll(records.capture());
    assertThat(records.getValue()).as("父块不嵌入：向量只覆盖 100 个子块").hasSize(100);
  }

  @Test
  void incompleteGeneratedKeysFailFastAndNeverReachVectorWrite() throws Exception {
    batches.clear();
    when(chunkMapper.insertBatch(any()))
        .thenAnswer(
            invocation -> {
              List<DocumentVectorChunkEntity> rows = invocation.getArgument(0);
              // 故意漏最后一行生成键：必须按「生成键不完整」失败，不得静默继续
              for (int i = 0; i < rows.size() - 1; i++) {
                rows.get(i).setId(1000L + i);
              }
              return rows.size();
            });
    stubIngestChunks(fixedChunks(24));

    assertThatThrownBy(this::ingest)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("切片批次主键回填不完整");

    verify(vectorStore, never()).upsertAll(any());
    assertFailedAndCleaned();
  }

  @Test
  void midBatchFailureIsVisibleAndCleansUp() throws Exception {
    batches.clear();
    when(chunkMapper.insertBatch(any()))
        .thenAnswer(
            invocation -> {
              List<DocumentVectorChunkEntity> rows = invocation.getArgument(0);
              if (batches.isEmpty()) {
                batches.add(new ArrayList<>(rows));
                for (DocumentVectorChunkEntity row : rows) {
                  row.setId(idSequence.getAndIncrement());
                }
                return rows.size();
              }
              throw new IllegalStateException("批次写库中途失败");
            });
    // 150 子块：第二批（第 65 行起）中途失败，失败必须可见
    stubIngestChunks(fixedChunks(150));

    assertThatThrownBy(this::ingest)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("批次写库中途失败");

    verify(vectorStore, never()).upsertAll(any());
    assertFailedAndCleaned();
  }

  @Test
  void embeddingFailureCleansUpChunksWrittenInBatch() throws Exception {
    stubBatchedBackfill();
    stubIngestChunks(fixedChunks(24));
    when(embeddingService.embed(anyString()))
        .thenReturn(new float[] {1f})
        .thenThrow(new IllegalStateException("嵌入服务超时"));

    assertThatThrownBy(this::ingest)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("嵌入服务超时");

    verify(chunkMapper, times(1)).insertBatch(any());
    verify(vectorStore, never()).upsertAll(any());
    assertFailedAndCleaned();
  }

  @Test
  void qdrantUpsertFailureCleansUpChunksAndVectors() throws Exception {
    stubBatchedBackfill();
    stubIngestChunks(fixedChunks(24));
    doThrow(new IllegalStateException("向量库不可达")).when(vectorStore).upsertAll(any());

    assertThatThrownBy(this::ingest)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("向量库不可达");

    assertFailedAndCleaned();
  }

  @Test
  void ingestBatchFailureThenRetrySucceeds() throws Exception {
    batches.clear();
    when(chunkMapper.insertBatch(any())).thenThrow(new IllegalStateException("批次写库失败"));
    stubIngestChunks(fixedChunks(24));

    assertThatThrownBy(this::ingest).hasMessageContaining("批次写库失败");
    assertFailedAndCleaned();

    // 失败后重试（同条件重跑）：清理语义保证可重试，重试必须写全 24 子块
    stubBatchedBackfill();
    DocumentIngestionResult retried = ingest();
    assertThat(retried.chunkCount()).isEqualTo(24);
    assertThat(logicalRows()).hasSize(24);
    assertThat(batchSizes()).containsExactly(24);
  }

  @Test
  void reingestBatchFailureCleansAndRetrySucceeds() throws Exception {
    when(uploadedFileMapper.selectOne(any())).thenReturn(existingFile());
    when(chunkMapper.selectOne(any())).thenReturn(oldRow());
    stubIngestChunks(fixedChunks(24));

    batches.clear();
    when(chunkMapper.insertBatch(any())).thenThrow(new IllegalStateException("重建批次写库失败"));
    assertThatThrownBy(() -> service.reingest("doc-9", USER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("重建批次写库失败");
    verify(chunkMapper, atLeastOnce()).deletePhysicallyByDocumentId("doc-9");
    verify(vectorStore, atLeastOnce()).deleteByDocumentId("doc-9");

    stubBatchedBackfill();
    DocumentIngestionResult retried = service.reingest("doc-9", USER);
    assertThat(retried.chunkCount()).isEqualTo(24);
    assertThat(logicalRows()).hasSize(24);
  }

  @Test
  void cleanupSubOperationFailureMustNotBeReportedAsSuccess() throws Exception {
    stubBatchedBackfill();
    stubIngestChunks(fixedChunks(24));
    when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("嵌入失败"));
    // 清理子操作自身失败（物理清切片抛异常）：markFailed 各自吞异常不阻断失败标记，
    // 但绝不改变「整体仍是失败」这一事实——不得误报成功，也不得假称零残留
    doThrow(new IllegalStateException("清理切片失败"))
        .when(chunkMapper)
        .deletePhysicallyByDocumentId(anyString());

    assertThatThrownBy(this::ingest)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("嵌入失败");

    ArgumentCaptor<UploadedFileEntity> updates = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(updates.capture());
    assertThat(updates.getValue().getStatus()).as("清理失败不得把失败改成成功").isEqualTo("FAILED");
    assertThat(updates.getValue().getErrorMessage()).contains("嵌入失败");
    verify(vectorStore, never()).upsertAll(any());
  }

  /** 失败可见性统一断言：物理清切片 + 清向量 + 文档标 FAILED（沿用既有分别尝试/失败告警语义）。 */
  private void assertFailedAndCleaned() {
    verify(chunkMapper, atLeastOnce()).deletePhysicallyByDocumentId(anyString());
    verify(vectorStore, atLeastOnce()).deleteByDocumentId(anyString());
    ArgumentCaptor<UploadedFileEntity> updates = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper, atLeastOnce()).updateById(updates.capture());
    assertThat(updates.getValue().getStatus()).isEqualTo("FAILED");
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<List<VectorRecord>> vectorRecords() {
    return ArgumentCaptor.forClass(List.class);
  }

  private UploadedFileEntity existingFile() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(55L);
    file.setDocumentId("doc-9");
    file.setFilename("a.txt");
    file.setOriginalFilename("a.txt");
    file.setFileType("txt");
    file.setStorageKey("storage-key-9");
    file.setKnowledgeBase("1");
    file.setStatus("COMPLETED");
    return file;
  }

  private DocumentVectorChunkEntity oldRow() {
    DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
    row.setId(1L);
    row.setDocumentId("doc-9");
    row.setChunkIndex(0);
    row.setChunkText("旧切片");
    row.setCategory("crm");
    row.setChunkRole("CHILD");
    return row;
  }
}
