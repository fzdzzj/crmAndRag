package com.slz.crm.unit.knowledge.document;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.ChunkHeaderText;
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
import com.slz.crm.platform.audit.GovernanceAuditResult;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 文档入库编排单测（提案4 任务 2.1/2.2，方案05 Chunk Header）。
 *
 * <p>核心契约：嵌入输入 = 上下文头 + 块文本（给碎片块全局视野）；
 * DB {@code chunk_text} 与 {@code VectorRecord.text} 保持原文——嵌入/展示分离。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentIngestionServiceTest {

    private static final UserContext USER = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private UploadedFileMapper uploadedFileMapper;
    @Mock
    private DocumentVectorChunkMapper chunkMapper;
    @Mock
    private KnowledgeBaseAuthorizationService authorizationService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private DocumentService documentService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private CrmVectorStore vectorStore;
    @Mock
    private GovernanceAuditRecorder auditRecorder;

    @BeforeEach
    void setUp() {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(knowledgeBase);
        when(authorizationService.canWrite(any(), eq(USER))).thenReturn(true);
        when(documentService.supports("a.txt")).thenReturn(true);
        when(fileStorageService.store(any(), eq("a.txt"), any())).thenReturn("storage-key-1");
        when(uploadedFileMapper.insert(any())).thenReturn(1);
        when(fileStorageService.open(anyString())).thenAnswer(
                invocation -> new ByteArrayInputStream("file-body".getBytes(StandardCharsets.UTF_8)));
        when(chunkMapper.insert(any(DocumentVectorChunkEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DocumentVectorChunkEntity.class).setId(100L);
            return 1;
        });
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f, 0f});
    }

    private DocumentIngestionService service() {
        return new DocumentIngestionService(knowledgeBaseMapper, uploadedFileMapper, chunkMapper,
                authorizationService, fileStorageService, documentService, embeddingService,
                vectorStore, auditRecorder);
    }

    private DocumentIngestionResult ingest(DocumentChunk... chunks) {
        return service().ingest(new DocumentIngestionCommand(
                1L, USER, "a.txt", "text/plain", 100L, "crm", null,
                new ByteArrayInputStream("file-body".getBytes(StandardCharsets.UTF_8))));
    }

    /** 任务 2.2：EmbeddingService 收到的是带上下文头前缀的文本（mock 捕获入参断言）。 */
    @Test
    void embedReceivesHeaderTextWhileStorageKeepsRawText() throws Exception {
        when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(List.of(
                new DocumentChunk("切片正文一", 0, 1, null, "crm", List.of(), null),
                new DocumentChunk("切片正文二", 1, 1, 3, "crm", List.of(), null)));
        ingest();

        // 嵌入输入带前缀：文件名/类目/页码；Excel 行号锚点用「第n行」
        ArgumentCaptor<String> embedArgs = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(embeddingService, org.mockito.Mockito.times(2)).embed(embedArgs.capture());
        assertThat(embedArgs.getAllValues().get(0))
                .startsWith("【a.txt | crm | 第1页】")
                .contains("切片正文一");
        assertThat(embedArgs.getAllValues().get(1))
                .startsWith("【a.txt | crm | 第3行】")
                .contains("切片正文二");

        // 展示分离：DB chunk_text 与向量记录文本均为原文，不含头前缀
        ArgumentCaptor<DocumentVectorChunkEntity> entities = ArgumentCaptor.forClass(DocumentVectorChunkEntity.class);
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.times(2)).insert(entities.capture());
        assertThat(entities.getAllValues()).extracting(DocumentVectorChunkEntity::getChunkText)
                .containsExactly("切片正文一", "切片正文二");

        ArgumentCaptor<List<VectorRecord>> records = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(vectorStore).upsertAll(records.capture());
        assertThat(records.getValue()).extracting(VectorRecord::text)
                .containsExactly("切片正文一", "切片正文二");
    }

    /** 头构造的锚点分支：行号优先于页码；空类目/空文件名有确定性归一。 */
    @Test
    void headerTextCoversAnchorAndBlankFallbacks() {
        assertThat(ChunkHeaderText.wrap("doc.md", "crm", 2, null, "正文"))
                .isEqualTo("【doc.md | crm | 第2页】\n正文");
        assertThat(ChunkHeaderText.wrap("plan.xlsx", "crm", 1, 7, "正文"))
                .isEqualTo("【plan.xlsx | crm | 第7行】\n正文");
        assertThat(ChunkHeaderText.wrap(null, null, null, null, "正文"))
                .isEqualTo("【未命名 | 未分类】\n正文");
    }

    /** 任务 3.2：语义切分逻辑段（≥2 子块）落一行父块（先行落库），子块挂 parent_chunk_id；父块不嵌入。 */
    @Test
    void semanticSectionPersistsParentRowAndLinksChildren() throws Exception {
        when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(List.of(
                new DocumentChunk("段切片一", 0, 2, null, "crm", List.of(), "第2页逻辑段全文"),
                new DocumentChunk("段切片二", 1, 2, null, "crm", List.of(), "第2页逻辑段全文"),
                new DocumentChunk("独立切片", 2, 3, null, "crm", List.of(), null)));
        ingest();

        ArgumentCaptor<DocumentVectorChunkEntity> inserts = ArgumentCaptor.forClass(DocumentVectorChunkEntity.class);
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.times(4)).insert(inserts.capture());
        List<DocumentVectorChunkEntity> rows = inserts.getAllValues();

        // 父块行先落库（子块要挂它的主键）：role/text/锚点/编号隔离全对齐
        DocumentVectorChunkEntity parent = rows.get(0);
        assertThat(parent.getChunkRole()).isEqualTo("PARENT");
        assertThat(parent.getChunkText()).isEqualTo("第2页逻辑段全文");
        assertThat(parent.getChunkIndex()).as("父块从子块总数+1 起编号，与子块序号空间隔离").isEqualTo(4);
        assertThat(parent.getPageNo()).isEqualTo(2);
        assertThat(parent.getId()).isNotNull();

        assertThat(rows.get(1).getChunkRole()).isEqualTo("CHILD");
        assertThat(rows.get(1).getParentChunkId()).isEqualTo(parent.getId());
        assertThat(rows.get(2).getParentChunkId()).isEqualTo(parent.getId());
        // 单片逻辑段（独立切片）：自身即父块，不挂父块行
        assertThat(rows.get(3).getChunkText()).isEqualTo("独立切片");
        assertThat(rows.get(3).getParentChunkId()).isNull();
        assertThat(rows.get(3).getChunkRole()).isEqualTo("CHILD");

        // 父块是生成单元不是检索单元：只有 3 个子块被嵌入
        org.mockito.Mockito.verify(embeddingService, org.mockito.Mockito.times(3)).embed(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorRecord>> records = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(vectorStore).upsertAll(records.capture());
        assertThat(records.getValue()).hasSize(3);
    }

    /** 任务 3.2：fixed 策略（parentText 恒 null）不产生父块行，行为与升级前一致。 */
    @Test
    void fixedStrategyPersistsNoParentRows() throws Exception {
        when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(List.of(
                new DocumentChunk("切片甲", 0, 1, null, "crm", List.of(), null),
                new DocumentChunk("切片乙", 1, 1, null, "crm", List.of(), null),
                new DocumentChunk("切片丙", 2, 1, null, "crm", List.of(), null)));
        ingest();

        ArgumentCaptor<DocumentVectorChunkEntity> inserts = ArgumentCaptor.forClass(DocumentVectorChunkEntity.class);
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.times(3)).insert(inserts.capture());
        assertThat(inserts.getAllValues())
                .allSatisfy(row -> {
                    assertThat(row.getChunkRole()).isEqualTo("CHILD");
                    assertThat(row.getParentChunkId()).isNull();
                })
                .extracting(DocumentVectorChunkEntity::getChunkIndex)
                .containsExactly(0, 1, 2);
    }

    // ---------------------------------------------------------------- reingest（任务 4.1/4.2/4.3）

    /** 任务 4.2：同一文档连续两次重建，切片集合（文本+锚点+父子关系）完全一致。 */
    @Test
    void reingestIsIdempotentOnChunkSet() throws Exception {
        when(uploadedFileMapper.selectOne(any())).thenReturn(existingFile());
        when(chunkMapper.selectOne(any())).thenReturn(oldRow("doc-9", "crm"));
        when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(List.of(
                new DocumentChunk("段切片一", 0, 2, null, "crm", List.of(), "段全文内容"),
                new DocumentChunk("段切片二", 1, 2, null, "crm", List.of(), "段全文内容"),
                new DocumentChunk("独立切片", 2, 3, null, "crm", List.of(), null)));

        List<Map<String, Object>> firstRun = runReingestCaptureChunkSet();
        List<Map<String, Object>> secondRun = runReingestCaptureChunkSet();

        org.assertj.core.api.Assertions.assertThat(secondRun)
                .as("两次重建的切片集合（文本+锚点+父子关系）必须一致")
                .containsExactlyElementsOf(firstRun);
        // 成功重建落 SUCCESS 审计（任务 4.3）
        org.mockito.Mockito.verify(auditRecorder, org.mockito.Mockito.atLeastOnce()).record(
                org.mockito.ArgumentMatchers.argThat(event ->
                        DocumentIngestionService.REINGEST_EVENT_TYPE.equals(event.eventType())
                                && GovernanceAuditResult.SUCCESS.equals(event.result())
                                && "doc-9".equals(event.targetId())
                                && "user:1".equals(event.actorUserRef())));
    }

    /** 重建失败（嵌入阶段）：物理清切片 + 清向量 + 文档标 FAILED 可重试 + FAILED 审计。 */
    @Test
    void reingestFailureCleansChunksAndVectorsAndMarksFailed() throws Exception {
        when(uploadedFileMapper.selectOne(any())).thenReturn(existingFile());
        when(chunkMapper.selectOne(any())).thenReturn(oldRow("doc-9", "crm"));
        when(documentService.process(any(), eq("a.txt"), eq("crm"))).thenReturn(List.of(
                new DocumentChunk("第一块", 0, 1, null, "crm", List.of(), null),
                new DocumentChunk("第二块", 1, 1, null, "crm", List.of(), null)));
        when(embeddingService.embed(anyString()))
                .thenReturn(new float[]{1f})
                .thenThrow(new IllegalStateException("嵌入服务超时"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().reingest("doc-9", USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("嵌入服务超时");

        // markFailed 清理语义：向量与切片清理（不留半量），文档标 FAILED 且可重试
        org.mockito.Mockito.verify(vectorStore, org.mockito.Mockito.atLeastOnce()).deleteByDocumentId("doc-9");
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.atLeastOnce())
                .deletePhysicallyByDocumentId("doc-9");
        ArgumentCaptor<UploadedFileEntity> updates = ArgumentCaptor.forClass(UploadedFileEntity.class);
        org.mockito.Mockito.verify(uploadedFileMapper, org.mockito.Mockito.atLeastOnce()).updateById(updates.capture());
        assertThat(updates.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(updates.getValue().getErrorMessage()).contains("嵌入服务超时");
        org.mockito.Mockito.verify(auditRecorder).record(org.mockito.ArgumentMatchers.argThat(event ->
                GovernanceAuditResult.FAILED.equals(event.result()) && "doc-9".equals(event.targetId())));
    }

    /** 授权拒绝：无知识库写权限不触碰任何数据，落 DENIED 审计。 */
    @Test
    void reingestWithoutKbWritePermissionIsDeniedAndAudited() {
        when(uploadedFileMapper.selectOne(any())).thenReturn(existingFile());
        when(authorizationService.canWrite(any(), eq(USER))).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().reingest("doc-9", USER))
                .isInstanceOf(SecurityException.class);

        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.never())
                .deletePhysicallyByDocumentId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(vectorStore, org.mockito.Mockito.never()).deleteByDocumentId(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(auditRecorder).record(org.mockito.ArgumentMatchers.argThat(event ->
                GovernanceAuditResult.DENIED.equals(event.result()) && "doc-9".equals(event.targetId())));
    }

    /** 单次重建的切片集合快照：文本+锚点+角色+父块链接（父块经 id 解析为文本），id 序列每次重置保证可比。 */
    private List<Map<String, Object>> runReingestCaptureChunkSet() throws Exception {
        java.util.concurrent.atomic.AtomicInteger idSequence = new java.util.concurrent.atomic.AtomicInteger(100);
        List<DocumentVectorChunkEntity> inserted = new java.util.ArrayList<>();
        when(chunkMapper.insert(any(DocumentVectorChunkEntity.class))).thenAnswer(invocation -> {
            DocumentVectorChunkEntity entity = invocation.getArgument(0);
            entity.setId((long) idSequence.getAndIncrement());
            inserted.add(entity);
            return 1;
        });
        service().reingest("doc-9", USER);
        Map<Long, String> textById = new java.util.HashMap<>();
        for (DocumentVectorChunkEntity entity : inserted) {
            textById.put(entity.getId(), entity.getChunkText());
        }
        return inserted.stream()
                .map(entity -> Map.<String, Object>of(
                        "text", entity.getChunkText(),
                        "pageNo", entity.getPageNo() == null ? -1 : entity.getPageNo(),
                        "rowIndex", entity.getRowIndex() == null ? -1 : entity.getRowIndex(),
                        "chunkIndex", entity.getChunkIndex(),
                        "role", entity.getChunkRole(),
                        "parentText", entity.getParentChunkId() == null ? "" : textById.get(entity.getParentChunkId())))
                .toList();
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

    private DocumentVectorChunkEntity oldRow(String documentId, String category) {
        DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
        row.setId(1L);
        row.setDocumentId(documentId);
        row.setChunkIndex(0);
        row.setChunkText("旧切片");
        row.setCategory(category);
        row.setChunkRole("CHILD");
        return row;
    }
}
