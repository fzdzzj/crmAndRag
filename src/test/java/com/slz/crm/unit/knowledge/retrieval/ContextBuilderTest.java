package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ContextBuilder 邻居拼装单测（add-context-compression-and-enrichment 任务 1.1/1.3/1.4）：
 * 前/后邻居拼装、首末块与跨文档边界、开关关闭回退升级前行为、DB 异常降级、同页优先。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBuilderTest {
    private static final String DOC_ID = "doc-1";
    private static final String NEIGHBORS_KEY = "rag.context.neighbors";

    @Mock
    private DocumentVectorChunkMapper chunkMapper;

    @Mock
    private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    @Mock
    private DynamicConfigService dynamicConfigService;

    @BeforeEach
    void setUp() {
        // 缺省：配置可用且未设置邻居键 → DynamicConfigService 返回调用方默认值 1（默认开启）
        when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
        when(dynamicConfigService.get(any(), any(), any())).thenAnswer(
                invocation -> invocation.getArgument(2));
    }

    /** 拼装：命中块前后都有邻居 → （前文承接）… 命中 …（后文承接）…，编号与候选下标一致。 */
    @Test
    void shouldAssemblePrevAndNextNeighbors() {
        stubNeighborRows(row(1, "前一页结尾内容", 2L), row(3, "后一页开头内容", 3L));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c2", 2, "命中块正文", 3L)));

        assertThat(context).isEqualTo(
                "[1] （前文承接）前一页结尾内容\n命中块正文\n（后文承接）后一页开头内容");
    }

    /** 边界（任务 1.3）：文档首块无前置邻居，只拼后置。 */
    @Test
    void firstChunkShouldHaveNoPrevNeighbor() {
        stubNeighborRows(row(1, "第二块内容", 1L));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c0", 0, "首块正文", 1L)));

        assertThat(context).doesNotContain("前文承接");
        assertThat(context).startsWith("[1] 首块正文");
        assertThat(context).contains("（后文承接）第二块内容");
    }

    /** 边界（任务 1.3）：文档末块无后置邻居（快照表查不到 index+1 行），只拼前置。 */
    @Test
    void lastChunkShouldHaveNoNextNeighbor() {
        stubNeighborRows(row(4, "倒数第二块内容", 2L));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c5", 5, "末块正文", 2L)));

        assertThat(context).doesNotContain("后文承接");
        assertThat(context).contains("（前文承接）倒数第二块内容");
        assertThat(context).endsWith("末块正文");
    }

    /** 边界（任务 1.3）：快照表返回其他文档的切片行时不得拼入（跨文档不取邻居，双保险过滤）。 */
    @Test
    void neighborFromOtherDocumentMustBeIgnored() {
        DocumentVectorChunkEntity foreign = row(1, "其他文档内容", 1L);
        foreign.setDocumentId("doc-OTHER");
        when(chunkMapper.selectList(any())).thenReturn(List.of(foreign));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c2", 2, "命中块正文", 1L)));

        assertThat(context).doesNotContain("其他文档内容");
        assertThat(context).doesNotContain("前文承接");
        assertThat(context).isEqualTo("[1] 命中块正文");
    }

    /** 开关回退（任务 1.4）：rag.context.neighbors=0 时输出与升级前纯拼接逐字一致。 */
    @Test
    void switchOffShouldFallBackToPlainNumberedContext() {
        when(dynamicConfigService.get(NEIGHBORS_KEY, Integer.class, 1)).thenReturn(0);
        stubNeighborRows(row(1, "前一页结尾内容", 1L), row(3, "后一页开头内容", 2L));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);
        List<RetrievalCandidate> candidates = List.of(
                candidate("c2", 2, "命中块正文", 2L),
                candidate("c9", 9, "  另一块正文  ", 3L));

        String context = builder.build(candidates);

        assertThat(context).isEqualTo("[1] 命中块正文\n[2] 另一块正文");
    }

    /** 同页优先（任务 1.1）：同序号数据异常出现两行时，取与命中块同页的行。 */
    @Test
    void samePageNeighborShouldBePreferredOnDuplicateIndex() {
        DocumentVectorChunkEntity crossPage = row(1, "跨页邻居", 7L);
        crossPage.setId(1L);
        DocumentVectorChunkEntity samePage = row(1, "同页邻居", 2L);
        samePage.setId(2L);
        when(chunkMapper.selectList(any())).thenReturn(List.of(crossPage, samePage));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c2", 2, "命中块正文", 2L)));

        assertThat(context).contains("同页邻居").doesNotContain("跨页邻居");
    }

    /** 失败边界：快照表查询异常 → 无邻居降级，命中块照常输出，不抛错。 */
    @Test
    void dbFailureShouldDegradeToNoNeighbor() {
        when(chunkMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c2", 2, "命中块正文", 1L)));

        assertThat(context).isEqualTo("[1] 命中块正文");
    }

    /** 多命中块编号独立：编号只与候选顺序绑定，邻居缺失不影响后续块编号。 */
    @Test
    void multipleHitsKeepIndependentNumbering() {
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        ContextBuilder builder = new ContextBuilder(chunkMapper, dynamicConfigProvider);

        String context = builder.build(List.of(
                candidate("c2", 2, "命中块A", 1L),
                candidate("c7", 7, "命中块B", 1L)));

        assertThat(context).isEqualTo("[1] 命中块A\n[2] 命中块B");
    }

    private void stubNeighborRows(DocumentVectorChunkEntity... rows) {
        when(chunkMapper.selectList(any())).thenReturn(List.of(rows));
    }

    private RetrievalCandidate candidate(String chunkId, int chunkIndex, String text, long pageNo) {
        Map<String, Object> metadata = Map.of(
                "chunkIndex", chunkIndex,
                "filename", "sales.txt",
                "pageNo", pageNo);
        return new RetrievalCandidate(new VectorSearchHit(chunkId, DOC_ID, 0.8d, text, metadata), 0.8d);
    }

    private DocumentVectorChunkEntity row(int chunkIndex, String text, long pageNo) {
        DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
        entity.setId((long) chunkIndex);
        entity.setDocumentId(DOC_ID);
        entity.setChunkIndex(chunkIndex);
        entity.setChunkText(text);
        entity.setPageNo((int) pageNo);
        return entity;
    }
}
