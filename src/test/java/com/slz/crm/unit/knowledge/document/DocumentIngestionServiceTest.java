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
import com.slz.crm.knowledge.storage.FileStorageService;
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

    private DocumentIngestionResult ingest(DocumentChunk... chunks) {
        DocumentIngestionService service = new DocumentIngestionService(
                knowledgeBaseMapper, uploadedFileMapper, chunkMapper, authorizationService,
                fileStorageService, documentService, embeddingService, vectorStore);
        return service.ingest(new DocumentIngestionCommand(
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
}
