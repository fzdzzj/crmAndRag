package com.slz.crm.unit.knowledge.document;

import com.slz.crm.knowledge.document.DerivedQuestionService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 衍生问题旁路单测（enhance-query-transformation 任务 3.1–3.3/3.5）：
 * 向量记录关联原块 chunkId 且文本保持原块原文、单块失败退化为普通块、开关关闭零调用、
 * token 计量进 TokenUsageRecorder（LLM=CHAT、嵌入=EMBEDDING，失败也计量）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DerivedQuestionServiceTest {
    private static final Executor DIRECT = Runnable::run;

    @Mock
    private ModelProvider modelProvider;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private CrmVectorStore vectorStore;
    @Mock
    private DocumentVectorChunkMapper chunkMapper;
    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    @Mock
    private DynamicConfigService dynamicConfigService;
    @Mock
    private ObjectProvider<TokenUsageRecorder> usageRecorderProvider;
    @Mock
    private TokenUsageRecorder usageRecorder;

    private UploadedFileEntity file;
    private DocumentVectorChunkEntity chunkOne;
    private DocumentVectorChunkEntity chunkTwo;

    @BeforeEach
    void setUp() {
        file = new UploadedFileEntity();
        file.setDocumentId("doc-1");
        file.setKnowledgeBase("1");
        file.setOriginalFilename("a.txt");
        file.setFileType("txt");
        file.setUserId("user:9");

        chunkOne = child(100L, 0, "片段一正文");
        chunkTwo = child(101L, 1, "片段二正文");
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of(chunkOne, chunkTwo));
        lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
        lenient().when(dynamicConfigService.get(eq("rag.query.derived-questions.enabled"), eq(Boolean.class), eq(false)))
                .thenReturn(true);
        lenient().when(usageRecorderProvider.getIfAvailable()).thenReturn(usageRecorder);
        lenient().when(embeddingService.embedWithUsage(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0, String.class);
            return ModelCallResult.ofVector(new float[]{(float) text.length(), 1f}, "embed-model", 5L, 5L);
        });
    }

    private DocumentVectorChunkEntity child(long id, int chunkIndex, String text) {
        DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
        entity.setId(id);
        entity.setDocumentId("doc-1");
        entity.setChunkIndex(chunkIndex);
        entity.setChunkText(text);
        entity.setCategory("crm");
        entity.setPageNo(1);
        entity.setRowIndex(null);
        return entity;
    }

    private DerivedQuestionService service(Boolean enabled) {
        lenient().when(dynamicConfigService.get(eq("rag.query.derived-questions.enabled"), eq(Boolean.class), eq(false)))
                .thenReturn(enabled);
        return new DerivedQuestionService(modelProvider, embeddingService, vectorStore, chunkMapper,
                dynamicConfigProvider, usageRecorderProvider, DIRECT);
    }

    private void llmReturns(String output) {
        lenient().when(modelProvider.chat(any(), any()))
                .thenReturn(ModelCallResult.ofText(output, "chat-model", 10L, 20L, 30L));
    }

    /** 任务 3.1：每块生成反向问题 → 向量记录 chunkId=原块、text=原块原文、metadata 带衍生标记与问题。 */
    @Test
    void derivedRecordsShouldPointBackToOriginalChunk() {
        llmReturns("这个流程的审批人是谁\n多久能批下来");

        service(true).generateForDocument(file, List.of(chunkOne, chunkTwo));

        ArgumentCaptor<List<VectorRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).upsertAll(captor.capture());
        List<VectorRecord> records = captor.getValue();
        assertEquals(4, records.size(), "2 块 × 每块 2 问");
        for (VectorRecord record : records) {
            assertEquals("doc-1", record.documentId());
            assertEquals("derived_question", record.metadata().get("vectorKind"));
            assertTrue(record.metadata().containsKey("derivedQuestion"));
            assertEquals("1", record.metadata().get("knowledgeBaseId"));
        }
        assertTrue(records.stream().allMatch(record ->
                ("100".equals(record.chunkId()) && "片段一正文".equals(record.text()))
                        || ("101".equals(record.chunkId()) && "片段二正文".equals(record.text()))),
                "chunkId 与 text 必须都取自原块（命中衍生问题即回原块）");
        Set<String> expectedQuestions = Set.of("这个流程的审批人是谁", "多久能批下来");
        for (String chunkId : List.of("100", "101")) {
            List<Object> questions = records.stream()
                    .filter(record -> chunkId.equals(record.chunkId()))
                    .map(record -> record.metadata().get("derivedQuestion"))
                    .toList();
            assertEquals(2, questions.size(), "块 " + chunkId + " 应有 2 个衍生问题");
            assertTrue(expectedQuestions.containsAll(questions), "块 " + chunkId + " 的问句应与 LLM 输出一致");
        }
    }

    /** 任务 3.2：单块 LLM 失败 → 该块退化为普通块，其余块照常生成，且不向调用方抛错。 */
    @Test
    void chunkFailureShouldDegradeOnlyThatChunk() {
        when(modelProvider.chat(any(), any()))
                .thenThrow(new IllegalStateException("模型超载"))
                .thenReturn(ModelCallResult.ofText("第二块的问题", "chat-model", 10L, 20L, 30L));

        assertDoesNotThrow(() -> service(true).generateForDocument(file, List.of(chunkOne, chunkTwo)));

        ArgumentCaptor<List<VectorRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsertAll(captor.capture());
        List<VectorRecord> records = captor.getValue();
        assertEquals(1, records.size());
        assertEquals("101", records.getFirst().chunkId(), "只有存活的块 2 产出衍生向量");
    }

    /** 任务 3.3：LLM 与嵌入消耗进 TokenUsageRecorder（CHAT/EMBEDDING），失败调用也计量。 */
    @Test
    void usageShouldBeRecordedForChatAndEmbedding() {
        llmReturns("问一\n问二");

        service(true).generateForDocument(file, List.of(chunkOne));

        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(usageRecorder, times(3)).record(captor.capture());
        List<TokenUsageRecord> records = captor.getAllValues();
        assertEquals(TokenUsageType.CHAT, records.get(0).type());
        assertEquals("chat-model", records.get(0).model());
        assertEquals(Long.valueOf(30L), records.get(0).totalTokens());
        assertEquals(2, records.stream().filter(record -> record.type() == TokenUsageType.EMBEDDING).count());
        assertTrue(records.stream().allMatch(record -> "user:9".equals(record.userIdRef())
                && Long.valueOf(1L).equals(record.knowledgeBaseId())
                && record.success()));
    }

    /** 任务 3.3 补充：失败调用以 success=false 计量（核算重试成本）。 */
    @Test
    void failedCallsShouldAlsoBeMetered() {
        when(modelProvider.chat(any(), any())).thenThrow(new IllegalStateException("限流"));

        service(true).generateForDocument(file, List.of(chunkOne));

        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(usageRecorder).record(captor.capture());
        assertEquals(TokenUsageType.CHAT, captor.getValue().type());
        assertTrue(captor.getValue().success() == false);
        verify(vectorStore, never()).upsertAll(any());
    }

    /** 任务 3.5：开关关闭（默认）→ 零 LLM 调用、零向量写入。 */
    @Test
    void disabledSwitchShouldProduceNoDerivedVectors() {
        llmReturns("不应被调用");

        service(false).submitAfterIngest(file, List.of(chunkOne, chunkTwo));

        verify(modelProvider, never()).chat(any(), any());
        verify(vectorStore, never()).upsertAll(any());
    }

    /** 竞态护栏：指向已删块的陈旧衍生向量被过滤（reingest 并发窗口）。 */
    @Test
    void staleChunkRecordsShouldBeDropped() {
        llmReturns("问一\n问二");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunkTwo));

        service(true).generateForDocument(file, List.of(chunkOne, chunkTwo));

        ArgumentCaptor<List<VectorRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsertAll(captor.capture());
        assertTrue(captor.getValue().stream().allMatch(record -> "101".equals(record.chunkId())),
                "只保留当前 DB 仍存活的 chunkId");
    }

    /** 提交被拒绝（队列饱和）等价于旁路缺席，不影响调用方。 */
    @Test
    void rejectedSubmissionShouldBeSwallowed() {
        DerivedQuestionService rejecting = new DerivedQuestionService(modelProvider, embeddingService,
                vectorStore, chunkMapper, dynamicConfigProvider, usageRecorderProvider,
                task -> { throw new RejectedExecutionException("队列已满"); });
        llmReturns("问一");

        assertDoesNotThrow(() -> rejecting.submitAfterIngest(file, List.of(chunkOne)));
        verify(modelProvider, never()).chat(any(), any());
    }
}
